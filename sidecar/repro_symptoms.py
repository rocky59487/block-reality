#!/usr/bin/env python3
"""
Reproduces the three symptoms reported against v0.3c, against the shipped engine.

    python repro_symptoms.py [path-to-br-sidecar]

Every case here is GROUNDED, so that "the engine refused an unsupported thing"
-- which is correct behaviour -- cannot be mistaken for "the extractor produced
nothing", which is not.

A. blocks that reach the engine and end up in no member and no shell
B. mixed structures: material mixes, and the member/shell mix
C. what a PARTIAL request does to the blocks that did arrive. The Java side
   silently drops blocks in unloaded chunks -- StructureManager.visitForCycle,
   `if (!level.isLoaded(pos)) return;` -- so this asks what the engine tells the
   player about a structure that was cut in half on the way in.

Tokens are the nine the mod registers, from BRContent.
"""
import json
import subprocess
import sys

EXE = sys.argv[1] if len(sys.argv) > 1 else "sidecar/build/br-sidecar"
Y0 = 64

STEEL = ("steel", "steel_rect_200x400")
STEEL_S = ("steel", "steel_rect_100x200")
CONC_B = ("concrete", "concrete_rect_400x600")
SLAB = ("concrete", "concrete_slab_200")
TIMBER = ("timber", "timber_rect_140x240")
BRICK = ("brick", "brick_rect_230x350")


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


def blk(x, y, z, kind, support=False):
    mat, sec = kind
    return {"x": x, "y": Y0 + y, "z": z, "mat": mat, "section": sec, "support": support}


def show(tag, n_in, r):
    mem = r.get("members", []) or []
    sh = r.get("shells", []) or []
    dc = max([m.get("dc", 0.0) for m in mem] + [s.get("dc", 0.0) for s in sh], default=0.0)
    un = r.get("unassigned")
    nun = len(un) if isinstance(un, list) else un
    print(f"  {tag:32} in={n_in:3} mem={len(mem):3} shell={len(sh):3} "
          f"unassigned={str(nun):>4} islands={str(r.get('islands')):>4} "
          f"singular={str(r.get('singularIslands')):>4} maxDC={dc:.4f} "
          f"ok={r.get('ok')}{'  note=' + str(r.get('note')) if r.get('note') else ''}")
    return dc


def main():
    sc = Sidecar(EXE)

    print("A. grounded shapes that still produce nothing")
    show("6 steel column (control)", 6,
         sc.solve([blk(0, i, 0, STEEL, support=(i == 0)) for i in range(6)]))

    # a floor one block off the ground, carried by four grounded steel columns
    def floored(kind):
        b = []
        for cx, cz in ((0, 0), (4, 0), (0, 4), (4, 4)):
            b.append(blk(cx, 0, cz, STEEL, support=True))
        b += [blk(x, 1, z, kind) for x in range(5) for z in range(5)]
        return b

    show("5x5 beam-block floor on 4 cols", 29, sc.solve(floored(STEEL)))
    show("5x5 slab floor on 4 cols", 29, sc.solve(floored(SLAB)))

    show("2x2x6 steel column, grounded", 24,
         sc.solve([blk(x, y, z, STEEL, support=(y == 0))
                   for x in range(2) for z in range(2) for y in range(6)]))

    wall = ([blk(x, y, 0, SLAB, support=(y == 0)) for x in range(4) for y in range(4)]
            + [blk(0, y, z, SLAB, support=(y == 0)) for z in range(1, 4) for y in range(4)])
    show("L wall of slab, grounded", len(wall), sc.solve(wall))

    print("\nB. mixed structures")
    mixed = ([blk(0, i, 0, BRICK, support=(i == 0)) for i in range(3)]
             + [blk(4, i, 0, BRICK, support=(i == 0)) for i in range(3)]
             + [blk(x, 3, 0, TIMBER) for x in range(5)])
    show("timber beam on brick piers", len(mixed), sc.solve(mixed))

    show("steel col + concrete beam", 8,
         sc.solve([blk(0, i, 0, STEEL, support=(i == 0)) for i in range(4)]
                  + [blk(x, 4, 0, CONC_B) for x in range(4)]))
    show("  control: all steel", 8,
         sc.solve([blk(0, i, 0, STEEL, support=(i == 0)) for i in range(4)]
                  + [blk(x, 4, 0, STEEL) for x in range(4)]))
    show("  control: steel, 2 sections", 8,
         sc.solve([blk(0, i, 0, STEEL, support=(i == 0)) for i in range(4)]
                  + [blk(x, 4, 0, STEEL_S) for x in range(4)]))

    # member kind meeting shell kind: a grounded steel beam with a slab sitting on it
    bs = ([blk(x, 0, 0, STEEL, support=True) for x in range(5)]
          + [blk(x, 1, z, SLAB) for x in range(5) for z in range(3)])
    show("grounded steel beam + slab", len(bs), sc.solve(bs))
    # the same slab with no beam under it, as the control
    show("  control: slab alone, grounded", 15,
         sc.solve([blk(x, 0, z, SLAB, support=True) for x in range(5) for z in range(3)]))

    print("\nC. a request cut in half on the way in")
    full = [blk(0, i, 0, STEEL, support=(i == 0)) for i in range(10)]
    load = [{"x": 0, "y": Y0 + 9, "z": 0, "fx": 0.0, "fy": -50000.0, "fz": 0.0}]
    a = show("full 10 column + 50 kN top", 10, sc.solve(full, load))

    # bottom three sit in a chunk that is not loaded, so they never reach the engine
    b = show("base 3 never arrived", 7, sc.solve(full[3:], load))
    print(f"   the world is unchanged; max D/C {a:.4f} -> {b:.4f}")

    # the load's own block is missing: the top three did not arrive
    c = show("top 3 never arrived", 7, sc.solve(full[:7], load))
    print(f"   the world is unchanged; max D/C {a:.4f} -> {c:.4f}")

    # a two-column portal where one column is in the unloaded chunk
    portal = ([blk(0, i, 0, STEEL, support=(i == 0)) for i in range(5)]
              + [blk(6, i, 0, STEEL, support=(i == 0)) for i in range(5)]
              + [blk(x, 5, 0, STEEL) for x in range(7)])
    pload = [{"x": 3, "y": Y0 + 5, "z": 0, "fx": 0.0, "fy": -100000.0, "fz": 0.0}]
    d = show("portal, both columns", len(portal), sc.solve(portal, pload))
    half = [b_ for b_ in portal if b_["x"] < 5]
    e = show("portal, right column missing", len(half), sc.solve(half, pload))
    print(f"   the world is unchanged; max D/C {d:.4f} -> {e:.4f}"
          f"   ({100 * (e / d - 1):+.0f}%)" if d else "")

    sc.close()


if __name__ == "__main__":
    main()
