from pathlib import Path
import zipfile,json,hashlib,shutil,subprocess
root=Path('C:/Users/wmc02/Desktop/block-reality');build=root/'build/manufactured-metadata'
linux=Path('//wsl.localhost/Ubuntu-22.04/home/rocky/br-manufactured-metadata')
out=root/'evidence/CONSTRUCTION_TRANSACTIONS/MANUFACTURED_METADATA';out.mkdir()
(out/'.gitattributes').write_text('** -text\n')
def pack(path,files):
    manifest={}
    with zipfile.ZipFile(path,'x',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as z:
        for name,p in files:
            data=p.read_bytes();z.writestr(name,data)
            manifest[name]={'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()}
        z.writestr('__sha256.json',json.dumps(manifest,indent=2))
    with zipfile.ZipFile(path) as z:
        for name,entry in manifest.items():assert hashlib.sha256(z.read(name)).hexdigest()==entry['sha256']
    print(path.name,len(manifest),flush=True)
for platform,base,logs in [('windows',root,build),('linux',linux,linux/'qualification')]:
    dst=out/platform;dst.mkdir()
    for phase,folder in [('core','mod/core'),('forge','forge')]:
        pack(dst/(phase+'-xml.zip'),[(p.name,p) for p in (base/folder/'build/test-results/test').glob('TEST-*.xml')])
    for p in logs.iterdir():
        if p.is_file() and p.suffix in ['.json','.log']:shutil.copy2(p,dst/p.name)
    process=logs/'process'
    pack(dst/'process-raw.zip',[(p.relative_to(process).as_posix(),p) for p in process.rglob('*') if p.is_file()])
    profile=logs/('profile-'+platform)
    pack(dst/'profile-observations.zip',[(p.relative_to(profile).as_posix(),p) for p in profile.iterdir() if p.is_file()])
    # All 144 actual copies remain at their original owned roots and are hash-manifested.
    # One full copy per size is archived because every corresponding file was identical.
    pack(dst/'profile-committed-exemplars.zip',[(str(n)+'/'+p.name,p) for n in [64,1024,4096] for p in (profile/('pieces-'+str(n))/'commit-0').iterdir() if p.is_file()])
mutant=Path('//wsl.localhost/Ubuntu-22.04/home/rocky/br-manufactured-mutations/evidence')
pack(out/'compiled-mutations.zip',[(p.relative_to(mutant).as_posix(),p) for p in mutant.rglob('*') if p.is_file()])
first=build/'first-14'
pack(out/'pre-extension-control.zip',[(p.relative_to(first).as_posix(),p) for p in first.rglob('*') if p.is_file()])
fixture=build/'profile-fixture'
pack(out/'profile-fixture.zip',[(p.relative_to(fixture).as_posix(),p) for p in fixture.rglob('*') if p.is_file()])
helpers=out/'helpers';helpers.mkdir()
for p in build.glob('*.py'):shutil.copy2(p,helpers/p.name)
shutil.copy2(root/'build/ct-journal-bootstrap/ci-5af6f44.json',out/'parent-ci-5af6f44.json')
identity={'source':subprocess.check_output(['git','rev-parse','4737e3c'],text=True).strip(),
          'profile_source':subprocess.check_output(['git','rev-parse','19a36fb'],text=True).strip(),
          'source_archive_sha256':hashlib.sha256((build/'source.tar').read_bytes()).hexdigest(),
          'profile_source_archive_sha256':hashlib.sha256((build/'profile-source.tar').read_bytes()).hexdigest(),
          'source_archive_paths':['mod','forge','gradle','scripts','contract','LICENSE','NOTICE','third_party'],
          'engine_modified':False,'formal_forge_construction_caller':False}
(out/'source.json').write_text(json.dumps(identity,indent=2)+'\n')
receipts={p.relative_to(out).as_posix():{'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in out.rglob('*') if p.is_file()}
(out/'receipts.json').write_text(json.dumps(receipts,indent=2)+'\n')
print('Archived',len(receipts),'files; raw XML/process bytes verified',flush=True)
