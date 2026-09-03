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

TWO SHAPES (D-044). The mod used to ship an ENGINE EXECUTABLE inside the jar, unpacked
and started as a child process. That shape does not pass distribution review, so the
engine is becoming a shared LIBRARY the game loads in-process. Both shapes exist while
the game flow moves over, and they are gated differently on purpose:

    engine.manifest   -> the sidecar shape. The old rules, unchanged. The executables it
                         carries are inventoried and printed, because that inventory IS
                         the reason for the move and it should be visible every run.
    natives.manifest  -> the library shape. Everything above PLUS the rule that makes it
                         reviewable: THE JAR CONTAINS NO EXECUTABLES (N24-a1), by name
                         and by magic bytes, so renaming one does not get it past.

A jar carrying both manifests is a hard failure: whichever engine the runtime picked,
the other one shipped for nothing, and "which engine is this jar" must have one answer.
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


NATIVES_MANIFEST = PREFIX + "natives.manifest"
SIDECAR_MANIFEST = PREFIX + "engine.manifest"


# ---------------------------------------------------------------- N24-a1
#
# An executable is not a file extension, so this does not ask about one alone. A shipped
# `br-sidecar` has no extension at all and is the very thing the rule exists to exclude,
# while `libbsi_tectonic.so` is an ELF with the same magic bytes and is exactly what the
# library shape is FOR. The rule is therefore: what does the format say it is, and does
# the name agree.
ELF = b"\x7fELF"
PE = b"MZ"
SHEBANG = b"#!"
# Mach-O, single-architecture, both byte orders and both widths.
MACHO = {b"\xcf\xfa\xed\xfe", b"\xce\xfa\xed\xfe", b"\xfe\xed\xfa\xce", b"\xfe\xed\xfa\xcf"}
# ...and the fat/universal header, which is CAFEBABE — THE SAME FOUR BYTES AS EVERY JAVA
# CLASS FILE. The first version of this check read four bytes and called all 97 classes in
# the mod executables, which is a gate that cries wolf until somebody turns it off. Four
# bytes are not enough to tell them apart; the next four are. A class file follows the
# magic with minor and major version numbers (major 45 = Java 1.1, 61 = Java 17, and the
# numbering has never gone backwards), a fat binary with an architecture count, which is
# how many slices it has — realistically under a dozen, never 45.
FAT_MAGIC = {b"\xca\xfe\xba\xbe", b"\xca\xfe\xba\xbf"}
SCRIPT_SUFFIXES = (".exe", ".bat", ".cmd", ".com", ".sh", ".ps1", ".msi", ".app")
LIBRARY_SUFFIXES = (".so", ".dylib", ".dll")


def is_java_class(head8):
    """CAFEBABE followed by a plausible class-file version."""
    if len(head8) < 8 or head8[:4] not in FAT_MAGIC:
        return False
    major = int.from_bytes(head8[6:8], "big")
    return 45 <= major <= 200


def executables_in(z, infos):
    """Every entry that a reviewer would call a program, with the reason.

    Extension first, because `.exe` and `.bat` are programs whatever their content, then
    the magic bytes, because renaming is the obvious way past a name check. A shared
    library carries the same ELF/PE magic as a program and is allowed EXACTLY when its
    name says library — which is why the two tests are not interchangeable and neither
    one alone would do.
    """
    out = []
    for info in infos:
        name = info.filename
        if name.endswith("/"):
            continue
        low = name.lower()
        if low.endswith(SCRIPT_SUFFIXES) and not low.endswith(".dll"):
            out.append((name, f"its name ends in {os.path.splitext(low)[1]}"))
            continue
        is_lib = low.endswith(LIBRARY_SUFFIXES) or ".so." in low
        try:
            with z.open(info) as f:
                head = f.read(8)
        except Exception as e:                      # a member that will not open at all
            out.append((name, f"could not be read to classify it: {e}"))
            continue
        if is_java_class(head):
            continue                                # bytecode in a jar is what a jar is
        if head.startswith(ELF) and not is_lib:
            out.append((name, "it is an ELF binary and its name does not say library"))
        elif head.startswith(PE) and not is_lib:
            out.append((name, "it is a PE binary and its name does not say library"))
        elif (head[:4] in MACHO or head[:4] in FAT_MAGIC) and not is_lib:
            out.append((name, "it is a Mach-O binary and its name does not say library"))
        elif head.startswith(SHEBANG):
            out.append((name, "it starts with a #! interpreter line"))
    return out


