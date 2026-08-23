#!/usr/bin/env python3
"""Every check count quoted in a document must be the count the suites actually print.

    python3 scripts/check_docs.py dist/br-sidecar

Why this file exists. "219 acceptance checks" was quoted in nine documents and in the
GitHub release body, and the true number was 218: somebody had counted `grep -c PASS`,
which counts the trailing "ALL PASS" line too. The off-by-one survived two generations
of documents and left the repository as a public claim (PR26_REVIEW A-8 / DOC-1). Three
older numbers — 151, 164, 216 — were still sitting in outreach material at the same time,
each true of some earlier build and none of the shipped one.

Counting by hand is the defect. verify.py now prints its own total, gradle's JUnit XML
already carries the Java totals, and this script asserts that every quoted number in the
table below equals one of them.

A pattern that stops matching its file is a FAILURE, not a skip. Otherwise rewording a
sentence silently switches the check off, which is the same class of mistake as the
`| tail -3` that ate the engine gate's exit code.
"""
import glob
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# (path, regex with exactly one capture group, which count it must equal)
#
# ENGINE            sidecar/verify.py total
# JAVA_TOTAL        every JUnit test in mod/ and forge/
# JAVA_ENGINE       the subset that starts br-sidecar and runs FrameCore
TABLE = [
    ("README.md", r"`sidecar/verify\.py`, (\d+) checks, all passing", "ENGINE"),
    ("README.md", r"`sidecar/verify\.py` (\d+) 項全過", "ENGINE"),
    ("README.md", r"\| Java \| (\d+) tests, all passing", "JAVA_TOTAL"),
    ("README.md", r"\| Java \| (\d+) 項測試全過", "JAVA_TOTAL"),
    ("README.md", r"(\d+) of them start `br-sidecar`", "JAVA_ENGINE"),
    ("README.md", r"其中 (\d+) 項會實際啟動 `br-sidecar`", "JAVA_ENGINE"),
    ("README.md", r"verification-(\d+)_engine", "ENGINE"),
    ("README.md", r"engine_%2B_(\d+)_Java_checks", "JAVA_TOTAL"),
    ("QUICKSTART.md", r"`ALL PASS`（(\d+) 項", "ENGINE"),
    ("QUICKSTART.md", r"其中 (\d+) 個會\*\*實際啟動", "JAVA_ENGINE"),
    ("QUICKSTART.md", r"沒帶 `-Dbr\.sidecar` 這 (\d+) 個會 skip", "JAVA_ENGINE"),
    ("QUICKSTART.md", r"資料層另外被 (\d+) 個測試釘住", "JAVA_TOTAL"),
    ("docs/RESEARCH_BRIEF.md", r"\| Engine checks \| (\d+)/\d+ passing", "ENGINE"),
    ("docs/RESEARCH_BRIEF.md", r"\| Engine checks \| \d+/(\d+) passing", "ENGINE"),
    ("docs/RESEARCH_BRIEF.md", r"\| Java tests \| (\d+)/\d+ passing", "JAVA_TOTAL"),
    ("docs/RESEARCH_BRIEF.md", r"\| Java tests \| \d+/(\d+) passing", "JAVA_TOTAL"),
    ("docs/RESEARCH_BRIEF.md", r"(\d+) start the real sidecar", "JAVA_ENGINE"),
    ("docs/outreach/OUTREACH.md", r"record\. (\d+) acceptance", "ENGINE"),
    ("docs/outreach/OUTREACH.md", r"\((\d+) closed-form acceptance checks per build", "ENGINE"),
    ("docs/outreach/COMMUNITY.md", r"gimmick: (\d+) acceptance checks", "ENGINE"),
    ("docs/outreach/COMMUNITY.md", r"\((\d+) 項閉合解", "ENGINE"),
    ("docs/outreach/FUNDING.md", r"\*\*:(\d+) 項閉合解 gate", "ENGINE"),
    ("sidecar/README.md", r"\*\*，(\d+) 項 gate 兩邊全過", "ENGINE"),
    ("sidecar/README.md", r"Wine 實測 (\d+) 項全過", "ENGINE"),
    ("sidecar/README.md", r"^(\d+) 項，全部對閉合解", "ENGINE"),
    ("sidecar/patches/README.md", r"# 本專案的 (\d+) 項 gate", "ENGINE"),
    (".github/workflows/release.yml", r"every number is gated: (\d+) acceptance checks", "ENGINE"),
]


def engine_count(exe):
    out = subprocess.run([sys.executable, os.path.join(ROOT, "sidecar", "verify.py"), exe],
                         capture_output=True, text=True).stdout
    m = re.search(r"ALL PASS \((\d+) checks\)", out)
    if not m:
        sys.exit("verify.py did not report ALL PASS; run it and read the failure first")
    return int(m.group(1))


def java_counts():
    """(total, engine-backed) from the JUnit XML gradle already writes."""
    total, engine = 0, 0
    roots = glob.glob(os.path.join(ROOT, "mod", "*", "build", "test-results", "test")) \
        + glob.glob(os.path.join(ROOT, "forge", "build", "test-results", "test"))
    if not roots:
        return None, None
    for d in roots:
        for f in glob.glob(os.path.join(d, "TEST-*.xml")):
            suite = ET.parse(f).getroot()
            n = int(suite.get("tests", 0))
            total += n
            # The engine-backed classes are the ones that launch br-sidecar; they are
            # named here rather than guessed, so adding one is a deliberate act.
            if os.path.basename(f) in ("TEST-com.blockreality.core.sidecar.SidecarEngineTest.xml",):
                engine += n
    return total, engine


def main():
    exe = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "dist", "br-sidecar")
    counts = {"ENGINE": engine_count(exe)}
    jt, je = java_counts()
    counts["JAVA_TOTAL"], counts["JAVA_ENGINE"] = jt, je
    print(f"measured: engine={counts['ENGINE']} java_total={jt} java_engine_backed={je}")

    bad = []
    for path, pattern, kind in TABLE:
        full = os.path.join(ROOT, path)
        if not os.path.exists(full):
            bad.append(f"{path}: file is gone, but this table still checks it")
            continue
        text = open(full, encoding="utf-8").read()
        # Documents wrap; a claim split over two lines is still one claim.
        flat = re.sub(r"\s+", " ", text)
        m = re.search(pattern, flat, re.MULTILINE) or re.search(pattern, text, re.MULTILINE)
        if not m:
            bad.append(f"{path}: pattern no longer matches — {pattern}")
            continue
        want = counts.get(kind)
        if want is None:
            print(f"  skip {path} [{kind}]: no test results on disk to compare against")
            continue
        got = int(m.group(1))
        if got != want:
            bad.append(f"{path}: says {got} {kind.lower()} checks, the suite reports {want}")

    if bad:
        print()
        for b in bad:
            print("FAIL " + b)
        return 1
    print(f"{len(TABLE)} quoted counts all agree with the suites")
    return 0


if __name__ == "__main__":
    sys.exit(main())
