from pathlib import Path
import tarfile,json
base=Path('/home/rocky');out=base/'br-mg-extra-first';out.mkdir()
with tarfile.open('/mnt/c/Users/wmc02/Desktop/block-reality/build/material-geometry/probe-extra-source.tar') as archive:
    archive.extractall(base/'br-mg-final-client',filter='data')
(out/'CRP_OWNED').write_text('block-reality-client-render-probe-v1\n')
identity=json.loads((base/'br-mg-render-final/identity.json').read_text());identity['probe_change']='vanilla creative inventory redirection accepted; shipping source unchanged'
(out/'identity.json').write_text(json.dumps(identity,indent=2))
launcher=(base/'br-mg-render-final/launcher.py').read_text().replace('br-mg-render-final','br-mg-extra-first')
(out/'launcher.py').write_text(launcher)
print('Supplemental interaction/capture output prepared')
