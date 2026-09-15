from pathlib import Path
import subprocess,tarfile,os,shutil,json,hashlib,xml.etree.ElementTree as ET
root=Path('/home/rocky/br-forge-axis-transactions');work=root/'mutation-work';out=root/'qualification/mutations'
assert (root/'OWNER').read_text().strip()=='block-reality-forge-axis-transactions-qualification-v1'
assert not work.exists() and not out.exists();work.mkdir();out.mkdir()
archive=Path('/mnt/c/Users/wmc02/Desktop/block-reality/build/forge-axis-transactions/source-third.tar')
with tarfile.open(archive) as tar:
    for item in tar.getmembers():assert (work/item.name).resolve().is_relative_to(work.resolve()) and not item.issym() and not item.islnk()
    tar.extractall(work)
env=dict(os.environ,PYTHONUTF8='1',PYTHONIOENCODING='utf-8',OPENBLAS_CORETYPE='Haswell',OPENBLAS_NUM_THREADS='1')
records=[]
def run(name,folder,args):
    command=['bash','gradlew','--no-daemon','--console=plain',*args]
    with (out/(name+'.log')).open('xb') as log:r=subprocess.run(command,cwd=work/folder,env=env,stdout=log,stderr=subprocess.STDOUT)
    record={'name':name,'command':command,'exit':r.returncode};records.append(record)
    (out/(name+'-exit.json')).write_text(json.dumps(record));print(name,r.returncode,flush=True)
    return r.returncode
def results(name):
    source=work/'mod/core/build/test-results/test';shutil.copytree(source,out/(name+'-xml'))
    suites=[ET.parse(p).getroot() for p in source.glob('TEST-*.xml')]
    return {'tests':sum(int(t.get('tests',0)) for t in suites),'skipped':sum(int(t.get('skipped',0)) for t in suites),
      'failures':[{'name':t.get('name'),'class':t.get('classname'),'type':t.find('failure').get('type')} for suite in suites for t in suite.findall('testcase') if t.find('failure') is not None]}
tests=[':core:test','--tests','*RevisionGateTest','--tests','*ManufacturedRegistryTest']
assert run('control','mod',tests)==0
control=results('control');assert control['tests']>20 and not control['failures'] and not control['skipped']
source=work/'mod/core/src/main/java/com/blockreality/core/transaction/ManufacturedRegistry.java';original=source.read_bytes()
old=b'return Math.max(revisions.getOrDefault(dimension,0L),abortedFloors.getOrDefault(dimension,0L));';new=b'return revisions.getOrDefault(dimension,0L);'
assert original.count(old)==1;source.write_bytes(original.replace(old,new));shutil.copy2(source,out/'ManufacturedRegistry-mutant.java')
assert run('discard-aborted-floor','mod',tests)!=0
fault=results('discard-aborted-floor')
assert len(fault['failures'])==1 and fault['failures'][0]['name']=='abortedBeforeRevisionSurvivesRepeatedReopenWithoutPromotingRejectedRequests()' and fault['failures'][0]['type']=='org.opentest4j.AssertionFailedError'
source.write_bytes(original)
assert run('restored','mod',tests)==0
restored=results('restored');assert restored['tests']==control['tests'] and not restored['failures'] and not restored['skipped']
source=work/'forge/src/main/java/com/blockreality/impl/server/StructureManager.java';original=source.read_bytes()
old=b'if (ConstructionService.suppressStructuralChange(level,pos)) return;'
assert original.count(old)==2;mutated=original.replace(old,b'/* compiled negative arm: expose provisional structural observation */',1);source.write_bytes(mutated)
shutil.copy2(source,out/'StructureManager-mutant.java')
assert run('provisional-observation-jar','forge',['jar','reobfJar','-PbrNativesDir=/home/rocky/br-native-v15-consumer/staged'])==0
jar=work/'forge/build/libs/blockreality-0.4.0-dev.jar';shutil.copy2(jar,out/'provisional-observation.jar')
source.write_bytes(original)
(out/'results.json').write_text(json.dumps({'source':'338c490','control':control,'floor_mutant':fault,'restored':restored,'visibility_jar_sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'visibility_runtime':'PENDING'},indent=2))
print('Mutation jar compiled; runtime oracle remains pending',flush=True)
