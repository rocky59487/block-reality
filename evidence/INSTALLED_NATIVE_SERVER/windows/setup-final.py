from pathlib import Path
import shutil,socket,json,hashlib
base=Path('/home/rocky/br-installed-native-server');source=base/'server';final=base/'final-server';out=base/'qualification'
for port in [25596,25597]:
    with socket.socket() as sock:sock.bind(('127.0.0.1',port))
assert json.loads((out/'control-after-restart.json').read_text())['restart_comparison']=='PASS'
final.mkdir();shutil.copytree(source/'libraries',final/'libraries')
for name in ['run.sh','run.bat','server.properties','eula.txt','INS_OWNED']:shutil.copy2(source/name,final/name)
shutil.copytree(source/'defaultconfigs',final/'defaultconfigs')
(final/'user_jvm_args.txt').write_text('-Xms512m\n-Xmx2g\n-Xlog:class+load=info:file=../qualification/final-classload.log\n')
jar=Path('/mnt/c/Users/wmc02/Desktop/block-reality/build/installed-native-server/blockreality-0.4.0-dev.jar')
assert hashlib.sha256(jar.read_bytes()).hexdigest()=='7bdfce5d1581bfadf43b769d8aeabc0cec06e7f73fe48cd1c9b1fd6c5fdb7b3c'
(final/'mods').mkdir();shutil.copy2(jar,final/'mods'/jar.name)
assert not (final/'blockreality').exists() and not (final/'runtime-smoke').exists()
(out/'final-install.json').write_text(json.dumps({'module':'ae6a4eb','jar_sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'server':str(final),'fresh_world':True,'initial_cache_exists':False,'forge_installation':'copy of verified official installation libraries/run scripts; no control world/cache copied'},indent=2)+'\n')
print('Fresh final installed server with exact new jar and no native cache')
