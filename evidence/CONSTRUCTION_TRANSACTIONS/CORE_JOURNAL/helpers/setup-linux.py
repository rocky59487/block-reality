from pathlib import Path
import tarfile,json,hashlib
root=Path('/home/rocky/br-construction-transactions'); root.mkdir()
source=Path('/mnt/c/Users/wmc02/Desktop/block-reality/build/construction-transactions')
with tarfile.open(source/'module-working-source.tar') as archive: archive.extractall(root,filter='data')
(root/'qualification').mkdir()
manifest=json.loads((source/'core-working-sources.json').read_text())
for name,expected in manifest.items():
    assert hashlib.sha256((root/name).read_bytes()).hexdigest()==expected, name
(root/'qualification/identity.json').write_text(json.dumps({'criteria':'1dafb52','base':'29c8d37','source_manifest_sha256':hashlib.sha256((source/'core-working-sources.json').read_bytes()).hexdigest(),'module_files':len(manifest),'engine_source':'42e10f5','native_readonly':True},indent=2))
print(root, len(manifest), 'source hashes match')
