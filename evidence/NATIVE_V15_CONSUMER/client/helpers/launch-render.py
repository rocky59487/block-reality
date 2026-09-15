from pathlib import Path
import subprocess,os,time,signal,json,sys

base=Path('/home/rocky/br-native-v15-render');out=base/'observations'
role=sys.argv[1];assert role in ['server','client']
attempt=sys.argv[2] if len(sys.argv)>2 else 'first'
assert attempt in ['first','complete-inputs','ready-profile','complete-profile']
if attempt in ['ready-profile','complete-profile']:out=base/('observations-ready' if attempt=='ready-profile' else 'observations-final')
assert (out/'CRP_OWNED').read_text().strip()=='block-reality-client-render-probe-v1'
env=dict(os.environ,OPENBLAS_CORETYPE='Haswell',OPENBLAS_NUM_THREADS='1',PYTHONUTF8='1')
env.pop('BR_ENGINE',None)
command=['bash','gradlew','--no-daemon','--console=plain','-PbrNativesDir=/home/rocky/br-native-v15-consumer/staged']
if role=='client':
    root=Path('/home/rocky/br-render-tools')
    env['PATH']=str(root/'root/usr/bin')+':'+env['PATH']
    env['LD_LIBRARY_PATH']=str(root/'root/usr/lib/x86_64-linux-gnu')
    env['LIBGL_ALWAYS_SOFTWARE']='1';env['LP_NUM_THREADS']='4'
    command=[str(root/'root/usr/bin/xvfb-run'),'-a','-e',str(out/'client-xvfb-first.log'),'-s',f'-screen 0 1920x1080x24 -nolisten tcp -fp {root}/root/usr/share/fonts/X11/misc']+command
    command+=['-I','../scripts/client-render-probe.gradle','-Dbr.clientProbeOutput='+str(out),'runClient']
else:command+=['-I','../scripts/render-server-probe.gradle','runServer']
started=time.monotonic()
with (out/(role+'-'+attempt+'.log')).open('xb') as log:
    process=subprocess.Popen(command,cwd=base/role/'forge',env=env,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
    (out/(role+'-'+attempt+'-owner.json')).write_text(json.dumps({'pid':process.pid,'cwd':str(base/role/'forge'),'command':command}))
    try:code=process.wait(timeout=900 if role=='server' else 600)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid,signal.SIGTERM)
        try:process.wait(timeout=10)
        except subprocess.TimeoutExpired:os.killpg(process.pid,signal.SIGKILL);process.wait()
        code='timeout'
receipt={'exit':code,'elapsed_seconds':time.monotonic()-started}
(out/(role+'-'+attempt+'-exit.json')).write_text(json.dumps(receipt));print(json.dumps(receipt),flush=True)
raise SystemExit(0 if code==0 else 1)
