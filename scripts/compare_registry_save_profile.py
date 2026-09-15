"""SC: compare complete save profiles against the frozen SP baseline without dropping samples."""
import argparse
import csv
import json
import math
from pathlib import Path

FIXTURES = ("D4096", "D32768", "D131072")
PATTERNS = ("READY", "PENDING64")
STAGES = ("tag_encode", "file_save", "reopen")


def read(root):
    samples, iterations = {}, {}
    seeds = json.loads((root / "seed-hashes.json").read_text(encoding="utf-8"))
    assert set(seeds) == {f"{f}/blockreality_world_index.dat" for f in FIXTURES}
    for fork in range(1, 4):
        assert json.loads((root / f"fork-{fork}-exit.json").read_text())["exit_code"] == 0
        rows = [json.loads(line) for line in (root / f"fork-{fork}.jsonl").read_text().splitlines()]
        assert len(rows) == 158
        assert rows[0]["kind"] == "runtime" and rows[0]["fork"] == fork
        assert rows[0]["heap_max"] == 2 * 1024**3 and rows[0]["allocation_supported"]
        assert rows[-1] == {"kind": "complete", "fork": fork}
        for fixture in FIXTURES:
            for pattern in PATTERNS:
                group = [r for r in rows if r.get("fixture") == fixture and r.get("pattern") == pattern]
                assert [r["kind"] for r in group] == ["start"] + ["warmup"] * 5 + ["sample"] * 20
                assert group[0]["seed_sha256"] == seeds[f"{fixture}/blockreality_world_index.dat"]
                for i, row in enumerate(group[1:]):
                    assert row["iteration"] == i and row["fork"] == fork
                    assert [s["name"] for s in row["stages"]] == list(STAGES)
                    assert all(type(s[k]) is int and s[k] >= 0 for s in row["stages"] for k in ("ns", "allocated"))
                    assert type(row["compressed_bytes"]) is int and row["compressed_bytes"] > 0
                    key = (fork, fixture, pattern, i)
                    assert key not in iterations
                    iterations[key] = row
                    if row["kind"] == "sample":
                        samples[key] = row
    assert len(iterations) == 450 and len(samples) == 360
    sources = json.loads((root / "identity.json").read_text(encoding="utf-8"))["sources_lf_sha256"]
    return seeds, sources, samples, iterations


def p95(values):
    return sorted(values)[math.ceil(len(values) * .95) - 1]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for side in ("baseline", "candidate"):
        for platform in ("windows", "linux"):
            p.add_argument(f"--{side}-{platform}", type=Path, required=True)
    p.add_argument("--out", type=Path, required=True)
    a = p.parse_args()
    a.out.mkdir(parents=True, exist_ok=False)
    seeds, source_maps, canonical, failures, rows = None, {}, {}, [], []
    pairs = measured_pairs = 0
    for platform in ("windows", "linux"):
        data = []
        for side in ("baseline", "candidate"):
            actual_seeds, sources, samples, iterations = read(getattr(a, f"{side}_{platform}"))
            if seeds is None:
                seeds = actual_seeds
            assert seeds == actual_seeds
            assert source_maps.setdefault(side, sources) == sources, "cross-platform source drift"
            for (_, fixture, pattern, i), row in iterations.items():
                key = (fixture, pattern, i)
                value = (row["payload_sha256"], row["cells"], row["epoch"])
                if canonical.setdefault(key, value) != value:
                    failures.append({"kind": "payload", "platform": platform, "side": side, "case": key})
            data.append((samples, iterations))
        assert data[0][1].keys() == data[1][1].keys()
        for key, before in data[0][1].items():
            after = data[1][1][key]
            pairs += 1
            if after["compressed_bytes"] > 1.5 * before["compressed_bytes"]:
                failures.append({"kind": "compressed_size", "platform": platform, "case": key,
                                 "baseline": before["compressed_bytes"], "candidate": after["compressed_bytes"]})
        measured_pairs += len(data[0][0])
        for fixture in FIXTURES:
            for pattern in PATTERNS:
                groups = [[r for (_, f, p, _), r in d[0].items() if f == fixture and p == pattern] for d in data]
                assert all(len(g) == 60 for g in groups)
                for stage in STAGES:
                    metrics = [[next(s for s in r["stages"] if s["name"] == stage) for r in g] for g in groups]
                    times = [[m["ns"] for m in g] for g in metrics]
                    allocation = [p95([m["allocated"] for m in g]) for g in metrics]
                    old, new = [p95(g) for g in times]
                    budget = .5 * old if fixture == "D131072" and stage == "file_save" else max(1.25 * old, old + 1_000_000)
                    if new > budget:
                        failures.append({"kind": "timing", "platform": platform, "fixture": fixture,
                                         "pattern": pattern, "stage": stage, "baseline_ns": old, "candidate_ns": new, "budget_ns": budget})
                    if allocation[1] > allocation[0] + 65536:
                        failures.append({"kind": "allocation", "platform": platform, "fixture": fixture,
                                         "pattern": pattern, "stage": stage, "baseline": allocation[0], "candidate": allocation[1]})
                    rows.append({"platform": platform, "fixture": fixture, "pattern": pattern, "stage": stage,
                                 "samples_each": 60, "baseline_p95_ms": old / 1e6, "candidate_p50_ms": sorted(times[1])[29] / 1e6,
                                 "candidate_p95_ms": new / 1e6, "candidate_max_ms": max(times[1]) / 1e6,
                                 "budget_ms": budget / 1e6, "baseline_allocation_p95": allocation[0], "candidate_allocation_p95": allocation[1],
                                 "baseline_compressed_min": min(r["compressed_bytes"] for r in groups[0]),
                                 "baseline_compressed_max": max(r["compressed_bytes"] for r in groups[0]),
                                 "candidate_compressed_min": min(r["compressed_bytes"] for r in groups[1]),
                                 "candidate_compressed_max": max(r["compressed_bytes"] for r in groups[1])})
    assert source_maps["baseline"].keys() == source_maps["candidate"].keys()
    changed = [f for f, sha in source_maps["baseline"].items() if sha != source_maps["candidate"][f]]
    assert changed == ["forge/src/main/java/com/blockreality/impl/server/WorldIndexData.java"], changed
    assert pairs == 900 and measured_pairs == 720 and len(canonical) == 150 and len(rows) == 36
    with (a.out / "comparison.csv").open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0])); writer.writeheader(); writer.writerows(rows)
    result = {"iteration_pairs": pairs, "measured_pairs": measured_pairs, "canonical_payloads": len(canonical),
              "changed_source_files": changed, "failures": failures, "pass": not failures}
    (a.out / "result.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))
    if failures:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
