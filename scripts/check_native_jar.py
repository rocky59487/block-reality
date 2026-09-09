#!/usr/bin/env python3
"""Real NATIVE_CONSUMER jar gate. Requires compiled NativeJarProbe, JNA and local release library.

Runs Python CAPI and jar-only production Java classes on identical C5/C6/C8 frames,
three fresh sessions each; two additional JVMs rendezvous inside resource extraction.
No production child process is introduced: every subprocess here is a test driver.
"""
import argparse
import base64
import ctypes
from contextlib import closing
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def sha(data):
    return hashlib.sha256(data).hexdigest()


def load_corpus():
    path = ROOT / "contract/conformance/run.py"
    spec = importlib.util.spec_from_file_location("native_jar_corpus", path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def frames(corpus, directory):
    raw = dict(corpus.load_cases())
    for name, data in raw.items():
        if not name.startswith(("C5-", "C6-", "C8-")):
            continue
        case = corpus.Case(name, data, raw)
        world = next(iter(case.worlds.values()))
        mats, secs = case.vocab_ids()
        for storage in ["f64", "f32"]:
            dest = directory / (case.case_id.replace("-", "_") + "_" + storage)
            dest.mkdir(parents=True, exist_ok=False)
            hello = {"bsi": 1, "client": "native-jar/1", "contractSha256": corpus.contract_sha(), "arena": {"supported": False, "maxBytes": 0}}
            solve = dict(world.solve, numThreads=1, include=["members", "stations", "shells", "memberGeometry", "stationIdentity"],
                         precision={"tier": "commit", "storage": storage})
            loads = corpus.encode_loads(world.loads)
            if loads:
                solve["loads"] = len(loads) // 64
            requests = [("bsi.hello", hello, b""), ("bsi.vocab.declare", case.vocab, b""),
                        ("bsi.world.declare", {"blocks": len(world.blocks)}, corpus.encode_blocks(world.blocks, mats, secs)),
                        ("bsi.solve", solve, loads)]
            for index, (method, body, payload) in enumerate(requests):
                (dest / f"{index}.frame").write_bytes(corpus.encode_frame(corpus.header(method, body, str(index)), payload))


def direct(corpus, library, inputs, dest):
    for session in sorted(inputs.iterdir()):
        with closing(corpus.CapiClient(str(library))) as engine:
            # Replace the default-options session with an explicitly single-thread session.
            engine.lib.bsi_capi_close(engine.h)
            engine.h = engine.lib.bsi_capi_open(b'{"numThreads":1}')
            assert engine.h, "single-thread session refused"
            for path in sorted(session.glob("*.frame")):
                frame = path.read_bytes()
                buffer = ctypes.create_string_buffer(1 << 20)
                length, needed = ctypes.c_size_t(0), ctypes.c_size_t(0)
                rc = engine.lib.bsi_capi_call(engine.h, frame, len(frame), buffer, len(buffer), ctypes.byref(length), ctypes.byref(needed))
                assert rc == 0, (path, rc, needed.value)
                reply = buffer.raw[:length.value]
                decoded = corpus.decode_frame(reply)
                assert not decoded.error, (path, decoded.h)
                if path.name == "0.frame":
                    assert decoded.h["version"] == "1.3.0" and decoded.h["contractSha256"] == corpus.contract_sha(), decoded.h
                    assert decoded.h["buildSha"] == "c90b448", decoded.h
                target = dest / session.name / path.name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(reply)


def snapshot(directory):
    return {p.relative_to(directory).as_posix(): sha(p.read_bytes()) for p in sorted(directory.rglob("*.frame"))}


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    for name in ["jar", "java", "classes", "jna", "library", "out"]:
        ap.add_argument("--" + name, type=Path, required=True)
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=False)
    log = {}
    env = dict(os.environ, OPENBLAS_CORETYPE="Haswell", OPENBLAS_NUM_THREADS="1")
    def record(name, cmd, p):
        row = {"command": list(map(str, cmd)), "exit": p.returncode}
        for key, data in [("stdout", p.stdout), ("stderr", p.stderr)]:
            row[key + "_base64"] = base64.b64encode(data).decode()
            row[key + "_sha256"] = sha(data)
        log[name] = row
        (args.out / "commands.json").write_text(json.dumps(log, indent=2) + "\n", encoding="utf-8")
        assert p.returncode == 0, (name, p.returncode, (p.stdout+p.stderr).decode(errors="replace"))
    def run(name, cmd):
        p = subprocess.run(list(map(str, cmd)), capture_output=True, env=env)
        record(name, cmd, p)
    def command(mode, cache, dest, jar=None, extra=()):
        use_jar = jar or args.jar
        cp = os.pathsep.join(map(str, [use_jar, args.classes, args.jna]))
        return list(map(str, [args.java, "-cp", cp, "com.blockreality.core.engine.NativeJarProbe",
                              mode, use_jar, cache, args.out / "inputs", dest, *extra]))
    corpus = load_corpus()
    frames(corpus, args.out / "inputs")
    platform = "windows-x86_64" if os.name == "nt" else "linux-x86_64"
    with zipfile.ZipFile(args.jar) as archive:
        manifest = archive.read("blockreality-engine/natives.manifest").decode()
        entries = [line.split() for line in manifest.splitlines() if line and not line.startswith("#")]
        entry = next(e for e in entries if e[0]+"-"+e[1] == platform)
        assert sha(args.library.read_bytes()) == entry[3]
        assert archive.read(f"blockreality-engine/{platform}/{entry[2]}") == args.library.read_bytes()
    for repetition in range(3):
        direct(corpus, args.library, args.out / "inputs", args.out / f"direct-{repetition}")
        run(f"jar-{repetition}", command("replay", args.out / "cache", args.out / f"jar-{repetition}"))
    reference = snapshot(args.out / "direct-0")
    assert len(reference) == 24, ("corpus request count", len(reference))
    for arm in [f"{kind}-{i}" for kind in ["direct", "jar"] for i in range(3)]:
        assert snapshot(args.out / arm) == reference, ("direct/jar DET mismatch", arm)
    run("faults", command("faults", args.out / "fault-cache", args.out / "faults"))
    # Permission denial must be the filesystem's answer, not a fake loader exception.
    denied = args.out / "denied-cache"
    denied.mkdir()
    if os.name == "nt":
        account = os.environ["USERDOMAIN"] + "\\" + os.environ["USERNAME"]
        run("deny-acl", ["icacls", denied, "/deny", account + ":(OI)(CI)(W)"])
        try:
            run("denied", command("denied", denied, args.out / "denied"))
        finally:
            run("restore-acl", ["icacls", denied, "/remove:d", account])
    else:
        denied.chmod(0o500)
        try:
            run("denied", command("denied", denied, args.out / "denied"))
        finally:
            denied.chmod(0o700)
    race = args.out / "race"
    race.mkdir()
    jobs = []
    try:
        for i in range(2):
            cmd = command("race", race / "cache", race / str(i), extra=[str(i)])
            jobs.append((cmd, subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)))
        deadline = time.monotonic() + 25
        while not all((race / f"ready-{i}").is_file() for i in range(2)):
            assert time.monotonic() < deadline, "two-JVM extraction rendezvous failed"
            time.sleep(0.02)
        (race / "go").write_text("go", encoding="utf-8")
        for i, (cmd, process) in enumerate(jobs):
            stdout, stderr = process.communicate(timeout=30)
            record(f"race-{i}", cmd, subprocess.CompletedProcess(cmd, process.returncode, stdout, stderr))
            assert snapshot(race / str(i)) == reference, "race changed native replies"
    finally:
        for cmd, process in jobs:
            if process.poll() is None:
                process.kill()
                stdout, stderr = process.communicate()
                log["race-aborted"] = {"stdout_base64":base64.b64encode(stdout).decode(),"stderr_base64":base64.b64encode(stderr).decode(),"exit":process.returncode}
                (args.out / "race-aborted.json").write_text(json.dumps(log["race-aborted"]),encoding="utf-8")
    # No static BsiContract cache from another classpath may rescue a broken jar resource.
    for mode in ["missing", "invalid", "overlong"]:
        bad_jar = args.out / (mode + ".jar")
        with zipfile.ZipFile(args.jar) as source, zipfile.ZipFile(bad_jar, "w", zipfile.ZIP_DEFLATED) as target:
            for info in source.infolist():
                if info.filename == "blockreality/contract/CONTRACT_SHA256":
                    if mode != "missing":
                        target.writestr(info, b"z"*64 if mode=="invalid" else corpus.contract_sha().encode()+b" "*4)
                else:
                    target.writestr(info, source.read(info.filename))
        run(mode+"-pin", command("missing-pin",args.out/(mode+"-cache"),args.out/(mode+"-pin"),jar=bad_jar))
    summary = dict(platform=platform, jar_sha256=sha(args.jar.read_bytes()), native_sha256=entry[3],
                   requests=snapshot(args.out/"inputs"), replies=reference, det_repeats=3,
                   concurrent_jvms=2, numThreads=1, scope="jar extraction + BSI replay; not a Minecraft game run")
    (args.out / "verification.json").write_text(json.dumps(summary, indent=2)+"\n",encoding="utf-8")
    print(json.dumps(summary,indent=2))


if __name__ == "__main__":
    main()
