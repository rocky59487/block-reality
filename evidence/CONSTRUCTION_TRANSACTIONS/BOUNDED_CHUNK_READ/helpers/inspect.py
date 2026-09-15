from pathlib import Path
import zipfile,json,hashlib,xml.etree.ElementTree as ET
root=Path('C:/Users/wmc02/Desktop/block-reality');out=root/'build/bounded-chunk-read'
linux=Path('//wsl.localhost/Ubuntu-22.04/home/rocky/br-bounded-chunk-read')
counts={}
for platform,base in [('windows',root),('linux',linux)]:
    docs=[ET.parse(p).getroot() for p in (base/'forge/build/test-results/test').glob('TEST-*.xml')]
    counts[platform]={k:sum(int(d.attrib.get(k,0)) for d in docs) for k in ['tests','failures','errors','skipped']}
    assert counts[platform]=={'tests':137,'failures':0,'errors':0,'skipped':0}
old=zipfile.ZipFile(root/'build/ct-chunk-storage/blockreality-0.4.0-dev-chunk-storage.jar')
jar=out/'blockreality-0.4.0-dev-bounded-chunk.jar';new=zipfile.ZipFile(jar)
removed=sorted(set(old.namelist())-set(new.namelist()));added=sorted(set(new.namelist())-set(old.namelist()))
changed=[n for n in old.namelist() if n in new.namelist() and old.read(n)!=new.read(n)]
at=new.read('META-INF/accesstransformer.cfg');lines=[l.split('#')[0].strip() for l in at.decode().splitlines() if l.split('#')[0].strip()]
expected=['public net.minecraft.world.level.chunk.storage.IOWorker f_63518_',
          'public net.minecraft.world.level.chunk.storage.IOWorker f_63519_',
          'public net.minecraft.world.level.chunk.storage.IOWorker m_63545_(Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;',
          'public net.minecraft.world.level.chunk.storage.RegionFileStorage m_63711_(Lnet/minecraft/world/level/ChunkPos;)Lnet/minecraft/world/level/chunk/storage/RegionFile;']
assert lines==expected
assert at==(root/'forge/src/main/resources/META-INF/accesstransformer.cfg').read_bytes()
identity={'source':'bca242a','jar_bytes':jar.stat().st_size,'jar_sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),
          'added_entries':added,'removed_entries':removed,'changed_entries':changed,'unchanged_entries':len(old.namelist())-len(changed),
          'unchanged_classes':sum(n.endswith('.class') for n in old.namelist() if n not in changed),'access_transformer':lines,
          'access_transformer_sha256':hashlib.sha256(at).hexdigest()}
(out/'jar-identity.json').write_text(json.dumps(identity,indent=2)+'\n');(out/'counts.json').write_text(json.dumps(counts,indent=2)+'\n')
print(json.dumps({'artifact':identity,'forge_counts':counts},indent=2))
assert not removed and changed==['com/blockreality/impl/server/construction/ChunkFileParticipant$1.class']
assert added==['META-INF/accesstransformer.cfg','com/blockreality/impl/server/construction/BoundedChunkRead.class']
