#!/usr/bin/env python3
"""Run three isolated, named Java input mutations without editing production sources."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
CORE = ROOT / "mod/core/src/main/java/com/blockreality/core"
ARMS = {
    "ID_ORDER": ("bsi/BsiVocabulary.java", "return e.getKey();", "return 0;",
                 "bsi.BsiVocabularyTest.usesReturnedIdsInsteadOfDeclarationOrder"),
    "SWAP_YZ": ("engine/GameWorldSnapshot.java", "cell.axis(), cell.joint()",
                "(cell.axis() == 1 ? 2 : cell.axis() == 2 ? 1 : 0), cell.joint()",
                "engine.GameWorldSnapshotTest.preservesDeclaredAxesRotationJointAndSiValues"),
    "DROP_GROUND": ("engine/GameWorldSnapshot.java", "for (var p : ground) blocks.add",
                    "for (var p : ground.stream().limit(0).toList()) blocks.add",
                    "engine.GameWorldSnapshotTest.includesAllSixObservedContactsOnceInCanonicalOrder"),
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    out = Path(args.out).resolve()
    out.mkdir(parents=True, exist_ok=False)
    results = {}
    for arm, (file, old, new, test) in ARMS.items():
        source = (CORE / file).read_text(encoding="utf-8")
        if source.count(old) != 1:
            raise RuntimeError(f"{arm}: mutation anchor drift")
        directory = out / arm
        directory.mkdir()
        (directory / Path(file).name).write_text(source.replace(old, new), encoding="utf-8")
        path = directory.as_posix().replace("'", "\\'")
        init = directory / "mutation.gradle"
        init.write_text("""gradle.projectsEvaluated {
    def p = gradle.rootProject.project(':core')
    def compiled = p.tasks.register('compileGameInputMutation', JavaCompile) {
        dependsOn p.tasks.named('classes')
        source = p.files('%s/%s')
        classpath = p.sourceSets.main.compileClasspath + p.sourceSets.main.output
        destinationDirectory = p.file('%s/classes')
        options.release = 17
    }
    p.tasks.register('gameInputMutation', Test) {
        dependsOn compiled, p.tasks.named('testClasses')
        testClassesDirs = p.sourceSets.test.output.classesDirs
        classpath = p.files('%s/classes') + p.sourceSets.test.runtimeClasspath
        useJUnitPlatform()
        filter { includeTestsMatching 'com.blockreality.core.%s' }
        reports.junitXml.outputLocation = p.file('%s/results')
        reports.html.required = false
    }
}
""" % (path, Path(file).name, path, path, test, path), encoding="utf-8")
        wrapper = ["cmd", "/c", "gradlew.bat"] if os.name == "nt" else ["bash", "gradlew"]
        completed = subprocess.run(wrapper + ["-I", str(init), ":core:gameInputMutation", "--console=plain"],
                                   cwd=ROOT / "mod", capture_output=True)
        (directory / "run.txt").write_bytes(completed.stdout + completed.stderr)
        cases = [case for file in sorted((directory / "results").glob("TEST-*.xml"))
                 for case in ET.parse(file).getroot().findall("testcase")]
        expected = test.rsplit(".", 1)[1] + "()"
        failures = [(case.attrib["name"], case.find("failure")) for case in cases]
        if (completed.returncode != 1 or len(cases) != 1 or failures[0][0] != expected
                or failures[0][1] is None
                or failures[0][1].attrib.get("type") != "org.opentest4j.AssertionFailedError"):
            raise RuntimeError(f"{arm}: expected one named assertion FAIL; see {directory}")
        results[arm] = {"registered": 1, "failed": expected, "type": failures[0][1].attrib["type"]}
        print(f"{arm}: 1 registered / 1 named assertion FAIL", flush=True)
    (out / "counts.json").write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
