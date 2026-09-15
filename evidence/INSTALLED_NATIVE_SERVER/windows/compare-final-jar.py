from pathlib import Path
import zipfile,hashlib,json,shutil,tomllib
root=Path(__file__).resolve().parents[2];out=root/'build/installed-native-server';old=root/'build/native-candidate-runtime/blockreality-0.4.0-dev.jar';new=root/'forge/build/libs/blockreality-0.4.0-dev.jar'
assert hashlib.sha256(old.read_bytes()).hexdigest()=='5a93c66bfe5f5a3dd8c1104a6e97fe000a2a0d2a8e66bf602821993d2a4c9b79'
with zipfile.ZipFile(old) as control, zipfile.ZipFile(new) as candidate:
    assert set(control.namelist())==set(candidate.namelist())
    changed=[n for n in control.namelist() if control.read(n)!=candidate.read(n)]
    assert changed==['META-INF/mods.toml'],changed
    doc=tomllib.loads(candidate.read('META-INF/mods.toml').decode('utf-8'));assert doc['mods'][0]['modId']=='blockreality'
    classes=[n for n in candidate.namelist() if n.endswith('.class')];assert len(classes)==204
    natives=[n for n in candidate.namelist() if n.endswith(('.so','.dll'))];assert len(natives)==2
    report={'source':'ae6a4eb','control_sha256':hashlib.sha256(old.read_bytes()).hexdigest(),'jar_sha256':hashlib.sha256(new.read_bytes()).hexdigest(),'jar_bytes':new.stat().st_size,'changed_entries':changed,'identical_class_entries':len(classes),'native_hashes':{n:hashlib.sha256(candidate.read(n)).hexdigest() for n in natives},'test_reuse':'NCR full Windows509/12SKIP, Linux520/1SKIP and protocol gates; all class/native/resource bytes except mods.toml identical'}
    (out/'final-mods.toml').write_bytes(candidate.read('META-INF/mods.toml'))
(out/'final-artifact.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
target=out/'blockreality-0.4.0-dev.jar';assert not target.exists();shutil.copy2(new,target)
print(json.dumps(report,indent=2))
