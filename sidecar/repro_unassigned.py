#!/usr/bin/env python3
"""
Where do the blocks go? Measured rather than assumed.

    python repro_unassigned.py [path-to-br-sidecar]

The reply has three places a block can land: some member's `blocks`, some shell's
`blocks`, or `unassigned`. This script checks whether every block of the request
lands in at least one of them, and what `unassigned` actually means when it does.

It also asks what `bucklingFactor: 0` means, since one number carries more than
one state (V04_PLAN 2.6).

Produces the table frozen as N17/N18 in docs/GATES.md. Tokens are the nine the
mod registers, from BRContent.
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

    def solve(self, blocks, loads=None, buckling=None):
        self.rev += 1
        req = {"op": "solve", "revision": self.rev, "blocks": blocks}
        if loads:
            req["loads"] = loads
        if buckling is not None:
            req["buckling"] = buckling
        self.p.stdin.write(json.dumps(req) + chr(10))
        self.p.stdin.flush()
        return json.loads(self.p.stdout.readline())


def blk(x, y, z, mat_sec=STEEL, support=False):
    m, s = mat_sec
    d = {"x": x, "y": Y0 + y, "z": z, "mat": m, "section": s}
    if support:
        d["support"] = True
    return d


def cells(entries):
    """Coordinates out of a `blocks` array or an `unassigned` array, either shape."""
    out = []
    for e in entries:
        if isinstance(e, dict):
            out += [tuple(c) for c in e.get("blocks", [])]
        else:
            out.append(tuple(e))
    return out


def audit(name, bs, r):
    want = {(q["x"], q["y"], q["z"]) for q in bs}
    mem = [c for m in r.get("members", []) for c in cells(m["blocks"])]
    sh = [c for s in r.get("shells", []) for c in cells(s["blocks"])]
    un = cells(r.get("unassigned", []))
    covered = (set(mem) | set(sh) | set(un)) & want
    print("  %-42s in=%-4d covered=%-4d MISSING=%-4d shared-between-members=%-3d"
          "  un/elem overlap=%d"
          % (name, len(want), len(covered), len(want - covered),
             len(mem) - len(set(mem)), len((set(un) & (set(mem) | set(sh))))))
    if r.get("diagnostic"):
        print("      diagnostic: " + r["diagnostic"][:92])
    return want - covered


def main():
    sc = Sidecar(EXE)
    print()
    print("A. is every input block accounted for somewhere in the reply?")
    print()
    holes = 0
    cases = [
        ("A1 ungrounded 6-block beam (mechanism)", [blk(x, 0, 0) for x in range(6)]),
        ("A2 grounded 6-block span", [blk(x, 0, 0, support=(x in (0, 5))) for x in range(6)]),
        ("A3 beam lying flat on the ground", [blk(x, 0, 0, support=True) for x in range(6)]),
        ("A4 lone block, grounded", [blk(0, 0, 0, support=True)]),
        ("A5 one-block-wide slab strip", [blk(x, 0, 0, SLAB, support=True) for x in range(6)]),
        ("A6 4x4 slab on 4 grounded columns",
         [blk(cx, y, cz, support=(y == 0))
          for (cx, cz) in [(0, 0), (3, 0), (0, 3), (3, 3)] for y in range(4)]
         + [blk(x, 4, z, SLAB) for x in range(4) for z in range(4)]),
        ("A7 L junction (portal corner)",
         [blk(0, y, 0, support=(y == 0)) for y in range(5)] + [blk(x, 4, 0) for x in range(1, 6)]),
        ("A8 grounded column + separate floating beam",
         [blk(0, y, 0, support=(y == 0)) for y in range(5)] + [blk(10 + x, 3, 0) for x in range(6)]),
    ]
    for name, bs in cases:
        holes += len(audit(name, bs, sc.solve(bs)))

    print()
    print("  accounting holes across the batch: %d blocks" % holes)
    print("  (every hole above is a SINGULAR island: its blocks are in neither members")
    print("   nor shells nor unassigned. V03A_REVIEW N4-2, still open.)")
    print()

    print("B. what does bucklingFactor 0 mean?")
    print()
    col = ([blk(0, 0, 0, support=True)] + [blk(0, y, 0) for y in range(1, 12)])
    load = [{"x": 0, "y": Y0 + 11, "z": 0, "fz": -50000.0}]
    r = sc.solve(col, loads=load, buckling=True)
    print("  B1 column, buckling=true            bucklingFactor=%r" % r.get("bucklingFactor"))
    r = sc.solve(col, loads=load, buckling=False)
    print("  B2 same column, buckling=false      bucklingFactor=%r" % r.get("bucklingFactor"))
    r = sc.solve([blk(x, 0, 0, support=True) for x in range(6)], buckling=True)
    print("  B3 nothing to buckle, buckling=true bucklingFactor=%r" % r.get("bucklingFactor"))
    print("  buckling-related keys in the reply: %r"
          % (sorted(k for k in r if "uckling" in k),))
    print()


main()
