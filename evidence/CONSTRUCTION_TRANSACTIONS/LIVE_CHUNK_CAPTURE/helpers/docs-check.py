from pathlib import Path
import subprocess,os,json,sys
root=Path('C:/Users/wmc02/Desktop/block-reality');out=root/'build/live-chunk-capture'
env=dict(os.environ,PYTHONUTF8='1',PYTHONIOENCODING='utf-8')
command=[sys.executable,'-X','utf8','scripts/check_docs.py',str(root/'dist/br-sidecar.exe')]
with (out/'docs-check-windows.log').open('xb') as log:r=subprocess.run(command,cwd=root,env=env,stdout=log,stderr=subprocess.STDOUT)
(out/'docs-check-windows-exit.json').write_text(json.dumps({'exit':r.returncode,'command':command}))
print(r.returncode);sys.exit(r.returncode)
