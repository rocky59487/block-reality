from pathlib import Path
import tarfile, shutil, hashlib, json
root=Path('/home/rocky/br-manufactured-metadata')
source=Path('/mnt/c/Users/wmc02/Desktop/block-reality/build/manufactured-metadata')
with tarfile.open(source/'profile-source.tar') as archive:
    allowed={'mod/core/src/profile/java/com/blockreality/core/transaction/ManufacturedMetadataProfile.java','scripts/manufactured-metadata-profile.gradle'}
    files=[m for m in archive.getmembers() if m.isfile()]
    assert {m.name for m in files}==allowed
    for member in files:
        dest=root/member.name
        assert dest.resolve().is_relative_to(root.resolve()) and not dest.exists(), dest
        dest.parent.mkdir(parents=True,exist_ok=True)
        with dest.open('xb') as f:f.write(archive.extractfile(member).read())
fixture=source/'profile-fixture'; dest=root/'qualification/profile-fixture'
assert (fixture/'OWNER').read_text().strip()=='block-reality-manufactured-metadata-profile-v1'
assert not dest.exists()
def manifest(path):
    return {str(p.relative_to(path)).replace('\\','/'):{'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in sorted(path.rglob('*')) if p.is_file()}
before=manifest(fixture)
shutil.copytree(fixture,dest)
assert before==manifest(dest)==manifest(fixture)
record={'source':str(fixture),'destination':str(dest),'files':before,'file_count':len(before),'total_bytes':sum(f['bytes'] for f in before.values())}
(root/'qualification/profile-fixture-copy.json').write_text(json.dumps(record,indent=2))
shutil.copy2(root/'qualification/profile-fixture-copy.json',source/'profile-fixture-copy.json')
shutil.copy2(source/'profile.py',root/'qualification/profile.py')
print(json.dumps({'files':record['file_count'],'bytes':record['total_bytes'],'identical':True}))
