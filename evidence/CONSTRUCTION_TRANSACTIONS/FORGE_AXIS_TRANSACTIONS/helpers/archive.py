from pathlib import Path
import zipfile,json,hashlib,shutil,subprocess
root=Path('C:/Users/wmc02/Desktop/block-reality');build=root/'build/forge-axis-transactions'
linux=Path('//wsl.localhost/Ubuntu-22.04/home/rocky/br-forge-axis-transactions');logs=linux/'qualification'
assert all(x['expected_behavior_observed'] for x in json.loads((logs/'mutations/runtime-oracle.json').read_text()))
out=root/'evidence/CONSTRUCTION_TRANSACTIONS/FORGE_AXIS_TRANSACTIONS';out.mkdir();(out/'.gitattributes').write_text('** -text\n')
def pack(path,files):
    manifest={}
    with zipfile.ZipFile(path,'x',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as z:
        for name,p in files:
            assert name not in manifest;data=p.read_bytes();z.writestr(name,data);manifest[name]={'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()}
        z.writestr('__sha256.json',json.dumps(manifest,indent=2))
    with zipfile.ZipFile(path) as z:
        for name,entry in manifest.items():assert hashlib.sha256(z.read(name)).hexdigest()==entry['sha256']
    print(path.name,len(manifest),flush=True)
def tree(path):return [(p.relative_to(path).as_posix(),p) for p in path.rglob('*') if p.is_file()]
for platform,folder in [('windows',build),('linux',logs)]:
    dst=out/platform;dst.mkdir()
    for p in folder.iterdir():
        if p.is_file() and p.suffix in ['.json','.log']:shutil.copy2(p,dst/p.name)
        if p.is_dir() and (p.name.endswith('-xml') or p.name.startswith('process-')):pack(dst/(p.name+'.zip'),tree(p))
for p in logs.glob('runtime-*'):
    result=json.loads((p/'result.json').read_text());exit_record=json.loads((p/'exit.json').read_text())
    assert not exit_record['timeout']
    if p.name=='runtime-visibility-control':assert result['status']=='FAIL' and exit_record['exit']==0
    elif result['status']=='INTERRUPTED':assert exit_record['exit']==73
    else:assert result['status']=='PASS' and exit_record['exit']==0
    assert (p/'durable-world-manifest.json').exists()
    pack(out/(p.name+'.zip'),tree(p))
pack(out/'compiled-mutations.zip',[(name,p) for name,p in tree(logs/'mutations') if p.suffix!='.jar'])
pack(out/'companion-bytecode.zip',[(label+'/'+name,p) for label in ['javap-before','javap-after'] for name,p in tree(build/label)])
pack(out/'harness-current.zip',tree(build/'harness')+[('br-axis-probe-third.jar',linux/'harness-build/build/libs/br-axis-probe-1.0.jar')])
for name in ['harness-before-first-runtime','harness-before-inventory-audit']:pack(out/(name+'.zip'),tree(logs/name))
pack(out/'source-attempts.zip',[(p.name,p) for p in build.glob('source-*.tar')])
helpers=out/'helpers';helpers.mkdir()
for p in build.glob('*.py'):shutil.copy2(p,helpers/p.name)
shutil.copy2(root/'build/live-chunk-capture/ci-ecc8525.json',out/'parent-ci-ecc8525.json')
identity={'sources':{p.stem:{'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'bytes':p.stat().st_size} for p in build.glob('source-*.tar')},'core_full_suite_source':subprocess.check_output(['git','rev-parse','d720995'],cwd=root,text=True).strip(),'forge_installed_source':subprocess.check_output(['git','rev-parse','338c490'],cwd=root,text=True).strip(),'engine_modified':False,'formal_forge_caller':'empty-hand sneak axis edit only','ordinary_placement_inventory_blueprint_undo':'OPEN','synthetic_players_only':True}
(out/'source.json').write_text(json.dumps(identity,indent=2)+'\n')
receipts={p.relative_to(out).as_posix():{'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in out.rglob('*') if p.is_file()}
(out/'receipts.json').write_text(json.dumps(receipts,indent=2)+'\n');print('Archived',len(receipts),'files; raw ZIP bytes reopened and verified')
