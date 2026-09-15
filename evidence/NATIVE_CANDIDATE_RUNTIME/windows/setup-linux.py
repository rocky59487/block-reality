from pathlib import Path
import tarfile,shutil,hashlib,json
workspace=Path('/mnt/c/Users/wmc02/Desktop/block-reality/build/native-candidate-runtime')
root=Path('/home/rocky/br-native-candidate-runtime');root.mkdir()
with tarfile.open(workspace/'module-source.tar') as archive:archive.extractall(root,filter='data')
native=root/'native';native.mkdir();source=workspace/'staged/linux-x86_64/libbsi_tectonic.so';shutil.copy2(source,native/source.name)
expected='53aae7156b94c0a761ef5abb2322ad47a708362f417a50ca3f04105d9c50abf1';actual=hashlib.sha256((native/source.name).read_bytes()).hexdigest();assert actual==expected
out=root/'qualification';out.mkdir();(out/'identity.json').write_text(json.dumps({'module_source':'c2a1b94','criteria':'d96d98a','native_source':'42e10f5f7af166788588afcd2fb2fd97101d16dc','native_sha256':actual},indent=2))
print('Isolated current module and delivered self-contained SO ready; no engine build')
