#!/usr/bin/env python3
"""Sidecar acceptance checks.

Every case here compares against a closed-form result or an invariant that does
not depend on the solver being right. Run it after any sidecar change:

    python3 sidecar/verify.py sidecar/build/br-sidecar
"""
import json
import math
import subprocess
import sys

BLOCK_MM = 1000.0
G = 9.81

fails = []


def check(tag, got, expect, tol):
    rel = abs(got - expect) / max(abs(expect), 1e-30)
    ok = math.isfinite(got) and rel <= tol
    if not ok:
        fails.append(tag)
    print(f"  {'[PASS]' if ok else '[FAIL]'} {tag:<38} got={got:<14.6g} exp={expect:<14.6g} rel={rel:.2e}")


def check_true(tag, cond, detail=""):
    if not cond:
        fails.append(tag)
    print(f"  {'[PASS]' if cond else '[FAIL]'} {tag:<38} {detail}")


class Sidecar:
    def __init__(self, exe):
        self.p = subprocess.Popen([exe], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  text=True, bufsize=1)

    def call(self, req):
        self.p.stdin.write(json.dumps(req) + "\n")
        self.p.stdin.flush()
        line = self.p.stdout.readline()
        if not line:
            raise RuntimeError("sidecar closed the pipe")
        return json.loads(line)

    def close(self):
        try:
            self.p.stdin.write('{"op":"bye"}\n')
            self.p.stdin.flush()
        except Exception:
            pass
        self.p.wait(timeout=5)


def beam_blocks(n, mat="steel", section="steel_rect_200x400", y=64):
    """n blocks along +X at height y; the first one is a support."""
    return [{"x": i, "y": y, "z": 0, "mat": mat, "section": section, "support": i == 0}
            for i in range(n)]


