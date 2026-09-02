#!/usr/bin/env python3
"""Recompute the BSI contract hash and compare with CONTRACT_SHA256.

Covered: every file under contract/ except this script, CONTRACT_SHA256 and README.md,
in sorted relative-path order; each file contributes "<relpath>\n" + bytes.
Usage: check_contract.py [--write]   (exit 0 = match, 1 = mismatch, 2 = missing pin)
"""
import hashlib, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
EXCLUDE = {"check_contract.py", "CONTRACT_SHA256", "README.md"}

def contract_hash():
    h = hashlib.sha256()
    rels = []
    for root, _dirs, files in os.walk(HERE):
        for f in files:
            rel = os.path.relpath(os.path.join(root, f), HERE).replace(os.sep, "/")
            if rel in EXCLUDE or "__pycache__" in rel:
                continue
            rels.append(rel)
    for rel in sorted(rels):
        h.update((rel + "\n").encode("utf-8"))
        with open(os.path.join(HERE, rel), "rb") as fh:
            h.update(fh.read())
    return h.hexdigest(), sorted(rels)

def main():
    digest, rels = contract_hash()
    pin = os.path.join(HERE, "CONTRACT_SHA256")
    if "--write" in sys.argv:
        with open(pin, "w", encoding="utf-8") as fh:
            fh.write(digest + "\n")
        print(f"CONTRACT_SHA256 = {digest} ({len(rels)} files)")
        return 0
    if not os.path.exists(pin):
        print("CONTRACT_SHA256 missing; run with --write", file=sys.stderr)
        return 2
    want = open(pin, encoding="utf-8").read().strip()
    if want != digest:
        print(f"contract hash MISMATCH: pinned {want[:12]}..., computed {digest[:12]}... over {len(rels)} files",
              file=sys.stderr)
        return 1
    print(f"contract hash OK: {digest[:12]}... ({len(rels)} files)")
    return 0

if __name__ == "__main__":
    sys.exit(main())
