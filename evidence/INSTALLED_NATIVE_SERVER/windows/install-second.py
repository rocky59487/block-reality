from pathlib import Path
import hashlib,json,subprocess,os,shutil
root=Path('/home/rocky/br-installed-native-server');out=root/'qualification'
source=Path('/mnt/c/Users/wmc02/Desktop/block-reality/build/installed-native-server/forge-1.20.1-47.4.13-installer.jar')
body=source.read_bytes();assert hashlib.sha1(body).hexdigest()=='790949ee0cb4671175a806befa370d69008b4b4e'
installer=root/source.name;assert not installer.exists();shutil.copy2(source,installer)
(out/'installer.json').write_text(json.dumps({'url':'https://maven.minecraftforge.net/net/minecraftforge/forge/1.20.1-47.4.13/forge-1.20.1-47.4.13-installer.jar','sha1':hashlib.sha1(body).hexdigest(),'sha256':hashlib.sha256(body).hexdigest(),'bytes':len(body),'criteria':'474fb12','download':'Windows curl HTTPS; WSL urllib first attempt HTTP403'},indent=2)+'\n')
(out/'download-first-error.txt').write_text('install-first.py failed before any installation with urllib.error.HTTPError: HTTP Error 403: Forbidden.\nThe same official URL downloads with Windows curl; bytes match the SHA1 frozen from the official Forge page.\n')
env=os.environ.copy()
for name in ['BR_ENGINE','JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS']:env.pop(name,None)
command=['java','-jar',str(installer),'--installServer',str(root/'server')]
with (out/'install-second.log').open('wb') as log:result=subprocess.run(command,cwd=root,env=env,stdout=log,stderr=subprocess.STDOUT,timeout=600)
(out/'install-second-exit.json').write_text(json.dumps({'command':command,'exit':result.returncode})+'\n')
print('Official Forge installer exit',result.returncode,flush=True)
raise SystemExit(result.returncode)
