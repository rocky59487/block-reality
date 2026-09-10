from pathlib import Path
import tarfile,shutil,hashlib,json,socket
base=Path('/home/rocky'); workspace=Path('/mnt/c/Users/wmc02/Desktop/block-reality/build/material-geometry')
for port in [25594,25595]:
    with socket.socket() as sock:sock.bind(('127.0.0.1',port))
for name in ['br-material-geometry','br-mg-render-server','br-mg-render-client']:
    dest=base/name;dest.mkdir()
    with tarfile.open(workspace/'module-source.tar') as archive:archive.extractall(dest,filter='data')
root=base/'br-material-geometry';qual=root/'qualification';qual.mkdir()
staged=base/'br-native-candidate-runtime/staged'
identity={'module_source':'9b6c7073012842d004fe48f4186dbca61931c8d4','criteria':'7e0b70f','native_source':'42e10f5f7af166788588afcd2fb2fd97101d16dc','native_sha256':hashlib.sha256((staged/'linux-x86_64/libbsi_tectonic.so').read_bytes()).hexdigest(),'scope':'owned Linux development source sets; real client/server; same delivered library'}
assert identity['native_sha256']=='53aae7156b94c0a761ef5abb2322ad47a708362f417a50ca3f04105d9c50abf1'
(qual/'identity.json').write_text(json.dumps(identity,indent=2))
directory=base/'br-mg-render-server/forge/run';directory.mkdir()
(directory/'server.properties').write_text((base/'br-ncr-render-server/forge/run/server.properties').read_text())
(directory/'eula.txt').write_text('eula=true\n');(directory/'defaultconfigs').mkdir()
shutil.copy2(base/'br-ncr-render-server/forge/run/defaultconfigs/forge-server.toml',directory/'defaultconfigs/forge-server.toml')
profile=base/'br-mg-render-client/forge/run-client-probe';profile.mkdir();(profile/'options.txt').write_text('onboardAccessibility:false\n')
out=base/'br-mg-render-first';out.mkdir();(out/'CRP_OWNED').write_text('block-reality-client-render-probe-v1\n')
(out/'identity.json').write_text(json.dumps(identity,indent=2))
launcher=(base/'br-ncr-render-first/launcher.py').read_text().replace('br-ncr-render-first','br-mg-render-first').replace('br-ncr-render-client','br-mg-render-client')
(out/'launcher.py').write_text(launcher)
print('Owned module, server, client and first evidence roots prepared')
