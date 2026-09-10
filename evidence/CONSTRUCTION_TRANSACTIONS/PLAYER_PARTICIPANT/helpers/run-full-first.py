from pathlib import Path
import subprocess, os, sys, json, shutil

windows = sys.platform == 'win32'
root = Path('C:/Users/wmc02/Desktop/block-reality') if windows else Path('/home/rocky/br-construction-transactions')
out = root / ('build/construction-transactions/player-participant' if windows else 'qualification/player-participant')
out.mkdir(exist_ok=True)
project = sys.argv[1]
tag = ('windows-' if windows else 'linux-') + project
env = dict(os.environ, OPENBLAS_CORETYPE='Haswell', OPENBLAS_NUM_THREADS='1', PYTHONUTF8='1', PYTHONIOENCODING='utf-8')
if windows:
    env['JAVA_HOME'] = 'C:/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot'
    env['PATH'] = env['JAVA_HOME'] + '/bin;' + env['PATH']
    env['BR_ENGINE'] = str(root / 'build/native-candidate-runtime/staged/windows-x86_64/bsi_tectonic.dll')
    env['BR_SIDECAR'] = str(root / 'dist/br-sidecar.exe')
    staged = str(root / 'build/native-candidate-runtime/staged')
else:
    env['BR_ENGINE'] = '/home/rocky/br-native-candidate-runtime/staged/linux-x86_64/libbsi_tectonic.so'
    env['BR_SIDECAR'] = '/home/rocky/br-native-candidate-runtime/dist/br-sidecar'
    staged = '/home/rocky/br-native-candidate-runtime/staged'
assert Path(env['BR_ENGINE']).is_file()
assert Path(env['BR_SIDECAR']).is_file()
cmd = (['cmd', '/c', 'gradlew.bat'] if windows else ['bash', 'gradlew']) + ['--no-daemon', '--console=plain']
if project == 'core':
    cwd = root / 'mod'
    cmd += ['check', ':core:constructionRecoveryGate', '-Dbr.transactionEvidence=' + str(out / 'process')]
    xml = cwd / 'core/build/test-results/test'
elif project == 'forge':
    cwd = root / 'forge'; cmd += ['build', '-PbrNativesDir=' + staged]
    xml = cwd / 'build/test-results/test'
else: raise ValueError(project)
(out / (tag + '-command.json')).write_text(json.dumps({'command': cmd, 'cwd': str(cwd), 'BR_ENGINE': env['BR_ENGINE'], 'BR_SIDECAR': env['BR_SIDECAR']}, indent=2))
log = out / (tag + '.log')
with log.open('x') as f: result = subprocess.run(cmd, cwd=cwd, env=env, stdout=f, stderr=subprocess.STDOUT)
if xml.exists(): shutil.copytree(xml, out / (tag + '-xml'))
print('\n'.join(log.read_text().splitlines()[-35:]))
sys.exit(result.returncode)
