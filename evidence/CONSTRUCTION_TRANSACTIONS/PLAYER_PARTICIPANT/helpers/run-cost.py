from pathlib import Path
import subprocess, sys, os, json
windows = sys.platform == 'win32'
root = Path('C:/Users/wmc02/Desktop/block-reality') if windows else Path('/home/rocky/br-construction-transactions')
work = Path('C:/Users/wmc02/Desktop/block-reality/build/construction-transactions/player-participant') if windows else Path('/mnt/c/Users/wmc02/Desktop/block-reality/build/construction-transactions/player-participant')
out = work if windows else root / 'qualification/player-participant'
tag = 'windows' if windows else 'linux'
env = dict(os.environ, PYTHONUTF8='1', PYTHONIOENCODING='utf-8')
if windows:
    env['JAVA_HOME'] = 'C:/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot'
    env['PATH'] = env['JAVA_HOME'] + '/bin;' + env['PATH']
command = (['cmd', '/c', 'gradlew.bat'] if windows else ['bash', 'gradlew']) + ['--no-daemon', '--console=plain', '-I', str(work / 'measure.gradle'), '-Dbr.playerCostSource=' + str(work / 'cost-src'), '-Dbr.playerCostOutput=' + str(out / 'cost'), 'constructionPlayerCost']
(out / ('cost-' + tag + '-command.json')).write_text(json.dumps(command, indent=2))
with (out / ('cost-' + tag + '.log')).open('x') as f: result = subprocess.run(command, cwd=root / 'forge', env=env, stdout=f, stderr=subprocess.STDOUT)
(out / ('cost-' + tag + '-exit.json')).write_text(json.dumps({'exit_code': result.returncode}))
print('\n'.join((out / ('cost-' + tag + '.log')).read_bytes().decode('utf-8', errors='replace').splitlines()[-25:]))
sys.exit(result.returncode)
