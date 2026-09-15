"""CRP: control only the isolated loopback rendering world and probe-owned capture folder."""
import argparse
import json
from pathlib import Path
import re
import time
from rcon_client import MinecraftRCON


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--config', type=Path, required=True)
    p.add_argument('--out', type=Path, required=True)
    p.add_argument('--stage', choices=['setup', 'capture', 'stop'], required=True)
    a = p.parse_args()
    props = dict(line.split('=', 1) for line in a.config.read_text().splitlines()
                 if line and not line.startswith('#') and '=' in line)
    assert props['level-name'] == 'render-probe' and props['server-ip'] == '127.0.0.1'
    assert props['server-port'] == '25594' and props['rcon.port'] == '25595'
    assert (a.out / 'CRP_OWNED').read_text().strip() == 'block-reality-client-render-probe-v1'
    record = a.out / f'{a.stage}-events.json'
    assert not record.exists(), 'preserve prior stage records'
    events = []
    client = MinecraftRCON('127.0.0.1', 25595, props['rcon.password'])
    assert client.connect(45), 'isolated server RCON unavailable'

    def save(): record.write_text(json.dumps(events, indent=2, ensure_ascii=False), encoding='utf-8')
    def cmd(command):
        reply = re.sub('§.', '', client.command(command))
        events.append({'command': command, 'response': reply}); save(); return reply
    def server(): return json.loads(cmd('br_render_state'))
    def gate(name, condition):
        events.append({'gate': name, 'pass': bool(condition)}); save()
        assert condition, name
        print(name + ': PASS', flush=True)
    def until(name, function, timeout=60):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if (a.out / 'fatal.txt').exists(): raise AssertionError((a.out / 'fatal.txt').read_text())
            value = function()
            if value: return value
            time.sleep(.2)
        gate(name + ' timed out', False)
    def local():
        path = a.out / 'state.json'
        return json.loads(path.read_text()) if path.exists() else {}
    def control(value):
        current = a.out / 'control.json'
        value['id'] = json.loads(current.read_text())['id'] + 1 if current.exists() else 1
        (a.out / 'control.next').write_text(json.dumps(value))
        (a.out / 'control.next').replace(current)

    try:
        if a.stage == 'stop':
            control({'action': 'exit'}); cmd('stop'); return
        if a.stage == 'setup':
            gate('fresh isolated registry', server()['cells'] == 0)
            for command in ['gamerule doMobSpawning false', 'gamerule doDaylightCycle false',
                            'gamerule doWeatherCycle false', 'time set day', 'weather clear',
                            'forceload add -16 -16 63 63', 'setworldspawn 2 201 -8']:
                cmd(command)
            time.sleep(2)
            for command in [
                'setblock -1 200 0 minecraft:stone',
                'fill 0 200 0 4 200 0 blockreality:steel_beam[axis=x]',
                'setblock 10 199 0 minecraft:stone',
                'fill 10 200 0 10 204 0 blockreality:steel_beam[axis=y]',
                'fill -1 210 10 -1 210 12 minecraft:stone',
                'fill 0 210 10 3 210 12 blockreality:concrete_slab[axis=y]',
            ]: cmd(command)
            def ready():
                s = server()
                return s if s['kind'] == 'RESULT' and s['worldRevision'] == s['resultRevision'] and s['objectsReady'] else None
            state = until('native scenes ready before joining', ready)
            gate('native beam column panel fixture', state['cells'] == 22 and state['members'] == 2 and state['shells'] == 12)
            with (a.out / 'bootstrap-expected.json').open('x') as f: json.dump(state, f, indent=2)
            return

        expected = json.loads((a.out / 'bootstrap-expected.json').read_text())
        connected = until('real client bootstrap', lambda: (s if (s := local()).get('connected')
                          and s.get('worldRevision') == expected['worldRevision'] and s.get('kind') == 'RESULT' else None))
        gate('cached native result arrives without edit or resolve', all(connected[k] == expected[k]
             for k in ['worldRevision', 'resultRevision', 'members', 'shells', 'maxDc'])
             and server()['worldRevision'] == expected['worldRevision'])
        cmd('gamemode spectator BRRenderProbe')
        cmd('item replace entity BRRenderProbe weapon.mainhand with blockreality:stress_glasses')

        def capture(scene, camera, mode, state):
            cmd('tp BRRenderProbe ' + camera)
            time.sleep(1)
            for width, height in [(1280, 720), (1920, 1080)]:
                for language in ['en_us', 'zh_tw']:
                    name = f'{scene}-{language}-{width}'
                    control({'action': 'capture', 'name': name, 'width': width, 'height': height, 'scale': 2,
                             'language': language, 'mode': mode, 'worldRevision': state['worldRevision'], 'kind': state['kind']})
                    receipt_path = a.out / (name + '.json')
                    receipt = until(name, lambda: json.loads(receipt_path.read_text()) if receipt_path.exists() else None, 90)
                    gate(name + ' actual framebuffer and server revision', receipt['width'] == width and receipt['height'] == height
                         and receipt['worldRevision'] == state['worldRevision'] and receipt['kind'] == state['kind']
                         and receipt['language'] == language and receipt['png_bytes'] > 0)
                    gate(name + ' no intervening structure edit', server()['worldRevision'] == state['worldRevision'])
                    if state['kind'] == 'RESULT':
                        gate(name + ' native payload values', all(receipt[k] == state[k]
                             for k in ['resultRevision', 'members', 'shells', 'maxDc']) and not receipt['stale'])

        capture('beam', '2.5 201 -8 0 15', 'UTILIZATION', expected)
        capture('column', '10.5 202 -8 0 5', 'STRESS', expected)
        capture('panel', '2.5 215 6 0 50', 'MATERIAL', expected)
        products = ['steel_beam', 'steel_beam_150x300', 'steel_beam_100x200', 'timber_beam',
                    'concrete_beam', 'brick_pier', 'concrete_slab', 'concrete_slab_150', 'steel_plate']
        for column, product in enumerate(products):
            for row, axis in enumerate(['x', 'y', 'z', 'undeclared']):
                cmd(f'setblock {column * 4} 200 {24 + row * 4} blockreality:{product}[axis={axis}]')
        refused = until('undeclared catalogue refuses model', lambda: s if (s := server())['kind'] == 'MODEL_REFUSED' else None)
        capture('catalogue', '16 215 12 0 30', 'MATERIAL', refused)
        control({'action': 'exit'})
    finally:
        save(); client.close()


if __name__ == '__main__': main()
