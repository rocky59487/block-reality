from pathlib import Path
import zipfile,hashlib,json,xml.etree.ElementTree as ET
root=Path('C:/Users/wmc02/Desktop/block-reality');out=root/'build/forge-axis-transactions'
old=zipfile.ZipFile(root/'build/live-chunk-capture/blockreality-0.4.0-dev-live-capture.jar')
jar=out/'blockreality-0.4.0-dev-axis-third.jar';new=zipfile.ZipFile(jar)
changed=[n for n in old.namelist() if n in new.namelist() and old.read(n)!=new.read(n)]
added=sorted(set(new.namelist())-set(old.namelist()));removed=sorted(set(old.namelist())-set(new.namelist()));assert not removed
for name in old.namelist():
    if name.startswith(('blockreality-engine/','META-INF/third_party/','META-INF/native-release/')) or name in ['META-INF/LICENSE','META-INF/NOTICE','META-INF/accesstransformer.cfg']:
        assert old.read(name)==new.read(name)
assert not any('testaxisprobe' in n for n in new.namelist())
identity={'source':'338c490','bytes':jar.stat().st_size,'sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'changed_entries':changed,'added_entries':added,'removed_entries':removed,
          'unchanged_entries':len(old.namelist())-len(changed),'unchanged_classes':sum(n.endswith('.class') and n not in changed for n in old.namelist()),'engine_licenses_provenance_and_AT_unchanged':True}
(out/'jar-identity-third.json').write_text(json.dumps(identity,indent=2));print(json.dumps(identity,indent=2))
counts={}
for phase in ['core-second','forge-third']:
    docs=[ET.parse(p).getroot() for p in (out/(phase+'-xml')).glob('TEST-*.xml')]
    counts[phase]={k:sum(int(d.attrib.get(k,0)) for d in docs) for k in ['tests','failures','errors','skipped']}
assert counts['core-second']=={'tests':477,'failures':0,'errors':0,'skipped':12}
assert counts['forge-third']=={'tests':145,'failures':0,'errors':0,'skipped':0}
(out/'windows-counts.json').write_text(json.dumps(counts,indent=2));print(counts)
