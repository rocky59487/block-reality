"""HR-3 supplemental large-GUI check against the real, restored CRP native scene."""
import argparse
import json
from pathlib import Path
import re
import time
from rcon_client import MinecraftRCON


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--stage', choices=['restore', 'capture'], required=True)
    args = parser.parse_args()
    props = dict(line.split('=', 1) for line in args.config.read_text().splitlines()
                 if line and not line.startswith('#') and '=' in line)
    assert props['server-ip'] == '127.0.0.1' and props['server-port'] == '25594'
    assert props['rcon.port'] == '25595' and props['level-name'] == 'render-probe'
    assert (args.out / 'CRP_OWNED').read_text().strip() == 'block-reality-client-render-probe-v1'
    record = args.out / (args.stage + '-events.json')
    assert not record.exists(), 'retain first attempts'
    events = []
    rcon = MinecraftRCON('127.0.0.1', 25595, props['rcon.password'])
    assert rcon.connect(30)

    def save(): record.write_text(json.dumps(events, indent=2), encoding='utf-8')
    def command(value):
        response = re.sub('§.', '', rcon.command(value))
        events.append({'command': value, 'response': response}); save()
        return response
    def server(): return json.loads(command('br_render_state'))
    def gate(name, passed):
        events.append({'gate': name, 'pass': bool(passed)}); save()
        assert passed, name
    def wait(name, check):
        until = time.monotonic() + 90
        while time.monotonic() < until:
            fatal = args.out / 'fatal.txt'
            if fatal.exists(): raise AssertionError(fatal.read_text())
            value = check()
            if value: return value
            time.sleep(.2)
        gate(name + ' timeout', False)
    def read(name):
        path = args.out / name
        return json.loads(path.read_text()) if path.exists() else {}
    def control(value):
        value['id'] = read('control.json').get('id', 0) + 1
        path = args.out / 'control.next'; path.write_text(json.dumps(value)); path.replace(args.out / 'control.json')

    try:
        if args.stage == 'restore':
            previous = server()
            gate('only known catalogue world', previous['cells'] == 58 and previous['kind'] == 'MODEL_REFUSED')
            command('fill 0 200 24 32 200 36 minecraft:air')
            expected = wait('native fixture restored', lambda: s if (s := server())['kind'] == 'RESULT'
                            and s['worldRevision'] == s['resultRevision'] and s['objectsReady'] else None)
            gate('unchanged supported fixture payload', expected['cells'] == 22 and expected['members'] == 2
                 and expected['shells'] == 12 and expected['maxDc'] == 0.7597396436387274)
            with (args.out / 'bootstrap-expected.json').open('x') as stream: json.dump(expected, stream, indent=2)
            return
        expected = read('bootstrap-expected.json')
        wait('restored cached client bootstrap', lambda: (s := read('state.json')).get('connected')
             and s.get('worldRevision') == expected['worldRevision'] and s.get('kind') == 'RESULT')
        command('gamemode spectator BRRenderProbe')
        command('item replace entity BRRenderProbe weapon.mainhand with blockreality:stress_glasses')
        command('tp BRRenderProbe 2.5 201 -8 0 15')
        for language in ['en_us', 'zh_tw']:
            name = 'overflow-' + language + '-1280'
            control({'action': 'capture', 'name': name, 'width': 1280, 'height': 720, 'scale': 3,
                     'language': language, 'mode': 'UTILIZATION', 'worldRevision': expected['worldRevision'], 'kind': 'RESULT'})
            receipt = wait(name, lambda: read(name + '.json'))
            gate(name + ' actual large GUI and native payload', receipt['guiScale'] == 3 and receipt['width'] == 1280
                 and receipt['height'] == 720 and receipt['language'] == language and receipt['png_bytes'] > 0
                 and all(receipt[key] == expected[key] for key in ['worldRevision', 'resultRevision', 'members', 'shells', 'maxDc'])
                 and not receipt['stale'] and server()['worldRevision'] == expected['worldRevision'])
        control({'action': 'exit'})
    finally:
        save(); rcon.close()


if __name__ == '__main__': main()
