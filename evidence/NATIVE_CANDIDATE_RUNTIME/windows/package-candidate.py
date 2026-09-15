from pathlib import Path
import shutil,hashlib,subprocess,json,sys
sys.stdout.reconfigure(encoding='utf-8')
root=Path(__file__).resolve().parents[2];work=root/'build/native-candidate-runtime';stage=work/'distribution';stage.mkdir()
for source,name in [(work/'blockreality-0.4.0-dev.jar','blockreality-0.4.0-dev.jar'),(root/'LICENSE','LICENSE'),(root/'NOTICE','NOTICE'),(work/'staged/provenance.json','engine-provenance.json')]:shutil.copy2(source,stage/name)
(stage/'third_party').mkdir()
for source in sorted((root/'third_party').glob('*.txt')):shutil.copy2(source,stage/'third_party'/source.name)
(stage/'START-HERE.txt').write_text('Block Reality 0.4.0-dev — local native candidate\n\nForge 1.20.1 / Java 17. Windows x86_64 and Linux x86_64 native libraries are inside this jar.\nPlace blockreality-0.4.0-dev.jar in an isolated Forge profile mods directory.\nEngine source: 42e10f5f7af166788588afcd2fb2fd97101d16dc, candidate build of tectonic2 1.3.0.\nThis is a module integration candidate, not v1 or a replacement of the formal engine release.\nSee evidence/NATIVE_CANDIDATE_RUNTIME/RESULTS.md in the module source for the exact qualification scope.\nJava does not implement engineering verdicts, fracture, crushing or rigid-body motion.\n',encoding='utf-8')
files=sorted(p for p in stage.rglob('*') if p.is_file())
(stage/'SHA256SUMS.txt').write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.relative_to(stage).as_posix()+'\n' for p in files),encoding='utf-8')
for script,name in [('check_bundle.py','bundle-first'),('check_bundle_selftest.py','bundle-negative-first')]:
    result=subprocess.run([sys.executable,str(root/'scripts'/script),str(stage)],cwd=root,capture_output=True)
    (work/(name+'.log')).write_bytes(result.stdout+result.stderr)
    (work/(name+'-exit.json')).write_text(json.dumps({'exit':result.returncode,'script':script})+'\n')
    print(name,result.returncode,flush=True)
    if result.returncode:raise SystemExit(result.returncode)
