from pathlib import Path
import csv,json,math,hashlib
root=Path('C:/Users/wmc02/Desktop/block-reality');out=root/'build/manufactured-metadata'
linux=Path('//wsl.localhost/Ubuntu-22.04/home/rocky/br-manufactured-metadata/qualification')
def manifest(root):
    return {p.relative_to(root).as_posix():{'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in sorted(root.rglob('*')) if p.is_file()}
seed=json.loads((out/'profile-fixture-copy.json').read_text())['files']
assert seed==manifest(out/'profile-fixture')==manifest(linux/'profile-fixture')
summary={};fingerprints={};copies={}
for platform,base in [('windows',out),('linux',linux)]:
    assert json.loads((base/('profile-measure-'+platform+'-exit.json')).read_text())['exit']==0
    folder=base/('profile-'+platform)
    rows=list(csv.DictReader((folder/'samples.tsv').open(),delimiter='\t'));assert len(rows)==432
    summary[platform]=[];fingerprints[platform]={};copies[platform]={}
    for pieces in [64,1024,4096]:
        for phase in ['reopen','prepare-edit','commit-metadata']:
            selected=[r for r in rows if int(r['pieces'])==pieces and r['phase']==phase]
            assert [int(r['iteration']) for r in selected]==list(range(48))
            assert all(r['warmup']==str(i<8).lower() for i,r in enumerate(selected))
            hashes={r['fingerprint'] for r in selected};assert len(hashes)==1
            fingerprints[platform][str(pieces)+'/'+phase]=list(hashes)[0]
            measured=selected[8:]
            ns=sorted(int(r['nanoseconds']) for r in measured);allocated=sorted(int(r['allocated_bytes']) for r in measured)
            def percentile(values,q):return values[math.ceil(q*len(values))-1]
            summary[platform].append({'phase':phase,'pieces':pieces,'cells':pieces*32,'samples':40,
                'p50_ms':percentile(ns,.5)/1e6,'p95_ms':percentile(ns,.95)/1e6,
                'allocated_p50':percentile(allocated,.5),'allocated_p95':percentile(allocated,.95)})
        copies[platform].update({str(pieces)+'/'+k:v for k,v in manifest(folder/('pieces-'+str(pieces))).items()})
assert fingerprints['windows']==fingerprints['linux']
assert copies['windows']==copies['linux']
record={'status':'RECORDED','sample_protocol':'8 warmups plus 40 measured iterations per phase/case; nearest-rank percentiles',
        'execution':'Windows and WSL measurement JVMs ran concurrently on the same physical host; no CPU/storage isolation, cold-disk or FPS claim',
        'summary':summary,'fingerprints':fingerprints,'unchanged_seed_files':len(seed),
        'identical_commit_copy_files_per_platform':len(copies['windows'])}
(out/'profile-summary.json').write_text(json.dumps(record,indent=2)+'\n')
(out/'profile-copy-manifest.json').write_text(json.dumps({'identical_on_both_platforms':True,'files':copies['windows']},indent=2)+'\n')
print(json.dumps(record,indent=2))
