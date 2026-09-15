from pathlib import Path
import tarfile,subprocess,os,json,shutil,difflib,xml.etree.ElementTree as ET
root=Path('/home/rocky/br-ct-core-mutations'); root.mkdir()
workspace=Path('/mnt/c/Users/wmc02/Desktop/block-reality/build/construction-transactions')
with tarfile.open(workspace/'module-working-source.tar') as archive: archive.extractall(root,filter='data')
out=root/'qualification';out.mkdir()
coordinator=root/'mod/core/src/main/java/com/blockreality/core/transaction/AtomicConstructionCoordinator.java'
journal=root/'mod/core/src/main/java/com/blockreality/core/transaction/FileTransactionJournal.java'
original={p:p.read_text() for p in [coordinator,journal]}
old_commit='''                host.flush(resources(intent));
                requireState(intent, host, true);
                decision = journal.decide(request.id(), Phase.COMMITTED, Reason.NONE);'''
early_commit='''                decision = journal.decide(request.id(), Phase.COMMITTED, Reason.NONE);
                host.flush(resources(intent));
                requireState(intent, host, true);'''
foreign='''                if (!change.before().equals(actual) && !change.after().equals(actual))'''
latch='''        if (readFailure != null) throw new IOException("Journal read failed; reopen and verify all records before recovery", readFailure);'''
arms=[('commit-before-flush',coordinator,old_commit,early_commit,'everyParticipantWriteAndFlushFailureRestoresAllBeforeImagesWithoutPublication'),('overwrite-foreign-value',coordinator,foreign,'                if (false)','unknownExternalValueOrRevisionPreventsAnyRecoveryWrite'),('clear-corruption-with-baseline',journal,latch,'        // Mutation: omit corruption latch.','corruptionCannotBeClearedByPublishingABaselineWithoutReopeningTheJournal')]
report=[]
def run(name,selector):
    dest=out/name;dest.mkdir();command=['bash','gradlew','--no-daemon',':core:test','--tests',selector,'--console=plain']
    (dest/'command.json').write_text(json.dumps(command))
    with (dest/'gradle.log').open('x') as f:r=subprocess.run(command,cwd=root/'mod',stdout=f,stderr=subprocess.STDOUT)
    shutil.copytree(root/'mod/core/build/test-results/test',dest/'xml')
    cases=[c for p in (dest/'xml').glob('TEST-*.xml') for c in ET.parse(p).getroot().findall('testcase')]
    return r,cases,dest
r,cases,dest=run('control','com.blockreality.core.transaction.*');assert r.returncode==0 and len(cases)==27 and all(c.find('failure') is None and c.find('skipped') is None for c in cases)
try:
    for name,path,old,new,test in arms:
        source=original[path];assert source.count(old)==1,name;changed=source.replace(old,new);path.write_text(changed)
        r,cases,dest=run(name,'com.blockreality.core.transaction.AtomicConstructionCoordinatorTest.'+test)
        (dest/'mutation.diff').write_text(''.join(difflib.unified_diff(source.splitlines(True),changed.splitlines(True),fromfile=str(path.relative_to(root)),tofile=str(path.relative_to(root)))))
        failures=[c for c in cases if c.find('failure') is not None]
        assert r.returncode!=0 and len(cases)==1 and len(failures)==1,(name,r.returncode,len(cases))
        assert failures[0].find('failure').get('type')=='org.opentest4j.AssertionFailedError',(name,failures[0].find('failure').attrib)
        assert '> Task :core:compileJava FAILED' not in (dest/'gradle.log').read_text(),name
        report.append({'arm':name,'compiled':True,'exit':r.returncode,'caught_by':failures[0].get('name'),'type':failures[0].find('failure').get('type')});path.write_text(source);print('CAUGHT',name,flush=True)
finally:
    for p,s in original.items():p.write_text(s)
r,cases,dest=run('restored','com.blockreality.core.transaction.*');assert r.returncode==0 and len(cases)==27 and all(c.find('failure') is None and c.find('skipped') is None for c in cases)
(out/'summary.json').write_text(json.dumps({'control':27,'arms':report,'restored':27,'status':'PASS'},indent=2));print('Control 27; three compiled negatives caught; restored 27 PASS')
