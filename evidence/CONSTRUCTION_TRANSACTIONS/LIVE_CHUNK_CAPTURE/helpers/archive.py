from pathlib import Path
import zipfile,json,hashlib,shutil,subprocess
root=Path('C:/Users/wmc02/Desktop/block-reality');build=root/'build/live-chunk-capture'
linux=Path('//wsl.localhost/Ubuntu-22.04/home/rocky/br-live-chunk-capture')
out=root/'evidence/CONSTRUCTION_TRANSACTIONS/LIVE_CHUNK_CAPTURE';out.mkdir();(out/'.gitattributes').write_text('** -text\n')
def pack(path,files):
    manifest={}
    with zipfile.ZipFile(path,'x',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as z:
        for name,p in files:
            data=p.read_bytes();z.writestr(name,data);manifest[name]={'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()}
        z.writestr('__sha256.json',json.dumps(manifest,indent=2))
    with zipfile.ZipFile(path) as z:
        for name,entry in manifest.items():assert hashlib.sha256(z.read(name)).hexdigest()==entry['sha256']
    print(path.name,len(manifest),flush=True)
def tree(path):return [(p.relative_to(path).as_posix(),p) for p in path.rglob('*') if p.is_file()]
for platform,logs in [('windows',build),('linux',linux/'qualification')]:
    dst=out/platform;dst.mkdir();pack(dst/'forge-xml.zip',tree(logs/'full-first-xml'))
    assert json.loads((logs/'full-first-exit.json').read_text())['exit']==0
    for p in logs.iterdir():
        if p.is_file() and p.suffix in ['.json','.log']:shutil.copy2(p,dst/p.name)
pack(out/'first-eighteen.zip',tree(build/'test-first-xml'))
pack(out/'companion-bytecode.zip',[(label+'/'+name,p) for label in ['javap-before','javap-after'] for name,p in tree(build/label)])
mutant=Path('//wsl.localhost/Ubuntu-22.04/home/rocky/br-live-capture-mutations/evidence')
assert len(json.loads((mutant/'results.json').read_text()))==4
pack(out/'compiled-mutations.zip',tree(mutant))
runtime=linux/'qualification/runtime-first'
assert json.loads((runtime/'result.json').read_text())['status']=='PASS'
assert json.loads((runtime/'exit.json').read_text())=={'exit':0,'timeout':False}
pack(out/'installed-runtime.zip',tree(runtime));pack(out/'harness-source.zip',tree(build/'harness'))
helpers=out/'helpers';helpers.mkdir()
for p in build.iterdir():
    if p.is_file() and p.suffix=='.py':shutil.copy2(p,helpers/p.name)
shutil.copy2(root/'build/bounded-chunk-read/ci-02856fd.json',out/'parent-ci-02856fd.json')
identity={'source':json.loads((build/'source.json').read_text())['source'],
          'source_archive_sha256':hashlib.sha256((build/'source.tar').read_bytes()).hexdigest(),
          'source_archive_paths':['mod','forge','gradle','scripts','contract','LICENSE','NOTICE','third_party'],
          'unchanged_core_full_suite_source':'4737e3cf04e5dfdc9ce453795fab522f9c005110',
          'engine_modified':False,'formal_forge_construction_caller':False,'installed_harness_is_separate_mod':True}
(out/'source.json').write_text(json.dumps(identity,indent=2)+'\n')
receipts={p.relative_to(out).as_posix():{'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in out.rglob('*') if p.is_file()}
(out/'receipts.json').write_text(json.dumps(receipts,indent=2)+'\n');print('Archived',len(receipts),'files; raw ZIP bytes verified')
