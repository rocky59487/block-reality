from pathlib import Path
import json,xml.etree.ElementTree as ET,socket,subprocess
root=Path('C:/Users/wmc02/Desktop/block-reality');out=root/'build/live-chunk-capture'
linux=Path('//wsl.localhost/Ubuntu-22.04/home/rocky/br-live-chunk-capture')
counts={}
for platform,path in [('windows',out/'full-first-xml'),('linux',linux/'qualification/full-first-xml')]:
    docs=[ET.parse(p).getroot() for p in path.glob('TEST-*.xml')]
    counts[platform]={k:sum(int(d.attrib.get(k,0)) for d in docs) for k in ['tests','failures','errors','skipped']}
    assert counts[platform]=={'tests':142,'failures':0,'errors':0,'skipped':0}
(out/'counts.json').write_text(json.dumps(counts,indent=2))
assert json.loads((linux/'qualification/runtime-first/exit.json').read_text())=={'exit':0,'timeout':False}
result=json.loads((linux/'qualification/runtime-first/result.json').read_text());assert result['status']=='PASS' and len(result['checks'])==44
samples=[n/1e6 for n in result['captureNanosRecordedOnly']]
(out/'runtime-summary.json').write_text(json.dumps({'checks':44,'exit':0,'capture_ms':samples,
    'median_ms':(sorted(samples)[4]+sorted(samples)[5])/2,'max_ms':max(samples),'claim':'10 recorded samples after 2 warmups; no performance acceptance'},indent=2))
for name in ['README.md','QUICKSTART.md','docs/RESEARCH_BRIEF.md','docs/outreach/LISTING.md']:
    p=root/name;data=p.read_bytes()
    for a,b in [('609 tests','614 tests'),('609 項','614 項'),('609 個','614 個'),('609 Java','614 Java'),('609/609','614/614'),
                ('_609_Java','_614_Java'),('137 Forge','142 Forge'),('Forge 側 137','Forge 側 142'),('597 pass','602 pass'),('latest Windows run:','Windows coverage:')]:
        data=data.replace(a.encode(),b.encode())
    p.write_bytes(data)
p=root/'CLAUDE.md';data=p.read_bytes()
for a,b in [('目前開發jar15,326,800B/SHA4bf732d83a1e…','目前開發jar15,332,097B/SHA06e6355bc11d…'),
            ('Windows core472/Forge137：609登錄、597PASS、12平台SKIP；Linux608PASS、1平台SKIP。','Windows core472/Forge142：614登錄、602PASS、12平台SKIP；Linux613PASS、1平台SKIP。'),
            ('原版序列化吞錯仍是正式接入前的限制；解析上限後續進度見下一段。','解析上限與原版吞錯的後續修正見CT_BOUNDED_CHUNK_READ/CT_LIVE_CHUNK_CAPTURE。'),
            ('完整live capture/施工/undo/UI仍待接合。','完整live capture後續見下一段；正式施工/undo/UI仍待接合。')]:
    assert data.count(a.encode())==1;data=data.replace(a.encode(),b.encode())
anchor='- NATIVE_VERDICT_API 已刪除只看數字的未使用影線API'.encode()
addition='''- CT_LIVE_CHUNK_CAPTURE 已以直接capability/方塊實體見證檢查原版完整NBT，保留新增save-hook欄位，
  拒絕遺漏/改寫/不穩定資料與pending實體；整批擷取完成後才能寫入，不主動生成區塊。
  雙平台Forge142PASS；普通jar隔離實裝44項檢查通過，含兩種真capability第1–4次呼叫故障、
  hook錯誤、完整checkpoint/讀回、跨維度/執行緒拒絕與原庫存保留。核心沿用4737e3c相同來源。
  詳 `evidence/CONSTRUCTION_TRANSACTIONS/LIVE_CHUNK_CAPTURE/RESULTS.md`；普通施工入口、更新抑制與CT仍未完成。
'''.replace('\n','\r\n').encode()
assert data.count(anchor)==1;data=data.replace(anchor,addition+anchor);p.write_bytes(data)
print(json.dumps(counts),flush=True)
