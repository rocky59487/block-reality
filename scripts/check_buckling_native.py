#!/usr/bin/env python3
"""Compare real old/new native none-mode replies (C5/C6/C8, f64/f32, DET3)."""
import argparse
from contextlib import closing
import hashlib
import json
from pathlib import Path
import struct
from check_native_jar import load_corpus


def run(args):
    out = Path(args.out).resolve()
    out.mkdir(parents=True, exist_ok=False)
    corpus = load_corpus()
    cases = dict(corpus.load_cases())
    results = {}
    for name, data in cases.items():
        if not name.startswith(("C5-", "C6-", "C8-")):
            continue
        case = corpus.Case(name, data, cases)
        world = next(iter(case.worlds.values()))
        mats, secs = case.vocab_ids()
        for storage in ["f64", "f32"]:
            key = name + "-" + storage
            all_runs = []
            for arm, lib, contract in [("before", args.before, args.before_contract),
                                       ("after", args.after, corpus.contract_sha())]:
                for trial in range(3):
                    directory = out / key / f"{arm}-{trial}"
                    directory.mkdir(parents=True)
                    solve = dict(world.solve, numThreads=1, buckling={"mode": "none"},
                                 include=["members", "stations", "shells", "memberGeometry", "stationIdentity"],
                                 precision={"tier": "commit", "storage": storage})
                    loads = corpus.encode_loads(world.loads)
                    if loads:
                        solve["loads"] = len(loads) // 64
                    requests = [("bsi.hello", {"bsi": 1, "client": "aggregation-regression/1", "contractSha256": contract}, b""),
                                ("bsi.vocab.declare", case.vocab, b""),
                                ("bsi.world.declare", {"blocks": len(world.blocks)}, corpus.encode_blocks(world.blocks, mats, secs)),
                                ("bsi.solve", solve, loads)]
                    replies = []
                    with closing(corpus.CapiClient(lib)) as client:
                        for index, (method, body, payload) in enumerate(requests):
                            reply = client.call(corpus.header(method, body, str(index)), payload)
                            assert not reply.error, (key, arm, method, reply.h)
                            header_len, payload_len = struct.unpack_from("<II", client.buf.raw, 4)
                            raw = client.buf.raw[:12 + header_len + payload_len]
                            (directory / f"{index}.frame").write_bytes(raw)
                            if index:
                                replies.append(raw)
                            else:
                                assert "bsi.buckling.eigen" not in reply.h.get("capabilities", []), reply.h
                    all_runs.append(replies)
            assert all(r == all_runs[0] for r in all_runs), (key, "old/new or DET3 mismatch")
            results[key] = [hashlib.sha256(r).hexdigest() for r in all_runs[0]]
            print(key + ": 3 replies identical old/new, DET3", flush=True)
    assert len(results) == 6, "corpus drift"
    receipt = {"replies": results, "beforeLibrary": hashlib.sha256(Path(args.before).read_bytes()).hexdigest(),
               "afterLibrary": hashlib.sha256(Path(args.after).read_bytes()).hexdigest(),
               "beforeContract": args.before_contract, "afterContract": corpus.contract_sha()}
    (out / "results.json").write_text(json.dumps(receipt, indent=2) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before", required=True)
    parser.add_argument("--after", required=True)
    parser.add_argument("--before-contract", required=True)
    parser.add_argument("--out", required=True)
    run(parser.parse_args())
