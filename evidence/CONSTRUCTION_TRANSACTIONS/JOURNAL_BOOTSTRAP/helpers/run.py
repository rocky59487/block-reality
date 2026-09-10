from pathlib import Path
import os,sys,subprocess,json,shutil
win=sys.platform=='win32'
root=Path('C:/Users/wmc02/Desktop/block-reality') if win else Path('/home/rocky/br-ct-journal-bootstrap')
out=root/'build/ct-journal-bootstrap' if win else root/'qualification'
out.mkdir(exist_ok=True)
staged=Path('C:/Users/wmc02/Desktop/block-reality/build/native-v1.5-consumer/staged') if win else Path('/home/rocky/br-native-v15-consumer/staged')
env=dict(os.environ,PYTHONUTF8='1',PYTHONIOENCODING='utf-8',OPENBLAS_CORETYPE='Haswell',OPENBLAS_NUM_THREADS='1')
env['BR_ENGINE']=str(staged/('windows-x86_64/bsi_tectonic.dll' if win else 'linux-x86_64/libbsi_tectonic.so'))
env['BR_SIDECAR']=str(root/'dist/br-sidecar.exe') if win else '/home/rocky/br-native-candidate-runtime/dist/br-sidecar'
if win:
    env['JAVA_HOME']='C:/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot'
    env['PATH']=env['JAVA_HOME']+'/bin;'+env['PATH']
for phase,folder,tasks in [('core','mod',['check']),('process','mod',[':core:constructionRecoveryGate','-Dbr.transactionEvidence='+str(out/'process')]),('forge','forge',['build','-PbrNativesDir='+str(staged)])]:
    command=(['cmd','/c','gradlew.bat'] if win else ['bash','gradlew'])+['--no-daemon','--console=plain',*tasks]
    with (out/(phase+'-first.log')).open('xb') as log:
        result=subprocess.run(command,cwd=root/folder,env=env,stdout=log,stderr=subprocess.STDOUT)
    (out/(phase+'-exit.json')).write_text(json.dumps({'command':command,'exit':result.returncode}))
    print(phase,result.returncode,flush=True)
    if result.returncode:sys.exit(result.returncode)
shutil.copy2(root/'forge/build/libs/blockreality-0.4.0-dev.jar',out/'blockreality-0.4.0-dev-ct-bootstrap.jar')
