"""MG supplemental actual baked-model, crosshair and vanilla interaction observations."""
import argparse
import json
from pathlib import Path
import re
import time
from rcon_client import MinecraftRCON

# Independent fixed extents: the oracle does not import the production presentation mapper.
BOUNDS = {
    'steel_beam': {'x': [0,.3,.4,1,.7,.6], 'y': [.3,0,.4,.7,1,.6], 'z': [.4,.3,0,.6,.7,1]},
    'steel_beam_150x300': {'x': [0,.35,.425,1,.65,.575], 'y': [.35,0,.425,.65,1,.575], 'z': [.425,.35,0,.575,.65,1]},
    'steel_beam_100x200': {'x': [0,.4,.45,1,.6,.55], 'y': [.4,0,.45,.6,1,.55], 'z': [.45,.4,0,.55,.6,1]},
    'timber_beam': {'x': [0,.38,.43,1,.62,.57], 'y': [.38,0,.43,.62,1,.57], 'z': [.43,.38,0,.57,.62,1]},
}
PRODUCTS = ['steel_beam','steel_beam_150x300','steel_beam_100x200','timber_beam',
            'concrete_beam','brick_pier','concrete_slab','concrete_slab_150','steel_plate']
ENDS = {'steel_beam':'netherite_block','steel_beam_150x300':'netherite_block','steel_beam_100x200':'netherite_block',
        'timber_beam':'stripped_oak_log_top','concrete_beam':'smooth_stone','brick_pier':'red_terracotta',
        'concrete_slab':'polished_andesite','concrete_slab_150':'polished_andesite','steel_plate':'netherite_block'}
SIDES = {'steel_beam':'iron_block','steel_beam_150x300':'iron_block','steel_beam_100x200':'iron_block',
         'timber_beam':'stripped_oak_log','concrete_beam':'gray_concrete','brick_pier':'bricks',
         'concrete_slab':'smooth_stone','concrete_slab_150':'smooth_stone','steel_plate':'iron_block'}
