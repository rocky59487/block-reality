#!/usr/bin/env python3
"""BSI v1 conformance runner (contract artifact; mirrored by hash in both repositories).

  --selfcheck                 C-0: every assertion path names a contract field; case JSON valid; contract hash
  --list                      cases with family, grade and required capabilities
  --adapter engine  --lib L   drive a bsi_engine.h library through bsi-hostd (all transports)
  --adapter capi    --lib L   drive a bsi_capi.h library in-process through ctypes (what Java does)
  --adapter sidecar --exe E   drive a host process that takes the bsi-hostd flags
  --adapter frame_v2 --lib L  NOT RUN (exit 3) until the engine's T-A verbs exist

  --hostd P            path to bsi-hostd (default: searched under build/)
  --transports a,b     subset of stdio-b64,arena,frame (default: all available)
  --repeat N           DET x N (default 3) on the primary transport
  --assume-caps a,b    harvest mode: act as if capabilities were declared. Results tagged ASSUMED, exit 4. Never green.
  --expected-red F     JSON account of red cases that are on the books (outside contract/). A listed case that is
                       GREEN is a failure (stale account).
  --families C-4,C-5   run only these families;  --case NAME  run one case
  --record F           write a JSON report
  --stub               allow the x-bsi.stub engine (mechanics families are still refused for it)
  --allow-all-skip     exit 0 even when every case was skipped

Exit codes: 0 all hard cases green (>=1 executed); 1 hard red; 2 contract/usage; 3 adapter not run; 4 harvest mode; 5 all skipped.
"""
import argparse, ast, base64, ctypes, glob, hashlib, json, math, mmap, os, re, struct, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
CONTRACT = os.path.dirname(HERE)
MECHANICS_FAMILIES = {"C-4", "C-5", "C-6", "C-7", "C-8", "C-10", "C-12", "C-13"}

# ----------------------------------------------------------------------------- contract
def load_schema():
    return json.load(open(os.path.join(CONTRACT, "bsi.schema.json"), encoding="utf-8"))

def contract_sha():
    return open(os.path.join(CONTRACT, "CONTRACT_SHA256"), encoding="utf-8").read().strip()

def load_cases():
    out = []
    for f in sorted(glob.glob(os.path.join(HERE, "cases", "*.json"))):
        out.append((os.path.basename(f), json.load(open(f, encoding="utf-8"))))
    return out

FIELD_FMT = {"i32": "i", "u32": "I", "u8": "B", "u16": "H", "f64": "d", "f32": "f", "i64": "q", "u64": "Q"}

def record_format(schema, section):
    """struct format + field names for an x-records entry ('stations:f32' -> f32 variant)."""
    base, _, variant = section.partition(":")
    rec = schema["x-records"][base]
    fmt = "<"
    names = []
    for fld in rec["fields"]:
        name, typ = fld[0], fld[1]
        m = re.match(r"^([a-z0-9]+)(?:\[(\d+)\])?$", typ)
        t, n = m.group(1), int(m.group(2) or 1)
        if t == base or t == "block":           # nested record (edit)
            continue
        if variant == "f32" and t == "f64":
            t = "f32"
        fmt += FIELD_FMT[t] * n
        names.append((name, n))
    return fmt, names

