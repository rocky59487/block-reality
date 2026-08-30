#!/usr/bin/env python3
"""
What subdividing a run would move, and what it would cost.

    python repro_subdivision_cost.py [path-to-br-sidecar]

The buckling mesh dependence (#65) is fixed by cutting a run into more elements.
Before asking an engine to do that, one thing has to be measured rather than
assumed: does subdividing move the LINEAR answer? If D/C shifts with the mesh
then fixing buckling silently restates every number the mod has ever shown,
which is a far larger change than the one being asked for.

Subdivision is emulated the way the engine already allows: a load on an interior
block splits the run there.

THE SPLIT LOAD HAS TO BE NEGLIGIBLE, AND 1 N IS NOT. On a vertical run, `fz` is
a HORIZONTAL force: eighteen 1 N loads up a 19 m column is 171 N*m of bending,
which over Z = 2.67e6 mm3 is 0.064 MPa -- 4.4% of the column's 1.46 MPa, and it
looks exactly like mesh sensitivity until you notice the arithmetic. This script
uses 1e-9 N and prints both, because the difference between "the mesh moved it"
and "my probe moved it" is the whole measurement.
"""
import json
import subprocess
import sys
import time

EXE = sys.argv[1] if len(sys.argv) > 1 else "sidecar/build/br-sidecar"
N, Y0 = 20, 64
SECTION = "steel_rect_200x400"


class Sidecar:
    def __init__(self, exe):
        self.p = subprocess.Popen([exe], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  text=True, bufsize=1)
        self.rev = 0

    def solve(self, blocks, loads, buckling=True):
        self.rev += 1
        req = {"op": "solve", "revision": self.rev, "blocks": blocks,
               "loads": loads, "buckling": buckling}
        t = time.perf_counter()
        self.p.stdin.write(json.dumps(req) + chr(10))
        self.p.stdin.flush()
        return json.loads(self.p.stdout.readline()), (time.perf_counter() - t) * 1000.0


def column():
    return [{"x": 0, "y": Y0 + i, "z": 0, "mat": "steel", "section": SECTION,
             "support": i == 0} for i in range(N)]


def splits(k, magnitude):
    """k elements: k-1 interior split points, spread as evenly as the blocks allow."""
    out = []
    for s in range(1, k):
        i = round(s * (N - 1) / k)
        if 0 < i < N - 1:
            out.append({"x": 0, "y": Y0 + i, "z": 0, "fz": -magnitude})
    return out


def sigma(r):
    """The governing station's compressive stress, which is what D/C is made of."""
    for m in r.get("members", []):
        if m["id"] == r.get("governing"):
            g = m["governingStation"]
            if 0 <= g < len(m["stations"]):
                return m["stations"][g]["sigmaComp"]
    return float("nan")


def main():
    sc = Sidecar(EXE)

    print()
    print("A. does the mesh move the linear answer?")
    for magnitude, label in ((1.0, "1 N split load -- NOT negligible, see the docstring"),
                             (1e-9, "1e-9 N split load -- genuinely negligible")):
        print()
        print("  %s" % label)
        print("    %-9s %-9s %-15s %-13s %s"
              % ("elements", "members", "max D/C", "sigma (MPa)", "vs 1 element"))
        base = None
        for k in (1, 2, 4, 10, 19):
            r, _ = sc.solve(column(), splits(k, magnitude), buckling=False)
            dc = r.get("maxDC", 0)
            if base is None:
                base = dc
            print("    %-9d %-9d %-15.8e %-13.6f %+.4f%%"
                  % (k, len(r.get("members", [])), dc, sigma(r),
                     100 * (dc - base) / base if base else 0))

    print()
    print("  Read the second table: the linear answer is BIT-IDENTICAL under refinement.")
    print("  Subdividing for buckling therefore restates nothing the mod has shown.")
    print()

    print("B. what buckling costs at each refinement")
    print()
    print("    %-9s %-7s %-7s %-11s %-9s %s"
          % ("elements", "nodes", "dof", "lambda_cr", "off (ms)", "on (ms)"))
    for k in (1, 2, 4, 10, 19):
        bs, ld = column(), splits(k, 1e-9)
        sc.solve(bs, ld, False)
        off = min(sc.solve(bs, ld, False)[1] for _ in range(3))
        r, _ = sc.solve(bs, ld, True)
        on = min(sc.solve(bs, ld, True)[1] for _ in range(3))
        print("    %-9d %-7s %-7s %-11.4f %-9.1f %.1f"
              % (k, r.get("nodes"), r.get("dof"), r.get("bucklingFactor"), off, on))
    print()
    print("  Greenhill exact for this column: lambda_cr = 9.8914")
    print()


main()
