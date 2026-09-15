from pathlib import Path
import shutil,hashlib,json,socket,zipfile,platform,subprocess
base=Path('/home/rocky/br-installed-native-server');server=base/'server';out=base/'qualification'
assert json.loads((out/'install-second-exit.json').read_text())['exit']==0
for port in [25596,25597]:
    with socket.socket() as sock:sock.bind(('127.0.0.1',port))
jar=Path('/mnt/c/Users/wmc02/Desktop/block-reality/build/native-candidate-runtime/blockreality-0.4.0-dev.jar')
assert hashlib.sha256(jar.read_bytes()).hexdigest()=='5a93c66bfe5f5a3dd8c1104a6e97fe000a2a0d2a8e66bf602821993d2a4c9b79'
mods=server/'mods';mods.mkdir(exist_ok=True);assert not list(mods.iterdir());shutil.copy2(jar,mods/jar.name)
assert not (server/'blockreality').exists()
props=Path('/home/rocky/br-native-candidate-runtime/forge/run/server.properties').read_text()
(server/'server.properties').write_text(props);(server/'eula.txt').write_text('eula=true\n')
default=server/'defaultconfigs';default.mkdir(exist_ok=True)
shutil.copy2(Path('/home/rocky/br-native-candidate-runtime/forge/run/defaultconfigs/forge-server.toml'),default/'forge-server.toml')
(server/'user_jvm_args.txt').write_text('-Xms512m\n-Xmx2g\n-Xlog:class+load=info:file=../qualification/control-classload.log\n')
with zipfile.ZipFile(jar) as archive:(out/'control-mods.toml').write_bytes(archive.read('META-INF/mods.toml'))
(out/'control-identity.json').write_text(json.dumps({'jar_sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'jar_bytes':jar.stat().st_size,'criteria':'474fb12','qualified_module':'c2a1b94','server':str(server),'mod_inventory':[jar.name],'initial_native_cache_exists':False,'java':subprocess.check_output(['java','-version'],stderr=subprocess.STDOUT,text=True),'os':platform.platform()},indent=2)+'\n')
print('Installed production control jar in fresh Forge server, empty native cache, loopback only')
