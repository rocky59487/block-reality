from pathlib import Path
import shutil,socket,hashlib,json,zipfile,sys
root=Path('/home/rocky/br-forge-axis-transactions');out=root/'qualification';case=sys.argv[1]
assert case in ['inventory','visibility','restored'];server=root/('installed-'+case)
assert (root/'OWNER').read_text().strip()=='block-reality-forge-axis-transactions-qualification-v1' and not server.exists()
with socket.socket() as sock:sock.bind(('127.0.0.1',25600))
source=Path('/home/rocky/br-native-v15-consumer/installed-server');server.mkdir();shutil.copytree(source/'libraries',server/'libraries')
for name in ['run.sh','run.bat','eula.txt']:shutil.copy2(source/name,server/name)
(server/'OWNER').write_text('block-reality-axis-runtime-v1\n');(server/'mods').mkdir()
qualified=Path('/mnt/c/Users/wmc02/Desktop/block-reality/build/forge-axis-transactions/blockreality-0.4.0-dev-axis-third.jar')
jar=out/'mutations/isolated-provisional-observation.jar' if case=='visibility' else qualified
assert hashlib.sha256(qualified.read_bytes()).hexdigest()=='828d24b47b237c2ded256633a10c3e0d42795eeaa0c51c88514eb7773889fa32'
if case=='visibility':
    assert json.loads((out/'mutations/provisional-observation-jar-exit.json').read_text())['exit']==0
    with zipfile.ZipFile(qualified) as a,zipfile.ZipFile(jar) as b:
        assert set(a.namelist())==set(b.namelist())
        differences=[n for n in a.namelist() if a.read(n)!=b.read(n)]
        assert all(n.startswith('com/blockreality/impl/server/StructureManager') for n in differences)
        (out/'mutations/visibility-jar-differences.json').write_text(json.dumps(differences,indent=2))
shutil.copy2(jar,server/'mods/blockreality-0.4.0-dev.jar')
harness=root/'harness-build/build/libs/br-axis-probe-1.0.jar';harness_hash=hashlib.sha256(harness.read_bytes()).hexdigest()
assert harness_hash==json.loads((out/'harness-identity-third.json').read_text())['sha256']
assert json.loads((out/'harness-build-third-exit.json').read_text())['exit']==0
shutil.copy2(harness,server/'mods/br-axis-probe.jar')
shutil.copy2(root/'installed-control/server.properties',server/'server.properties')
(out/('installed-'+case+'-identity.json')).write_text(json.dumps({'jar_sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'harness_sha256':harness_hash,'server':str(server),'fresh_world':True,'loopback_port':25600,'negative_arm':case=='visibility','library_source':str(source/'libraries')},indent=2))
print('Prepared audit case',case,flush=True)
