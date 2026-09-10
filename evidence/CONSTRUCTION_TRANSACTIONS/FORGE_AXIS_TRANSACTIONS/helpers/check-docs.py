from pathlib import Path
import subprocess,os,json,sys
root=Path('C:/Users/wmc02/Desktop/block-reality');out=root/'build/forge-axis-transactions'
command=[sys.executable,'-X','utf8','scripts/check_docs.py','dist/br-sidecar.exe']
with (out/'docs-first.log').open('xb') as log:r=subprocess.run(command,cwd=root,env=dict(os.environ,PYTHONUTF8='1',PYTHONIOENCODING='utf-8'),stdout=log,stderr=subprocess.STDOUT)
(out/'docs-first-exit.json').write_text(json.dumps({'command':command,'exit':r.returncode,'legacy_existing_executable_only':True,'engine_built':False}));print('Documentation quoted counts exit',r.returncode,flush=True);sys.exit(r.returncode)
