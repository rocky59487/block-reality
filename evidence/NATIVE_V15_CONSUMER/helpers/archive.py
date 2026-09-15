from pathlib import Path
import hashlib, json, zipfile, tarfile, shutil

root = Path.cwd(); work = root / 'build/native-v1.5-consumer'
linux = Path('//wsl.localhost/Ubuntu-22.04/home/rocky/br-native-v15-consumer/qualification')
out = root / 'evidence/NATIVE_V15_CONSUMER'; out.mkdir(parents=True)
(out / '.gitattributes').write_bytes(b'** -text\n')
receipts = {}; sha = lambda data: hashlib.sha256(data).hexdigest()

def copy(path, name):
    target = out / name; target.parent.mkdir(parents=True, exist_ok=True); assert not target.exists(), target
    data = path.read_bytes(); target.write_bytes(data); receipts[name] = {'bytes': len(data), 'sha256': sha(data)}

def archive(path, name, replay=False):
    target = out / name; target.parent.mkdir(parents=True, exist_ok=True); assert not target.exists(), target
    entries = {}; omitted = []
    with zipfile.ZipFile(target, 'w', compression=zipfile.ZIP_DEFLATED) as z:
        for p in sorted(path.rglob('*')):
            if not p.is_file(): continue
            assert not p.is_symlink(), p
            rel = p.relative_to(path).as_posix()
            if replay and p.suffix not in ['.frame', '.json', '.log', '.txt']:
                omitted.append({'path': rel, 'bytes': p.stat().st_size() if callable(p.stat().st_size) else p.stat().st_size})
                continue
            data = p.read_bytes(); z.writestr(rel, data); entries[rel] = {'bytes': len(data), 'sha256': sha(data)}
        z.writestr('__raw_file_manifest.json', json.dumps(entries, indent=2))
        if omitted:
            z.writestr('__selection.json', json.dumps({'retained': 'all .frame/.json/.log/.txt original bytes', 'omitted': omitted,
                        'scope': 'duplicate extracted libraries, generated corrupt jars and rendezvous marker bytes remain in the original owned build directory; published assets and final jar are separately hash-pinned'}, indent=2))
    with zipfile.ZipFile(target) as z:
        for rel, metadata in entries.items(): assert sha(z.read(rel)) == metadata['sha256']
    receipts[name] = {'bytes': target.stat().st_size, 'sha256': sha(target.read_bytes()), 'raw_files': len(entries), 'omitted_files': len(omitted)}

for p in sorted(work.iterdir()):
    if p.is_file() and p.suffix in ['.log', '.json']:
        copy(p, 'windows/' + p.name)
for p in sorted(linux.iterdir()):
    if p.is_file() and p.suffix in ['.log', '.json', '.txt']:
        copy(p, 'linux/' + p.name)
for platform, base in [('windows', work), ('linux', linux)]:
    for project in ['core', 'forge']: archive(base / (platform + '-' + project + '-xml'), platform + '/' + project + '-xml.zip')
    for path in sorted(base.glob('replay-*')):
        if path.is_dir(): archive(path, platform + '/' + path.name + '.zip', replay=True)
archive(linux / 'incremental-notice', 'incremental-notice.zip')
copy(work / 'archive-guards/verification.json', 'archive-guards/verification.json')
for case in sorted((work / 'archive-guards').iterdir()):
    if case.is_dir(): copy(case / 'SHA256SUMS', 'archive-guards/' + case.name + '-SHA256SUMS')
copy(work / 'download/SHA256SUMS', 'release/SHA256SUMS')
for name in ['upstream-v1.5-release.json', 'upstream-pr126.json']:
    copy(root / 'build/construction-transactions/player-participant' / name, 'release/' + name)
with tarfile.open(work / 'download/tectonic2-1.5.0-source.tar.gz') as source:
    for name in ['SOURCE_MANIFEST.json', 'SOURCE_REVISION']:
        data = source.extractfile(name).read(); target = out / 'release' / name; target.write_bytes(data)
        receipts['release/' + name] = {'bytes': len(data), 'sha256': sha(data)}
for p in sorted((work / 'staged').rglob('*')):
    if p.is_file() and p.suffix not in ['.dll', '.so']: copy(p, 'staged-metadata/' + p.relative_to(work / 'staged').as_posix())
for name in ['run.py', 'run-before-jna-fix.py', 'incremental-notice.py', 'setup-installed.py', 'launch-installed.py', 'archive.py']:
    copy(work / name, 'helpers/' + name)
(out / 'receipts.json').write_text(json.dumps(receipts, indent=2), encoding='utf-8')
print('Archived', len(receipts), 'files;', sum(v['bytes'] for v in receipts.values()), 'bytes; raw selections and SHA manifests verified')
