from pathlib import Path
import os,sys,subprocess,tarfile,json,shutil,hashlib,time,xml.etree.ElementTree as ET
source=Path('/mnt/c/Users/wmc02/Desktop/block-reality/build/ct-chunk-storage')
root=Path('/home/rocky/br-ct-chunk-mutations');assert not root.exists();root.mkdir()
with tarfile.open(source/'source.tar') as tar:
    for m in tar.getmembers():
        assert (root/m.name).resolve().is_relative_to(root.resolve()) and (m.isfile() or m.isdir())
    tar.extractall(root,filter='data')
out=root/'evidence';out.mkdir();(root/'OWNER').write_text('block-reality-chunk-storage-mutations-v1\n')
file=root/'forge/src/main/java/com/blockreality/impl/server/construction/ChunkFileParticipant.java'
original=file.read_bytes();text=original.decode();results=[]
force='try { await(storage.synchronize(true)); }';assert text.count(force)==1
loop='for (var entry : tags.entrySet()) issued.add(Objects.requireNonNull(storage.store(entry.getKey(),entry.getValue())));';assert text.count(loop)==1
variants=[('control',original),('force',text.replace(force,'try { await(storage.synchronize(false)); }').encode()),
          ('lost-unrelated',text.replace(loop,'for (var entry : tags.entrySet()) { entry.getValue().remove("ForgeCaps"); issued.add(Objects.requireNonNull(storage.store(entry.getKey(),entry.getValue()))); }').encode()),
          ('restored',original)]
env=dict(os.environ,PYTHONUTF8='1',PYTHONIOENCODING='utf-8');env.pop('BR_ENGINE',None);env.pop('BR_SIDECAR',None)
try:
    for phase,data in variants:
        file.write_bytes(data);start=time.time()
        command=['bash','gradlew','--no-daemon','--console=plain','test','--tests','com.blockreality.impl.server.construction.ChunkFileParticipantTest']
        log=out/(phase+'.log')
        with log.open('xb') as f:code=subprocess.run(command,cwd=root/'forge',env=env,stdout=f,stderr=subprocess.STDOUT).returncode
        xml=root/'forge/build/test-results/test/TEST-com.blockreality.impl.server.construction.ChunkFileParticipantTest.xml'
        assert xml.exists() and xml.stat().st_mtime>=start
        shutil.copy2(xml,out/(phase+'.xml'));doc=ET.parse(xml).getroot()
        failures=[case.attrib['name'] for case in doc.findall('testcase') if case.find('failure') is not None]
        results.append({'phase':phase,'exit':code,'tests':int(doc.attrib['tests']),'failures':failures,'source_sha256':hashlib.sha256(data).hexdigest()})
        (out/'results.json').write_text(json.dumps(results,indent=2));print(json.dumps(results[-1]),flush=True)
        assert 'compileJava FAILED' not in log.read_text() and 'compileTestJava FAILED' not in log.read_text()
        assert int(doc.attrib['tests'])==7 and int(doc.attrib.get('skipped','0'))==0
        if phase in ['control','restored']:assert code==0 and not failures
        else:assert code!=0 and 'wholeImagesArePrivateAndForcingPrecedesVerifiedReadback()' in failures
finally:file.write_bytes(original)
assert file.read_bytes()==original
