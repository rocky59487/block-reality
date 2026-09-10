from pathlib import Path
import hashlib,json,gzip,shutil,socket
root=Path('/mnt/c/Users/wmc02/Desktop/block-reality');work=root/'build/installed-native-server';base=Path('/home/rocky/br-installed-native-server');out=root/'evidence/INSTALLED_NATIVE_SERVER';out.mkdir()
(out/'.gitattributes').write_text('# Raw and compressed evidence preserves original bytes.\n** -text\n')
for port in [25596,25597]:
    with socket.socket() as sock:sock.bind(('127.0.0.1',port))
assert 'SELFTEST ALL PASS (9 injections, 0 slipped)' in (work/'final-bundle-negative-second.log').read_text(encoding='utf-8-sig')
shutil.make_archive(str(work/'blockreality-0.4.0-dev-native-candidate'),'zip',work/'distribution')
package=work/'blockreality-0.4.0-dev-native-candidate.zip'
(work/'distribution.json').write_text(json.dumps({'file':package.name,'bytes':package.stat().st_size,'sha256':hashlib.sha256(package.read_bytes()).hexdigest()},indent=2)+'\n')
raw=[]
for platform,directory in [('windows',work),('linux',base/'qualification')]:
    for source in sorted(directory.iterdir()):
        if not source.is_file() or source.suffix not in {'.py','.log','.json','.txt','.toml'}:continue
        body=source.read_bytes();destination=out/platform/source.name
        if len(body)>1_000_000 and source.suffix=='.log':destination=destination.with_suffix('.log.gz');stored=gzip.compress(body,mtime=0)
        else:stored=body
        destination.parent.mkdir(parents=True,exist_ok=True);destination.write_bytes(stored)
        raw.append({'source':str(source),'source_bytes':len(body),'source_sha256':hashlib.sha256(body).hexdigest(),'stored':destination.relative_to(out).as_posix(),'stored_sha256':hashlib.sha256(stored).hexdigest()})
for arm,server in [('control',base/'server'),('final',base/'final-server')]:
    for name in ['run.sh','run.bat','user_jvm_args.txt','INS_OWNED']:
        destination=out/'installation'/arm/name;destination.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(server/name,destination)
    log=(base/'qualification'/f'{arm}-smoke-first.log').read_text();assert log.count(': PASS')==10
    restart=json.loads((base/'qualification'/f'{arm}-after-restart.json').read_text());assert restart['restart_comparison']=='PASS'
    for name in [f'{arm}-server-first.log',f'{arm}-server-restart.log']:
        text=(base/'qualification'/name).read_text();assert 'native engine source=BUNDLED' in text and '53aae7156b94c0a761ef5abb2322ad47a708362f417a50ca3f04105d9c50abf1' in text and 'native engine READY: tectonic 1.3.0' in text
    (out/f'{arm}-exit.json').write_text(json.dumps({'first_server_exit':0,'restart_server_exit':0,'source':'completed execution tool sessions; RCON stop in corresponding raw receipts'})+'\n')
shutil.copy2(work/'distribution/SHA256SUMS.txt',out/'distribution-SHA256SUMS.txt')
shutil.copy2(work/'distribution/CANDIDATE.txt',out/'distribution-CANDIDATE.txt')
(out/'raw-sources.json').write_text(json.dumps(raw,indent=2)+'\n')
(out/'counts.json').write_text(json.dumps({'control':{'server_status_pass':10,'restart':'PASS','installed_jar':True},'final':{'server_status_pass':10,'restart':'PASS','installed_jar':True},'forge_dependency_artifacts_verified':63,'identical_production_classes':204,'identical_native_libraries':2,'bundle_negatives':9,'owned_ports_released':[25596,25597],'qualification_scope':'Linux installed dedicated servers; NCR executable-byte test results reused explicitly'},indent=2)+'\n')
print('Final candidate ZIP',package.stat().st_size,hashlib.sha256(package.read_bytes()).hexdigest())
print('Archived',len(raw),'raw sources; larger logs compressed losslessly')
