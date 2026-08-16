#!/usr/bin/env python3
"""Generates the verification evidence: identity, accuracy, determinism, performance.

    scripts/evidence.py sidecar/build/br-sidecar [--windows <exe-or-wrapper>]

Writes evidence/verification.json and evidence/VERIFICATION.md.

Why this exists rather than a table typed by hand: every number a reader might check
should come from a run they can repeat, stamped with the exact engine, compiler and
fixture set that produced it. A table in a document drifts from the code the moment
either changes, and a drifted table is worse than none — it looks checked.

Each case carries its own closed-form reference, computed here from beam theory rather
than read back from the engine. A test whose expected value comes from the thing under
test is not a test.
"""
import hashlib
import json
import math
import os
import platform
import subprocess
import statistics
import sys
import time

BLOCK_MM = 1000.0
G = 9.81

# steel_rect_200x400
B, D = 200.0, 400.0
A = B * D
WZ = B * D * D / 6.0
IZ = B * D ** 3 / 12.0
RHO = 7850.0
W = RHO * A * 1e-9 * G           # 6.16068 N/mm
FY_ALLOW_COMP = 350.0


class Sidecar:
    def __init__(self, exe):
        self.exe = exe
        self.p = subprocess.Popen([exe], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  text=True, bufsize=1)

    def call(self, req):
        self.p.stdin.write(json.dumps(req) + "\n")
        self.p.stdin.flush()
        return json.loads(self.p.stdout.readline())

    def raw(self, req):
        self.p.stdin.write(json.dumps(req) + "\n")
        self.p.stdin.flush()
        return self.p.stdout.readline().strip()

    def close(self):
        try:
            self.p.stdin.close()
        except Exception:
            pass
        self.p.wait(timeout=10)


def beam(n, mat="steel", section="steel_rect_200x400", supports=(0,), y=64):
    return [{"x": i, "y": y, "z": 0, "mat": mat, "section": section,
             "support": i in supports} for i in range(n)]


def top_sigma(member, k):
    st = member["stations"][k]
    for f in st["fibres"]:
        if f["dir"][1] > 0.5:
            return f["sigma"]
    return None


