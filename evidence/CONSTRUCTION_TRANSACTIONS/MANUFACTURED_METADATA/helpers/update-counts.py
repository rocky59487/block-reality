from pathlib import Path
import json
root=Path('C:/Users/wmc02/Desktop/block-reality')
counts=json.loads((root/'build/manufactured-metadata/counts.json').read_text())
assert counts['windows']['core']=={'tests':472,'failures':0,'errors':0,'skipped':12}
assert counts['windows']['forge']=={'tests':124,'failures':0,'errors':0,'skipped':0}
for name in ['README.md','QUICKSTART.md','docs/RESEARCH_BRIEF.md','docs/outreach/LISTING.md']:
    p=root/name;data=p.read_bytes()
    for a,b in [(b'578',b'596'),(b'454',b'472'),(b'566 pass',b'584 pass')]:data=data.replace(a,b)
    p.write_bytes(data)
p=root/'CLAUDE.md';data=p.read_bytes()
for a,b in [('製造物件metadata/Forge入口仍待接合。','製造物件metadata後續進度見下一段，Forge入口仍待接合。'),
            ('目前開發jar15,286,967B/SHA73a491fba10e…保留v1.5雙庫；沒有正式v1發布。','目前開發jar15,316,390B/SHA1315dce1e785…保留v1.5雙庫；沒有正式v1發布。'),
            ('Windows core454/Forge124：578登錄、566PASS、12平台SKIP；Linux577PASS、1平台SKIP。','Windows core472/Forge124：596登錄、584PASS、12平台SKIP；Linux595PASS、1平台SKIP。')]:
    assert data.count(a.encode())==1;data=data.replace(a.encode(),b.encode())
anchor='- NATIVE_VERDICT_API 已刪除只看數字的未使用影線API'.encode()
addition='''- CT_MANUFACTURED_METADATA 已用COMMITTED日誌重建永久製造ID、精確所有權、不可逆編輯/退休與整批undo資格。
  18項新測試、兩平台完整測試及37次中斷/復原通過；所有權與undo兩個可編譯反例被抓到。
  原236類別與引擎/資源逐位不變，只增加14個metadata類別。雙平台864次日誌成本操作內容一致；
  單格準備p95≤0.61ms，持久提交約13–46ms，131K重建約141/167ms；同主機並行量測僅Recorded。
  尚無Forge施工呼叫者；metadata資格不授權世界修改或退款，完整CT-1..9/undo/UI仍開放。
  詳 `evidence/CONSTRUCTION_TRANSACTIONS/MANUFACTURED_METADATA/RESULTS.md`。
'''.encode()
assert data.count(anchor)==1;data=data.replace(anchor,addition+anchor);p.write_bytes(data)
p=root/'docs/V1_MODULE_PROGRAM.md';data=p.read_bytes()
old='這仍不重播已提交world/player歷史，也不是已完成製造物件metadata或Forge施工入口。'.encode()
new='這仍不重播已提交world/player歷史；製造物件metadata後續進度如下，Forge施工入口仍開放。'.encode()
assert data.count(old)==1;data=data.replace(old,new)
anchor='MODULE_PIPELINE_PROFILE 已將 metadata/gather/queue/BSI/解碼/封包/apply 分別量測，預設不收資料。'.encode()
addition='''CT_MANUFACTURED_METADATA 已使明確piece計畫取得永久UUID、COMMITTED日誌唯一權威與精確格所有權。
切割/依賴編輯使舊piece不可逆失去整批undo資格，退役ID不復用；損壞history/即時核實失敗拒絕使用。
18項新測試及兩個可編譯反例通過，完整596登錄：Windows584PASS/12平台SKIP、Linux595PASS/1平台SKIP。
最新jar1315dce1e785…只新增14類別，原236類別、v1.5雙庫/契約/資源不變。
雙平台同種子864次成本操作內容一致；同步提交p95約13–46ms，僅Recorded，未接受FPS。
詳 `../evidence/CONSTRUCTION_TRANSACTIONS/MANUFACTURED_METADATA/RESULTS.md`；尚無Forge施工呼叫者。
metadata只提供整批undo資格，不代替原world/item影像、權限/依賴或退款校驗；CT-1..9仍全開放。

'''.encode()
assert data.count(anchor)==1;data=data.replace(anchor,addition+anchor);p.write_bytes(data)
