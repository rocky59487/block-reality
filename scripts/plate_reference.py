#!/usr/bin/env python3
"""Independent reference for the clamped square plate coefficients.

    python3 scripts/plate_reference.py

Nothing here touches FrameCore, MITC4 or verify.py: 13-point finite differences on the
biharmonic operator, q = D = a = 1, so the results ARE the coefficients. It exists
because verify.py and evidence.py both compare against a centre coefficient that is NOT
the tabulated one, and a claim like that has to be checkable in one command.

Expected output (Richardson-extrapolated from n = 20/40/80):

    w_max     0.00126532   table 0.00126   agrees
    M_edge   -0.05133376   table -0.0513   agrees
    M_centre  0.02290512   table 0.0231    DISAGREES by 0.85%

Two of three reproduce the table to every digit it prints; the third does not, and
three-significant-figure rounding can only carry +-0.217%.
"""
import numpy as np
from scipy.sparse import lil_matrix, csr_matrix
from scipy.sparse.linalg import spsolve

def solve(n):
    h = 1.0 / n
    ids = {}
    for i in range(1, n):
        for j in range(1, n):
            ids[(i, j)] = len(ids)
    N = len(ids)
    A = lil_matrix((N, N))
    b = np.full(N, h**4)                      # q h^4 / D, q = D = 1

    def add(row, i, j, c):
        # clamped edge: w = 0 on the boundary, and dw/dn = 0 mirrors the ghost point
        if i == -1: i = 1
        if i == n + 1: i = n - 1
        if j == -1: j = 1
        if j == n + 1: j = n - 1
        if i in (0, n) or j in (0, n):
            return                            # w = 0 there, drops out
        A[row, ids[(i, j)]] += c

    for (i, j), r in ids.items():
        add(r, i, j, 20.0)
        for di, dj in ((1,0),(-1,0),(0,1),(0,-1)):
            add(r, i+di, j+dj, -8.0)
        for di, dj in ((1,1),(1,-1),(-1,1),(-1,-1)):
            add(r, i+di, j+dj, 2.0)
        for di, dj in ((2,0),(-2,0),(0,2),(0,-2)):
            add(r, i+di, j+dj, 1.0)

    w = spsolve(csr_matrix(A), b)
    W = np.zeros((n+1, n+1))
    for (i, j), r in ids.items():
        W[i, j] = w[r]
    return W, h

def coeffs(n, nu):
    W, h = solve(n)
    c = n // 2
    wmax = W[c, c]
    wxx = (W[c+1, c] - 2*W[c, c] + W[c-1, c]) / h**2
    wyy = (W[c, c+1] - 2*W[c, c] + W[c, c-1]) / h**2
    m_centre = -(wxx + nu * wyy)                       # M = -D (w,xx + nu w,yy)
    # edge midpoint x=0: clamped, so w=w_x=0 there and M_edge = -D w_xx
    wxx_e = (W[1, c] - 2*W[0, c] + W[1, c]) / h**2     # ghost mirror w_{-1}=w_{1}
    m_edge = -wxx_e
    return wmax, m_centre, m_edge

NU = 0.3
rows = []
for n in (20, 40, 80):
    rows.append((n,) + coeffs(n, NU))
    print(f"n={n:3d}  w_max={rows[-1][1]:.9f}  M_centre={rows[-1][2]:.9f}  M_edge={rows[-1][3]:.9f}")

def rich(v1, v2):        # O(h^2), mesh halved
    return v2 + (v2 - v1) / 3.0
for k, name in ((1, "w_max"), (2, "M_centre"), (3, "M_edge")):
    e1 = rich(rows[0][k], rows[1][k])
    e2 = rich(rows[1][k], rows[2][k])
    e3 = e2 + (e2 - e1) / 15.0                          # second Richardson level
    print(f"{name:9s} extrapolated = {e2:.9f}   (2nd level {e3:.9f})")
