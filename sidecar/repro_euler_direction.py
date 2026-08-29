#!/usr/bin/env python3
"""
Which way does the single-element buckling error point?

    python repro_euler_direction.py [path-to-br-sidecar]

repro_selfweight_buckling.py shows the reported factor sitting 68% BELOW the exact
value when self weight is the only axial force. That is the discretisation, not the
theory, and the sign is not universal: with a large point load at the top the axial
force is nearly uniform, which is the case a single element represents well. This
script measures that second case so the two can be stated together honestly.

Cantilever, fixed base, free top: P_cr = pi^2 E I / (2 L)^2.
"""
import json
import math
import subprocess
import sys

EXE = sys.argv[1] if len(sys.argv) > 1 else "sidecar/build/br-sidecar"

N, Y0 = 20, 64
SECTION = "steel_rect_200x400"
B, D = 0.200, 0.400
E = 200e9
RHO, G = 7850.0, 9.81

A = B * D
I_WEAK = D * B ** 3 / 12.0
L = (N - 1) * 1.0
Q = A * RHO * G
P_EULER = math.pi ** 2 * E * I_WEAK / (2 * L) ** 2


class Sidecar:
    def __init__(self, exe):
        self.p = subprocess.Popen([exe], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  text=True, bufsize=1)
        self.rev = 0

    def solve(self, blocks, loads=None):
        self.rev += 1
        req = {"op": "solve", "revision": self.rev, "blocks": blocks}
        if loads:
            req["loads"] = loads
        self.p.stdin.write(json.dumps(req) + "\n")
        self.p.stdin.flush()
        return json.loads(self.p.stdout.readline())

    def close(self):
        self.p.stdin.write('{"op":"bye"}\n')
        self.p.stdin.flush()


def column():
    return [{"x": 0, "y": Y0 + i, "z": 0, "mat": "steel",
             "section": SECTION, "support": i == 0} for i in range(N)]


def main():
    sc = Sidecar(EXE)
    print(f"cantilever {L:.0f} m, section {SECTION}")
    print(f"self weight {Q * L / 1000:.1f} kN, Euler P_cr = {P_EULER / 1000:.1f} kN\n")

    print("top point load, applied at the top block so the run stays ONE element")
    print(f"   {'P applied':>12}{'members':>9}{'lambda_cr':>11}{'lambda*P':>12}{'vs Euler':>10}")
    for p_kn in [500.0, 2000.0, 10000.0, 50000.0]:
        r = sc.solve(column(), [{"x": 0, "y": Y0 + N - 1, "z": 0,
                                 "fx": 0.0, "fy": -p_kn * 1000.0, "fz": 0.0}])
        lam = r["bucklingFactor"]
        # The eigenvalue scales the whole load set: applied point load AND self weight.
        # With P >> self weight the critical combination is essentially lambda * P.
        crit = lam * p_kn * 1000.0
        print(f"   {p_kn:>10.0f} kN{len(r['members']):>9}{lam:>11.5f}"
              f"{crit / 1000:>10.1f} kN{100 * (crit / P_EULER - 1):>9.1f}%")

    sc.close()


if __name__ == "__main__":
    main()
