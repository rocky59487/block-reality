from pathlib import Path
import tarfile,hashlib,json,shutil
root=Path('/home/rocky/br-forge-axis-transactions');assert (root/'OWNER').read_text().strip()=='block-reality-forge-axis-transactions-qualification-v1'
source=Path('/mnt/c/Users/wmc02/Desktop/block-reality/build/forge-axis-transactions')
same_core=0
with tarfile.open(source/'source-third.tar') as tar:
    for m in tar.getmembers():
        assert (root/m.name).resolve().is_relative_to(root.resolve()) and (m.isfile() or m.isdir())
        if m.isfile() and m.name.startswith('mod/core/'):
            assert (root/m.name).read_bytes()==tar.extractfile(m).read();same_core+=1
    tar.extractall(root,filter='data')
shutil.copy2(source/'run.py',root/'qualification/run.py')
(root/'qualification/source-third.json').write_text(json.dumps({'source':'338c490','archive_sha256':hashlib.sha256((source/'source-third.tar').read_bytes()).hexdigest(),
    'unchanged_core_files_compared':same_core,'core_and_process_second_receipts_reused':True},indent=2));print('Updated Forge sources; unchanged core files',same_core)
