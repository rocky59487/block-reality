#!/usr/bin/env python3
"""BSI v1 conformance runner (skeleton, B8 delivers the adapters).

Today this runner does the parts that need no engine:
  --selfcheck : C-0 (every assertion path names a contract field), case JSON validity,
                contract hash (via check_contract.py)
  --list      : cases with family, grade and required capabilities
Adapters (--adapter engine|sidecar|frame_v2) are declared but raise NotImplemented
until B8 lands; a missing adapter is reported, never silently green.
"""
import glob, json, os, re, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
CONTRACT = os.path.dirname(HERE)

def load_schema():
    return json.load(open(os.path.join(CONTRACT, "bsi.schema.json"), encoding="utf-8"))

def load_cases():
    out = []
    for f in sorted(glob.glob(os.path.join(HERE, "cases", "*.json"))):
        out.append((os.path.basename(f), json.load(open(f, encoding="utf-8"))))
    return out

# Contract vocabulary a path may address (C-0). Sections come from x-records,
# header fields from solve.response/diag/error definitions.
def contract_paths(schema):
    roots = set(schema["x-records"].keys())
    roots |= {"status", "diag", "buckling", "unassigned", "sections", "payload", "error"}
    fields = set()
    for rec, spec in schema["x-records"].items():
        for fld in spec["fields"]:
            fields.add(f"{rec}.{fld[0]}")
    for k in schema["$defs"]["diag"]["properties"]:
        fields.add(f"diag.{k}")
    fields |= {"buckling.kind", "buckling.state", "unassigned.why", "unassigned.island", "unassigned.blocks"}
    return roots, fields

PATH_RE = re.compile(r"^([a-zA-Z]+)(\[[^\]]*\])?(?:\.([a-zA-Z]+)(\[[^\]]*\])?)?")

def check_path(path, roots, fields):
    m = PATH_RE.match(path)
    if not m:
        return f"unparseable path {path!r}"
    root, _idx, field, _idx2 = m.groups()
    if root not in roots:
        return f"unknown root {root!r} in {path!r}"
    if field and f"{root}.{field}" not in fields and root not in ("unassigned",):
        return f"unknown field {root}.{field} in {path!r}"
    return None

def selfcheck():
    rc = subprocess.call([sys.executable, os.path.join(CONTRACT, "check_contract.py")])
    if rc != 0:
        return rc
    schema = load_schema()
    roots, fields = contract_paths(schema)
    problems = 0
    for name, case in load_cases():
        asserts = list(case.get("assert", []))
        for v in case.get("variants", []):
            asserts += v.get("assert", [])
        for s in case.get("steps", []):
            asserts += s.get("assert", [])
        for a in asserts:
            if "path" not in a:
                continue
            why = check_path(a["path"], roots, fields)
            if why:
                problems += 1
                print(f"[C-0] {name}: {why}")
        for cap in case.get("requires", []):
            if cap not in schema["x-capabilities"]:
                problems += 1
                print(f"[C-0] {name}: unknown capability {cap!r}")
    print(f"selfcheck: {len(load_cases())} cases, {problems} problem(s)")
    return 1 if problems else 0

def list_cases():
    for name, case in load_cases():
        print(f"{name:40} {case.get('family','?'):5} {case.get('grade','?'):12} requires={','.join(case.get('requires', []))}")
    return 0

def main(argv):
    if "--selfcheck" in argv:
        return selfcheck()
    if "--list" in argv:
        return list_cases()
    if "--adapter" in argv:
        print("adapters (engine|sidecar|frame_v2) are delivered by block-reality B8 / tectonic2 MC68; "
              "not implemented in this contract revision -- reported, not skipped", file=sys.stderr)
        return 3
    print(__doc__)
    return 0

if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
