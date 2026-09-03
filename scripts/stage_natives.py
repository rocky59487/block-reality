#!/usr/bin/env python3
"""Lays out the engine libraries for `forge -PbrNativesDir=…`, asking each one what it is.

    python3 scripts/stage_natives.py --out build/natives \
            --lib linux-x86_64=/path/to/libbsi_tectonic.so

The forge build needs three things per library that the file itself does not carry in its
name: which platform it is for, which engine version it is, and which BSI contract it
speaks. The first is the caller's to state. The other two are NOT taken on trust and NOT
copied out of a build script's variables — this script LOADS the library through
contract/bsi_capi.h and asks it, with the same `bsi.hello` the Minecraft mod will send.

That is the point. A manifest that repeats what the build system believed is one source of
truth wearing two hats; a manifest filled in from the engine's own answer is a second,
independent leg, and a library that will not answer, or answers with a contract this
repository does not pin, never reaches the jar. The failure it catches is the ordinary
one: an engine rebuilt against an older contract, copied into a staging directory that
still had yesterday's name.

Nothing here reaches the network. It reads local files and calls five C functions.
"""
import argparse
import ctypes
import hashlib
import json
import os
import struct
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FRAME_PREFIX = struct.Struct("<2sHII")


def sha256_file(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def contract_pin():
    with open(os.path.join(ROOT, "contract", "CONTRACT_SHA256"), encoding="utf-8") as f:
        return f.read().split()[0].strip().lower()


def ask(lib_path, pin):
    """Opens the library and returns its `bsi.hello` reply header as a dict."""
    lib = ctypes.CDLL(os.path.abspath(lib_path))
    lib.bsi_capi_abi_version.restype = ctypes.c_uint32
    lib.bsi_capi_open.restype = ctypes.c_void_p
    lib.bsi_capi_open.argtypes = [ctypes.c_char_p]
    lib.bsi_capi_call.restype = ctypes.c_int
    lib.bsi_capi_call.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_size_t,
                                  ctypes.c_void_p, ctypes.c_size_t,
                                  ctypes.POINTER(ctypes.c_size_t), ctypes.POINTER(ctypes.c_size_t)]
    lib.bsi_capi_close.argtypes = [ctypes.c_void_p]

    abi = lib.bsi_capi_abi_version()
    if abi != 1:
        raise SystemExit(f"{lib_path}: bsi_capi_abi_version() is {abi}, this build speaks 1")

    handle = lib.bsi_capi_open(json.dumps({"numThreads": 1}).encode())
    if not handle:
        raise SystemExit(f"{lib_path}: bsi_capi_open returned NULL")
    try:
        body = {"bsi": 1, "client": "stage_natives/1", "contractSha256": pin,
                "arena": {"supported": False, "maxBytes": 0}}
        hdr = json.dumps({"bsi": 1, "kind": "request", "id": "hello", "method": "bsi.hello",
                          "revision": 1, "body": body}, separators=(",", ":")).encode("utf-8")
        frame = FRAME_PREFIX.pack(b"FC", 0, len(hdr), 0) + hdr
        buf = ctypes.create_string_buffer(1 << 16)
        ln, need = ctypes.c_size_t(0), ctypes.c_size_t(0)
        rc = lib.bsi_capi_call(handle, frame, len(frame), buf, len(buf),
                               ctypes.byref(ln), ctypes.byref(need))
        if rc == 2:
            buf = ctypes.create_string_buffer(need.value + 1024)
            rc = lib.bsi_capi_call(handle, frame, len(frame), buf, len(buf),
                                   ctypes.byref(ln), ctypes.byref(need))
        if rc != 0:
            raise SystemExit(f"{lib_path}: bsi_capi_call rc={rc} on bsi.hello")
        raw = buf.raw[:ln.value]
        magic, _flags, hl, pl = FRAME_PREFIX.unpack_from(raw, 0)
        if magic != b"FC" or 12 + hl + pl != len(raw):
            raise SystemExit(f"{lib_path}: malformed reply frame")
        return json.loads(raw[12:12 + hl].decode("utf-8"))
    finally:
        lib.bsi_capi_close(handle)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True, help="staging directory to write")
    ap.add_argument("--lib", action="append", required=True, metavar="PLATFORM=PATH",
                    help="e.g. linux-x86_64=/path/to/libbsi_tectonic.so; repeatable")
    ap.add_argument("--allow-contract-mismatch", action="store_true",
                    help="stage a library whose contract differs from this repository's pin. "
                         "For inspecting a mismatch, never for building a release.")
    ap.add_argument("--require-clean", action="store_true",
                    help="refuse a library whose buildSha says it came from a dirty worktree. "
                         "scripts/package.sh passes this; a development jar does not.")
    args = ap.parse_args()

    pin = contract_pin()
    entries = []
    for spec in args.lib:
        if "=" not in spec:
            raise SystemExit(f"--lib wants PLATFORM=PATH, got {spec!r}")
        platform, path = spec.split("=", 1)
        if platform.count("-") != 1 or not all(platform.split("-")):
            raise SystemExit(f"platform {platform!r} is not <os>-<arch>")
        if not os.path.isfile(path):
            raise SystemExit(f"{path} is not a file")

        reply = ask(path, pin)
        if reply.get("status") not in (None, "OK", "ok"):
            raise SystemExit(f"{path}: bsi.hello answered {reply.get('status')} "
                             f"({reply.get('detail') or reply.get('message') or 'no detail'})")
        got = str(reply.get("contractSha256", "")).lower()
        engine = str(reply.get("engine") or "").strip()
        version = str(reply.get("version") or "").strip()
        build = str(reply.get("buildSha") or "").strip()
        if not got:
            raise SystemExit(f"{path}: bsi.hello named no contractSha256")
        if got != pin and not args.allow_contract_mismatch:
            raise SystemExit(f"{path}: the engine speaks contract {got[:12]}… and this "
                             f"repository pins {pin[:12]}…. One of the two is stale; staging "
                             f"it anyway would put the mismatch in a player's log instead.")
        if not engine or not version:
            raise SystemExit(f"{path}: bsi.hello named no engine/version, so a player's log "
                             f"could not say which engine they are running")
        # One whitespace-free token, because the manifest the forge build writes is
        # whitespace-separated and a version with a space in it would silently become two
        # fields and fail the seven-field check somewhere far from here.
        stamp = f"{engine}-{version}" + (f"+{build}" if build else "")
        if " " in stamp or "\t" in stamp:
            raise SystemExit(f"{path}: engine version {stamp!r} contains whitespace")
        if args.require_clean and build.endswith("-dirty"):
            raise SystemExit(f"{path}: built from a DIRTY worktree ({build}). A release binary "
                             f"whose source cannot be named is not traceable; rebuild from a "
                             f"committed tree, or drop --require-clean for a development jar.")
        if build.endswith("-dirty"):
            print(f"  RECORDED: {path} was built from a dirty worktree ({build})")

        dest_dir = os.path.join(args.out, platform)
        os.makedirs(dest_dir, exist_ok=True)
        dest = os.path.join(dest_dir, os.path.basename(path))
        with open(path, "rb") as src, open(dest, "wb") as dst:
            while True:
                chunk = src.read(1 << 20)
                if not chunk:
                    break
                dst.write(chunk)
        # A library is loaded, not run. Staging it without the execute bit means the
        # packaged copy cannot pick one up from the file it came from (N24-a4).
        os.chmod(dest, 0o644)

        digest = sha256_file(dest)
        entries.append({"platform": platform, "os": platform.split("-")[0],
                        "arch": platform.split("-")[1], "file": os.path.basename(path),
                        "sha256": digest, "size": os.path.getsize(dest),
                        "engineVersion": stamp, "engine": engine, "version": version,
                        "buildSha": build, "contractSha256": got,
                        "capabilities": reply.get("capabilities") or []})
        print(f"staged {platform}/{os.path.basename(path)}  engine {stamp}  "
              f"contract {got[:12]}…  sha256 {digest[:12]}…  "
              f"capabilities {len(reply.get('capabilities') or [])}")

    prov = os.path.join(args.out, "provenance.json")
    with open(prov, "w", encoding="utf-8") as f:
        json.dump({"contractSha256": pin, "libraries": entries}, f, indent=2, sort_keys=True)
        f.write("\n")
    print(f"wrote {prov}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
