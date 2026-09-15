from pathlib import Path
import tarfile,subprocess,shutil,json,os,xml.etree.ElementTree as ET,hashlib
root=Path('/home/rocky/br-native-verdict-mutations');root.mkdir()
with tarfile.open('/mnt/c/Users/wmc02/Desktop/block-reality/build/native-verdict-cleanup/mutation-source.tar') as t:
    for m in t.getmembers():assert not m.issym() and not m.islnk() and (root/m.name).resolve().is_relative_to(root)
    t.extractall(root)
out=root/'evidence';out.mkdir()
source=root/'mod/api/src/main/java/com/blockreality/api/render/StressPalette.java'
original=source.read_bytes();needle=b'        if (overloaded) return DC_OVER;';assert original.count(needle)==1
env=dict(os.environ,PYTHONUTF8='1',PYTHONIOENCODING='utf-8');env.pop('BR_ENGINE',None)
results=[]
try:
    for name in ['control','ignore-native-flag','restored']:
        source.write_bytes(original.replace(needle,b'        // Mutant ignores the native overload verdict.') if name=='ignore-native-flag' else original)
        command=['bash','gradlew','--no-daemon','--console=plain',':core:test','--tests','com.blockreality.core.render.StressPaletteTest']
        with (out/(name+'.log')).open('xb') as log:
            run=subprocess.run(command,cwd=root/'mod',env=env,stdout=log,stderr=subprocess.STDOUT)
        xml=root/'mod/core/build/test-results/test/TEST-com.blockreality.core.render.StressPaletteTest.xml'
        assert xml.exists(), 'compile/setup failure is not a behavioral negative'
        shutil.copy2(xml,out/(name+'.xml'))
        suite=ET.fromstring(xml.read_bytes()); failures=[t.attrib['name'] for t in suite.findall('testcase') if t.find('failure') is not None]
        result={'phase':name,'exit':run.returncode,'tests':int(suite.attrib['tests']),'failures':failures,'source_sha256':hashlib.sha256(source.read_bytes()).hexdigest()}
        results.append(result);(out/'results.json').write_text(json.dumps(results,indent=2))
        assert result['tests']==15
        if name=='ignore-native-flag':
            assert run.returncode!=0 and 'hatchAndUtilizationKeepNativeVerdictAuthority()' in failures
            assert ':api:compileJava' in (out/(name+'.log')).read_text()
        else:assert run.returncode==0 and not failures
        print(name,result['exit'],failures,flush=True)
finally:source.write_bytes(original)
assert source.read_bytes()==original
