from pathlib import Path
import zipfile,json,hashlib,xml.etree.ElementTree as ET,subprocess
root=Path('C:/Users/wmc02/Desktop/block-reality');out=root/'build/live-chunk-capture'
old=zipfile.ZipFile(root/'build/bounded-chunk-read/blockreality-0.4.0-dev-bounded-chunk.jar')
jar=out/'blockreality-0.4.0-dev-live-capture.jar';new=zipfile.ZipFile(jar)
removed=sorted(set(old.namelist())-set(new.namelist()));added=sorted(set(new.namelist())-set(old.namelist()))
changed=[n for n in old.namelist() if n in new.namelist() and old.read(n)!=new.read(n)]
identity={'source':json.loads((out/'source.json').read_text())['source'],'jar_bytes':jar.stat().st_size,'jar_sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),
          'added_entries':added,'removed_entries':removed,'changed_entries':changed,'unchanged_entries':len(old.namelist())-len(changed),
          'unchanged_classes':sum(n.endswith('.class') for n in old.namelist() if n not in changed)}
(out/'jar-identity.json').write_text(json.dumps(identity,indent=2)+'\n');print(json.dumps(identity,indent=2))
assert not removed and changed==['com/blockreality/impl/server/construction/ChunkFileParticipant.class']
assert added==['com/blockreality/impl/server/construction/LiveChunkCapture.class']
assert not subprocess.check_output(['git','diff','4737e3c','HEAD','--','mod/core'],cwd=root)
