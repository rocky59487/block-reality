"""Strict RS comparison: complete raw populations, byte identity, precommitted budgets."""
import argparse
import csv
import json
import math
from pathlib import Path

FIXTURES = ("D4096", "D32768", "D131072", "F131072")
PATTERNS = ("ONE", "BURST64")
STAGES = ("edit", "capture", "reconcile", "publish", "coverage_snapshot", "coverage_encode", "objects_encode", "objects_decode")


def read(root):
    samples, all_iterations = {}, {}
    for fork in range(1, 4):
        receipt = json.loads((root / f"fork-{fork}-exit.json").read_text(encoding="utf-8"))
        assert receipt["exit_code"] == 0, f"incomplete fork {fork} at {root}"
        rows = [json.loads(line) for line in (root / f"fork-{fork}.jsonl").read_text(encoding="utf-8").splitlines()]
        assert rows[0]["kind"] == "runtime" and rows[-1] == {"kind": "complete", "fork": fork}
        assert rows[0]["heap_max"] == 2 * 1024**3 and rows[0]["allocation_supported"]
        for fixture in FIXTURES:
            for pattern in PATTERNS:
                group = [r for r in rows if r.get("fixture") == fixture and r.get("pattern") == pattern]
                assert [r["kind"] for r in group] == ["start", "initial"] + ["warmup"]*5 + ["sample"]*20
                for i, row in enumerate(group[2:]):
                    assert row["iteration"] == i and row["fork"] == fork
                    assert tuple(s["name"] for s in row["stages"]) == STAGES
                    assert all(isinstance(s["ns"], int) and s["ns"] >= 0 and isinstance(s["allocated"], int) and s["allocated"] >= 0 for s in row["stages"])
                    key = (fork, fixture, pattern, i)
                    assert key not in all_iterations
                    all_iterations[key] = row
                    if row["kind"] == "sample":
                        samples[key] = row
    assert len(samples) == 480 and len(all_iterations) == 600
    return samples, all_iterations


def p95(values):
    return sorted(values)[math.ceil(len(values)*.95)-1]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--baseline", type=Path, required=True)
    p.add_argument("--candidate", type=Path, required=True)
    p.add_argument("--out", type=Path, required=True)
    a = p.parse_args()
    a.out.mkdir(parents=True, exist_ok=False)
    baseline, all_baseline = read(a.baseline)
    candidate, all_candidate = read(a.candidate)
    assert all_baseline.keys() == all_candidate.keys()
    mismatches = [key for key in all_baseline if any(all_baseline[key][field] != all_candidate[key][field]
                  for field in ("digest", "cells", "records", "bytes", "coverage_cold"))]
    rows, failures = [], []
    for fixture in FIXTURES:
        for pattern in PATTERNS:
            groups = [[row for (_,f,p,_),row in source.items() if f == fixture and p == pattern]
                      for source in (baseline,candidate)]
            for stage in STAGES:
                metrics = [[next(s for s in r["stages"] if s["name"] == stage) for r in group] for group in groups]
                times = [[m["ns"] for m in group] for group in metrics]
                allocated = [[m["allocated"] for m in group] for group in metrics]
                old, new = (p95(v) for v in times)
                budget = old*.25 if fixture == "D131072" and stage in ("capture","reconcile") else max(old*1.25,old+1_000_000)
                passed = new <= budget
                row = {"fixture":fixture,"pattern":pattern,"stage":stage,"samples_each":60,
                       "baseline_p50_ms":sorted(times[0])[29]/1e6,"baseline_p95_ms":old/1e6,"baseline_max_ms":max(times[0])/1e6,
                       "candidate_p50_ms":sorted(times[1])[29]/1e6,"candidate_p95_ms":new/1e6,"candidate_max_ms":max(times[1])/1e6,
                       "budget_ms":budget/1e6,"timing_pass":passed,"baseline_allocation_p95":p95(allocated[0]),
                       "candidate_allocation_p95":p95(allocated[1])}
                rows.append(row)
                if not passed:
                    failures.append({"fixture":fixture,"pattern":pattern,"stage":stage,"kind":"timing", "baseline_ns":old,"candidate_ns":new,"budget_ns":budget})
                if stage == "capture" and p95(allocated[1]) > 1.6*p95(allocated[0]):
                    failures.append({"fixture":fixture,"pattern":pattern,"kind":"capture_allocation"})
            totals = [[sum(s["allocated"] for s in row["stages"]) for row in group] for group in groups]
            old_total, new_total = p95(totals[0]),p95(totals[1])
            if new_total > old_total*1.1:
                failures.append({"fixture":fixture,"pattern":pattern,"kind":"total_allocation", "baseline":old_total,"candidate":new_total})
            rows.append({"fixture":fixture,"pattern":pattern,"stage":"TOTAL_ALLOCATED","samples_each":60,
                         "baseline_allocation_p95":old_total,"candidate_allocation_p95":new_total})
    with (a.out/"comparison.csv").open("w",newline="",encoding="utf-8") as f:
        writer = csv.DictWriter(f,fieldnames=list(rows[0])); writer.writeheader(); writer.writerows(rows)
    result = {"measured_pairs":len(baseline),"warmup_and_measured_digest_pairs":len(all_baseline),
              "digest_mismatches":mismatches,"budget_failures":failures,"pass":not mismatches and not failures}
    (a.out/"result.json").write_text(json.dumps(result,indent=2)+"\n",encoding="utf-8")
    print(json.dumps(result,indent=2))
    if not result["pass"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
