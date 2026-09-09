#!/usr/bin/env python3
"""MP-4/5: guarded native server profiling and the frozen A49/F576/M832 baseline."""
import argparse
import hashlib
import json
import platform
from pathlib import Path
import re
import subprocess
import time
from rcon_client import MinecraftRCON


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config',required=True);parser.add_argument('--out',required=True)
    args=parser.parse_args();config=Path(args.config);out=Path(args.out)
    props=dict(s.split('=',1) for s in config.read_text().splitlines() if s and not s.startswith('#') and '=' in s)
    assert props['level-name']=='pipeline-smoke' and props['server-ip']=='127.0.0.1'
    assert props['server-port']=='25592' and props['rcon.port']=='25593'
    assert not out.exists(),'preserve previous run; use a new output name'
    doc={'host':platform.platform(),'java':subprocess.run(['java','-version'],capture_output=True,text=True).stderr,
         'memory':Path('/proc/meminfo').read_text(),'commands':[],'gates':[],'scenes':[]}
    out.parent.mkdir(parents=True,exist_ok=True)
    client=MinecraftRCON('127.0.0.1',25593,props['rcon.password']);assert client.connect(timeout=45)
    def save():out.write_text(json.dumps(doc,indent=2,ensure_ascii=False)+'\n')
    def cmd(text):
        response=re.sub('§.','',client.command(text));doc['commands'].append({'command':text,'response':response});save();return response
    def state():return json.loads(cmd('br_pipeline_state'))
    def probe():return json.loads(cmd('br_pipeline_probe'))
    def gate(name,condition):
        doc['gates'].append({'name':name,'pass':bool(condition)});save();assert condition,name
        print(name+': PASS',flush=True)
    def current(cells,revision=None):
        until=time.monotonic()+45
        while True:
            s=state()
            if s['current'] and s['objectsReady'] and s['cells']==cells and (revision is None or s['revision']==revision):return s
            if time.monotonic()>until:gate('expected CURRENT: '+json.dumps(s),False)
            time.sleep(.025)
    def resolve(cells):
        previous=state()['revision'];cmd('br resolve');return current(cells,previous+1)
    def clear():
        cmd('fill 4104 200 8 4196 200 36 minecraft:air')
        cmd('fill 4256 210 8 4271 210 23 minecraft:air')
    def frames():
        for r in range(8):
            for c in range(8):
                x,z=4104+12*c,8+4*r
                cmd(f'setblock {x-1} 200 {z} minecraft:stone')
                cmd(f'fill {x} 200 {z} {x+8} 200 {z} blockreality:steel_beam[axis=x]')
    try:
        gate('MP-1 fresh server recorder is disabled',probe()['profile']['session']==0 and not probe()['profile']['stages'])
        cmd('forceload add 4096 0 4287 47');time.sleep(3)
        cmd('setblock 4103 200 8 minecraft:stone');cmd('fill 4104 200 8 4152 200 8 blockreality:steel_beam[axis=x]')
        current(49);before=probe()
        gate('MP-4 native solve records nothing while disabled',not before['profile']['stages'])
        cmd('br profile start');cmd('br profile show');cmd('br profile stop');after=probe()
        gate('MP-3 controls leave world/object/result unchanged',all(before[k]==after[k] for k in ['revision','resultRevision','graphSha','members','shells']))
        frozen=after['profile'];resolve(49)
        gate('MP-3 stop keeps frozen samples despite subsequent solve',probe()['profile']==frozen)
        cmd('br profile start')
        cmd('setblock 4153 200 8 blockreality:steel_beam[axis=x]');current(50)
        cmd('setblock 4153 200 8 minecraft:air');current(49)
        cmd('br profile stop');metadata=probe();doc['metadata']=metadata;save()
        gate('MP-4 real metadata stages recorded',all(metadata['profile']['stages'].get(n,{}).get('observed',0)>=2
             for n in ['METADATA_CAPTURE','METADATA_QUEUE','METADATA_RECONCILE','METADATA_PUBLISH']))
        for name,cells in [('A49',49),('F576',576),('M832',832)]:
            if name=='F576':clear();frames()
            if name=='M832':
                cmd('fill 4255 210 8 4255 210 23 minecraft:stone')
                cmd('fill 4256 210 8 4271 210 23 blockreality:concrete_slab[axis=y]')
            ready=current(cells)
            for _ in range(10):resolve(cells)
            scene={'name':name,'cells':cells,'before':probe(),'warmups':10,'measured':[]};doc['scenes'].append(scene);save()
            cmd('br profile start')
            for i in range(40):
                started=time.monotonic_ns();result=resolve(cells)
                scene['measured'].append({'ordinal':i,'request_to_observed_current_ns':time.monotonic_ns()-started,'state':result});save()
            cmd('br profile stop');scene['after']=probe();scene['command_readout']=cmd('br profile show');save()
            stats=scene['after']['profile']['stages']
            gate(name+' MP-4 all 40 pipeline completions observed',all(stats.get(n,{}).get('observed')==40
                 for n in ['GATHER_PREPARE','GATHER_FINISH','ANALYSIS_QUEUE','ANALYSIS_WORKER','WORLD_MAP','WORLD_ENCODE','RESULT_DECODE','PACKET_BUILD','APPLY']))
            gate(name+' MP-2 native calls and byte counters observed',stats['NATIVE_CALL']['observed']==80
                 and scene['after']['profile']['counters']['REQUEST_BYTES']>0 and scene['after']['profile']['counters']['REPLY_BYTES']>0)
            gate(name+' MP-3 resolves preserve construction graph',scene['before']['graphSha']==scene['after']['graphSha'])
            print(name+' Recorded p95 ms: '+json.dumps({k:round(v['p95Ns']/1e6,3) for k,v in stats.items()}),flush=True)
        cmd('save-all flush');cmd('stop')
    finally:client.close()


if __name__=='__main__':main()
