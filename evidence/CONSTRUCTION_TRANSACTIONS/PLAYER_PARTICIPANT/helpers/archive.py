from pathlib import Path
import json, hashlib, zipfile, shutil

root = Path.cwd(); work = root / 'build/construction-transactions/player-participant'
linux = Path('//wsl.localhost/Ubuntu-22.04/home/rocky/br-construction-transactions/qualification/player-participant')
mutations = Path('//wsl.localhost/Ubuntu-22.04/home/rocky/br-ct-player-mutations/qualification')
out = root / 'evidence/CONSTRUCTION_TRANSACTIONS/PLAYER_PARTICIPANT'; out.mkdir(parents=True)
(out / '.gitattributes').write_text('** -text\n', encoding='utf-8')
receipts = {}; digest = lambda data: hashlib.sha256(data).hexdigest()

def copy(path, name):
    target = out / name; target.parent.mkdir(parents=True, exist_ok=True)
    assert not target.exists(), target
    data = path.read_bytes(); target.write_bytes(data)
    receipts[name] = {'sha256': digest(data), 'bytes': len(data)}

def archive(path, name):
    target = out / name; target.parent.mkdir(parents=True, exist_ok=True)
    assert not target.exists(), target
    entries = {}
    with zipfile.ZipFile(target, 'w', compression=zipfile.ZIP_DEFLATED) as z:
        for file in sorted(path.rglob('*')):
            if not file.is_file(): continue
            assert not file.is_symlink(), file
            data = file.read_bytes(); rel = file.relative_to(path).as_posix()
            entries[rel] = {'sha256': digest(data), 'bytes': len(data)}; z.writestr(rel, data)
        z.writestr('__raw_file_manifest.json', json.dumps(entries, indent=2))
    with zipfile.ZipFile(target) as z:
        for rel, metadata in entries.items(): assert digest(z.read(rel)) == metadata['sha256']
    receipts[name] = {'sha256': digest(target.read_bytes()), 'bytes': target.stat().st_size, 'raw_files': len(entries), 'raw_file_bytes_preserved': True}

for name in ['player-compile-first.log', 'player-tests-first.log', 'player-core-first.log', 'forge-bounded.log', 'windows-core.log', 'windows-forge.log', 'windows-core-command.json', 'windows-forge-command.json', 'source-manifest.json', 'linux-overlay.json', 'test-counts.json', 'native-test-execution.json', 'jar-identity.json', 'jar-guard.json', 'cost-recorded.json', 'cost-windows.log', 'cost-windows-command.json', 'cost-windows-exit.json', 'upstream-v1.5-release.json', 'upstream-pr126.json']:
    copy(work / name, name)
for name in ['core-first-xml', 'forge-first-xml', 'windows-core-xml', 'windows-forge-xml', 'process', 'cost', 'javap']:
    archive(work / name, 'windows/' + name + '.zip')
for name in ['linux-core.log', 'linux-forge.log', 'linux-core-command.json', 'linux-forge-command.json', 'cost-linux.log', 'cost-linux-command.json', 'cost-linux-exit.json']:
    copy(linux / name, 'linux/' + name)
for name in ['linux-core-xml', 'linux-forge-xml', 'process', 'cost']:
    archive(linux / name, 'linux/' + name + '.zip')
archive(mutations, 'compiled-mutations.zip'); copy(mutations / 'summary.json', 'compiled-mutations-summary.json')
for name in ['run-full-first.py', 'run-full.py', 'run-cost.py', 'run-mutations.py', 'measure.gradle', 'archive.py']:
    copy(work / name, 'helpers/' + name)
archive(work / 'cost-src', 'helpers/cost-source.zip')
summary = {'status': 'PLAYER_FILE_ADAPTER_VERIFIED_FULL_CT_OPEN', 'source': '5d1fb6f4a02d3f082fd879690a04c216b5b319fa',
           'criteria': ['3db4e37', '60f02f6', 'CT_PLAYER_PARTICIPANT.md'], 'engine_qualified_in_this_step': '1.3.0/42e10f5',
           'tests': json.loads((work / 'test-counts.json').read_text()), 'jar': json.loads((work / 'jar-identity.json').read_text()),
           'completed_full_CT_gates': [], 'upstream_update': 'Engine v1.5 released; module Main PR126 merged. This step is not qualification of v1.5.'}
(out / 'summary.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
(out / 'receipts.json').write_text(json.dumps(receipts, indent=2), encoding='utf-8')
print('Archived', len(receipts), 'files;', sum(m['bytes'] for m in receipts.values()), 'bytes; every embedded raw-file digest verified')
