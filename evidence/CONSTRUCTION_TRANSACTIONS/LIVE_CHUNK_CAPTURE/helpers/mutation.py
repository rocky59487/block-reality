from pathlib import Path
import os,subprocess,tarfile,json,shutil,hashlib,time,xml.etree.ElementTree as ET
source=Path('/mnt/c/Users/wmc02/Desktop/block-reality/build/live-chunk-capture')
root=Path('/home/rocky/br-live-capture-mutations');assert not root.exists();root.mkdir()
with tarfile.open(source/'source.tar') as tar:
    for m in tar.getmembers():assert (root/m.name).resolve().is_relative_to(root.resolve()) and (m.isfile() or m.isdir())
    tar.extractall(root,filter='data')
out=root/'evidence';out.mkdir();(root/'OWNER').write_text('block-reality-live-capture-mutations-v1\n')
file=root/'forge/src/main/java/com/blockreality/impl/server/construction/LiveChunkCapture.java'
original=file.read_bytes();text=original.decode();results=[]
def omit(name,next_name):
    start=text.index('    static void '+name);body=text.index('{',start);end=text.index('    '+next_name,body)
    return (text[:body+1]+'\n    }\n\n'+text[end:]).encode()
suite='com.blockreality.impl.server.construction.LiveChunkCaptureTest'
variants=[('control',original,[suite],5),
          ('omit-witness',omit('verifyWitness','static void verifyHook'),[suite+'.droppedOrChangedChunkCapabilitiesNeverBecomeAnEmptySuccess',suite+'.omittedChangedDuplicateAndForeignBlockEntitiesRefuse'],2),
          ('omit-hook-guard',omit('verifyHook','private static byte[] capabilities'),[suite+'.hooksCanAddButCannotReplaceDeleteOrMutateExistingFields'],1),
          ('restored',original,[suite],5)]
env=dict(os.environ,PYTHONUTF8='1',PYTHONIOENCODING='utf-8');env.pop('BR_ENGINE',None);env.pop('BR_SIDECAR',None)
try:
    for phase,data,selected,count in variants:
        file.write_bytes(data);start=time.time();command=['bash','gradlew','--no-daemon','--console=plain','test']
        for test in selected:command+=['--tests',test]
        log=out/(phase+'.log')
        with log.open('xb') as f:code=subprocess.run(command,cwd=root/'forge',env=env,stdout=f,stderr=subprocess.STDOUT).returncode
        xml=root/('forge/build/test-results/test/TEST-'+suite+'.xml')
        assert xml.exists() and xml.stat().st_mtime>=start
        shutil.copy2(xml,out/(phase+'.xml'));doc=ET.parse(xml).getroot();actual=int(doc.attrib['tests'])
        assert int(doc.attrib.get('skipped','0'))==0
        failures=[case.attrib['name'] for case in doc.findall('testcase') if case.find('failure') is not None]
        results.append({'phase':phase,'exit':code,'tests':actual,'selected':selected,'failures':failures,'source_sha256':hashlib.sha256(data).hexdigest()})
        (out/'results.json').write_text(json.dumps(results,indent=2));(out/(phase+'.java')).write_bytes(data);print(json.dumps(results[-1]),flush=True)
        assert 'compileJava FAILED' not in log.read_text() and 'compileTestJava FAILED' not in log.read_text()
        assert actual==count
        if phase in ['control','restored']:assert code==0 and not failures
        else:assert code!=0 and len(failures)==count
finally:file.write_bytes(original)
assert file.read_bytes()==original
