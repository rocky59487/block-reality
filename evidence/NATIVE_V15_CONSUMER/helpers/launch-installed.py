from pathlib import Path
import subprocess, os, sys, json, signal

root = Path('/home/rocky/br-native-v15-consumer'); server = root / 'installed-server'; out = root / 'qualification'
attempt = sys.argv[1]; assert attempt in ['first', 'restart']
assert (server / 'INS_OWNED').read_text().strip() == 'block-reality-installed-native-server-v1'
env = dict(os.environ, OPENBLAS_CORETYPE='Haswell', OPENBLAS_NUM_THREADS='1')
env.pop('BR_ENGINE', None); env.pop('BR_SIDECAR', None)
(server / 'user_jvm_args.txt').write_text('-Xms512m\n-Xmx2g\n-Xlog:class+load=info:file=../qualification/installed-classload-' + attempt + '.log\n')
with (out / ('installed-server-' + attempt + '.log')).open('x') as log:
    process = subprocess.Popen(['bash', 'run.sh', 'nogui'], cwd=server, env=env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    (out / ('installed-owner-' + attempt + '.json')).write_text(json.dumps({'pid': process.pid, 'process_group': process.pid, 'command': ['bash', 'run.sh', 'nogui'], 'cwd': str(server), 'deadline_seconds': 480}))
    print('Owned installed server process', process.pid, flush=True)
    try: code = process.wait(timeout=480)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGTERM)
        try: process.wait(timeout=20)
        except subprocess.TimeoutExpired: os.killpg(process.pid, signal.SIGKILL); process.wait()
        raise
(out / ('installed-server-' + attempt + '-exit.json')).write_text(json.dumps({'exit_code': code}))
print('Owned installed server exited', code); sys.exit(code)