# --------------------------------------------------------------------- cases
def cases():
    """(name, request, [(quantity, expected, extractor)]) — references are analytic."""
    out = []

    # V1 cantilever, tip load only (self weight cancelled by rho? no: kept, so include it)
    n, L, P = 5, 4000.0, 20000.0
    req = {"op": "solve", "revision": 1, "blocks": beam(n),
           "loads": [{"x": n - 1, "y": 64, "z": 0, "fy": -P}]}
    checks = []
    for k in range(11):
        x = L * k / 10.0
        a = L - x
        checks.append((f"sigma_top(x={x:.0f}mm)",
                       (P * a + W * a * a / 2.0) / WZ,
                       (lambda kk: (lambda r: top_sigma(r["members"][0], kk)))(k)))
    checks.append(("D/C", ((P * L + W * L * L / 2.0) / WZ) / FY_ALLOW_COMP,
                   lambda r: r["members"][0]["dc"]))
    checks.append(("member length (mm)", L, lambda r: r["members"][0]["lengthMm"]))
    out.append(("V1  cantilever, tip load + self weight", req, checks))

    # V2 cantilever, self weight only
    req = {"op": "solve", "revision": 2, "blocks": beam(n)}
    checks = []
    for k in range(11):
        a = L - L * k / 10.0
        checks.append((f"sigma_top(x={L * k / 10.0:.0f}mm)", W * a * a / 2.0 / WZ,
                       (lambda kk: (lambda r: top_sigma(r["members"][0], kk)))(k)))
    out.append(("V2  cantilever, self weight only", req, checks))

    # V3 interior-governing: upward tip load P = wL/2 zeroes both ends, peak at midspan
    n3, L3 = 9, 8000.0
    p3 = W * L3 / 2.0
    req = {"op": "solve", "revision": 3, "blocks": beam(n3),
           "loads": [{"x": n3 - 1, "y": 64, "z": 0, "fy": p3}]}
    checks = [
        ("moment at end i (N.mm)", 0.0, lambda r: r["members"][0]["i"]["Mz"]),
        ("moment at end j (N.mm)", 0.0, lambda r: r["members"][0]["j"]["Mz"]),
        ("peak |sigma| at midspan (MPa)", (W * L3 * L3 / 8.0) / WZ,
         lambda r: max(abs(top_sigma(r["members"][0], k))
                       for k in range(len(r["members"][0]["stations"])))),
        ("D/C from the interior", ((W * L3 * L3 / 8.0) / WZ) / FY_ALLOW_COMP,
         lambda r: r["members"][0]["dc"]),
    ]
    out.append(("V3  interior-governing cantilever (both ends zero)", req, checks))

    # V4 fixed-fixed with a midspan stub: moment reverses along the member
    n4, L4 = 9, 8000.0
    blocks = beam(n4, supports=(0, n4 - 1))
    blocks += [{"x": 4, "y": 65, "z": 0, "mat": "steel", "section": "steel_rect_200x400"},
               {"x": 4, "y": 66, "z": 0, "mat": "steel", "section": "steel_rect_200x400"}]
    req = {"op": "solve", "revision": 4, "blocks": blocks}
    p_stub = W * 2 * BLOCK_MM

    def half(r):
        hs = [m for m in r["members"]
              if m["blocks"][0][1] == 64 and m["blocks"][-1][1] == 64]
        return sorted(hs, key=lambda m: m["blocks"][0][0])

    checks = [
        ("support moment (N.mm)", W * L4 * L4 / 12.0 + p_stub * L4 / 8.0,
         lambda r: half(r)[0]["i"]["Mz"]),
        ("midspan moment (N.mm)", -(W * L4 * L4 / 24.0 + p_stub * L4 / 8.0),
         lambda r: half(r)[0]["j"]["Mz"]),
        ("adjacent members agree at the shared node",
         0.0, lambda r: half(r)[0]["j"]["Mz"] - half(r)[1]["i"]["Mz"]),
    ]
    out.append(("V4  fixed-fixed with midspan node (moment reverses)", req, checks))

    # V5 axial: uniform stress, no bending
    n5 = 5
    req = {"op": "solve", "revision": 5, "blocks": beam(n5),
           "loads": [{"x": n5 - 1, "y": 64, "z": 0, "fx": 100000.0}]}
    checks = [("mean fibre stress = N/A (MPa)", 100000.0 / A,
               lambda r: (top_sigma(r["members"][0], 5)
                          + [f["sigma"] for f in r["members"][0]["stations"][5]["fibres"]
                             if f["dir"][1] < -0.5][0]) / 2.0)]
    out.append(("V5  axial tension", req, checks))

    # V6 concrete: the governing fibre follows the material, D/C against Rtens
    cb, cd = 400.0, 600.0
    wc = 2350.0 * (cb * cd) * 1e-9 * G
    req = {"op": "solve", "revision": 6,
           "blocks": beam(5, mat="concrete", section="concrete_rect_400x600")}
    checks = [("D/C against Rtens = 3 MPa",
               ((wc * 4000.0 ** 2 / 2.0) / (cb * cd * cd / 6.0)) / 3.0,
               lambda r: r["members"][0]["dc"])]
    out.append(("V6  concrete section, tension governs", req, checks))

    return out