def main():
    exe = sys.argv[1] if len(sys.argv) > 1 else "sidecar/build/br-sidecar"
    sc = Sidecar(exe)

    # ---------------------------------------------------------------- hello
    print("[H] handshake")
    h = sc.call({"op": "hello"})
    check_true("ok", h.get("ok") is True)
    check_true("engine is FrameCore", h.get("engine") == "FrameCore", h.get("engine", ""))
    check_true("materials advertised", "steel" in h.get("materials", []))
    check_true("sections advertised", "steel_rect_200x400" in h.get("sections", []))

    # ------------------------------------------- C1: cantilever vs closed form
    # 5 blocks -> one member, centre-to-centre length 4000 mm, root fixed.
    # Root moment must equal the tip load moment plus the self-weight moment:
    #     M = P*L + w*L^2/2      with w = rho * A * 1e-9 * g   [N/mm]
    print("\n[C1] cantilever, tip load + self weight")
    P = 20000.0                       # N, downward in Minecraft axes
    n = 5
    L = (n - 1) * BLOCK_MM
    b, d = 200.0, 400.0               # steel_rect_200x400
    A = b * d
    rho = 7850.0
    w = rho * A * 1e-9 * G            # N/mm
    m_expect = P * L + w * L * L / 2.0

    r = sc.call({"op": "solve", "revision": 1,
                 "blocks": beam_blocks(n),
                 "loads": [{"x": n - 1, "y": 64, "z": 0, "fy": -P}]})
    check_true("ok", r.get("ok") is True, r.get("error", ""))
    check_true("not singular", r.get("singular") is False, r.get("diagnostic", ""))
    check_true("exactly one member", len(r.get("members", [])) == 1,
               f"got {len(r.get('members', []))}")
    mem = r["members"][0]
    check("member length", mem["lengthMm"], L, 1e-12)
    check("blocks covered", len(mem["blocks"]), n, 1e-12)
    root = math.hypot(mem["i"]["My"], mem["i"]["Mz"])
    check("root moment |M_i|", root, m_expect, 1e-6)

    # D/C must reproduce the elastic screen by hand: sigma / allowable.
    Wz = b * d * d / 6.0
    Wy = d * b * b / 6.0
    dc_strong = (root / Wz) / 350.0
    dc_weak = (root / Wy) / 350.0
    # ONE answer, not "either axis". Accepting the strong OR the weak value was the same
    # mistake ENGINE_FINDINGS.md is about: such a gate cannot detect an axis swap, which
    # is the single defect the non-square fixture rule exists to catch.
    # The load is vertical and the member runs along +X, so the strong axis governs.
    check("D/C vs hand screen (strong axis)", mem["dc"], dc_strong, 1e-6)
    check_true("the two axes really do differ (fixture is non-square)",
               abs(dc_strong - dc_weak) > 1e-9,
               f"strong={dc_strong:.6g} weak={dc_weak:.6g}")
    # This field names the GOVERNING FIBRE, not the load type and not a product failure
    # event. ElasticAllowable takes the argmax of five ratios, and for steel the
    # compressive allowable (350) is below the tensile one (500), so pure bending reaches
    # the compression limit first. It does NOT mean concrete-style crushing, and nothing
    # downstream may route on it alone.
    check_true("governing fibre is the compression face (steel: 350 < 500)",
               mem["governingFibre"] == "CRUSH", mem["governingFibre"])
    check_true("no unassigned blocks", len(r.get("unassigned", [])) == 0)

    # ------------------------------------------- C1b: mode tracks the material
    # Same geometry in concrete, where tension is the weak side (Rtens 3.0 vs
    # Rcomp 30). If the mode field were a fixed label it would still say CRUSH;
    # it must flip to TENSION.
    print("\n[C1b] governing fibre follows the material")
    rc = sc.call({"op": "solve", "revision": 11,
                  "blocks": beam_blocks(n, mat="concrete", section="concrete_rect_400x600")})
    check_true("ok", rc.get("ok") is True, rc.get("error", ""))
    check_true("concrete governs in TENSION", rc["members"][0]["governingFibre"] == "TENSION",
               rc["members"][0]["governingFibre"])
    # Hand screen: self weight only, sigma = (w L^2 / 2) / Wz, D/C = sigma / Rtens.
    cb, cd = 400.0, 600.0                      # concrete_rect_400x600
    wc = 2350.0 * (cb * cd) * 1e-9 * G         # N/mm
    dc_expect = ((wc * L * L / 2.0) / (cb * cd * cd / 6.0)) / 3.0
    check("concrete D/C vs hand screen", rc["members"][0]["dc"], dc_expect, 1e-6)

    # ------------------------------ C1c: fibre signs — the whole point of the overlay
    # A cantilever pushed DOWN at the tip sags: the top face stretches and the
    # bottom face is squashed. The overlay must be able to show that, so the sign
    # of each fibre has to be right in WORLD axes, not just in the engine's local
    # frame. Every fibre carries its own Minecraft-space direction, so this test
    # reads the geometry the same way the renderer will.
    print("\n[C1c] top tension / bottom compression (tension-positive)")
    st_root = mem["stations"][0]
    st_tip = mem["stations"][-1]
    check_true("11 stations", len(mem["stations"]) == 11, str(len(mem["stations"])))

    def fibre_by_dir(station, want_up):
        for f in station["fibres"]:
            dy = f["dir"][1]
            if (dy > 0.5 and want_up) or (dy < -0.5 and not want_up):
                return f
        return None

    top = fibre_by_dir(st_root, True)
    bot = fibre_by_dir(st_root, False)
    check_true("a fibre points up", top is not None)
    check_true("a fibre points down", bot is not None)
    check_true("TOP fibre is in TENSION (sigma > 0)", top["sigma"] > 0, f"{top['sigma']:.4g} MPa")
    check_true("BOTTOM fibre is in COMPRESSION (sigma < 0)", bot["sigma"] < 0, f"{bot['sigma']:.4g} MPa")

    # Pure bending is antisymmetric about the centroid: the two opposing fibres
    # must be equal and opposite once the (tiny) axial term is out of the way.
    check("|sigma_top| == |sigma_bot|", abs(top["sigma"]), abs(bot["sigma"]), 1e-9)

    # And the magnitude is the textbook M*c/I.
    sigma_expect = m_expect * (d / 2.0) / (b * d ** 3 / 12.0)
    check("sigma_top vs M*c/I", top["sigma"], sigma_expect, 1e-6)

    # Neutral axis of a beam with no axial force sits on the centroid.
    check_true("neutral axis reported", "naY" in st_root, str(sorted(st_root.keys())))
    check_true("neutral axis at centroid", abs(st_root.get("naY", 9e9)) < 1e-6,
               f"naY={st_root.get('naY')}")

    # Bending vanishes at a free tip, so the fibres must too. This catches a
    # renderer that paints a whole member one colour: the tip has to fade out.
    tip_top = fibre_by_dir(st_tip, True)
    check_true("tip fibre ~ 0", abs(tip_top["sigma"]) < 1e-6 * abs(top["sigma"]) + 1e-9,
               f"{tip_top['sigma']:.4g} MPa")
    check_true("no neutral axis at an unstressed tip", "naY" not in st_tip)

    # ----------------------------------- EVERY station, not just the two ends.
    # Checking only the root and the tip left nine stations free to be wrong while the
    # gate still printed ALL PASS, and the README claimed "machine precision at every
    # station" on the strength of a printout rather than an assertion.
    #     sigma_top(x) = [P (L-x) + w (L-x)^2 / 2] / W
    W = b * d * d / 6.0
    worst_rel = 0.0
    for st in mem["stations"]:
        a = L - st["x"]
        expect = (P * a + w * a * a / 2.0) / W
        got = fibre_by_dir(st, True)["sigma"]
        rel = abs(got - expect) / max(abs(expect), 1e-30)
        worst_rel = max(worst_rel, rel if abs(expect) > 1e-12 else abs(got))
    check_true(f"all {len(mem['stations'])} stations vs closed form",
               worst_rel < 1e-9, f"worst rel={worst_rel:.2e}")

    # ---------------------------------- C1d: axial force moves the neutral axis
    # Push the member along its own axis as well: the stress diagram shifts, the
    # neutral axis moves off the centroid, and if the axial part is big enough
    # the whole section goes one way and there is no neutral axis at all.
    print("\n[C1d] axial force shifts the neutral axis")
    rax = sc.call({"op": "solve", "revision": 12,
                   "blocks": beam_blocks(n),
                   "loads": [{"x": n - 1, "y": 64, "z": 0, "fy": -P, "fx": -400000.0}]})
    ax_root = rax["members"][0]["stations"][0]
    ax_top = fibre_by_dir(ax_root, True)
    ax_bot = fibre_by_dir(ax_root, False)

    # SIGNED oracle, not just "it moved". sigma(y) = axial + bendY * (y / cz) along the
    # TOP_Y direction, so the crossing is at y0 = -cz (sTop + sBot) / (sTop - sBot).
    # The previous check only asserted |naY| > 1e-3, which a sign error passes — and
    # there was one. A neutral axis drawn on the wrong side of the centroid is worse
    # than none, because it looks authoritative.
    cz = d / 2.0
    na_expect = -cz * (ax_top["sigma"] + ax_bot["sigma"]) / (ax_top["sigma"] - ax_bot["sigma"])
    check("neutral axis position (signed)", ax_root.get("naY", 9e9), na_expect, 1e-9)
    # And the direction is physical: compression added on top pushes the neutral axis up,
    # tension pushes it down. Here fx is -400000 (compression), so it must sit ABOVE the
    # centroid, on the same side as the tensile face.
    check_true("compression axial puts the NA above the centroid",
               ax_root.get("naY", 0.0) > 0, f"naY={ax_root.get('naY')}")
    check_true("fibres no longer equal and opposite",
               abs(abs(ax_top["sigma"]) - abs(ax_bot["sigma"])) > 1e-6)
    # Superposition: the axial term is the same on both faces, so their mean is N/A.
    mean_sigma = (ax_top["sigma"] + ax_bot["sigma"]) / 2.0
    check("mean fibre stress == N/A", mean_sigma, -400000.0 / A, 1e-4)

    # ------------------------------------------------- C2: D/C scales with load
    # Doubling the tip load must not double D/C exactly (self weight is fixed),
    # but the moment must rise by exactly P*L.
    print("\n[C2] load superposition")
    r2 = sc.call({"op": "solve", "revision": 2,
                  "blocks": beam_blocks(n),
                  "loads": [{"x": n - 1, "y": 64, "z": 0, "fy": -2 * P}]})
    root2 = math.hypot(r2["members"][0]["i"]["My"], r2["members"][0]["i"]["Mz"])
    check("moment delta == P*L", root2 - root, P * L, 1e-6)

    # ------------------------------------------------------- C3: length scaling
    # A cantilever twice as long under self weight alone carries 4x the root
    # moment: M = w*L^2/2.
    print("\n[C3] self-weight scaling  M ~ L^2")
    ra = sc.call({"op": "solve", "revision": 3, "blocks": beam_blocks(3)})
    rb = sc.call({"op": "solve", "revision": 4, "blocks": beam_blocks(5)})
    ma = math.hypot(ra["members"][0]["i"]["My"], ra["members"][0]["i"]["Mz"])
    mb = math.hypot(rb["members"][0]["i"]["My"], rb["members"][0]["i"]["Mz"])
    check("M(4m)/M(2m)", mb / ma, 4.0, 1e-6)

    # ------------------------------------------------- C4: mechanism detection
    # No support anywhere: the stiffness matrix is singular and the sidecar must
    # say so rather than return numbers.
    print("\n[C4] mechanism (no support)")
    blocks = beam_blocks(n)
    for blk in blocks:
        blk["support"] = False
    r4 = sc.call({"op": "solve", "revision": 5, "blocks": blocks})
    check_true("ok (not a protocol error)", r4.get("ok") is True, r4.get("error", ""))
    check_true("singular reported", r4.get("singular") is True)
    check_true("diagnostic non-empty", bool(r4.get("diagnostic")), r4.get("diagnostic", ""))
    check_true("no member results", len(r4.get("members", [])) == 0)

    # ----------------------------------------------- C5: single block rejected
    # One block is L/h = 1 -- not a beam. It must be reported as unassigned
    # rather than silently modelled as a stub member.
    print("\n[C5] single block is not a member")
    r5 = sc.call({"op": "solve", "revision": 6,
                  "blocks": [{"x": 0, "y": 64, "z": 0, "mat": "steel",
                              "section": "steel_rect_200x400", "support": True}]})
    check_true("ok", r5.get("ok") is True)
    check_true("no members", len(r5.get("members", [])) == 0)

    # ------------------------------------------------ C6: junction splits runs
    # An L shape shares its corner block, so it must become two members joined
    # at one node -- not one bent member and not two disconnected ones.
    print("\n[C6] L junction -> two members, shared node")
    lshape = [{"x": i, "y": 64, "z": 0, "mat": "steel", "section": "steel_rect_200x400",
               "support": i == 0} for i in range(4)]
    lshape += [{"x": 3, "y": 64, "z": k, "mat": "steel", "section": "steel_rect_200x400",
                "support": False} for k in range(1, 4)]
    r6 = sc.call({"op": "solve", "revision": 7, "blocks": lshape})
    check_true("ok", r6.get("ok") is True, r6.get("error", ""))
    check_true("two members", len(r6.get("members", [])) == 2,
               f"got {len(r6.get('members', []))}")
    check_true("no unassigned blocks", len(r6.get("unassigned", [])) == 0,
               str(r6.get("unassigned", [])))

    # ------------------- C8: the interior governs — the case end-only screening missed
    # Screening only endI and endJ cannot see a member whose worst section is in the
    # middle. Such a member came back essentially unstressed: silently safe, the most
    # dangerous kind of wrong.
    #
    # Constructing that case needs care. The obvious fixture — a beam supported at both
    # ends — does NOT work here: supports are fixAll and extraction puts nodes only at
    # run ends, so a two-node member with both ends fixed has no free DOF at all and the
    # engine correctly answers "fully constrained". (Recorded as a real limitation of the
    # current extraction, not worked around.)
    #
    # A cantilever with an UPWARD tip load does work, and is a sharper test:
    #     M(a) = -P a + w a^2 / 2         a = L - x
    # With P = w L / 2 the moment is exactly zero at BOTH ends and peaks at midspan:
    #     a* = P / w = L/2   ->   M* = -w L^2 / 8
    # So end-only screening reports ~0 while the real peak is w L^2 / 8.
    print("\n[C8] the interior governs: both ends ~0, midspan carries w L^2 / 8")
    n8 = 9
    L8 = (n8 - 1) * BLOCK_MM
    P8 = w * L8 / 2.0                      # upward, so +fy
    r_ss = sc.call({"op": "solve", "revision": 20,
                    "blocks": beam_blocks(n8),
                    "loads": [{"x": n8 - 1, "y": 64, "z": 0, "fy": P8}]})
    check_true("ok", r_ss.get("ok") is True, r_ss.get("error", ""))
    check_true("not singular", r_ss.get("singular") is False, r_ss.get("diagnostic", ""))
    m8 = r_ss["members"][0]

    W8 = b * d * d / 6.0
    check_true("both ends carry ~no moment",
               abs(m8["i"]["Mz"]) < 1e-3 and abs(m8["j"]["Mz"]) < 1e-3,
               f"Mi={m8['i']['Mz']:.4g} Mj={m8['j']['Mz']:.4g}")

    sigma_mid_expect = (w * L8 * L8 / 8.0) / W8
    mid = min(m8["stations"], key=lambda s: abs(s["x"] - L8 / 2.0))
    mid_top = fibre_by_dir(mid, True)
    check("midspan sigma vs closed form", abs(mid_top["sigma"]), sigma_mid_expect, 1e-6)

    # The analytic extremum lands exactly at midspan, so it must be one of the stations.
    check_true("the extremum station exists", abs(mid["x"] - L8 / 2.0) < 1e-6,
               f"nearest station x={mid['x']}")
    check_true("a governing station is reported", m8.get("governingStation", -1) >= 0,
               str(m8.get("governingStation")))

    # THE REGRESSION: D/C must come from the worst station, not from the two ends.
    check("D/C from the interior", m8["dc"], sigma_mid_expect / 350.0, 1e-6)
    check_true("end-only screening would have said ~0",
               m8["dc"] > 100 * (abs(m8["i"]["Mz"]) / W8) / 350.0 + 1e-6,
               f"dc={m8['dc']:.6g}")

    # -------------------------------- C9: fail-closed input, not silent defaults
    # Each of these used to be quietly turned into a DIFFERENT solvable structure,
    # with ok:true on the reply.
    print("\n[C9] malformed input is refused, not reinterpreted")

    def rejects(tag, req):
        rr = sc.call(req)
        check_true(tag, rr.get("ok") is False, str(rr)[:120])

    good = {"x": 0, "y": 64, "z": 0, "mat": "steel", "section": "steel_rect_200x400",
            "support": True}
    nxt = {"x": 1, "y": 64, "z": 0, "mat": "steel", "section": "steel_rect_200x400"}

    rejects("missing material", {"op": "solve", "revision": 30,
                                "blocks": [{k: v for k, v in good.items() if k != "mat"}, nxt]})
    rejects("missing section", {"op": "solve", "revision": 31,
                               "blocks": [{k: v for k, v in good.items() if k != "section"}, nxt]})
    rejects("unknown section", {"op": "solve", "revision": 32,
                                "blocks": [dict(good, section="steel_h400"), nxt]})
    rejects("non-integer coordinate", {"op": "solve", "revision": 33,
                                       "blocks": [dict(good, x=0.5), nxt]})
    rejects("duplicate coordinate", {"op": "solve", "revision": 34,
                                     "blocks": [good, dict(good, support=False), nxt]})
    rejects("missing revision", {"op": "solve", "blocks": [good, nxt]})
    rejects("non-integer revision", {"op": "solve", "revision": 1.5, "blocks": [good, nxt]})

    # A load in the middle of a member has nowhere to go in this model. It must be
    # refused, not dropped — dropping it reports the structure as SAFER than it is.
    rejects("load inside a member",
            {"op": "solve", "revision": 35,
             "blocks": beam_blocks(5),
             "loads": [{"x": 2, "y": 64, "z": 0, "fy": -P}]})

    rejects("non-finite load",
            {"op": "solve", "revision": 36, "blocks": beam_blocks(5),
             "loads": [{"x": 4, "y": 64, "z": 0, "fy": 1e999}]})

    # ------------------------------------------------- C10: section change splits
    # A run whose section changes halfway must not be solved as one member with the
    # head block's section.
    print("\n[C10] a section change splits the run")
    mixed = [{"x": i, "y": 64, "z": 0, "mat": "steel",
              "section": "steel_rect_200x400" if i < 3 else "steel_rect_100x200",
              "support": i == 0} for i in range(6)]
    r10 = sc.call({"op": "solve", "revision": 40, "blocks": mixed})
    check_true("ok", r10.get("ok") is True, r10.get("error", ""))
    secs = sorted(m["section"] for m in r10.get("members", []))
    check_true("two members, one per section", secs == ["steel_rect_100x200", "steel_rect_200x400"],
               str(secs))

    # ------------------------------------------------- C7: bad input is safe
    # Unknown ids and malformed lines must produce an error line, never a crash
    # and never a silent default.
    print("\n[C7] fail-safe on bad input")
    r7 = sc.call({"op": "solve", "revision": 8,
                  "blocks": [{"x": 0, "y": 64, "z": 0, "mat": "unobtainium",
                              "section": "steel_rect_200x400", "support": True},
                             {"x": 1, "y": 64, "z": 0, "mat": "unobtainium",
                              "section": "steel_rect_200x400"}]})
    check_true("unknown material rejected", r7.get("ok") is False, str(r7))
    check_true("error names the material", "unobtainium" in r7.get("error", ""),
               r7.get("error", ""))

    r8 = sc.call({"op": "nonsense"})
    check_true("unknown op rejected", r8.get("ok") is False)

    sc.p.stdin.write("this is not json\n")
    sc.p.stdin.flush()
    bad = json.loads(sc.p.stdout.readline())
    check_true("malformed line survives", bad.get("ok") is False, bad.get("error", ""))

    r9 = sc.call({"op": "hello"})
    check_true("still alive after bad input", r9.get("ok") is True)

    sc.close()

    print()
    if fails:
        print(f"FAILED {len(fails)}: {', '.join(fails)}")
        return 1
    print("ALL PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
