from pathlib import Path
import tarfile,shutil,json,socket
base=Path('/home/rocky');work=Path('/mnt/c/Users/wmc02/Desktop/block-reality/build/material-geometry')
for port in [25594,25595]:
    with socket.socket() as sock:sock.bind(('127.0.0.1',port))
for name in ['br-mg-final-server','br-mg-final-client']:
    dest=base/name;dest.mkdir()
    with tarfile.open(work/'module-final-source.tar') as archive:archive.extractall(dest,filter='data')
run=base/'br-mg-final-server/forge/run';run.mkdir();(run/'defaultconfigs').mkdir()
for name in ['server.properties','eula.txt','defaultconfigs/forge-server.toml']:
    shutil.copy2(base/'br-mg-render-server/forge/run'/name,run/name)
profile=base/'br-mg-final-client/forge/run-client-probe';profile.mkdir();(profile/'options.txt').write_text('onboardAccessibility:false\n')
out=base/'br-mg-render-final';out.mkdir();(out/'CRP_OWNED').write_text('block-reality-client-render-probe-v1\n')
identity=json.loads((base/'br-mg-render-first/identity.json').read_text());identity['module_source']='b10884b5802c24121263fb779319860c7f95ec9c'
(out/'identity.json').write_text(json.dumps(identity,indent=2))
launcher=(base/'br-mg-render-first/launcher.py').read_text().replace('br-mg-render-first','br-mg-render-final').replace('br-mg-render-client','br-mg-final-client')
(out/'launcher.py').write_text(launcher)
source=base/'br-material-geometry/forge/src/main/java/com/blockreality/impl/client/StressSurfaceRenderer.java'
shutil.copy2(base/'br-mg-final-client/forge/src/main/java/com/blockreality/impl/client/StressSurfaceRenderer.java',source)
print('Fresh final module client/server fixtures prepared; prior receipts retained')