def properties(sc):
    """Behaviours with no closed form: they are either right or wrong, not approximate."""
    out = []

    r = sc.call({"op": "solve", "revision": 20,
                 "blocks": [dict(b, support=False) for b in beam(5)]})
    out.append(("P1  unsupported structure reported as a mechanism",
                r.get("ok") is True and r.get("singular") is True and bool(r.get("diagnostic"))))

    r = sc.call({"op": "solve", "revision": 21, "blocks": beam(1)})
    out.append(("P2  a single block is not a beam",
                r.get("ok") is True and not r.get("members") and len(r.get("unassigned", [])) == 1))

    r = sc.call({"op": "solve", "revision": 22, "blocks": beam(5),
                 "loads": [{"x": 2, "y": 64, "z": 0, "fy": -1000.0}]})
    out.append(("P3  an unrepresentable load is refused, not dropped", r.get("ok") is False))

    bad = dict(beam(2)[0]); bad.pop("mat")
    r = sc.call({"op": "solve", "revision": 23, "blocks": [bad, beam(2)[1]]})
    out.append(("P4  a missing material is refused, not defaulted", r.get("ok") is False))

    r = sc.call({"op": "solve", "revision": 24,
                 "blocks": [dict(b, section="steel_h400") for b in beam(2)]})
    out.append(("P5  an unknown section is refused", r.get("ok") is False))

    a = sc.call({"op": "solve", "revision": 25, "blocks": beam(5)})
    b = sc.call({"op": "solve", "revision": 25, "blocks": beam(5)})
    out.append(("P6  repeated solves are bit-identical", json.dumps(a) == json.dumps(b)))

    return out


# ---------------------------------------------------------------- performance
def performance(exe):
    """Wall-clock per solve against problem size. Median of repeats, cold process excluded."""
    rows = []
    for n_members in (1, 2, 5, 10, 20, 50, 100):
        # A comb: one spine plus n_members-1 stubs, so member count grows with the model.
        blocks = beam(2 * n_members + 1)
        for k in range(1, n_members):
            blocks += [{"x": 2 * k, "y": 65, "z": 0, "mat": "steel",
                        "section": "steel_rect_200x400"}]
        sc = Sidecar(exe)
        sc.call({"op": "hello"})
        req = {"op": "solve", "revision": 1, "blocks": blocks}
        sc.call(req)                                    # warm the process
        times = []
        for i in range(9):
            t0 = time.perf_counter()
            r = sc.call(dict(req, revision=i + 2))
            times.append((time.perf_counter() - t0) * 1000.0)
        got = len(r.get("members", []))
        sc.close()
        rows.append({"blocks": len(blocks), "members": got,
                     "median_ms": round(statistics.median(times), 3),
                     "min_ms": round(min(times), 3),
                     "max_ms": round(max(times), 3)})
    return rows


# ------------------------------------------------------------------- identity
def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def identity(exe, framecore_dir):
    def git(*args):
        try:
            return subprocess.check_output(["git", "-C", framecore_dir, *args],
                                           text=True, stderr=subprocess.DEVNULL).strip()
        except Exception:
            return "unavailable"

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    files = {}
    for rel in ("sidecar/main.cpp", "sidecar/json.hpp", "sidecar/verify.py",
                "sidecar/CMakeLists.txt", "scripts/evidence.py"):
        p = os.path.join(root, rel)
        if os.path.exists(p):
            files[rel] = sha256(p)

    return {
        "engine": {
            "name": "FrameCore",
            "commit": git("rev-parse", "HEAD"),
            "committed": git("log", "-1", "--format=%cI"),
            "worktree_clean": git("status", "--porcelain") == "",
            "supernodal_lane": "compiled out (FRAMECORE_SUPERNODAL=0); solves via Eigen SimplicialLDLT",
        },
        "binary": {"path": os.path.abspath(exe), "sha256": sha256(exe)},
        "sources": files,
        "host": {
            "platform": platform.platform(),
            "python": platform.python_version(),
        },
    }


