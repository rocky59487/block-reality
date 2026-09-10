from pathlib import Path
import sys,json,re,time,os
root=Path('/home/rocky/br-native-candidate-runtime');sys.path.insert(0,str(root/'scripts'))
from rcon_client import MinecraftRCON
out=root/'qualification/runtime-second-setup.json';assert not out.exists()
config=root/'forge/run/runtime-smoke/serverconfig/blockreality-server.toml'
text=config.read_text();text,n=re.subn(r'(?m)^(\s*mode\s*=\s*)"INPROCESS"',r'\1"OFF"',text);assert n==1
with config.open('w') as f:f.write(text);f.flush();os.fsync(f.fileno())
props=dict(line.split('=',1) for line in (root/'forge/run/server.properties').read_text().splitlines() if '=' in line and not line.startswith('#'))
client=MinecraftRCON('127.0.0.1',int(props['rcon.port']),props['rcon.password']);assert client.connect();records=[]
def command(text):
    reply=re.sub('§.','',client.command(text));records.append({'command':text,'response':reply});out.write_text(json.dumps(records,indent=2)+'\n');return reply
try:
    until=time.monotonic()+20
    while 'analysis        OFF' not in command('br status'):
        assert time.monotonic()<until;time.sleep(.5)
    command('forceload add -16 -16 48 32')
    command('setblock -1 200 0 minecraft:stone')
    command('fill 0 200 0 4 200 0 blockreality:steel_beam[axis=x]')
    command('execute positioned 0 200 0 run br scan 2')
    status=command('br status');assert 'analysis        OFF' in status and 'last result     none yet' in status
finally:client.close()
print('Seeded the known supported model while OFF; first empty-world setup failure retained')
