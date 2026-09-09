#!/usr/bin/env python3
"""Compile isolated consumer mutations; never edit the working Java sources."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "mod/core/src/main/java/com/blockreality/core/bsi/BsiAnalysisResult.java"
MUTATIONS = {
    "DROP_ISLANDS": ('snapshot.islands().stream().map(row ->', 'snapshot.islands().stream().limit(0).map(row ->'),
    "WRONG_ID": ('new IslandBuckling(row.island(),', 'new IslandBuckling(0,'),
}


def run(args):
    out = Path(args.out).resolve()
    out.mkdir(parents=True, exist_ok=False)
    source = SOURCE.read_text(encoding="utf-8")
    results = {}
    for arm, (old, new) in MUTATIONS.items():
        assert source.count(old) == 1, (arm, "mutation anchor drift")
        directory = out / arm
        directory.mkdir()
        (directory / "BsiAnalysisResult.java").write_text(source.replace(old, new), encoding="utf-8")
        path = directory.as_posix().replace("'", "\\'")
        init = directory / "mutation.gradle"
        init.write_text("""gradle.projectsEvaluated {
    def p = gradle.rootProject.project(':core')
    def compiled = p.tasks.register('compileAggregationMutation', JavaCompile) {
        dependsOn p.tasks.named('classes')
        source = p.files('%s/BsiAnalysisResult.java')
        classpath = p.sourceSets.main.compileClasspath + p.sourceSets.main.output
        destinationDirectory = p.file('%s/classes')
        options.release = 17
    }
    p.tasks.register('aggregationMutation', Test) {
        dependsOn compiled, p.tasks.named('testClasses')
        testClassesDirs = p.sourceSets.test.output.classesDirs
        classpath = p.files('%s/classes') + p.sourceSets.test.runtimeClasspath
        useJUnitPlatform()
        filter { includeTestsMatching 'com.blockreality.core.bsi.BsiAnalysisResultTest' }
        reports.junitXml.outputLocation = p.file('%s/results')
        reports.html.required = false
    }
}
""" % (path, path, path, path), encoding="utf-8")
        cmd = (["cmd", "/c", "gradlew.bat"] if os.name == "nt" else ["bash", "gradlew"])
        result = subprocess.run(cmd + ["-I", str(init), ":core:aggregationMutation", "--console=plain"],
                                cwd=ROOT / "mod", capture_output=True)
        (directory / "run.txt").write_bytes(result.stdout + result.stderr)
        tests, failed = [], []
        for file in sorted((directory / "results").glob("TEST-*.xml")):
            for test in ET.parse(file).getroot().findall("testcase"):
                tests.append(test.attrib["name"])
                if test.find("failure") is not None or test.find("error") is not None:
                    failed.append(test.attrib["name"])
        if result.returncode != 1 or not failed:
            raise RuntimeError(f"{arm}: not a named test failure; exit={result.returncode}, failures={failed}")
        results[arm] = {"checks": tests, "failed": failed}
        print(f"{arm}: {len(tests)} tests / {len(failed)} FAIL", flush=True)
    (out / "counts.json").write_text(json.dumps(results, indent=2) + "\n")
    if args.expected and results != json.loads(Path(args.expected).read_text()):
        raise RuntimeError("named Java mutation outcomes differ from frozen counts")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", required=True)
    parser.add_argument("--expected")
    run(parser.parse_args())