# ------------------------------------------------------------------------ run
def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    exe = sys.argv[1]
    win = None
    if "--windows" in sys.argv:
        win = sys.argv[sys.argv.index("--windows") + 1]
    framecore = os.environ.get("FRAMECORE_DIR",
                               "/home/user/architect_simulator/Plugins/FrameSolver/Source/FrameCore")

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    outdir = os.path.join(root, "evidence")
    os.makedirs(outdir, exist_ok=True)

    sc = Sidecar(exe)
    hello = sc.call({"op": "hello"})

    # Two metrics, not one. A quantity whose exact value is ZERO cannot have a relative
    # error, and folding it into the same figure as the others would misreport the
    # accuracy of everything else: one absolute residual of 1e-8 on a zero reference
    # would be quoted as though the method were 1e-8 accurate, when the non-zero
    # comparisons are eight orders of magnitude better.
    results = []
    rels, absresid = [], []
    for name, req, checks in cases():
        r = sc.call(req)
        if not r.get("ok"):
            results.append({"case": name, "error": r.get("error", "solve failed")})
            continue
        rows = []
        for label, expected, extract in checks:
            got = extract(r)
            if got is None:
                rows.append({"quantity": label, "expected": expected, "got": None,
                             "rel": None, "abs": None})
                continue
            err = abs(got - expected)
            if abs(expected) > 0:
                rel = err / abs(expected)
                rels.append(rel)
                rows.append({"quantity": label, "expected": expected, "got": got,
                             "rel": rel, "abs": None})
            else:
                absresid.append(err)
                rows.append({"quantity": label, "expected": expected, "got": got,
                             "rel": None, "abs": err})
        results.append({"case": name, "checks": rows})
    worst_rel = max(rels) if rels else 0.0
    worst_abs = max(absresid) if absresid else 0.0

    props = properties(sc)

    # Cross-platform determinism over the whole fixture set, not one case.
    determinism = {"checked": False}
    if win:
        wsc = Sidecar(win)
        wsc.call({"op": "hello"})
        same, total = 0, 0
        for _, req, _ in cases():
            total += 1
            if sc.raw(req) == wsc.raw(req):
                same += 1
        wsc.close()
        determinism = {"checked": True, "identical": same, "cases": total,
                       "note": "byte-for-byte comparison of the full reply line"}

    sc.close()

    perf = performance(exe)

    doc = {
        "identity": identity(exe, framecore),
        "handshake": hello,
        "accuracy": {
            "cases": results,
            "nonzero_references": {
                "comparisons": len(rels),
                "worst_relative_error": worst_rel,
                "rms_relative_error": math.sqrt(sum(x * x for x in rels) / len(rels)) if rels else None,
            },
            "zero_references": {
                "comparisons": len(absresid),
                "worst_absolute_residual": worst_abs,
                "note": "quantities whose exact value is zero; compared absolutely",
            },
        },
        "properties": [{"property": p, "holds": ok} for p, ok in props],
        "determinism": determinism,
        "performance": perf,
    }

    with open(os.path.join(outdir, "verification.json"), "w") as f:
        json.dump(doc, f, indent=2)

    write_markdown(os.path.join(outdir, "VERIFICATION.md"), doc)

    ok = worst_rel < 1e-9 and worst_abs < 1e-3 and all(ok for _, ok in props) \
        and (not determinism["checked"] or determinism["identical"] == determinism["cases"])
    print(f"worst relative error {worst_rel:.3e} over {len(rels)} non-zero references")
    print(f"worst absolute residual {worst_abs:.3e} over {len(absresid)} zero references")
    print(f"properties {sum(1 for _, o in props if o)}/{len(props)}")
    if determinism["checked"]:
        print(f"determinism {determinism['identical']}/{determinism['cases']} identical across platforms")
    print("evidence/verification.json and evidence/VERIFICATION.md written")
    return 0 if ok else 1


