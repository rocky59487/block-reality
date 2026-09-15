from pathlib import Path
import zipfile,json,hashlib,xml.etree.ElementTree as ET
root=Path('C:/Users/wmc02/Desktop/block-reality');out=root/'build/manufactured-metadata'
linux=Path('//wsl.localhost/Ubuntu-22.04/home/rocky/br-manufactured-metadata')
counts={}
for platform,base in [('windows',root),('linux',linux)]:
    counts[platform]={}
    for phase,folder in [('core','mod/core'),('forge','forge')]:
        docs=[ET.parse(p).getroot() for p in (base/folder/'build/test-results/test').glob('TEST-*.xml')]
        counts[platform][phase]={k:sum(int(d.attrib.get(k,0)) for d in docs) for k in ['tests','failures','errors','skipped']}
        assert counts[platform][phase]['tests']==(472 if phase=='core' else 124)
        assert not counts[platform][phase]['failures'] and not counts[platform][phase]['errors']
old=zipfile.ZipFile(root/'build/ct-journal-bootstrap/blockreality-0.4.0-dev-ct-bootstrap.jar')
jar=out/'blockreality-0.4.0-dev-manufactured-metadata.jar';new=zipfile.ZipFile(jar)
removed=sorted(set(old.namelist())-set(new.namelist()))
added=sorted(set(new.namelist())-set(old.namelist()))
changed=[n for n in old.namelist() if n in new.namelist() and old.read(n)!=new.read(n)]
identity={'source':'4737e3c','jar_bytes':jar.stat().st_size,'jar_sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),
          'added_entries':added,'removed_entries':removed,'changed_entries':changed,'unchanged_entries':len(old.namelist())-len(changed),
          'unchanged_classes':sum(n.endswith('.class') for n in old.namelist() if n not in changed)}
(out/'jar-identity.json').write_text(json.dumps(identity,indent=2)+'\n')
(out/'counts.json').write_text(json.dumps(counts,indent=2)+'\n')
print(json.dumps({'artifact':identity,'counts':counts},indent=2))
assert not removed and not changed
assert all(n.startswith('com/blockreality/core/transaction/Manufactured') or n.startswith('com/blockreality/core/transaction/MetadataCodec') for n in added)
assert all(n.endswith('.class') for n in added)
