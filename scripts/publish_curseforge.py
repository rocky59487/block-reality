#!/usr/bin/env python3
"""
Upload a release file to an existing CurseForge project.

    python3 scripts/publish_curseforge.py --project 1673233 --list-versions
    python3 scripts/publish_curseforge.py --project 1673233 --dry-run
    python3 scripts/publish_curseforge.py --project 1673233 --token-file ~/.cf-token

The project must already exist: CurseForge has no create-project endpoint, so the
project is made in the web UI and only the files are automated here. The page's
description, categories, licence and links are likewise web-only — paste
docs/outreach/paste/description.md into the description field by hand.

Get a token at https://legacy.curseforge.com/account/api-tokens. It is read from
CURSEFORGE_TOKEN or --token-file, never printed and never written anywhere.

The one thing that catches people: `gameVersions` is a list of CurseForge's own
integer ids, not strings like "1.20.1", and it must name the modloader as well as
the Minecraft version — a file uploaded without the Forge id shows up under every
loader. This script resolves both ids by name and refuses to upload if either is
missing, rather than guessing.
"""
from __future__ import annotations

import argparse
import json
import mimetypes
import os
import sys
import urllib.error
import urllib.request
import uuid
import zipfile
from pathlib import Path

API = "https://minecraft.curseforge.com/api"
UA = "rocky59487/block-reality publish script (github.com/rocky59487/block-reality)"

ROOT = Path(__file__).resolve().parent.parent

MC_VERSION = "1.20.1"
MODLOADER = "Forge"
RELEASE_TYPE = "beta"


def die(msg: str) -> None:
    print(f"error: {msg}", file=sys.stderr)
    raise SystemExit(1)


def read_token(args: argparse.Namespace) -> str:
    if args.token_file:
        tok = Path(args.token_file).expanduser().read_text(encoding="utf-8").strip()
        if not tok:
            die(f"{args.token_file} is empty")
        return tok
    tok = os.environ.get("CURSEFORGE_TOKEN", "").strip()
    if not tok:
        die("no token. Set CURSEFORGE_TOKEN, or pass --token-file. "
            "Generate one at https://legacy.curseforge.com/account/api-tokens")
    return tok


def call(token: str, path: str, *, body: bytes | None = None,
         ctype: str | None = None, method: str = "GET"):
    req = urllib.request.Request(API + path, data=body, method=method)
    req.add_header("X-Api-Token", token)
    req.add_header("User-Agent", UA)
    if ctype:
        req.add_header("Content-Type", ctype)
    try:
        with urllib.request.urlopen(req) as r:
            raw = r.read()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        die(f"{method} {path} -> HTTP {e.code}: {e.read().decode('utf-8', 'replace')[:400]}")


def multipart(fields: dict[str, str], files: list[tuple[str, str, bytes]]) -> tuple[bytes, str]:
    boundary = "----brcurseforge" + uuid.uuid4().hex
    out = bytearray()
    for name, value in fields.items():
        out += f"--{boundary}\r\n".encode()
        out += f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode()
        out += value.encode("utf-8") + b"\r\n"
    for name, filename, content in files:
        ct = mimetypes.guess_type(filename)[0] or "application/java-archive"
        out += f"--{boundary}\r\n".encode()
        out += (f'Content-Disposition: form-data; name="{name}"; '
                f'filename="{filename}"\r\n').encode()
        out += f"Content-Type: {ct}\r\n\r\n".encode()
        out += content + b"\r\n"
    out += f"--{boundary}--\r\n".encode()
    return bytes(out), f"multipart/form-data; boundary={boundary}"


def find_jar() -> Path:
    jars = sorted((ROOT / "dist").glob("blockreality-*.jar"))
    if len(jars) != 1:
        die(f"expected exactly one jar in dist/, found {len(jars)}")
    return jars[0]


def version_of(jar: Path) -> str:
    with zipfile.ZipFile(jar) as z:
        toml = z.read("META-INF/mods.toml").decode("utf-8")
    for line in toml.splitlines():
        if line.strip().startswith("version"):
            return line.split("=", 1)[1].strip().strip('"')
    die("no version in the jar's mods.toml")
    raise AssertionError


def resolve_versions(token: str) -> tuple[list[int], list[str]]:
    """Return the gameVersions ids for the Minecraft version and the modloader."""
    types = {t["id"]: t for t in call(token, "/game/version-types")}
    versions = call(token, "/game/versions")
    ids: list[int] = []
    notes: list[str] = []
    for want, kind in ((MC_VERSION, "minecraft"), (MODLOADER, "modloader")):
        hits = [v for v in versions if v.get("name") == want]
        if not hits:
            die(f"CurseForge does not list a {kind} version named {want!r}")
        # Prefer the entry whose version type slug looks like the kind we asked for,
        # so "Forge" the loader is not confused with anything else sharing the name.
        best = hits[0]
        for v in hits:
            slug = (types.get(v.get("gameVersionTypeID"), {}) or {}).get("slug", "")
            if kind == "modloader" and "modloader" in slug:
                best = v
                break
        ids.append(best["id"])
        tslug = (types.get(best.get("gameVersionTypeID"), {}) or {}).get("slug", "?")
        notes.append(f"{want} -> id {best['id']} (type {tslug})")
    return ids, notes


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--project", required=True, help="CurseForge project id, e.g. 1673233")
    ap.add_argument("--token-file", help="file holding the CurseForge API token (not printed)")
    ap.add_argument("--list-versions", action="store_true",
                    help="print the ids this script would use, upload nothing")
    ap.add_argument("--dry-run", action="store_true",
                    help="resolve everything and show the metadata, upload nothing")
    args = ap.parse_args()

    jar = find_jar()
    version = version_of(jar)
    notes_path = ROOT / f"docs/outreach/paste/release-notes-{version}.md"
    if not notes_path.exists():
        die(f"{notes_path} is missing — write the changelog before publishing")
    changelog = notes_path.read_text(encoding="utf-8")

    token = read_token(args)
    ids, notes = resolve_versions(token)

    print(f"project    {args.project}")
    print(f"jar        {jar.name}  ({jar.stat().st_size} bytes)")
    print(f"version    {version}   releaseType {RELEASE_TYPE}")
    for n in notes:
        print(f"  {n}")
    print(f"changelog  {len(changelog)} chars from {notes_path.name}")

    if args.list_versions or args.dry_run:
        print("\nnothing was uploaded.")
        return

    metadata = {
        "changelog": changelog,
        "changelogType": "markdown",
        "displayName": f"Block Reality {version}",
        "gameVersions": ids,
        "releaseType": RELEASE_TYPE,
    }
    payload, ctype = multipart(
        {"metadata": json.dumps(metadata)},
        [("file", jar.name, jar.read_bytes())],
    )
    r = call(token, f"/projects/{args.project}/upload-file",
             body=payload, ctype=ctype, method="POST")
    print(f"\nuploaded file id {r.get('id')}")
    print("CurseForge scans every upload, and a jar containing a native executable is "
          "very likely to go to a human.")
    print("The reply kit for that is docs/outreach/LISTING.md section 1.1.")


if __name__ == "__main__":
    main()
