from pathlib import Path
import shutil,subprocess,json,os,sys,zipfile,hashlib
root=Path('/home/rocky/br-live-chunk-capture');source=Path('/mnt/c/Users/wmc02/Desktop/block-reality/build/live-chunk-capture/harness')
dest=root/'harness-build';assert not dest.exists();shutil.copytree(source,dest)
for name in ['settings.gradle','gradlew','gradlew.bat']:shutil.copy2(root/'forge'/name,dest/name)
shutil.copytree(root/'forge/gradle',dest/'gradle')
out=root/'qualification';command=['bash','gradlew','--no-daemon','--console=plain','jar','reobfJar']
env=dict(os.environ,PYTHONUTF8='1',PYTHONIOENCODING='utf-8');env.pop('BR_ENGINE',None);env.pop('BR_SIDECAR',None)
with (out/'harness-build-first.log').open('xb') as log:result=subprocess.run(command,cwd=dest,env=env,stdout=log,stderr=subprocess.STDOUT)
(out/'harness-build-first-exit.json').write_text(json.dumps({'exit':result.returncode,'command':command}))
print('harness',result.returncode,flush=True)
if result.returncode:sys.exit(result.returncode)
jar=dest/'build/libs/br-capture-probe-1.0.jar'
with zipfile.ZipFile(jar) as z:
    names=z.namelist();assert 'META-INF/mods.toml' in names and 'META-INF/accesstransformer.cfg' not in names
    assert all(n.startswith('com/blockreality/testcaptureprobe/') for n in names if n.endswith('.class'))
identity={'sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'bytes':jar.stat().st_size,'entries':names,'no_production_classes_or_access_transformer':True}
(out/'harness-identity.json').write_text(json.dumps(identity,indent=2));print(json.dumps(identity),flush=True)