def read_manifest_lines(text, fields, problems, what):
    """Splits a manifest into records, complaining about any line that is not `fields` wide."""
    rows = []
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        if len(parts) != fields:
            problems.append(f"{what} line is not {fields} fields: {line!r}")
            continue
        rows.append(parts)
    return rows


# ---------------------------------------------------------------- the sidecar shape
def check_sidecar_shape(z, infos, names, dist, problems):
    """The engine-executable shape (D-027). These rules are unchanged; see the history in
    docs/GATES.md 2026-08-23b for why each one is shaped the way it is."""
    with open(os.path.join(ROOT, "evidence", "verification.json"), encoding="utf-8") as f:
        ident = json.load(f)["identity"]

    manifest = z.read(SIDECAR_MANIFEST).decode("utf-8")
    seen = []
    for os_, arch, name, claimed, size in read_manifest_lines(manifest, 5, problems, "manifest"):
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

    expected = {SIDECAR_MANIFEST} | {PREFIX + r[2] for r in
                                     read_manifest_lines(manifest, 5, [], "manifest")}
    check_engine_directory(infos, expected, problems)
    return "the sidecar shape: both engines bundled, and jar == manifest == evidence == dist/"


# ---------------------------------------------------------------- the library shape
def check_natives_shape(z, infos, names, dist, problems):
    """The in-process shape (D-044): a shared library, loaded, never executed."""
    manifest = z.read(NATIVES_MANIFEST).decode("utf-8")
    rows = read_manifest_lines(manifest, 7, problems, "natives manifest")

    # The mod's own contract pin. A bundled engine that speaks a different contract than
    # the mod that carries it is the D-044 failure mode with the loudest consequence and
    # the quietest symptom: the handshake refuses at runtime, in a player's log, with
    # nobody able to say which half is stale. It costs one file read to catch it here.
    pin_path = os.path.join(ROOT, "contract", "CONTRACT_SHA256")
    pin = None
    if os.path.exists(pin_path):
        pin = open(pin_path, encoding="utf-8").read().split()[0].strip().lower()
    else:
        problems.append("contract/CONTRACT_SHA256 is missing: this build cannot state which "
                        "contract the mod speaks, so it cannot check the engine against it")

    expected = {NATIVES_MANIFEST}
    for os_, arch, name, claimed, size, engine_version, contract in rows:
        entry = f"{PREFIX}{os_}-{arch}/{name}"
        expected.add(entry)
        if entry not in names:
            problems.append(f"natives manifest lists {entry} but the jar does not carry it")
            continue
        data = z.read(entry)
        actual = sha256_bytes(data)
        if actual != claimed:
            problems.append(f"{entry}: jar has {actual[:12]}…, manifest says {claimed[:12]}…")
        if len(data) != int(size):
            problems.append(f"{entry}: jar has {len(data)} bytes, manifest says {size}")
        if not engine_version or engine_version == "unknown":
            problems.append(f"{entry}: the manifest does not name an engine version, so a "
                            f"player's log cannot say which engine they are running")
        if pin and contract.lower() != pin:
            problems.append(f"{entry}: built against contract {contract[:12]}… but the mod pins "
                            f"{pin[:12]}… — this jar's engine and its Java half do not speak "
                            f"the same interface")

        loose = os.path.join(dist, f"{os_}-{arch}", name)
        if os.path.exists(loose) and sha256_file(loose) != actual:
            problems.append(f"{entry}: dist/{os_}-{arch}/{name} and the copy inside the jar differ")

    if not rows:
        problems.append("the natives manifest lists nothing — a jar that says it carries an "
                        "engine and does not is the one outcome this gate exists to prevent")

    check_engine_directory(infos, expected, problems)
    platforms = ", ".join(f"{r[0]}-{r[1]}" for r in rows) or "nothing"
    return f"the library shape: {platforms}, jar == manifest, contract pin agrees, zero executables"


