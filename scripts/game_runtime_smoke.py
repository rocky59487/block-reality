#!/usr/bin/env python3
"""Exercise only the isolated runtime-smoke development server through local RCON."""
import argparse
import json
from pathlib import Path
import re
import time
from rcon_client import MinecraftRCON


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--stop-only", action="store_true")
    args = parser.parse_args()
    props = dict(line.split("=", 1) for line in Path(args.config).read_text().splitlines()
                 if line and not line.startswith("#") and "=" in line)
    if props.get("level-name") != "runtime-smoke" or props.get("server-ip") != "127.0.0.1":
        raise RuntimeError("this gate only operates the isolated loopback runtime-smoke world")
    client = MinecraftRCON("127.0.0.1", int(props["rcon.port"]), props["rcon.password"])
    receipts = []
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)

    def command(text):
        response = re.sub("§.", "", client.command(text))
        receipts.append({"command": text, "response": response})
        out.write_text(json.dumps(receipts, indent=2, ensure_ascii=False) + "\n")
        return response

    def wait_for(name, predicate):
        until = time.monotonic() + 20
        while True:
            reply = command("br status")
            if predicate(reply):
                print(name + ": PASS", flush=True)
                return reply
            if time.monotonic() >= until:
                raise AssertionError(name + ": " + reply)
            time.sleep(.5)

    def revision(reply):
        return int(re.search(r"revision\s+(\d+)", reply).group(1))

    if not client.connect():
        raise RuntimeError("local smoke server is not accepting RCON")
    try:
        if args.stop_only:
            command("stop")
            return
        command("forceload add -16 -16 48 32")
        command("fill -2 198 -2 36 215 15 minecraft:air")
        command("execute positioned 0 200 0 run br scan 2")
        command("br status")
        command("br members")
        command("br section 1")
        command("setblock -1 200 0 minecraft:stone")
        command("fill 0 200 0 4 200 0 blockreality:steel_beam[axis=x]")
        command("execute positioned 0 200 0 run br scan 2")
        supported = wait_for("native cantilever", lambda s: "engine          READY" in s and "1 members, 0 plate facets" in s)
        command("br members")
        # Changing only ground must trigger new analysis, without scan or resolve.
        command("setblock -1 200 0 minecraft:air")
        # The delivered engine returns SOLVE_FAILED for an entirely ungrounded world.
        # This proves invalidation/refusal only, not a typed MECHANISM result (see gate log).
        removed = wait_for("ground removal revokes old success and reports native refusal", lambda s:
                           revision(s) > revision(supported) and "last result     FAILED" in s
                           and "SOLVE_FAILED:" in s and "members," not in s)
        command("setblock -1 200 0 minecraft:stone")
        wait_for("ground restoration restores support", lambda s:
                 revision(s) > revision(removed) and "1 members, 0 plate facets" in s)
        command("setblock 10 199 0 minecraft:stone")
        command("fill 10 200 0 10 204 0 blockreality:steel_beam[axis=y]")
        command("fill -1 210 10 -1 210 12 minecraft:stone")
        command("fill 0 210 10 3 210 12 blockreality:concrete_slab[axis=y]")
        command("fill 20 200 0 24 200 0 blockreality:steel_beam[axis=x]")
        command("execute positioned 0 200 0 run br scan 2")
        wait_for("column beam slab and mechanism", lambda s: re.search(r"2 members, [1-9][0-9]* plate facets", s) and "1 unrestrained" in s)
        command("setblock 30 200 0 blockreality:steel_beam")
        command("execute positioned 0 200 0 run br scan 2")
        wait_for("legacy undeclared axis is explicit", lambda s: "FAILED" in s and "undeclared placement axis" in s)
        command("setblock 30 200 0 minecraft:air")
        command("execute positioned 0 200 0 run br scan 2")
        wait_for("declared model recovers", lambda s: "2 members," in s and "FAILED" not in s)
        command("br reset")
        wait_for("native session reset", lambda s: "engine          READY" in s and "2 members," in s)
    finally:
        client.close()


if __name__ == "__main__":
    main()
