from pathlib import Path
import subprocess,sys,json
scripts=Path('/mnt/c/Users/wmc02/Desktop/block-reality/build/forge-axis-transactions');out=Path('/home/rocky/br-forge-axis-transactions/qualification')
assert json.loads((out/'runtime-committed-restart-again/exit.json').read_text())=={'exit':0,'timeout':False}
results=[]
for case in ['inventory','visibility','restored']:
    subprocess.run([sys.executable,str(scripts/'setup-audit.py'),case],check=True)
    r=subprocess.run([sys.executable,str(scripts/'launch-runtime.py'),case,'control'])
    result=json.loads((out/('runtime-'+case+'-control')/'result.json').read_text())
    if case=='visibility':
        assert r.returncode==1 and result['status']=='FAIL' and result['failure']=='java.lang.AssertionError: COMMITTED no provisional revision/metadata/observation/envelope'
        assert json.loads((out/('runtime-'+case+'-control')/'exit.json').read_text())=={'exit':0,'timeout':False}
    else:assert r.returncode==0 and result['status']=='PASS'
    subprocess.run([sys.executable,str(scripts/'snapshot-runtime.py'),case,'control'],check=True)
    results.append({'case':case,'probe_status':result['status'],'checks_completed':len(result['checks']),'expected_behavior_observed':True})
(out/'mutations/runtime-oracle.json').write_text(json.dumps(results,indent=2));print('Inventory and visibility audit complete',flush=True)
