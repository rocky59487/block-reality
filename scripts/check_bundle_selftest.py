#!/usr/bin/env python3
"""Does check_bundle.py still bite? Seven injections against a known-good staging directory.

    python3 scripts/check_bundle_selftest.py <stage-dir>

A gate is a claim about what CANNOT get past it, and that claim decays silently. This
file's whole reason to exist is written in docs/GATES.md 2026-08-23b: check_bundle.py has
been tightened THREE times, each time after an injection walked past every green check —
3 MB of padding under a duplicate entry name, 511 KB of anything at all, a .env with a
fake secret riding into the published archive. Each of those was found by trying, not by
reading. So this tries, on every CI run, and fails if any injection slips.

Every case below MUST turn the gate red. A case that goes green is not a passing test: it
is the gate having lost a tooth, and the run fails naming which one.

The subject is a staging directory that check_bundle.py already accepts — the one
scripts/package_natives.sh built. Nothing here modifies it; each case is applied to a copy.
"""
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LIB_PREFIX = "blockreality-engine/"


def find_library(names):
    for n in names:
        if n.startswith(LIB_PREFIX) and (n.endswith(".so") or n.endswith(".dll") or n.endswith(".dylib")):
            return n
    return None


# ------------------------------------------------------------------ the injections
def rename_to_exe(items, lib):
    for info, _ in items:
        if info.filename == lib:
            info.filename = os.path.dirname(lib) + "/renamed.exe"
    return items


def elf_under_an_innocent_name(items, lib):
    body = next(d for i, d in items if i.filename == os.path.dirname(lib) + "/renamed.exe"
                or i.filename == lib)
    items.append((zipfile.ZipInfo("assets/blockreality/helper"), body[:8192]))
    return items


def a_shell_script(items, lib):
    items.append((zipfile.ZipInfo("assets/blockreality/setup"), b"#!/bin/sh\nexit 0\n"))
    return items


def one_flipped_byte(items, lib):
    out = []
    for info, data in items:
        if info.filename == lib:
            b = bytearray(data)
            b[len(b) // 2] ^= 0x01
            data = bytes(b)
        out.append((info, data))
    return out


def a_foreign_contract(items, lib):
    out = []
    for info, data in items:
        if info.filename.endswith("natives.manifest"):
            lines = data.decode().rstrip("\n").split("\n")
            fields = lines[-1].split()
            fields[-1] = "f" * 64
            lines[-1] = " ".join(fields)
            data = ("\n".join(lines) + "\n").encode()
        out.append((info, data))
    return out


def licences_dropped(items, lib):
    return [(i, d) for i, d in items
            if "OpenBLAS" not in i.filename and "METIS" not in i.filename]


def two_engine_shapes(items, lib):
    items.append((zipfile.ZipInfo(LIB_PREFIX + "engine.manifest"),
                  b"# os arch file sha256 size\nlinux x86_64 br-sidecar " + b"0" * 64 + b" 100\n"))
    return items


CASES = [
    ("N24-a1  the library renamed to .exe", rename_to_exe),
    ("N24-a1  an ELF binary under an innocent name", elf_under_an_innocent_name),
    ("N24-a1  a shell script", a_shell_script),
    ("N24-a3  one byte of the library flipped", one_flipped_byte),
    ("N24-a3  the manifest claiming a foreign contract", a_foreign_contract),
    ("N24-a5  the OpenBLAS and METIS licence texts dropped", licences_dropped),
    ("        one jar carrying two engine shapes", two_engine_shapes),
]


def run_case(stage, jar_name, lib, mutate, work):
    shutil.rmtree(work, ignore_errors=True)
    shutil.copytree(stage, work)
    with zipfile.ZipFile(os.path.join(stage, jar_name)) as src:
        items = [(info, src.read(info.filename)) for info in src.infolist()]
    items = mutate(items, lib)
    with zipfile.ZipFile(os.path.join(work, jar_name), "w", zipfile.ZIP_DEFLATED) as out:
        for info, data in items:
            out.writestr(info, data)
    # SHA256SUMS is regenerated so that ONLY the injected defect is under test — otherwise
    # every case would trip the stray-file rule and prove nothing about the rule it names.
    subprocess.run("find . -type f ! -name SHA256SUMS.txt -printf '%P\\n' | sort | "
                   "xargs sha256sum > SHA256SUMS.txt", shell=True, cwd=work, check=True)
    r = subprocess.run([sys.executable, os.path.join(ROOT, "scripts", "check_bundle.py"), work],
                       capture_output=True, text=True)
    return r.returncode, (r.stdout + r.stderr)


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    stage = os.path.abspath(sys.argv[1])
    jars = [f for f in os.listdir(stage) if f.startswith("blockreality-") and f.endswith(".jar")]
    if len(jars) != 1:
        print(f"FAIL expected exactly one mod jar in {stage}, found {jars}")
        return 1
    jar_name = jars[0]

    # The subject must be GREEN before anything is injected. A staging directory that is
    # already failing would make every case below "catch" something and say nothing.
    base = subprocess.run([sys.executable, os.path.join(ROOT, "scripts", "check_bundle.py"), stage],
                          capture_output=True, text=True)
    if base.returncode != 0:
        print(f"FAIL {stage} does not pass check_bundle.py before any injection:")
        print(base.stdout + base.stderr)
        return 1

    with zipfile.ZipFile(os.path.join(stage, jar_name)) as z:
        lib = find_library(z.namelist())
    if lib is None:
        print(f"FAIL {jar_name} carries no engine library — this self-test is for the "
              f"library shape, and there is nothing here to inject against")
        return 1

    slipped = []
    with tempfile.TemporaryDirectory() as tmp:
        work = os.path.join(tmp, "stage")
        for name, mutate in CASES:
            rc, out = run_case(stage, jar_name, lib, mutate, work)
            if rc == 0:
                slipped.append(name)
                print(f"SLIPPED  {name}")
            else:
                reason = next((l.strip() for l in out.splitlines() if "FAIL" in l), "")
                print(f"caught   {name}\n           {reason[:120]}")

    print()
    if slipped:
        print(f"FAIL {len(slipped)} injection(s) walked past check_bundle.py:")
        for s in slipped:
            print("  " + s)
        return 1
    print(f"SELFTEST ALL PASS ({len(CASES)} injections, 0 slipped)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
