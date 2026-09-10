from pathlib import Path
import subprocess,sys,json,shutil,hashlib,socket
scripts=Path('/mnt/c/Users/wmc02/Desktop/block-reality/build/forge-axis-transactions');root=Path('/home/rocky/br-forge-axis-transactions');out=root/'qualification';server=root/'installed-visibility'
assert server.resolve().is_relative_to(root.resolve()) and (server/'OWNER').read_text().strip()=='block-reality-axis-runtime-v1'
assert list((server/'mods').iterdir())==[] and not (server/'axis-runtime').exists()
with socket.socket() as sock:sock.bind(('127.0.0.1',25600))
jar=out/'mutations/isolated-provisional-observation.jar';harness=root/'harness-build/build/libs/br-axis-probe-1.0.jar'
assert hashlib.sha256(jar.read_bytes()).hexdigest()==json.loads((out/'mutations/isolated-artifact.json').read_text())['isolated_negative_sha256']
assert hashlib.sha256(harness.read_bytes()).hexdigest()==json.loads((out/'harness-identity-third.json').read_text())['sha256']
shutil.copy2(jar,server/'mods/blockreality-0.4.0-dev.jar');shutil.copy2(harness,server/'mods/br-axis-probe.jar');shutil.copy2(root/'installed-control/server.properties',server/'server.properties')
(out/'installed-visibility-identity.json').write_text(json.dumps({'jar_sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'harness_sha256':hashlib.sha256(harness.read_bytes()).hexdigest(),'negative_arm':True,'fresh_world':True,'server':str(server),'loopback_port':25600,'resumed_after_setup_inspection_only':True},indent=2))
results=[{'case':'inventory','probe_status':'PASS','checks_completed':60,'expected_behavior_observed':True}]
assert json.loads((out/'runtime-inventory-control/result.json').read_text())['status']=='PASS'
for case in ['visibility','restored']:
    if case=='restored':subprocess.run([sys.executable,str(scripts/'setup-audit.py'),case],check=True)
    r=subprocess.run([sys.executable,str(scripts/'launch-runtime.py'),case,'control']);result=json.loads((out/('runtime-'+case+'-control')/'result.json').read_text())
    if case=='visibility':assert r.returncode==1 and result['status']=='FAIL' and result['failure']=='java.lang.AssertionError: COMMITTED no provisional revision/metadata/observation/envelope'
    else:assert r.returncode==0 and result['status']=='PASS' and len(result['checks'])==60
    assert json.loads((out/('runtime-'+case+'-control')/'exit.json').read_text())=={'exit':0,'timeout':False}
    subprocess.run([sys.executable,str(scripts/'snapshot-runtime.py'),case,'control'],check=True)
    results.append({'case':case,'probe_status':result['status'],'checks_completed':len(result['checks']),'expected_behavior_observed':True})
(out/'mutations/runtime-oracle.json').write_text(json.dumps(results,indent=2));print('Inventory and visibility audit complete',flush=True)
