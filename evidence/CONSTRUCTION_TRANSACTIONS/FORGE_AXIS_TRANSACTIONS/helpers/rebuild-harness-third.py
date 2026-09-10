from pathlib import Path
import shutil,subprocess,os,json,zipfile,hashlib,sys
root=Path('/home/rocky/br-forge-axis-transactions');out=root/'qualification';dest=root/'harness-build'
assert (root/'OWNER').read_text().strip()=='block-reality-forge-axis-transactions-qualification-v1'
saved=out/'harness-before-inventory-audit';saved.mkdir();shutil.copytree(dest/'src',saved/'src');shutil.copy2(dest/'build/libs/br-axis-probe-1.0.jar',saved/'br-axis-probe-second.jar');shutil.copy2(out/'harness-identity.json',saved/'identity.json')
source=Path('/mnt/c/Users/wmc02/Desktop/block-reality/build/forge-axis-transactions/harness/src/main/java/com/blockreality/testaxisprobe/AxisProbe.java')
shutil.copy2(source,dest/'src/main/java/com/blockreality/testaxisprobe/AxisProbe.java')
command=['bash','gradlew','--no-daemon','--console=plain','jar','reobfJar']
with (out/'harness-build-third.log').open('xb') as log:r=subprocess.run(command,cwd=dest,env=dict(os.environ,PYTHONUTF8='1',PYTHONIOENCODING='utf-8'),stdout=log,stderr=subprocess.STDOUT)
(out/'harness-build-third-exit.json').write_text(json.dumps({'exit':r.returncode,'command':command,'reason':'Strengthen partial inventory assertion to exact full saved inventory, selected slot and carried bytes; preserve prior 53-check control.'}));print('harness third',r.returncode,flush=True)
if r.returncode:sys.exit(r.returncode)
jar=dest/'build/libs/br-axis-probe-1.0.jar'
with zipfile.ZipFile(jar) as z:
    names=z.namelist();assert 'META-INF/accesstransformer.cfg' not in names
    assert all(n.startswith('com/blockreality/testaxisprobe/') for n in names if n.endswith('.class'))
(out/'harness-identity-third.json').write_text(json.dumps({'sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'bytes':jar.stat().st_size,'entries':names,'no_production_classes_or_access_transformer':True},indent=2))
