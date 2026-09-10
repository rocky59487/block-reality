from pathlib import Path
import hashlib,json,zipfile
base=Path('/home/rocky/br-installed-native-server');out=base/'qualification'
with zipfile.ZipFile(base/'forge-1.20.1-47.4.13-installer.jar') as archive:
    profiles={name:json.loads(archive.read(name)) for name in ['install_profile.json','version.json']}
    for name in profiles:(out/name).write_bytes(archive.read(name))
expected={}
for name,profile in profiles.items():
    for library in profile['libraries']:
        artifact=library.get('downloads',{}).get('artifact')
        if artifact:
            key=artifact['path'];assert key not in expected or expected[key]==artifact
            expected[key]=artifact
checks=[];absent=[]
for relative,artifact in sorted(expected.items()):
    path=base/'server/libraries'/relative
    if not path.exists():absent.append(relative);continue
    body=path.read_bytes();assert len(body)==artifact['size'] and hashlib.sha1(body).hexdigest()==artifact['sha1'],relative
    other=base/'final-server/libraries'/relative
    assert other.read_bytes()==body,relative
    checks.append({'path':relative,'bytes':len(body),'sha1':artifact['sha1'],'sha256':hashlib.sha256(body).hexdigest()})
assert checks
(out/'forge-dependencies.json').write_text(json.dumps({'verified_in_both_installs':checks,'not_present_in_server_install':absent,'scope':'all present dependency artifacts listed by official installer profiles; generated patched Minecraft outputs validated by installer, not counted again'},indent=2)+'\n')
for arm in ['control','final']:
    classlog=(out/(arm+'-classload.log')).read_text()
    selected=[line for line in classlog.splitlines() if 'com.blockreality.' in line and 'source: union:' in line]
    assert selected and all('/mods/blockreality-0.4.0-dev.jar' in line for line in selected)
    assert not any(word in classlog for word in ['ClientRenderProbe source:','RenderServerProbe source:','StateDeliveryProbe source:'])
    (out/(arm+'-mod-class-origins.txt')).write_text('\n'.join(selected)+'\n')
print('Verified',len(checks),'official dependency artifacts in both installations;',len(absent),'profile entries absent; module origins are installed jars')
