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
        # infolist(), not a set of names. A zip may carry the SAME name twice and readers
        # disagree about which copy wins — z.read() takes the last, most tools show the
        # last, some extractors write both. Padding a 3 MB duplicate in front of the real
        # engine passed every check here and moved the jar by 324 bytes, because a set had
        # already thrown the evidence away.
        infos = z.infolist()
        names = set()
        for info in infos:
            if info.filename in names:
                problems.append(f"the jar carries {info.filename} more than once — readers "
                                f"disagree about which copy wins")
            names.add(info.filename)
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
        # 64 KB, not 512 KB. The largest legitimate non-engine entry in this jar is 17 KB,
        # so anything above 64 KB that is not an engine wants explaining; the old threshold
        # let 511 KB through for free.
        for info in infos:
            if info.file_size >= 64 * 1024 and info.filename not in expected:
                problems.append(f"unexpected {info.file_size // 1024} KB payload in the jar: "
                                f"{info.filename}")
            # ...and nothing unlisted may sit in the engine directory, at any size.
            if info.filename.startswith(PREFIX) and info.filename not in expected \
                    and not info.filename.endswith("/"):
                problems.append(f"{info.filename} is in the engine directory but not in "
                                f"the manifest")

    for required in ("META-INF/LICENSE", "META-INF/NOTICE"):
        if required not in names:
            problems.append(f"the jar does not carry {required} (Apache-2.0 4(a)/4(d))")
    if not any(n.startswith("META-INF/third_party/") for n in names):
        problems.append("the jar carries no third-party licence texts, and the engine "
                        "statically links FrameCore (MIT) and Eigen (MPL-2.0)")

    # `sha256sum -c` only verifies the files ON the list, and the release workflow zips
    # the whole directory with no allow-list — so one stray file in this TRACKED directory
    # would ship publicly with every gate green. A rehearsal carried a .env holding a fake
    # secret and a debug build all the way into the published archive, exit 0 throughout.
    sums = os.path.join(dist, "SHA256SUMS.txt")
    if not os.path.exists(sums):
        problems.append("dist/SHA256SUMS.txt is missing")
    else:
        listed = set()
        with open(sums, encoding="utf-8") as f:
            for line in f:
                parts = line.split(None, 1)
                if len(parts) == 2:
                    listed.add(parts[1].strip().lstrip("*"))
        present = set()
        for base, _dirs, files in os.walk(dist):
            for name in files:
                rel = os.path.relpath(os.path.join(base, name), dist).replace(os.sep, "/")
                if rel != "SHA256SUMS.txt":
                    present.add(rel)
        for stray in sorted(present - listed):
            problems.append(f"dist/{stray} is not in SHA256SUMS.txt — it would be published "
                            f"in the archive with nothing vouching for it")
        for gone in sorted(listed - present):
            problems.append(f"SHA256SUMS.txt lists {gone}, which is not there")

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