def write_markdown(path, doc):
    L = []
    ident = doc["identity"]
    L.append("# Verification evidence\n")
    L.append("Generated by `scripts/evidence.py`. Every number here comes from a run of the")
    L.append("binary named below; none is transcribed by hand.\n")

    L.append("## Identity\n")
    L.append("| | |")
    L.append("|---|---|")
    L.append(f"| engine | {ident['engine']['name']} |")
    L.append(f"| commit | `{ident['engine']['commit']}` ({ident['engine']['committed']}) |")
    L.append(f"| worktree clean | {ident['engine']['worktree_clean']} |")
    L.append(f"| solver lane | {ident['engine']['supernodal_lane']} |")
    L.append(f"| binary sha256 | `{ident['binary']['sha256']}` |")
    L.append(f"| host | {ident['host']['platform']} |")
    L.append("")
    L.append("Source hashes:\n")
    L.append("| file | sha256 |")
    L.append("|---|---|")
    for k, v in ident["sources"].items():
        L.append(f"| `{k}` | `{v}` |")
    L.append("")

    acc = doc["accuracy"]
    nz, zr = acc["nonzero_references"], acc["zero_references"]
    L.append("## Accuracy against closed-form solutions\n")
    L.append(f"**{nz['comparisons']} comparisons against non-zero references: worst relative")
    L.append(f"error {nz['worst_relative_error']:.3e}, RMS {nz['rms_relative_error']:.3e}.**\n")
    L.append(f"**{zr['comparisons']} comparisons against exactly-zero references: worst absolute")
    L.append(f"residual {zr['worst_absolute_residual']:.3e} N·mm.**\n")
    L.append("The two are reported separately on purpose. A quantity whose exact value is zero")
    L.append("has no relative error, and folding such a case into the same figure would")
    L.append("misreport everything else — a single absolute residual of 1e-8 on a zero")
    L.append("reference would be quoted as though the method were 1e-8 accurate, when the")
    L.append("non-zero comparisons are several orders of magnitude better than that.\n")
    for case in acc["cases"]:
        L.append(f"### {case['case']}\n")
        if "error" in case:
            L.append(f"engine error: `{case['error']}`\n")
            continue
        L.append("| quantity | closed form | engine | error |")
        L.append("|---|---:|---:|---:|")
        for c in case["checks"]:
            got = "—" if c["got"] is None else f"{c['got']:.6g}"
            if c["rel"] is not None:
                err = f"{c['rel']:.2e} rel"
            elif c["abs"] is not None:
                err = f"{c['abs']:.2e} abs"
            else:
                err = "—"
            L.append(f"| {c['quantity']} | {c['expected']:.6g} | {got} | {err} |")
        L.append("")

    L.append("## Properties\n")
    L.append("Behaviours with no closed form: each is either right or wrong.\n")
    L.append("| property | holds |")
    L.append("|---|---|")
    for p in doc["properties"]:
        L.append(f"| {p['property']} | {'yes' if p['holds'] else 'NO'} |")
    L.append("")

    det = doc["determinism"]
    L.append("## Cross-platform determinism\n")
    if det["checked"]:
        L.append(f"**{det['identical']}/{det['cases']} cases byte-for-byte identical** between the")
        L.append("native Linux binary and the Windows cross-build. Comparison is of the whole")
        L.append("reply line, not of selected fields.\n")
    else:
        L.append("Not checked in this run (no second binary supplied).\n")

    L.append("## Performance\n")
    L.append("Wall clock per solve, measured from the client side over the protocol, so it")
    L.append("includes serialisation and the process boundary. Median of nine repeats after")
    L.append("one warm-up; the cold process start is excluded because it happens once.\n")
    L.append("| blocks | members | median (ms) | min | max | ms/member |")
    L.append("|---:|---:|---:|---:|---:|---:|")
    for r in doc["performance"]:
        per = r["median_ms"] / r["members"] if r["members"] else float("nan")
        L.append(f"| {r['blocks']} | {r['members']} | {r['median_ms']} | {r['min_ms']} "
                 f"| {r['max_ms']} | {per:.3f} |")
    L.append("")
    big = doc["performance"][-1]
    L.append(f"At {big['members']} members the whole round trip is {big['median_ms']:.1f} ms,")
    L.append("against a Minecraft tick of 50 ms — and the solve does not run on the tick")
    L.append("thread, so this is latency to a result rather than time taken from the game.")
    L.append("")

    with open(path, "w") as f:
        f.write("\n".join(L) + "\n")


if __name__ == "__main__":
    sys.exit(main())
