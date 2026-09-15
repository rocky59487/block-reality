from pathlib import Path
import os,subprocess,tarfile,json,shutil,hashlib,time,xml.etree.ElementTree as ET
source=Path('/mnt/c/Users/wmc02/Desktop/block-reality/build/bounded-chunk-read')
root=Path('/home/rocky/br-bounded-chunk-mutations');assert not root.exists();root.mkdir()
with tarfile.open(source/'source.tar') as tar:
    for m in tar.getmembers():assert (root/m.name).resolve().is_relative_to(root.resolve()) and (m.isfile() or m.isdir())
    tar.extractall(root,filter='data')
out=root/'evidence';out.mkdir();(root/'OWNER').write_text('block-reality-bounded-chunk-mutations-v1\n')
shutil.copy2(source/'test.gradle',out/'test.gradle')
file=root/'forge/src/main/java/com/blockreality/impl/server/construction/BoundedChunkRead.java'
original=file.read_bytes();text=original.decode();results=[]
cap='stream.readNBytes(ChunkFileParticipant.MAX_CHUNK_BYTES + 1)';assert text.count(cap)==1
parser='CanonicalNbt.read(bytes,ChunkFileParticipant.MAX_CHUNK_BYTES)';assert text.count(parser)==1
suite='com.blockreality.impl.server.construction.'
alltests=[suite+'BoundedChunkReadTest',suite+'ChunkFileParticipantTest']
variants=[('control',original,alltests,13),
          ('stream-cap',text.replace(cap,'stream.readAllBytes()').encode(),[suite+'BoundedChunkReadTest.decodedByteCapStopsTheStreamBeforeTagAllocation'],1),
          ('vanilla-parser',text.replace(parser,'net.minecraft.nbt.NbtIo.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes)))').encode(),[suite+'BoundedChunkReadTest.duplicateNamesRefuseWithoutChangingRegionFiles'],1),
          ('restored',original,alltests,13)]
env=dict(os.environ,PYTHONUTF8='1',PYTHONIOENCODING='utf-8');env.pop('BR_ENGINE',None);env.pop('BR_SIDECAR',None)
try:
    for phase,data,selected,count in variants:
        file.write_bytes(data);start=time.time()
        command=['bash','gradlew','--no-daemon','--console=plain','-I',str(out/'test.gradle'),'-Dbr.chunkReadEvidence='+str(out/(phase+'-fixtures')),'test']
        for test in selected:command+=['--tests',test]
        log=out/(phase+'.log')
        with log.open('xb') as f:code=subprocess.run(command,cwd=root/'forge',env=env,stdout=f,stderr=subprocess.STDOUT).returncode
        names=['BoundedChunkReadTest','ChunkFileParticipantTest'] if count==13 else ['BoundedChunkReadTest']
        actual=0;failures=[]
        for name in names:
            xml=root/('forge/build/test-results/test/TEST-'+suite+name+'.xml')
            assert xml.exists() and xml.stat().st_mtime>=start
            shutil.copy2(xml,out/(phase+'-'+name+'.xml'));doc=ET.parse(xml).getroot();actual+=int(doc.attrib['tests'])
            assert int(doc.attrib.get('skipped','0'))==0
            failures += [case.attrib['name'] for case in doc.findall('testcase') if case.find('failure') is not None]
        results.append({'phase':phase,'exit':code,'tests':actual,'selected':selected,'failures':failures,'source_sha256':hashlib.sha256(data).hexdigest()})
        (out/'results.json').write_text(json.dumps(results,indent=2));print(json.dumps(results[-1]),flush=True)
        assert 'compileJava FAILED' not in log.read_text() and 'compileTestJava FAILED' not in log.read_text()
        assert actual==count
        if phase in ['control','restored']:assert code==0 and not failures
        else:assert code!=0 and len(failures)==1
finally:file.write_bytes(original)
assert file.read_bytes()==original
