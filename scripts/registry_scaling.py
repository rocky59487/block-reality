"""Run three fresh profile JVMs, retaining partial output and every failure. Linux only."""
import argparse
import hashlib
import json
import pathlib
import platform
import subprocess
import time

p = argparse.ArgumentParser()
p.add_argument("--root", type=pathlib.Path, required=True)
p.add_argument("--out", type=pathlib.Path, required=True)
a = p.parse_args()
root, out = a.root.resolve(), a.out.resolve()
out.mkdir(parents=True, exist_ok=False)
sources = {}
for base in (root / "mod/api/src/main", root / "mod/core/src/main", root / "mod/core/src/profile"):
    for f in sorted(base.rglob("*")):
        if f.is_file():
            sources[str(f.relative_to(root))] = hashlib.sha256(f.read_bytes().replace(b"\r\n", b"\n")).hexdigest()
metadata = {"platform": platform.platform(), "cpu": pathlib.Path("/proc/cpuinfo").read_text(),
            "memory": pathlib.Path("/proc/meminfo").read_text(), "sources_lf_sha256": sources}
(out / "identity.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
for fork in range(1, 4):
    command = ["bash", "gradlew", "--no-daemon", "-I", "../scripts/registry-scaling.gradle",
               ":core:registryScaling", f"-Dbr.registryOutput={out / f'fork-{fork}.jsonl'}", f"-Dbr.registryFork={fork}"]
    start = time.monotonic()
    with (out / f"fork-{fork}.log").open("w", encoding="utf-8") as log:
        try:
            # A process group permits bounded timeout cleanup, including Gradle's JavaExec child.
            import os
            import signal
            process = subprocess.Popen(command, cwd=root / "mod", stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            try:
                code = process.wait(timeout=1200)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait()
                code = "timeout"
        finally:
            log.flush()
    receipt = {"fork": fork, "exit_code": code, "elapsed_seconds": time.monotonic()-start}
    (out / f"fork-{fork}-exit.json").write_text(json.dumps(receipt), encoding="utf-8")
    print(json.dumps(receipt), flush=True)
    if code != 0:
        raise SystemExit(f"fork {fork} failed; partial files retained")
