from pathlib import Path
import tarfile,shutil,hashlib,json,subprocess
root=Path('/home/rocky/br-forge-axis-transactions');assert not root.exists();root.mkdir()
source=Path('/mnt/c/Users/wmc02/Desktop/block-reality/build/forge-axis-transactions')
with tarfile.open(source/'source-first.tar') as tar:
    for m in tar.getmembers():assert (root/m.name).resolve().is_relative_to(root.resolve()) and (m.isfile() or m.isdir())
    tar.extractall(root,filter='data')
(root/'qualification').mkdir();(root/'OWNER').write_text('block-reality-forge-axis-transactions-qualification-v1\n')
shutil.copy2(source/'run.py',root/'qualification/run.py');print(root)
