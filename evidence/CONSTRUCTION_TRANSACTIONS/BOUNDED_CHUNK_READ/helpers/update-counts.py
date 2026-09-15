from pathlib import Path
import json
root=Path('C:/Users/wmc02/Desktop/block-reality')
counts=json.loads((root/'build/bounded-chunk-read/counts.json').read_text())
assert all(v=={'tests':137,'failures':0,'errors':0,'skipped':0} for v in counts.values())
for name in ['README.md','QUICKSTART.md','docs/RESEARCH_BRIEF.md','docs/outreach/LISTING.md']:
    p=root/name;data=p.read_bytes()
    for a,b in [(b'603',b'609'),(b'131 Forge',b'137 Forge'),(b'591 pass',b'597 pass'),('Forge 側 131'.encode(),'Forge 側 137'.encode())]:data=data.replace(a,b)
    p.write_bytes(data)
p=root/'CLAUDE.md';data=p.read_bytes()
for a,b in [('目前開發jar15,324,472B/SHAb5a9bab7e7b6…','目前開發jar15,326,800B/SHA4bf732d83a1e…'),
            ('Windows core472/Forge131：603登錄、591PASS、12平台SKIP；Linux602PASS、1平台SKIP。','Windows core472/Forge137：609登錄、597PASS、12平台SKIP；Linux608PASS、1平台SKIP。'),
            ('原版序列化吞錯與無上限disk NBT解析仍是正式接入前的限制。','原版序列化吞錯仍是正式接入前的限制；解析上限後續進度見下一段。')]:
    assert data.count(a.encode())==1;data=data.replace(a.encode(),b.encode())
anchor='- NATIVE_VERDICT_API 已刪除只看數字的未使用影線API'.encode()
addition='''- CT_BOUNDED_CHUNK_READ 已在同一IOWorker內限制解壓讀取16MiB+1，再用嚴格NBT解析，拒絕pending影像。
  13項區塊測試、雙平台Forge137PASS；省略stream cap與換回vanilla parser的可編譯反例被抓到。
  普通jar在隔離已安裝Forge專服12項檢查通過：真chunk完整checkpoint/after/恢復、箱子原NBT不變，
  duplicate/超量region拒絕且原檔不變。AT只由正式jar提供，harness不含它；專服正常exit0。
  既有253類別/雙庫與資源不變，只改storage轉接、新增reader與精確4條AT。core仍沿用相同來源實跑。
  詳 `evidence/CONSTRUCTION_TRANSACTIONS/BOUNDED_CHUNK_READ/RESULTS.md`；完整live capture/施工/undo/UI仍待接合。
'''.encode()
assert data.count(anchor)==1;data=data.replace(anchor,addition+anchor);p.write_bytes(data)
