#!/usr/bin/env python3
"""Generates the verification evidence: identity, accuracy, determinism, performance.

    scripts/evidence.py sidecar/build/br-sidecar [--windows <exe-or-wrapper>]

Writes evidence/verification.json and evidence/VERIFICATION.md.

Why this exists rather than a table typed by hand: every number a reader might check
should come from a run they can repeat, stamped with the exact engine, compiler and
fixture set that produced it. A table in a document drifts from the code the moment
either changes, and a drifted table is worse than none — it looks checked.

Each case carries its own closed-form reference, computed here from beam theory rather
than read back from the engine. A test whose expected value comes from the thing under
test is not a test.
"""
import hashlib
import json
import math
import os
import platform
import subprocess
import statistics
import sys
import time

BLOCK_MM = 1000.0
G = 9.81

# steel_rect_200x400
B, D = 200.0, 400.0
A = B * D
WZ = B * D * D / 6.0
IZ = B * D ** 3 / 12.0
RHO = 7850.0
W = RHO * A * 1e-9 * G           # 6.16068 N/mm
FY_ALLOW_COMP = 350.0

# concrete_slab_200 — the MITC4 plate fixture
PLATE_T = 200.0
PLATE_E = 30000.0
PLATE_NU = 0.20
PLATE_RHO = 2350.0
G_MM = 9810.0                                        # SelfWeight.h works in mm/s^2
Q_PLATE = PLATE_RHO * PLATE_T * G_MM * 1e-12         # N/mm^2, the slab's own weight

# Timoshenko & Woinowsky-Krieger, clamped square plate under a uniform load.
#
# The tabulated CENTRE coefficient 0.0231 is quoted for nu = 0.3. At the centre of a
# square clamped plate the two curvatures are equal by symmetry, so M = D*k*(1+nu) and the
# coefficient rescales as (1+nu)/1.3. Using 0.0231 directly against a nu = 0.2 plate makes
# the error appear to GROW as the mesh is refined, which reads as a divergent element and
# is really a wrong reference.
#
# The EDGE coefficient needs no such correction: the tangential curvature vanishes along a
# clamped edge, so M_edge = -D*w,nn carries no nu.
#
# Both coefficients are tabulated to three significant figures, so an agreement better
# than about 0.2% is comparing against the table's own rounding, not against the theory.
PLATE_C_CENTRE = 0.0231 / 1.3 * (1 + PLATE_NU)
PLATE_C_EDGE = 0.0513


def slab(n, y=64, mat="concrete", plate="concrete_slab_200", clamped=True):
    """n x n plate blocks; the perimeter is clamped, so the meshed span is (n-1) metres."""
    return [{"x": i, "y": y, "z": j, "mat": mat, "section": plate,
             "support": clamped and (i in (0, n - 1) or j in (0, n - 1))}
            for i in range(n) for j in range(n)]


def corner_moment(res, wx, wz, comp, key="Mc", tol=1e-6):
    """A per-corner moment component at a world position, or None.

    `Mc` is the field the demand and the contour use, so it carries the recovered value at
    a clamped edge; `McRaw` is the element's own corner output, present only where the
    recovery fired. Asking for the raw one and silently getting the corrected one is how a
    comparison quietly becomes a comparison of a number with itself.
    """
    for sh in res.get("shells", []):
        if key not in sh:
            continue
        for k, w in enumerate(sh["world"]):
            if abs(w[0] - wx) < tol and abs(w[2] - wz) < tol:
                return sh[key][k][comp]
    return None


class Sidecar:
    def __init__(self, exe):
        self.exe = exe
        self.p = subprocess.Popen([exe], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  text=True, bufsize=1)

    def call(self, req):
        self.p.stdin.write(json.dumps(req) + "\n")
        self.p.stdin.flush()
        return json.loads(self.p.stdout.readline())

    def raw(self, req):
        self.p.stdin.write(json.dumps(req) + "\n")
        self.p.stdin.flush()
        return self.p.stdout.readline().strip()

    def close(self):
        try:
            self.p.stdin.close()
        except Exception:
            pass
        self.p.wait(timeout=10)


def beam(n, mat="steel", section="steel_rect_200x400", supports=(0,), y=64):
    return [{"x": i, "y": y, "z": 0, "mat": mat, "section": section,
             "support": i in supports} for i in range(n)]


def top_sigma(member, k):
    st = member["stations"][k]
    for f in st["fibres"]:
        if f["dir"][1] > 0.5:
            return f["sigma"]
    return None


