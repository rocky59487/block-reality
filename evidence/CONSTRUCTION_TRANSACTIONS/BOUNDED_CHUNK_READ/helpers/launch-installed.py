from pathlib import Path
import subprocess,os,json,signal,sys
root=Path('/home/rocky/br-bounded-chunk-read');server=root/'installed-server';out=root/'qualification/runtime-first'
assert server.resolve().is_relative_to(root.resolve()) and (server/'OWNER').read_text().strip()=='block-reality-bounded-chunk-runtime-v1'
env=dict(os.environ,OPENBLAS_CORETYPE='Haswell',OPENBLAS_NUM_THREADS='1');env.pop('BR_ENGINE',None);env.pop('BR_SIDECAR',None)
with (out/'server-first.log').open('xb') as log:
    process=subprocess.Popen(['bash','run.sh','nogui'],cwd=server,env=env,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
    (out/'process-owner.json').write_text(json.dumps({'pid':process.pid,'process_group':process.pid,'cwd':str(server),'deadline_seconds':300}))
    print('Owned bounded-reader server PID',process.pid,flush=True)
    try:code=process.wait(timeout=300)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid,signal.SIGTERM)
        try:process.wait(timeout=20)
        except subprocess.TimeoutExpired:os.killpg(process.pid,signal.SIGKILL);process.wait()
        (out/'exit.json').write_text(json.dumps({'exit':process.returncode,'timeout':True}));raise
(out/'exit.json').write_text(json.dumps({'exit':code,'timeout':False}))
result=json.loads((out/'result.json').read_text()) if (out/'result.json').exists() else {'status':'MISSING'}
print('Installed process',code,'probe',result.get('status'),'checks',len(result.get('checks',[])),flush=True)
sys.exit(0 if code==0 and result.get('status')=='PASS' else 1)
