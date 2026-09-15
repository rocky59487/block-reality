from pathlib import Path
import json,hashlib,zipfile,shutil,socket

root=Path('C:/Users/wmc02/Desktop/block-reality')
source=Path('//wsl.localhost/Ubuntu-22.04/home/rocky/br-native-v15-render')
out=root/'evidence/NATIVE_V15_CONSUMER/client';out.mkdir()
final=source/'observations-final'
counts={}
for name,expected in [('mg-models-events.json',127),('capture-events.json',45),('mg-interact-events.json',14),('mg-capture-events.json',6)]:
    entries=json.loads((final/name).read_text())
    gates=[x for x in entries if 'gate' in x]
    assert len(gates)==expected and all(x['pass'] for x in gates),(name,gates)
    counts[name]=len(gates)
assert len(list((final/'screenshots').glob('*.png')))==22
for path in [source/'observations/server-complete-inputs-exit.json',final/'client-complete-profile-exit.json']:
    assert json.loads(path.read_text())['exit']==0
raw={}
for folder in ['observations','observations-ready','observations-final']:
    for p in (source/folder).rglob('*'):
        if p.is_file() and '__pycache__' not in p.parts:
            raw[p.relative_to(source).as_posix()]=p.read_bytes()
for role in ['server','client']:
    for p in (source/role/'forge').rglob('libbsi_tectonic.so'):
        # Record rather than duplicate each generated/cached library.
        digest=hashlib.sha256(p.read_bytes()).hexdigest()
        assert digest=='2d048b9bfaf971d6b1971b7b0c36cc4942238448652735ba264dfbddf7d440a4'
        raw['identities/'+role+'-'+hashlib.sha256(str(p).encode()).hexdigest()[:12]+'.json']=json.dumps({'path':str(p),'bytes':p.stat().st_size,'sha256':digest}).encode()
manifest={name:{'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()} for name,data in raw.items()}
with zipfile.ZipFile(out/'raw-observations.zip','x',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as z:
    for name,data in raw.items():z.writestr(name,data)
    z.writestr('__sha256.json',json.dumps(manifest,indent=2))
with zipfile.ZipFile(out/'raw-observations.zip') as z:
    for name,digest in manifest.items():assert hashlib.sha256(z.read(name)).hexdigest()==digest['sha256']
helpers=out/'helpers';helpers.mkdir()
for name in ['setup-render.py','setup-render-linux.py','launch-render.py','run-render-scenes.py','archive-client.py']:
    shutil.copy2(root/'build/native-v1.5-consumer'/name,helpers/name)
for name in ['render-source.json','ci-f7f9533.json']:
    shutil.copy2(root/'build/native-v1.5-consumer'/name,out/name)
summary={'source':'98dbd54f67eab00a558d1971301370ce5eb7222e','production_classes_same_as_jar':'1a60418bc9e747ab93fb79b01549e70e74d76bc8',
         'checks':counts,'screenshots':22,'server_exit':0,'client_exit':0,'renderer':'llvmpipe software, actual Forge client',
         'shell_indicative_actual_game_input':'UNOBSERVED','windows_cm_n25':'OPEN','fps':'UNQUALIFIED','raw_files':len(manifest)}
(out/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
receipts={p.relative_to(out).as_posix():{'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in out.rglob('*') if p.is_file()}
(out/'receipts.json').write_text(json.dumps(receipts,indent=2)+'\n')
preview=root/'build/native-v1.5-consumer/client-preview'
for name in ['catalogue-zh_tw-1280.png','mg-unscanned-frames.png','mg-items-inventory.png','mg-items-hotbar-zh_tw.png']:
    shutil.copy2(final/'screenshots'/name,preview/name)
print(json.dumps(summary,indent=2))
