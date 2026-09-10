from pathlib import Path
import json,shutil
base=Path('/home/rocky'); first=base/'br-hud-readability-final'; out=base/'br-hud-readability-final-overflow';out.mkdir()
shutil.copy2(first/'CRP_OWNED',out/'CRP_OWNED')
identity=json.loads((first/'identity.json').read_text());identity.update(scene_driver='89e784a',scenario='restored beam at GUI scale 3; supplemental HR-3');(out/'identity.json').write_text(json.dumps(identity,indent=2))
(out/'launcher.py').write_text((first/'launcher.py').read_text().replace('br-hud-readability-final','br-hud-readability-final-overflow'))
shutil.copy2(Path('/mnt/c/Users/wmc02/Desktop/block-reality/scripts/hud_overflow_scenes.py'),base/'br-hud-readability-final-server/scripts/hud_overflow_scenes.py')
