from pathlib import Path
import json,hashlib,zipfile,shutil,subprocess
root=Path('C:/Users/wmc02/Desktop/block-reality');build=root/'build/forge-axis-transactions';out=root/'evidence/CONSTRUCTION_TRANSACTIONS/FORGE_AXIS_TRANSACTIONS';linux=Path('//wsl.localhost/Ubuntu-22.04/home/rocky/br-forge-axis-transactions/qualification')
for name,record in json.loads((out/'receipts.json').read_text()).items():assert hashlib.sha256((out/name).read_bytes()).hexdigest()==record['sha256']
with zipfile.ZipFile(build/'blockreality-0.4.0-dev-axis-third.jar') as a,zipfile.ZipFile(linux/'blockreality-0.4.0-dev-axis-third.jar') as b:
    different=[n for n in a.namelist() if a.read(n)!=b.read(n)]
    assert len(different)==5 and all(not n.endswith('.class') for n in different)
    for n in different:assert a.read(n).replace(b'\r\n',b'\n')==b.read(n).replace(b'\r\n',b'\n')
    details=[{'name':n,'windows_bytes':len(a.read(n)),'linux_bytes':len(b.read(n)),'windows_crlf':a.read(n).count(b'\r\n'),'linux_crlf':b.read(n).count(b'\r\n')} for n in different]
    (out/'platform-resource-line-endings.json').write_text(json.dumps({'different_entries':details,'all_differences_are_only_line_endings':True,'compiled_classes_identical':True,'correction':'Initial inspection narration guessed the direction incorrectly. Both sides normalize identically; the Linux snapshot has more CRLF, while some Windows entries have mixed endings. The candidate was never changed.'},indent=2))
inventories={}
for case in ['inventory','restored']:
    run=linux/('runtime-'+case+'-control');assert json.loads((run/'result.json').read_text())['status']=='PASS'
    pairs=[]
    for index in range(1,8):
        a=(run/f'inventory-{index}-before.nbt').read_bytes();b=(run/f'inventory-{index}-after.nbt').read_bytes();assert a==b
        pairs.append({'interaction':index,'bytes':len(a),'sha256':hashlib.sha256(a).hexdigest()})
    inventories[case]=pairs
(out/'inventory-byte-audit.json').write_text(json.dumps(inventories,indent=2))
assert json.loads((build/'docs-first-exit.json').read_text())['exit']==0
for name in ['docs-first.log','docs-first-exit.json']:shutil.copy2(build/name,out/'windows'/name)
for name in ['check-docs.py','finalize-evidence.py','finalize-evidence-first.py']:shutil.copy2(build/name,out/'helpers'/name)
shutil.copy2(build/'finalize-first-failure.json',out/'finalize-first-failure.json')
shutil.copy2(build/'RESULTS.md',out/'RESULTS.md')
command=['gh','api','repos/rocky59487/tectonic2/releases/latest','--jq','{tag_name,published_at,target_commitish}'];raw=subprocess.check_output(command,cwd=root)
assert json.loads(raw)['tag_name']=='v1.5';(out/'upstream-latest-readonly.json').write_bytes(raw)
records={p.relative_to(out).as_posix():{'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in out.rglob('*') if p.is_file() and p.name!='receipts.json'}
(out/'receipts.json').write_text(json.dumps(records,indent=2)+'\n');print('Final verified evidence',len(records),'files',sum(r['bytes'] for r in records.values()),'bytes',flush=True)
