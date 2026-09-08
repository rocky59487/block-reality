# MC64_FORWARD：共同分析結果與判定轉發

2026-09-08，實作前凍結。基線為引擎 v1.3（c90b448，main 合入 c776ebc）與
consumer bbb0a8a（Main 合入 264a00c）。本單元不改引擎求解核、BSI 契約或發布標籤。

## 來源與範圍

1. 新 BSI→AnalysisResult 只有一個聚合入口，重用現有梁／殼樣本轉接。
   全域 overCapacity / bucklingCritical 只對完整 blocks 的既有 bit0 / bit2 做 OR；
   不以 maxDC 或 factor 與 1 比較。顯示 maxDc/owner 從完整 blocks 選最大已回傳 dc，
   同值保留 canonical block 序第一筆；這是展示摘要，不冒稱引擎另有全域主宰欄位。
2. AnalysisResult 保存獨立 boolean；舊建構子維持 Sidecar 的歷史 dc>1、0<factor<=1
   相容語意，集中在明示 legacy 入口。新 BSI 路徑不得經這個相容建構子。
   BSI factor<1 與舊 Sidecar <=1 的不同不能被一個共用數值比較抹平。
3. BSI 入口只接受本次 revision、完整成功 commit 軌回覆、完整必需區段、可信 quality、
   有限數值及一致的 diag/索引。錯誤、缺失、partial、display、timedOut、錯 revision
   都產生 failed AnalysisResult，不帶舊樣本或旗標。未提供的樣本不能當成功零筆。
4. 本版實際原生 buckling=none/disabled-by-request；真 JNA 只可宣稱這個狀態。
   手工 layout 的 computed/eigen 僅驗轉發。六種 BSI 狀態分別保留；未知狀態拒絕。
   不同島 state 混合、screen kind 的完整 API 表示尚未凍，入口明示拒絕，不壓成安全值；
   其擴充由 MC66/BUCK_SHELLS 負責。不得據此宣稱屈曲求解完成。
5. StressResultPacket 升 channel9，maxDc/factor 改 f64；原值與獨立旗標一起保留。
   刪除 alignToVerdict 與 float underflow 回填；0/負值/NaN/Inf 與 state 的結構矛盾拒絕，
   不以數值門檻修理旗標。全域旗標在展示截斷前形成，不能只掃留下的樣本。
   指令/HUD/ClientStressState 使用保存的 boolean。錯 revision/維度/清除的既有生命週期保留。

## 硬線與獨立 oracle

- 手工 LE 布局：dc=1 配 true、dc=nextUp(1) 配 false；factor=1 配 false、factor>1 配 true。
  這是故意不一致的傳輸 oracle，證明消費者沒有重判，不宣稱是合法物理解。
- 封包完整往返保持 f64 bit pattern 與 boolean；Double.MIN_VALUE 不回填，Double.MAX_VALUE
  不因窄化變 Inf。原任意截短、非法計數、NaN/Inf、未知 enum 及狀態矛盾繼續拒絕。
- 真 v1.3 JNA：原 C4 懸臂／固定端點力及混合機構模型，聚合摘要與原生 blocks、
  梁殼旗標、樣本、平衡帳一致；成功→錯 revision/拒絕→成功不能沿用舊結果。
- 舊 Sidecar 實跑與既有 Java/Forge suite 全跑。新增數量首跑後 dated 釘數；SKIP 照登。
- 具名隔離 Java 變異至少：全域改 dc 比較、屈曲改 factor 比較、BSI 丟 bit、忽略 revision、
  封包窄化、復活 alignToVerdict、未知 state 洗成 disabled；各正常編譯後由目標測試咬合。
- 原始 stdout/stderr、JUnit XML bytes/base64/SHA 保存；來源与 staged/committed Git bytes 核對。

## 誠實界線

不改舊力學、2690 計數、Linux 七個 FAIL 或 41.7ms FAIL；本單元不作效能宣稱。
已發布 v1.3 原生資產可直接用於真 JNA，無需因純消費者變更重建相同 core。
GAME_SWAP、真 Minecraft 視窗／HUD、display budget、jar 原生封裝仍各自待驗收；
本單元最多驗證到 headless result→packet，加上既有 HUD 欄位使用的程式檢查。
