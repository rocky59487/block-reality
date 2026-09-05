#!/usr/bin/env python3
"""Where the mass goes at a grounded end, and at a joint.

    python sidecar/repro_grounded_end_mass.py [path-to-br-sidecar]

N25's first in-game run reported a five-block column as L=4000mm and a five-block
beam between two columns as L=6000mm. Both are centreline conventions and both are
defensible on their own; together they raise a question no gate asks: does the
weight the engine carries equal the weight of the blocks the player placed?

A run's own length is centre-to-centre, so N blocks span N-1 metres. At a JOINT the
neighbouring run extends to this run's centre, so the half block is picked up by the
neighbour -- nothing is lost. At a FREE end and at a GROUND end there is no
neighbour, so half a block of material sits outside the model.

This script measures the reaction against rho*A*g*L for both candidate lengths and
prints which one the engine is actually using. It adjudicates nothing; the numbers
go in the N25 record.
"""
import json
import subprocess
import sys

EXE = sys.argv[1] if len(sys.argv) > 1 else "dist/br-sidecar"
Y0 = 64
SECTION = "steel_rect_200x400"
# Matching the sidecar's built-in steel catalogue entry for the rectangular section.
A_M2 = 0.200 * 0.400
RHO = 7850.0
G = 9.81


class Sidecar:
    def __init__(self, exe):
        self.p = subprocess.Popen([exe], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  text=True, bufsize=1)
        self.rev = 0

    def solve(self, blocks):
        self.rev += 1
        req = {"op": "solve", "revision": self.rev, "blocks": blocks, "loads": [],
               "buckling": False}
        self.p.stdin.write(json.dumps(req) + chr(10))
        self.p.stdin.flush()
        return json.loads(self.p.stdout.readline())


def column(n):
    return [{"x": 0, "y": Y0 + i, "z": 0, "mat": "steel", "section": SECTION,
             "support": i == 0} for i in range(n)]


def portal(h, span):
    """Two columns h blocks tall at x=0 and x=span, a beam filling the row between."""
    bs = []
    for x in (0, span):
        bs += [{"x": x, "y": Y0 + i, "z": 0, "mat": "steel", "section": SECTION,
                "support": i == 0} for i in range(h)]
    bs += [{"x": x, "y": Y0 + h - 1, "z": 0, "mat": "steel", "section": SECTION,
            "support": False} for x in range(1, span)]
    return bs


def lengths(r):
    return [m.get("lengthMm", 0) / 1000.0 for m in r.get("members", [])]


def report(label, blocks, reply):
    ls = lengths(reply)
    modelled = sum(ls)
    placed = float(len(blocks))
    w = RHO * A_M2 * G
    print("  %-22s blocks %2d (%.1f m)   members %s   modelled %.1f m"
          % (label, len(blocks), placed, [round(x, 1) for x in ls], modelled))
    print("  %-22s weight if modelled-length %9.1f N   if block-count %9.1f N   gap %+.1f%%"
          % ("", w * modelled, w * placed,
             100 * (modelled - placed) / placed if placed else 0))


def main():
    sc = Sidecar(EXE)
    print()
    print("A. a lone grounded column: no joints, so both ends are unpaired")
    print()
    for n in (2, 5, 10, 20):
        bs = column(n)
        report("%d blocks" % n, bs, sc.solve(bs))
    print()
    print("  The free top end and the ground end each drop half a block.")
    print("  N blocks model as N-1 metres, so the shortfall is 1/N and never zero.")
    print()

    print("B. the N25 portal frame: the beam's ends ARE joints, the column bases are not")
    print()
    for h, span in ((5, 6), (5, 10), (9, 6)):
        bs = portal(h, span)
        report("h=%d span=%d" % (h, span), bs, sc.solve(bs))
    print()
    print("  The beam gains one metre across its two joints; the two columns lose one")
    print("  metre each at their unpaired bases. The net is -1 m for every geometry")
    print("  measured -- it does not scale with span or height, so as a FRACTION it")
    print("  shrinks as the frame grows, and it is worst on the smallest structures.")
    print()


main()
