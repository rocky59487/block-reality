from pathlib import Path
import subprocess, tarfile, hashlib, json, shutil, difflib, xml.etree.ElementTree as ET

root = Path('/home/rocky/br-ct-player-mutations'); root.mkdir()
workspace = Path('/mnt/c/Users/wmc02/Desktop/block-reality/build/construction-transactions/player-participant')
with tarfile.open(workspace / 'source.tar') as archive: archive.extractall(root, filter='data')
for name, digest in json.loads((workspace / 'source-manifest.json').read_text()).items():
    assert hashlib.sha256((root / name).read_bytes()).hexdigest() == digest
out = root / 'qualification'; out.mkdir()
coordinator = root / 'mod/core/src/main/java/com/blockreality/core/transaction/AtomicConstructionCoordinator.java'
player = root / 'forge/src/main/java/com/blockreality/impl/server/construction/PlayerFileParticipant.java'
inventory = root / 'forge/src/main/java/com/blockreality/impl/server/construction/PlayerInventoryImage.java'
original = {p: p.read_text() for p in [coordinator, player, inventory]}
arms = [
 ('skip-checkpoint', 'core', coordinator, '                host.checkpoint(resources(intent));', '                // Mutant: omitted durable baseline checkpoint.', 'com.blockreality.core.transaction.AtomicConstructionCoordinatorTest.checkpointMakesUnsavedBaselineDurableBeforePreparedAndIsNeverRepeatedForReplay'),
 ('lose-unrelated-player-data', 'forge', player, '        validateActor(desired, before.actor);', '        desired = desired.copy(); desired.remove("ForgeData");\n        validateActor(desired, before.actor);', 'com.blockreality.impl.server.construction.PlayerFileParticipantTest.exactFullPlayerSurvivesVanillaAndOfflineReloadWithInventoryOnlyChange'),
 ('accept-duplicate-slots', 'forge', inventory, '            if (slots.put(slot(item), item) != null) throw new IOException("Duplicate player inventory slot");', '            slots.put(slot(item), item); // Mutant: accept ambiguous inventory.', 'com.blockreality.impl.server.construction.PlayerInventoryImageTest.rejectsDuplicateUnknownMistypedAndMalformedInventoryWithoutMutation')
]

def run(name, project, selector):
    dest = out / name; dest.mkdir()
    cwd = root / ('mod' if project == 'core' else 'forge')
    command = ['bash', 'gradlew', '--no-daemon', ':core:test' if project == 'core' else 'test', '--tests', selector, '--console=plain']
    (dest / 'command.json').write_text(json.dumps(command))
    with (dest / 'gradle.log').open('x') as f: result = subprocess.run(command, cwd=cwd, stdout=f, stderr=subprocess.STDOUT)
    xml = cwd / ('core/build/test-results/test' if project == 'core' else 'build/test-results/test')
    if xml.exists(): shutil.copytree(xml, dest / 'xml')
    cases = [c for p in (dest / 'xml').glob('TEST-*.xml') for c in ET.parse(p).getroot().findall('testcase')]
    return result, cases, dest

report = []
for project, count, selector in [('core', 30, 'com.blockreality.core.transaction.*'), ('forge', 15, 'com.blockreality.impl.server.construction.*')]:
    result, cases, dest = run('control-' + project, project, selector)
    assert result.returncode == 0 and len(cases) == count and all(c.find('failure') is None and c.find('skipped') is None for c in cases)
try:
    for name, project, path, old, new, selector in arms:
        source = original[path]; assert source.count(old) == 1
        changed = source.replace(old, new); path.write_text(changed)
        result, cases, dest = run(name, project, selector)
        (dest / 'mutation.diff').write_text(''.join(difflib.unified_diff(source.splitlines(True), changed.splitlines(True), fromfile=str(path.relative_to(root)), tofile=str(path.relative_to(root)))))
        failures = [c for c in cases if c.find('failure') is not None]
        assert result.returncode != 0 and len(cases) == 1 and len(failures) == 1, name
        assert failures[0].find('failure').get('type') == 'org.opentest4j.AssertionFailedError', name
        assert 'compileJava FAILED' not in (dest / 'gradle.log').read_text(), name
        report.append({'arm': name, 'compiled': True, 'caught_by': failures[0].get('name'), 'failure_type': failures[0].find('failure').get('type')})
        path.write_text(source); print('CAUGHT', name, flush=True)
finally:
    for path, source in original.items(): path.write_text(source)
for project, count, selector in [('core', 30, 'com.blockreality.core.transaction.*'), ('forge', 15, 'com.blockreality.impl.server.construction.*')]:
    result, cases, dest = run('restored-' + project, project, selector)
    assert result.returncode == 0 and len(cases) == count and all(c.find('failure') is None and c.find('skipped') is None for c in cases)
(out / 'summary.json').write_text(json.dumps({'source': '5d1fb6f4a02d3f082fd879690a04c216b5b319fa', 'control': 45, 'arms': report, 'restored': 45, 'status': 'PASS'}, indent=2))
print('Control45; three compiled behavioral negatives caught; restored45 PASS')
