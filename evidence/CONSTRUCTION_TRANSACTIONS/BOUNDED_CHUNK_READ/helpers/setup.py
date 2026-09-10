from pathlib import Path
import tarfile,shutil
root=Path('/home/rocky/br-bounded-chunk-read');assert not root.exists();root.mkdir()
source=Path('/mnt/c/Users/wmc02/Desktop/block-reality/build/bounded-chunk-read')
with tarfile.open(source/'source.tar') as tar:
    for m in tar.getmembers():
        assert (root/m.name).resolve().is_relative_to(root.resolve()) and (m.isfile() or m.isdir())
    tar.extractall(root,filter='data')
(root/'qualification').mkdir();(root/'OWNER').write_text('block-reality-bounded-chunk-read-qualification-v1\n')
for name in ['run.py','test.gradle']:shutil.copy2(source/name,root/'qualification'/name)
print(root)
