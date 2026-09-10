from pathlib import Path
import subprocess,os,time,signal,json
root=Path('/home/rocky/br-render-tools');out=Path('/home/rocky/br-hud-readability-final');env=os.environ.copy()
env['PATH']=str(root/'root/usr/bin')+':'+env['PATH']
env['LD_LIBRARY_PATH']=str(root/'root/usr/lib/x86_64-linux-gnu')
env['LIBGL_ALWAYS_SOFTWARE']='1';env['LP_NUM_THREADS']='4'
command=[str(root/'root/usr/bin/xvfb-run'),'-a','-e',str(out/'client-xvfb-first.log'),'-s',f'-screen 0 1920x1080x24 -nolisten tcp -fp {root}/root/usr/share/fonts/X11/misc','bash','gradlew','--no-daemon','-I','../scripts/client-render-probe.gradle','-Dbr.clientProbeOutput='+str(out),'runClient','--console=plain']
started=time.monotonic()
with (out/'client-first.log').open('w') as log:
 process=subprocess.Popen(command,cwd='/home/rocky/br-hud-readability-final-client/forge',env=env,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
 try:code=process.wait(timeout=600)
 except subprocess.TimeoutExpired:
  os.killpg(process.pid,signal.SIGTERM)
  try:process.wait(timeout=10)
  except subprocess.TimeoutExpired:os.killpg(process.pid,signal.SIGKILL);process.wait()
  code='timeout'
receipt={'exit':code,'elapsed_seconds':time.monotonic()-started};(out/'client-first-exit.json').write_text(json.dumps(receipt));print(json.dumps(receipt),flush=True)
raise SystemExit(0 if code==0 else 1)
