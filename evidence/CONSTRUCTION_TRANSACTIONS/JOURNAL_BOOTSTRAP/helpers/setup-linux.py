from pathlib import Path
import tarfile,shutil,json,hashlib
root=Path('/home/rocky/br-ct-journal-bootstrap');root.mkdir()
source=Path('/mnt/c/Users/wmc02/Desktop/block-reality/build/ct-journal-bootstrap')
with tarfile.open(source/'source.tar') as t:
    for m in t.getmembers():assert not m.issym() and not m.islnk() and (root/m.name).resolve().is_relative_to(root)
    t.extractall(root)
shutil.copy2(source/'run.py',root/'run.py')
print(root)
