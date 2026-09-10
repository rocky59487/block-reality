from pathlib import Path
import tarfile,socket,json,hashlib,shutil
base=Path('/home/rocky'); archive=Path('/mnt/c/Users/wmc02/Desktop/block-reality/build/hud-readability-final-source.tar')
for port in [25594,25595]:
 with socket.socket() as sock:sock.bind(('127.0.0.1',port))
for name in ['br-hud-readability-final-server','br-hud-readability-final-client']:
 dest=base/name;dest.mkdir()
 with tarfile.open(archive) as tar:tar.extractall(dest,filter='data')
run=base/'br-hud-readability-final-server/forge/run';run.mkdir()
for name in ['eula.txt','server.properties']:shutil.copy2(base/'br-client-render-server/forge/run'/name,run/name)
(run/'defaultconfigs').mkdir();shutil.copy2(base/'br-client-render-server/forge/run/render-probe/serverconfig/forge-server.toml',run/'defaultconfigs/forge-server.toml')
profile=base/'br-hud-readability-final-client/forge/run-client-probe';profile.mkdir();(profile/'options.txt').write_text('onboardAccessibility:false\n')
out=base/'br-hud-readability-final';out.mkdir();(out/'CRP_OWNED').write_text('block-reality-client-render-probe-v1\n')
lib=base/'br-bsi-retention-native/libbsi_tectonic.so'
(out/'identity.json').write_text(json.dumps({'source':'299750b','criteria':'59e7809','native_source':'95a03e82bbc50c53eac97b5289b79d8e640f8075','native_sha256':hashlib.sha256(lib.read_bytes()).hexdigest(),'server_root':str(run),'client_root':str(profile),'client_identity':'synthetic BRRenderProbe; offline test server'},indent=2))
launcher=(base/'br-client-render-third/launcher.py').read_text().replace('br-client-render-third','br-hud-readability-final').replace('br-client-render-client','br-hud-readability-final-client').replace('client-third','client-first').replace('client-xvfb-third','client-xvfb-first')
(out/'launcher.py').write_text(launcher)
print('Fresh HUD server, client and first evidence directory created')
