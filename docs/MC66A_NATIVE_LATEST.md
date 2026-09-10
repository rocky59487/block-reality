# MC66a 候選包與完整模組分支對位

2026-09-10，合入模組新來源前凍結。前一範圍MC66A_NATIVE_PACKAGE的全部證據保持，
其consumer來源為bbe9ccd，不能套到新的Java程式碼。
PR完整盤點顯示#108–#118已另行完成原生遊戲流程、局部Critical語意、狀態交付、
原生資料API/舊力學退場、持久索引、建造身份、量測/壓縮與真client probe。
本階段固定consumer #118 dc94b2b4fc4fb9df55f67a635042cb300f6f80e9；不追逐其後移動目標。

[硬] 在自己的整合分支合入上述來源，不改其原分支、不重做它的功能、不改原先FAIL與判準。
候選引擎仍42e10f5，兩平台DLL/SO、ZIP、SOURCE_MANIFEST與根SHA256SUMS不得變更；
contract55檔/hash保持。CI來源ref維持42e10f5；模組包的來源/清單/授權沿既有完整鏈。
[硬] 新模組完整Windows core/Forge、API purity、文件計數、checkNativeOnlyJar與compiled
legacy-class/reference-only反例；所有native-dependent必須執行，平台SKIP逐項記錄。
Linux真JNA相關選集無SKIP，含原Engine/Recovery/Analysis/Buckling/GameInput及新原生消費測試。
新reobfJar必須由上述新模組來源產生，兩平台48frames×3/兩JVM競爭重跑，九bundle反例仍咬。
新jar完整source/native/jar SHA保留；舊15,148,828B jar是前一階段證據，不得稱為本階段產物。
[硬] 本階段不改引擎core或重建已驗DLL/SO，不冒充重跑334/i9或原2690/MC66a/ASan。
模組#118的軟體渲染client證據與已知視覺FAIL只讀取對位，不冒充本次真client驗收。
沒有新FPS/效能宣稱、沒有正式release；下一工作先核對最新模組責任及引擎欠項，
不能重做已完成的GAME_RUNTIME或局部Critical資料語意。原v2/v3/v4欠項保持。
