#!/usr/bin/env python3
"""The jar must carry the engines the evidence record verified.

    python3 scripts/check_bundle.py [dist]

The mod now ships as one jar with the engine inside it (D-027), which moves an install
step into a build step — and a build step with no gate is how "the jar contains an
engine" becomes a claim nobody checks. Three hashes have to agree for that claim to
mean anything:

    the bytes in the jar  ==  the manifest beside them  ==  the binary the acceptance
                                                            suite and evidence.py ran

Any two of those agreeing is not enough. Manifest against jar catches a repack; jar
against evidence catches the one that actually hurts — a jar built from an engine that
never passed its gates, or from a stale dist/ left over from an earlier build.
"""
import hashlib
import json
import os
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PREFIX = "blockreality-engine/"      # hyphenated on purpose; see BundledEngine.DIR
# manifest os/arch -> the field evidence.py records it under
EVIDENCE_FIELD = {("linux", "x86_64"): "binary", ("windows", "x86_64"): "binary_windows"}


def sha256_bytes(b):
    return hashlib.sha256(b).hexdigest()


def sha256_file(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    dist = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "dist")
    jars = [f for f in os.listdir(dist) if f.startswith("blockreality-") and f.endswith(".jar")]
    if len(jars) != 1:
        return fail(f"expected exactly one mod jar in {dist}, found {jars}")
    jar = os.path.join(dist, jars[0])

    with open(os.path.join(ROOT, "evidence", "verification.json"), encoding="utf-8") as f:
        ident = json.load(f)["identity"]

    problems = []
    with zipfile.ZipFile(jar) as z:
        names = set(z.namelist())
        if PREFIX + "engine.manifest" not in names:
            return fail(f"{jars[0]} carries no {PREFIX}engine.manifest — it was built without "
                        f"an engine. Pass -PbrEngineDir to the forge build, or run "
                        f"scripts/package.sh, which does.")
        manifest = z.read(PREFIX + "engine.manifest").decode("utf-8")

        seen = []
        for line in manifest.splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split()
            if len(parts) != 5:
                problems.append(f"manifest line is not five fields: {line!r}")
                continue
            os_, arch, name, claimed, size = parts
            seen.append((os_, arch))

            if PREFIX + name not in names:
                problems.append(f"manifest lists {name} but the jar does not carry it")
                continue
            data = z.read(PREFIX + name)
            actual = sha256_bytes(data)
            if actual != claimed:
                problems.append(f"{name}: jar has {actual[:12]}…, manifest says {claimed[:12]}…")
            if len(data) != int(size):
                problems.append(f"{name}: jar has {len(data)} bytes, manifest says {size}")

            field = EVIDENCE_FIELD.get((os_, arch))
            if field is None:
                problems.append(f"{os_}/{arch} is in the jar but evidence.py records no such binary")
                continue
            rec = (ident.get(field) or {}).get("sha256")
            if not rec:
                problems.append(f"evidence has no identity.{field}.sha256 to compare {name} against")
            elif rec != actual:
                problems.append(f"{name}: the jar carries {actual[:12]}… but the evidence record "
                                f"verified {rec[:12]}… — the jar was built from a different engine")

            # ...and the same bytes must be the ones shipped loose beside it, or the
            # archive would hand a player two different engines under one name.
            loose = os.path.join(dist, name)
            if os.path.exists(loose):
                if sha256_file(loose) != actual:
                    problems.append(f"{name}: dist/{name} and the copy inside the jar differ")
            else:
                problems.append(f"dist/{name} is missing")

        for want in EVIDENCE_FIELD:
            if want not in seen:
                problems.append(f"nothing shipped for {want[0]}/{want[1]}")

        # ...and nothing ELSE large is in there. The check above passed happily while the
        # jar carried two copies of both engines — a renamed resource directory had left
        # the old one behind in the build's generated folder, and 2.4 MB became 4.5 MB
        # with every gate still green. A gate that only looks where it expects to find
        # things cannot see what it did not expect.
        expected = {PREFIX + n for n in
                    ["engine.manifest"] + [line.split()[2] for line in manifest.splitlines()
                                           if line.strip() and not line.startswith("#")]}
        for n in names:
            info = z.getinfo(n)
            if info.file_size >= 512 * 1024 and n not in expected:
                problems.append(f"unexpected {info.file_size // 1024} KB payload in the jar: {n}")

    if problems:
        print(f"{jars[0]}:")
        for p in problems:
            print("  FAIL " + p)
        return 1
    print(f"{jars[0]}: both engines bundled, and jar == manifest == evidence == dist/")
    return 0


def fail(msg):
    print("FAIL " + msg)
    return 1


if __name__ == "__main__":
    sys.exit(main())
