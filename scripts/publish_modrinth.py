#!/usr/bin/env python3
"""
Create the Modrinth project and upload a version, from the files in this repository.

    export MODRINTH_TOKEN=...          # or: --token-file path/to/token
    python3 scripts/publish_modrinth.py --dry-run
    python3 scripts/publish_modrinth.py

The token is read from the environment or a file and is never printed, never written
to disk by this script, and never sent anywhere except api.modrinth.com. Generate one
at https://modrinth.com/settings/pats with the scopes CREATE_PROJECT, WRITE_PROJECT,
CREATE_VERSION and WRITE_VERSION.

What it does, in order:

  1. creates the project as a DRAFT, with the icon from the jar
  2. uploads the jar as a Beta version, with the release notes as its changelog
  3. uploads the gallery images, if a site checkout is given

It deliberately stops there. A Modrinth draft is not public until someone presses
"Submit for review" on the project page, and that decision is a human's — not least
because this repository has no gate that proves the jar loads in a running game.

Re-running is safe in the sense that Modrinth refuses a duplicate slug and a duplicate
version number rather than overwriting; the script reports and stops.
"""
from __future__ import annotations

import argparse
import json
import mimetypes
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile
from pathlib import Path

API = "https://api.modrinth.com/v2"
UA = "rocky59487/block-reality publish script (github.com/rocky59487/block-reality)"

ROOT = Path(__file__).resolve().parent.parent

SLUG = "block-reality"
TITLE = "Block Reality"
SUMMARY = ("Real finite element analysis on the blocks you place — stress contours, "
           "demand/capacity per member, and buckling.")
CATEGORIES = ["technology", "utility"]
LICENSE = "Apache-2.0"
SOURCE = "https://github.com/rocky59487/block-reality"
ISSUES = "https://github.com/rocky59487/block-reality/issues"
WIKI = "https://rocky59487.github.io/block-reality-site/"

GAME_VERSIONS = ["1.20.1"]
LOADERS = ["forge"]
VERSION_TYPE = "beta"

# Gallery order is the order Modrinth shows them in. The first one is featured.
GALLERY = [
    ("img/utilisation-lens.webp", "Utilisation lens",
     "Colour is demand over capacity across the whole structure."),
    ("img/analysis.webp", "Analysis readout",
     "The HUD names the governing fibre and the worst demand/capacity ratio."),
    ("img/column-loaded.webp", "Loaded column",
     "A slender column under load, with its section readout."),
    ("img/column-selfweight.webp", "Column under self weight",
     "Every member carries its own weight before anything is added."),
    ("img/somethingbigger.webp", "A larger frame",
     "Members, slabs and walls solved as one structure."),
    ("img/buildsomething.webp", "Building with structural blocks",
     "Nine structural blocks that mix into a single model where they touch."),
    ("img/creative-tab.webp", "Creative tab",
     "The blocks, and the stress glasses used to read them."),
    ("img/br-status.webp", "/br status",
     "Engine identity and the independently recomputed equilibrium residual."),
]


def die(msg: str) -> None:
    print(f"error: {msg}", file=sys.stderr)
    raise SystemExit(1)


def read_token(args: argparse.Namespace) -> str:
    if args.token_file:
        tok = Path(args.token_file).read_text(encoding="utf-8").strip()
        if not tok:
            die(f"{args.token_file} is empty")
        return tok
    tok = os.environ.get("MODRINTH_TOKEN", "").strip()
    if not tok:
        die("no token. Set MODRINTH_TOKEN, or pass --token-file. "
            "Generate one at https://modrinth.com/settings/pats")
    return tok


def multipart(fields: dict[str, str], files: list[tuple[str, str, bytes]]) -> tuple[bytes, str]:
    """Build a multipart/form-data body. files is [(field, filename, content)]."""
    boundary = "----brmodrinth" + uuid.uuid4().hex
    out = bytearray()
    for name, value in fields.items():
        out += f"--{boundary}\r\n".encode()
        out += f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode()
        out += value.encode("utf-8") + b"\r\n"
    for name, filename, content in files:
        ctype = mimetypes.guess_type(filename)[0] or "application/octet-stream"
        out += f"--{boundary}\r\n".encode()
        out += (f'Content-Disposition: form-data; name="{name}"; '
                f'filename="{filename}"\r\n').encode()
        out += f"Content-Type: {ctype}\r\n\r\n".encode()
        out += content + b"\r\n"
    out += f"--{boundary}--\r\n".encode()
    return bytes(out), f"multipart/form-data; boundary={boundary}"


