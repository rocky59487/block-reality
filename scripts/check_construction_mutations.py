#!/usr/bin/env python3
"""CI-5: compile two behavioral fault arms in a disposable source copy, leaving the checkout intact."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    out = Path(args.out).resolve(); out.mkdir(parents=True, exist_ok=True)
    arms = [
        ('identity-reuse', 'mod', 'core/src/main/java/com/blockreality/core/world/ConstructionLedger.java',
         'if (!work.destroyed().contains(p)) continuous.add(owner);',
         'if (true) continuous.add(owner);',
         ':core:test', '*ConstructionLedgerTest.fullDestructionAndSameTickReplacementCannotResurrectIdentityEvenAfterPendingSave',
         'core/build/test-results/test', 'fullDestructionAndSameTickReplacementCannotResurrectIdentityEvenAfterPendingSave()'),
        ('object-schema', 'forge', 'src/main/java/com/blockreality/impl/server/WorldIndexData.java',
         'tag.getInt("objectsFormat") != 1', 'false',
         'test', '*WorldIndexDataTest.unknownObjectSchemaCannotFallBackToCoverageOrOverwriteFile',
         'build/test-results/test', 'unknownObjectSchemaCannotFallBackToCoverageOrOverwriteFile()')]
    results = []
    (root / 'build').mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='ci-mutants-', dir=root / 'build') as temporary:
        work = Path(temporary)
        assert work.resolve().is_relative_to((root / 'build').resolve())
        for name in ['mod', 'forge', 'contract', 'gradle', 'scripts']:
            shutil.copytree(root / name, work / name, ignore=shutil.ignore_patterns('build', '.gradle', 'run', '__pycache__'))
        for name in ['LICENSE', 'NOTICE']:
            shutil.copy2(root / name, work / name)
        for name, project, relative, old, new, task, test, xmlpath, oracle in arms:
            source = work / project / relative; original = source.read_text(encoding='utf-8')
            assert original.count(old) == 1
            source.write_text(original.replace(old, new), encoding='utf-8')
            wrapper = ['cmd', '/c', 'gradlew.bat'] if os.name == 'nt' else ['bash', 'gradlew']
            with (out / (name + '.log')).open('w', encoding='utf-8') as log:
                run = subprocess.run(wrapper + [task, '--tests', test, '--console=plain'],
                                     cwd=work / project, stdout=log, stderr=subprocess.STDOUT)
            source.write_text(original, encoding='utf-8')
            failures = []
            for path in (work / project / xmlpath).glob('TEST-*.xml'):
                tree = ET.parse(path)
                for case in tree.findall('.//testcase'):
                    if case.get('name') == oracle and case.find('failure') is not None:
                        failures.append({'test': case.get('name'), 'failure': case.find('failure').get('type')})
                shutil.copy2(path, out / (name + '-' + path.name))
            passed = run.returncode != 0 and len(failures) == 1 and failures[0]['failure'] == 'org.opentest4j.AssertionFailedError'
            results.append({'arm': name, 'exit': run.returncode, 'behavioral_oracle': failures, 'pass': passed})
            print(name + ': ' + ('PASS (compiled fault rejected)' if passed else 'FAIL'), flush=True)
    (out / 'results.json').write_text(json.dumps(results, indent=2) + '\n')
    assert all(r['pass'] for r in results), 'compiler failures or missing behavioral oracles do not pass'


if __name__ == '__main__':
    main()
