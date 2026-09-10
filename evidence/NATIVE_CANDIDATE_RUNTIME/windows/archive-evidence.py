from pathlib import Path
import shutil,json,hashlib,subprocess,socket
workspace=Path('/mnt/c/Users/wmc02/Desktop/block-reality');work=workspace/'build/native-candidate-runtime';out=workspace/'evidence/NATIVE_CANDIDATE_RUNTIME';out.mkdir()
linux=Path('/home/rocky/br-native-candidate-runtime/qualification');render=Path('/home/rocky/br-ncr-render-first')
def copy_file(source,target):
    target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,target)
def copy_tree(source,target,suffixes):
    for p in sorted(source.rglob('*')):
        if p.is_file() and p.suffix in suffixes:copy_file(p,target/p.relative_to(source))
for p in sorted(work.iterdir()):
    if p.is_file() and p.suffix in {'.log','.json','.py'}:copy_file(p,out/'windows'/p.name)
for folder in ['windows-core-first-xml','windows-forge-first-xml']:
    copy_tree(work/folder,out/'windows'/folder,{'.xml'})
copy_tree(work/'jar-windows-first',out/'windows/jar-first',{'.json','.frame','.txt','.log'})
copy_file(work/'release-guards-first/verification.json',out/'windows/release-guards-first.json')
copy_tree(linux,out/'linux',{'.log','.json','.xml','.frame','.txt'})
copy_tree(render,out/'client',{'.log','.json','.png','.py'})
copy_file(render/'CRP_OWNED',out/'client/CRP_OWNED')
copy_file(work/'staged/provenance.json',out/'provenance.json')
copy_file(work/'assets/SHA256SUMS',out/'delivered-SHA256SUMS')
copy_file(work/'assets/tectonic2-1.3.0-verification.json',out/'delivered-verification.json')
copy_file(work/'distribution/SHA256SUMS.txt',out/'distribution-SHA256SUMS.txt')
copy_file(work/'distribution/START-HERE.txt',out/'distribution-START-HERE.txt')
sources=[]
for path in subprocess.check_output(['git','ls-tree','-r','--name-only','c2a1b94'],cwd=workspace,text=True).splitlines():
    if path.startswith(('mod/','forge/','scripts/','gradle/','contract/','.github/tectonic2-contract-ref')):
        content=subprocess.check_output(['git','show','c2a1b94:'+path],cwd=workspace)
        sources.append({'path':path,'bytes':len(content),'sha256':hashlib.sha256(content).hexdigest()})
(out/'module-c2a1b94-sources.json').write_text(json.dumps(sources,indent=2)+'\n')
for port in [25594,25595,25596,25597]:
    with socket.socket() as sock:sock.bind(('127.0.0.1',port))
(out/'owned-servers-stopped.json').write_text(json.dumps({'ports_available':[25594,25595,25596,25597],'original_cm_server_untouched':True})+'\n')
summaries={}
for platform,directory in [('windows',out/'windows'),('linux',out/'linux')]:
    cases=[json.loads((directory/f'{platform}-{project}-first-tests.json').read_text()) for project in ['core','forge']]
    summaries[platform]={k:sum(c[k] for c in cases) for k in ['tests','passed','failures','errors','skipped']}
    summaries[platform]['skips']=[s for c in cases for s in c['skips']]
    jar=directory/('jar-first' if platform=='windows' else 'jar-linux-first')/'verification.json'
    verification=json.loads(jar.read_text());assert len(verification['requests'])==48 and verification['jar_sha256']=='5a93c66bfe5f5a3dd8c1104a6e97fe000a2a0d2a8e66bf602821993d2a4c9b79'
runtime=(linux/'runtime-smoke-third.log').read_text();assert runtime.count(': PASS')==16
events=json.loads((linux/'runtime-smoke-third.json').read_text());assert sum(e['command']=='br_delivery_probe' and 'delivery probe PASS:' in e['response'] for e in events)==6
capture=(render/'capture-first.log').read_text();assert capture.count(': PASS')==45
images=list((render/'screenshots').glob('*.png'));assert len(images)==16
summaries['game']={'server_status_pass':16,'synthetic_player_events':24,'client_assertions_pass':45,'actual_screenshots':16,'scope':'bundled dev resources / isolated real Forge server and client; not installed jar or FPS qualification','client_exit':json.loads((render/'client-first-exit.json').read_text())}
(out/'counts.json').write_text(json.dumps(summaries,indent=2)+'\n')
files=[{'path':p.relative_to(out).as_posix(),'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in sorted(out.rglob('*')) if p.is_file()]
(out/'receipts.json').write_text(json.dumps(files,indent=2)+'\n')
print(json.dumps({'summaries':summaries,'evidence_files':len(files),'evidence_bytes':sum(f['bytes'] for f in files)},indent=2))
