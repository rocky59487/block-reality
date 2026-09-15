from pathlib import Path
import tarfile,hashlib,json
root=Path('/home/rocky/br-forge-axis-transactions');assert root.resolve()==Path('/home/rocky/br-forge-axis-transactions')
assert (root/'OWNER').read_text().strip()=='block-reality-forge-axis-transactions-qualification-v1'
source=Path('/mnt/c/Users/wmc02/Desktop/block-reality/build/forge-axis-transactions/source-second.tar')
with tarfile.open(source) as tar:
    for m in tar.getmembers():assert (root/m.name).resolve().is_relative_to(root.resolve()) and (m.isfile() or m.isdir())
    tar.extractall(root,filter='data')
(root/'qualification/source-second.json').write_text(json.dumps({'source':'d720995','archive_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'initial_snapshot_not_executed_on_linux':True},indent=2))
print('Updated owned Linux qualification source from the preserved second snapshot')
