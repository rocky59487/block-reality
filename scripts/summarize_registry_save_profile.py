"""Require every SP fixture/fork/platform and matching canonical payloads; timings stay Recorded."""
import argparse
import csv
import json
import math
from pathlib import Path


def percentile(values, rank):
    return sorted(values)[math.ceil(len(values)*rank)-1]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--windows", type=Path, required=True)
    p.add_argument("--linux", type=Path, required=True)
    p.add_argument("--out", type=Path, required=True)
    a = p.parse_args()
    a.out.mkdir(parents=True, exist_ok=False)
    hashes, summaries, iterations, samples = {}, [], 0, 0
    seed_hashes = json.loads((a.windows/"seed-hashes.json").read_text(encoding="utf-8"))
    assert seed_hashes == json.loads((a.linux/"seed-hashes.json").read_text(encoding="utf-8"))
    for name, root in (("windows",a.windows),("linux",a.linux)):
        measured = {}
        for fork in range(1,4):
            assert json.loads((root/f"fork-{fork}-exit.json").read_text())["exit_code"] == 0
            rows = [json.loads(line) for line in (root/f"fork-{fork}.jsonl").read_text().splitlines()]
            assert rows[0]["kind"]=="runtime" and rows[0]["heap_max"]==2*1024**3 and rows[0]["allocation_supported"]
            assert rows[-1]=={"kind":"complete","fork":fork}
            for fixture in ("D4096","D32768","D131072"):
                for pattern in ("READY","PENDING64"):
                    group=[r for r in rows if r.get("fixture")==fixture and r.get("pattern")==pattern]
                    assert [r["kind"] for r in group]==["start"]+["warmup"]*5+["sample"]*20
                    assert group[0]["seed_sha256"]==seed_hashes[f"{fixture}/blockreality_world_index.dat"]
                    for i,row in enumerate(group[1:]):
                        assert row["iteration"]==i and row["fork"]==fork
                        assert [s["name"] for s in row["stages"]]==["tag_encode","file_save","reopen"]
                        assert all(isinstance(s["ns"],int) and s["ns"]>=0 and isinstance(s["allocated"],int) and s["allocated"]>=0 for s in row["stages"])
                        key=(fixture,pattern,i)
                        value=(row["payload_sha256"],row["cells"],row["epoch"])
                        assert hashes.setdefault(key,value)==value, f"payload mismatch {name} fork {fork} {key}"
                        iterations+=1
                        if row["kind"]=="sample":
                            measured.setdefault((fixture,pattern),[]).append(row);samples+=1
        for (fixture,pattern),group in measured.items():
            assert len(group)==60
            for stage in ("tag_encode","file_save","reopen"):
                metrics=[next(s for s in row["stages"] if s["name"]==stage) for row in group]
                durations=[s["ns"] for s in metrics];allocated=[s["allocated"] for s in metrics]
                summaries.append({"platform":name,"fixture":fixture,"pattern":pattern,"stage":stage,"samples":60,
                                  "p50_ms":percentile(durations,.5)/1e6,"p95_ms":percentile(durations,.95)/1e6,"max_ms":max(durations)/1e6,
                                  "allocation_p95":percentile(allocated,.95),"compressed_min":min(r["compressed_bytes"] for r in group),
                                  "compressed_max":max(r["compressed_bytes"] for r in group)})
    assert iterations==900 and samples==720 and len(hashes)==150 and len(summaries)==36
    with (a.out/"baseline.csv").open("w",newline="",encoding="utf-8") as f:
        writer=csv.DictWriter(f,fieldnames=list(summaries[0]));writer.writeheader();writer.writerows(summaries)
    result={"iterations":iterations,"measured_samples":samples,"canonical_payloads_matched_across_six_forks":len(hashes),
            "functional_pass":True,"timing_classification":"Recorded"}
    (a.out/"result.json").write_text(json.dumps(result,indent=2)+"\n",encoding="utf-8")
    print(json.dumps(result,indent=2))


if __name__=="__main__":
    main()
