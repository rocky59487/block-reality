#!/usr/bin/env python3
"""NO-2: inspect the shipping jar, including dormant references in class constant pools."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import zipfile


FORBIDDEN = (
    "com/blockreality/api/StressFieldSpec", "com/blockreality/api/ShellFieldSpec",
    "com/blockreality/core/protocol/ProtocolCodec", "com/blockreality/core/protocol/BinaryCodec",
    "com/blockreality/core/sidecar/SidecarClient", "com/blockreality/core/sidecar/SidecarProcess",
    "com/blockreality/core/sidecar/SidecarConfig", "com/blockreality/core/sidecar/ShmRegion",
    "com/blockreality/testlegacy/", "com/blockreality/testfixtures/",
    "com/blockreality/core/world/RegistryScaling",
    "com/blockreality/impl/server/RegistrySaveProfile",
    "com/blockreality/impl/server/ConstructionProbe",
    "com/blockreality/impl/server/WorldRegistryProbe",
    "com/blockreality/impl/server/PipelineProbe",
    "com/blockreality/impl/net/StateDeliveryProbe",
    "com/blockreality/impl/client/ClientRenderProbe",
    "com/blockreality/impl/client/MaterialGeometryProbe",
    "com/blockreality/impl/server/RenderServerProbe",
    "com/blockreality/core/transaction/TransactionProcess",
    "com/blockreality/core/transaction/TransactionFixtures",
)
REQUIRED = {"com/blockreality/api/" + name + ".class"
            for name in ("AnalysisResult", "MemberSnapshot", "ShellSnapshot")}


def utf8_constants(data):
    """Read every CONSTANT_Utf8, including descriptors not referenced by executed methods."""
    offset = 0

    def take(size):
        nonlocal offset
        if offset + size > len(data):
            raise ValueError("truncated class constant pool")
        value = data[offset:offset + size]
        offset += size
        return value

    if take(4) != b"\xca\xfe\xba\xbe":
        raise ValueError("invalid class magic")
    take(4)  # minor + major
    count = struct.unpack(">H", take(2))[0]
    index = 1
    constants = []
    while index < count:
        tag = take(1)[0]
        if tag == 1:
            size = struct.unpack(">H", take(2))[0]
            # Modified UTF-8 differs for NUL/surrogates, never for the ASCII names checked here.
            constants.append(take(size).decode("utf-8", errors="replace"))
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            take(4)
        elif tag in (5, 6):
            take(8)
            index += 1
        elif tag in (7, 8, 16, 19, 20):
            take(2)
        elif tag == 15:
            take(3)
        else:
            raise ValueError(f"unknown class constant tag {tag}")
        index += 1
    return constants


def inspect_jar(path):
    errors = []
    classes = 0
    with zipfile.ZipFile(path) as jar:
        names = jar.namelist()
        if len(names) != len(set(names)):
            errors.append("duplicate jar entries")
        for missing in sorted(REQUIRED - set(names)):
            errors.append("missing shipping snapshot: " + missing)
        for name in names:
            for banned in FORBIDDEN:
                if banned in name:
                    errors.append("forbidden entry: " + name)
            if not name.endswith(".class"):
                continue
            classes += 1
            try:
                constants = utf8_constants(jar.read(name))
            except ValueError as error:
                errors.append(f"{name}: {error}")
                continue
            for banned in FORBIDDEN:
                if any(banned in value for value in constants):
                    errors.append(f"forbidden constant: {name} -> {banned}")
    return {"classes": classes, "errors": errors}


def mutation_checks(jar_path, javac):
    """Compiled legacy entry, dormant reference and construction crash-driver entry negatives."""
    with tempfile.TemporaryDirectory(prefix="br-native-only-") as temp:
        work = Path(temp)
        legacy = work / "Forbidden.java"
        legacy.write_text("package com.blockreality.testlegacy; public class Forbidden {}", encoding="utf-8")
        reference = work / "ResurrectedReference.java"
        reference.write_text("package com.blockreality.impl; public class ResurrectedReference {"
                             "public com.blockreality.testlegacy.Forbidden dependency;}", encoding="utf-8")
        process = work / "TransactionProcessLeak.java"
        process.write_text("package com.blockreality.core.transaction; public class TransactionProcessLeak {}", encoding="utf-8")
        compilation = subprocess.run([str(javac), "--release", "17", "-d", str(work),
                                      str(legacy), str(reference), str(process)], capture_output=True, text=True)
        if compilation.returncode:
            raise RuntimeError("mutation compilation failed; not an oracle: " + compilation.stderr)
        reports = []
        for arm, name, expected in (
                ("bundled-legacy", "com/blockreality/testlegacy/Forbidden.class", "forbidden entry:"),
                ("dormant-reference", "com/blockreality/impl/ResurrectedReference.class", "forbidden constant:"),
                ("construction-process-driver", "com/blockreality/core/transaction/TransactionProcessLeak.class", "forbidden entry:")):
            mutated = work / (arm + ".jar")
            shutil.copyfile(jar_path, mutated)
            with zipfile.ZipFile(mutated, "a") as jar:
                jar.write(work / name, name)
            result = inspect_jar(mutated)
            if not any(error.startswith(expected) for error in result["errors"]):
                raise AssertionError(f"{arm} escaped the artifact gate: {result}")
            if arm == "dormant-reference" and any("forbidden entry:" in e for e in result["errors"]):
                raise AssertionError("reference arm accidentally bundled the forbidden class")
            reports.append({"arm": arm, "compileExit": compilation.returncode, "refused": result["errors"]})
        return reports


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path)
    parser.add_argument("--mutation-checks", action="store_true")
    parser.add_argument("--javac", type=Path)
    args = parser.parse_args()
    report = inspect_jar(args.jar)
    report["sha256"] = hashlib.sha256(args.jar.read_bytes()).hexdigest()
    if report["errors"]:
        print(json.dumps(report, indent=2))
        return 1
    if args.mutation_checks:
        javac = args.javac
        if javac is None:
            java_home = os.environ.get("JAVA_HOME")
            javac = Path(java_home) / "bin" / ("javac.exe" if os.name == "nt" else "javac") if java_home else shutil.which("javac")
        if not javac or not Path(javac).is_file():
            raise RuntimeError("NO-2 mutation checks require javac; cannot skip")
        report["mutations"] = mutation_checks(args.jar, javac)
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
