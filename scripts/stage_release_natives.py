#!/usr/bin/env python3
"""Stage both native platforms from authenticated, locally downloaded release assets.

The caller supplies the SHA256 of the published SHA256SUMS obtained independently
(e.g. the release verification receipt). This is an integrity chain, not a signature.
No library is loaded here: each platform must still run the real NativeJarProbe gate.
For locally built libraries use stage_natives.py, which asks their actual hello.
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import tarfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PLATFORMS = {"windows-x86_64": "bsi_tectonic.dll", "linux-x86_64": "libbsi_tectonic.so"}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def safe_path(name):
    p = PurePosixPath(name)
    require(name == p.as_posix() and not p.is_absolute() and ".." not in p.parts
            and all(re.fullmatch(r"[A-Za-z0-9_.-]+", part) for part in p.parts),
            f"unsafe path: {name}")
    return name


def sums(data):
    result = {}
    for line in data.decode("utf-8").splitlines():
        digest, name = line.split(None, 1)
        safe_path(name)
        require(re.fullmatch(r"[0-9a-f]{64}", digest) and name not in result,
                f"invalid or duplicate checksum: {name}")
        result[name] = digest
    require(result, "empty checksum inventory")
    return result


def verify_release(directory, version, revision, sums_sha256, basis="published", inventory_profile="with-verification"):
    require(basis in {"published", "candidate"}, "invalid asset qualification basis")
    require(inventory_profile in {"with-verification", "sdk"}, "invalid release inventory profile")
    pin = (ROOT / "contract/CONTRACT_SHA256").read_text(encoding="utf-8").strip()
    inventory = (directory / "SHA256SUMS").read_bytes()
    require(sha(inventory) == sums_sha256, "published SHA256SUMS hash mismatch")
    assets = sums(inventory)
    expected = {f"tectonic2-{version}-{p}.zip" for p in PLATFORMS}
    source_name = f"tectonic2-{version}-source.tar.gz"
    expected.add(source_name)
    if inventory_profile == "with-verification":
        expected.add(f"tectonic2-{version}-verification.json")
    require(set(assets) == expected, "release asset inventory mismatch")
    for name, digest in assets.items():
        require(sha((directory / name).read_bytes()) == digest, f"release asset hash mismatch: {name}")
    # Verify the root source manifest, not historical manifests embedded in evidence.
    with tarfile.open(directory / source_name, "r:gz") as archive:
        members = archive.getmembers()
        names = [m.name for m in members]
        require(len(names) == len(set(names)), "duplicate source archive entry")
        require(all(m.isfile() or m.isdir() for m in members), "non-regular source archive entry")
        require(archive.extractfile("SOURCE_REVISION").read().decode().strip() == revision, "source revision mismatch")
        manifest = json.load(archive.extractfile("SOURCE_MANIFEST.json"))
        require(manifest["revision"] == revision and manifest["contract_sha256"] == pin, "source manifest identity mismatch")
        files = {m.name for m in members if m.isfile()} - {"SOURCE_MANIFEST.json"}
        require(files == set(manifest["files"]), "source manifest inventory mismatch")
        for name, digest in manifest["files"].items():
            # This archive is inspected in memory; no tar path is extracted to disk.
            require(sha(archive.extractfile(name).read()) == digest, f"source file hash mismatch: {name}")
    entries, payloads = [], {}
    for platform, filename in PLATFORMS.items():
        asset = f"tectonic2-{version}-{platform}.zip"
        with zipfile.ZipFile(directory / asset) as archive:
            names = archive.namelist()
            require(len(names) == len(set(names)), "duplicate native archive entry")
            for name in names:
                safe_path(name)
            inner = sums(archive.read("SHA256SUMS"))
            require(set(names) == set(inner) | {"SHA256SUMS"}, "native archive inventory mismatch")
            for name, digest in inner.items():
                require(sha(archive.read(name)) == digest, f"native archive hash mismatch: {name}")
            provenance = json.loads(archive.read("provenance.json"))
            require(provenance["source_commit"] == revision and provenance["version"] == version
                    and provenance["engine"] == "tectonic" and provenance["platform"] == platform
                    and provenance["contract_sha256"] == pin, f"native provenance identity mismatch: {platform}")
            for name in ["CONTRACT_SHA256", "bsi.schema.json", "BSI.md", "bsi_capi.h", "bsi_engine.h"]:
                require(archive.read("include/" + name) == (ROOT / "contract" / name).read_bytes(), f"SDK mismatch: {name}")
            data = archive.read(filename)
            binary = provenance["binary"]
            require(binary["file"] == filename and binary["bytes"] == len(data)
                    and binary["sha256"] == sha(data), f"binary provenance mismatch: {platform}")
            os_, arch = platform.split("-")
            entries.append(dict(platform=platform, os=os_, arch=arch, file=filename,
                                sha256=sha(data), size=len(data), engineVersion=f"tectonic-{version}+{revision[:7]}",
                                engine="tectonic", version=version, buildSha=revision[:7], contractSha256=pin,
                                releaseAsset=asset, releaseAssetSha256=assets[asset]))
            payloads[f"{platform}/{filename}"] = data
            payloads[f"release/{platform}/provenance.json"] = archive.read("provenance.json")
            for name in names:
                if name.startswith("licenses/") or name == "LICENSE.txt":
                    payloads[f"licenses/{platform}/{name}"] = archive.read(name)
    provenance = dict(contractSha256=pin, libraries=entries, release=dict(
        sourceCommit=revision, version=version, sha256sums=sha(inventory), assets=assets,
        inventoryProfile=inventory_profile,
        sourceFiles=len(files), basis=("published release integrity" if basis == "published"
            else "locally qualified candidate") + "; runtime hello is a separate platform gate"))
    payloads["provenance.json"] = (json.dumps(provenance, indent=2) + "\n").encode()
    return payloads, provenance


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--release-dir", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--version", required=True)
    ap.add_argument("--source-commit", required=True)
    ap.add_argument("--sums-sha256", required=True)
    ap.add_argument("--basis", choices=["published", "candidate"], default="published",
                    help="candidate identifies local qualification without claiming publication")
    ap.add_argument("--inventory-profile", choices=["with-verification", "sdk"], default="with-verification",
                    help="sdk explicitly accepts exactly two native ZIPs and the source archive; default also requires a verification JSON")
    args = ap.parse_args()
    try:
        require(not args.out.exists(), "output must be a new directory")
        require(re.fullmatch(r"[0-9a-f]{40}", args.source_commit), "invalid source commit")
        require(re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", args.version), "invalid release version")
        payloads, provenance = verify_release(args.release_dir, args.version, args.source_commit, args.sums_sha256,
                                             args.basis, args.inventory_profile)
        # All validation precedes the first write. The final provenance is the commit marker.
        args.out.mkdir(parents=True)
        for name, data in payloads.items():
            dest = args.out / name
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(data)
            dest.chmod(0o644)
        print(json.dumps(provenance, indent=2))
        return 0
    except (ValueError, OSError, KeyError, tarfile.TarError, zipfile.BadZipFile) as ex:
        print(f"FAIL native release staging: {ex}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