CELL = [0,0,0,1,1,1]


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--config',type=Path,required=True); p.add_argument('--out',type=Path,required=True)
    p.add_argument('--stage',choices=['models','interact','capture'],required=True)
    a=p.parse_args(); props=dict(line.split('=',1) for line in a.config.read_text().splitlines()
                               if line and not line.startswith('#') and '=' in line)
    assert props['level-name']=='render-probe' and props['server-ip']=='127.0.0.1'
    assert props['server-port']=='25594' and props['rcon.port']=='25595'
    assert (a.out/'CRP_OWNED').read_text().strip()=='block-reality-client-render-probe-v1'
    record=a.out/f'mg-{a.stage}-events.json'; assert not record.exists(), 'preserve prior attempts'
    events=[]; client=MinecraftRCON('127.0.0.1',25595,props['rcon.password']); assert client.connect(45)
    def save(): record.write_text(json.dumps(events,indent=2,ensure_ascii=False),encoding='utf-8')
    def cmd(command):
        reply=re.sub('§.','',client.command(command)); events.append({'command':command,'response':reply}); save(); return reply
    def gate(name,condition):
        events.append({'gate':name,'pass':bool(condition)}); save(); assert condition,name; print(name+': PASS',flush=True)
    def until(name,fn,timeout=90):
        deadline=time.monotonic()+timeout
        while time.monotonic()<deadline:
            if (a.out/'fatal.txt').exists(): raise AssertionError((a.out/'fatal.txt').read_text())
            value=fn()
            if value:return value
            time.sleep(.2)
        gate(name+' timed out',False)
    def local(): return json.loads((a.out/'state.json').read_text()) if (a.out/'state.json').exists() else {}
    def server(): return json.loads(cmd('br_render_state'))
    def control(action,name,**extra):
        current=a.out/'control.json'; ident=json.loads(current.read_text())['id']+1 if current.exists() else 1
        value={'id':ident,'action':action,'name':name,**extra}
        assert not (a.out/(name+'.json')).exists(), 'receipt overwrite'
        (a.out/'control.next').write_text(json.dumps(value)); (a.out/'control.next').replace(current)
        return until(name,lambda: json.loads(path.read_text()) if (path:=a.out/(name+'.json')).exists() else None)
    def near(actual,expected): return actual is not None and len(actual)==len(expected) and all(abs(x-y)<2e-6 for x,y in zip(actual,expected))
    def capture(name,view=None,language='en_us',width=1920):
        s=server(); settings={'width':width,'height':1080 if width==1920 else 720,'scale':2,'language':language,
                            'mode':'MATERIAL','worldRevision':s['worldRevision'],'kind':s['kind']}
        if view:settings['view']=view
        r=control('capture',name,**settings)
        gate(name+' actual screenshot and revision',r['png_bytes']>0 and r['worldRevision']==s['worldRevision']
             and r['width']==width and r['language']==language)
    try:
        until('connected',lambda:local().get('connected'))
        if a.stage=='models':
            receipt=control('models','mg-models')
            gate('all nine registered products observed',set(x['id'] for x in receipt['products'])=={'blockreality:'+x for x in PRODUCTS})
            for product in receipt['products']:
                name=product['id'].split(':')[1]
                gate(name+' four actual states',set(s['axis'] for s in product['states'])=={'x','y','z','undeclared'})
                for s in product['states']:
                    axis=s['axis']; expected=BOUNDS.get(name,{}).get(axis,CELL); baked=s['baked']; quads=baked['quads']
                    gate(name+' '+axis+' baked target and collision agree',all(near(value,expected)
                         for value in [baked['bounds'],s['shape'],s['collision']]))
                    gate(name+' '+axis+' real atlas sprites',len(quads)==(90 if axis=='undeclared' else 6)
                         and all('missing' not in q['sprite'] for q in quads) and 'missing' not in baked['particle'])
                    if axis=='undeclared':
                        warning=[q for q in quads if q['sprite']=='minecraft:block/yellow_concrete']
                        gate(name+' warning on all six faces',len(warning)==12 and len({q['face'] for q in warning})==6)
                    else:
                        end_faces={'x':{'east','west'},'y':{'up','down'},'z':{'north','south'}}[axis]
                        gate(name+' '+axis+' declared end faces',all(q['sprite']=='minecraft:block/'+
                             (ENDS[name] if q['face'] in end_faces else SIDES[name]) for q in quads))
                item=product['item']
                gate(name+' actual item geometry and sprites',near(item['bounds'],BOUNDS.get(name,{}).get('z',CELL))
                     and len(item['quads'])==6 and all('missing' not in q['sprite'] for q in item['quads']))
            return
        if a.stage=='interact':
            cmd('gamemode creative BRRenderProbe')
            cmd('fill 40 198 40 63 198 63 minecraft:stone')
            for command in ['setblock 44 200 44 minecraft:stone','setblock 44 202 48 minecraft:stone','setblock 44 200 52 minecraft:stone']:
                cmd(command)
            cmd('item replace entity BRRenderProbe weapon.mainhand with blockreality:steel_beam')
            until('held structural item',lambda:local().get('interaction',{}).get('mainHand')=='blockreality:steel_beam')
            for axis,camera,anchor,face,target in [
                ('x','47.5 199 44.5 90 0',[44,200,44],'east',[45,200,44]),
                ('y','44.5 199 48.5 0 -90',[44,202,48],'down',[44,201,48]),
                ('z','44.5 199 49.5 0 0',[44,200,52],'north',[44,200,51])]:
                before=server(); cmd('tp BRRenderProbe '+camera); time.sleep(1)
                r=control('interact','mg-place-'+axis); hit=r['before']
                gate(axis+' actual clicked face',hit['hitType']=='BLOCK' and hit['position']==anchor and hit['face']==face and not hit['shift'])
                command='execute if block '+' '.join(map(str,target))+' blockreality:steel_beam[axis='+axis+']'
                until(axis+' server placement',lambda:'Test passed' in cmd(command))
                after=server(); gate(axis+' server revision advanced',after['worldRevision']>before['worldRevision'])
            cmd('item replace entity BRRenderProbe weapon.mainhand with minecraft:air')
            until('empty main hand',lambda:local().get('interaction',{}).get('mainHand')=='minecraft:air')
            control('crouch','mg-crouch-on',down=True)
            until('actual player crouches',lambda:local().get('interaction',{}).get('shift'))
            cmd('tp BRRenderProbe 47.5 199 44.5 90 -5'); time.sleep(1)
            for before_axis,after_axis in [('x','y'),('y','z'),('z','x')]:
                before=server(); r=control('interact','mg-cycle-'+after_axis); hit=r['before']
                gate(before_axis+' actual empty-hand sneak target',hit['position']==[45,200,44] and hit['axis']==before_axis
                     and hit['mainHand']=='minecraft:air' and hit['shift'] and near(hit['shape'],BOUNDS['steel_beam'][before_axis]))
                until(after_axis+' server cycle',lambda:'Test passed' in cmd('execute if block 45 200 44 blockreality:steel_beam[axis='+after_axis+']'))
                gate(after_axis+' server cycle revision advanced',server()['worldRevision']>before['worldRevision'])
                time.sleep(.6)
            control('crouch','mg-crouch-off',down=False)
            until('actual player stands',lambda: not local().get('interaction',{}).get('shift',True))
            cmd('tp BRRenderProbe 47.5 199 44.1 90 0'); time.sleep(1)
            r=control('inspect','mg-ray-through-cell-corner')['observed']
            gate('real ray passes empty part of beam cell',r['hitType']=='BLOCK' and r['position']==[44,200,44]
                 and r['block']=='minecraft:stone' and r['face']=='east')
            cmd('tp BRRenderProbe 47.5 199 44.5 90 0'); time.sleep(1)
            r=control('inspect','mg-ray-hits-section')['observed']
            gate('real ray targets visible beam section',r['position']==[45,200,44] and r['axis']=='x'
                 and near(r['shape'],BOUNDS['steel_beam']['x']) and near(r['collision'],BOUNDS['steel_beam']['x']))
            return
        cmd('gamemode spectator BRRenderProbe')
        cmd('item replace entity BRRenderProbe weapon.mainhand with minecraft:air')
        # Original catalogue remains in the same world; expose the texture without scan colouring.
        for name,camera in [('frames','6 206 19 0 32'),('cells','24 206 19 0 32'),('undeclared','6 204 31 0 20')]:
            cmd('tp BRRenderProbe '+camera); time.sleep(1); capture('mg-unscanned-'+name)
        cmd('gamemode creative BRRenderProbe')
        for slot,product in enumerate(PRODUCTS):cmd(f'item replace entity BRRenderProbe hotbar.{slot} with blockreality:{product}')
        cmd('tp BRRenderProbe 47.5 199 44.5 90 0'); time.sleep(1)
        for lang in ['en_us','zh_tw']:
            capture('mg-items-hotbar-'+lang,language=lang,width=1280)
        control('inventory','mg-inventory-open',down=True)
        capture('mg-items-inventory','inventory')
        control('inventory','mg-inventory-close',down=False)
    finally:save();client.close()


if __name__=='__main__':main()
