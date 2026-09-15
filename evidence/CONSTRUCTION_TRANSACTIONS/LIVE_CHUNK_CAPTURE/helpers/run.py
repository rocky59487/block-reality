from pathlib import Path
import os,sys,subprocess,json,shutil
win=sys.platform=='win32'
root=Path('C:/Users/wmc02/Desktop/block-reality') if win else Path('/home/rocky/br-live-chunk-capture')
out=root/'build/live-chunk-capture' if win else root/'qualification'
staged=Path('C:/Users/wmc02/Desktop/block-reality/build/native-v1.5-consumer/staged') if win else Path('/home/rocky/br-native-v15-consumer/staged')
env=dict(os.environ,PYTHONUTF8='1',PYTHONIOENCODING='utf-8',OPENBLAS_CORETYPE='Haswell',OPENBLAS_NUM_THREADS='1')
env['BR_ENGINE']=str(staged/('windows-x86_64/bsi_tectonic.dll' if win else 'linux-x86_64/libbsi_tectonic.so'))
if win:
    env['JAVA_HOME']='C:/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot';env['PATH']=env['JAVA_HOME']+'/bin;'+env['PATH']
name=sys.argv[1] if len(sys.argv)>1 else 'full-first'
command=(['cmd','/c','gradlew.bat'] if win else ['bash','gradlew'])+['--no-daemon','--console=plain']
if name=='test-first':
    command+=['test','--tests','com.blockreality.impl.server.construction.LiveChunkCaptureTest',
              '--tests','com.blockreality.impl.server.construction.BoundedChunkReadTest',
              '--tests','com.blockreality.impl.server.construction.ChunkFileParticipantTest']
else: command+=['build','-PbrNativesDir='+str(staged)]
with (out/(name+'.log')).open('xb') as log:result=subprocess.run(command,cwd=root/'forge',env=env,stdout=log,stderr=subprocess.STDOUT)
(out/(name+'-exit.json')).write_text(json.dumps({'command':command,'exit':result.returncode}))
shutil.copytree(root/'forge/build/test-results/test',out/(name+'-xml'))
print(name,result.returncode,flush=True)
if result.returncode:sys.exit(result.returncode)
if name!='test-first':shutil.copy2(root/'forge/build/libs/blockreality-0.4.0-dev.jar',out/'blockreality-0.4.0-dev-live-capture.jar')