def decode_records(schema, section, blob):
    if section.startswith("facetSurfaces"):
        f = "f" if section.endswith(":f32") else "d"
        sz = struct.calcsize("<" + f * 32)
        out = []
        for k in range(len(blob) // sz):
            v = struct.unpack_from("<" + f * 32, blob, k * sz)
            out.append({"top": [dict(zip(("s1", "s2", "theta", "vm"), v[4*i:4*i+4])) for i in range(4)],
                        "bottom": [dict(zip(("s1", "s2", "theta", "vm"), v[16+4*i:16+4*i+4])) for i in range(4)]})
        return out
    fmt, names = record_format(schema, section)
    sz = struct.calcsize(fmt)
    out = []
    for k in range(len(blob) // sz):
        vals = struct.unpack_from(fmt, blob, k * sz)
        rec, i = {}, 0
        for name, n in names:
            rec[name] = vals[i] if n == 1 else list(vals[i:i+n])
            i += n
        out.append(rec)
    return out

# ----------------------------------------------------------------------------- tiny JSON-schema validator (same subset as the C++ host)
class Validator:
    def __init__(self, schema):
        self.s = schema
    def ref(self, r):
        cur = self.s
        for seg in r[2:].split("/"):
            cur = cur[seg]
        return cur
    def check(self, node, v, path, problems):
        if "$ref" in node:
            t = self.ref(node["$ref"])
            if isinstance(t, list):
                if v not in t: problems.append(f"{path}: not in enumeration")
                return
            return self.check(t, v, path, problems)
        if "oneOf" in node:
            n = 0
            for alt in node["oneOf"]:
                p = []; self.check(alt, v, path, p); n += (not p)
            if n != 1: problems.append(f"{path}: oneOf {n} match")
            return
        if "const" in node and v != node["const"]: problems.append(f"{path}: const"); return
        if "type" in node:
            t = node["type"]
            ok = {"object": lambda: isinstance(v, dict), "array": lambda: isinstance(v, list), "string": lambda: isinstance(v, str),
                  "boolean": lambda: isinstance(v, bool), "integer": lambda: isinstance(v, int) and not isinstance(v, bool),
                  "number": lambda: isinstance(v, (int, float)) and not isinstance(v, bool), "null": lambda: v is None}[t]()
            if not ok: problems.append(f"{path}: expected {t}"); return
        if "enum" in node and v not in node["enum"]: problems.append(f"{path}: not in enum"); return
        if isinstance(v, (int, float)) and not isinstance(v, bool):
            if "minimum" in node and v < node["minimum"]: problems.append(f"{path}: below minimum")
            if "maximum" in node and v > node["maximum"]: problems.append(f"{path}: above maximum")
            if "exclusiveMinimum" in node and not v > node["exclusiveMinimum"]: problems.append(f"{path}: exclusiveMinimum")
        if isinstance(v, str):
            if "minLength" in node and len(v) < node["minLength"]: problems.append(f"{path}: too short")
            if "maxLength" in node and len(v) > node["maxLength"]: problems.append(f"{path}: too long")
            if "pattern" in node and not re.match(node["pattern"], v): problems.append(f"{path}: pattern")
        if isinstance(v, list):
            if "minItems" in node and len(v) < node["minItems"]: problems.append(f"{path}: too few")
            if "maxItems" in node and len(v) > node["maxItems"]: problems.append(f"{path}: too many")
            if node.get("uniqueItems") and len({json.dumps(x, sort_keys=True) for x in v}) != len(v): problems.append(f"{path}: duplicates")
            if "items" in node:
                for i, e in enumerate(v): self.check(node["items"], e, f"{path}[{i}]", problems)
        if isinstance(v, dict):
            props = node.get("properties", {})
            for r in node.get("required", []):
                if r not in v: problems.append(f"{path}.{r}: required")
            closed = node.get("additionalProperties") is False
            for k, e in v.items():
                if k in props: self.check(props[k], e, f"{path}.{k}", problems)
                elif k.startswith("x-"): continue
                elif closed: problems.append(f"{path}.{k}: unknown key")
    def validate(self, defname, v):
        p = []
        self.check(self.s["$defs"][defname], v, defname, p)
        return p

# ----------------------------------------------------------------------------- expressions
class Expr:
    """Whitelisted arithmetic: numbers, names, + - * / ^ (power), unary -, parentheses, pi."""
    OK = (ast.Expression, ast.BinOp, ast.UnaryOp, ast.Num, ast.Constant, ast.Name, ast.Load, ast.Add, ast.Sub, ast.Mult, ast.Div, ast.Pow, ast.USub, ast.UAdd)
    @staticmethod
    def eval(expr, env):
        if isinstance(expr, (int, float)):
            return float(expr)
        src = str(expr).replace("^", "**")
        tree = ast.parse(src, mode="eval")
        for node in ast.walk(tree):
            if not isinstance(node, Expr.OK):
                raise ValueError(f"disallowed token in expression {expr!r}: {type(node).__name__}")
            if isinstance(node, ast.Name) and node.id not in env and node.id != "pi":
                raise ValueError(f"unknown symbol {node.id!r} in {expr!r}")
        e = dict(env); e["pi"] = math.pi
        return float(eval(compile(tree, "<derive>", "eval"), {"__builtins__": {}}, e))

def build_env(derive):
    env = {}
    for k, v in (derive or {}).items():
        env[k] = Expr.eval(v, env)
    return env

# ----------------------------------------------------------------------------- cases -> worlds
class World:
    def __init__(self, blocks, loads, solve, name="default"):
        self.blocks = blocks   # list of dict(x,y,z,mat,sect,axis,joint,axisRot,fill,strength)
        self.loads = loads     # list of dict(x,y,z,fx,fy,fz)
        self.solve = solve
        self.name = name

def parse_block_tuple(t):
    if isinstance(t, dict):
        return dict(t)
    x, y, z, mat, sect, axis, joint, axisRot, fill, strength = t
    return {"x": x, "y": y, "z": z, "mat": mat, "sect": sect, "axis": axis, "joint": joint, "axisRot": axisRot, "fill": fill, "strength": strength}

def parse_block_dsl(s):
    """'column(x=0,z=0,y=0..19,steel,axis=1) + ground_rigid at (0,-1,0)'"""
    blocks = []
    for part in [p.strip() for p in s.split("+")]:
        m = re.match(r"^column\((.*)\)$", part)
        if m:
            kv = {}
            mat = None
            axis = 1
            for item in [x.strip() for x in m.group(1).split(",")]:
                if "=" in item:
                    k, v = item.split("=", 1)
                    if k == "axis": axis = int(v)
                    else: kv[k] = v
                else:
                    mat = item
            rng = {}
            for k, v in kv.items():
                if ".." in v:
                    a, b = v.split(".."); rng[k] = range(int(a), int(b) + 1)
                else:
                    rng[k] = [int(v)]
            for x in rng.get("x", [0]):
                for y in rng.get("y", [0]):
                    for z in rng.get("z", [0]):
                        blocks.append({"x": x, "y": y, "z": z, "mat": mat, "sect": None, "axis": axis, "joint": 0, "axisRot": 0, "fill": 1, "strength": 1})
            continue
        m = re.match(r"^(\S+) at \((-?\d+),(-?\d+),(-?\d+)\)$", part)
        if m:
            blocks.append({"x": int(m.group(2)), "y": int(m.group(3)), "z": int(m.group(4)), "mat": m.group(1), "sect": None, "axis": 0, "joint": 0, "axisRot": 0, "fill": 1, "strength": 1})
            continue
        raise ValueError(f"cannot parse block DSL fragment {part!r}")
    return blocks

def parse_loads(ls):
    out = []
    for l in ls or []:
        if isinstance(l, dict): out.append(dict(l)); continue
        x, y, z, fx, fy, fz = l
        out.append({"x": x, "y": y, "z": z, "fx": fx, "fy": fy, "fz": fz})
    return out

def transform(blocks, loads, how):
    nb, nl = [], []
    for b in blocks:
        b = dict(b)
        if how == "mirror_x":
            b["x"] = -1 - b["x"]
        elif how == "rot90_y":
            x, z = b["x"], b["z"]; b["x"], b["z"] = -1 - z, x
            b["axis"] = {0: 2, 1: 1, 2: 0}[b["axis"]]
        else:
            raise ValueError(f"unknown transform {how}")
        nb.append(b)
    for l in loads:
        l = dict(l)
        if how == "mirror_x":
            l["x"] = -1 - l["x"]; l["fx"] = -l["fx"]
        elif how == "rot90_y":
            x, z = l["x"], l["z"]; l["x"], l["z"] = -1 - z, x
            fx, fz = l["fx"], l["fz"]; l["fx"], l["fz"] = -fz, fx
        nl.append(l)
    return nb, nl

class Case:
    def __init__(self, name, raw, all_cases):
        self.name = name
        self.raw = raw
        base = {}
        ref = raw.get("extends") or raw.get("world") if isinstance(raw.get("world"), str) else raw.get("extends")
        if isinstance(ref, str):
            base = dict(all_cases[ref + ".json"])
        merged = dict(base)
        for k, v in raw.items():
            if k in ("extends",):
                continue
            if k == "world" and isinstance(v, str):
                continue
            merged[k] = v
        self.d = merged
        self.case_id = raw["case"]
        self.family = raw.get("family", "?")
        self.grade = raw.get("grade", "hard")
        self.requires = raw.get("requires", base.get("requires", []))
        self.vocab = merged["vocab"]
        self.derive = dict(base.get("derive", {})); self.derive.update(raw.get("derive", {}))
        self.env = build_env(self.derive)
        self.solve = merged.get("solve", {"selfWeight": True, "gravity": [0, -9.81, 0]})
        self.steps = raw.get("steps")
        self.variants = raw.get("variants")
        self.worlds = self._worlds(merged)
        self.asserts = raw.get("assert", [])

    def _blocks(self, spec):
        if isinstance(spec, str):
            return parse_block_dsl(spec)
        return [parse_block_tuple(t) for t in spec]

    def _worlds(self, d):
        worlds = {}
        base_loads = parse_loads(d.get("loads"))
        if "worlds" in d:
            for name, spec in d["worlds"].items():
                if isinstance(spec, list):
                    worlds[name] = World(self._blocks(spec), list(base_loads), self.solve, name)
            for name, spec in d["worlds"].items():
                if isinstance(spec, dict) and "extends" in spec:
                    b = worlds[spec["extends"]]
                    worlds[name] = World(list(b.blocks) + self._blocks(spec.get("add", [])), list(b.loads), self.solve, name)
            for name, spec in d["worlds"].items():
                if isinstance(spec, dict) and "transform" in spec:
                    b = worlds[spec["of"]]
                    nb, nl = transform(b.blocks, b.loads, spec["transform"])
                    worlds[name] = World(nb, nl, self.solve, name)
            return worlds
        blocks = self._blocks(d.get("blocks", []))
        if self.variants:
            for v in self.variants:
                vb = [dict(b) for b in blocks]
                if "sect" in v:
                    mats = {m["name"]: m for m in self.vocab["materials"]}
                    for b in vb:
                        if mats[b["mat"]]["role"] == "member":
                            b["sect"] = v["sect"]
                worlds[v["name"]] = World(vb, parse_loads(v.get("loads", d.get("loads"))), v.get("solve", self.solve), v["name"])
            return worlds
        worlds["default"] = World(blocks, base_loads, self.solve, "default")
        return worlds

    def vocab_ids(self):
        mats = {m["name"]: i for i, m in enumerate(self.vocab["materials"])}
        secs = {s["name"]: i for i, s in enumerate(self.vocab.get("sections", []))}
        return mats, secs

# ----------------------------------------------------------------------------- wire encoding
def encode_blocks(blocks, mats, secs):
    out = bytearray()
    for b in sorted(blocks, key=lambda b: (b["x"], b["y"], b["z"])):
        sect = -1 if b["sect"] is None else secs[b["sect"]]
        out += struct.pack("<iiiiiBBBBdd", b["x"], b["y"], b["z"], mats[b["mat"]], sect, b["axis"], b["joint"], b["axisRot"], 0, float(b["fill"]), float(b["strength"]))
    return bytes(out)

def encode_loads(loads):
    out = bytearray()
    for l in sorted(loads, key=lambda l: (l["x"], l["y"], l["z"])):
        out += struct.pack("<iiiIdddddd", l["x"], l["y"], l["z"], 0, float(l["fx"]), float(l["fy"]), float(l["fz"]), 0.0, 0.0, 0.0)
    return bytes(out)

def header(method, body, rid, revision=1):
    h = {"bsi": 1, "kind": "request", "id": rid, "method": method, "revision": revision}
    if body is not None:
        h["body"] = body
    return json.dumps(h, separators=(",", ":"), ensure_ascii=False)

# ----------------------------------------------------------------------------- transports
class Reply:
    def __init__(self, header_text, payload):
        self.text = header_text
        self.payload = payload
        self.h = json.loads(header_text)
    @property
    def error(self):
        return self.h.get("kind") == "error"
    @property
    def code(self):
        return self.h.get("code")

class LineClient:
    """T-B' over a host process taking the bsi-hostd flags."""
    name = "stdio-b64"
    def __init__(self, argv):
        self.p = subprocess.Popen(argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=False)
    def call(self, hdr, payload=b""):
        d = json.loads(hdr)
        if payload:
            d["payloadB64"] = base64.b64encode(payload).decode("ascii")
        line = json.dumps(d, separators=(",", ":"), ensure_ascii=False) + "\n"
        self.p.stdin.write(line.encode("utf-8")); self.p.stdin.flush()
        out = self.p.stdout.readline()
        if not out:
            raise RuntimeError("host process closed its stdout")
        text = out.decode("utf-8").rstrip("\n")
        m = re.search(r',"payloadBytes":(\d+),"payloadB64":"([A-Za-z0-9+/=]*)"\}$', text)
        if not m:
            raise RuntimeError("reply line lacks the payloadBytes/payloadB64 suffix")
        pl = base64.b64decode(m.group(2))
        if len(pl) != int(m.group(1)):
            raise RuntimeError("payloadBytes disagrees with payloadB64")
        return Reply(text[:m.start()] + "}", pl)
    def close(self):
        try:
            self.p.stdin.close(); self.p.wait(timeout=10)
        except Exception:
            self.p.kill()

FRAME_PREFIX = struct.Struct("<2sHII")

def encode_frame(hdr, payload, flags=0):
    hb = hdr.encode("utf-8")
    if payload: flags |= 6
    return FRAME_PREFIX.pack(b"FC", flags, len(hb), len(payload)) + hb + payload

def decode_frame(buf):
    magic, flags, hl, pl = FRAME_PREFIX.unpack_from(buf, 0)
    if magic != b"FC" or 12 + hl + pl != len(buf):
        raise RuntimeError("malformed reply frame")
    return Reply(buf[12:12 + hl].decode("utf-8"), bytes(buf[12 + hl:]))

class FrameClient:
    name = "frame"
    def __init__(self, argv):
        self.p = subprocess.Popen(argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=False)
    def call(self, hdr, payload=b""):
        self.p.stdin.write(encode_frame(hdr, payload)); self.p.stdin.flush()
        prefix = self.p.stdout.read(12)
        if len(prefix) < 12: raise RuntimeError("host process closed its stdout")
        _, _, hl, pl = FRAME_PREFIX.unpack(prefix)
        rest = b""
        while len(rest) < hl + pl:
            chunk = self.p.stdout.read(hl + pl - len(rest))
            if not chunk: raise RuntimeError("short frame")
            rest += chunk
        return decode_frame(prefix + rest)
    def close(self):
        try:
            self.p.stdin.close(); self.p.wait(timeout=10)
        except Exception:
            self.p.kill()

ARENA_HDR = struct.Struct("<IIQ" + "Q" * 10 + "QII16s")

class ArenaClient:
    """T-B: doorbell lines over stdio; world/loads/req/reply in a mapped file. Regions are laid out
    [header][world][attrs][loads][req][reply]; the file grows on needBigger."""
    name = "arena"
    def __init__(self, argv, capacity=1 << 20):
        self.tmp = tempfile.NamedTemporaryFile(prefix="bsi-arena-", suffix=".bin", delete=False)
        self.path = self.tmp.name
        self.tmp.close()
        self.capacity = capacity
        with open(self.path, "wb") as f: f.truncate(capacity)
        self.seq = 0
        self.world = b""; self.attrs = b""
        self.p = subprocess.Popen(list(argv) + ["--arena", self.path], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=False)
    def _layout(self, loads, req):
        off = 128
        regions = {}
        for name, data in (("world", self.world), ("attrs", self.attrs), ("loads", loads), ("req", req)):
            regions[name] = (off, len(data), data); off += len(data)
            off = (off + 63) // 64 * 64
        regions["reply"] = (off, 0, b"")
        return regions, off
    def call(self, hdr, payload=b""):
        d = json.loads(hdr)
        method = d["method"]
        door = {"bsi.hello": "hello", "bsi.vocab.declare": "vocab", "bsi.vocab.query": "vocab", "bsi.world.declare": "declare",
                "bsi.world.edit": "edit", "bsi.solve": "solve", "bsi.cancel": "cancel"}[method]
        loads = b""
        if door == "declare":
            nb = d.get("body", {}).get("blocks", len(payload) // 40)
            self.world, self.attrs = payload[:nb * 40], payload[nb * 40:]
        elif door == "solve":
            loads = payload
        req = hdr.encode("utf-8")
        for attempt in range(8):
            regions, end = self._layout(loads, req)
            need = end + 65536
            if need > self.capacity:
                self.capacity = max(need, self.capacity * 2)
                with open(self.path, "r+b") as f: f.truncate(self.capacity)
            self.seq += 1
            with open(self.path, "r+b") as f:
                m = mmap.mmap(f.fileno(), self.capacity)
                for name in ("world", "attrs", "loads", "req"):
                    off, ln, data = regions[name]
                    m[off:off + ln] = data
                h = ARENA_HDR.pack(0x41495342, 1, self.capacity,
                                   regions["world"][0], regions["world"][1], regions["attrs"][0], regions["attrs"][1],
                                   regions["loads"][0], regions["loads"][1], regions["req"][0], regions["req"][1],
                                   regions["reply"][0], 0, self.seq, 0, 0, b"\0" * 16)
                m[0:128] = h
                m.flush(); m.close()
            bell = json.dumps({"bsi": 1, "door": door, "seq": self.seq}) + "\n"
            self.p.stdin.write(bell.encode()); self.p.stdin.flush()
            line = self.p.stdout.readline()
            if not line: raise RuntimeError("host process closed its stdout")
            r = json.loads(line)
            if r.get("seq") != self.seq: raise RuntimeError("doorbell seq mismatch")
            if r["door"] == "needBigger":
                self.capacity = max(self.capacity * 2, regions["reply"][0] + int(r["required"]) + 4096)
                with open(self.path, "r+b") as f: f.truncate(self.capacity)
                continue
            if r["door"] not in ("reply", "error"):
                raise RuntimeError(f"doorbell answered {r}")
            with open(self.path, "rb") as f:
                m = mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ)
                off = regions["reply"][0]; ln = int(r["replyLen"])
                buf = bytes(m[off:off + ln]); m.close()
            return decode_frame(buf)
        raise RuntimeError("arena never became big enough")
    def close(self):
        try:
            self.p.stdin.close(); self.p.wait(timeout=10)
        except Exception:
            self.p.kill()
        try: os.unlink(self.path)
        except OSError: pass

class CapiClient:
    """T-A in process: ctypes over bsi_capi.h. This is the path the Minecraft mod takes (JNA)."""
    name = "capi"
    def __init__(self, lib, probe_caps=None):
        self.lib = ctypes.CDLL(lib)
        self.lib.bsi_capi_abi_version.restype = ctypes.c_uint32
        self.lib.bsi_capi_open.restype = ctypes.c_void_p
        self.lib.bsi_capi_open.argtypes = [ctypes.c_char_p]
        self.lib.bsi_capi_call.restype = ctypes.c_int
        self.lib.bsi_capi_call.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_size_t, ctypes.c_void_p, ctypes.c_size_t, ctypes.POINTER(ctypes.c_size_t), ctypes.POINTER(ctypes.c_size_t)]
        self.lib.bsi_capi_close.argtypes = [ctypes.c_void_p]
        if self.lib.bsi_capi_abi_version() != 1:
            raise RuntimeError("bsi_capi_abi_version != 1")
        opts = {"probe": True, "assumeCaps": list(probe_caps)} if probe_caps else {}
        self.h = self.lib.bsi_capi_open(json.dumps(opts).encode())
        if not self.h: raise RuntimeError("bsi_capi_open failed")
        self.buf = ctypes.create_string_buffer(1 << 16)
    def call(self, hdr, payload=b""):
        f = encode_frame(hdr, payload)
        for _ in range(3):
            ln, need = ctypes.c_size_t(0), ctypes.c_size_t(0)
            rc = self.lib.bsi_capi_call(self.h, f, len(f), self.buf, len(self.buf), ctypes.byref(ln), ctypes.byref(need))
            if rc == 2:
                self.buf = ctypes.create_string_buffer(need.value + 1024); continue
            if rc != 0: raise RuntimeError(f"bsi_capi_call rc={rc}")
            return decode_frame(self.buf.raw[:ln.value])
        raise RuntimeError("bsi_capi_call kept asking for a bigger buffer")
    def close(self):
        self.lib.bsi_capi_close(self.h)

# ----------------------------------------------------------------------------- session driver
class Outcome:
    """Everything one solve produced, decoded, plus the inputs it was asked for."""
    def __init__(self, schema, world, mats, secs, replies, solve_reply, error=None):
        self.world = world; self.mats = mats; self.secs = secs
        self.replies = replies      # list of (method, Reply)
        self.reply = solve_reply
        self.error = error          # Reply of the failing verb, if any
        self.sections = {}
        if solve_reply is not None and not solve_reply.error:
            for s in solve_reply.h["sections"]:
                blob = solve_reply.payload[s["offset"]:s["offset"] + s["bytes"]]
                self.sections[s["name"]] = decode_records(schema, s["name"], blob)
        self.schema = schema
    def sec(self, name):
        for k in (name, name + ":f32"):
            if k in self.sections: return self.sections[k]
        return []

def open_client(args, transport, assume):
    hostd = args.hostd
    if args.adapter == "capi":
        return CapiClient(args.lib, assume)
    if args.adapter == "engine":
        argv = [hostd, "--engine", args.lib, "--transport", transport]
    elif args.adapter == "sidecar":
        argv = list(args.exe) + ["--transport", transport]
    else:
        raise RuntimeError("adapter not runnable")
    if assume:
        argv += ["--assume-caps", ",".join(assume)]
    return {"stdio-b64": LineClient, "frame": FrameClient, "arena": ArenaClient}[transport](argv)

def hello(client, sha=None):
    body = {"bsi": 1, "client": "conformance/1", "contractSha256": sha or contract_sha(), "arena": {"supported": True, "maxBytes": 1 << 28}}
    return client.call(header("bsi.hello", body, "hello"))

def run_world(schema, client, case, world, solve_body=None, loads=None, sha=None):
    """hello -> vocab -> declare -> solve for one world; returns an Outcome."""
    mats, secs = case.vocab_ids()
    replies = []
    r = hello(client, sha); replies.append(("bsi.hello", r))
    if r.error: return Outcome(schema, world, mats, secs, replies, None, r)
    r = client.call(header("bsi.vocab.declare", case.vocab, "vocab")); replies.append(("bsi.vocab.declare", r))
    if r.error: return Outcome(schema, world, mats, secs, replies, None, r)
    wb = encode_blocks(world.blocks, mats, secs)
    r = client.call(header("bsi.world.declare", {"blocks": len(world.blocks)}, "declare"), wb); replies.append(("bsi.world.declare", r))
    if r.error: return Outcome(schema, world, mats, secs, replies, None, r)
    ld = encode_loads(world.loads if loads is None else loads)
    body = dict(solve_body if solve_body is not None else world.solve)
    if "numThreads" not in body: body["numThreads"] = 1
    nl = len(ld) // 64
    if nl: body["loads"] = nl
    r = client.call(header("bsi.solve", body, "solve"), ld); replies.append(("bsi.solve", r))
    return Outcome(schema, world, mats, secs, replies, r, r if r.error else None)

# ----------------------------------------------------------------------------- C-1 checks on every reply
def check_reply(schema, validator, method, reply, declared_blocks=None):
    probs = []
    h = reply.h
    if reply.error:
        probs += validator.validate("error", h)
        return probs
    if method == "bsi.hello":
        probs += validator.validate("hello.response", h)
        order = list(schema["$defs"]["hello.response"]["properties"].keys())
    elif method == "bsi.solve":
        probs += validator.validate("solve.response", h)
        order = list(schema["$defs"]["solve.response"]["properties"].keys())
        keys = [k for k in h.keys() if not k.startswith("x-")]
        if keys != [k for k in order if k in h]:
            probs.append(f"solve.response key order {keys} != schema order")
        off = 0
        want = ["blocks", "equilibrium", "quality", "buckling", "members", "memberBlocks", "stations", "facets", "facetSurfaces", "attrsEcho"]
        names = [s["name"].split(":")[0] for s in h["sections"]]
        if [n for n in want if n in names] != names:
            probs.append(f"sections not in the fixed order: {names}")
        for s in h["sections"]:
            if s["offset"] != off: probs.append(f"section {s['name']} not contiguous")
            base, _, var = s["name"].partition(":")
            rec = schema["x-records"][base]
            sz = rec["x-f32"] if var == "f32" else rec["bytes"]
            if s["bytes"] != sz * s["count"]: probs.append(f"section {s['name']} bytes {s['bytes']} != {sz}*{s['count']}")
            off += s["bytes"]
        if off != len(reply.payload): probs.append("payload length != sum of sections")
        bl = [s for s in h["sections"] if s["name"] == "blocks"]
        if not bl: probs.append("no blocks section")
        elif declared_blocks is not None and bl[0]["count"] != declared_blocks: probs.append(f"blocks count {bl[0]['count']} != declared {declared_blocks}")
        whys = schema["x-enums"]["unassignedWhy"]
        ranks = [(whys.index(u["why"]) if u["why"] in whys else len(whys), u["island"], u["blocks"]) for u in h["unassigned"]]
        if ranks != sorted(ranks): probs.append("unassigned not in canonical order")
        codes = [w["code"] for w in h["diag"]["warnings"]]
        if codes != sorted(codes) or len(set(codes)) != len(codes): probs.append("warnings not sorted/merged")
    return probs

def check_c7(outcome):
    probs = []
    for i, b in enumerate(outcome.sec("blocks")):
        over = bool(b["flags"] & 1)
        if over != (b["dc"] > 1.0): probs.append(f"blocks[{i}] overloaded flag disagrees with dc")
    for name in ("members", "facets"):
        ids = [r["id"] for r in outcome.sec(name)]
        if ids != sorted(ids) or len(set(ids)) != len(ids): probs.append(f"{name} ids not strictly ascending")
    return probs

# ----------------------------------------------------------------------------- assertions
class AssertError(Exception):
    pass

def input_blocks_sorted(outcome):
    return sorted(outcome.world.blocks, key=lambda b: (b["x"], b["y"], b["z"]))

def where_ok(pred, blk):
    if not pred: return True
    env = dict(blk)
    for part in pred.split("&&"):
        m = re.match(r"^\s*(\w+)\s*(==|!=|<=|>=|<|>)\s*(-?\w+)\s*$", part)
        if not m: raise AssertError(f"cannot parse where {pred!r}")
        k, op, v = m.groups()
        lhs = env[k]
        rhs = int(v) if re.match(r"^-?\d+$", v) else v
        if op == "==" and not lhs == rhs: return False
        if op == "!=" and not lhs != rhs: return False
        if op == "<" and not lhs < rhs: return False
        if op == ">" and not lhs > rhs: return False
        if op == "<=" and not lhs <= rhs: return False
        if op == ">=" and not lhs >= rhs: return False
    return True

def select_member(outcome, a):
    members = outcome.sec("members")
    mb = outcome.sec("memberBlocks")
    sel = a.get("select", {})
    if "blocksContain" in sel:
        want = list(sel["blocksContain"])
        for m in members:
            cells = [[c["x"], c["y"], c["z"]] for c in mb[m["blockFirst"]:m["blockFirst"] + m["blockCount"]]]
            if want in cells: return m
        raise AssertError(f"no member contains {want}")
    if "member" in a:
        return members[a["member"]]
    raise AssertError("member selector needs 'member' or 'select'")

def resolve(outcome, path, a):
    """Resolve a contract path against an Outcome. Returns a value or a list (for [*])."""
    h = outcome.reply.h
    m = re.match(r"^([a-zA-Z]+)(?:\[([^\]]*)\])?(?:\.([a-zA-Z]+)(?:\[(\d+)\])?)?$", path)
    if not m: raise AssertError(f"unparseable path {path}")
    root, idx, field, fidx = m.groups()
    def pick(rec):
        v = rec[field]
        return v[int(fidx)] if fidx is not None else v
    if root == "status": return h["status"]
    if root == "diag": return h["diag"][field]
    if root == "sections": return h["sections"]
    if root == "payload": return outcome.reply.payload
    if root == "buckling":
        if idx is None: return h["buckling"][field]
        return pick(outcome.sec("buckling")[int(idx)])
    if root == "unassigned":
        if idx is None:
            return h["unassigned"] if field is None else [u[field] for u in h["unassigned"]]
        groups = [u for u in h["unassigned"] if u["why"] == idx]
        if not groups: raise AssertError(f"no unassigned group {idx}")
        return groups[0][field] if field else groups[0]
    if root in ("equilibrium", "quality"):
        recs = outcome.sec(root)
        if not recs: raise AssertError(f"no {root} section")
        return pick(recs[0])
    if root == "blocks":
        recs = outcome.sec("blocks"); inputs = input_blocks_sorted(outcome)
        if idx == "*":
            sel = [(r, b) for r, b in zip(recs, inputs) if where_ok(a.get("where"), b)]
            vals = []
            for r, b in sel:
                v = pick(r)
                if field == "reason":
                    v = "none" if v == 0 else outcome.schema["x-enums"]["unassignedWhy"][v - 1]
                vals.append(v)
            return vals
        return pick(recs[int(idx)]) if field else recs[int(idx)]
    if root in ("members", "facets"):
        recs = outcome.sec(root)
        if idx is None: return recs if field is None else [pick(r) for r in recs]
        if idx == "span": rec = select_member(outcome, a)
        elif idx == "*": return [pick(r) for r in recs]
        else: rec = recs[int(idx)]
        return pick(rec) if field else rec
    if root == "stations":
        mem = select_member(outcome, a)
        st = outcome.sec("stations")[mem["stationFirst"]:mem["stationFirst"] + mem["stationCount"]]
        if not st: raise AssertError("member has no stations")
        if idx == "governing":
            rec = min(st, key=lambda s: abs(s["s"] - mem["governingS"]))
            return pick(rec) if field else rec
        if idx == "*": return [pick(r) for r in st]
        return pick(st[int(idx)]) if field else st[int(idx)]
    raise AssertError(f"unknown root {root}")

def num(v, env):
    return Expr.eval(v, env) if isinstance(v, str) else float(v)

def close(got, want, a, env):
    if isinstance(want, str) and not re.match(r"^[-+ (]*[\d.a-zA-Z_]", want):
        return got == want
    if isinstance(want, str) and want in ("ok", "partial", "computed", "eigen", "screen", "none") and isinstance(got, str):
        return got == want
    if isinstance(got, str): return got == want
    w = num(want, env); g = float(got)
    rel = a.get("rel"); absd = a.get("abs")
    if isinstance(rel, str): rel = 1e-2               # PROVISIONAL(...) lines: structure frozen, number provisional
    if rel is None and absd is None:
        return g == w
    tol = 0.0
    if rel is not None: tol = max(tol, abs(w) * float(rel))
    if absd is not None: tol = max(tol, float(absd))
    return abs(g - w) <= tol

def evaluate(a, outcomes, env, default_world):
    """One assertion against the outcomes dict {world_name: Outcome}. Raises AssertError on failure."""
    if "error" in a:
        o = outcomes[a.get("world", default_world)]
        if o.error is None or o.error.code != a["error"]:
            raise AssertError(f"expected error {a['error']}, got {o.error.code if o.error else 'success'}")
        return
    path = a["path"]
    if "delta" in a or "equal" in a:
        pair = a.get("delta") or a.get("equal")
        va = resolve(outcomes[pair[0]], path, a); vb = resolve(outcomes[pair[1]], path, a)
        if a.get("bitwise"):
            if va != vb: raise AssertError(f"{path}: {pair[0]} and {pair[1]} differ bitwise")
            return
        if "delta" in a:
            d = float(va) - float(vb)
            if "rel_le" in a:
                ref = abs(float(vb)) or 1.0
                if abs(d) / ref > float(a["rel_le"]): raise AssertError(f"{path}: |delta|/ref = {abs(d)/ref} > {a['rel_le']}")
                return
            if not close(d, a["eq"], a, env): raise AssertError(f"{path}: delta {d} != {a['eq']} (={num(a['eq'], env)})")
            return
        if not close(va, vb, {"rel": a.get("rel", 0)}, env): raise AssertError(f"{path}: {va} != {vb}")
        return
    o = outcomes[a.get("world", default_world)]
    if o.reply is None or o.reply.error:
        raise AssertError(f"{path}: solve failed with {o.error.code if o.error else '?'}: {o.error.h.get('message') if o.error else ''}")
    got = resolve(o, path, a)
    if "count" in a:
        if len(got) != a["count"]: raise AssertError(f"{path}: count {len(got)} != {a['count']}")
    elif "count_ge" in a:
        if len(got) < a["count_ge"]: raise AssertError(f"{path}: count {len(got)} < {a['count_ge']}")
    elif "has" in a:
        want = a["has"]
        hit = [u for u in got if u.get("why") == want.get("why")]
        if not hit: raise AssertError(f"{path}: no group {want}")
        if "count" in want and sum(len(u["blocks"]) for u in hit) != want["count"]: raise AssertError(f"{path}: group {want['why']} has {sum(len(u['blocks']) for u in hit)} blocks, want {want['count']}")
    elif "set" in a:
        if sorted(map(tuple, got)) != sorted(map(tuple, a["set"])): raise AssertError(f"{path}: {got} != set {a['set']}")
    elif "all_eq" in a:
        if not got: raise AssertError(f"{path}: empty selection")
        bad = [g for g in got if g != a["all_eq"]]
        if bad: raise AssertError(f"{path}: {len(bad)} of {len(got)} != {a['all_eq']} (e.g. {bad[0]})")
    elif "exists_finite" in a:
        if not any(isinstance(g, float) and math.isfinite(g) for g in got): raise AssertError(f"{path}: no finite value")
    elif "eq" in a:
        ok = close(got, a["eq"], a, env)
        if not ok and "alt" in a and "in" in a["alt"]:
            ok = any(close(got, x, a, env) for x in a["alt"]["in"])
        if not ok:
            shown = num(a["eq"], env) if not isinstance(a["eq"], str) or re.match(r"^[-+ (]*[\d.a-zA-Z_]", a["eq"]) else a["eq"]
            raise AssertError(f"{path}: {got} != {a['eq']} (={shown})")
    elif "abs_eq" in a:
        if not close(abs(float(got)), a["abs_eq"], a, env): raise AssertError(f"{path}: |{got}| != {a['abs_eq']} (={num(a['abs_eq'], env)})")
    elif "abs_le" in a:
        if abs(float(got)) > num(a["abs_le"], env): raise AssertError(f"{path}: |{got}| > {a['abs_le']}")
    elif "le" in a:
        if float(got) > num(a["le"], env): raise AssertError(f"{path}: {got} > {a['le']}")
    elif "lt" in a:
        if not float(got) < num(a["lt"], env): raise AssertError(f"{path}: {got} !< {a['lt']}")
    elif "gt" in a:
        if not float(got) > num(a["gt"], env): raise AssertError(f"{path}: {got} !> {a['gt']}")
    elif "ge" in a:
        if float(got) < num(a["ge"], env): raise AssertError(f"{path}: {got} < {a['ge']}")
    else:
        raise AssertError(f"{path}: unknown operator in {a}")

# ----------------------------------------------------------------------------- steps mode (C-11)
def run_steps(schema, validator, args, case, transport, assume, is_stub):
    """Sequential verbs on one session; hello-contract-mismatch and anything after a BSI_VERSION run fresh."""
    mats, secs = case.vocab_ids()
    client = open_client(args, transport, assume)
    fresh_needed = False
    replies = []
    try:
        r = hello(client); replies.append(("bsi.hello", r))
        if r.error: raise AssertError(f"hello failed: {r.code}")
        r = client.call(header("bsi.vocab.declare", case.vocab, "vocab")); replies.append(("bsi.vocab.declare", r))
        if r.error: raise AssertError(f"vocab failed: {r.code}")
        current_world = None
        for step in case.steps:
            name = step["name"]
            do = step["do"]
            if fresh_needed:
                client.close(); client = open_client(args, transport, assume); fresh_needed = False
                r = hello(client); r = client.call(header("bsi.vocab.declare", case.vocab, "vocab"))
                if current_world is not None:
                    client.call(header("bsi.world.declare", {"blocks": len(current_world.blocks)}, "declare"), encode_blocks(current_world.blocks, mats, secs))
            if do == "bsi.hello":
                r = hello(client, step.get("contractSha256")); fresh_needed = True
            elif do == "bsi.world.declare":
                if "world" in step:
                    w = case.worlds["default"] if step["world"] == case.d.get("case") or step["world"] == case.raw.get("world") else None
                    if w is None: raise AssertError(f"step {name}: unknown world {step['world']}")
                else:
                    w = World([parse_block_tuple(t) for t in step["blocks"]], [], case.solve, name)
                r = client.call(header("bsi.world.declare", {"blocks": len(w.blocks)}, "declare-" + name), encode_blocks(w.blocks, mats, secs))
                if not r.error: current_world = w
            elif do == "bsi.solve":
                body = dict(step.get("solve", case.solve))
                if "numThreads" not in body: body["numThreads"] = 1
                ld = encode_loads(parse_loads(step.get("loads")))
                if ld: body["loads"] = len(ld) // 64
                r = client.call(header("bsi.solve", body, "solve-" + name), ld)
            else:
                raise AssertError(f"step {name}: unknown verb {do}")
            replies.append((do, r))
            probs = check_reply(schema, validator, do, r, len(current_world.blocks) if (do == "bsi.solve" and current_world) else None)
            if probs: raise AssertError(f"step {name}: C-1 {probs[0]}")
            if "expectError" in step:
                if not r.error or r.code != step["expectError"]:
                    raise AssertError(f"step {name}: expected {step['expectError']}, got {r.code if r.error else 'success'}")
                if "expectAt" in step and r.h.get("at") != step["expectAt"]:
                    raise AssertError(f"step {name}: at {r.h.get('at')} != {step['expectAt']}")
            if "expect" in step:
                if r.error: raise AssertError(f"step {name}: expected success, got {r.code}: {r.h.get('message')}")
                for k, v in step["expect"].items():
                    got = r.h
                    for seg in k.split("."): got = got[seg]
                    if got != v: raise AssertError(f"step {name}: {k} = {got}, want {v}")
            if "assert" in step:
                if r.error: raise AssertError(f"step {name}: {r.code}: {r.h.get('message')}")
                if is_stub:
                    print(f"       step {name}: STUB (no mechanics) -- assert skipped, not counted green")
                else:
                    o = Outcome(schema, current_world, mats, secs, replies, r)
                    for a in step["assert"]:
                        evaluate(a, {"default": o}, case.env, "default")
    finally:
        client.close()
    return replies

# ----------------------------------------------------------------------------- driver
def selfcheck(schema):
    rc = subprocess.call([sys.executable, os.path.join(CONTRACT, "check_contract.py")])
    if rc != 0:
        return rc
    roots = set(schema["x-records"].keys()) | {"status", "diag", "buckling", "unassigned", "sections", "payload", "error"}
    fields = set()
    for rec, spec in schema["x-records"].items():
        for fld in spec["fields"]:
            fields.add(f"{rec}.{fld[0]}")
    for k in schema["$defs"]["diag"]["properties"]:
        fields.add(f"diag.{k}")
    fields |= {"buckling.kind", "buckling.state", "buckling.factor", "buckling.island", "unassigned.why", "unassigned.island", "unassigned.blocks"}
    pat = re.compile(r"^([a-zA-Z]+)(\[[^\]]*\])?(?:\.([a-zA-Z]+)(\[[^\]]*\])?)?$")
    problems = 0
    raw = dict(load_cases())
    for name, case in raw.items():
        asserts = list(case.get("assert", []))
        for v in case.get("variants", []): asserts += v.get("assert", [])
        for s in case.get("steps", []): asserts += s.get("assert", [])
        for a in asserts:
            if "path" not in a: continue
            m = pat.match(a["path"])
            if not m: problems += 1; print(f"[C-0] {name}: unparseable path {a['path']!r}"); continue
            root, _i, field, _j = m.groups()
            if root not in roots: problems += 1; print(f"[C-0] {name}: unknown root {root!r}")
            elif field and f"{root}.{field}" not in fields and root not in ("unassigned",): problems += 1; print(f"[C-0] {name}: unknown field {root}.{field}")
        for cap in case.get("requires", []):
            if cap not in schema["x-capabilities"]: problems += 1; print(f"[C-0] {name}: unknown capability {cap!r}")
        try:
            Case(name, case, raw)
        except Exception as e:      # noqa
            problems += 1; print(f"[C-0] {name}: case does not build: {e}")
    print(f"selfcheck: {len(raw)} cases, {problems} problem(s)")
    return 1 if problems else 0

def list_cases():
    for name, case in load_cases():
        print(f"{name:40} {case.get('family','?'):5} {case.get('grade','?'):12} requires={','.join(case.get('requires', []))}")
    return 0

def find_hostd(args):
    if args.hostd: return args.hostd
    for pat in ("build/host/bsi-hostd", "build/**/bsi-hostd", "../build/host/bsi-hostd"):
        hits = glob.glob(os.path.join(os.getcwd(), pat), recursive=True)
        if hits: return hits[0]
    return None

def main(argv):
    ap = argparse.ArgumentParser(add_help=False)
    ap.add_argument("--selfcheck", action="store_true"); ap.add_argument("--list", action="store_true"); ap.add_argument("-h", "--help", action="store_true")
    ap.add_argument("--adapter"); ap.add_argument("--lib"); ap.add_argument("--exe", nargs="+"); ap.add_argument("--hostd")
    ap.add_argument("--transports"); ap.add_argument("--repeat", type=int, default=3); ap.add_argument("--assume-caps"); ap.add_argument("--expected-red")
    ap.add_argument("--families"); ap.add_argument("--case"); ap.add_argument("--record"); ap.add_argument("--stub", action="store_true"); ap.add_argument("--allow-all-skip", action="store_true")
    args = ap.parse_args(argv)
    if args.help: print(__doc__); return 0
    schema = load_schema()
    if args.selfcheck: return selfcheck(schema)
    if args.list: return list_cases()
    if not args.adapter: print(__doc__); return 2
    if subprocess.call([sys.executable, os.path.join(CONTRACT, "check_contract.py")]) != 0:
        print("contract hash mismatch: refusing to run", file=sys.stderr); return 2
    if args.adapter == "frame_v2":
        print("frame_v2 adapter: NOT RUN -- the engine's T-A bsi.* verbs (tectonic2 MC68) do not exist yet; reported, never green", file=sys.stderr)
        return 3
    if args.adapter in ("engine", "sidecar"):
        args.hostd = find_hostd(args)
        if args.adapter == "engine" and (not args.lib or not args.hostd):
            print("--adapter engine needs --lib and a bsi-hostd (--hostd)", file=sys.stderr); return 2
        if args.adapter == "sidecar" and not args.exe:
            print("--adapter sidecar needs --exe", file=sys.stderr); return 2
        transports = (args.transports or "stdio-b64,frame,arena").split(",")
        if os.name == "nt": transports = [t for t in transports if t != "arena"]
    elif args.adapter == "capi":
        if not args.lib: print("--adapter capi needs --lib", file=sys.stderr); return 2
        transports = ["capi"]
    else:
        print(f"unknown adapter {args.adapter}", file=sys.stderr); return 2
    assume = [c for c in (args.assume_caps or "").split(",") if c]
    expected_red = {}
    if args.expected_red:
        acc = json.load(open(args.expected_red, encoding="utf-8"))
        expected_red = {e["case"]: e for e in acc.get("cases", [])}
    validator = Validator(schema)
    raw = dict(load_cases())
    cases = [Case(n, c, raw) for n, c in raw.items()]
    if args.families: cases = [c for c in cases if c.family in args.families.split(",")]
    if args.case: cases = [c for c in cases if c.case_id == args.case]

    # hello once to learn the capabilities
    try:
        probe = open_client(args, transports[0], assume)
    except Exception as e:      # noqa
        print(f"engine could not be loaded: {e!r} (C-3: a refused load is the correct outcome for a foreign ABI)", file=sys.stderr); return 1
    try:
        h = hello(probe)
        if h.error:
            print(f"hello refused: {h.code} {h.h.get('message')}", file=sys.stderr); return 1
        caps = set(h.h["capabilities"]); engine = f"{h.h['engine']}/{h.h['version']}"
    except Exception as e:      # noqa
        print(f"engine refused to answer hello: {e!r} (C-3: a refused load is the correct outcome for a foreign ABI)", file=sys.stderr); return 1
    finally:
        probe.close()
    is_stub = "x-bsi.stub" in caps
    if is_stub and not args.stub:
        print("engine declares x-bsi.stub: a zero-mechanics test double. Pass --stub to run host-only families.", file=sys.stderr); return 2
    effective = caps | set(assume)
    print(f"engine {engine}  capabilities {sorted(caps)}  transports {transports}  {'ASSUMED ' + str(assume) if assume else ''}")

    report = {"engine": engine, "capabilities": sorted(caps), "assumed": assume, "transports": transports, "cases": []}
    hard_red = 0; executed = 0; skipped = 0; stale = 0
    for case in cases:
        entry = {"case": case.case_id, "family": case.family, "grade": case.grade, "result": None, "detail": ""}
        report["cases"].append(entry)
        missing = [c for c in case.requires if c not in effective]
        tag = ""
        if any(c in assume for c in case.requires): tag = " ASSUMED"
        if missing:
            entry["result"] = "SKIP"; entry["detail"] = "requires " + ",".join(missing); skipped += 1
            print(f"[{case.family:4}] {case.case_id:36} SKIP   (requires {','.join(missing)})"); continue
        if is_stub and case.family in MECHANICS_FAMILIES:
            entry["result"] = "SKIP"; entry["detail"] = "STUB (no mechanics)"; skipped += 1
            print(f"[{case.family:4}] {case.case_id:36} SKIP   STUB (no mechanics)"); continue
        try:
            executed += 1
            if case.steps:
                per = {t: run_steps(schema, validator, args, case, t, assume, is_stub) for t in transports}
                first = transports[0]
                for t in transports[1:]:
                    for (ma, ra), (mb, rb) in zip(per[first], per[t]):
                        if ra.text != rb.text or ra.payload != rb.payload:
                            raise AssertError(f"C-2: transport {t} differs from {first} at {ma}")
            else:
                per_transport = {}
                for t in transports:
                    outcomes = {}
                    for wname, world in case.worlds.items():
                        client = open_client(args, t, assume)
                        try:
                            o = run_world(schema, client, case, world)
                        finally:
                            client.close()
                        for method, r in o.replies:
                            probs = check_reply(schema, validator, method, r, len(world.blocks) if method == "bsi.solve" else None)
                            if probs: raise AssertError(f"world {wname} {method}: C-1 {probs[0]}")
                        if o.reply is not None and not o.reply.error:
                            probs = check_c7(o)
                            if probs: raise AssertError(f"world {wname}: C-7 {probs[0]}")
                        outcomes[wname] = o
                    per_transport[t] = outcomes
                # C-2: every transport bitwise identical
                first = transports[0]
                for t in transports[1:]:
                    for wname in case.worlds:
                        a, b = per_transport[first][wname], per_transport[t][wname]
                        ra, rb = (a.reply or a.error), (b.reply or b.error)
                        if ra.text != rb.text or ra.payload != rb.payload:
                            raise AssertError(f"C-2: transport {t} differs from {first} for world {wname}")
                # C-9: repeat on the primary transport
                for k in range(1, args.repeat):
                    for wname, world in case.worlds.items():
                        client = open_client(args, first, assume)
                        try: o = run_world(schema, client, case, world)
                        finally: client.close()
                        ra, rb = (per_transport[first][wname].reply or per_transport[first][wname].error), (o.reply or o.error)
                        if ra.text != rb.text or ra.payload != rb.payload:
                            raise AssertError(f"C-9: run {k+1} differs bitwise for world {wname}")
                outcomes = per_transport[first]
                default_world = next(iter(case.worlds))
                # variant-level asserts
                if case.variants:
                    for v in case.variants:
                        for a in v.get("assert", []):
                            a = dict(a); a.setdefault("world", v["name"])
                            evaluate(a, outcomes, case.env, v["name"])
                for a in case.asserts:
                    grade = a.get("grade", case.grade)
                    try:
                        evaluate(a, outcomes, case.env, default_world)
                    except AssertError as e:
                        if grade == "provisional":
                            print(f"       PROVISIONAL-MISS {e}")
                        else:
                            raise
            entry["result"] = "PASS" + tag.strip()
            if case.case_id in expected_red:
                stale += 1; entry["result"] = "STALE-ACCOUNT"
                print(f"[{case.family:4}] {case.case_id:36} GREEN but on the expected-red account -> STALE (remove the entry)")
            else:
                print(f"[{case.family:4}] {case.case_id:36} PASS{tag}")
        except AssertError as e:
            if case.case_id in expected_red:
                entry["result"] = "RED-ON-ACCOUNT"; entry["detail"] = str(e)
                print(f"[{case.family:4}] {case.case_id:36} RED (on account: {expected_red[case.case_id].get('waits_on','?')}){tag}  {e}")
            elif case.grade == "provisional":
                entry["result"] = "PROVISIONAL-MISS"; entry["detail"] = str(e)
                print(f"[{case.family:4}] {case.case_id:36} PROVISIONAL-MISS{tag}  {e}")
            else:
                hard_red += 1; entry["result"] = "FAIL"; entry["detail"] = str(e)
                print(f"[{case.family:4}] {case.case_id:36} FAIL{tag}  {e}")
        except Exception as e:      # noqa: transport / runner failure is red too, never silent
            hard_red += 1; entry["result"] = "ERROR"; entry["detail"] = repr(e)
            print(f"[{case.family:4}] {case.case_id:36} ERROR{tag}  {e!r}")
    print(f"summary: executed={executed} skipped={skipped} hard_red={hard_red} stale_account={stale}" + (f"  ASSUMED caps {assume}: exit 4 by design (not a capability claim)" if assume else ""))
    if args.record:
        json.dump(report, open(args.record, "w", encoding="utf-8"), indent=1)
        print(f"report written to {args.record}")
    if hard_red or stale: return 1
    if assume: return 4
    if executed == 0 and not args.allow_all_skip: return 5
    return 0

if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
