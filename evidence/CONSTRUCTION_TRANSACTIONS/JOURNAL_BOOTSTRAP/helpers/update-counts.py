from pathlib import Path
import re,json
root=Path('C:/Users/wmc02/Desktop/block-reality')
counts=json.loads((root/'build/ct-journal-bootstrap/counts.json').read_text())
assert counts['windows']['core']=={'tests':454,'failures':0,'errors':0,'skipped':12}
assert counts['windows']['forge']=={'tests':124,'failures':0,'errors':0,'skipped':0}
for name in ['README.md','QUICKSTART.md','docs/RESEARCH_BRIEF.md','docs/outreach/LISTING.md']:
    p=root/name;data=p.read_bytes()
    for a,b in [(b'573',b'578'),(b'449',b'454'),(b'561 pass',b'566 pass')]:data=data.replace(a,b)
    p.write_bytes(data)
p=root/'CLAUDE.md';data=p.read_bytes()
for a,b in [('核心現有30項測試','核心現有35項測試'),('Windows core449/Forge124：573登錄、561PASS、12平台SKIP；Linux572PASS、1平台SKIP。','Windows core454/Forge124：578登錄、566PASS、12平台SKIP；Linux577PASS、1平台SKIP。')]:
    assert data.count(a.encode())==1;data=data.replace(a.encode(),b.encode())
p.write_bytes(data)
