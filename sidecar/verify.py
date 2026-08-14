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


def beam_blocks(n, mat="steel", section="steel_h400", y=64):
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
    check_true("sections advertised", "steel_h400" in h.get("sections", []))

    # ------------------------------------------- C1: cantilever vs closed form
    # 5 blocks -> one member, centre-to-centre length 4000 mm, root fixed.
    # Root moment must equal the tip load moment plus the self-weight moment:
    #     M = P*L + w*L^2/2      with w = rho * A * 1e-9 * g   [N/mm]
    print("\n[C1] cantilever, tip load + self weight")
    P = 20000.0                       # N, downward in Minecraft axes
    n = 5
    L = (n - 1) * BLOCK_MM
    b, d = 200.0, 400.0               # steel_h400
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
    check_true("D/C matches a hand screen",
               abs(mem["dc"] - dc_strong) < 1e-6 or abs(mem["dc"] - dc_weak) < 1e-6,
               f"dc={mem['dc']:.6g} strong={dc_strong:.6g} weak={dc_weak:.6g}")
    # The mode names the GOVERNING FIBRE, not the load type. ElasticAllowable
    # takes the argmax of five ratios, and for steel the compressive allowable
    # (350) is lower than the tensile one (500), so a pure bending stress reaches
    # the compression limit first and reports CRUSH. That is the useful answer:
    # it tells the player which side of the section runs out first.
    check_true("mode is CRUSH (steel: comp 350 < tens 500)", mem["mode"] == "CRUSH", mem["mode"])
    check_true("no unassigned blocks", len(r.get("unassigned", [])) == 0)

    # ------------------------------------------- C1b: mode tracks the material
    # Same geometry in concrete, where tension is the weak side (Rtens 3.0 vs
    # Rcomp 30). If the mode field were a fixed label it would still say CRUSH;
    # it must flip to TENSION.
    print("\n[C1b] governing fibre follows the material")
    rc = sc.call({"op": "solve", "revision": 11,
                  "blocks": beam_blocks(n, mat="concrete", section="rc_400x600")})
    check_true("ok", rc.get("ok") is True, rc.get("error", ""))
    check_true("concrete governs in TENSION", rc["members"][0]["mode"] == "TENSION",
               rc["members"][0]["mode"])
    # Hand screen: self weight only, sigma = (w L^2 / 2) / Wz, D/C = sigma / Rtens.
    cb, cd = 400.0, 600.0                      # rc_400x600
    wc = 2350.0 * (cb * cd) * 1e-9 * G         # N/mm
    dc_expect = ((wc * L * L / 2.0) / (cb * cd * cd / 6.0)) / 3.0
    check("concrete D/C vs hand screen", rc["members"][0]["dc"], dc_expect, 1e-6)

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
                              "section": "steel_h400", "support": True}]})
    check_true("ok", r5.get("ok") is True)
    check_true("no members", len(r5.get("members", [])) == 0)

    # ------------------------------------------------ C6: junction splits runs
    # An L shape shares its corner block, so it must become two members joined
    # at one node -- not one bent member and not two disconnected ones.
    print("\n[C6] L junction -> two members, shared node")
    lshape = [{"x": i, "y": 64, "z": 0, "mat": "steel", "section": "steel_h400",
               "support": i == 0} for i in range(4)]
    lshape += [{"x": 3, "y": 64, "z": k, "mat": "steel", "section": "steel_h400",
                "support": False} for k in range(1, 4)]
    r6 = sc.call({"op": "solve", "revision": 7, "blocks": lshape})
    check_true("ok", r6.get("ok") is True, r6.get("error", ""))
    check_true("two members", len(r6.get("members", [])) == 2,
               f"got {len(r6.get('members', []))}")
    check_true("no unassigned blocks", len(r6.get("unassigned", [])) == 0,
               str(r6.get("unassigned", [])))

    # ------------------------------------------------- C7: bad input is safe
    # Unknown ids and malformed lines must produce an error line, never a crash
    # and never a silent default.
    print("\n[C7] fail-safe on bad input")
    r7 = sc.call({"op": "solve", "revision": 8,
                  "blocks": [{"x": 0, "y": 64, "z": 0, "mat": "unobtainium",
                              "section": "steel_h400", "support": True},
                             {"x": 1, "y": 64, "z": 0, "mat": "unobtainium",
                              "section": "steel_h400"}]})
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
