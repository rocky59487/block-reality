#!/usr/bin/env python3
"""
What the buckling threshold is actually buying, per shape.

    python repro_buckling_cost.py [path-to-br-sidecar]

`BucklingPolicy` skips the eigensolve above a configured number of structural
BLOCKS (default 300). The cost of an eigensolve is cubic in the number of free
DOF, and blocks are not DOF: a straight beam of 300 blocks is two nodes, a slab
of 300 blocks is 300. If the two shapes cost wildly different amounts at the
same block count, the policy's input is not the cost driver and the threshold
means a different thing to every player depending on what they are building.

Measured, not extrapolated. The config comment currently quotes a fit through
node counts and then applies it to a block count.
"""
import json
import subprocess
import sys
import time

EXE = sys.argv[1] if len(sys.argv) > 1 else "sidecar/build/br-sidecar"
Y0 = 64
STEEL = ("steel", "steel_rect_200x400")
SLAB = ("concrete", "concrete_slab_200")


class Sidecar:
    def __init__(self, exe):
        self.p = subprocess.Popen([exe], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  text=True, bufsize=1)
        self.rev = 0

    def solve(self, blocks, buckling):
        self.rev += 1
        req = {"op": "solve", "revision": self.rev, "blocks": blocks,
               "buckling": buckling}
        t = time.perf_counter()
        self.p.stdin.write(json.dumps(req) + chr(10))
        self.p.stdin.flush()
        r = json.loads(self.p.stdout.readline())
        return r, (time.perf_counter() - t) * 1000.0


def blk(x, y, z, mat_sec=STEEL, support=False):
    m, s = mat_sec
    d = {"x": x, "y": Y0 + y, "z": z, "mat": m, "section": s}
    if support:
        d["support"] = True
    return d


def beam(n):
    """A straight run: many blocks, almost no nodes."""
    return [blk(x, 0, 0, support=(x in (0, n - 1))) for x in range(n)]


def slab(side):
    """A square floor on four corner columns: blocks and nodes go together."""
    b = [blk(x, 4, z, SLAB) for x in range(side) for z in range(side)]
    for (cx, cz) in [(0, 0), (side - 1, 0), (0, side - 1), (side - 1, side - 1)]:
        for y in range(4):
            b.append(blk(cx, y, cz, support=(y == 0)))
    return b


def lattice(bays):
    """A frame: columns every 2 m with beams across, so junctions everywhere."""
    b = []
    for i in range(bays + 1):
        # Columns stop one short of the beam row: overlapping them is a duplicate
        # coordinate, which the engine refuses outright rather than resolving.
        for y in range(3):
            b.append(blk(2 * i, y, 0, support=(y == 0)))
    for x in range(2 * bays + 1):
        b.append(blk(x, 3, 0))
    return b


def run(sc, name, blocks):
    # One warm call so the first measurement is not paying for page faults.
    sc.solve(blocks, False)
    off_best = min(sc.solve(blocks, False)[1] for _ in range(3))
    r, _ = sc.solve(blocks, True)
    on_best = min(sc.solve(blocks, True)[1] for _ in range(3))
    nodes = r.get("nodes", 0)
    print("  %-28s blocks=%-5d nodes=%-5d dof=%-6d  off=%7.1f ms  on=%8.1f ms  x%.1f"
          % (name, len(blocks), nodes, r.get("dof", 0), off_best, on_best,
             on_best / off_best if off_best else 0))
    return len(blocks), nodes, on_best


def main():
    sc = Sidecar(EXE)
    print()
    print("A. same block count, three shapes")
    print()
    # 300 is the shipped default for bucklingBlockLimit, so measure AT the threshold.
    run(sc, "straight beam, 300 blocks", beam(300))
    run(sc, "floor slab, 17x17 = 289 + 16", slab(17))
    run(sc, "portal frame, 100 bays", lattice(100))

    print()
    print("B. how the slab scales (the shape the threshold lets through)")
    print()
    rows = []
    for side in (8, 12, 15, 17, 20):
        rows.append(run(sc, "floor slab %dx%d" % (side, side), slab(side)))

    print()
    print("  A player laying a floor crosses the 300-block default at about 17x17.")
    for nb, nn, ms in rows:
        print("    %3d blocks / %3d nodes -> %8.1f ms with buckling on" % (nb, nn, ms))
    print()
    print("C. the same block budget spent on a beam")
    print()
    for n in (100, 200, 300, 600):
        run(sc, "straight beam %d blocks" % n, beam(n))
    print()


main()
