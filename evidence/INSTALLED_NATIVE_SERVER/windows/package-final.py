from pathlib import Path
import hashlib,shutil,subprocess,json,sys
root=Path(__file__).resolve().parents[2];work=root/'build/installed-native-server';stage=work/'distribution';stage.mkdir()
for name in ['LICENSE','NOTICE']:shutil.copy2(root/name,stage/name)
shutil.copytree(root/'build/native-candidate-runtime/distribution/third_party',stage/'third_party')
shutil.copy2(root/'build/native-candidate-runtime/staged/provenance.json',stage/'engine-provenance.json')
shutil.copy2(root/'scripts/dist-docs/START-HERE-engine-library.txt',stage/'START-HERE.txt')
shutil.copy2(work/'blockreality-0.4.0-dev.jar',stage/'blockreality-0.4.0-dev.jar')
(stage/'CANDIDATE.txt').write_text('Block Reality 0.4.0-dev native candidate\n\nModule source ae6a4eb; Tectonic2 source 42e10f5f7af166788588afcd2fb2fd97101d16dc.\nWindows/Linux x86_64 libraries included. Minecraft 1.20.1 / Forge 47.4.13 / Java 17.\nThis is a locally qualified candidate, not the v1 release or formal engine v1.3 replacement.\nNCR qualifies native tests and the same native/class bytes on both platforms; INS adds an installed Linux dedicated-server run.\nWindows installed client, full interaction/material directions, FPS and engine-driven fracture/crushing/rigid-body lifecycle remain unqualified.\nExact results: evidence/NATIVE_CANDIDATE_RUNTIME and evidence/INSTALLED_NATIVE_SERVER in the source repository.\n',encoding='utf-8')
files=sorted(p for p in stage.rglob('*') if p.is_file())
(stage/'SHA256SUMS.txt').write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.relative_to(stage).as_posix()+'\n' for p in files),encoding='utf-8',newline='\n')
for script,name in [('check_bundle.py','final-bundle-first'),('check_bundle_selftest.py','final-bundle-negative-first')]:
    result=subprocess.run([sys.executable,'-X','utf8',str(root/'scripts'/script),str(stage)],cwd=root,capture_output=True)
    (work/(name+'.log')).write_bytes(result.stdout+result.stderr)
    (work/(name+'-exit.json')).write_text(json.dumps({'exit':result.returncode,'script':script})+'\n',encoding='utf-8')
    print(name,result.returncode,flush=True)
    if result.returncode:raise SystemExit(result.returncode)
shutil.make_archive(str(work/'blockreality-0.4.0-dev-native-candidate'),'zip',stage)
archive=work/'blockreality-0.4.0-dev-native-candidate.zip'
(work/'distribution.json').write_text(json.dumps({'file':archive.name,'bytes':archive.stat().st_size,'sha256':hashlib.sha256(archive.read_bytes()).hexdigest()},indent=2)+'\n',encoding='utf-8')
