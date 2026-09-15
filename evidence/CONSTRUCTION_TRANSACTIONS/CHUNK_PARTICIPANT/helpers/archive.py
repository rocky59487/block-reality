from pathlib import Path
import zipfile,json,hashlib,shutil,subprocess
root=Path('C:/Users/wmc02/Desktop/block-reality');build=root/'build/ct-chunk-storage'
linux=Path('//wsl.localhost/Ubuntu-22.04/home/rocky/br-ct-chunk-storage')
out=root/'evidence/CONSTRUCTION_TRANSACTIONS/CHUNK_PARTICIPANT';out.mkdir()
(out/'.gitattributes').write_text('** -text\n')
def pack(path,files):
    manifest={}
    with zipfile.ZipFile(path,'x',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as z:
        for name,p in files:
            data=p.read_bytes();z.writestr(name,data);manifest[name]={'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()}
        z.writestr('__sha256.json',json.dumps(manifest,indent=2))
    with zipfile.ZipFile(path) as z:
        for name,entry in manifest.items():assert hashlib.sha256(z.read(name)).hexdigest()==entry['sha256']
for platform,base,logs in [('windows',root,build),('linux',linux,linux/'qualification')]:
    dst=out/platform;dst.mkdir()
    pack(dst/'forge-xml.zip',[(p.name,p) for p in (base/'forge/build/test-results/test').glob('TEST-*.xml')])
    for p in logs.iterdir():
        if p.is_file() and p.suffix in ['.json','.log']:shutil.copy2(p,dst/p.name)
mutant=Path('//wsl.localhost/Ubuntu-22.04/home/rocky/br-ct-chunk-mutations/evidence')
pack(out/'compiled-mutations.zip',[(p.name,p) for p in mutant.iterdir() if p.is_file()])
first=build/'first-seven';pack(out/'first-seven.zip',[(p.name,p) for p in first.iterdir() if p.is_file()])
helpers=out/'helpers';helpers.mkdir()
for p in build.glob('*.py'):shutil.copy2(p,helpers/p.name)
shutil.copy2(root/'build/manufactured-metadata/ci-87cc1cd.json',out/'parent-ci-87cc1cd.json')
identity={'source':subprocess.check_output(['git','rev-parse','70d1112'],text=True).strip(),
          'source_archive_sha256':hashlib.sha256((build/'source.tar').read_bytes()).hexdigest(),
          'source_archive_paths':['mod','forge','gradle','scripts','contract','LICENSE','NOTICE','third_party'],
          'unchanged_core_full_suite_source':'4737e3cf04e5dfdc9ce453795fab522f9c005110',
          'engine_modified':False,'formal_forge_construction_caller':False}
(out/'source.json').write_text(json.dumps(identity,indent=2)+'\n')
receipts={p.relative_to(out).as_posix():{'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in out.rglob('*') if p.is_file()}
(out/'receipts.json').write_text(json.dumps(receipts,indent=2)+'\n');print('Archived',len(receipts),'files; ZIP bytes verified')
