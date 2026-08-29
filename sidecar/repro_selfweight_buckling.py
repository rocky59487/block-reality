#!/usr/bin/env python3
"""
Reproduces the self-weight buckling mesh dependence.

    python repro_selfweight_buckling.py [path-to-br-sidecar]

Builds a plain 20-block steel column, supported at the base, and asks the engine
for its buckling factor under three conditions. It applies NO meaningful load in
part B -- one newton against 117 kN of self weight -- so any change there is the
mesh, not the physics.

Reference for part C is Greenhill's problem: a uniform vertical cantilever
buckling under its own weight, q L^3 / (EI) = 7.837.
"""
import json
import subprocess
import sys

EXE = sys.argv[1] if len(sys.argv) > 1 else "sidecar/build/br-sidecar"

N, Y0 = 20, 64                       # 20 blocks -> 19 m centre to centre
SECTION = "steel_rect_200x400"
B, D = 0.200, 0.400                  # m
E = 200e9                            # Pa, the value verify.py C12 uses
RHO, G = 7850.0, 9.81

A = B * D
I_WEAK = D * B ** 3 / 12.0
L = (N - 1) * 1.0
Q = A * RHO * G                      # N/m
LAMBDA_EXACT = 7.837 * E * I_WEAK / L ** 3 / Q


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
        line = self.p.stdout.readline()
        if not line:
            raise RuntimeError("sidecar closed the pipe")
        return json.loads(line)

    def close(self):
        self.p.stdin.write('{"op":"bye"}\n')
        self.p.stdin.flush()


def column():
    return [{"x": 0, "y": Y0 + i, "z": 0, "mat": "steel",
             "section": SECTION, "support": i == 0} for i in range(N)]


def load(i, fx=0.0, fy=0.0, fz=0.0):
    return {"x": 0, "y": Y0 + i, "z": 0, "fx": fx, "fy": fy, "fz": fz}


def main():
    sc = Sidecar(EXE)
    print(f"column {L:.0f} m, section {SECTION}, self weight {Q * L / 1000:.1f} kN")
    print(f"Greenhill exact lambda_cr = {LAMBDA_EXACT:.2f}\n")

    print("A. where the load sits decides whether the run splits")
    print(f"   {'load position':<22}{'members':>9}{'lambda_cr':>11}{'max D/C':>10}")
    for tag, i in [("none", None), ("top block", N - 1), ("one below top", N - 2),
                   ("mid height", N // 2), ("base block", 0)]:
        r = sc.solve(column(), [load(i, 1000.0, 1000.0, 1000.0)] if i is not None else None)
        m = r.get("members", [])
        dc = max([x["dc"] for x in m], default=0.0)
        print(f"   {tag:<22}{len(m):>9}{r['bucklingFactor']:>11.4f}{dc:>10.4f}")

    print("\nB. ONE NEWTON -- 0.00085% of self weight, so this is the mesh, not the load")
    print(f"   {'load position':<22}{'members':>9}{'lambda_cr':>11}{'vs no load':>12}")
    base = sc.solve(column())["bucklingFactor"]
    print(f"   {'none':<22}{1:>9}{base:>11.4f}{'--':>12}")
    for tag, i in [("top block", N - 1), ("one below top", N - 2), ("mid height", N // 2)]:
        r = sc.solve(column(), [load(i, fy=-1.0)])
        print(f"   {tag:<22}{len(r['members']):>9}{r['bucklingFactor']:>11.4f}"
              f"{100 * (r['bucklingFactor'] / base - 1):>11.1f}%")

    print("\nC. convergence -- interior nodes forced by negligible 1 N loads")
    print(f"   {'elements':>9}{'lambda_cr':>11}{'vs Greenhill':>14}")
    for k in [0, 1, 3, 9, 19]:
        loads = [load(round(N * j / (k + 1)), fy=-1.0) for j in range(1, k + 1)]
        r = sc.solve(column(), loads or None)
        lam = r["bucklingFactor"]
        print(f"   {len(r['members']):>9}{lam:>11.4f}"
              f"{100 * (lam / LAMBDA_EXACT - 1):>13.0f}%")
    print(f"   {'exact':>9}{LAMBDA_EXACT:>11.4f}{0:>13.0f}%")

    sc.close()


if __name__ == "__main__":
    main()
