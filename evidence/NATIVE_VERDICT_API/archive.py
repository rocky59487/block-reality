from pathlib import Path
import hashlib,json,zipfile,shutil,subprocess
root=Path('C:/Users/wmc02/Desktop/block-reality');build=root/'build/native-verdict-cleanup'
out=root/'evidence/NATIVE_VERDICT_API';out.mkdir()
(out/'.gitattributes').write_text('** -text\n')
old=zipfile.ZipFile(root/'build/native-v1.5-consumer/blockreality-0.4.0-dev-v1.5.jar')
new=zipfile.ZipFile(build/'blockreality-0.4.0-dev-native-verdict.jar')
name='com/blockreality/api/render/StressPalette$LegendStop.class';a=old.read(name);b=new.read(name)
assert len(a)==len(b)
differences=[{'offset':i,'before':x,'after':y} for i,(x,y) in enumerate(zip(a,b)) if x!=y]
assert len(differences)==8 and all(x['before']==180 and x['after']==169 for x in differences)
identity=json.loads((build/'jar-identity.json').read_text())
identity.update(status='PASS with disclosed debug metadata delta',unchanged_classes=234,legend_record_changed_bytes=differences,
                first_strict_single_class_assertion='FAIL: nested record has line-number metadata changes; original identity retained',
                all_native_resources_and_packets_unchanged=True)
(out/'artifact-reviewed.json').write_text(json.dumps(identity,indent=2)+'\n')
def pack(destination,files):
    raw={name:p.read_bytes() for name,p in files}
    manifest={name:{'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()} for name,data in raw.items()}
    with zipfile.ZipFile(destination,'x',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as z:
        for name,data in raw.items():z.writestr(name,data)
        z.writestr('__sha256.json',json.dumps(manifest,indent=2))
    with zipfile.ZipFile(destination) as z:
        for name,data in raw.items():assert z.read(name)==data
for phase,source in [('core',root/'mod/core/build/test-results/test'),('forge',root/'forge/build/test-results/test')]:
    pack(out/(phase+'-xml.zip'),[(p.name,p) for p in source.glob('TEST-*.xml')])
mutant=Path('//wsl.localhost/Ubuntu-22.04/home/rocky/br-native-verdict-mutations/evidence')
pack(out/'compiled-mutation.zip',[(p.name,p) for p in mutant.iterdir() if p.is_file()])
for p in build.iterdir():
    if p.is_file() and p.suffix in ['.json','.log','.txt','.diff','.py','.java']:
        shutil.copy2(p,out/p.name)
shutil.copy2(root/'build/native-v1.5-consumer/ci-1f5c7b3.json',out/'native-parent-ci-1f5c7b3.json')
receipts={p.relative_to(out).as_posix():{'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in out.rglob('*') if p.is_file()}
(out/'receipts.json').write_text(json.dumps(receipts,indent=2)+'\n')
print(json.dumps(identity,indent=2))
