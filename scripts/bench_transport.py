#!/usr/bin/env python3
"""Round-trip latency: JSON lines vs the shared-memory transport, same solves.

Measures from "request handed to the sidecar" to "reply line received", which on
the JSON wire includes parsing the request, serialising the full reply and pushing
it through a pipe, and on the shm wire includes reading and writing the mapped
region plus a ~60-byte doorbell. The solve in the middle is identical, so the
difference is the transport. Client-side decode is deliberately excluded: this
script is Python, the mod is Java, and neither's parser speaks for the other.

    python3 scripts/bench_transport.py <path-to-br-sidecar> [rounds [nx ny]]

`measure()` is importable: scripts/evidence.py calls it so the transport numbers
quoted in the README come from the committed verification record, not from a
one-off run nobody can find again.
"""
import json
import mmap
import os
import shutil
import statistics
import struct
import subprocess
import sys
import tempfile
import time

BLOCK = dict(mat="steel", section="steel_rect_200x400")


def grid_frame(nx, ny):
    """A one-bay-deep frame: nx columns of ny blocks, beams across every floor.
    Junctions split the runs, so this meshes into a few hundred members."""
    blocks = {}
    for i in range(nx):
        for j in range(ny):
            blocks[(i * 4, 64 + j, 0)] = dict(x=i * 4, y=64 + j, z=0, support=j == 0, **BLOCK)
    for j in range(4, ny, 5):
        for x in range(0, (nx - 1) * 4 + 1):
            key = (x, 64 + j, 0)
            if key not in blocks:
                blocks[key] = dict(x=x, y=64 + j, z=0, support=False, **BLOCK)
    loads = [dict(x=(nx - 1) * 4, y=64 + ny - 1, z=0, fy=-20000.0)]
    return list(blocks.values()), loads


def measure(exe, rounds=20, nx=10, ny=21):
    """Run the comparison and return the numbers as a dict (no printing)."""
    if rounds < 1:
        raise ValueError("rounds must be >= 1")

    p = subprocess.Popen([exe], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                         text=True, bufsize=1)

    def call(req):
        p.stdin.write(json.dumps(req) + "\n")
        p.stdin.flush()
        return json.loads(p.stdout.readline())

    hello = call({"op": "hello"})
    mats = hello["materials"]
    toks = hello["sections"] + [x["id"] for x in hello["plates"]]

    blocks, loads = grid_frame(nx, ny)
    r = call({"op": "solve", "revision": 1, "blocks": blocks, "loads": loads})
    assert r["ok"] and not r["singular"], r.get("error", r.get("diagnostic"))
    model = {"blocks": len(blocks), "members": len(r["members"]), "dof": r["dof"]}

    # --- JSON ------------------------------------------------------------------
    json_ms = []
    reply_bytes = 0
    for k in range(rounds):
        line = json.dumps({"op": "solve", "revision": 100 + k,
                           "blocks": blocks, "loads": loads})
        t0 = time.perf_counter()
        p.stdin.write(line + "\n")
        p.stdin.flush()
        reply = p.stdout.readline()
        json_ms.append((time.perf_counter() - t0) * 1e3)
        reply_bytes = len(reply)

    # --- shm -------------------------------------------------------------------
    tmpdir = tempfile.mkdtemp(prefix="br-bench")
    path = os.path.join(tmpdir, "region.bin")
    with open(path, "wb") as f:
        f.write(b"\0" * (8 << 20))
    fh = open(path, "r+b")
    mm = mmap.mmap(fh.fileno(), 0)
    assert call({"op": "shm.open", "path": path})["ok"]

    def encode(rev):
        out = struct.pack("<IIII", 0x31515242, rev, 0, 1)
        out += struct.pack("<I", len(blocks))
        for b in blocks:
            out += struct.pack("<iiiiiI", b["x"], b["y"], b["z"],
                               mats.index(b["mat"]), toks.index(b["section"]),
                               1 if b["support"] else 0)
        out += struct.pack("<I", len(loads))
        for l in loads:
            out += struct.pack("<iii", l["x"], l["y"], l["z"])
            out += struct.pack("<6d", 0, l["fy"], 0, 0, 0, 0)
        return out

    shm_ms = []
    shm_bytes = 0
    for k in range(rounds):
        payload = encode(200 + k)
        bell = json.dumps({"op": "solve.shm", "revision": 200 + k, "bytes": len(payload)})
        t0 = time.perf_counter()
        mm.seek(0)
        mm.write(payload)
        p.stdin.write(bell + "\n")
        p.stdin.flush()
        ack = json.loads(p.stdout.readline())
        shm_ms.append((time.perf_counter() - t0) * 1e3)
        assert ack["ok"], ack
        shm_bytes = ack["bytes"]

    p.stdin.write('{"op":"bye"}\n')   # bye has no reply; EOF ends the process
    p.stdin.flush()
    p.wait(timeout=5)
    mm.close()
    fh.close()
    shutil.rmtree(tmpdir, ignore_errors=True)   # 8 MB per run adds up (SCRIPT-9)

    med_j, med_s = statistics.median(json_ms), statistics.median(shm_ms)
    return {
        **model,
        "rounds": rounds,
        "json_median_ms": round(med_j, 2), "json_min_ms": round(min(json_ms), 2),
        "json_reply_bytes": reply_bytes,
        "shm_median_ms": round(med_s, 2), "shm_min_ms": round(min(shm_ms), 2),
        "shm_reply_bytes": shm_bytes,
        "saving_ms": round(med_j - med_s, 2),
        "saving_pct": round((1 - med_s / med_j) * 100.0, 1),
    }


def main():
    exe = sys.argv[1]
    rounds = int(sys.argv[2]) if len(sys.argv) > 2 else 20
    nx = int(sys.argv[3]) if len(sys.argv) > 3 else 10
    ny = int(sys.argv[4]) if len(sys.argv) > 4 else 21
    if rounds < 1:
        sys.exit("rounds must be >= 1")

    m = measure(exe, rounds, nx, ny)
    print(f"model: {m['blocks']} blocks -> {m['members']} members, "
          f"{m['dof']} DOF")
    print(f"JSON : median {m['json_median_ms']:7.2f} ms  (min {m['json_min_ms']:.2f})   "
          f"reply {m['json_reply_bytes']} bytes over the pipe")
    print(f"shm  : median {m['shm_median_ms']:7.2f} ms  (min {m['shm_min_ms']:.2f})   "
          f"reply {m['shm_reply_bytes']} bytes in the region, doorbell ~60 bytes over the pipe")
    print(f"transport saving: {m['saving_ms']:.2f} ms per solve ({m['saving_pct']:.0f}%)")


if __name__ == "__main__":
    main()
