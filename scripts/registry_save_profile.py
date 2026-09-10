"""Run the opt-in Forge adapter profile; use identical seed files on both platforms."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import signal
import subprocess
import time


def run(command, cwd, log_path):
    start = time.monotonic()
    with log_path.open("w", encoding="utf-8") as log:
        kwargs = {"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP} if os.name == "nt" else {"start_new_session": True}
        process = subprocess.Popen(command, cwd=cwd, stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, **kwargs)
        try:
            code = process.wait(timeout=600)
        except subprocess.TimeoutExpired:
            if os.name == "nt":
                # Only the process tree started above, never other game/Gradle processes.
                subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"], stdout=log, stderr=subprocess.STDOUT, check=False)
            else:
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
            process.wait()
            code = "timeout"
    return {"exit_code": code, "elapsed_seconds": time.monotonic()-start}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--root", type=Path, required=True)
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--seeds", type=Path, required=True)
    p.add_argument("--make-seeds", action="store_true")
    a = p.parse_args()
    root, out, seeds = a.root.resolve(), a.out.resolve(), a.seeds.resolve()
    assert (root/"forge/gradlew").is_file()
    if a.make_seeds:
        assert not seeds.exists(), "refusing to replace an existing seed set"
    else:
        assert seeds.is_dir(), "an existing immutable seed set is required"
    out.mkdir(parents=True, exist_ok=False)
    sources = {}
    for base in (root/"mod/api/src/main", root/"mod/core/src/main", root/"forge/src/main", root/"forge/src/profile"):
        for f in sorted(base.rglob("*")):
            if f.is_file():
                data = f.read_bytes()
                if f.suffix in (".java", ".json", ".toml", ".txt", ".mcmeta"):
                    data = data.replace(b"\r\n",b"\n")
                sources[f.relative_to(root).as_posix()] = hashlib.sha256(data).hexdigest()
    metadata = {"platform": platform.platform(), "output_path": str(out), "sources_lf_sha256": sources}
    if os.name != "nt":
        metadata["filesystem"] = subprocess.check_output(["findmnt", "-T", str(out), "-o", "FSTYPE,SOURCE,TARGET", "--json"], text=True)
    else:
        disks = json.loads(subprocess.check_output(["powershell", "-NoProfile", "-NonInteractive", "-Command",
                "Get-CimInstance Win32_LogicalDisk | Select-Object DeviceID, FileSystem, Size, FreeSpace | ConvertTo-Json -Compress"], text=True))
        if isinstance(disks, dict):
            disks = [disks]
        metadata["filesystem"] = next(d for d in disks if d["DeviceID"].casefold() == out.drive.casefold())
    (out/"identity.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    wrapper = ["cmd", "/c", "gradlew.bat"] if os.name == "nt" else ["bash", "gradlew"]
    def command(fork):
        return wrapper+["--no-daemon", "-I", "../scripts/registry-save-profile.gradle", "registrySaveProfile",
                        f"-Dbr.saveOutput={out}", f"-Dbr.saveSeeds={seeds}", f"-Dbr.saveFork={fork}", "--console=plain"]
    if a.make_seeds:
        receipt = run(command("seed"), root/"forge", out/"seed-first.log")
        (out/"seed-exit.json").write_text(json.dumps(receipt), encoding="utf-8")
        print("seed", json.dumps(receipt), flush=True)
        if receipt["exit_code"] != 0:
            raise SystemExit("seed creation failed; first output retained")
    seed_hashes = {f.relative_to(seeds).as_posix(): hashlib.sha256(f.read_bytes()).hexdigest() for f in sorted(seeds.rglob("*.dat"))}
    assert set(seed_hashes) == {f"{s}/blockreality_world_index.dat" for s in ("D4096", "D32768", "D131072")}
    (out/"seed-hashes.json").write_text(json.dumps(seed_hashes, indent=2), encoding="utf-8")
    for fork in range(1, 4):
        receipt = run(command(fork), root/"forge", out/f"fork-{fork}.log")
        (out/f"fork-{fork}-exit.json").write_text(json.dumps(receipt), encoding="utf-8")
        print(f"fork {fork}", json.dumps(receipt), flush=True)
        if receipt["exit_code"] != 0:
            raise SystemExit(f"fork {fork} failed; partial output retained")


if __name__ == "__main__":
    main()
