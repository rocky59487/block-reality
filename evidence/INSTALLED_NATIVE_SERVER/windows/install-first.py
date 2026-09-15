from pathlib import Path
import urllib.request,hashlib,json,subprocess,os
root=Path('/home/rocky/br-installed-native-server');root.mkdir()
out=root/'qualification';out.mkdir()
url='https://maven.minecraftforge.net/net/minecraftforge/forge/1.20.1-47.4.13/forge-1.20.1-47.4.13-installer.jar'
with urllib.request.urlopen(url,timeout=45) as reply:body=reply.read(20_000_001)
assert len(body)<20_000_001
assert hashlib.sha1(body).hexdigest()=='790949ee0cb4671175a806befa370d69008b4b4e'
installer=root/'forge-1.20.1-47.4.13-installer.jar';installer.write_bytes(body)
(out/'installer.json').write_text(json.dumps({'url':url,'sha1':hashlib.sha1(body).hexdigest(),'sha256':hashlib.sha256(body).hexdigest(),'bytes':len(body),'criteria':'474fb12'},indent=2)+'\n')
env=os.environ.copy();env.pop('BR_ENGINE',None)
command=['java','-jar',str(installer),'--installServer',str(root/'server')]
with (out/'install-first.log').open('wb') as log:result=subprocess.run(command,cwd=root,env=env,stdout=log,stderr=subprocess.STDOUT,timeout=600)
(out/'install-first-exit.json').write_text(json.dumps({'command':command,'exit':result.returncode})+'\n')
print('Official Forge installer exit',result.returncode,flush=True)
raise SystemExit(result.returncode)
