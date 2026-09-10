from pathlib import Path
import os, sys, subprocess, json, shutil, zipfile

windows = sys.platform == 'win32'
root = Path('C:/Users/wmc02/Desktop/block-reality') if windows else Path('/home/rocky/br-native-v15-consumer')
out = root / ('build/native-v1.5-consumer' if windows else 'qualification')
staged = root / ('build/native-v1.5-consumer/staged' if windows else 'staged')
phase = sys.argv[1]; attempt = sys.argv[2] if len(sys.argv) > 2 else 'first'
tag = ('windows-' if windows else 'linux-') + phase + ('' if attempt == 'first' else '-' + attempt)
env = dict(os.environ, OPENBLAS_CORETYPE='Haswell', OPENBLAS_NUM_THREADS='1', PYTHONUTF8='1', PYTHONIOENCODING='utf-8')
env['BR_ENGINE'] = str(staged / ('windows-x86_64/bsi_tectonic.dll' if windows else 'linux-x86_64/libbsi_tectonic.so'))
env['BR_SIDECAR'] = str(root / 'dist/br-sidecar.exe') if windows else '/home/rocky/br-native-candidate-runtime/dist/br-sidecar'
if windows:
    env['JAVA_HOME'] = 'C:/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot'
    env['PATH'] = env['JAVA_HOME'] + '/bin;' + env['PATH']
    java = Path(env['JAVA_HOME']) / 'bin/java.exe'
else: java = Path(shutil.which('java'))
assert Path(env['BR_ENGINE']).is_file(); assert Path(env['BR_SIDECAR']).is_file()
if phase in ['core', 'forge']:
    command = (['cmd', '/c', 'gradlew.bat'] if windows else ['bash', 'gradlew']) + ['--no-daemon', '--console=plain']
    cwd = root / ('mod' if phase == 'core' else 'forge')
    command += ['check'] if phase == 'core' else ['build', '-PbrNativesDir=' + str(staged)]
elif phase == 'replay':
    cwd = root
    candidates = list((Path.home() / '.gradle/caches/modules-2/files-2.1/net.java.dev.jna/jna/5.12.1').glob('*/jna-5.12.1.jar'))
    assert len(candidates) == 1, candidates
    jna = candidates[0]
    with zipfile.ZipFile(jna) as archive: assert 'com/sun/jna/Library.class' in archive.namelist()
    command = [sys.executable, '-X', 'utf8', str(root / 'scripts/check_native_jar.py'), '--jar', str(out / 'blockreality-0.4.0-dev-v1.5.jar'),
               '--java', str(java), '--classes', str(root / 'mod/core/build/classes/java/test'), '--jna', str(jna),
               '--library', env['BR_ENGINE'], '--out', str(out / ('replay-' + attempt)), '--version', '1.5.0', '--build-sha', '42ba7eb', '--eigen', '--shell-eigen']
elif phase == 'corpus':
    cwd = root
    command = [sys.executable, '-X', 'utf8', str(root / 'contract/conformance/run.py'), '--adapter', 'capi', '--lib', env['BR_ENGINE'], '--case', 'C14-shell-wall-buckling', '--repeat', '3', '--record', str(out / 'c14-corpus.json')]
else: raise ValueError(phase)
(out / (tag + '-command.json')).write_text(json.dumps({'command': command, 'cwd': str(cwd), 'BR_ENGINE': env['BR_ENGINE'], 'source': 'fc5424fc2b7a6376047ded560201d1eb0f0e69bc'}, indent=2))
with (out / (tag + '-first.log')).open('x') as log: result = subprocess.run(command, cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT)
(out / (tag + '-exit.json')).write_text(json.dumps({'exit_code': result.returncode}))
if phase in ['core', 'forge']:
    xml = cwd / ('core/build/test-results/test' if phase == 'core' else 'build/test-results/test')
    if xml.exists(): shutil.copytree(xml, out / (tag + '-xml'))
print('\n'.join((out / (tag + '-first.log')).read_bytes().decode('utf-8', errors='replace').splitlines()[-35:]))
sys.exit(result.returncode)