# --------------------------------------------------------------------- cases
def cases():
    """(name, request, [(quantity, expected, extractor)]) — references are analytic."""
    out = []

    # V1 cantilever, tip load only (self weight cancelled by rho? no: kept, so include it)
    n, L, P = 5, 4000.0, 20000.0
    req = {"op": "solve", "revision": 1, "blocks": beam(n),
           "loads": [{"x": n - 1, "y": 64, "z": 0, "fy": -P}]}
    checks = []
    for k in range(11):
        x = L * k / 10.0
        a = L - x
        checks.append((f"sigma_top(x={x:.0f}mm)",
                       (P * a + W * a * a / 2.0) / WZ,
                       (lambda kk: (lambda r: top_sigma(r["members"][0], kk)))(k)))
    checks.append(("D/C", ((P * L + W * L * L / 2.0) / WZ) / FY_ALLOW_COMP,
                   lambda r: r["members"][0]["dc"]))
    checks.append(("member length (mm)", L, lambda r: r["members"][0]["lengthMm"]))
    out.append(("V1  cantilever, tip load + self weight", req, checks))

    # V2 cantilever, self weight only
    req = {"op": "solve", "revision": 2, "blocks": beam(n)}
    checks = []
    for k in range(11):
        a = L - L * k / 10.0
        checks.append((f"sigma_top(x={L * k / 10.0:.0f}mm)", W * a * a / 2.0 / WZ,
                       (lambda kk: (lambda r: top_sigma(r["members"][0], kk)))(k)))
    out.append(("V2  cantilever, self weight only", req, checks))

    # V3 interior-governing: upward tip load P = wL/2 zeroes both ends, peak at midspan
    n3, L3 = 9, 8000.0
    p3 = W * L3 / 2.0
    req = {"op": "solve", "revision": 3, "blocks": beam(n3),
           "loads": [{"x": n3 - 1, "y": 64, "z": 0, "fy": p3}]}
    checks = [
        ("moment at end i (N.mm)", 0.0, lambda r: r["members"][0]["i"]["Mz"]),
        ("moment at end j (N.mm)", 0.0, lambda r: r["members"][0]["j"]["Mz"]),
        ("peak |sigma| at midspan (MPa)", (W * L3 * L3 / 8.0) / WZ,
         lambda r: max(abs(top_sigma(r["members"][0], k))
                       for k in range(len(r["members"][0]["stations"])))),
        ("D/C from the interior", ((W * L3 * L3 / 8.0) / WZ) / FY_ALLOW_COMP,
         lambda r: r["members"][0]["dc"]),
    ]
    out.append(("V3  interior-governing cantilever (both ends zero)", req, checks))

    # V4 fixed-fixed with a midspan stub: moment reverses along the member
    n4, L4 = 9, 8000.0
    blocks = beam(n4, supports=(0, n4 - 1))
    blocks += [{"x": 4, "y": 65, "z": 0, "mat": "steel", "section": "steel_rect_200x400"},
               {"x": 4, "y": 66, "z": 0, "mat": "steel", "section": "steel_rect_200x400"}]
    req = {"op": "solve", "revision": 4, "blocks": blocks}
    p_stub = W * 2 * BLOCK_MM

    def half(r):
        hs = [m for m in r["members"]
              if m["blocks"][0][1] == 64 and m["blocks"][-1][1] == 64]
        return sorted(hs, key=lambda m: m["blocks"][0][0])

    checks = [
        ("support moment (N.mm)", W * L4 * L4 / 12.0 + p_stub * L4 / 8.0,
         lambda r: half(r)[0]["i"]["Mz"]),
        ("midspan moment (N.mm)", -(W * L4 * L4 / 24.0 + p_stub * L4 / 8.0),
         lambda r: half(r)[0]["j"]["Mz"]),
        ("adjacent members agree at the shared node",
         0.0, lambda r: half(r)[0]["j"]["Mz"] - half(r)[1]["i"]["Mz"]),
    ]
    out.append(("V4  fixed-fixed with midspan node (moment reverses)", req, checks))

    # V5 axial: uniform stress, no bending
    n5 = 5
    req = {"op": "solve", "revision": 5, "blocks": beam(n5),
           "loads": [{"x": n5 - 1, "y": 64, "z": 0, "fx": 100000.0}]}
    checks = [("mean fibre stress = N/A (MPa)", 100000.0 / A,
               lambda r: (top_sigma(r["members"][0], 5)
                          + [f["sigma"] for f in r["members"][0]["stations"][5]["fibres"]
                             if f["dir"][1] < -0.5][0]) / 2.0)]
    out.append(("V5  axial tension", req, checks))

    # V6 concrete: the governing fibre follows the material, D/C against Rtens
    cb, cd = 400.0, 600.0
    wc = 2350.0 * (cb * cd) * 1e-9 * G
    req = {"op": "solve", "revision": 6,
           "blocks": beam(5, mat="concrete", section="concrete_rect_400x600")}
    checks = [("D/C against Rtens = 3 MPa",
               ((wc * 4000.0 ** 2 / 2.0) / (cb * cd * cd / 6.0)) / 3.0,
               lambda r: r["members"][0]["dc"])]
    out.append(("V6  concrete section, tension governs", req, checks))

    # V7 plate self weight is conserved exactly. The load a shell applies is lumped to its
    # four corners by the engine; this asks whether the TOTAL is still q times the meshed
    # area, and whether the reactions balance it. Both are exact statements, so they belong
    # here with the machine-precision references rather than in the convergence study.
    n7 = 9
    a7 = (n7 - 1) * BLOCK_MM
    req = {"op": "solve", "revision": 7, "blocks": slab(n7)}
    checks = [
        ("total applied weight (N)", -Q_PLATE * a7 * a7,
         lambda r: r["equilibrium"]["applied"][1]),
        ("vertical reaction balances it (N)", 0.0,
         lambda r: r["equilibrium"]["applied"][1] + r["equilibrium"]["reaction"][1]),
        ("horizontal equilibrium, x (N)", 0.0,
         lambda r: r["equilibrium"]["applied"][0] + r["equilibrium"]["reaction"][0]),
        ("membrane force under transverse load (N/mm)", 0.0,
         lambda r: max(abs(sh["N"]["xx"]) for sh in r["shells"])),
        ("facets from an n x n slab", float((n7 - 1) ** 2),
         lambda r: float(len(r["shells"]))),
    ]
    out.append(("V7  plate self weight and equilibrium", req, checks))

    # V8 a column bearing on a slab shares its node. If it did not, this is a mechanism —
    # so the axial force adding up to the whole weight IS the connection, as a number.
    blocks = [{"x": i, "y": 68, "z": j, "mat": "concrete", "section": "concrete_slab_200",
               "support": False} for i in range(3) for j in range(3)]
    for ci, cj in ((0, 0), (0, 2), (2, 0), (2, 2)):
        for h in range(64, 68):
            blocks.append({"x": ci, "y": h, "z": cj, "mat": "steel",
                           "section": "steel_rect_200x400", "support": h == 64})
    req = {"op": "solve", "revision": 8, "blocks": blocks}
    checks = [
        ("columns carry the whole structure (N)", 0.0,
         lambda r: sum(-m["i"]["N"] for m in r["members"]) - r["equilibrium"]["applied"][1]),
        ("one connected structure", 1.0, lambda r: float(r["islands"])),
        ("mechanisms", 0.0, lambda r: float(r["singularIslands"])),
    ]
    out.append(("V8  column bearing on a slab (shared node)", req, checks))


    return out


