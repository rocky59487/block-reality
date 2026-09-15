from pathlib import Path
import hashlib,shutil,subprocess,json,sys,os,zipfile
root=Path(__file__).resolve().parents[2];work=root/'build/material-geometry';stage=work/'distribution';stage.mkdir()
for name in ['LICENSE','NOTICE']:shutil.copy2(root/name,stage/name)
shutil.copytree(root/'build/native-candidate-runtime/distribution/third_party',stage/'third_party')
shutil.copy2(root/'build/native-candidate-runtime/staged/provenance.json',stage/'engine-provenance.json')
shutil.copy2(root/'scripts/dist-docs/START-HERE-engine-library.txt',stage/'START-HERE.txt')
shutil.copy2(work/'blockreality-0.4.0-dev.jar',stage/'blockreality-0.4.0-dev.jar')
(stage/'CANDIDATE.txt').write_text('Block Reality 0.4.0-dev material geometry native candidate\n\n'
    'Shipping module source b10884b; Tectonic2 source 42e10f5f7af166788588afcd2fb2fd97101d16dc.\n'
    'Windows/Linux x86_64 libraries included. Minecraft 1.20.1 / Forge 47.4.13 / Java 17.\n'
    'This is a development candidate, not v1 or a replacement of the formal engine release.\n'
    'MG verifies declared rectangular shapes, atlas end faces, target/collision bounds, native sample mapping,\n'
    'real isolated Linux client placement and sneak axis cycling, and inventory rendering.\n'
    '528 tests registered: Windows 516 pass / 12 platform skips; Linux 527 pass / 1 platform skip.\n'
    'All native-dependent cases executed. The original 16 scenes/45 checks, 127 model checks,\n'
    '14 interaction checks and 6 supplemental captures passed. Rendering used software llvmpipe.\n'
    'Panels still represent material cells: their placement axis is not a physical panel normal.\n'
    'Original installed Windows CM/N25, FPS and engine-driven fracture/crushing/rigid-body lifecycle remain unqualified.\n'
    'The prior INS installed-server evidence applies to its earlier jar, not this changed artifact.\n'
    'Results: evidence/MATERIAL_GEOMETRY in the source repository.\n',encoding='utf-8')
files=sorted(p for p in stage.rglob('*') if p.is_file())
(stage/'SHA256SUMS.txt').write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.relative_to(stage).as_posix()+'\n' for p in files),encoding='utf-8',newline='\n')
env=os.environ.copy();env['PYTHONUTF8']='1';env['PYTHONIOENCODING']='utf-8'
for script,name in [('check_bundle.py','bundle-final'),('check_bundle_selftest.py','bundle-negative-final')]:
    result=subprocess.run([sys.executable,'-X','utf8',str(root/'scripts'/script),str(stage)],cwd=root,capture_output=True,env=env)
    (work/(name+'.log')).write_bytes(result.stdout+result.stderr)
    (work/(name+'-exit.json')).write_text(json.dumps({'exit':result.returncode,'script':script})+'\n',encoding='utf-8')
    print(name,result.returncode,flush=True)
    if result.returncode:raise SystemExit(result.returncode)
shutil.make_archive(str(work/'blockreality-0.4.0-dev-material-geometry-candidate'),'zip',stage)
archive=work/'blockreality-0.4.0-dev-material-geometry-candidate.zip'
with zipfile.ZipFile(work/'blockreality-0.4.0-dev.jar') as jar,zipfile.ZipFile(root/'build/installed-native-server/blockreality-0.4.0-dev.jar') as previous:
    natives=[n for n in jar.namelist() if n.endswith(('.dll','.so'))]
    assert len(natives)==2
    for name in natives:assert jar.read(name)==previous.read(name)
    changes=[n for n in jar.namelist() if n not in previous.namelist() or jar.read(n)!=previous.read(n)]
    receipt={'jar':{'bytes':(work/'blockreality-0.4.0-dev.jar').stat().st_size,'sha256':hashlib.sha256((work/'blockreality-0.4.0-dev.jar').read_bytes()).hexdigest()},
             'native_bytes_identical_to_INS':natives,'changed_jar_entries':changes,
             'archive':{'file':archive.name,'bytes':archive.stat().st_size,'sha256':hashlib.sha256(archive.read_bytes()).hexdigest()}}
(work/'distribution.json').write_text(json.dumps(receipt,indent=2)+'\n',encoding='utf-8')
print(json.dumps(receipt['jar']),flush=True)
