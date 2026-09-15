from pathlib import Path
import zipfile,json,hashlib,shutil,subprocess
root=Path('C:/Users/wmc02/Desktop/block-reality');build=root/'build/bounded-chunk-read'
linux=Path('//wsl.localhost/Ubuntu-22.04/home/rocky/br-bounded-chunk-read')
out=root/'evidence/CONSTRUCTION_TRANSACTIONS/BOUNDED_CHUNK_READ';out.mkdir();(out/'.gitattributes').write_text('** -text\n')
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
for platform,base,logs in [('windows',root,build),('linux',linux,linux/'qualification')]:
    dst=out/platform;dst.mkdir()
    pack(dst/'forge-xml.zip',[(p.name,p) for p in (base/'forge/build/test-results/test').glob('TEST-*.xml')])
    for p in logs.iterdir():
        if p.is_file() and p.suffix in ['.json','.log']:shutil.copy2(p,dst/p.name)
    pack(dst/'full-region-fixtures.zip',tree(logs/'full-region-fixtures'))
pack(out/'first-thirteen.zip',tree(build/'first-thirteen'))
pack(out/'first-region-fixtures.zip',tree(build/'first-region-fixtures'))
mutant=Path('//wsl.localhost/Ubuntu-22.04/home/rocky/br-bounded-chunk-mutations/evidence')
pack(out/'compiled-mutations.zip',tree(mutant))
runtime=linux/'qualification/runtime-first'
assert json.loads((runtime/'result.json').read_text())['status']=='PASS'
assert json.loads((runtime/'exit.json').read_text())=={'exit':0,'timeout':False}
pack(out/'installed-runtime.zip',tree(runtime))
pack(out/'harness-source.zip',tree(build/'harness'))
helpers=out/'helpers';helpers.mkdir()
for p in build.iterdir():
    if p.is_file() and p.suffix in ['.py','.gradle']:shutil.copy2(p,helpers/p.name)
shutil.copy2(root/'build/ct-chunk-storage/ci-540ba95.json',out/'parent-ci-540ba95.json')
identity={'source':subprocess.check_output(['git','rev-parse','bca242a'],text=True).strip(),
          'source_archive_sha256':hashlib.sha256((build/'source.tar').read_bytes()).hexdigest(),
          'source_archive_paths':['mod','forge','gradle','scripts','contract','LICENSE','NOTICE','third_party'],
          'unchanged_core_full_suite_source':'4737e3cf04e5dfdc9ce453795fab522f9c005110',
          'engine_modified':False,'formal_forge_construction_caller':False,'installed_harness_is_separate_mod':True}
(out/'source.json').write_text(json.dumps(identity,indent=2)+'\n')
receipts={p.relative_to(out).as_posix():{'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in out.rglob('*') if p.is_file()}
(out/'receipts.json').write_text(json.dumps(receipts,indent=2)+'\n');print('Archived',len(receipts),'files; raw ZIP bytes verified')
