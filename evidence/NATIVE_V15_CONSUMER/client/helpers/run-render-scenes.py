from pathlib import Path
import subprocess,sys,json,hashlib
base=Path('/home/rocky/br-native-v15-render')
out=base/({'ready-profile':'observations-ready','complete-profile':'observations-final'}.get(sys.argv[1] if len(sys.argv)>1 else '', 'observations'))
scripts=base/'server/scripts';config=base/'server/forge/run/server.properties'
assert (out/'CRP_OWNED').read_text().strip()=='block-reality-client-render-probe-v1'
stages=[('models',scripts/'material_geometry_scenes.py','models'),
        ('capture',out/'client_render_scenes_keepalive.py','capture'),
        ('interact',scripts/'material_geometry_scenes.py','interact'),
        ('geometry-capture',scripts/'material_geometry_scenes.py','capture'),
        ('stop',scripts/'client_render_scenes.py','stop')]
for label,script,stage in stages:
    command=[sys.executable,str(script),'--config',str(config),'--out',str(out),'--stage',stage]
    with (out/(label+'-first.log')).open('xb') as f:
        run=subprocess.run(command,stdout=f,stderr=subprocess.STDOUT,timeout=360)
    (out/(label+'-first-exit.json')).write_text(json.dumps({'exit':run.returncode,'command':command,'script_sha256':hashlib.sha256(script.read_bytes()).hexdigest()}))
    print(label,run.returncode,flush=True)
    if run.returncode:raise SystemExit(run.returncode)
