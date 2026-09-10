from pathlib import Path
import json
root=Path('C:/Users/wmc02/Desktop/block-reality');linux=Path('//wsl.localhost/Ubuntu-22.04/home/rocky/br-forge-axis-transactions/qualification')
assert all(x['expected_behavior_observed'] for x in json.loads((linux/'mutations/runtime-oracle.json').read_text()))
def edit(path,changes):
    p=root/path;text=p.read_text(encoding='utf-8')
    for old,new in changes:
        assert old in text,(path,old);text=text.replace(old,new)
    p.write_text(text,encoding='utf-8')
edit('README.md',[('330_engine_%2B_614_Java','330_engine_%2B_622_Java'),('614 tests registered (472 core, 142 Forge-side)','622 tests registered (477 core, 145 Forge-side)'),('614 項測試已登錄（core 472、Forge 側 142）','622 項測試已登錄（core 477、Forge 側 145）')])
edit('QUICKSTART.md',[('資料層另外被 614 個測試釘住','資料層另外被 622 個測試釘住')])
edit('docs/RESEARCH_BRIEF.md',[('614/614 registered (Windows coverage: 602 pass','622/622 registered (Windows coverage: 610 pass')])
edit('docs/outreach/LISTING.md',[('614 Java tests (472 core, 142 Forge-side; Windows: 602 pass','622 Java tests (477 core, 145 Forge-side; Windows: 610 pass')])
edit('CLAUDE.md',[
('CONSTRUCTION_TRANSACTIONS 已先凍 CT-1..9/D-048，新增核心日誌與復原協調器，尚未接 Forge 施工入口。','CONSTRUCTION_TRANSACTIONS 已凍 CT-1..9/D-048，核心日誌/復原已接首個軸向入口；一般施工仍未完成。'),
('製造物件metadata後續進度見下一段，Forge入口仍待接合。','製造metadata與首個軸向Forge入口進度見後續段落。'),
('目前開發jar15,332,097B/SHA06e6355bc11d…','目前開發jar15,355,886B/SHA828d24b47b23…'),
('尚無Forge施工呼叫者；metadata資格不授權世界修改或退款','BUILD/undo仍無Forge呼叫者；metadata資格不單獨授權世界修改或退款'),
('RESULTS.md`；尚無Forge施工呼叫者，CT仍開放。','RESULTS.md`；軸向EDIT已接，完整CT仍開放。'),
('RESULTS.md`；普通施工入口、更新抑制與CT仍未完成。','RESULTS.md`；軸向交易抑制已接，普通放置與完整CT仍未完成。'),
('- Windows core472/Forge142：614登錄、602PASS、12平台SKIP；Linux613PASS、1平台SKIP。','- Windows core477/Forge145：622登錄、610PASS、12平台SKIP；Linux621PASS、1平台SKIP。'),
('- NATIVE_VERDICT_API 已刪除只看數字的未使用影線API，公開利用率配色必須收原生overload旗標。',
 '- CT_FORGE_AXIS_TRANSACTIONS 已接空手潛行右鍵的真Forge入口：完整區塊/旧覆蓋checkpoint、\n'
 '  PREPARED抑制觀測/派工/封包、COMMITTED後單次revision與發布，登入前復原。\n'
 '  普通jar實裝控制/恢復控制各60項通過，完整庫存/選取欄位/游標物NBT逐位不變；\n'
 '  3個真Minecraft JVM中斷與各2次重啟通過，提交前回滾、提交後保留、終態日誌不重寫。\n'
 '  時鐘保留與觀測洩漏兩個可編譯反例被抓到；首敗與原始日誌照存。\n'
 '  詳 `evidence/CONSTRUCTION_TRANSACTIONS/FORGE_AXIS_TRANSACTIONS/RESULTS.md`；玩家為synthetic，\n'
 '  一般放置/庫存消耗/blueprint/undo/C2S确认與真socket/UI仍開放，不能稱完整CT或高性能。\n'
 '- NATIVE_VERDICT_API 已刪除只看數字的未使用影線API，公開利用率配色必須收原生overload旗標。')])
p=root/'docs/V1_MODULE_PROGRAM.md';text=p.read_text(encoding='utf-8');start=text.index('目前模組來源5e5920d');end=text.index('\n原v1.5換裝單元',start)
text=text[:start]+'''目前模組來源338c490，開發jar15,355,886B、SHA828d24b47b23…；保留同一v1.5雙平台庫。
核心477項於d720995雙平台完整實跑，338c490核心來源逐位相同而沿用；Forge145項本輪雙平台全過。
合計622登錄：Windows610PASS/12平台SKIP，Linux621PASS/1平台SKIP。首個空手軸向EDIT已接
Forge交易服務，先checkpoint完整區塊/舊覆蓋，提交後才公開一次，啟動時先復原再開放分析。
普通jar隔離實装控制與恢復控制各60項通過，完整庫存NBT不變；三個Minecraft JVM中斷
各兩次重啟通過，兩個可編譯反例被抓到。來源、首敗與證據見
`../evidence/CONSTRUCTION_TRANSACTIONS/FORGE_AXIS_TRANSACTIONS/RESULTS.md`。
`codex/forge-axis-transactions`接續PR133；一般放置/消耗庫存/blueprint/undo與C2S確認仍待接合，
完整CT-1..9及#12/#17保持開放。玩家是synthetic；真socket/client、FPS/soak與正式v1仍待驗。
''' + text[end:];p.write_text(text,encoding='utf-8')
print('Updated current counts and axis-only scope in six documents')