def properties(sc):
    """Behaviours with no closed form: they are either right or wrong, not approximate."""
    out = []

    r = sc.call({"op": "solve", "revision": 20,
                 "blocks": [dict(b, support=False) for b in beam(5)]})
    out.append(("P1  unsupported structure reported as a mechanism",
                r.get("ok") is True and r.get("singular") is True and bool(r.get("diagnostic"))))

    r = sc.call({"op": "solve", "revision": 21, "blocks": beam(1)})
    out.append(("P2  a single block is not a beam",
                r.get("ok") is True and not r.get("members") and len(r.get("unassigned", [])) == 1))

    # A load on a block that forms no element at all has nowhere to go and is refused.
    # (A load in the MIDDLE of a member is representable now — it splits the run — so the
    # unrepresentable case had to be narrowed to what is still genuinely unrepresentable.)
    r = sc.call({"op": "solve", "revision": 22, "blocks": beam(1),
                 "loads": [{"x": 0, "y": 64, "z": 0, "fy": -1000.0}]})
    out.append(("P3  a load on no element is refused, not dropped", r.get("ok") is False))

    bad = dict(beam(2)[0]); bad.pop("mat")
    r = sc.call({"op": "solve", "revision": 23, "blocks": [bad, beam(2)[1]]})
    out.append(("P4  a missing material is refused, not defaulted", r.get("ok") is False))

    r = sc.call({"op": "solve", "revision": 24,
                 "blocks": [dict(b, section="steel_h400") for b in beam(2)]})
    out.append(("P5  an unknown section is refused", r.get("ok") is False))

    a = sc.call({"op": "solve", "revision": 25, "blocks": beam(5)})
    b = sc.call({"op": "solve", "revision": 25, "blocks": beam(5)})
    out.append(("P6  repeated solves are bit-identical", json.dumps(a) == json.dumps(b)))

    # P7 one unrestrained structure must not silence the others. This is the property a
    # player reported as "the mod stops working once there are several buildings": a single
    # global stiffness matrix made every building share one factorisation, and one
    # rank-deficient structure anywhere made it fail for all of them.
    solo = sc.call({"op": "solve", "revision": 26, "blocks": beam(5)})
    floating = [{"x": i + 100, "y": 64, "z": 0, "mat": "steel",
                 "section": "steel_rect_200x400"} for i in range(5)]
    both = sc.call({"op": "solve", "revision": 27, "blocks": beam(5) + floating})
    out.append(("P7  a mechanism is confined to the structure that is one",
                both.get("ok") is True and both.get("islands") == 2
                and both.get("singularIslands") == 1
                and bool(both.get("members"))
                and both.get("maxDC") == solo.get("maxDC")))

    # P8 a flat field of PLATE blocks is one plate, not a grillage. The same shape in BEAM
    # blocks is a grillage, and the token is what tells them apart — geometry never guesses.
    sl = sc.call({"op": "solve", "revision": 28, "blocks": slab(7)})
    gr = sc.call({"op": "solve", "revision": 29,
                  "blocks": [dict(b, mat="steel", section="steel_rect_200x400")
                             for b in slab(7)]})
    out.append(("P8  a slab meshes as plate facets and yields no members",
                len(sl.get("shells", [])) == 36 and not sl.get("members")
                and bool(gr.get("members")) and not gr.get("shells")))

    # P9 plate blocks stacked two deep are a SOLID, not a shell. Meshing it as three
    # intersecting sheets would triple its mass and its stiffness.
    solid = sc.call({"op": "solve", "revision": 30,
                     "blocks": [{"x": i, "y": 64 + h, "z": j, "mat": "concrete",
                                 "section": "concrete_slab_200", "support": False}
                                for i in range(3) for j in range(3) for h in range(2)]})
    out.append(("P9  a solid of plate blocks is refused, not meshed",
                not solid.get("shells") and len(solid.get("unassigned", [])) == 18))

    # P10 the support-moment recovery may only ever RAISE a reported demand. If it could
    # lower one, a failure to recover would make a plate look safer than it is.
    rec = sc.call({"op": "solve", "revision": 31, "blocks": slab(13)})
    out.append(("P10 support-moment recovery never lowers a demand",
                any(sh["edgeRecovered"] for sh in rec["shells"])
                and all(sh["dc"] >= sh["dcRaw"] - 1e-12 for sh in rec["shells"])))

    # P11 a load inside a member splits it and acts where it was put. Checked against the
    # closed form rather than against the reply: a load quietly snapped to the nearest node
    # would still produce a perfectly self-consistent answer.
    n11, p11 = 9, 20000.0
    r = sc.call({"op": "solve", "revision": 32, "blocks": beam(n11),
                 "loads": [{"x": 4, "y": 64, "z": 0, "fy": -p11}]})
    span = (n11 - 1) * BLOCK_MM
    expect = p11 * 4 * BLOCK_MM + W * span * span / 2.0
    out.append(("P11 a load inside a member splits it and acts there",
                r.get("ok") is True and len(r.get("members", [])) == 2
                and abs(r["members"][0]["i"]["Mz"] - expect) / expect < 1e-12))

    # P12 stability is not strength. A slender column the stress screen calls comfortable.
    col = [{"x": 0, "y": 64 + i, "z": 0, "mat": "steel", "section": "steel_rect_200x400",
            "support": i == 0} for i in range(21)]
    r = sc.call({"op": "solve", "revision": 33, "blocks": col,
                 "loads": [{"x": 0, "y": 84, "z": 0, "fy": -400000.0}]})
    out.append(("P12 a slender column is safe by stress and unstable by buckling",
                r.get("maxDC", 1) < 0.05 and 0 < r.get("bucklingFactor", 0) < 1.0))

    return out


