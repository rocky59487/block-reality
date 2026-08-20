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

    def raw(self, line):
        """One raw request line, for malformed-input gates the dict API cannot express."""
        self.p.stdin.write(line + "\n")
        self.p.stdin.flush()
        reply = self.p.stdout.readline()
        if not reply:
            raise RuntimeError("sidecar closed the pipe")
        return json.loads(reply)

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

    # ------- C8b: the moment must CHANGE SIGN along a beam held at both ends
    # The bug this exists for: FrameCore reports end J as an element END ACTION, whose
    # sign flips with the member's direction, not as a section force. Interpolating
    # between end I and a raw end J therefore produces a diagram that never changes
    # sign — a beam held at both ends came out reading tension along its whole length,
    # supports and midspan alike.
    #
    # A cantilever cannot catch this. Its free end carries no moment at all, so the
    # wrong term is multiplied by zero. It needs a member with moment at BOTH ends,
    # which needs an interior node, which is why this fixture has a stub at midspan.
    print("\n[C8b] hogging at the support, sagging at midspan")
    n9 = 9
    L9 = (n9 - 1) * BLOCK_MM
    ff = [{"x": i, "y": 64, "z": 0, "mat": "steel", "section": "steel_rect_200x400",
           "support": i == 0 or i == n9 - 1} for i in range(n9)]
    # A vertical stub at midspan: two blocks make a run, the shared block becomes a node,
    # and the beam gains the interior degree of freedom it needs to bend.
    ff += [{"x": 4, "y": 65, "z": 0, "mat": "steel", "section": "steel_rect_200x400"},
           {"x": 4, "y": 66, "z": 0, "mat": "steel", "section": "steel_rect_200x400"}]
    r8b = sc.call({"op": "solve", "revision": 21, "blocks": ff})
    check_true("ok", r8b.get("ok") is True, r8b.get("error", ""))
    check_true("not singular", r8b.get("singular") is False, r8b.get("diagnostic", ""))

    # The half-span member that starts at a support.
    span = None
    for mem in r8b.get("members", []):
        blocks = mem["blocks"]
        if blocks[0] == [0, 64, 0] or blocks[-1] == [0, 64, 0]:
            span = mem
    check_true("found the member at the support", span is not None)

    if span is not None:
        sig = [fibre_by_dir(st, True)["sigma"] for st in span["stations"]]
        check_true("top fibre changes sign along the member",
                   any(a > 0 for a in sig) and any(a < 0 for a in sig),
                   f"top sigma from {sig[0]:.3f} to {sig[-1]:.3f}")
        # Hogging at the built-in end puts the TOP in tension there; between the
        # supports the beam sags and the top goes into compression.
        check_true("support end is in tension on top", sig[0] > 0, f"{sig[0]:.4g}")
        check_true("the far end is in compression on top", sig[-1] < 0, f"{sig[-1]:.4g}")

    # Both halves report the SAME section moment at the node they share. This is the
    # invariant the end-action bug broke, and it needs no closed form to state.
    halves = [mm for mm in r8b["members"] if mm["blocks"][0][1] == 64 and mm["blocks"][-1][1] == 64]
    check_true("two half-span members", len(halves) == 2, str(len(halves)))
    if len(halves) == 2:
        a, bb = sorted(halves, key=lambda mm: mm["blocks"][0][0])
        check("adjacent members agree at the shared node", a["j"]["Mz"], bb["i"]["Mz"], 1e-12)

        # Closed form for what this actually is: a fixed-fixed beam under its own weight
        # PLUS the hanging stub's weight as a central point load.
        #   M_support = w L^2 / 12 + P L / 8      (hogging, positive here)
        #   M_midspan = -(w L^2 / 24 + P L / 8)   (sagging)
        p_stub = w * 2 * BLOCK_MM                       # the stub is a 2 m run
        m_support = w * L9 * L9 / 12.0 + p_stub * L9 / 8.0
        m_mid = -(w * L9 * L9 / 24.0 + p_stub * L9 / 8.0)
        check("support moment vs closed form", a["i"]["Mz"], m_support, 1e-6)
        check("midspan moment vs closed form", a["j"]["Mz"], m_mid, 1e-6)

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

    # A load on a block that belongs to NO element still has nowhere to go, and is still
    # refused rather than dropped — dropping it reports the structure as SAFER than it is.
    # (A load in the middle of a member is a different case now: see C11.)
    rejects("load on a block that is no element",
            {"op": "solve", "revision": 35,
             "blocks": [{"x": 0, "y": 64, "z": 0, "mat": "steel",
                         "section": "steel_rect_200x400", "support": True}],
             "loads": [{"x": 0, "y": 64, "z": 0, "fy": -P}]})

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

    # ------------------------------------------- C11: a load splits the member
    # A load anywhere along a member used to refuse the WHOLE request, so hanging a weight
    # on the middle of a beam — the most obvious thing anyone would do with this mod —
    # killed the analysis of every structure in the world. The loaded block is now a node,
    # so the run splits there and the load lands exactly where it was put.
    print("\n[C11] a load in the middle of a member splits it")
    n11, P11 = 9, 20000.0
    a11 = 4 * BLOCK_MM                       # the load is 4 m from the root
    L11 = (n11 - 1) * BLOCK_MM
    r11 = sc.call({"op": "solve", "revision": 37, "blocks": beam_blocks(n11),
                   "loads": [{"x": 4, "y": 64, "z": 0, "fy": -P11}]})
    check_true("ok", r11.get("ok") is True, r11.get("error", ""))
    check_true("the run split at the loaded block", len(r11.get("members", [])) == 2,
               str([(m["blocks"][0], m["blocks"][-1]) for m in r11.get("members", [])]))
    # Closed form for a cantilever with a point load at distance a plus its own weight.
    # The load's position enters through a, so a load quietly moved to the nearest end
    # would show up here as a wrong root moment rather than as a passing test.
    check("root moment with the load at midspan", r11["members"][0]["i"]["Mz"],
          P11 * a11 + w * L11 * L11 / 2.0, 1e-12)
    check_true("both halves are present and share the loaded block",
               r11["members"][0]["blocks"][-1] == r11["members"][1]["blocks"][0] == [4, 64, 0],
               str(r11["members"][0]["blocks"][-1]))
    check("equilibrium still closes", r11["equilibrium"]["residual"], 0.0, 1e-10)

    # The same load one block further along must give a DIFFERENT root moment. Without
    # this, a bug that snapped every load to the same node would pass the check above.
    r11b = sc.call({"op": "solve", "revision": 38, "blocks": beam_blocks(n11),
                    "loads": [{"x": 5, "y": 64, "z": 0, "fy": -P11}]})
    check("moving the load moves the moment", r11b["members"][0]["i"]["Mz"],
          P11 * 5 * BLOCK_MM + w * L11 * L11 / 2.0, 1e-12)

    # ============================================================ BUCKLING ====
    # A stress check is not a stability check, and the gap is not small: Euler's critical
    # load falls with the SQUARE of the length while the stress ratio does not know the
    # length exists. A tall thin column is exactly what a player builds first.
    print("\n[C12] linear buckling vs Euler")
    E_STEEL = 200000.0
    b_col, d_col = 200.0, 400.0
    IY_WEAK = d_col * b_col ** 3 / 12.0          # buckling takes the WEAK axis
    A_COL = b_col * d_col

    def column(n, p_newton, splits=(), y0=64):
        blocks = [{"x": 0, "y": y0 + i, "z": 0, "mat": "steel",
                   "section": "steel_rect_200x400", "support": i == 0} for i in range(n)]
        # A zero-magnitude load adds no force but does create a NODE, so it refines the
        # mesh without touching the reference load the eigenvalue scales.
        loads = [{"x": 0, "y": y0 + n - 1, "z": 0, "fy": -p_newton}]
        loads += [{"x": 0, "y": y0 + k, "z": 0, "fy": 0.0} for k in splits]
        return blocks, loads

    n12 = 17
    L12 = (n12 - 1) * BLOCK_MM
    # Self weight is part of the reference load the eigenvalue scales, and a DISTRIBUTED
    # axial load is far less destabilising than a tip load — so the tip-load Euler formula
    # is only a valid reference when self weight is negligible against P. At P = 2e7 it is
    # half a per cent. Comparing against Euler with a heavy column instead made refinement
    # look divergent, when what was wrong was the reference.
    P12 = 2.0e7
    W12 = 7850.0 * A_COL * 1e-9 * G * L12
    euler = math.pi ** 2 * E_STEEL * IY_WEAK / (2 * L12) ** 2     # fixed-free, K = 2

    blocks, loads = column(n12, P12)
    r12 = sc.call({"op": "solve", "revision": 60, "blocks": blocks, "loads": loads})
    check_true("one element per column", len(r12.get("members", [])) == 1)
    # ONE 2-node element with the consistent geometric stiffness gives the textbook
    # P_cr = 2.4860 EI/L^2 against Euler's 2.4674 — a ratio of 1.00754. Pinning the known
    # discretisation value rather than "close to Euler" makes an accidental change visible.
    check("single-element column matches the textbook value",
          r12["bucklingFactor"] * (P12 + W12) / euler, 2.4860 / 2.4674, 2e-4)

    blocks, loads = column(n12, P12, splits=(4, 8, 12))
    r12b = sc.call({"op": "solve", "revision": 61, "blocks": blocks, "loads": loads})
    check_true("the column refined into four elements", len(r12b.get("members", [])) == 4)
    check("refined column converges onto Euler",
          r12b["bucklingFactor"] * (P12 + W12) / euler, 1.0, 0.006)

    # The whole point, as one number pair: a column the stress screen calls safe.
    print("\n[C13] a stress check is not a stability check")
    n13 = 21
    L13 = (n13 - 1) * BLOCK_MM
    P13 = 400000.0
    blocks, loads = column(n13, P13)
    r13 = sc.call({"op": "solve", "revision": 62, "blocks": blocks, "loads": loads})
    dc13 = r13["maxDC"]
    lam13 = r13["bucklingFactor"]
    check_true("the stress screen reports it comfortably safe", dc13 < 0.05, f"D/C = {dc13:.4f}")
    check_true("but it is already past its buckling load", 0 < lam13 < 1.0,
               f"lambda_cr = {lam13:.4f}")

    # Shorten the same column and it becomes stable again — the factor has to depend on
    # length, which is the property a stress ratio structurally cannot have.
    blocks, loads = column(9, P13)
    r13b = sc.call({"op": "solve", "revision": 63, "blocks": blocks, "loads": loads})
    check_true("the same load on a short column is stable", r13b["bucklingFactor"] > 4.0,
               f"lambda_cr = {r13b['bucklingFactor']:.4f}")

    # ... and the critical LOAD follows 1/L^2. Comparing the two lambdas directly does not
    # test that: lambda scales the reference load, which includes each column's own weight,
    # and the two columns do not weigh the same. So the comparison is made on P_cr with the
    # applied load large enough that self weight is negligible in both.
    P_BIG = 2.0e7
    pcr = {}
    for nn in (21, 9):
        bl, ld = column(nn, P_BIG)
        rr = sc.call({"op": "solve", "revision": 64 + nn, "blocks": bl, "loads": ld})
        w_self = 7850.0 * A_COL * 1e-9 * G * (nn - 1) * BLOCK_MM
        pcr[nn] = rr["bucklingFactor"] * (P_BIG + w_self)
    check("critical load scales as 1/L^2", pcr[9] / pcr[21], (20.0 / 8.0) ** 2, 0.01)

    # ======================================================= SHELL BUCKLING ==
    # Without shell geometric stiffness a thin wall in compression reports no buckling risk
    # at all — the same unsafe-direction gap the column check closes, one element type over.
    print("\n[C14] a wall buckles out of plane")
    W_E, W_NU, W_T = 30000.0, 0.20, 200.0
    W_D = W_E * W_T ** 3 / (12 * (1 - W_NU ** 2))     # plate rigidity: D, not EI

    def wall_column(nw, nh, p_newton, y0=64):
        blocks = [{"x": i, "y": y0 + j, "z": 0, "mat": "concrete",
                   "section": "concrete_slab_200", "support": j == 0}
                  for i in range(nw) for j in range(nh)]
        loads = [{"x": i, "y": y0 + nh - 1, "z": 0, "fy": -p_newton / nw} for i in range(nw)]
        return blocks, loads

    P_WALL = 3.0e7                       # self weight negligible against it
    q_wall = 2350.0 * W_T * 9810.0 * 1e-12
    scaled = []
    for nw, nh in ((3, 13), (3, 21), (3, 31), (5, 21), (9, 21)):
        blocks, loads = wall_column(nw, nh, P_WALL)
        rb = sc.call({"op": "solve", "revision": 500 + nw * 40 + nh,
                      "blocks": blocks, "loads": loads})
        lam = rb.get("bucklingFactor", 0)
        check_true(f"wall {nw - 1}x{nh - 1}: a buckling factor is reported", lam > 0,
                   f"lambda_cr = {lam}")
        w_mm, h_mm = (nw - 1) * BLOCK_MM, (nh - 1) * BLOCK_MM
        p_cr = lam * (P_WALL + q_wall * w_mm * h_mm)
        # A cantilever plate strip: P_cr = pi^2 D w / (2h)^2. D and not EI — a plate resists
        # anticlastic curvature, which is a factor 1/(1-nu^2) = 1.0417 here and would show up
        # as a 4% "error" against the beam formula.
        ref = math.pi ** 2 * W_D * w_mm / (2 * h_mm) ** 2
        scaled.append((nw, nh, p_cr, ref, p_cr * h_mm ** 2 / w_mm))

    # The 1/h^2 law is reference-free: three heights at one width must give the same
    # P_cr*h^2/w. This tests the physics without depending on which idealisation applies.
    fixed_width = [x for x in scaled if x[0] == 3]
    base = fixed_width[0][4]
    for nw, nh, _, _, k in fixed_width:
        check(f"wall {nw - 1}x{nh - 1}: P_cr follows 1/h^2", k, base, 5e-3)

    # And the value itself approaches the plate reference as the mesh across the width is
    # refined: 2 elements wide is 3% low, 8 elements wide under 1%.
    narrow = [x for x in scaled if x[0] == 3 and x[1] == 21][0]
    wide = [x for x in scaled if x[0] == 9][0]
    check_true("wall: 2 elements across is within 4% of the plate reference",
               abs(narrow[2] / narrow[3] - 1) < 0.04, f"{narrow[2] / narrow[3]:.4f}")
    check_true("wall: refining across the width converges onto it",
               abs(wide[2] / wide[3] - 1) < abs(narrow[2] / narrow[3] - 1),
               f"{wide[2] / wide[3]:.4f} vs {narrow[2] / narrow[3]:.4f}")

    # ============================================================ ISLANDS =====
    # A world is not one structure. Before this, one unsupported beam anywhere in the
    # dimension made the single global stiffness matrix rank-deficient and EVERY building
    # reported nothing — measured, and the reason the mod appeared to die as soon as a
    # second structure existed.
    print("\n[S0] separate buildings are separate problems")
    alone = sc.call({"op": "solve", "revision": 40, "blocks": beam_blocks(5), "loads": []})
    floating = [{"x": i + 100, "y": 64, "z": 0, "mat": "steel",
                 "section": "steel_rect_200x400"} for i in range(5)]
    both = sc.call({"op": "solve", "revision": 41,
                    "blocks": beam_blocks(5) + floating, "loads": []})
    check_true("the sound building still solves", both.get("ok") is True and both.get("members"),
               f"members={len(both.get('members', []))}")
    check("D/C unchanged by a neighbour", both.get("maxDC", 0), alone.get("maxDC", 0), 0)
    check_true("both islands counted", both.get("islands") == 2, str(both.get("islands")))
    check_true("only the mechanism is a mechanism", both.get("singularIslands") == 1,
               str(both.get("singularIslands")))

    # A load on a block belonging to NO element must still be refused, per island or not:
    # the check is global, so no island may quietly decide the load is "not mine" and let it
    # vanish. The block here sits between the two buildings and forms nothing.
    bad_load = sc.call({"op": "solve", "revision": 42,
                        "blocks": beam_blocks(5) + floating
                        + [{"x": 50, "y": 64, "z": 0, "mat": "steel",
                            "section": "steel_rect_200x400", "support": False}],
                        "loads": [{"x": 50, "y": 64, "z": 0, "fy": -1000.0}]})
    check_true("a load on no element is refused with many islands", bad_load.get("ok") is False,
               bad_load.get("error", ""))

    # ... and a load INSIDE a member of one island is now fine, and belongs to that island.
    ok_load = sc.call({"op": "solve", "revision": 43,
                       "blocks": beam_blocks(5) + floating,
                       "loads": [{"x": 2, "y": 64, "z": 0, "fy": -1000.0}]})
    check_true("an interior load is carried, not refused", ok_load.get("ok") is True,
               ok_load.get("error", ""))
    check_true("and it split that island's member", len(ok_load.get("members", [])) == 2,
               f"members={len(ok_load.get('members', []))}")

    # ============================================================= PLATES =====
    # MITC4 flat shells. Every reference below is Timoshenko's clamped square plate under
    # a uniform load, computed here rather than read back from the engine.
    T_E, T_NU, T_RHO, T_T = 30000.0, 0.20, 2350.0, 200.0
    G_MM = 9810.0
    q_plate = T_RHO * T_T * G_MM * 1e-12          # N/mm^2, the slab's own weight

    # The tabulated centre coefficient 0.0231 is for nu = 0.3. At the centre of a square
    # clamped plate the two curvatures are equal by symmetry, so M = D*k*(1+nu) and the
    # coefficient rescales as (1+nu)/1.3. The EDGE coefficient does not: the tangential
    # curvature vanishes along a clamped edge, so M_edge = -D*w,nn is nu-free.
    C_CENTRE = 0.0231 / 1.3 * (1 + T_NU)
    C_EDGE = 0.0513

    def slab(n, y=64, mat="concrete", plate="concrete_slab_200", clamped=True):
        return [{"x": i, "y": y, "z": j, "mat": mat, "section": plate,
                 "support": clamped and (i in (0, n - 1) or j in (0, n - 1))}
                for i in range(n) for j in range(n)]

    def corner_moment(res, wx, wz, comp):
        """Per-corner moment component at a given world position, or None."""
        for sh in res.get("shells", []):
            for k, w in enumerate(sh["world"]):
                if abs(w[0] - wx) < 1e-6 and abs(w[2] - wz) < 1e-6:
                    return sh["Mc"][k][comp]
        return None

    print("\n[S1] a slab is a plate, not a grid of beams")
    n = 7
    r_slab = sc.call({"op": "solve", "revision": 50, "blocks": slab(n), "loads": []})
    check_true("ok", r_slab.get("ok") is True, r_slab.get("error", ""))
    check_true("no members extracted from a slab", len(r_slab.get("members", [])) == 0,
               f"members={len(r_slab.get('members', []))}")
    check_true("one facet per 2x2 block square",
               len(r_slab.get("shells", [])) == (n - 1) ** 2,
               f"shells={len(r_slab.get('shells', []))} expected={(n - 1) ** 2}")
    check_true("nothing left unassigned", len(r_slab.get("unassigned", [])) == 0,
               str(r_slab.get("unassigned", [])[:4]))
    # Beam tokens in the same shape are the counter-example: that IS a grillage, and it
    # double-counts every block. The token is what tells the two cases apart.
    r_grid = sc.call({"op": "solve", "revision": 51,
                      "blocks": [{"x": i, "y": 64, "z": j, "mat": "steel",
                                  "section": "steel_rect_200x400",
                                  "support": i in (0, n - 1) or j in (0, n - 1)}
                                 for i in range(n) for j in range(n)], "loads": []})
    check_true("beam tokens still extract as members", len(r_grid.get("members", [])) > 0,
               f"members={len(r_grid.get('members', []))}")
    check_true("and produce no facets", len(r_grid.get("shells", [])) == 0)

    print("\n[S2] shell self weight is conserved, and equilibrium closes")
    eq = r_slab["equilibrium"]
    a_mesh = (n - 1) * BLOCK_MM                      # the MESHED span, centre to centre
    check("applied weight = q * meshed area", eq["applied"][1], -q_plate * a_mesh * a_mesh, 1e-12)
    check_true("equilibrium residual at machine precision", eq["residual"] < 1e-10,
               f"residual={eq['residual']:.3e}")
    check("horizontal equilibrium x", eq["applied"][0] + eq["reaction"][0], 0.0, 1e-30)
    check("horizontal equilibrium z", eq["applied"][2] + eq["reaction"][2], 0.0, 1e-30)

    print("\n[S3] facet frame is orthonormal and points the way it says")
    sh0 = r_slab["shells"][0]
    for name in ("ex", "ey", "n"):
        v = sh0[name]
        check(f"|{name}| = 1", math.sqrt(sum(c * c for c in v)), 1.0, 1e-12)
    check("ex . ey = 0", sum(a * b for a, b in zip(sh0["ex"], sh0["ey"])), 0.0, 1e-30)
    check_true("a floor's normal is up", sh0["n"] == [0, 1, 0], str(sh0["n"]))
    # A floor carries no in-plane force under gravity alone. Anything else would mean the
    # membrane and bending blocks are coupled where they must not be.
    check("no membrane force under transverse load", sh0["N"]["xx"], 0.0, 1e-30)

    print("\n[S4] clamped plate: span moment vs Timoshenko, and it converges")
    prev_err = None
    for nn in (9, 13):
        rr = sc.call({"op": "solve", "revision": 60 + nn, "blocks": slab(nn), "loads": []})
        aa = (nn - 1) * BLOCK_MM
        c = (nn - 1) / 2.0 * BLOCK_MM + BLOCK_MM / 2.0
        got = corner_moment(rr, c, c, 0)
        ref = C_CENTRE * q_plate * aa * aa
        err = abs(abs(got) - ref) / ref
        check_true(f"span moment within 1% at {nn - 1} elements", err < 0.01,
                   f"got={got:.1f} ref={-ref:.1f} err={err * 100:.2f}%")
        if prev_err is not None:
            check_true("and it got better with more elements", err < prev_err,
                       f"{prev_err * 100:.2f}% -> {err * 100:.2f}%")
        prev_err = err

    print("\n[S5] support moment is recovered, not read")
    # Corner-sampled MITC4 moments are not superconvergent and are badly LOW at a clamped
    # edge — an under-report of the moment that governs the support is a silently-safe
    # answer. The recovery extrapolates from the two interior element centres. This checks
    # that it fires, that it moves towards the reference, and that it never lowers a D/C.
    for nn, tol in ((9, 0.20), (17, 0.06)):
        rr = sc.call({"op": "solve", "revision": 70 + nn, "blocks": slab(nn), "loads": []})
        aa = (nn - 1) * BLOCK_MM
        c = (nn - 1) / 2.0 * BLOCK_MM + BLOCK_MM / 2.0
        ref = C_EDGE * q_plate * aa * aa
        edge = [sh for sh in rr["shells"] if sh["edgeRecovered"]]
        check_true(f"recovery fired at {nn - 1} elements", len(edge) > 0, f"{len(edge)} facets")
        got = None
        for sh in edge:
            for k, w in enumerate(sh["world"]):
                if abs(w[0] - c) < BLOCK_MM and abs(w[2] - BLOCK_MM / 2.0) < 1e-6:
                    got = sh["Mc"][k][1]
        err = abs(abs(got) - ref) / ref
        check_true(f"support moment within {tol * 100:.0f}% at {nn - 1} elements", err < tol,
                   f"got={got:.1f} ref={ref:.1f} err={err * 100:.1f}%")
        check_true("recovery never lowers a demand",
                   all(sh["dc"] >= sh["dcRaw"] - 1e-12 for sh in rr["shells"]))

    print("\n[S6] plate blocks that cannot form a facet are reported, not dropped")
    lone = sc.call({"op": "solve", "revision": 80,
                    "blocks": [{"x": 0, "y": 64, "z": 0, "mat": "concrete",
                                "section": "concrete_slab_200", "support": True}], "loads": []})
    check_true("a lone plate block is unassigned", len(lone.get("unassigned", [])) == 1,
               str(lone.get("unassigned")))
    strip = sc.call({"op": "solve", "revision": 81,
                     "blocks": [{"x": i, "y": 64, "z": 0, "mat": "concrete",
                                 "section": "concrete_slab_200", "support": i == 0}
                                for i in range(5)], "loads": []})
    check_true("a one-block-wide strip is unassigned",
               len(strip.get("unassigned", [])) == 5 and not strip.get("shells"),
               f"unassigned={len(strip.get('unassigned', []))}")
    # Two sheets stacked is a SOLID. Meshing it as three intersecting sheets would triple
    # its mass and stiffness — the grillage bug wearing a different hat.
    solid = sc.call({"op": "solve", "revision": 82,
                     "blocks": [{"x": i, "y": 64 + h, "z": j, "mat": "concrete",
                                 "section": "concrete_slab_200", "support": False}
                                for i in range(3) for j in range(3) for h in range(2)],
                     "loads": []})
    check_true("a solid block of plate is refused, not meshed",
               len(solid.get("shells", [])) == 0 and len(solid.get("unassigned", [])) == 18,
               f"shells={len(solid.get('shells', []))} unassigned={len(solid.get('unassigned', []))}")

    print("\n[S7] a column bears on the slab it holds up")
    # Four columns under the corners of a 3x3 slab. The slab itself carries no support, so
    # the ONLY way this is not a mechanism is if each column's top node IS a slab node.
    bl = [{"x": i, "y": 68, "z": j, "mat": "concrete", "section": "concrete_slab_200"}
          for i in range(3) for j in range(3)]
    for (ci, cj) in ((0, 0), (0, 2), (2, 0), (2, 2)):
        for h in range(64, 68):
            bl.append({"x": ci, "y": h, "z": cj, "mat": "steel",
                       "section": "steel_rect_200x400", "support": h == 64})
    r_col = sc.call({"op": "solve", "revision": 90, "blocks": bl, "loads": []})
    check_true("ok", r_col.get("ok") is True, r_col.get("error", ""))
    check_true("not a mechanism", r_col.get("singular") is False, r_col.get("diagnostic", ""))
    check_true("one connected structure", r_col.get("islands") == 1, str(r_col.get("islands")))
    check_true("four columns and four facets",
               len(r_col.get("members", [])) == 4 and len(r_col.get("shells", [])) == 4,
               f"members={len(r_col.get('members', []))} shells={len(r_col.get('shells', []))}")
    eqc = r_col["equilibrium"]
    check_true("columns and slab both weigh in", eqc["residual"] < 1e-9,
               f"residual={eqc['residual']:.3e}")
    # The columns must actually carry the slab: their axial force at the base has to add up
    # to the whole weight of the structure. This is the connection, stated as a number.
    check("columns carry the total weight",
          sum(-mm["i"]["N"] for mm in r_col["members"]), eqc["applied"][1], 1e-9)

    # ========================================================== SHEAR WALLS ==
    # D-005 chose MITC4 over grillage on one specific ground: a grillage restrains the
    # in-plane DOFs at every node, so it CANNOT carry a shear wall's load path at all.
    # That justification had never been tested. A wall carries lateral load two ways at
    # once, and both are checked here against closed forms.
    print("\n[S8] a shear wall carries load in its own plane")

    def wall(nw, nh, shear_n, y0=64):
        """nw wide, nh tall, base row clamped, `shear_n` total pushed along +x at the top."""
        blocks = [{"x": i, "y": y0 + j, "z": 0, "mat": "concrete",
                   "section": "concrete_slab_200", "support": j == 0}
                  for i in range(nw) for j in range(nh)]
        loads = [{"x": i, "y": y0 + nh - 1, "z": 0, "fx": shear_n / nw} for i in range(nw)]
        return blocks, loads

    def centre(sh, k):
        return sum(w[k] for w in sh["world"]) / 4.0

    V_WALL = 100000.0
    # Tolerances set from the MEASURED agreement, not from what felt safe to claim. With
    # the QM6 membrane these land at 1e-5 and better, so a gate at 1% would pass through
    # a hundredfold regression without noticing.
    for nw, nh, tol_v, tol_m in ((5, 13, 1e-4, 1e-4), (3, 21, 1e-4, 1e-4)):
        blocks, loads = wall(nw, nh, V_WALL)
        rw = sc.call({"op": "solve", "revision": 300 + nw * 10 + nh,
                      "blocks": blocks, "loads": loads})
        check_true(f"wall {nw - 1}x{nh - 1} solves", rw.get("ok") is True and bool(rw.get("shells")),
                   rw.get("error", ""))
        w_mm = (nw - 1) * BLOCK_MM
        h_mm = (nh - 1) * BLOCK_MM

        heights = sorted({round(centre(s, 1)) for s in rw["shells"]})
        ymid = heights[len(heights) // 2]
        row = sorted([s for s in rw["shells"] if abs(centre(s, 1) - ymid) < 1e-6],
                     key=lambda s: centre(s, 0))

        # 1. Shear flow. The whole horizontal force crosses every cut, so the mean in-plane
        #    shear per unit width is V/w. A grillage reports ZERO here — this number is the
        #    load path that motivated the element choice.
        nxy = sum(s["N"]["xy"] for s in row) / len(row)
        check(f"wall {nw - 1}x{nh - 1}: shear flow = V/w", abs(nxy), V_WALL / w_mm, tol_v)

        # 2. Overturning. The wall is a vertical cantilever whose cross-section is its own
        #    plan (w x t), so the vertical fibre force varies linearly across the width.
        #    Compared at the outermost facet CENTRE, half a block in from the edge.
        bend = (row[-1]["N"]["xx"] - row[0]["N"]["xx"]) / 2.0
        moment = V_WALL * (h_mm - (ymid - (64 * BLOCK_MM + BLOCK_MM / 2.0)))
        c_mm = w_mm / 2.0 - BLOCK_MM / 2.0
        expect = moment * c_mm / (T_T * w_mm ** 3 / 12.0) * T_T
        check(f"wall {nw - 1}x{nh - 1}: overturning fibre force", abs(bend), abs(expect), tol_m)

        # 3. Uniform compression from the wall's own weight, exact: the material above the
        #    cut and nothing else.
        mean = sum(s["N"]["xx"] for s in row) / len(row)
        above = h_mm - (ymid - (64 * BLOCK_MM + BLOCK_MM / 2.0))
        check(f"wall {nw - 1}x{nh - 1}: self weight above the cut", -mean, q_plate * above, 1e-6)

    # --------------------- S9: every plate token, not just concrete_slab_200
    # The catalogue ships three plate tokens; S1-S8 exercised only one. A token whose
    # thickness or material is wired wrong would pass every existing gate untouched —
    # "no capability without a gate" applies per token, not per code path.
    print("\n[S9] the other plate tokens: concrete_slab_150, steel_plate_20")
    S_NU, S_RHO = 0.29, 7850.0                     # engine catalogue: steel
    n9 = 9
    a9 = (n9 - 1) * BLOCK_MM
    c9 = (n9 - 1) / 2.0 * BLOCK_MM + BLOCK_MM / 2.0
    r200 = sc.call({"op": "solve", "revision": 90, "blocks": slab(n9), "loads": []})

    def slab_token_gate(tag, rev, mat, plate, t_mm, rho, nu):
        rr = sc.call({"op": "solve", "revision": rev,
                      "blocks": slab(n9, mat=mat, plate=plate), "loads": []})
        check_true(f"{tag}: ok", rr.get("ok") is True, rr.get("error", ""))
        q = rho * t_mm * G_MM * 1e-12
        # Thickness enters the model twice, independently: linearly through MASS
        # (self weight), and inversely-squared through the SURFACE STRESS kernel.
        # This pins the first; the vm ratio checks below pin the second.
        check(f"{tag}: self weight = rho*t*g*area", rr["equilibrium"]["applied"][1],
              -q * a9 * a9, 1e-12)
        # Timoshenko centre coefficient at this material's nu (edge coefficient is
        # nu-free, centre rescales by (1+nu)/1.3 — same derivation as C_CENTRE).
        got = corner_moment(rr, c9, c9, 0)
        ref = 0.0231 / 1.3 * (1 + nu) * q * a9 * a9
        err = abs(abs(got) - ref) / ref
        check_true(f"{tag}: centre span moment within 1%", err < 0.01,
                   f"got={got:.4g} ref={ref:.4g} err={err * 100:.2f}%")
        return rr

    r150 = slab_token_gate("slab_150", 91, "concrete", "concrete_slab_150", 150.0, T_RHO, T_NU)
    r020 = slab_token_gate("plate_20", 92, "steel", "steel_plate_20", 20.0, S_RHO, S_NU)

    # Thickness-squared in the STRESS kernel, pinned by ratios of the centre-face
    # von Mises. The comparison point is the plate CENTRE — the field's stationary
    # point — on an n=8 mesh (7x7 facets), whose central facet centre lands exactly
    # there. Two earlier cuts of this gate compared at the argmax instead, which
    # sits half an element inside the clamped-edge boundary layer where the closed
    # form is only ~1% good; both cuts and the move are registered in docs/GATES.md.
    def centre_vm(rev, mat, plate):
        n8 = 8
        rr = sc.call({"op": "solve", "revision": rev,
                      "blocks": slab(n8, mat=mat, plate=plate), "loads": []})
        cx = (n8 - 1) / 2.0 * BLOCK_MM + BLOCK_MM / 2.0   # plate centre, world mm
        best, bd = None, None
        for sh in rr["shells"]:
            wx = sum(w[0] for w in sh["world"]) / 4.0
            wz = sum(w[2] for w in sh["world"]) / 4.0
            d = (wx - cx) ** 2 + (wz - cx) ** 2
            if bd is None or d < bd:
                best, bd = sh, d
        return best["vmTop"]

    v200 = centre_vm(93, "concrete", "concrete_slab_200")
    v150 = centre_vm(94, "concrete", "concrete_slab_150")
    v020 = centre_vm(95, "steel", "steel_plate_20")
    # Same material: the whole moment field scales with q = rho*g*t and the surface
    # stress with M/t^2, so vm(150)/vm(200) = t200/t150 = 4/3 pointwise.
    check("centre vm ratio 150/200 = t200/t150", v150 / v200, 200.0 / 150.0, 1e-3)
    # Across materials, at the centre the two curvatures are equal by symmetry, so
    # both surface stresses are s = 6*q*f,xx*(1+nu)/t^2 and vm of the equal biaxial
    # pair is s itself: E drops out, nu enters only as (1+nu).
    check("centre vm ratio steel20/conc200 = (rho_s/rho_c)*(t_c/t_s)*(1+nu_s)/(1+nu_c)",
          v020 / v200, (S_RHO / T_RHO) * (200.0 / 20.0) * (1 + S_NU) / (1 + T_NU), 5e-3)

    # ------------------------- C15: timber and brick sections vs closed forms
    # The material catalogue always carried timber and brick; no block could declare
    # them because no beam section existed for either. These gates run BEFORE the
    # game-side blocks were added — "no capability without a gate" — and pin the two
    # new non-square sections to hand screens through the same closed forms C1 uses.
    print("\n[C15] timber and brick sections")
    h15 = sc.call({"op": "hello"})
    check_true("timber section advertised", "timber_rect_140x240" in h15.get("sections", []))
    check_true("brick section advertised", "brick_rect_230x350" in h15.get("sections", []))

    # Timber cantilever under self weight. Timber's allowables are Rcomp 5 < Rtens 8,
    # so like steel it reaches the compression face first: CRUSH, at the root.
    tb, td, trho = 140.0, 240.0, 600.0
    rt = sc.call({"op": "solve", "revision": 700,
                  "blocks": beam_blocks(5, mat="timber", section="timber_rect_140x240")})
    check_true("timber cantilever solves", rt.get("ok") is True, rt.get("error", ""))
    wt = trho * (tb * td) * 1e-9 * G
    dc_t = ((wt * L * L / 2.0) / (tb * td * td / 6.0)) / 5.0
    check("timber D/C vs hand screen", rt["members"][0]["dc"], dc_t, 1e-6)
    check_true("timber governs in CRUSH (Rcomp 5 < Rtens 8)",
               rt["members"][0]["governingFibre"] == "CRUSH", rt["members"][0]["governingFibre"])

    # Brick pier: a vertical stack under its own weight. At the supported base the
    # axial stress is rho*g*L — independent of the section, which makes it a pure
    # check of the density chain and the vertical-run extraction. The section only
    # decides that D/C reads it against Rcomp = 10.
    brho = 1800.0
    rb15 = sc.call({"op": "solve", "revision": 701,
                    "blocks": [{"x": 0, "y": 64 + i, "z": 0, "mat": "brick",
                                "section": "brick_rect_230x350", "support": i == 0}
                               for i in range(5)]})
    check_true("brick pier solves", rb15.get("ok") is True, rb15.get("error", ""))
    sigma_base = brho * 1e-9 * G * L          # MPa, at the supported end
    check("brick pier D/C = rho*g*L / Rcomp", rb15["members"][0]["dc"], sigma_base / 10.0, 1e-6)
    check_true("brick pier governs in CRUSH",
               rb15["members"][0]["governingFibre"] == "CRUSH", rb15["members"][0]["governingFibre"])

    # ------------------------------------------------- T: transport equivalence
    # The shared-memory transport must be the SAME sidecar answering the SAME
    # question — so every number must match the JSON reply bit for bit. This
    # simultaneously proves three things: the binary layout is decoded as
    # specified, the 17-digit JSON path round-trips doubles exactly (a double
    # that survives text unchanged and equals the raw bits cannot have been
    # altered in transit), and the two encoders quote one solve, not two.
    import mmap as mmap_mod
    import os
    import struct
    import tempfile

    print("\n[T] shared-memory transport: bit-exact against JSON")
    hs = sc.call({"op": "hello"})
    check_true("shm capability advertised", hs.get("shm") == 1, str(hs.get("shm")))
    t_mats = list(hs.get("materials", []))
    t_toks = list(hs.get("sections", [])) + [p["id"] for p in hs.get("plates", [])]
    FIBRE_NAMES = ["NONE", "CRUSH", "TENSION", "SHEAR", "BENDING", "TORSION", "SHELL_VM"]

    def f64bits(x):
        return struct.pack("<d", float(x))

    def num_eq(a, b):
        if a == 0 and b == 0:      # +0 vs -0: JSON prints both as "0"
            return True
        return f64bits(a) == f64bits(b)

    class Cur:
        def __init__(self, mv):
            self.mv = mv
            self.o = 0

        def u32(self):
            v = struct.unpack_from("<I", self.mv, self.o)[0]
            self.o += 4
            return v

        def i32(self):
            v = struct.unpack_from("<i", self.mv, self.o)[0]
            self.o += 4
            return v

        def f64(self):
            v = struct.unpack_from("<d", self.mv, self.o)[0]
            self.o += 8
            return v

        def f64n(self, n):
            v = list(struct.unpack_from(f"<{n}d", self.mv, self.o))
            self.o += 8 * n
            return v

        def i32n(self, n):
            v = list(struct.unpack_from(f"<{n}i", self.mv, self.o))
            self.o += 4 * n
            return v

        def s(self):
            n = self.u32()
            v = bytes(self.mv[self.o:self.o + n]).decode("utf-8")
            self.o += n
            return v

    def shm_encode_request(mm, rev, blocks, loads, buckling=True):
        o = struct.pack("<III", 0x31515242, rev & 0xffffffff, (rev >> 32) & 0xffffffff)
        o += struct.pack("<I", 1 if buckling else 0)
        o += struct.pack("<I", len(blocks))
        for b in blocks:
            o += struct.pack("<iiiiiI", b["x"], b["y"], b["z"],
                             t_mats.index(b["mat"]), t_toks.index(b["section"]),
                             1 if b.get("support") else 0)
        o += struct.pack("<I", len(loads))
        for l in loads:
            o += struct.pack("<iii", l["x"], l["y"], l["z"])
            o += struct.pack("<6d", l.get("fx", 0), l.get("fy", 0), l.get("fz", 0),
                             l.get("mx", 0), l.get("my", 0), l.get("mz", 0))
        mm.seek(0)
        mm.write(o)
        return len(o)

    def shm_decode_reply(mm, nbytes):
        c = Cur(memoryview(mm)[:nbytes])
        magic = c.u32()
        assert magic == 0x31505242, hex(magic)
        rev = c.u32() | (c.u32() << 32)
        ok = c.u32() == 1
        if not ok:
            return {"ok": False, "revision": rev, "error": c.s()}
        r = {"ok": True, "revision": rev}
        r["singular"] = c.u32() == 1
        r["islands"] = c.u32()
        r["singularIslands"] = c.u32()
        diag = c.s()
        if diag:
            r["diagnostic"] = diag
        note = c.s()
        if note:
            r["note"] = note
        r["equilibrium"] = {"applied": c.f64n(3), "reaction": c.f64n(3), "residual": c.f64()}
        r["maxDC"] = c.f64()
        r["governing"] = c.i32()
        gk = c.u32()
        if gk:
            r["governingKind"] = "member" if gk == 1 else "shell"
        bf = c.f64()
        if bf > 0:
            r["bucklingFactor"] = bf
        r["nodes"] = c.u32()
        r["dof"] = c.u32()
        r["members"] = []
        for _ in range(c.u32()):
            m = {"id": c.i32(), "mat": t_mats[c.i32()], "section": t_toks[c.i32()],
                 "lengthMm": c.f64(), "dc": c.f64(),
                 "governingFibre": FIBRE_NAMES[c.u32()], "governingStation": c.i32()}
            for end in ("i", "j"):
                N, Vy, Vz, T, My, Mz = c.f64n(6)
                m[end] = {"N": N, "Vy": Vy, "Vz": Vz, "T": T, "My": My, "Mz": Mz}
            f = {"origin": c.f64n(3), "ax": c.f64n(3), "ay": c.f64n(3), "az": c.f64n(3)}
            f["A"], f["Iy"], f["Iz"], f["cy"], f["cz"], f["wy"], f["wz"] = c.f64n(7)
            m["field"] = f
            m["blocks"] = [c.i32n(3) for _ in range(c.u32())]
            m["stations"] = []
            for _ in range(c.u32()):
                st = {"x": c.f64(), "world": c.f64n(3)}
                sig = c.f64n(4)
                st["fibres"] = [{"name": nm, "sigma": sv} for nm, sv in
                                zip(("TOP_Y", "BOT_Y", "PLUS_Z", "MINUS_Z"), sig)]
                st["sigmaTens"], st["sigmaComp"], st["tau"] = c.f64n(3)
                naY, naZ = c.f64n(2)
                if not math.isnan(naY):
                    st["naY"] = naY
                if not math.isnan(naZ):
                    st["naZ"] = naZ
                m["stations"].append(st)
            r["members"].append(m)
        r["shells"] = []
        for _ in range(c.u32()):
            sh = {"id": c.i32(), "mat": t_mats[c.i32()], "plate": t_toks[c.i32()],
                  "t": c.f64(), "dc": c.f64(), "dcRaw": c.f64()}
            fl = c.u32()
            sh["face"] = "TOP" if (fl & 1) else "BOT"
            sh["edgeRecovered"] = (fl & 2) != 0
            sh["corner"] = c.i32()
            sh["blocks"] = [c.i32n(3) for _ in range(4)]
            sh["world"] = [c.f64n(3) for _ in range(4)]
            sh["ex"], sh["ey"], sh["n"] = c.f64n(3), c.f64n(3), c.f64n(3)
            nxx, nyy, nxy, mxx, myy, mxy, qx, qy = c.f64n(8)
            sh["N"] = {"xx": nxx, "yy": nyy, "xy": nxy}
            sh["M"] = {"xx": mxx, "yy": myy, "xy": mxy}
            sh["Q"] = {"x": qx, "y": qy}
            sh["Mc"] = [c.f64n(3) for _ in range(4)]
            sh["McRaw"] = [c.f64n(3) for _ in range(4)]
            sh["vmTop"], sh["vmBot"] = c.f64n(2)
            r["shells"].append(sh)
        r["unassigned"] = [c.i32n(3) for _ in range(c.u32())]
        return r

    def deep_eq(a, b, path):
        if isinstance(a, dict) and isinstance(b, dict):
            for k in a.keys() | b.keys():
                if k not in a or k not in b:
                    return f"{path}.{k}: present on one side only"
                bad = deep_eq(a[k], b[k], f"{path}.{k}")
                if bad:
                    return bad
            return None
        if isinstance(a, list) and isinstance(b, list):
            if len(a) != len(b):
                return f"{path}: length {len(a)} vs {len(b)}"
            for i, (x, y) in enumerate(zip(a, b)):
                bad = deep_eq(x, y, f"{path}[{i}]")
                if bad:
                    return bad
            return None
        if isinstance(a, bool) or isinstance(b, bool) or isinstance(a, str) or isinstance(b, str):
            return None if a == b else f"{path}: {a!r} vs {b!r}"
        if isinstance(a, (int, float)) and isinstance(b, (int, float)):
            return None if num_eq(a, b) else f"{path}: {a!r} vs {b!r} (bit mismatch)"
        return None if a == b else f"{path}: {a!r} vs {b!r}"

    def strip_json_extras(r):
        # Fibre dir/offset are geometry the binary wire derives from the member
        # header instead of repeating per fibre; McRaw is emitted by JSON only
        # when recovery fired but always by the binary layout.
        out = json.loads(json.dumps(r))
        for m in out.get("members", []):
            for st in m.get("stations", []):
                for f in st.get("fibres", []):
                    f.pop("dir", None)
                    f.pop("offsetMm", None)
        for sh in out.get("shells", []):
            if "McRaw" not in sh:
                sh["McRaw"] = sh.get("Mc")
        return out

    shm_path = os.path.join(tempfile.mkdtemp(prefix="br-verify-shm"), "region.bin")
    with open(shm_path, "wb") as f:
        f.write(b"\0" * (8 * 1024 * 1024))
    fshm = open(shm_path, "r+b")
    mm = mmap_mod.mmap(fshm.fileno(), 0)

    ro = sc.call({"op": "shm.open", "path": shm_path})
    check_true("shm.open ok", ro.get("ok") is True, ro.get("error", ""))

    t_cases = [
        ("cantilever + tip load", beam_blocks(5),
         [{"x": 4, "y": 64, "z": 0, "fy": -20000.0}]),
        ("two supports + midspan load",
         [{"x": i, "y": 64, "z": 0, "mat": "steel", "section": "steel_rect_200x400",
           "support": i in (0, 6)} for i in range(7)],
         [{"x": 3, "y": 64, "z": 0, "fy": -30000.0}]),
        ("shear wall 2x4", *wall(3, 5, 100000.0)),
    ]
    for ti, (tag, tb, tl) in enumerate(t_cases):
        rev = 9000 + ti
        rj = sc.call({"op": "solve", "revision": rev, "blocks": tb, "loads": tl})
        nreq = shm_encode_request(mm, rev, tb, tl)
        bell = sc.call({"op": "solve.shm", "revision": rev, "bytes": nreq})
        check_true(f"T{ti} doorbell ok ({tag})", bell.get("ok") is True, bell.get("error", ""))
        rb = shm_decode_reply(mm, bell["bytes"])
        # `op` is doorbell-side framing, not solve content.
        rjc = strip_json_extras(rj)
        rjc.pop("op", None)
        bad = deep_eq(rjc, rb, "$")
        check_true(f"T{ti} shm reply bit-exact vs JSON ({tag})", bad is None, bad or "")

    # Error path: a load on nothing must refuse identically on both transports.
    rev = 9100
    eb = beam_blocks(3)
    el = [{"x": 40, "y": 64, "z": 0, "fy": -1000.0}]
    rj = sc.call({"op": "solve", "revision": rev, "blocks": eb, "loads": el})
    nreq = shm_encode_request(mm, rev, eb, el)
    bell = sc.call({"op": "solve.shm", "revision": rev, "bytes": nreq})
    check_true("T-err doorbell still ok=false", bell.get("ok") is False, str(bell))
    check_true("T-err same refusal text", bell.get("error") == rj.get("error"),
               f"json={rj.get('error')!r} shm={bell.get('error')!r}")

    # ---------------------------------- TF: frame boundary and fail-closed wire
    # The byte length on the doorbell IS the frame (#27); the mapping capacity is
    # not. Every refusal here must name the problem, not solve a lighter model.
    print("\n[TF] shm framing and fail-closed decode")
    fb, fl = beam_blocks(3), []
    nreq = shm_encode_request(mm, 9200, fb, fl)
    check_true("TF bytes missing refused",
               sc.call({"op": "solve.shm", "revision": 9200}).get("ok") is False, "")
    check_true("TF bytes zero refused",
               sc.call({"op": "solve.shm", "revision": 9200, "bytes": 0}).get("ok") is False, "")
    check_true("TF bytes beyond region refused",
               sc.call({"op": "solve.shm", "revision": 9200,
                        "bytes": 1 << 30}).get("ok") is False, "")
    r = sc.call({"op": "solve.shm", "revision": 9200, "bytes": nreq + 8})
    check_true("TF trailing bytes refused", r.get("ok") is False and "trailing" in r.get("error", ""),
               str(r.get("error", "")))
    r = sc.call({"op": "solve.shm", "revision": 9200, "bytes": nreq - 8})
    check_true("TF truncated frame refused", r.get("ok") is False and "truncat" in r.get("error", ""),
               str(r.get("error", "")))
    check_true("TF exact frame still solves",
               sc.call({"op": "solve.shm", "revision": 9200, "bytes": nreq}).get("ok") is True, "")

    # Unknown request flags / block flags / out-of-world coordinates: refused (#28).
    def patched(rev, patch_at, value):
        n = shm_encode_request(mm, rev, fb, fl)
        mm.seek(patch_at)
        mm.write(struct.pack("<I", value))
        return n
    n = patched(9201, 12, 3)   # flags word: unknown bit 1 set
    r = sc.call({"op": "solve.shm", "revision": 9201, "bytes": n})
    check_true("TF unknown request flags refused",
               r.get("ok") is False and "flags" in r.get("error", ""), str(r.get("error", "")))
    n = patched(9202, 20 + 20, 2)   # first block's flags word (header 20 + x,y,z,mat,tok)
    r = sc.call({"op": "solve.shm", "revision": 9202, "bytes": n})
    check_true("TF unknown block flags refused",
               r.get("ok") is False and "flags" in r.get("error", ""), str(r.get("error", "")))
    n = patched(9203, 20, 0x7fffffff)   # first block x: far outside the world
    r = sc.call({"op": "solve.shm", "revision": 9203, "bytes": n})
    check_true("TF out-of-world coordinate refused",
               r.get("ok") is False and "range" in r.get("error", ""), str(r.get("error", "")))

    # ------------------------------------ TR: revision is exact 64-bit (#29)
    # A double folds every integer above 2^53; the fence must not. Adjacent
    # revisions on both sides of the fold, echoed exactly, on both transports.
    print("\n[TR] revision domain is exact int64")
    for rev in (2 ** 53 - 1, 2 ** 53, 2 ** 53 + 1, 2 ** 63 - 1):
        rj = sc.call({"op": "solve", "revision": rev, "blocks": fb, "loads": fl})
        check_true(f"TR json echoes {rev}", rj.get("ok") is True and rj.get("revision") == rev,
                   str(rj.get("revision")))
        n = shm_encode_request(mm, rev, fb, fl)
        bell = sc.call({"op": "solve.shm", "revision": rev, "bytes": n})
        check_true(f"TR shm echoes {rev}", bell.get("ok") is True and bell.get("revision") == rev,
                   str(bell.get("revision")))
        rb = shm_decode_reply(mm, bell["bytes"])
        check_true(f"TR region echoes {rev}", rb.get("revision") == rev, str(rb.get("revision")))
    check_true("TR fractional revision refused",
               sc.raw('{"op":"solve","revision":1.5,"blocks":[]}').get("ok") is False, "")
    check_true("TR exponent-form revision refused",
               sc.raw('{"op":"solve","revision":1e3,"blocks":[]}').get("ok") is False, "")
    check_true("TR negative revision refused",
               sc.call({"op": "solve", "revision": -1, "blocks": []}).get("ok") is False, "")
    check_true("TR beyond-int64 revision refused",
               sc.raw('{"op":"solve","revision":9223372036854775808,"blocks":[]}').get("ok") is False, "")

    # -------------------------------- TS: JSON request schema is strict (#30)
    # A wrong-typed or misspelled field must refuse, never quietly become a
    # lighter model that still answers ok:true.
    print("\n[TS] strict JSON request schema")
    base = {"op": "solve", "revision": 9300, "blocks": beam_blocks(3)}
    check_true("TS baseline solves", sc.call(base).get("ok") is True, "")
    check_true("TS loads as object refused",
               sc.call({**base, "loads": {}}).get("ok") is False, "")
    check_true("TS loads as string refused",
               sc.call({**base, "loads": "none"}).get("ok") is False, "")
    r = sc.call({**base, "load": [{"x": 0, "y": 64, "z": 0, "fy": -1.0}]})
    check_true("TS typo'd 'load' key refused", r.get("ok") is False and "load" in r.get("error", ""),
               str(r.get("error", "")))
    check_true("TS blocks as object refused",
               sc.call({"op": "solve", "revision": 9301, "blocks": {}}).get("ok") is False, "")
    check_true("TS blocks missing refused",
               sc.call({"op": "solve", "revision": 9302}).get("ok") is False, "")
    bad_support = [dict(beam_blocks(3)[0], support="true")] + beam_blocks(3)[1:]
    check_true("TS string 'support' refused",
               sc.call({"op": "solve", "revision": 9303, "blocks": bad_support}).get("ok") is False, "")
    check_true("TS unknown block field refused",
               sc.call({"op": "solve", "revision": 9304,
                        "blocks": [dict(beam_blocks(1)[0], colour="red")]}).get("ok") is False, "")
    check_true("TS trailing garbage line refused",
               sc.raw('{"op":"solve","revision":1,"blocks":[]} tail').get("ok") is False, "")
    check_true("TS malformed number refused",
               sc.raw('{"op":"solve","revision":1,"blocks":[{"x":1.2.3,"y":64,"z":0,'
                      '"mat":"steel","section":"steel_rect_200x400"}]}').get("ok") is False, "")
    check_true("TS invalid escape refused",
               sc.raw('{"op":"solve","revision":1,"blocks":[],"loads":[],"buckling":true,'
                      '"op":"so\\qlve"}').get("ok") is False, "")

    # Capacity path: a region too small must refuse loudly, never truncate.
    small_path = os.path.join(os.path.dirname(shm_path), "small.bin")
    with open(small_path, "wb") as f:
        f.write(b"\0" * 4096)
    ro = sc.call({"op": "shm.open", "path": small_path})
    check_true("shm.open small ok", ro.get("ok") is True, ro.get("error", ""))
    fs2 = open(small_path, "r+b")
    mm2 = mmap_mod.mmap(fs2.fileno(), 0)
    rev = 9101
    bb, bl = wall(3, 5, 100000.0)
    nreq = shm_encode_request(mm2, rev, bb, bl)
    bell = sc.call({"op": "solve.shm", "revision": rev, "bytes": nreq})
    check_true("T-cap overflow refused with grow hint",
               bell.get("ok") is False and "grow" in bell.get("error", ""), str(bell))
    mm2.close()
    fs2.close()

    mm.close()
    fshm.close()

    sc.close()

    print()
    if fails:
        print(f"FAILED {len(fails)}: {', '.join(fails)}")
        return 1
    print("ALL PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
