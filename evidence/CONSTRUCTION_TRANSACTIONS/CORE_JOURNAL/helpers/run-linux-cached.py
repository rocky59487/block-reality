from pathlib import Path
import subprocess,os,sys,json
root=Path('/home/rocky/br-construction-transactions'); out=root/'qualification'
project=sys.argv[1]
env=dict(os.environ,OPENBLAS_CORETYPE='Haswell',OPENBLAS_NUM_THREADS='1',BR_ENGINE='/home/rocky/br-native-candidate-runtime/staged/linux-x86_64/libbsi_tectonic.so',BR_SIDECAR='/home/rocky/br-native-candidate-runtime/dist/br-sidecar',PYTHONUTF8='1',PYTHONIOENCODING='utf-8')
assert Path(env['BR_ENGINE']).is_file(); assert Path(env['BR_SIDECAR']).is_file()
command=['bash','gradlew','--no-daemon']
if project=='core': command+=['check',':core:constructionRecoveryGate','-Dbr.transactionEvidence='+str(out/'process-linux-cached')]; cwd=root/'mod'
elif project=='forge': command+=['build','-PbrNativesDir=/home/rocky/br-native-candidate-runtime/staged']; cwd=root/'forge'
else: raise ValueError(project)
command+=['--console=plain']
(out/('linux-'+project+'-cached-command.json')).write_text(json.dumps({'command':command,'cwd':str(cwd),'BR_ENGINE':env['BR_ENGINE'],'BR_SIDECAR':env['BR_SIDECAR']},indent=2))
log=out/('linux-'+project+'-cached.log')
with log.open('x') as f: result=subprocess.run(command,cwd=cwd,env=env,stdout=f,stderr=subprocess.STDOUT)
print('\n'.join(log.read_text().splitlines()[-45:])); sys.exit(result.returncode)
