from pathlib import Path
import tarfile,shutil
root=Path('/home/rocky/br-ct-chunk-storage');assert not root.exists();root.mkdir()
source=Path('/mnt/c/Users/wmc02/Desktop/block-reality/build/ct-chunk-storage')
with tarfile.open(source/'source.tar') as tar:
    for m in tar.getmembers():
        assert (root/m.name).resolve().is_relative_to(root.resolve())
        assert m.isfile() or m.isdir()
    tar.extractall(root,filter='data')
(root/'qualification').mkdir();(root/'OWNER').write_text('block-reality-chunk-storage-qualification-v1\n')
shutil.copy2(source/'run.py',root/'qualification/run.py')
print(root)
