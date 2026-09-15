from pathlib import Path
import shutil,hashlib,json,sys,socket
root=Path('/home/rocky/br-forge-axis-transactions');case,mode=sys.argv[1:3]
server=root/('installed-'+case);out=root/'qualification'/('runtime-'+case+'-'+mode)
assert server.resolve().is_relative_to(root.resolve()) and (server/'OWNER').read_text().strip()=='block-reality-axis-runtime-v1'
assert (out/'exit.json').is_file()
with socket.socket() as sock:sock.bind(('127.0.0.1',25600))
dest=out/'durable-world';dest.mkdir();world=server/'axis-runtime'
files=list((world/'blockreality/construction').glob('*'))
files += [world/'data/blockreality_world_index.dat',world/'region/r.2.2.mca',world/'level.dat',world/'level.dat_old']
files += list((world/'region').glob('c.64.64.mcc'))
manifest={}
for p in files:
    if not p.exists():continue
    assert p.is_file() and not p.is_symlink() and p.stat().st_size<=64*1024*1024
    rel=p.relative_to(world);target=dest/rel;target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,target)
    manifest[rel.as_posix()]={'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()}
(out/'durable-world-manifest.json').write_text(json.dumps({'port_released':True,'files':manifest},indent=2))
print('Archived durable bytes',case,mode,len(manifest),flush=True)
