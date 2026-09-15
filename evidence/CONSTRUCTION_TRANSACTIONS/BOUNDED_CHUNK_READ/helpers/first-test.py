from pathlib import Path
import os,subprocess,json,sys
root=Path('C:/Users/wmc02/Desktop/block-reality');out=root/'build/bounded-chunk-read'
env=dict(os.environ,PYTHONUTF8='1',PYTHONIOENCODING='utf-8',JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot')
env['PATH']=env['JAVA_HOME']+'/bin;'+env['PATH']
command=['cmd','/c','gradlew.bat','--no-daemon','--console=plain','-I',str(out/'test.gradle'),
         '-Dbr.chunkReadEvidence='+str(out/'first-region-fixtures'),'test',
         '--tests','com.blockreality.impl.server.construction.BoundedChunkReadTest',
         '--tests','com.blockreality.impl.server.construction.ChunkFileParticipantTest']
with (out/'test-first.log').open('xb') as log:result=subprocess.run(command,cwd=root/'forge',env=env,stdout=log,stderr=subprocess.STDOUT)
(out/'test-first-exit.json').write_text(json.dumps({'exit':result.returncode,'command':command}))
print(result.returncode,flush=True);sys.exit(result.returncode)
