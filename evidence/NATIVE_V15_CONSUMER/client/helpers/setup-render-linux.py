from pathlib import Path
import tarfile, shutil, json, hashlib, socket

base=Path('/home/rocky/br-native-v15-render')
base.mkdir()
for port in [25594,25595]:
    with socket.socket() as s:
        s.bind(('127.0.0.1',port))
src=Path('/mnt/c/Users/wmc02/Desktop/block-reality/build/native-v1.5-consumer')
shutil.copy2(src/'render-source.json',base/'identity.json')
for role in ['server','client']:
    work=base/role;work.mkdir()
    with tarfile.open(src/'render-source.tar') as t:
        for m in t.getmembers():
            assert not m.issym() and not m.islnk() and (work/m.name).resolve().is_relative_to(work.resolve())
        t.extractall(work)
run=base/'server/forge/run';run.mkdir()
prior=Path('/home/rocky/br-mg-final-server/forge/run')
for name in ['server.properties','eula.txt']:
    shutil.copy2(prior/name,run/name)
if (prior/'defaultconfigs').exists():shutil.copytree(prior/'defaultconfigs',run/'defaultconfigs')
out=base/'observations';out.mkdir()
(out/'CRP_OWNED').write_text('block-reality-client-render-probe-v1\n')
shutil.copy2(base/'identity.json',out/'identity.json')
# Preserve the unchanged 45-check scene coordinator, defer only its final client exit.
original=(base/'server/scripts/client_render_scenes.py').read_text()
needle="        control({'action': 'exit'})\n"
assert original.count(needle)==1
(out/'client_render_scenes_keepalive.py').write_text(original.replace(needle,"        # Exit is deferred until the unchanged geometry interaction/capture checks finish.\n"))
shutil.copy2(base/'server/scripts/rcon_client.py',out/'rcon_client.py')
print(base)
