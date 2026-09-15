from pathlib import Path
import json,xml.etree.ElementTree as ET
root=Path('C:/Users/wmc02/Desktop/block-reality');out=root/'build/forge-axis-transactions';linux=Path('//wsl.localhost/Ubuntu-22.04/home/rocky/br-forge-axis-transactions/qualification')
summary={}
for platform,folder,skip in [('windows',out,12),('linux',linux,1)]:
    record={}
    for phase,version,count in [('core','second',477),('forge','third',145)]:
        assert json.loads((folder/(phase+'-'+version+'-exit.json')).read_text())['exit']==0
        suites=[ET.parse(p).getroot() for p in (folder/(phase+'-'+version+'-xml')).glob('TEST-*.xml')]
        counts={key:sum(int(s.get(key,0)) for s in suites) for key in ['tests','failures','errors','skipped']}
        skipped=[s.get('name')+'.'+c.get('name') for s in suites for c in s.findall('testcase') if c.find('skipped') is not None]
        assert counts=={'tests':count,'failures':0,'errors':0,'skipped':skip if phase=='core' else 0}
        record[phase]={'source':'d720995' if phase=='core' else '338c490','counts':counts,'skipped_tests':skipped}
    record['registered']=622;record['passed']=622-skip;record['skipped']=skip;summary[platform]=record
(out/'dual-platform-tests.json').write_text(json.dumps(summary,indent=2));print(json.dumps({p:{k:v for k,v in r.items() if k in ['registered','passed','skipped']} for p,r in summary.items()},indent=2))