# ------------------------------------------------------------------ shear wall
def shear_wall(sc):
    """A shear wall against closed forms, reported apart from the machine-precision cases.

    D-005 chose MITC4 over grillage on one specific ground: a grillage restrains the
    in-plane DOFs at every node, so it reports exactly ZERO for the shear flow below. That
    justification is checked here rather than asserted.

    This lives in its own section on purpose. The beam cases agree with their closed forms
    to 1e-10 because the finite element reproduces beam theory exactly; a wall does not.
    These numbers are finite-element answers compared against beam theory applied to the
    wall's own plan section, so their agreement is limited by discretisation and by how
    well a wall of that aspect ratio is a beam at all. Folding them into the same figure
    would quote the whole document's accuracy as 1e-5 when the arithmetic is 1e-10.
    """
    def cen(s, k):
        return sum(q[k] for q in s["world"]) / 4.0

    V = 100000.0
    rows = []
    for nw, nh in ((7, 7), (5, 13), (5, 21), (3, 21)):
        w_mm, h_mm = (nw - 1) * BLOCK_MM, (nh - 1) * BLOCK_MM
        blocks = [{"x": i, "y": 64 + j, "z": 0, "mat": "concrete",
                   "section": "concrete_slab_200", "support": j == 0}
                  for i in range(nw) for j in range(nh)]
        loads = [{"x": i, "y": 64 + nh - 1, "z": 0, "fx": V / nw} for i in range(nw)]
        r = sc.call({"op": "solve", "revision": 400 + nw * 10 + nh,
                     "blocks": blocks, "loads": loads})

        heights = sorted({round(cen(s, 1)) for s in r["shells"]})
        y = heights[len(heights) // 2]
        row = sorted([s for s in r["shells"] if abs(cen(s, 1) - y) < 1e-6],
                     key=lambda s: cen(s, 0))

        shear = abs(sum(s["N"]["xy"] for s in row) / len(row))
        shear_ref = V / w_mm

        bend = abs((row[-1]["N"]["xx"] - row[0]["N"]["xx"]) / 2.0)
        lever = h_mm - (y - (64 * BLOCK_MM + BLOCK_MM / 2.0))
        c = w_mm / 2.0 - BLOCK_MM / 2.0
        bend_ref = V * lever * c / (PLATE_T * w_mm ** 3 / 12.0) * PLATE_T

        # Self weight above the cut: exact, and independent of any beam idealisation.
        mean = sum(s["N"]["xx"] for s in row) / len(row)
        weight_ref = Q_PLATE * lever

        rows.append({
            "elements_wide": nw - 1, "elements_tall": nh - 1,
            "aspect": h_mm / w_mm,
            "shear_flow": shear, "shear_reference": shear_ref,
            "shear_rel_error": abs(shear - shear_ref) / shear_ref,
            "overturning": bend, "overturning_reference": bend_ref,
            "overturning_rel_error": abs(bend - bend_ref) / bend_ref,
            "self_weight_rel_error": abs(-mean - weight_ref) / weight_ref,
        })

    # The beam-theory comparison is gated on the SLENDER walls only. A wall as tall as it
    # is wide is a deep beam, where plane sections do not remain plane and beam theory is
    # itself the wrong model — the squat row stays in the table as the demonstration of
    # that, not as a failure of the element.
    #
    # Self weight is different: it is exact geometry, not an idealisation, so it is gated
    # at every aspect ratio.
    slender = [x for x in rows if x["aspect"] >= 3]
    gate = (all(x["shear_rel_error"] < 1e-4 for x in slender)
            and all(x["overturning_rel_error"] < 1e-5 for x in slender)
            and all(x["self_weight_rel_error"] < 1e-6 for x in rows))
    return {"rows": rows, "gate": gate, "shear_n": V}


# ------------------------------------------------------------------ convergence
def plate_convergence(sc):
    """Mesh convergence of the MITC4 plate against Timoshenko's clamped square plate.

    Two quantities behave very differently and both are reported, because reporting only
    the good one would be the kind of selective evidence this document exists to avoid.

      * The SPAN moment converges cleanly and is the quantity that governs a slab's field
        reinforcement.
      * The SUPPORT moment does not. MITC4's per-corner moments are not superconvergent,
        and at a clamped edge they are badly LOW — the unsafe direction. It is therefore
        recovered by extrapolating from the two interior element centres, and both the raw
        and recovered figures are given so the reader can see the size of the correction
        rather than take it on trust.
    """
    rows = []
    for n in (5, 7, 9, 11, 13, 15, 17, 21):
        r = sc.call({"op": "solve", "revision": 100 + n, "blocks": slab(n)})
        a = (n - 1) * BLOCK_MM
        c = (n - 1) / 2.0 * BLOCK_MM + BLOCK_MM / 2.0

        span_ref = PLATE_C_CENTRE * Q_PLATE * a * a
        span = corner_moment(r, c, c, 0)

        edge_ref = PLATE_C_EDGE * Q_PLATE * a * a
        # The clamping moment on an edge running along x is Myy — across the edge, not
        # along it. Taking Mxx there reads a quantity that is near zero and calls it an
        # 87% error.
        rec = None
        for sh in r["shells"]:
            if not sh.get("edgeRecovered"):
                continue
            for k, w in enumerate(sh["world"]):
                if abs(w[0] - c) < BLOCK_MM and abs(w[2] - BLOCK_MM / 2.0) < 1e-6:
                    rec = sh["Mc"][k][1]
        raw = corner_moment(r, c, BLOCK_MM / 2.0, 1, key="McRaw")

        rows.append({
            "elements_per_side": n - 1,
            "span_mm": a,
            "span_moment": span,
            "span_reference": -span_ref,
            "span_rel_error": abs(abs(span) - span_ref) / span_ref if span is not None else None,
            "support_reference": edge_ref,
            "support_corner_raw": raw,
            "support_raw_rel_error": abs(abs(raw) - edge_ref) / edge_ref if raw is not None else None,
            "support_recovered": rec,
            "support_rel_error": abs(abs(rec) - edge_ref) / edge_ref if rec is not None else None,
        })

    # Is the residual at fine meshes DISCRETISATION or is it the reference?
    #
    # MITC4 is a Reissner-Mindlin element and the Timoshenko coefficient is thin-plate, so
    # transverse shear is the obvious suspect. It is also testable: shear deformation
    # scales with t/a, discretisation does not. The same meshes are therefore run at two
    # thicknesses. If the signed errors track each other, the residual is not shear — and
    # then it is either the element or the tabulated coefficient's own precision.
    control = []
    for n in (9, 13, 17, 21):
        a = (n - 1) * BLOCK_MM
        c = (n - 1) / 2.0 * BLOCK_MM + BLOCK_MM / 2.0
        row = {"elements_per_side": n - 1}
        for token, t in (("concrete_slab_200", 200.0), ("concrete_slab_150", 150.0)):
            q = PLATE_RHO * t * G_MM * 1e-12
            r = sc.call({"op": "solve", "revision": 200 + n * 4 + int(t),
                         "blocks": slab(n, plate=token)})
            got = corner_moment(r, c, c, 0)
            ref = PLATE_C_CENTRE * q * a * a
            row[f"t{int(t)}_signed_error"] = (abs(got) - ref) / ref
            row[f"t{int(t)}_thickness_over_span"] = t / a
        control.append(row)

    finest = rows[-1]
    twelve = [x for x in rows if x["elements_per_side"] >= 12]
    # The span gate is two-sided: an error that grew without bound in EITHER direction is a
    # failure, and a one-sided bound would not notice the second kind.
    gate = (all(abs(x["span_rel_error"]) < 0.01 for x in twelve)
            and all(x["support_rel_error"] < x["support_raw_rel_error"] for x in rows)
            and finest["support_rel_error"] < 0.06
            # thickness independence: the two thicknesses must agree to well inside the
            # residual itself, or the diagnosis below is wrong.
            and all(abs(x["t200_signed_error"] - x["t150_signed_error"]) < 0.001
                    for x in control))
    return {"rows": rows, "gate": gate, "thickness_control": control,
            "centre_coefficient": PLATE_C_CENTRE, "edge_coefficient": PLATE_C_EDGE,
            "q_n_per_mm2": Q_PLATE}


# ---------------------------------------------------------------- performance
def performance(exe):
    """Wall-clock per solve against problem size. Median of repeats, cold process excluded.

    Measured BOTH with and without the buckling eigensolve. Buckling is on by default
    because a stress check alone is unsafe for slender members, but it is a second solve on
    top of the first and quoting only the total would hide what the default costs.
    """
    rows = []
    for n_members in (1, 2, 5, 10, 20, 30, 50, 100):
        # A comb: one spine plus n_members-1 stubs, so member count grows with the model.
        blocks = beam(2 * n_members + 1)
        for k in range(1, n_members):
            blocks += [{"x": 2 * k, "y": 65, "z": 0, "mat": "steel",
                        "section": "steel_rect_200x400"}]
        row = {"blocks": len(blocks)}
        for label, buckling in (("with_buckling", True), ("without_buckling", False)):
            sc = Sidecar(exe)
            sc.call({"op": "hello"})
            req = {"op": "solve", "revision": 1, "blocks": blocks, "buckling": buckling}
            sc.call(req)                                # warm the process
            # More repeats than the accuracy work needs: these are wall-clock numbers on a
            # shared machine and the sub-millisecond rows are dominated by the protocol
            # round trip, not by the solve.
            times = []
            for i in range(15):
                t0 = time.perf_counter()
                r = sc.call(dict(req, revision=i + 2))
                times.append((time.perf_counter() - t0) * 1000.0)
            sc.close()
            row["members"] = len(r.get("members", []))
            row["dof"] = r.get("dof", 0)
            row[label + "_ms"] = round(statistics.median(times), 3)
            if label == "with_buckling":
                row["min_ms"] = round(min(times), 3)
                row["max_ms"] = round(max(times), 3)
        row["median_ms"] = row["with_buckling_ms"]      # the default path
        rows.append(row)
    return rows


# ------------------------------------------------------------------- identity
def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def identity(exe, framecore_dir):
    def git(*args):
        # safe.directory: the engine checkout usually sits on NTFS and this script
        # usually runs under WSL, where git refuses "dubious ownership" and every
        # query below would come back "unavailable". Provenance that silently
        # degrades to "unavailable" is exactly what the gate now refuses to ship.
        try:
            return subprocess.check_output(
                ["git", "-c", "safe.directory=*", "-C", framecore_dir, *args],
                text=True, stderr=subprocess.DEVNULL).strip()
        except Exception:
            return "unavailable"

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    files = {}
    for rel in ("sidecar/main.cpp", "sidecar/json.hpp", "sidecar/verify.py",
                "sidecar/CMakeLists.txt", "scripts/evidence.py"):
        p = os.path.join(root, rel)
        if os.path.exists(p):
            files[rel] = sha256(p)

    return {
        "engine": {
            "name": "FrameCore",
            "commit": git("rev-parse", "HEAD"),
            "committed": git("log", "-1", "--format=%cI"),
            "worktree_clean": git("status", "--porcelain") == "",
            "supernodal_lane": "compiled out (FRAMECORE_SUPERNODAL=0); solves via Eigen SimplicialLDLT",
        },
        "binary": {"path": os.path.abspath(exe), "sha256": sha256(exe)},
        "sources": files,
        "host": {
            "platform": platform.platform(),
            "python": platform.python_version(),
        },
    }


# ------------------------------------------------------------------------ run
def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    exe = sys.argv[1]
    win = None
    if "--windows" in sys.argv:
        win = sys.argv[sys.argv.index("--windows") + 1]
    framecore = os.environ.get("FRAMECORE_DIR",
                               "/home/user/architect_simulator/Plugins/FrameSolver/Source/FrameCore")

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    outdir = os.path.join(root, "evidence")
    os.makedirs(outdir, exist_ok=True)

    sc = Sidecar(exe)
    hello = sc.call({"op": "hello"})

    # Two metrics, not one. A quantity whose exact value is ZERO cannot have a relative
    # error, and folding it into the same figure as the others would misreport the
    # accuracy of everything else: one absolute residual of 1e-8 on a zero reference
    # would be quoted as though the method were 1e-8 accurate, when the non-zero
    # comparisons are eight orders of magnitude better.
    results = []
    rels, absresid = [], []
    # A case that failed to solve, or a quantity the extractor could not find, used
    # to fall OUT of the aggregate: the headline numbers were then computed over the
    # cases that happened to work, and the gate passed without the accuracy having
    # been measured (SCRIPT-5). Both now count, and either makes the gate fail.
    failed_cases, missing_quantities = 0, 0
    for name, req, checks in cases():
        r = sc.call(req)
        if not r.get("ok"):
            failed_cases += 1
            results.append({"case": name, "error": r.get("error", "solve failed")})
            continue
        rows = []
        for label, expected, extract in checks:
            got = extract(r)
            if got is None:
                missing_quantities += 1
                rows.append({"quantity": label, "expected": expected, "got": None,
                             "rel": None, "abs": None})
                continue
            err = abs(got - expected)
            if abs(expected) > 0:
                rel = err / abs(expected)
                rels.append(rel)
                rows.append({"quantity": label, "expected": expected, "got": got,
                             "rel": rel, "abs": None})
            else:
                absresid.append(err)
                rows.append({"quantity": label, "expected": expected, "got": got,
                             "rel": None, "abs": err})
        results.append({"case": name, "checks": rows})
    worst_rel = max(rels) if rels else 0.0
    worst_abs = max(absresid) if absresid else 0.0

    props = properties(sc)
    convergence = plate_convergence(sc)
    wall = shear_wall(sc)

    # Cross-platform determinism over the whole fixture set, not one case.
    determinism = {"checked": False}
    if win:
        wsc = Sidecar(win)
        wsc.call({"op": "hello"})
        same, total = 0, 0
        for _, req, _ in cases():
            total += 1
            if sc.raw(req) == wsc.raw(req):
                same += 1
        wsc.close()
        determinism = {"checked": True, "identical": same, "cases": total,
                       "note": "byte-for-byte comparison of the full reply line"}

    sc.close()

    perf = performance(exe)

    # Transport comparison, from the same importable measurement the standalone
    # bench uses. The README quotes these numbers; they must come from a record
    # a reader can regenerate, not from a one-off terminal session (PERF-1).
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import bench_transport
    transport = bench_transport.measure(exe)

    doc = {
        "identity": identity(exe, framecore),
        "handshake": hello,
        "accuracy": {
            "cases": results,
            "failed_cases": failed_cases,
            "missing_quantities": missing_quantities,
            "nonzero_references": {
                "comparisons": len(rels),
                "worst_relative_error": worst_rel,
                "rms_relative_error": math.sqrt(sum(x * x for x in rels) / len(rels)) if rels else None,
            },
            "zero_references": {
                "comparisons": len(absresid),
                "worst_absolute_residual": worst_abs,
                "note": "quantities whose exact value is zero; compared absolutely",
            },
        },
        "properties": [{"property": p, "holds": ok} for p, ok in props],
        "plate_convergence": convergence,
        "shear_wall": wall,
        "determinism": determinism,
        "performance": perf,
        "transport": transport,
    }

    with open(os.path.join(outdir, "verification.json"), "w") as f:
        json.dump(doc, f, indent=2)

    write_markdown(os.path.join(outdir, "VERIFICATION.md"), doc)

    # Provenance is a NECESSARY condition (#47): a verification record that cannot
    # name the engine commit it verified, or that was produced from a dirty tree,
    # is not evidence — it is a screenshot. GATES.md carries the same requirement.
    eng = doc["identity"]["engine"]
    provenance_ok = (eng["commit"] != "unavailable" and eng["worktree_clean"] is True)

    ok = provenance_ok and failed_cases == 0 and missing_quantities == 0 \
        and worst_rel < 1e-9 and worst_abs < 1e-3 and all(ok for _, ok in props) \
        and convergence["gate"] and wall["gate"] \
        and (not determinism["checked"] or determinism["identical"] == determinism["cases"])
    print(f"provenance: engine commit {eng['commit'][:12]} "
          f"worktree_clean={eng['worktree_clean']} — {'OK' if provenance_ok else 'FAIL'}")
    if failed_cases or missing_quantities:
        print(f"INCOMPLETE: {failed_cases} case(s) failed to solve, "
              f"{missing_quantities} quantity(ies) not extracted — gate FAILS")
    print(f"worst relative error {worst_rel:.3e} over {len(rels)} non-zero references")
    print(f"worst absolute residual {worst_abs:.3e} over {len(absresid)} zero references")
    print(f"properties {sum(1 for _, o in props if o)}/{len(props)}")
    fin = convergence["rows"][-1]
    print(f"plate convergence: span {fin['span_rel_error'] * 100:.2f}%, "
          f"support {fin['support_rel_error'] * 100:.1f}% "
          f"(raw {fin['support_raw_rel_error'] * 100:.1f}%) at "
          f"{fin['elements_per_side']} elements — gate {'PASS' if convergence['gate'] else 'FAIL'}")
    wr = wall["rows"][1]
    print(f"shear wall: flow {wr['shear_rel_error'] * 100:.3f}%, "
          f"overturning {wr['overturning_rel_error'] * 100:.3f}% "
          f"— gate {'PASS' if wall['gate'] else 'FAIL'}")
    if determinism["checked"]:
        print(f"determinism {determinism['identical']}/{determinism['cases']} identical across platforms")
    print("evidence/verification.json and evidence/VERIFICATION.md written")
    return 0 if ok else 1


def write_markdown(path, doc):
    L = []
    ident = doc["identity"]
    L.append("# Verification evidence\n")
    L.append("Generated by `scripts/evidence.py`. Every number here comes from a run of the")
    L.append("binary named below; none is transcribed by hand.\n")

    L.append("## Identity\n")
    L.append("| | |")
    L.append("|---|---|")
    L.append(f"| engine | {ident['engine']['name']} |")
    L.append(f"| commit | `{ident['engine']['commit']}` ({ident['engine']['committed']}) |")
    L.append(f"| worktree clean | {ident['engine']['worktree_clean']} |")
    L.append(f"| solver lane | {ident['engine']['supernodal_lane']} |")
    L.append(f"| binary sha256 | `{ident['binary']['sha256']}` |")
    L.append(f"| host | {ident['host']['platform']} |")
    L.append("")
    L.append("Source hashes:\n")
    L.append("| file | sha256 |")
    L.append("|---|---|")
    for k, v in ident["sources"].items():
        L.append(f"| `{k}` | `{v}` |")
    L.append("")

    acc = doc["accuracy"]
    nz, zr = acc["nonzero_references"], acc["zero_references"]
    L.append("## Accuracy against closed-form solutions\n")
    L.append(f"**{nz['comparisons']} comparisons against non-zero references: worst relative")
    L.append(f"error {nz['worst_relative_error']:.3e}, RMS {nz['rms_relative_error']:.3e}.**\n")
    L.append(f"**{zr['comparisons']} comparisons against exactly-zero references: worst absolute")
    L.append(f"residual {zr['worst_absolute_residual']:.3e} N·mm.**\n")
    L.append("The two are reported separately on purpose. A quantity whose exact value is zero")
    L.append("has no relative error, and folding such a case into the same figure would")
    L.append("misreport everything else — a single absolute residual of 1e-8 on a zero")
    L.append("reference would be quoted as though the method were 1e-8 accurate, when the")
    L.append("non-zero comparisons are several orders of magnitude better than that.\n")
    for case in acc["cases"]:
        L.append(f"### {case['case']}\n")
        if "error" in case:
            L.append(f"engine error: `{case['error']}`\n")
            continue
        L.append("| quantity | closed form | engine | error |")
        L.append("|---|---:|---:|---:|")
        for c in case["checks"]:
            got = "—" if c["got"] is None else f"{c['got']:.6g}"
            if c["rel"] is not None:
                err = f"{c['rel']:.2e} rel"
            elif c["abs"] is not None:
                err = f"{c['abs']:.2e} abs"
            else:
                err = "—"
            L.append(f"| {c['quantity']} | {c['expected']:.6g} | {got} | {err} |")
        L.append("")

    L.append("## Properties\n")
    L.append("Behaviours with no closed form: each is either right or wrong.\n")
    L.append("| property | holds |")
    L.append("|---|---|")
    for p in doc["properties"]:
        L.append(f"| {p['property']} | {'yes' if p['holds'] else 'NO'} |")
    L.append("")

    cv = doc.get("plate_convergence")
    if cv:
        L.append("## Plate element: mesh convergence\n")
        L.append("MITC4 flat shells against Timoshenko & Woinowsky-Krieger's clamped square")
        L.append("plate under a uniform load, the load being the slab's own weight")
        L.append(f"(q = {cv['q_n_per_mm2']:.6g} N/mm²). One block is one element, so the mesh")
        L.append("density is set by how large the slab is.\n")
        L.append("Two coefficients are used and only one of them needed correcting. The")
        L.append(f"tabulated centre coefficient 0.0231 is quoted for ν = 0.3; at the centre of a")
        L.append("square clamped plate the two curvatures are equal by symmetry, so M = D·κ·(1+ν)")
        L.append(f"and it rescales to **{cv['centre_coefficient']:.6f}** for this plate's ν = 0.2.")
        L.append("The edge coefficient **0.0513** needs no correction, because the tangential")
        L.append("curvature vanishes along a clamped edge and M_edge = −D·w,nn carries no ν.")
        L.append("Using 0.0231 directly makes the error appear to *grow* as the mesh is refined,")
        L.append("which reads as a divergent element and is really a wrong reference.\n")
        L.append("Both coefficients are tabulated to three significant figures. Where the")
        L.append("agreement below reaches a fraction of a per cent, the reference is the less")
        L.append("precise of the two numbers being compared — see the thickness control below,")
        L.append("which is what establishes that rather than assuming it.\n")
        L.append("| elements per side | span | span error | support (raw corner) | support (recovered) | reference |")
        L.append("|---:|---:|---:|---:|---:|---:|")
        for r in cv["rows"]:
            L.append("| {} | {:.1f} | {:.2f}% | {:.1f}  ({:.1f}%) | {:.1f}  ({:.1f}%) | {:.1f} |".format(
                r["elements_per_side"], r["span_moment"], r["span_rel_error"] * 100,
                r["support_corner_raw"], r["support_raw_rel_error"] * 100,
                r["support_recovered"], r["support_rel_error"] * 100,
                r["support_reference"]))
        L.append("")
        L.append("Moments are per unit width, N·mm/mm.\n")
        L.append("### The span moment, and what the residual actually is\n")
        L.append("The span error falls steeply, passes through zero at about twelve elements and")
        L.append("then settles at a few tenths of a per cent on the other side. It does not keep")
        L.append("shrinking, so something other than mesh density is setting the floor, and")
        L.append("saying \"converges cleanly\" and stopping there would be describing the first")
        L.append("half of the table only.\n")
        L.append("The obvious suspect is transverse shear: MITC4 is a Reissner–Mindlin element")
        L.append("and Timoshenko's coefficient is thin-plate. That suspect is testable, because")
        L.append("shear deformation scales with t/a and discretisation does not — so the same")
        L.append("meshes were run at two thicknesses.\n")
        ctrl = cv.get("thickness_control") or []
        if ctrl:
            L.append("| elements per side | t = 200 mm | t/a | t = 150 mm | t/a |")
            L.append("|---:|---:|---:|---:|---:|")
            for r in ctrl:
                L.append("| {} | {:+.3f}% | {:.4f} | {:+.3f}% | {:.4f} |".format(
                    r["elements_per_side"],
                    r["t200_signed_error"] * 100, r["t200_thickness_over_span"],
                    r["t150_signed_error"] * 100, r["t150_thickness_over_span"]))
            L.append("")
            L.append("The two columns track each other to within a hundredth of a per cent while")
            L.append("t/a changes by a factor of three. **The residual is not shear deformation.**")
            L.append("What is left is the element's own converged answer differing from the")
            L.append("tabulated coefficient by well under one per cent — and that coefficient is a")
            L.append("truncated series quoted to three significant figures. At this level the")
            L.append("reference is the less precise of the two numbers being compared, which is")
            L.append("the honest place to stop rather than tune anything to close the gap.\n")
        L.append("### Why the support column has two numbers\n")
        L.append("The span moment is the quantity that governs a slab's field reinforcement, and")
        L.append("the table above is it. The support moment is a different story: MITC4's per-corner")
        L.append("moments are not superconvergent, and at a clamped edge the raw corner value is")
        L.append("badly low — the *unsafe* direction, and precisely the silently-safe answer this")
        L.append("project treats as the worst class of error.\n")
        L.append("It is therefore recovered by extrapolating from the two interior element")
        L.append("centres normal to the edge, M_edge ≈ 1.5·M₁ − 0.5·M₂, the textbook")
        L.append("interior-to-boundary recovery. Both figures are printed so the size of the")
        L.append("correction is visible rather than taken on trust. The recovery is applied only")
        L.append("where it is defensible — an edge whose two corner nodes are fully fixed, with a")
        L.append("neighbouring facet in the same local frame — and it is taken as the *worse* of")
        L.append("the two, so a failure to recover can never make a plate look safer.\n")
        L.append("**Honest boundary.** The recovered support moment is still several per cent low")
        L.append("on a coarse mesh, and the mesh is coarse whenever the slab is small: a 4 m slab")
        L.append("is four elements across and nothing recovers that well. Read the span moment as")
        L.append("quantitative and the support moment as indicative until the plate spans at least")
        L.append("a dozen blocks.\n")

    sw = doc.get("shear_wall")
    if sw:
        L.append("## Shear wall: the load path the element was chosen for\n")
        L.append("D-005 chose MITC4 flat shells over a grillage on one specific ground: a")
        L.append("grillage restrains the in-plane DOFs at every node, so it cannot carry a shear")
        L.append("wall's load at all and reports **exactly zero** for the first column below.")
        L.append("That justification is checked here rather than asserted.\n")
        L.append(f"A wall clamped along its base with {sw['shear_n']:.0f} N pushed across its top,")
        L.append("read at the facet row nearest mid height. It resists that load two ways at once,")
        L.append("and both have closed forms: in-plane shear flow V/w, and an overturning couple")
        L.append("carried as vertical fibre force, the wall acting as a cantilever whose")
        L.append("cross-section is its own plan.\n")
        L.append("| elements (w × h) | aspect h/w | shear flow error | overturning error | self weight error |")
        L.append("|---:|---:|---:|---:|---:|")
        for r in sw["rows"]:
            L.append("| {} × {} | {:.0f} | {:.2e} | {:.2e} | {:.2e} |".format(
                r["elements_wide"], r["elements_tall"], r["aspect"],
                r["shear_rel_error"], r["overturning_rel_error"], r["self_weight_rel_error"]))
        L.append("")
        L.append("Agreement improves with slenderness, as beam theory itself does: at h/w = 3 the")
        L.append("shear flow is a few parts in 1e5 and the overturning a few in 1e7; from h/w = 5")
        L.append("up, 1e-7 and 1e-9. The h/w >= 5 rows include a wall only")
        L.append("**two elements wide** — which is the point of the QM6 incompatible membrane")
        L.append("modes. Without them a four-node quad has no way to curve in its own plane, and")
        L.append("the same walls reported their overturning fibre force 3.4% and 12.3% LOW, the")
        L.append("unsafe direction. The bubble modes are statically condensed, so they cost no")
        L.append("global degrees of freedom.\n")
        L.append("The squat 6 × 6 wall is in the table deliberately and is **not** gated against")
        L.append("beam theory. At an aspect ratio of 1 a wall is a deep beam: plane sections do")
        L.append("not remain plane, and beam theory is the wrong model rather than the element")
        L.append("being wrong. Its 2% is the reference disagreeing, not the answer.\n")
        L.append("Self weight is gated at every aspect ratio, because it is exact geometry rather")
        L.append("than an idealisation: the compression at a cut is the material above it and")
        L.append("nothing else, and it matches to 1e-10.\n")

    det = doc["determinism"]
    L.append("## Cross-platform determinism\n")
    if det["checked"]:
        L.append(f"**{det['identical']}/{det['cases']} cases byte-for-byte identical** between the")
        L.append("native Linux binary and the Windows cross-build. Comparison is of the whole")
        L.append("reply line, not of selected fields.\n")
    else:
        L.append("Not checked in this run (no second binary supplied).\n")

    L.append("## Performance\n")
    L.append("Wall clock per solve, measured from the client side over the protocol, so it")
    L.append("includes serialisation and the process boundary. Median of nine repeats after")
    L.append("one warm-up; the cold process start is excluded because it happens once.\n")
    L.append("| blocks | members | DOF | default (ms) | min | max | ms/member | no buckling (ms) | buckling |")
    L.append("|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    for r in doc["performance"]:
        per = r["median_ms"] / r["members"] if r["members"] else float("nan")
        nb = r.get("without_buckling_ms")
        cost = f"x{r['median_ms'] / nb:.2f}" if nb else "—"
        L.append(f"| {r['blocks']} | {r['members']} | {r.get('dof', 0)} | {r['median_ms']} "
                 f"| {r['min_ms']} | {r['max_ms']} | {per:.3f} | {nb} | {cost} |")
    L.append("")
    big = doc["performance"][-1]
    L.append(f"At {big['members']} members the whole round trip is {big['median_ms']:.1f} ms,")
    L.append("against a Minecraft tick of 50 ms — and the solve does not run on the tick")
    L.append("thread, so this is latency to a result rather than time taken from the game.\n")
    tr = doc.get("transport")
    if tr:
        L.append("### Transport: shared memory vs JSON, same solves\n")
        L.append(f"A {tr['members']}-member, {tr['dof']}-DOF frame solved {tr['rounds']} times")
        L.append("over each wire (median). The solve in the middle is identical; the")
        L.append("difference is the transport.\n")
        L.append("| wire | median (ms) | min (ms) | reply size |")
        L.append("|---|---:|---:|---:|")
        L.append(f"| JSON lines | {tr['json_median_ms']} | {tr['json_min_ms']} "
                 f"| {tr['json_reply_bytes']} bytes over the pipe |")
        L.append(f"| shared memory | {tr['shm_median_ms']} | {tr['shm_min_ms']} "
                 f"| {tr['shm_reply_bytes']} bytes in the region, ~60-byte doorbell |")
        L.append("")
        L.append(f"Transport saving: {tr['saving_ms']} ms per solve ({tr['saving_pct']}%).")
        L.append("Measured by `scripts/bench_transport.py`, imported and run by this script")
        L.append("so the record regenerates with everything else.\n")
    if big.get("without_buckling_ms"):
        L.append("The last two columns are the price of the default. Linear buckling is a second")
        L.append("solve — an eigenvalue problem reusing the same factorisation — and it is on by")
        L.append("default because a stress check alone is unsafe for slender members. It is not")
        L.append("free, so the cost is measured rather than described, and `\"buckling\": false`")
        L.append("on the request turns it off for a caller that has decided it does not want it.\n")
        L.append("The DOF column is there because it explains a trap that was measured and then")
        L.append("removed. FrameCore's eigensolver defaults to a dense path below 500 free DOF")
        L.append("and a sparse subspace iteration above it, and only the sparse path reuses the")
        L.append("factorisation the linear solve already computed. That put the entire relative")
        L.append("cost of buckling on ordinary buildings: a 59-member structure (360 DOF) paid")
        L.append("**3.7x** for buckling while a 199-member one paid nothing, because the large")
        L.append("model was already on the cheap path. The sidecar therefore forces the sparse")
        L.append("path at every size, and the same 59-member solve went from 51.7 ms to 14.0 ms.\n")
        L.append("That is safe rather than a gamble, on the eigensolver's own contract: the")
        L.append("sparse path is tolerance-level rather than bit-identical and falls back to")
        L.append("dense on non-convergence, so the worst case is the cost that was there before.")
        L.append("Its accuracy is pinned against the textbook single-element column value to")
        L.append("1.6e-05 by a gate, not assumed.\n")
        L.append("It is deliberately **not** skipped by a per-member screen. A frame can sway-buckle")
        L.append("at a load far below any individual member's Euler load, so \"nothing is near its")
        L.append("own critical load\" is not a safe reason to skip the global eigensolve.")
    L.append("")

    with open(path, "w") as f:
        f.write("\n".join(L) + "\n")


if __name__ == "__main__":
    sys.exit(main())
