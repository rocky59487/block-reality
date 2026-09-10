from pathlib import Path
import shutil, socket, hashlib, json

root = Path('/home/rocky/br-native-v15-consumer'); server = root / 'installed-server'; out = root / 'qualification'
source = Path('/home/rocky/br-installed-native-server/final-server')
for port in [25596, 25597]:
    with socket.socket() as sock: sock.bind(('127.0.0.1', port))
server.mkdir(); shutil.copytree(source / 'libraries', server / 'libraries')
for name in ['run.sh', 'run.bat', 'server.properties', 'eula.txt', 'INS_OWNED']:
    shutil.copyfile(source / name, server / name)
shutil.copytree(source / 'defaultconfigs', server / 'defaultconfigs')
(server / 'mods').mkdir()
jar = out / 'blockreality-0.4.0-dev-v1.5.jar'; digest = hashlib.sha256(jar.read_bytes()).hexdigest()
assert digest == '9d8cb6795e9c41514697a6eb2492fd00ef9eb484b4b5b30f627371967702de30'
shutil.copyfile(jar, server / 'mods/blockreality-0.4.0-dev.jar')
assert not (server / 'runtime-smoke').exists() and not (server / 'blockreality').exists()
(out / 'installed-identity.json').write_text(json.dumps({'jar_sha256': digest, 'native_sha256': '2d048b9bfaf971d6b1971b7b0c36cc4942238448652735ba264dfbddf7d440a4', 'server': str(server), 'source': str(source), 'fresh_world': True, 'fresh_cache': True, 'libraries': 'copied verified official Forge1.20.1-47.4.13 installation; no old worlds, player data or native cache copied'}, indent=2))
print('Fresh installed v1.5 server prepared with exact qualified jar, isolated world and empty native cache')
