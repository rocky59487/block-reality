from pathlib import Path
import zipfile,json,hashlib,xml.etree.ElementTree as ET
root=Path('C:/Users/wmc02/Desktop/block-reality');out=root/'build/ct-journal-bootstrap'
linux=Path('//wsl.localhost/Ubuntu-22.04/home/rocky/br-ct-journal-bootstrap')
counts={}
for platform,base in [('windows',root),('linux',linux)]:
    counts[platform]={}
    for phase,folder in [('core','mod/core'),('forge','forge')]:
        docs=[ET.parse(p).getroot() for p in (base/folder/'build/test-results/test').glob('TEST-*.xml')]
        counts[platform][phase]={k:sum(int(d.attrib.get(k,0)) for d in docs) for k in ['tests','failures','errors','skipped']}
        assert counts[platform][phase]['tests']==(454 if phase=='core' else 124)
        assert not counts[platform][phase]['failures'] and not counts[platform][phase]['errors']
old=zipfile.ZipFile(root/'build/native-verdict-cleanup/blockreality-0.4.0-dev-native-verdict.jar')
jar=out/'blockreality-0.4.0-dev-ct-bootstrap.jar';new=zipfile.ZipFile(jar)
assert set(old.namelist())==set(new.namelist())
changed=[n for n in old.namelist() if old.read(n)!=new.read(n)]
identity={'source':'471fad9','jar_bytes':jar.stat().st_size,'jar_sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),
          'changed_entries':changed,'unchanged_entries':len(new.namelist())-len(changed)}
(out/'jar-identity.json').write_text(json.dumps(identity,indent=2)+'\n')
(out/'counts.json').write_text(json.dumps(counts,indent=2)+'\n')
print(json.dumps({'artifact':identity,'counts':counts},indent=2))
assert changed==['com/blockreality/core/transaction/FileTransactionJournal.class']
