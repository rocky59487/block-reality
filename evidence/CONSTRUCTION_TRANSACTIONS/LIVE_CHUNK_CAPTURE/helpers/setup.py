from pathlib import Path
import tarfile,shutil
root=Path('/home/rocky/br-live-chunk-capture');assert not root.exists();root.mkdir()
source=Path('/mnt/c/Users/wmc02/Desktop/block-reality/build/live-chunk-capture')
with tarfile.open(source/'source.tar') as tar:
    for m in tar.getmembers():
        assert (root/m.name).resolve().is_relative_to(root.resolve()) and (m.isfile() or m.isdir())
    tar.extractall(root,filter='data')
(root/'qualification').mkdir();(root/'OWNER').write_text('block-reality-live-chunk-capture-qualification-v1\n')
for name in ['run.py']:shutil.copy2(source/name,root/'qualification'/name)
print(root)
