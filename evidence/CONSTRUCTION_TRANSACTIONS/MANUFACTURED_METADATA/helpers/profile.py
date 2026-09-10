from pathlib import Path
import subprocess,sys,os,json
win=sys.platform=='win32';mode=sys.argv[1];assert mode in ['seed','measure']
root=Path('C:/Users/wmc02/Desktop/block-reality') if win else Path('/home/rocky/br-manufactured-metadata')
out=root/'build/manufactured-metadata' if win else root/'qualification'
env=dict(os.environ,PYTHONUTF8='1',PYTHONIOENCODING='utf-8');env.pop('BR_ENGINE',None);env.pop('BR_SIDECAR',None)
if win:
    env['JAVA_HOME']='C:/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot'
    env['PATH']=env['JAVA_HOME']+'/bin;'+env['PATH']
tag='profile-'+mode+'-'+('windows' if win else 'linux')
command=(['cmd','/c','gradlew.bat'] if win else ['bash','gradlew'])+['--no-daemon','--console=plain','-I','../scripts/manufactured-metadata-profile.gradle',
         ':core:manufacturedMetadataProfile','-Dbr.metadataMode='+mode,'-Dbr.metadataFixture='+str(out/'profile-fixture'),
         '-Dbr.metadataOutput='+str(out/('profile-windows' if win else 'profile-linux'))]
with (out/(tag+'-first.log')).open('xb') as f:result=subprocess.run(command,cwd=root/'mod',env=env,stdout=f,stderr=subprocess.STDOUT)
(out/(tag+'-exit.json')).write_text(json.dumps({'exit':result.returncode,'command':command}))
print(tag,result.returncode,flush=True)
sys.exit(result.returncode)
