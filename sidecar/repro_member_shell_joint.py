#!/usr/bin/env python3
"""
Which member-to-shell contacts actually carry load, and which only touch.

    python repro_member_shell_joint.py [path-to-br-sidecar]

Issue #75 says a beam under a slab does not join it. That is one point on a
boundary, and freezing a criterion against one point is how a line ends up in
the wrong place. This walks the whole boundary: every way a run and a facet can
meet, and whether the answer says one structure or two.

The oracle is deliberately not a stress value. It is topology plus statics:

  * `islands` -- one structure or two.
  * `singularIslands` -- did the part that should be carried come back as a
    mechanism, meaning nothing was holding it up.
  * whether the slab's weight shows up in `applied`, which is the load path
    question stated without any element vocabulary.

Tokens are the nine the mod registers, from BRContent.
"""
import json
import subprocess
import sys

EXE = sys.argv[1] if len(sys.argv) > 1 else "sidecar/build/br-sidecar"
Y0 = 64
STEEL = ("steel", "steel_rect_200x400")
SLAB = ("concrete", "concrete_slab_200")


class Sidecar:
    def __init__(self, exe):
        self.p = subprocess.Popen([exe], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  text=True, bufsize=1)
        self.rev = 0

    def solve(self, blocks):
        self.rev += 1
        self.p.stdin.write(json.dumps(
            {"op": "solve", "revision": self.rev, "blocks": blocks}) + chr(10))
        self.p.stdin.flush()
        return json.loads(self.p.stdout.readline())


def blk(x, y, z, mat_sec=STEEL, support=False):
    m, s = mat_sec
    d = {"x": x, "y": Y0 + y, "z": z, "mat": m, "section": s}
    if support:
        d["support"] = True
    return d


def report(name, blocks, r):
    why = {g["why"]: len(g["blocks"]) for g in r.get("unassigned", [])}
    joined = r.get("islands") == 1 and r.get("singularIslands") == 0
    print("  %-46s islands=%-2s singular=%-2s members=%-2d facets=%-2d  %-9s %s"
          % (name, r.get("islands"), r.get("singularIslands"),
             len(r.get("members", [])), len(r.get("shells", [])),
             "JOINED" if joined else "SEPARATE",
             why if why else ""))
    return joined


def main():
    sc = Sidecar(EXE)
    print()
    print("A. contacts that involve a run END (the case the extractor already handles)")
    print()

    # A1 the shipped case: a column standing under a slab, its top block IS a plate block.
    col = [blk(2, y, 1) for y in range(4)]
    col[0]["support"] = True
    slab = [blk(x, 4, z, SLAB) for x in range(5) for z in range(3)]
    report("A1 column under a slab (end-on)", col + slab, sc.solve(col + slab))

    # A2 four columns, the ordinary floor.
    four = []
    for (cx, cz) in [(0, 0), (4, 0), (0, 2), (4, 2)]:
        for y in range(4):
            four.append(blk(cx, y, cz, support=(y == 0)))
    report("A2 four columns under a slab", four + slab, sc.solve(four + slab))

    # A3 a horizontal beam whose END runs into the slab's edge, same height as the slab.
    edge_beam = [blk(-1 - i, 4, 1) for i in range(4)]
    edge_beam[-1]["support"] = True
    report("A3 beam END into a slab edge, coplanar", edge_beam + slab,
           sc.solve(edge_beam + slab))

    print()
    print("B. contacts along a run's SIDE (issue #75)")
    print()

    # B1 the issue as filed: a grounded beam with a slab resting on top of it.
    under = [blk(x, 3, 1, support=(x in (0, 4))) for x in range(5)]
    report("B1 slab resting ON a beam", under + slab, sc.solve(under + slab))

    # B2 the same, but the beam is coplanar with the slab and runs alongside it.
    beside = [blk(x, 4, -1, support=(x in (0, 4))) for x in range(5)]
    report("B2 beam ALONGSIDE a slab, coplanar", beside + slab,
           sc.solve(beside + slab))

    # B3 two beams under opposite edges -- the normal way a floor is framed.
    pair = ([blk(x, 3, 0, support=(x in (0, 4))) for x in range(5)]
            + [blk(x, 3, 2, support=(x in (0, 4))) for x in range(5)])
    report("B3 slab on TWO beams (normal framing)", pair + slab, sc.solve(pair + slab))

    print()
    print("C. what the slab weighs, and whether anything carries it")
    print()
    # The load-path question with no element vocabulary in it: with the slab present,
    # does the total applied force grow by the slab's weight?
    beams_only = sc.solve(pair)
    both = sc.solve(pair + slab)
    for label, r in (("two beams alone", beams_only), ("beams + slab", both)):
        eq = r.get("equilibrium", {})
        print("  %-24s applied=%s" % (label, [round(v, 1) for v in eq.get("applied", [])]))
    print()
    print("  A slab of 15 blocks of concrete_slab_200 weighs about")
    print("  15 x 1000 x 1000 x 200 mm3 x 2400 kg/m3 x 9.81 = %.0f N"
          % (15 * 1.0 * 1.0 * 0.2 * 2400 * 9.81))
    print("  If the two numbers above are equal, nothing is carrying it.")
    print()


main()
