from pathlib import Path
import tarfile,subprocess,shutil,json,xml.etree.ElementTree as ET,hashlib
root=Path('/home/rocky/br-ct-bootstrap-mutations');root.mkdir()
with tarfile.open('/mnt/c/Users/wmc02/Desktop/block-reality/build/ct-journal-bootstrap/source.tar') as t:
    for m in t.getmembers():assert not m.issym() and not m.islnk() and (root/m.name).resolve().is_relative_to(root)
    t.extractall(root)
out=root/'evidence';out.mkdir()
source=root/'mod/core/src/main/java/com/blockreality/core/transaction/FileTransactionJournal.java'
original=source.read_bytes();needle=b'if (exists && !Arrays.equals(manifestBytes(), existingManifest))';assert original.count(needle)==1
results=[]
try:
    for name in ['control','ignore-domain-integrity','restored']:
        source.write_bytes(original.replace(needle,b'if (false && exists && !Arrays.equals(manifestBytes(), existingManifest))') if name=='ignore-domain-integrity' else original)
        command=['bash','gradlew','--no-daemon','--console=plain',':core:test','--tests','com.blockreality.core.transaction.JournalBootstrapTest']
        with (out/(name+'.log')).open('xb') as log:
            run=subprocess.run(command,cwd=root/'mod',stdout=log,stderr=subprocess.STDOUT)
        xml=root/'mod/core/build/test-results/test/TEST-com.blockreality.core.transaction.JournalBootstrapTest.xml'
        assert xml.exists(),'compile/setup failure is not a behavioral negative'
        shutil.copy2(xml,out/(name+'.xml'));suite=ET.fromstring(xml.read_bytes())
        failures=[t.attrib['name'] for t in suite.findall('testcase') if t.find('failure') is not None]
        result={'phase':name,'exit':run.returncode,'tests':int(suite.attrib['tests']),'failures':failures,'source_sha256':hashlib.sha256(source.read_bytes()).hexdigest()}
        results.append(result);(out/'results.json').write_text(json.dumps(results,indent=2))
        assert result['tests']==5
        if name=='ignore-domain-integrity':
            assert run.returncode!=0 and 'malformedManifestIsNeverAcceptedOrReplaced()' in failures
        else:assert run.returncode==0 and not failures
        print(name,run.returncode,failures,flush=True)
finally:source.write_bytes(original)
assert source.read_bytes()==original
