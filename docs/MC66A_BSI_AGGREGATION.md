# MC66a BSI 世界聚合與消費端

2026-09-09；實作前獨立提交。基線 consumer b52a18d、引擎配對
codex/mc66a-island-snapshot；前置 MC66A_ISLAND_SNAPSHOT 與 MC64_FORWARD。

## 範圍與適用性

本單元只修共同 contract host 與 Java 消費入口；不改求解核、不宣告 eigen
capability、不換遊戲入口、不重發 v1.3。下一單元才接 LiveState 快照至 BSI/frame_v2。
既有 host「任一 computed 即世界 computed」會隱藏失敗；Java 拒絕混合狀態則會
丟掉有效局部結果。MC64_FORWARD 的混合狀態拒絕在本單元由完整每島 API 取代。

## 凍結規則

1. 請求 eigen/screen 時完整每島一筆，id 恰為 [0, diag.islands)，不得重複、缺少或
   越界；wire 按 id 升序。kind 必須與請求一致，eigen/screen 混用為 INTERNAL。
   none 時每島仍有 disabled/none/NaN；空世界零筆，none 摘要 disabled，其他為 not-eligible。
2. 世界優先序固定 SolverFailed > NotEligible > Scale > Computed > NoPositive。
   disabled 只屬 none 請求；active 夾入 disabled、none 夾入 active 均拒絕。
   computed 的 factor 必須有限且 >0；其他 state 必须 NaN。enum/保留位無非法值。
   摘要 computed 才能提供世界 factor（computed 島最小值）；其他摘要無世界 factor。
3. host 只聚合/校验型別，不求解。Java 驗證完整集合與 header 聚合一致，保存每島
   id/kind/state/f64 factor 到不可變 API；保留現有建構子相容性。
   Java 不比 lambda 與 1，不從 factor 重建 Critical；對完整 block bit2 做 OR，
   世界拒絕不能清掉其他 computed 島的 Critical。非 computed 島的 bit2 必拒絕。
4. Java 本輪只接受 none/eigen；screen 保留原明確拒絕，避免把篩查值冒充 eigen。
   Forge 現有摘要線維持格式；混合世界的拒絕 state、零世界 factor、true Critical
   必可 round-trip。每島 API 本輪留伺服器，後續 wire 接線才議逐島網路展示。
5. 兩倉 contract 完全鏡像，重新計算 hash 與 consumer ref。此為未定義/自相矛盾
   輸出守門修正，BSI 主版不升；舊合法 none/disabled 回覆 bytes 不變（握手 hash 除外）。

## Oracle 與硬線

- 獨立固定 5×5 優先序表枚舉全部 125 個三島組合、正反寫入序、空集合；
  不引用 engine core 或被測聚合函式產生期望值。
- Host：完整集合、kind/disabled、非法 enum、factor NaN/Inf/0/負數、順序與失敗輸出
  原子性（無半份 payload）。真 HostSession/CAPI 的原 gate 一併回歸。
- Java：同125組、computed 最小factor、拒絕不洩漏factor、每島保存/不可變、缺島/
  重複/越界/錯kind/錯header/非法factor拒絕；局部旗標不得以factor重算。
- Forge：混合世界 state/factor/Critical 摘要往返；舊封包測試回歸。
- Windows/Linux host 正常與故障變異；至少 ANY_COMPUTED、COLLECTION、KIND、FACTOR、
  ORDER、DISABLED 各有具名 FAIL。Java 至少狀態扁平化、世界factor洩漏、清局部旗標、
  漏每島保存四種源碼變異具名失敗。先 harvest 記錄名單，再獨立提交固定結果。
- 正常 host 三跑 bytes 相同才稱 DET3；保留首跑失敗，環境缺依賴不算通過或變異命中。
  不宣稱本輪已驗 native eigen、三箱求解、C10、i9、FPS、N16/N18 或 GAME_SWAP。

## 2026-09-09 固定具名結果（正式重跑前獨立提交）

Windows/Linux host harvest 同為417項；正常0FAIL，六變異依序
ANY_COMPUTED=108、COLLECTION=6、KIND=3、FACTOR=20、ORDER=126、DISABLED=2 FAIL，
每臂DET3。固定名單 MC66A_BSI_HOST_COUNTS.json；Java補齊空集合後14項，
FLATTEN=3、FACTOR_LEAK=2、CLEAR_CRITICAL=1、DROP_ISLANDS=3 FAIL，
固定名單 MC66A_BSI_JAVA_COUNTS.json。後續 runner 以 --expected 比對，不自動重釘。

首輪環境失敗：Windows 通用 host CMake 未設 BSI_STATIC，writer 定義被標 dllimport；
補旗標後完整預設目標又在動態 stub 找不到 writer 符號。這兩次不是數值失敗，
也不算 gate PASS。改建本輪需要的 static host/CAPI 測試目標，四套通過；Linux五套通過。
Windows 長存工作樹另有歷史CRLF，整個contract按既有 .gitattributes 正規化LF，
之後才鏡像/重新建庫。首份Windows hash d81aa3d8不是提交身份；最終以CONTRACT_SHA256為準。
實際原契約為51檔，新版52檔；舊對位文件的54檔為過期計數。
