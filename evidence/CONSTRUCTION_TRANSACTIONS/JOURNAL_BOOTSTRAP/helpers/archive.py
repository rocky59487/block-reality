from pathlib import Path
import zipfile,json,hashlib,shutil,subprocess
root=Path('C:/Users/wmc02/Desktop/block-reality');build=root/'build/ct-journal-bootstrap'
linux=Path('//wsl.localhost/Ubuntu-22.04/home/rocky/br-ct-journal-bootstrap')
out=root/'evidence/CONSTRUCTION_TRANSACTIONS/JOURNAL_BOOTSTRAP';out.mkdir()
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
for platform,base,logs in [('windows',root,build),('linux',linux,linux/'qualification')]:
    dst=out/platform;dst.mkdir()
    for phase,folder in [('core','mod/core'),('forge','forge')]:
        pack(dst/(phase+'-xml.zip'),[(p.name,p) for p in (base/folder/'build/test-results/test').glob('TEST-*.xml')])
    for p in logs.iterdir():
        if p.is_file() and p.suffix in ['.json','.log']:shutil.copy2(p,dst/p.name)
    process=logs/'process'
    pack(dst/'process-raw.zip',[(p.relative_to(process).as_posix(),p) for p in process.rglob('*') if p.is_file()])
mutant=Path('//wsl.localhost/Ubuntu-22.04/home/rocky/br-ct-bootstrap-mutations/evidence')
pack(out/'compiled-mutation.zip',[(p.name,p) for p in mutant.iterdir() if p.is_file()])
helpers=out/'helpers';helpers.mkdir()
for p in build.glob('*.py'):shutil.copy2(p,helpers/p.name)
shutil.copy2(root/'build/native-verdict-cleanup/ci-6d1a0c2.json',out/'parent-ci-6d1a0c2.json')
identity={'source':subprocess.check_output(['git','rev-parse','471fad9'],text=True).strip(),
          'source_archive_sha256':hashlib.sha256((build/'source.tar').read_bytes()).hexdigest(),
          'source_archive_paths':['mod','forge','gradle','scripts','contract','LICENSE','NOTICE','third_party'],
          'engine_modified':False,'formal_forge_construction_caller':False}
(out/'source.json').write_text(json.dumps(identity,indent=2)+'\n')
receipts={p.relative_to(out).as_posix():{'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in out.rglob('*') if p.is_file()}
(out/'receipts.json').write_text(json.dumps(receipts,indent=2)+'\n')
print('Archived',len(receipts),'files; process trees and XML byte hashes verified')
