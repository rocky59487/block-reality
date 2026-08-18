"""Lightweight Minecraft RCON protocol client."""

import socket
import struct
import time
import sys
import argparse

class MinecraftRCON:
    def __init__(self, host="127.0.0.1", port=25575, password="secret123"):
        self.host = host
        self.port = port
        self.password = password
        self.sock = None
        self.req_id = 0

    def connect(self, timeout=10):
        start = time.time()
        while time.time() - start < timeout:
            try:
                self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                self.sock.settimeout(5)
                self.sock.connect((self.host, self.port))
                # Login packet (type 3)
                self._send(3, self.password)
                req_id, ptype, body = self._read()
                if req_id == -1:
                    raise ConnectionError("RCON Authentication failed")
                return True
            except (ConnectionRefusedError, socket.timeout, OSError):
                time.sleep(1)
        return False

    def _send(self, ptype, body):
        self.req_id += 1
        data = struct.pack("<iii", 10 + len(body), self.req_id, ptype) + body.encode("utf-8") + b"\x00\x00"
        self.sock.sendall(data)

    def _read(self):
        head = self.sock.recv(12)
        if len(head) < 12:
            return -1, 0, ""
        length, req_id, ptype = struct.unpack("<iii", head)
        body = self.sock.recv(length - 8)
        return req_id, ptype, body[:-2].decode("utf-8", errors="replace")

    def command(self, cmd):
        if cmd.startswith("/"):
            cmd = cmd[1:]
        self._send(2, cmd)  # type 2 = EXECCOMMAND
        req_id, ptype, body = self._read()
        return body

    def close(self):
        if self.sock:
            try:
                self.sock.close()
            except Exception:
                pass
            finally:
                self.sock = None

    def __enter__(self):
        if not self.connect():
            raise ConnectionError(f"Could not connect to RCON server at {self.host}:{self.port}")
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.close()

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Send Minecraft RCON command")
    parser.add_argument("command", help="Command to execute on the Minecraft server")
    parser.add_argument("--host", default="127.0.0.1", help="RCON host (default: 127.0.0.1)")
    parser.add_argument("--port", type=int, default=25575, help="RCON port (default: 25575)")
    parser.add_argument("--password", default="secret123", help="RCON password")
    args = parser.parse_args()

    rcon = MinecraftRCON(host=args.host, port=args.port, password=args.password)
    if rcon.connect():
        try:
            resp = rcon.command(args.command)
            if resp:
                print(resp)
        finally:
            rcon.close()
    else:
        sys.stderr.write(f"Failed to connect to RCON at {args.host}:{args.port}\n")
        sys.exit(1)