def check_engine_directory(infos, expected, problems):
    """Nothing large anywhere, and nothing unlisted in the engine directory at any size.

    The size rule caught a jar that shipped two copies of both engines with every gate
    green (docs/GATES.md 2026-08-23b); the directory rule caught what the size rule could
    not, which was a small unlisted file sitting next to the engines. A gate that only
    looks where it expects to find things cannot see what it did not expect.
    """
    for info in infos:
        if info.file_size >= 64 * 1024 and info.filename not in expected:
            problems.append(f"unexpected {info.file_size // 1024} KB payload in the jar: "
                            f"{info.filename}")
        if info.filename.startswith(PREFIX) and info.filename not in expected \
                and not info.filename.endswith("/"):
            problems.append(f"{info.filename} is in the engine directory but not in the manifest")


def main():
    dist = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "dist")
    jars = [f for f in os.listdir(dist) if f.startswith("blockreality-") and f.endswith(".jar")]
    if len(jars) != 1:
        return fail(f"expected exactly one mod jar in {dist}, found {jars}")
    jar = os.path.join(dist, jars[0])

    problems = []
    notes = []
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

        has_sidecar = SIDECAR_MANIFEST in names
        has_natives = NATIVES_MANIFEST in names
        if has_sidecar and has_natives:
            return fail(f"{jars[0]} carries BOTH engine.manifest and natives.manifest. Whichever "
                        f"engine the runtime picks, the other shipped for nothing, and 'which "
                        f"engine is this jar' must have exactly one answer.")
        if not has_sidecar and not has_natives:
            return fail(f"{jars[0]} carries no engine manifest — it was built without an engine. "
                        f"Pass -PbrEngineDir or -PbrNativesDir to the forge build, or run "
                        f"scripts/package.sh, which does.")

        # ---- N24-a1, on both shapes. Hard for the library shape, inventoried for the
        # other: the sidecar jar carries executables BY DESIGN, and printing them every
        # run is the point — that inventory is the reason D-044 moved off this shape, and
        # a number that only appears in a decision document stops being felt.
        found = executables_in(z, infos)
        if has_natives:
            for name, why in found:
                problems.append(f"N24-a1: {name} is an executable in the jar ({why}). The "
                                f"in-process shape ships libraries, which are loaded, never run.")
        elif found:
            notes.append(f"RECORDED: this jar carries {len(found)} executable(s) — "
                         + ", ".join(n for n, _ in found)
                         + ". That is what the sidecar shape is, and what D-044 retires.")

        if has_natives:
            headline = check_natives_shape(z, infos, names, dist, problems)
        else:
            headline = check_sidecar_shape(z, infos, names, dist, problems)

        # ---- licences travel with the artefact, not the repository (Apache-2.0 4(a)/4(d))
        for required in ("META-INF/LICENSE", "META-INF/NOTICE"):
            if required not in names:
                problems.append(f"the jar does not carry {required} (Apache-2.0 4(a)/4(d))")
        third_party = {n for n in names if n.startswith("META-INF/third_party/")
                       and not n.endswith("/")}
        if not third_party:
            problems.append("the jar carries no third-party licence texts, and the engine "
                            "statically links code that is not this project's")
        if has_natives:
            # N24-a5. The library statically links OpenBLAS (BSD-3), METIS (Apache-2.0)
            # and the engine itself (Apache-2.0); their terms travel with every copy of
            # those bytes, so a recipient who gets the jar must get the texts.
            for want in ("OpenBLAS", "METIS", "tectonic2"):
                if not any(want.lower() in n.lower() for n in third_party):
                    problems.append(f"N24-a5: the library shape statically links {want} and the "
                                    f"jar carries no licence text naming it")

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

    for n in notes:
        print("  " + n)
    if problems:
        print(f"{jars[0]}:")
        for p in problems:
            print("  FAIL " + p)
        return 1
    print(f"{jars[0]}: {headline}")
    return 0


def fail(msg):
    print("FAIL " + msg)
    return 1


if __name__ == "__main__":
    sys.exit(main())
