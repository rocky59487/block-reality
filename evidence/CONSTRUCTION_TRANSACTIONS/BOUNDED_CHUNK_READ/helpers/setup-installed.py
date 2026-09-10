from pathlib import Path
import shutil,socket,hashlib,json,zipfile
root=Path('/home/rocky/br-bounded-chunk-read');out=root/'qualification';server=root/'installed-server'
assert (root/'OWNER').read_text().strip()=='block-reality-bounded-chunk-read-qualification-v1'
assert json.loads((out/'harness-build-first-exit.json').read_text())['exit']==0
assert not server.exists()
with socket.socket() as sock:sock.bind(('127.0.0.1',25598))
source=Path('/home/rocky/br-native-v15-consumer/installed-server')
server.mkdir();shutil.copytree(source/'libraries',server/'libraries')
for name in ['run.sh','run.bat','eula.txt']:shutil.copy2(source/name,server/name)
(server/'OWNER').write_text('block-reality-bounded-chunk-runtime-v1\n');(server/'mods').mkdir()
qualified=Path('/mnt/c/Users/wmc02/Desktop/block-reality/build/bounded-chunk-read/blockreality-0.4.0-dev-bounded-chunk.jar')
digest=hashlib.sha256(qualified.read_bytes()).hexdigest();assert digest=='4bf732d83a1eccf81dbf30da57bf1955c9d69e8c67435c15c85431f57962efce'
shutil.copy2(qualified,server/'mods/blockreality-0.4.0-dev.jar')
harness=root/'harness-build/build/libs/br-chunk-probe-1.0.jar'
assert hashlib.sha256(harness.read_bytes()).hexdigest()==json.loads((out/'harness-identity.json').read_text())['sha256']
shutil.copy2(harness,server/'mods/br-chunk-probe.jar')
with zipfile.ZipFile(server/'mods/blockreality-0.4.0-dev.jar') as z:
    assert hashlib.sha256(z.read('META-INF/accesstransformer.cfg')).hexdigest()=='230f398dea92d754f81bbeacd172ea9abb764a7b2438e48ef0b7d9810d475a13'
    assert not any('testchunkprobe' in n for n in z.namelist())
with zipfile.ZipFile(harness) as z:assert 'META-INF/accesstransformer.cfg' not in z.namelist()
(server/'server.properties').write_text('server-ip=127.0.0.1\nserver-port=25598\nonline-mode=false\nenable-rcon=false\nenable-query=false\nlevel-name=chunk-read-runtime\nlevel-type=minecraft:flat\ngenerate-structures=false\nspawn-protection=0\nview-distance=3\nsimulation-distance=3\nmax-players=1\nmax-tick-time=180000\n')
runtime=out/'runtime-first';runtime.mkdir();(runtime/'OWNER').write_text('block-reality-bounded-chunk-runtime-v1\n')
(server/'user_jvm_args.txt').write_text('-Xms512m\n-Xmx2g\n-Dbr.chunkProbeOutput='+str(runtime)+'\n-Xlog:class+load=info:file='+str(runtime/'classload.log')+'\n')
libraries={p.relative_to(server/'libraries').as_posix():{'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in (server/'libraries').rglob('*') if p.is_file()}
(out/'installed-library-manifest.json').write_text(json.dumps(libraries,indent=2))
(out/'installed-identity.json').write_text(json.dumps({'jar_sha256':digest,'server':str(server),'library_source':str(source/'libraries'),
    'harness_sha256':hashlib.sha256(harness.read_bytes()).hexdigest(),'fresh_world':True,'loopback_only':True,'port':25598,
    'no_old_world_player_or_cache_copied':True,'no_access_transformer_in_harness':True},indent=2))
print('Prepared isolated installed server with the Windows-qualified ordinary jar and separate test harness')
