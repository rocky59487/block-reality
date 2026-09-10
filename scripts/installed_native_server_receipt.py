#!/usr/bin/env python3
"""Record the owned installed-server readouts/cache, optionally compare a restart and stop."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import time
from rcon_client import MinecraftRCON


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', required=True, type=Path)
    parser.add_argument('--out', required=True, type=Path)
    parser.add_argument('--baseline', type=Path)
    parser.add_argument('--stop', action='store_true')
    args = parser.parse_args()
    root = args.config.resolve().parent
    props = dict(line.split('=', 1) for line in args.config.read_text(encoding='utf-8').splitlines()
                 if line and not line.startswith('#') and '=' in line)
    assert (root/'INS_OWNED').read_text(encoding='utf-8').strip() == 'block-reality-installed-native-server-v1'
    assert all(props.get(k) == v for k, v in {'level-name':'runtime-smoke', 'server-ip':'127.0.0.1',
               'server-port':'25596', 'rcon.port':'25597'}.items()), 'owned loopback fixture required'
    assert not args.out.exists(), 'do not overwrite an attempt'
    args.out.parent.mkdir(parents=True, exist_ok=True)
    receipt = {'commands': []}
    def save():
        args.out.write_text(json.dumps(receipt, indent=2, ensure_ascii=False)+'\n', encoding='utf-8')
    client = MinecraftRCON('127.0.0.1', 25597, props['rcon.password'])
    if not client.connect():
        raise RuntimeError('installed server is not accepting RCON')
    def command(text):
        reply = re.sub('§.', '', client.command(text))
        receipt['commands'].append({'command':text, 'response':reply}); save()
        return reply
    try:
        until = time.monotonic()+30
        while True:
            status = command('br status')
            if 'engine          READY' in status and '2 members, 12 plate facets' in status and 'CURRENT' in status:
                break
            if time.monotonic() >= until:
                raise AssertionError('persisted native model did not become current: '+status)
            time.sleep(.5)
        members = command('br members')
        assert 'CURRENT' in members
        # Compare observable native values, not manager/session revision identities.
        result = next(line.strip() for line in status.splitlines() if '2 members, 12 plate facets' in line)
        verdicts = [line.strip() for line in status.splitlines() if 'World buckling' in line]
        receipt['native_readouts'] = {'result':result, 'verdicts':verdicts}
        libraries = sorted((root/'blockreality/engine/lib').rglob('*.so'))
        assert len(libraries) == 1
        library = libraries[0]
        digest = hashlib.sha256(library.read_bytes()).hexdigest()
        assert digest == '53aae7156b94c0a761ef5abb2322ad47a708362f417a50ca3f04105d9c50abf1'
        receipt['cache'] = {'path':str(library.relative_to(root)), 'sha256':digest,
                            'bytes':library.stat().st_size, 'mtime_ns':library.stat().st_mtime_ns}
        jars = sorted((root/'mods').glob('*.jar'))
        assert len(jars) == 1 and jars[0].name == 'blockreality-0.4.0-dev.jar'
        receipt['jar_sha256'] = hashlib.sha256(jars[0].read_bytes()).hexdigest()
        if args.baseline:
            baseline = json.loads(args.baseline.read_text(encoding='utf-8'))
            assert receipt['native_readouts'] == baseline['native_readouts'], 'restart changed native readouts'
            assert receipt['cache'] == baseline['cache'], 'restart did not reuse identical native cache'
            assert receipt['jar_sha256'] == baseline['jar_sha256'], 'restart changed installed jar'
            receipt['restart_comparison'] = 'PASS'
        command('save-all flush')
        receipt['result'] = 'PASS'; save()
        if args.stop:
            command('stop')
    finally:
        client.close()
    print('PASS installed native readouts/cache'+(' and restart' if args.baseline else ''), flush=True)


if __name__ == '__main__':
    main()
