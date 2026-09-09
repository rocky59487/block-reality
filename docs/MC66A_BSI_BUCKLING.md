# MC66A BSI 同次屈曲快照（2026-09-09，實作前凍結）

前置：MC66A_ISLAND_SNAPSHOT、MC66A_BUCKLING_MEMBERS、MC64_FORWARD。
本單元接 BSI v1 host → adapter → LiveState → Java；不擴充 engine ABI 1 的結構。
frame_v2 進階 eigen 選項、殼屈曲、GAME_SWAP 與新發布不在本單元完成宣稱內。

## 硬線與獨立 oracle

1. 每個 diag island 恰有一筆 16B 屈曲記錄，輸出按 id=0..n-1；拒絕缺筆、重複、越界。
   kind 必須等於請求；none 僅 disabled，eigen/screen 禁 disabled。未知 enum、Computed
   的非有限/非正因子、其他狀態的非 NaN 因子全部 INTERNAL，產出 section 前拒絕。
   空 none=disabled、空 eigen/screen=not-eligible。宿主允許 screen 契約但引擎不宣告它。
2. 世界摘要以 SolverFailed > NotEligible > Scale > Computed > NoPositive 聚合。
   使用父單元獨立凍結的 125 列真值表，core 的 4 明確轉 BSI 的 5；三島全排列
   已涵蓋在表中。世界因子僅 Computed 時取各 Computed 最小值；BSI header 不加欄位。
   局部 computed 島的 bit2 不因別島拒絕/失敗而抹除。Java 只 OR 引擎旗標，不重算 λ<1。
3. 宿主在完整記錄驗證後按 canonical id 直接索引；每格旗標沿既有嚴格 double λ<1
   與自己島記錄雙向核對，不能以世界摘要代替。非法 owner island 也拒絕。
   本線證明索引形狀，不宣告時間/RSS 改善。
4. 原生 eigen 僅走 LiveState.solveWithBuckling 同次已驗快照；block/member flags直接轉發。
   budgetDof 範圍 0..INT_MAX，0=既有lane自動預算；預設 subdiv=2/maxIter=300/tol=1e-8。
   不得默默接受未支援的新選項；screen/display 仍 UNSUPPORTED。失敗 enum 不得錯映 disabled。
5. 真 BSI C10 Euler tip 與 Greenhill：相對解析值誤差 <=0.005；probe 相對差 <=1e-6。
   同世界至少檢查壓縮、拉伸/無載、機構/殼拒絕、budget scale，及 critical 局部旗標。
   新請求後再不請求：全部 disabled、NaN、bit2 清除；舊正常 solve bytes 與前版逐位。
   舊 hello capability/hash 正當改變不列 solve bytes。f32 不窄化 buckling factor 或旗標。
6. Java additive opt-in headers/solve/analyze 保留舊簽章/舊未請求字串；混合狀態完整解碼、
   世界摘要必須與每島一致；未知/不完整/矛盾拒絕，不健康化。保留不可變每島 typed結果。
   真 JNA 使用本次來源重建庫與新契約，不用已發布舊庫當新能力證據。

## 會咬的故障與驗證

host: AGG（任一computed）、IDS（跳過完整身分）、KIND、FACTOR、ORDER、CRITICAL（<=1）。
adapter: SNAPSHOT（不走新求解）、STATE（錯映失敗）、BUDGET（忽略預算）、FLAGS（清bit2）。
Java: AGG、FACTOR、CRITICAL（拒絕局部computed）、KIND，各自獨立故障證明。
每臂正常具名 checks 清單與精確 FAIL 集合首跑 harvest 後 dated 獨立 commit 釘死；
未固定前不得用於能力宣稱。編譯失敗/環境故障/逃逸照登，修訂不得覆蓋首次原始記錄。
Windows/Linux/i9 同 build DET3，Linux ASan/UBSan（非 LSan）；host既有測試、Java core/Forge、
真 native C10 / 三傳輸、原 WORLD/lane 與相關 BSI 回歸按觸及面執行。
原始 stdout/stderr base64+SHA、binary/source SHA、disk/index/HEAD 核對；兩倉 contract
逐位相同、pin 與 consumer engine ref 同步 commit。未知結果不以 SKIP 或前版 PASS 補上。
