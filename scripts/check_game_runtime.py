#!/usr/bin/env python3
"""Compile isolated lifecycle/placement mutations and require each named assertion to fail."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RUNTIME = "mod/core/src/main/java/com/blockreality/core/engine/NativeGameRuntime.java"
AXIS = "forge/src/main/java/com/blockreality/impl/block/StructuralBlock.java"
ARMS = {
    "OFF_LOAD": (RUNTIME, "mod", ":core", "this.clock = Objects.requireNonNull(clock);",
                 "this.clock = Objects.requireNonNull(clock); if (!enabled) factory.open();",
                 "com.blockreality.core.engine.NativeGameRuntimeTest.offNeverCallsTheLoader"),
    "CLOSE_IGNORED": (RUNTIME, "mod", ":core", "closing = true; generation++;", "closing = false;",
                      "com.blockreality.core.engine.NativeGameRuntimeTest.closeRevokesBlockedResultsWithoutClosingAnActiveNativeCall"),
    "UNDECLARED_Y": (AXIS, "forge", ":",
                     'if (this == UNDECLARED) throw new IllegalStateException("placement axis is undeclared");',
                     "if (this == UNDECLARED) return 1;",
                     "com.blockreality.impl.PlacementAxisTest.undeclaredLegacyBlocksNeverInventAWorldAxis"),
}


def run_mutations(arms, description=__doc__):
    parser = argparse.ArgumentParser(description=description)
    parser.add_argument("--out", required=True)
    out = Path(parser.parse_args().out).resolve()
    out.mkdir(parents=True, exist_ok=False)
    results = {}
    for arm, (file, project, target, old, new, test) in arms.items():
        source = (ROOT / file).read_text(encoding="utf-8")
        if source.count(old) != 1:
            raise RuntimeError(f"{arm}: mutation anchor drift")
        directory = out / arm
        directory.mkdir()
        (directory / Path(file).name).write_text(source.replace(old, new), encoding="utf-8")
        path = directory.as_posix().replace("'", "\\'")
        init = directory / "mutation.gradle"
        init.write_text("""gradle.projectsEvaluated {
    def p = gradle.rootProject.project('%s')
    def compiled = p.tasks.register('compileGameRuntimeMutation', JavaCompile) {
        dependsOn p.tasks.named('classes')
        source = p.files('%s/%s')
        classpath = p.sourceSets.main.compileClasspath + p.sourceSets.main.output
        destinationDirectory = p.file('%s/classes')
        options.release = 17
    }
    p.tasks.register('gameRuntimeMutation', Test) {
        dependsOn compiled, p.tasks.named('testClasses')
        testClassesDirs = p.sourceSets.test.output.classesDirs
        classpath = p.files('%s/classes') + p.sourceSets.test.runtimeClasspath
        useJUnitPlatform()
        filter { includeTestsMatching '%s' }
        reports.junitXml.outputLocation = p.file('%s/results')
        reports.html.required = false
    }
}
""" % (target, path, Path(file).name, path, path, test, path), encoding="utf-8")
        wrapper = ["cmd", "/c", "gradlew.bat"] if os.name == "nt" else ["bash", "gradlew"]
        task = (target if target != ":" else "") + ":gameRuntimeMutation"
        completed = subprocess.run(wrapper + ["-I", str(init), task, "--console=plain"],
                                   cwd=ROOT / project, capture_output=True)
        (directory / "run.txt").write_bytes(completed.stdout + completed.stderr)
        cases = [case for file in sorted((directory / "results").glob("TEST-*.xml"))
                 for case in ET.parse(file).getroot().findall("testcase")]
        expected = test.rsplit(".", 1)[1] + "()"
        if (completed.returncode != 1 or len(cases) != 1 or cases[0].attrib["name"] != expected
                or cases[0].find("failure") is None
                or cases[0].find("failure").attrib.get("type") != "org.opentest4j.AssertionFailedError"):
            raise RuntimeError(f"{arm}: expected one named assertion FAIL; see {directory}")
        results[arm] = {"registered": 1, "failed": expected, "type": cases[0].find("failure").attrib["type"]}
        print(f"{arm}: 1 registered / 1 named assertion FAIL", flush=True)
    (out / "counts.json").write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    run_mutations(ARMS)
