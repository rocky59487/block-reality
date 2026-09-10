from pathlib import Path
import hashlib,json,shutil,subprocess,socket
root=Path(__file__).resolve().parents[2];work=root/'build/material-geometry';dest=root/'evidence/MATERIAL_GEOMETRY';dest.mkdir()
(dest/'.gitattributes').write_text('** -text\n',encoding='utf-8')
linux=Path('//wsl.localhost/Ubuntu-22.04/home/rocky');receipts=[]
keep={'.json','.xml','.log','.frame','.png','.txt','.py'}
def copy_tree(source,target,skip_dirs=()):
    for p in sorted(source.rglob('*')):
        if not p.is_file():continue
        rel=p.relative_to(source)
        if any(x in skip_dirs for x in rel.parts):continue
        if p.suffix not in keep and p.name!='CRP_OWNED':continue
        q=target/rel;q.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(p,q)
        digest=hashlib.sha256(p.read_bytes()).hexdigest();assert hashlib.sha256(q.read_bytes()).hexdigest()==digest
        receipts.append({'source':str(p),'path':q.relative_to(dest).as_posix(),'sha256':digest,'bytes':p.stat().st_size})
copy_tree(work,dest/'windows',skip_dirs=('distribution','staged','probe-compile','__pycache__'))
copy_tree(linux/'br-material-geometry/qualification',dest/'linux')
copy_tree(linux/'br-mg-mutations/qualification',dest/'mutations')
for name,folder in [('first-client','br-mg-render-first'),('final-client','br-mg-render-final'),('extra-client','br-mg-extra-first')]:
    copy_tree(linux/folder,dest/name)
for name in ['CANDIDATE.txt','SHA256SUMS.txt','START-HERE.txt']:
    shutil.copyfile(work/'distribution'/name,dest/('distribution-'+name))
fields=['worldRevision','resultRevision','kind','notice','hasData','stale','members','shells','maxDc']
comparisons=[]
for scene in ['beam','column','panel','catalogue']:
    for lang in ['en_us','zh_tw']:
        for width in [1280,1920]:
            name=f'{scene}-{lang}-{width}.json'
            before=json.loads((root/'evidence/NATIVE_CANDIDATE_RUNTIME/client'/name).read_text())
            after=json.loads((dest/'final-client'/name).read_text())
            same={k:before[k]==after[k] for k in fields};assert all(same.values()),(name,same)
            comparisons.append({'file':name,'equal_fields':same})
before=json.loads((root/'evidence/NATIVE_CANDIDATE_RUNTIME/client/bootstrap-expected.json').read_text())
after=json.loads((dest/'final-client/bootstrap-expected.json').read_text());assert before==after
(dest/'native-readout-comparison.json').write_text(json.dumps({'bootstrap_all_fields_equal':True,'scenes':comparisons},indent=2))
summary={}
for directory,file in [('first-client','capture-events.json'),('final-client','capture-events.json'),
                       ('final-client','mg-models-events.json'),('extra-client','mg-interact-events.json'),('extra-client','mg-capture-events.json')]:
    events=json.loads((dest/directory/file).read_text());gates=[e for e in events if 'gate' in e]
    assert all(e['pass'] for e in gates);summary[directory+'/'+file]={'passed':len(gates)}
assert list(x['passed'] for x in summary.values())==[45,45,127,14,6]
summary['tests']=json.loads((work/'final-tests.json').read_text())
summary['jar']=json.loads((work/'distribution.json').read_text())
(dest/'summary.json').write_text(json.dumps(summary,indent=2))
paths=subprocess.check_output(['git','ls-tree','-r','--name-only','b10884b','mod/api/src/main','mod/core/src/main','forge/src/main','forge/build.gradle','gradle','contract'],cwd=root,text=True).splitlines()
canonical={}
for path in paths:
    blob=subprocess.check_output(['git','show','b10884b:'+path],cwd=root)
    canonical[path]=hashlib.sha256(blob).hexdigest()
    assert subprocess.check_output(['git','show','HEAD:'+path],cwd=root)==blob,path
(dest/'shipping-b10884b-sources.json').write_text(json.dumps(canonical,indent=2))
(dest/'receipts.json').write_text(json.dumps(receipts,indent=2))
print('Archived',len(receipts),'raw files; original16 native readouts and shipping sources verified')
