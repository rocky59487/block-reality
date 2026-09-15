from pathlib import Path
import json,hashlib,zipfile,shutil,xml.etree.ElementTree as ET,subprocess
root=Path.cwd();work=root/'build/construction-transactions';linux=Path('//wsl.localhost/Ubuntu-22.04/home/rocky/br-construction-transactions');mut=Path('//wsl.localhost/Ubuntu-22.04/home/rocky/br-ct-core-mutations/qualification')
out=root/'evidence/CONSTRUCTION_TRANSACTIONS/CORE_JOURNAL';out.mkdir(parents=True)
(out/'.gitattributes').write_text('** -text\n',encoding='utf-8')
receipts={}
def digest(data):return hashlib.sha256(data).hexdigest()
def copy(src,name):
    dest=out/name;dest.parent.mkdir(parents=True,exist_ok=True);assert not dest.exists(),dest
    data=src.read_bytes();dest.write_bytes(data);assert dest.read_bytes()==data
    receipts[name]={'sha256':digest(data),'bytes':len(data),'source':str(src)}
def archive(src,name):
    dest=out/name;dest.parent.mkdir(parents=True,exist_ok=True);assert not dest.exists(),dest
    entries={}
    with zipfile.ZipFile(dest,'w',compression=zipfile.ZIP_DEFLATED) as z:
        for p in sorted(src.rglob('*')):
            if not p.is_file():continue
            assert not p.is_symlink(),p
            rel=p.relative_to(src).as_posix();data=p.read_bytes();entries[rel]={'sha256':digest(data),'bytes':len(data)};z.writestr(rel,data)
        z.writestr('__raw_file_manifest.json',json.dumps(entries,indent=2))
    with zipfile.ZipFile(dest) as z:
        for nameIn,meta in entries.items():assert digest(z.read(nameIn))==meta['sha256']
    receipts[name]={'sha256':digest(dest.read_bytes()),'bytes':dest.stat().st_size,'raw_files':len(entries),'source':str(src),'format':'zip; every original file byte preserved, per-file hashes inside'}
# First observations stay separate from the accepted shipping candidate.
for name in ['compile-first.log','core-first.log','process-first.log','process-expanded.log','windows-core-full.log','windows-forge-full.log','windows-core-cached.log','windows-forge-cached.log','linux-launch-first.log','docs-counts.log','jar-guard.json','jar-guard-cached.json','jar-identity.json','core-working-sources.json','windows-full-tests.json','linux-full-tests.json','journal-cost-recorded.json','journal-cost-cached.json','cost-windows.log','cost-linux.log','cost-windows-cached.log','cost-linux-cached.log']:
    copy(work/name,name)
for name in ['setup-linux.py','run-linux-first.py','run-linux.py','run-linux-cached.py','mutations.py','TransactionJournalCost.java']:
    copy(work/name,'helpers/'+name)
for name in ['process-first','process-expanded','process-windows-final','process-windows-cached','core-first-xml','windows-core-full-xml','windows-forge-full-xml']:
    archive(work/name,name+'.zip')
for name in ['process-linux-final','process-linux-cached','linux-core-full-xml','linux-forge-full-xml']:
    archive(linux/'qualification'/name,name+'.zip')
for name in ['identity.json','linux-core-command.json','linux-forge-command.json','linux-core-cached-command.json','linux-forge-cached-command.json','linux-core-full.log','linux-forge-full.log','linux-core-cached.log','linux-forge-cached.log']:
    copy(linux/'qualification'/name,'linux/'+name)
archive(mut,'compiled-mutations.zip');copy(mut/'summary.json','compiled-mutations-summary.json')
summary={}
for platform,base in [('windows',root),('linux',linux)]:
    groups={}
    for project,folder in [('core',base/'mod/core/build/test-results/test'),('forge',base/'forge/build/test-results/test')]:
        archive(folder,f'{platform}-{project}-cached-xml.zip')
        cases=[c for p in folder.glob('TEST-*.xml') for c in ET.parse(p).getroot().findall('testcase')]
        counts={'registered':len(cases),'failed':sum(c.find('failure') is not None or c.find('error') is not None for c in cases),'skipped':sum(c.find('skipped') is not None for c in cases)};counts['passed']=counts['registered']-counts['failed']-counts['skipped'];assert counts['failed']==0
        groups[project]=counts
    summary[platform]=groups
    for variant,path in [('baseline',(work/'cost-windows' if platform=='windows' else linux/'qualification/cost-linux')),('cached',(work/'cost-windows-cached' if platform=='windows' else linux/'qualification/cost-linux-cached'))]:
        copy(path/'samples.csv',f'cost/{platform}-{variant}.csv');copy(path/'scope.txt',f'cost/{platform}-{variant}-scope.txt')
# Profile record bytes are identical across all four runs; keep one raw set and verified per-file hashes.
archive(work/'cost-windows','cost/canonical-records-and-baseline.zip')
source_paths=subprocess.check_output(['git','ls-files','mod/api/src/main/java','mod/core/src/main/java','forge/src/main/java','forge/src/main/resources'],text=True,encoding='utf-8').splitlines()
source_manifest={p:digest((root/p).read_bytes()) for p in source_paths if (root/p).is_file()}
(out/'shipping-3413590-sources.json').write_text(json.dumps(source_manifest,indent=2),encoding='utf-8')
jar=root/'forge/build/libs/blockreality-0.4.0-dev.jar';shutil.copy2(jar,work/'blockreality-0.4.0-dev-core-journal-cached.jar')
with zipfile.ZipFile(jar) as current,zipfile.ZipFile(root/'build/material-geometry/blockreality-0.4.0-dev.jar') as base:
    old=set(base.namelist());new=set(current.namelist());assert not old-new;assert all(base.read(n)==current.read(n) for n in old)
    added=sorted(new-old);assert all(n.startswith('com/blockreality/core/transaction/') for n in added)
    jar_result={'source':'341359021430fad5fb6e89b8ea2a4829d3af6b0f','sha256':digest(jar.read_bytes()),'bytes':jar.stat().st_size,'unchanged_existing_entries':len(old),'added':added,'native':{n:digest(current.read(n)) for n in new if n.endswith(('.dll','.so'))}}
summary.update({'status':'CORE_STEP_VERIFIED_CT_STILL_OPEN','source':'341359021430fad5fb6e89b8ea2a4829d3af6b0f','criteria':['a180ae2','1dafb52'],'allocation_criteria':'ff03863','jar':jar_result,'complete_CT_gates':[],'remaining':'Forge participant durability/restart, canonical NBT, authoritative pieces, undo, network entry, client UI, gameplay/performance; engine changes remain out of scope'})
(out/'summary.json').write_text(json.dumps(summary,indent=2),encoding='utf-8');(work/'core-step-summary.json').write_text(json.dumps(summary,indent=2),encoding='utf-8')
(out/'receipts.json').write_text(json.dumps(receipts,indent=2),encoding='utf-8')
print('Archived',len(receipts),'receipt files;',sum(r['bytes'] for r in receipts.values()),'bytes; embedded manifests verified')
print(json.dumps(summary,indent=2))
