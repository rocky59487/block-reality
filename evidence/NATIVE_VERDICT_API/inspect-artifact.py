from pathlib import Path
import subprocess,json,hashlib,zipfile,difflib,xml.etree.ElementTree as ET
root=Path('C:/Users/wmc02/Desktop/block-reality');out=root/'build/native-verdict-cleanup'
base=root/'build/native-v1.5-consumer/blockreality-0.4.0-dev-v1.5.jar'
jar=out/'blockreality-0.4.0-dev-native-verdict.jar'
java=Path('C:/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot/bin')
cls='com.blockreality.api.render.StressPalette$LegendStop'
compiled=[];verbose=[]
for name,path in [('before',base),('after',jar)]:
    code=subprocess.check_output([str(java/'javap.exe'),'-classpath',str(path),'-c','-p','-s',cls])
    raw=subprocess.check_output([str(java/'javap.exe'),'-classpath',str(path),'-v','-p',cls])
    (out/(name+'-legend-code.txt')).write_bytes(code)
    (out/(name+'-legend-verbose.txt')).write_bytes(raw)
    compiled.append(code);verbose.append(raw.decode('utf-8',errors='replace').splitlines(True))
assert compiled[0]==compiled[1], 'extra nested-class change affects descriptors or bytecode'
diff=''.join(difflib.unified_diff(*verbose,fromfile='before',tofile='after'))
(out/'legend-verbose.diff').write_text(diff,encoding='utf-8');print(diff)
counts={}
for phase,p in [('core',root/'mod/core/build/test-results/test'),('forge',root/'forge/build/test-results/test')]:
    docs=[ET.parse(x).getroot() for x in p.glob('TEST-*.xml')]
    counts[phase]={k:sum(int(s.attrib.get(k,0)) for s in docs) for k in ['tests','failures','errors','skipped']}
(out/'counts.json').write_text(json.dumps(counts,indent=2))
print(json.dumps(counts))
