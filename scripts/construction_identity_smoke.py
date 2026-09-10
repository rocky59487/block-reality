#!/usr/bin/env python3
"""CI-7: actual world callbacks, persistent identities, OFF mode and native preview links."""
import argparse
import json
import os
from pathlib import Path
import re
import time
from rcon_client import MinecraftRCON


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config',required=True);parser.add_argument('--out',required=True)
    parser.add_argument('--phase',choices=['prepare','restart','recover'],required=True)
    args=parser.parse_args();config=Path(args.config)
    props=dict(s.split('=',1) for s in config.read_text().splitlines() if s and not s.startswith('#') and '=' in s)
    assert props['level-name']=='construction-smoke' and props['server-ip']=='127.0.0.1'
    assert props['server-port']=='25590' and props['rcon.port']=='25591'
    client=MinecraftRCON('127.0.0.1',25591,props['rcon.password'])
    assert client.connect(timeout=45),'isolated CI server unavailable'
    out=Path(args.out);out.parent.mkdir(parents=True,exist_ok=True)
    doc=json.loads(out.read_text()) if out.exists() else {'commands':[],'gates':[]}
    def save():out.write_text(json.dumps(doc,indent=2,ensure_ascii=False)+'\n')
    def cmd(text):
        result=re.sub('§.','',client.command(text));doc['commands'].append({'phase':args.phase,'command':text,'response':result});save();return result
    def gate(name,condition):
        doc['gates'].append({'name':name,'pass':bool(condition)});save();assert condition,name
        print(name+': PASS',flush=True)
    def wait(command,predicate,name):
        deadline=time.monotonic()+45
        while True:
            result=cmd(command)
            if predicate(result):gate(name,True);return result
            if time.monotonic()>deadline:gate(name+': '+result,False)
            time.sleep(.25)
    def obj(x=4104,y=200,z=8):return wait(f'br object {x} {y} {z}',lambda s:'  CURRENT' in s,'object current')
    def key(text):return re.search(r'object ([0-9a-f-]+/\d+)',text).group(1)
    def number(text):return int(key(text).rsplit('/',1)[1])
    def snapshot():return wait('br_identity_snapshot',lambda s:'CI CURRENT' in s,'registry current')
    def digest(text):return re.search(r'sha=([0-9a-f]{64})',text).group(1)
    def mode(value):
        path=config.parent/'construction-smoke/serverconfig/blockreality-server.toml';old=path.read_text()
        new,n=re.subn(r'(?m)^(\s*mode\s*=\s*)"(?:OFF|INPROCESS)"',lambda m:m.group(1)+'"'+value+'"',old);assert n==1
        with path.open('w') as f:f.write(new);f.flush();os.fsync(f.fileno())
        os.utime(path,None)
    if args.phase=='prepare':
        gate('CI-4 starts OFF before native load','analysis        OFF' in cmd('br status'))
        cmd('forceload add 4096 0');cmd('forceload add 4144 0');time.sleep(2)
        cmd('setblock 4103 200 8 minecraft:stone');cmd('fill 4104 200 8 4108 200 8 blockreality:steel_beam[axis=x]')
        beam=obj();first=key(beam);gate('CI-4 OFF still publishes identity','cells 5' in beam and 'native links unavailable' in beam)
        cmd('fill 4104 200 9 4106 200 9 blockreality:steel_beam[axis=x]');parallel=obj(4104,200,9)
        gate('CI-1 parallel frame runs stay separate',key(parallel)!=first)
        cmd('fill 4104 200 9 4106 200 9 minecraft:air')
        cmd('setblock 4106 200 8 minecraft:air');left=obj();right=obj(4108)
        gate('CI-2 split retires root and records both children',key(left)!=first and key(right)!=first
             and key(left)!=key(right) and f'parents [{int(first.rsplit("/",1)[1])}]' in left
             and f'parents [{int(first.rsplit("/",1)[1])}]' in right)
        cmd('setblock 4106 200 8 blockreality:steel_beam[axis=x]');merged=obj()
        gate('CI-2 merge records both parents',f'parents [{number(left)}, {number(right)}]' in merged)
        old=key(merged);cmd('br_identity_replace');replaced=obj()
        gate('CI-2 complete same-command replacement cannot resurrect old ID',key(replaced)!=old)
        cmd('fill 4104 205 8 4105 206 8 blockreality:concrete_beam[axis=x]')
        mono=obj(4104,205,8);gate('CI-1 monolith face fusion','MONOLITH' in mono and 'cells 4' in mono)
        cmd('fill 4104 205 8 4105 206 8 minecraft:air')
        cmd('fill 4104 210 8 4105 210 9 blockreality:concrete_slab[axis=y]')
        panel=obj(4104,210,8);gate('CI-1 panel presentation region','PANEL' in panel and 'cells 4' in panel)
        cmd('fill 4104 210 8 4105 210 9 minecraft:air')
        # Extend beyond the retained FULL chunks for a real partial-unload test.
        cmd('fill 4109 200 8 4152 200 8 blockreality:steel_beam[axis=x]')
        beam=obj();gate('CI-2 extension preserves identity',key(beam)==key(replaced) and 'cells 49' in beam)
        mode('INPROCESS')
        wait('br status',lambda s:'CURRENT' in s and '49   test loads' in s and '1 members' in s,'CI-7 native complete baseline')
        beam=obj();gate('CI-6 native zero ID is a preview link','members [0]' in beam and 'source=' in beam)
        gate('CI-6 section zero is inspectable','CURRENT' in cmd('br section 0') and '#0' in cmd('br section 0'))
        # A declared column remains one object when the engine cuts it at a crossing beam.
        cmd('setblock 4160 199 8 minecraft:stone');cmd('fill 4160 200 8 4160 204 8 blockreality:steel_beam[axis=y]')
        cmd('fill 4161 202 8 4165 202 8 blockreality:steel_beam[axis=x]')
        wait('br status',lambda s:'CURRENT' in s and '59   test loads' in s,'CI-7 native crossing fixture')
        column=obj(4160,200,8);members=re.search(r'members \[([^\]]*)\]',column)
        gate('CI-6 one object links to multiple native elements',members and len(members.group(1).split(','))>=2)
        cmd('fill 4160 200 8 4160 204 8 minecraft:air');cmd('fill 4161 202 8 4165 202 8 minecraft:air')
        wait('br status',lambda s:'CURRENT' in s and '49   test loads' in s,'CI-7 return to baseline')
        doc['object']=key(obj());doc['graph']=digest(snapshot());save()
        cmd('br resolve');wait('br status',lambda s:'CURRENT' in s,'CI-6 resolve completes')
        gate('CI-6 solve never renumbers construction graph',digest(snapshot())==doc['graph'])
        cmd('forceload remove 4144 0');wait('br status',lambda s:'Waiting for unloaded input' in s and 'CURRENT' not in s,'CI-3 partial unload waits')
        absent=obj(4152);gate('CI-3 unloaded object still inspectable with stale native links',key(absent)==doc['object'] and 'native links unavailable' in absent)
        gate('CI-3 unloading leaves canonical graph unchanged',digest(snapshot())==doc['graph'])
        cmd('save-all flush');cmd('stop')
    elif args.phase=='restart':
        wait('br status',lambda s:'Waiting for unloaded input' in s,'CI-3 restarted world remains incomplete')
        gate('CI-3 persistent unloaded identity survives restart',key(obj(4152))==doc['object'] and digest(snapshot())==doc['graph'])
    else:
        cmd('forceload add 4144 0');wait('br status',lambda s:'CURRENT' in s and '49   test loads' in s,'CI-3 complete native recovery')
        gate('CI-3 reload preserves graph and native preview mapping',key(obj())==doc['object'] and digest(snapshot())==doc['graph'] and 'members [0]' in obj())
        cmd('fill 4104 200 8 4152 200 8 minecraft:air');empty=wait('br_identity_snapshot',lambda s:'CI CURRENT' in s and 'active=0 ' in s,'CI-4 empty world completes retirement')
        gate('CI-2 retired lineage remains in empty world','records=0 ' not in empty)
        cmd('save-all flush');cmd('stop')
    client.close()


if __name__=='__main__':main()
