#!/usr/bin/env python3
"""Minimal RCON client for the N25 server leg.

    python scripts/n25_rcon.py "br status" "br members"

Not committed as tooling -- it exists so the smoke run's commands and their
replies are captured verbatim rather than retyped from a screenshot. The
dedicated server's console does not echo command feedback the way a player's
chat does; RCON returns the sendSuccess text as the response body, which is
exactly what N25-c and N25-d need to read.
"""
import socket
import struct
import sys
import time

HOST, PORT, PASSWORD = "127.0.0.1", 25575, "n25smoke"
LOGIN, CMD, RESP = 3, 2, 0


def pack(req_id, kind, body):
    payload = struct.pack("<ii", req_id, kind) + body.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(payload)) + payload


def read_exact(s, n):
    buf = b""
    while len(buf) < n:
        chunk = s.recv(n - len(buf))
        if not chunk:
            raise EOFError("server closed the connection")
        buf += chunk
    return buf


def read_packet(s):
    (size,) = struct.unpack("<i", read_exact(s, 4))
    payload = read_exact(s, size)
    req_id, kind = struct.unpack("<ii", payload[:8])
    return req_id, kind, payload[8:-2].decode("utf-8", "replace")


def main():
    s = socket.create_connection((HOST, PORT), timeout=30)
    s.settimeout(30)
    s.sendall(pack(1, LOGIN, PASSWORD))
    rid, _, _ = read_packet(s)
    if rid == -1:
        sys.exit("rcon: authentication failed")

    for i, cmd in enumerate(sys.argv[1:], start=2):
        if cmd.startswith("sleep "):
            time.sleep(float(cmd.split()[1]))
            print("--- slept %s s" % cmd.split()[1])
            continue
        s.sendall(pack(i, CMD, cmd))
        _, _, body = read_packet(s)
        print("=== /%s" % cmd)
        print(body if body.strip() else "(empty reply)")
        print()
    s.close()


main()