def call(token: str, method: str, path: str, *, body: bytes | None = None,
         ctype: str | None = None) -> dict:
    req = urllib.request.Request(API + path, data=body, method=method)
    req.add_header("Authorization", token)
    req.add_header("User-Agent", UA)
    if ctype:
        req.add_header("Content-Type", ctype)
    try:
        with urllib.request.urlopen(req) as r:
            raw = r.read()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")
        # Modrinth answers with a JSON {error, description}; show the description,
        # which is the part that says what is actually wrong.
        try:
            detail = json.loads(detail).get("description", detail)
        except Exception:
            pass
        die(f"{method} {path} -> HTTP {e.code}: {detail}")


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
    raise AssertionError  # unreachable, keeps type checkers quiet


def icon_from_jar(jar: Path) -> bytes:
    with zipfile.ZipFile(jar) as z:
        return z.read("blockreality_icon.png")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--token-file", help="file holding the Modrinth PAT (not printed)")
    ap.add_argument("--site", help="path to a block-reality-site checkout, for gallery images")
    ap.add_argument("--dry-run", action="store_true",
                    help="show exactly what would be sent, contact nothing")
    ap.add_argument("--project", help="existing project id or slug; skip creation "
                                      "and only upload the version")
    args = ap.parse_args()

    jar = find_jar()
    version = version_of(jar)
    body_md = (ROOT / "docs/outreach/paste/description.md").read_text(encoding="utf-8")
    notes_path = ROOT / f"docs/outreach/paste/release-notes-{version}.md"
    if not notes_path.exists():
        die(f"{notes_path} is missing — write the changelog before publishing")
    changelog = notes_path.read_text(encoding="utf-8")

    gallery: list[tuple[Path, str, str]] = []
    if args.site:
        site = Path(args.site)
        for rel, title, desc in GALLERY:
            p = site / rel
            if p.exists():
                gallery.append((p, title, desc))
            else:
                print(f"  (skipping missing {rel})")

    print(f"jar        {jar.name}  ({jar.stat().st_size} bytes)")
    print(f"version    {version}   channel {VERSION_TYPE}")
    print(f"slug       {SLUG}")
    print(f"body       {len(body_md)} chars from docs/outreach/paste/description.md")
    print(f"changelog  {len(changelog)} chars from {notes_path.name}")
    print(f"gallery    {len(gallery)} image(s)")

    if args.dry_run:
        print("\ndry run: nothing was sent.")
        return

    token = read_token(args)

    if args.project:
        project_id = args.project
        print(f"\nusing existing project {project_id}")
    else:
        meta = {
            "slug": SLUG,
            "title": TITLE,
            "description": SUMMARY,
            "body": body_md,
            "categories": CATEGORIES,
            "client_side": "required",
            "server_side": "required",
            "project_type": "mod",
            "license_id": LICENSE,
            "source_url": SOURCE,
            "issues_url": ISSUES,
            "wiki_url": WIKI,
            "is_draft": True,
            "initial_versions": [],
        }
        payload, ctype = multipart(
            {"data": json.dumps(meta)},
            [("icon", "icon.png", icon_from_jar(jar))],
        )
        created = call(token, "POST", "/project", body=payload, ctype=ctype)
        project_id = created["id"]
        print(f"\ncreated draft project {project_id} ({SLUG})")

    vmeta = {
        "name": f"{TITLE} {version}",
        "version_number": version,
        "changelog": changelog,
        "dependencies": [],
        "game_versions": GAME_VERSIONS,
        "version_type": VERSION_TYPE,
        "loaders": LOADERS,
        "featured": True,
        "project_id": project_id,
        "file_parts": ["file"],
        "primary_file": "file",
    }
    payload, ctype = multipart(
        {"data": json.dumps(vmeta)},
        [("file", jar.name, jar.read_bytes())],
    )
    v = call(token, "POST", "/version", body=payload, ctype=ctype)
    print(f"uploaded version {v['version_number']} ({v['id']})")

    for i, (path, title, desc) in enumerate(gallery):
        ext = path.suffix.lstrip(".")
        q = (f"/project/{project_id}/gallery?ext={ext}"
             f"&featured={'true' if i == 0 else 'false'}"
             f"&title={urllib.parse.quote(title)}"
             f"&description={urllib.parse.quote(desc)}&ordering={i}")
        call(token, "POST", q, body=path.read_bytes(),
             ctype=mimetypes.guess_type(path.name)[0] or "image/webp")
        print(f"uploaded gallery image {path.name}")

    print(f"\nDraft is at https://modrinth.com/mod/{SLUG}/settings")
    print("It is NOT public yet. Review it, then press 'Submit for review' yourself —")
    print("and install the jar in a running game first, because nothing here checks that.")


if __name__ == "__main__":
    main()
