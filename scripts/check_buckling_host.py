#!/usr/bin/env python3
"""Build six host fault variants and retain exact named outcomes; no engine mechanics."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
MUTATIONS = ["ANY_COMPUTED", "COLLECTION", "KIND", "FACTOR", "ORDER", "DISABLED"]


def run(args):
    out = Path(args.out).resolve()
    out.mkdir(parents=True, exist_ok=False)
    build = Path(args.build).resolve()
    windows = sys.platform == "win32"
    suffix = ".exe" if windows else ""
    summary = {}
    for arm in ["clean"] + MUTATIONS:
        exe = build / ("buckling_tests" + suffix)
        if arm != "clean":
            exe = out / (arm + suffix)
            cmd = [args.cxx, "-std=c++17", "-O2", "-DBSI_STATIC", "-DBSI_HOST_TEST_MUTATIONS=1",
                   "-DBSI_TEST_BUCKLING_" + arm, str(ROOT / "contract/host/bsi_writer.cpp"),
                   str(ROOT / "contract/host/test/buckling_tests.cpp"), str(build / "libbsi_host.a"),
                   "-o", str(exe)]
            result = subprocess.run(cmd, capture_output=True)
            (out / (arm + "-build.txt")).write_bytes(result.stdout + result.stderr)
            if result.returncode:
                raise RuntimeError(f"{arm} build failed: {result.returncode}")
        outputs = []
        for trial in range(3):
            result = subprocess.run([str(exe)], capture_output=True)
            (out / f"{arm}-{trial}.txt").write_bytes(result.stdout)
            (out / f"{arm}-{trial}-stderr.txt").write_bytes(result.stderr)
            if result.returncode != (0 if arm == "clean" else 1) or result.stderr:
                raise RuntimeError(f"{arm}: expected named test exit, got {result.returncode}")
            outputs.append(result.stdout)
        if not outputs[0] == outputs[1] == outputs[2]:
            raise RuntimeError(f"{arm}: DET3 mismatch")
        lines = outputs[0].decode().splitlines()
        names = [x.rsplit(": ", 1)[0] for x in lines if x.endswith((": PASS", ": FAIL"))]
        failed = [x.rsplit(": ", 1)[0] for x in lines if x.endswith(": FAIL")]
        if not names or len(names) != len(set(names)) or (arm != "clean" and not failed):
            raise RuntimeError(f"{arm}: invalid named check set")
        summary[arm] = {"checks": names, "failed": failed}
        print(f"{arm}: {len(names)} checks / {len(failed)} FAIL / DET3", flush=True)
    (out / "counts.json").write_text(json.dumps(summary, indent=2) + "\n")
    receipts = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(out.iterdir()) if p.is_file()}
    receipts["contract"] = (ROOT / "contract/CONTRACT_SHA256").read_text().strip()
    (out / "sha256.json").write_text(json.dumps(receipts, indent=2) + "\n")
    if args.expected and summary != json.loads(Path(args.expected).read_text()):
        raise RuntimeError("named outcomes differ from frozen counts")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--expected")
    parser.add_argument("--cxx", default="g++")
    run(parser.parse_args())
