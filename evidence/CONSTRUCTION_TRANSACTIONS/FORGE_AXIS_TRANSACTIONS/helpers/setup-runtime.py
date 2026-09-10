from pathlib import Path
import shutil,socket,hashlib,json,zipfile,sys
root=Path('/home/rocky/br-forge-axis-transactions');out=root/'qualification';case=sys.argv[1]
assert case in ['control','prepared','flushed','committed'];server=root/('installed-'+case)
assert (root/'OWNER').read_text().strip()=='block-reality-forge-axis-transactions-qualification-v1'
assert not server.exists()
with socket.socket() as sock:sock.bind(('127.0.0.1',25600))
source=Path('/home/rocky/br-native-v15-consumer/installed-server');server.mkdir();shutil.copytree(source/'libraries',server/'libraries')
for name in ['run.sh','run.bat','eula.txt']:shutil.copy2(source/name,server/name)
(server/'OWNER').write_text('block-reality-axis-runtime-v1\n');(server/'mods').mkdir()
win=Path('/mnt/c/Users/wmc02/Desktop/block-reality/build/forge-axis-transactions')
qualified=win/'blockreality-0.4.0-dev-axis-third.jar';identity=json.loads((win/'jar-identity-third.json').read_text())
digest=hashlib.sha256(qualified.read_bytes()).hexdigest();assert digest==identity['sha256']
assert json.loads((win/'forge-third-exit.json').read_text())['exit']==0 and identity['engine_licenses_provenance_and_AT_unchanged']
shutil.copy2(qualified,server/'mods/blockreality-0.4.0-dev.jar')
harness=out/'harness-before-inventory-audit/br-axis-probe-second.jar';harness_hash=hashlib.sha256(harness.read_bytes()).hexdigest()
assert harness_hash==json.loads((out/'harness-identity.json').read_text())['sha256']
assert json.loads((out/'harness-build-second-exit.json').read_text())['exit']==0
shutil.copy2(harness,server/'mods/br-axis-probe.jar')
with zipfile.ZipFile(harness) as z:assert 'META-INF/accesstransformer.cfg' not in z.namelist()
(server/'server.properties').write_text('server-ip=127.0.0.1\nserver-port=25600\nonline-mode=false\nenable-rcon=false\nenable-query=false\nlevel-name=axis-runtime\nlevel-type=minecraft:flat\ngenerate-structures=false\nspawn-protection=0\nview-distance=3\nsimulation-distance=3\nmax-players=1\nmax-tick-time=180000\n')
(out/('installed-'+case+'-identity.json')).write_text(json.dumps({'jar_sha256':digest,'harness_sha256':harness_hash,'server':str(server),'fresh_world':True,'loopback_port':25600,'library_source':str(source/'libraries'),'no_old_world_player_or_cache_copied':True},indent=2))
if case=='control':
    libraries={p.relative_to(server/'libraries').as_posix():{'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in (server/'libraries').rglob('*') if p.is_file()}
    (out/'installed-library-manifest.json').write_text(json.dumps(libraries,indent=2))
print('Prepared owned installed case',case,flush=True)
