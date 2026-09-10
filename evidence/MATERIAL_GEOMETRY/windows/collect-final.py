from pathlib import Path
import json,shutil,xml.etree.ElementTree as ET
root=Path('C:/Users/wmc02/Desktop/block-reality');out=root/'build/material-geometry'
linux=Path('//wsl.localhost/Ubuntu-22.04/home/rocky/br-material-geometry')
results={}
for platform,base in [('windows',root),('linux',linux)]:
    summary={}
    for project,folder in [('core',base/'mod/core/build/test-results/test'),('forge',base/'forge/build/test-results/test')]:
        cases=[c for f in folder.glob('TEST-*.xml') for c in ET.parse(f).getroot().findall('testcase')]
        r={'tests':len(cases),'failed':sum(c.find('failure') is not None or c.find('error') is not None for c in cases),
           'skipped':sum(c.find('skipped') is not None for c in cases),
           'skips':[c.get('classname')+'.'+c.get('name') for c in cases if c.find('skipped') is not None]}
        r['passed']=r['tests']-r['failed']-r['skipped'];summary[project]=r
        target=out if platform=='windows' else linux/'qualification'
        if project=='core':shutil.copytree(folder,target/f'{platform}-{project}-final-xml')
        with (target/f'{platform}-{project}-final-tests.json').open('x') as f:json.dump(r,f,indent=2)
    results[platform]=summary
with (out/'final-tests.json').open('x') as f:json.dump(results,f,indent=2)
for platform,groups in results.items():print(platform, {key:sum(r[key] for r in groups.values()) for key in ['tests','passed','failed','skipped']})
