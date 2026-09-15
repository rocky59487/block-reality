#!/usr/bin/env python3
"""WR-9: isolated real server/native coverage, unload, restart and exact result recovery."""
import argparse
import json
from pathlib import Path
import re
import time
from rcon_client import MinecraftRCON


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--config', required=True)
    p.add_argument('--out', required=True)
    p.add_argument('--phase', choices=['prepare', 'restart', 'recover'], required=True)
    args = p.parse_args()
    props = dict(line.split('=', 1) for line in Path(args.config).read_text().splitlines()
                 if line and not line.startswith('#') and '=' in line)
    assert props['level-name'] == 'world-registry-smoke' and props['server-ip'] == '127.0.0.1'
    assert props['server-port'] == '25588' and props['rcon.port'] == '25589'
    client = MinecraftRCON('127.0.0.1', 25589, props['rcon.password'])
    if not client.connect(timeout=45):
        raise ConnectionError('isolated WR server did not start')
    out = Path(args.out); out.parent.mkdir(parents=True, exist_ok=True)
    doc = json.loads(out.read_text()) if out.exists() else {'commands': [], 'gates': []}

    def save():
        out.write_text(json.dumps(doc, indent=2, ensure_ascii=False) + '\n')

    def command(text):
        response = re.sub('§.', '', client.command(text))
        doc['commands'].append({'phase': args.phase, 'command': text, 'response': response})
        save(); return response

    def gate(name, condition):
        doc['gates'].append({'name': name, 'pass': bool(condition)}); save()
        assert condition, name
        print(name + ': PASS', flush=True)

    def wait(name, condition):
        until = time.monotonic() + 55
        while True:
            reply = command('br status')
            if condition(reply):
                gate(name, True); return reply
            if time.monotonic() >= until:
                gate(name + ': ' + reply, False)
            time.sleep(.5)

    def current(s):
        return 'CURRENT' in s and '1 members' in s and '49   test loads' in s

    def waiting(s):
        return 'Waiting for unloaded input:' in s and '49   test loads' in s and 'CURRENT' not in s

    def fingerprint(s, field):
        return re.search(field + r'=([0-9a-f]{64}|absent)', s).group(1)

    if args.phase == 'prepare':
        # Forced chunks load neighbours too. A 49-cell beam spans enough chunks for the far end
        # to actually unload while the anchored end stays loaded; a five-cell beam cannot do that.
        command('forceload add 4096 0'); command('forceload add 4144 0')
        time.sleep(2)
        command('setblock 4103 200 8 minecraft:stone')
        command('fill 4104 200 8 4152 200 8 blockreality:steel_beam[axis=x]')
        command('execute positioned 4104 200 8 run br scan 8')
        wait('WR-9 complete native baseline', current)
        baseline = command('br_registry_probe')
        gate('WR-8 current bootstrap carries cached result', 'bootstrap=RESULT payload=true' in baseline)
        doc['index'] = fingerprint(baseline, 'index'); doc['native'] = fingerprint(baseline, 'native'); save()
        command('forceload remove 4144 0')
        wait('WR-9 unloading retains 49 cells and defers new result', waiting)
        cut = command('br_registry_probe')
        gate('WR-8 waiting bootstrap has explanation and no result',
             'bootstrap=PENDING payload=false detail=Waiting' in cut)
        gate('WR-9 unloaded index and previous native result unchanged',
             fingerprint(cut, 'index') == doc['index'] and fingerprint(cut, 'native') == doc['native'])
        command('save-all flush'); command('stop')
    elif args.phase == 'restart':
        wait('WR-9 restart remembers unloaded cells and waits', waiting)
        cut = command('br_registry_probe')
        gate('WR-9 restart preserves exact index without fresh partial native result',
             fingerprint(cut, 'index') == doc['index'] and fingerprint(cut, 'native') == 'absent')
    else:
        command('forceload add 4144 0')
        wait('WR-9 reload resumes complete native result', current)
        recovered = command('br_registry_probe')
        gate('WR-9 recovered native packet bytes identical after revision normalization',
             fingerprint(recovered, 'index') == doc['index'] and fingerprint(recovered, 'native') == doc['native'])
        # Keep every structural cell readable, but put the final six-face observation across
        # the FULL boundary. Missing air/support observations must defer just like missing structure.
        command('fill 4144 200 8 4152 200 8 minecraft:air')
        command('execute positioned 4104 200 8 run br scan 8')
        wait('WR-6 40-cell ground-border baseline', lambda s:
             'CURRENT' in s and '1 members' in s and '40   test loads' in s)
        ground = command('br_registry_probe')
        command('forceload remove 4144 0')
        wait('WR-6 unreadable ground alone defers all 40 retained cells', lambda s:
             'Waiting for unloaded input: 1 cell(s)' in s and '40   test loads' in s and 'CURRENT' not in s)
        pending = command('br_registry_probe')
        gate('WR-6 ground deferral leaves index and old result unchanged',
             fingerprint(ground, 'index') == fingerprint(pending, 'index')
             and fingerprint(ground, 'native') == fingerprint(pending, 'native'))
        command('forceload add 4144 0')
        wait('WR-6 ground availability resumes analysis', lambda s:
             'CURRENT' in s and '1 members' in s and '40   test loads' in s)
        command('save-all flush'); command('stop')


if __name__ == '__main__':
    main()
