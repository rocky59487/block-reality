from pathlib import Path
import subprocess,os,json,signal,sys,socket
root=Path('/home/rocky/br-forge-axis-transactions');case,mode=sys.argv[1:3]
assert mode in {'control':['control'],'prepared':['prepared','restart','restart-again'],'flushed':['flushed','restart','restart-again'],'committed':['committed','restart','restart-again'],'inventory':['control'],'visibility':['control'],'restored':['control']}[case]
server=root/('installed-'+case);out=root/'qualification'/('runtime-'+case+'-'+mode)
assert server.resolve().is_relative_to(root.resolve()) and (server/'OWNER').read_text().strip()=='block-reality-axis-runtime-v1'
assert not out.exists();out.mkdir();(out/'OWNER').write_text('block-reality-axis-runtime-v1\n')
with socket.socket() as sock:sock.bind(('127.0.0.1',25600))
(server/'user_jvm_args.txt').write_text('-Xms512m\n-Xmx2g\n-Dbr.axisOutput='+str(out)+'\n-Dbr.axisServer='+str(server)+'\n-Dbr.axisMode='+mode+'\n-Xlog:class+load=info:file='+str(out/'classload.log')+'\n')
env=dict(os.environ,OPENBLAS_CORETYPE='Haswell',OPENBLAS_NUM_THREADS='1');env.pop('BR_ENGINE',None);env.pop('BR_SIDECAR',None)
with (out/'server.log').open('xb') as log:
    process=subprocess.Popen(['bash','run.sh','nogui'],cwd=server,env=env,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
    (out/'process-owner.json').write_text(json.dumps({'pid':process.pid,'process_group':process.pid,'cwd':str(server),'deadline_seconds':300}));print('Owned axis server',case,mode,'PID',process.pid,flush=True)
    try:code=process.wait(timeout=300)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid,signal.SIGTERM)
        try:process.wait(timeout=20)
        except subprocess.TimeoutExpired:os.killpg(process.pid,signal.SIGKILL);process.wait()
        (out/'exit.json').write_text(json.dumps({'exit':process.returncode,'timeout':True}));raise
(out/'exit.json').write_text(json.dumps({'exit':code,'timeout':False}))
result=json.loads((out/'result.json').read_text()) if (out/'result.json').exists() else {'status':'MISSING'}
expected=73 if mode in ['prepared','flushed','committed'] else 0
status='INTERRUPTED' if expected==73 else 'PASS'
print('Axis case',case,mode,'exit',code,'probe',result.get('status'),'checks',len(result.get('checks',[])),flush=True)
sys.exit(0 if code==expected and result.get('status')==status else 1)
