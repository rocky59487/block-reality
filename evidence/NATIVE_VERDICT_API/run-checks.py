from pathlib import Path
import os,subprocess,json,shutil,sys
root=Path('C:/Users/wmc02/Desktop/block-reality');out=root/'build/native-verdict-cleanup'
env=dict(os.environ,PYTHONUTF8='1',PYTHONIOENCODING='utf-8',OPENBLAS_CORETYPE='Haswell',OPENBLAS_NUM_THREADS='1')
env['JAVA_HOME']='C:/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot'
env['PATH']=env['JAVA_HOME']+'/bin;'+env['PATH']
env['BR_ENGINE']=str(root/'build/native-v1.5-consumer/staged/windows-x86_64/bsi_tectonic.dll')
env['BR_SIDECAR']=str(root/'dist/br-sidecar.exe')
for phase,cwd,tasks in [('core',root/'mod',['check']),('forge',root/'forge',['build','-PbrNativesDir='+str(root/'build/native-v1.5-consumer/staged')])]:
    command=['cmd','/c','gradlew.bat','--no-daemon','--console=plain',*tasks]
    with (out/(phase+'-first.log')).open('xb') as log: run=subprocess.run(command,cwd=cwd,env=env,stdout=log,stderr=subprocess.STDOUT)
    (out/(phase+'-exit.json')).write_text(json.dumps({'command':command,'exit':run.returncode}))
    print(phase,run.returncode,flush=True)
    if run.returncode:sys.exit(run.returncode)
shutil.copy2(root/'forge/build/libs/blockreality-0.4.0-dev.jar',out/'blockreality-0.4.0-dev-native-verdict.jar')
