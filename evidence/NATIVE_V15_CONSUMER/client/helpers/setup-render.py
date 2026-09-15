from pathlib import Path
import subprocess, json, tarfile, shutil, hashlib

repo=Path('C:/Users/wmc02/Desktop/block-reality')
out=repo/'build/native-v1.5-consumer'
archive=out/'render-source.tar'
assert not archive.exists()
paths=['mod','forge','contract','scripts','LICENSE','NOTICE','third_party','tectonic2.version']
tracked=subprocess.check_output(['git','ls-files'],cwd=repo,text=True).splitlines()
paths=[p for p in paths if any(x==p or x.startswith(p+'/') for x in tracked)]
subprocess.run(['git','archive','HEAD','-o',str(archive),*paths],cwd=repo,check=True)
(out/'render-source.json').write_text(json.dumps({'source':subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip(),'sha256':hashlib.sha256(archive.read_bytes()).hexdigest(),'paths':paths},indent=2))
