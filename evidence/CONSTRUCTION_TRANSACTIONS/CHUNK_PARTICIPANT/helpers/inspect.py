from pathlib import Path
import zipfile,json,hashlib,xml.etree.ElementTree as ET
root=Path('C:/Users/wmc02/Desktop/block-reality');out=root/'build/ct-chunk-storage'
linux=Path('//wsl.localhost/Ubuntu-22.04/home/rocky/br-ct-chunk-storage')
counts={}
for platform,base in [('windows',root),('linux',linux)]:
    docs=[ET.parse(p).getroot() for p in (base/'forge/build/test-results/test').glob('TEST-*.xml')]
    counts[platform]={k:sum(int(d.attrib.get(k,0)) for d in docs) for k in ['tests','failures','errors','skipped']}
    assert counts[platform]=={'tests':131,'failures':0,'errors':0,'skipped':0}
old=zipfile.ZipFile(root/'build/manufactured-metadata/blockreality-0.4.0-dev-manufactured-metadata.jar')
jar=out/'blockreality-0.4.0-dev-chunk-storage.jar';new=zipfile.ZipFile(jar)
removed=sorted(set(old.namelist())-set(new.namelist()));added=sorted(set(new.namelist())-set(old.namelist()))
changed=[n for n in old.namelist() if n in new.namelist() and old.read(n)!=new.read(n)]
identity={'source':'70d1112','jar_bytes':jar.stat().st_size,'jar_sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),
          'added_entries':added,'removed_entries':removed,'changed_entries':changed,'unchanged_entries':len(old.namelist())-len(changed),
          'unchanged_classes':sum(n.endswith('.class') for n in old.namelist() if n not in changed)}
(out/'jar-identity.json').write_text(json.dumps(identity,indent=2)+'\n');(out/'counts.json').write_text(json.dumps(counts,indent=2)+'\n')
print(json.dumps({'artifact':identity,'forge_counts':counts},indent=2))
assert not removed and not changed
assert len(added)==4 and all(n.startswith('com/blockreality/impl/server/construction/ChunkFileParticipant') and n.endswith('.class') for n in added)
