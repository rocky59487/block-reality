from pathlib import Path
import json,hashlib,xml.etree.ElementTree as ET,shutil
root=Path(__file__).resolve().parents[2];out=root/'evidence/NATIVE_CANDIDATE_RUNTIME';work=root/'build/native-candidate-runtime'
(out/'archive-first-error.txt').write_text("Evidence collection copied raw files and canonical source manifest, then aggregate generation exited 1.\nTraceback terminal line: KeyError: 'skips'\nCause: the Windows Forge summary has skipped=0 and omits its empty skips list.\nfinish-evidence.py independently parses all four XML directories and checks their existing summary totals; no test or original receipt is changed.\n",encoding='utf-8')
summaries={}
for platform in ['windows','linux']:
    directory=out/platform;allcases=[]
    for project in ['core','forge']:
        cases=[c for f in sorted((directory/f'{platform}-{project}-first-xml').glob('TEST-*.xml')) for c in ET.parse(f).getroot().findall('testcase')]
        counts={'tests':len(cases),'failures':sum(c.find('failure') is not None for c in cases),'errors':sum(c.find('error') is not None for c in cases),'skipped':sum(c.find('skipped') is not None for c in cases)}
        counts['passed']=counts['tests']-counts['failures']-counts['errors']-counts['skipped']
        recorded=json.loads((directory/f'{platform}-{project}-first-tests.json').read_text(encoding='utf-8-sig'))
        assert all(recorded[k]==v for k,v in counts.items()),(platform,project,counts)
        allcases.extend(cases)
    summaries[platform]={'tests':len(allcases),'failures':sum(c.find('failure') is not None for c in allcases),'errors':sum(c.find('error') is not None for c in allcases),'skipped':sum(c.find('skipped') is not None for c in allcases),'skips':[{'class':c.get('classname'),'test':c.get('name')} for c in allcases if c.find('skipped') is not None]}
    s=summaries[platform];s['passed']=s['tests']-s['failures']-s['errors']-s['skipped'];assert s['tests']==521 and s['failures']==s['errors']==0
    jar=directory/('jar-first' if platform=='windows' else 'jar-linux-first')/'verification.json'
    verification=json.loads(jar.read_text());assert len(verification['requests'])==48 and verification['jar_sha256']=='5a93c66bfe5f5a3dd8c1104a6e97fe000a2a0d2a8e66bf602821993d2a4c9b79'
runtime=(out/'linux/runtime-smoke-third.log').read_text();assert runtime.count(': PASS')==16
events=json.loads((out/'linux/runtime-smoke-third.json').read_text());assert sum(e['command']=='br_delivery_probe' and 'delivery probe PASS:' in e['response'] for e in events)==6
capture=(out/'client/capture-first.log').read_text();assert capture.count(': PASS')==45
images=list((out/'client/screenshots').glob('*.png'));assert len(images)==16
summaries['game']={'server_status_pass':16,'synthetic_player_events':24,'client_assertions_pass':45,'actual_screenshots':16,'scope':'bundled dev resources / isolated real Forge server and client; not installed jar or FPS qualification','client_exit':json.loads((out/'client/client-first-exit.json').read_text())}
(out/'counts.json').write_text(json.dumps(summaries,indent=2)+'\n',encoding='utf-8')
for name in ['resolve-docs.py','finish-evidence.py']:
    shutil.copy2(work/name,out/'windows'/name)
(out/'runtime-second-setup-error.txt').write_text('prepare-runtime-second.py exited 1:\n  File "prepare-runtime-second.py", line 16, in <module>\n    assert time.monotonic()<until;time.sleep(.5)\nAssertionError\n\nCopied from the tool terminal receipt; command/status history is linux/runtime-second-setup.json.\n',encoding='utf-8')
files=[{'path':p.relative_to(out).as_posix(),'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in sorted(out.rglob('*')) if p.is_file() and p.name!='receipts.json']
(out/'receipts.json').write_text(json.dumps(files,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'tests':{k:{f:summaries[k][f] for f in ['tests','passed','skipped']} for k in ['windows','linux']},'evidence_files':len(files),'evidence_bytes':sum(f['bytes'] for f in files)},indent=2))
