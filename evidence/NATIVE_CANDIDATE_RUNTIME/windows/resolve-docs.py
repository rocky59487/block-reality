from pathlib import Path
import subprocess
root=Path(__file__).resolve().parents[2]
def stage(version,path):return subprocess.check_output(['git','show',version+':'+path],cwd=root).decode('utf-8')
for path in ['CLAUDE.md','docs/MC66A_FRAME_V2.md','docs/V1_MODULE_PROGRAM.md']:
    (root/path).write_text(stage(':2',path),encoding='utf-8')
path='docs/NATIVE_CANDIDATE.md'
text=stage(':3',path)
text=text.replace('# MC66a 雙平台候選包\n','# MC66a 雙平台候選包\n\n> 此文件保存模組 #119 最新交付（10146b5，基於 #118）的資格範圍。\n> HUD #120 與目前整合候選 c2a1b94 的雙平台完整測試、同 jar 與真遊戲證據，\n> 另見 [NATIVE_CANDIDATE_RUNTIME](NATIVE_CANDIDATE_RUNTIME.md)。兩顆 jar 的 hash 與測試範圍不可混用。\n',1)
(root/path).write_text(text,encoding='utf-8')
path=root/'CLAUDE.md';text=path.read_text(encoding='utf-8')
text=text.replace('NCR 正在整合驗收，沒有改引擎。','NCR 已驗目前 HUD/模組候選，沒有改引擎。')
text=text.replace('- NATIVE_CANDIDATE_RUNTIME 已凍判準並整合 #119 的候選 staging/check 腳本與來源 pin。\n  root SHA 已核對交付資料；目前版本的双平台真庫/真 jar/遊戲驗收尚未執行，不能沿用舊候選的420項結論。','- NATIVE_CANDIDATE_RUNTIME 已整合 #119 及其最新交付文件，保留 #120 HUD。\n  同一顆15,203,560B jar帶雙平台42e10f5庫，兩平台48frame×3、解包/快取/權限/雙JVM通過；\n  Windows509 PASS/12平台SKIP，Linux520 PASS/1平台SKIP，原生相關全部執行。\n  真Forge開發環境由bundled資源解包，16項server狀態、24個合成玩家事件、45項client守門/16圖通過。\n  首次空世界前置錯誤與漏通知失敗保留；詳 `evidence/NATIVE_CANDIDATE_RUNTIME/RESULTS.md`。\n  這是候選與dev遊戲驗收，未替換正式資產，也不等於installed-jar/Windows CM/N25/FPS合格。')
text=text.replace('- Windows core412/Forge109：521登錄、492PASS、29SKIP。包含19項 native 整合測試的登錄，\n  不代表 Windows 已執行新原生庫。Linux 真 server 梁柱板/混合機構/支承恢復/reset 已實跑；','- Windows core412/Forge109：521登錄、509PASS、12平台SKIP；Linux520PASS、1平台SKIP。\n  19項 native 相關全部執行。新庫Linux真 server 梁柱板/混合機構/支承恢復/reset 已實跑；')
path.write_text(text,encoding='utf-8')
path=root/'docs/V1_MODULE_PROGRAM.md';text=path.read_text(encoding='utf-8')
old='NATIVE_CANDIDATE_RUNTIME 已凍結 #120 與 #119 封裝候選的整合判準。交付來源42e10f5\n與目前契約相同；已納入候選staging/check脚本及pin，正在驗完整521項登錄與最新真jar/遊戲流程。\n#119的420 PASS/12 SKIP是較早模組來源的結果，不能替代本分支。引擎來源與資產保持唯讀，\n詳 `NATIVE_CANDIDATE_RUNTIME.md`；此處尚未宣告新候選通過或發布。'
new='NATIVE_CANDIDATE_RUNTIME 已按先凍判準整合 #120 與 #119（含其10146b5文件增量）。\n來源42e10f5與目前契約相同；目前jar15,203,560B、SHA5a93c66bfe5f…，完整Windows509PASS/12平台SKIP、\nLinux520PASS/1平台SKIP。兩平台同jar48frame×3/快取/權限/雙JVM，來源10反例/4守門移除與bundle9反例通過。\n新庫真Forge開發环境16項狀態/24合成玩家事件/45項client守門與16張畫面通過，bundled資源自動解包。\n首個空世界前置與漏通知失敗照存；不替代installed-jar/Windows CM/N25/FPS，不替換正式引擎。\n詳 `../evidence/NATIVE_CANDIDATE_RUNTIME/RESULTS.md`；#119先後兩個候選的歷史數字/hash各自保留。'
assert old in text;text=text.replace(old,new);path.write_text(text,encoding='utf-8')
print('Resolved documentation while retaining HUD and current runtime; no shipping source changes')
