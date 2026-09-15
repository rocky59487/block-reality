from pathlib import Path
import shutil,json,xml.etree.ElementTree as ET,sys
root=Path(__file__).resolve().parents[2]
if sys.platform=='win32': root=Path('C:/Users/wmc02/Desktop/block-reality');out=root/'build/material-geometry';platform='windows'
else: root=Path('/home/rocky/br-material-geometry');out=root/'qualification';platform='linux'
for project,folder in [('core',root/'mod/core/build/test-results/test'),('forge',root/'forge/build/test-results/test')]:
    cases=[c for f in sorted(folder.glob('TEST-*.xml')) for c in ET.parse(f).getroot().findall('testcase')]
    assert cases
    result={'tests':len(cases),'failures':sum(c.find('failure') is not None for c in cases),'errors':sum(c.find('error') is not None for c in cases),'skipped':sum(c.find('skipped') is not None for c in cases),'skips':[{'class':c.get('classname'),'test':c.get('name')} for c in cases if c.find('skipped') is not None]}
    result['passed']=result['tests']-result['failures']-result['errors']-result['skipped']
    shutil.copytree(folder,out/f'{platform}-{project}-first-xml')
    with (out/f'{platform}-{project}-first-tests.json').open('x') as f:json.dump(result,f,indent=2)
    print(project,json.dumps(result))
