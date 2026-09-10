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
assert not removed and set(changed)=={'com/blockreality/impl/server/construction/ChunkFileParticipant.class',
    'com/blockreality/impl/server/construction/ChunkFileParticipant$1.class',
    'com/blockreality/impl/server/construction/ChunkFileParticipant$Batch.class'}
assert added==['com/blockreality/impl/server/construction/LiveChunkCapture.class']
javap='C:/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot/bin/javap.exe'
companions={}
for name in changed:
    if '$' not in name:continue
    results=[]
    for label,z in [('before',old),('after',new)]:
        folder=out/('javap-'+label);folder.mkdir(exist_ok=True);file=folder/Path(name).name;file.write_bytes(z.read(name))
        result=subprocess.check_output([javap,'-p','-c','-s',str(file)]);(folder/(file.stem+'.txt')).write_bytes(result);results.append(result)
    assert results[0]==results[1];companions[name]='identical javap -p -c -s; debug line metadata differs'
identity['companion_bytecode']=companions
(out/'jar-identity.json').write_text(json.dumps(identity,indent=2)+'\n')
assert not subprocess.check_output(['git','diff','4737e3c','HEAD','--','mod/core/src/main','mod/core/src/test','mod/core/src/testFixtures','mod/core/build.gradle'],cwd=root)
