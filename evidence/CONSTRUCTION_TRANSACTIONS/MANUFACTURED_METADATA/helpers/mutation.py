from pathlib import Path
import tarfile,subprocess,shutil,json,xml.etree.ElementTree as ET,hashlib
root=Path('/home/rocky/br-manufactured-mutations');root.mkdir()
with tarfile.open('/mnt/c/Users/wmc02/Desktop/block-reality/build/manufactured-metadata/source.tar') as t:
    for m in t.getmembers():assert not m.issym() and not m.islnk() and (root/m.name).resolve().is_relative_to(root)
    t.extractall(root)
out=root/'evidence';out.mkdir()
source=root/'mod/core/src/main/java/com/blockreality/core/transaction/ManufacturedRegistry.java';original=source.read_bytes()
mutants={
 'ownership':[(b'if (!cells.add(cell) || owners.containsKey(new Cell(dimension,cell)))',b'if (!cells.add(cell))'),
              (b'if (!claimed.add(cell) || owner != null && !owner.equals(update.after().id()))',b'if (!claimed.add(cell))')],
 'undo-eligibility':[(b'if (before.status() != INTACT) throw invalid();',b'// Mutant accepts an edited piece for inverse metadata.'),
                     (b'Operation.UNDO && (before.status() != INTACT || after.status() != RETIRED',b'Operation.UNDO && (after.status() != RETIRED')]
}
results=[]
try:
    for name in ['control','ownership','undo-eligibility','restored']:
        data=original
        for before,after in mutants.get(name,[]):assert data.count(before)==1;data=data.replace(before,after)
        source.write_bytes(data)
        command=['bash','gradlew','--no-daemon','--console=plain',':core:test','--tests','com.blockreality.core.transaction.ManufacturedRegistryTest','--tests','com.blockreality.core.transaction.MetadataCodecTest']
        with (out/(name+'.log')).open('xb') as log:run=subprocess.run(command,cwd=root/'mod',stdout=log,stderr=subprocess.STDOUT)
        failures=[];count=0
        for cls in ['ManufacturedRegistryTest','MetadataCodecTest']:
            xml=root/('mod/core/build/test-results/test/TEST-com.blockreality.core.transaction.'+cls+'.xml')
            assert xml.exists(),'compile/setup failure is not a behavioral negative'
            shutil.copy2(xml,out/(name+'-'+cls+'.xml'));suite=ET.fromstring(xml.read_bytes());count+=int(suite.attrib['tests'])
            failures += [t.attrib['name'] for t in suite.findall('testcase') if t.find('failure') is not None]
        record={'phase':name,'exit':run.returncode,'tests':count,'failures':failures,'source_sha256':hashlib.sha256(data).hexdigest()}
        results.append(record);(out/'results.json').write_text(json.dumps(results,indent=2))
        assert count==18
        if name in mutants:
            target='forgedOwnershipCannotPublishOrReconstructEvenWithValidJournalChecksums()' if name=='ownership' else 'cutPreservesOriginalIdentityAndNeverRestoresUndoEligibility()'
            assert run.returncode!=0 and target in failures
        else:assert run.returncode==0 and not failures
        print(name,run.returncode,failures,flush=True)
finally:source.write_bytes(original)
assert source.read_bytes()==original
