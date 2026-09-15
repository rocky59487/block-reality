from pathlib import Path
import json
root=Path('C:/Users/wmc02/Desktop/block-reality')
counts=json.loads((root/'build/ct-chunk-storage/counts.json').read_text())
assert all(v=={'tests':131,'failures':0,'errors':0,'skipped':0} for v in counts.values())
for name in ['README.md','QUICKSTART.md','docs/RESEARCH_BRIEF.md','docs/outreach/LISTING.md']:
    p=root/name;data=p.read_bytes()
    for a,b in [(b'596',b'603'),(b'124 Forge',b'131 Forge'),(b'584 pass',b'591 pass'),('Forge 側 124'.encode(),'Forge 側 131'.encode())]:data=data.replace(a,b)
    p.write_bytes(data)
p=root/'CLAUDE.md';data=p.read_bytes()
for a,b in [('目前開發jar15,316,390B/SHA1315dce1e785…','目前開發jar15,324,472B/SHAb5a9bab7e7b6…'),
            ('Windows core472/Forge124：596登錄、584PASS、12平台SKIP；Linux595PASS、1平台SKIP。','Windows core472/Forge131：603登錄、591PASS、12平台SKIP；Linux602PASS、1平台SKIP。')]:
    assert data.count(a.encode())==1;data=data.replace(a.encode(),b.encode())
anchor='- NATIVE_VERDICT_API 已刪除只看數字的未使用影線API'.encode()
addition='''- CT_CHUNK_PARTICIPANT 已新增同一IOWorker上的整批NBT保存、全部future排空、強制落盤及完整讀回比對。
  7項新測試含真region檔案重開，雙平台Forge131PASS；省略force/遺失capability兩反例被抓到。
  原250類別與v1.5庫/資源逐位不變，只增加4類別；core沿用4737e3c已實跑的相同來源。
  完整NBT需由host提供；原版序列化吞錯與無上限disk NBT解析仍是正式接入前的限制。
  詳 `evidence/CONSTRUCTION_TRANSACTIONS/CHUNK_PARTICIPANT/RESULTS.md`；尚無Forge施工呼叫者，CT仍開放。
'''.encode()
assert data.count(anchor)==1;data=data.replace(anchor,addition+anchor);p.write_bytes(data)
