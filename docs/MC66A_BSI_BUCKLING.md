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


## 2026-09-09 C10 前置：材料 Euler–Bernoulli 映射（實作前追加）

讀取真 C10 發現其材料 eulerBernoulli=true 仍被 adapter 拒絕。原先將 C10 視為只差
eigen接線的範圍估計不足，不能以替換語料/assume繞過；本段增加明確前置。
新增 MaterialDef 預設 false 的欄位，沿唯一 buildSection(sd,md,axisRot,sp) 決定
sd.eulerBernoulli OR md.eulerBernoulli 時 Asy=Asz=0，其他幾何/容量不變。
禁止改寫共用 SectionDef；同一斷面供兩材料使用、顯式/預設section、旋轉軸與舊section旗標
必須各自正確。adapter將契約材料旗標轉發；frame_v2原section旗標与未請求行為不變。
獨立 oracle：同短懸臂端橫力，EB 位移 PL^3/(3EI)，Timo再加 PL/(G As)。
按實际求解值檢查 <=1e-9 相對誤差，不能只測Asy屬性；共用斷面無污染按解析兩解對照。
既有 TEC_MUT_BSI_CORE_EB_IGNORE 保留具名故障並改為「忽略材料旗標」；原 CORE-EB
拒絕oracle保存在Git歷史/首次回歸輸出，dated改為成功映射且必須有物理差異。
新故障 TEC_MUT_BSI_BUCKLING_EB_SHARED 將設定污染共用斷面，必須被兩材料腿抓到。
這是 BSI 已有 opt-in 欄位的新增支援，不修改 ABI/預設求解、不宣稱MC全部材料語意已完成。


## 2026-09-09 首次 C++ 輸格

首輪833 checks為825PASS/8FAIL；失敗全部在新增短梁位移oracle。診斷顯示MC節點在格中心
(x+0.5,y+0.5,z+0.5)，測試以整數格址尋找節點而讀不到位移，得到NaN。修正測試的
節點定位，不改原fixture、解析公式或1e-9線。首版/診斷版來源與raw輸出各自保留。


## 2026-09-09 舊 BSI 邊界 gate 能力遷移

舊 runner 首跑310 checks有15FAIL：三輪各CAPS、EIGEN、EB、EB重宣告、EB回收比較。
舊線預期eigen/EB拒絕；本段已新增支援，依前凍條文更新這些能力預期，原輸出/source保留。
EB_IGNORE 的物理故障移到新 BSI buckling 解析短梁 gate；不再以舊拒絕臂作其證據。
新 C10 原語料實跑PASS，無assume；9PASS/1SKIP(C13 custom)。新增budget非法值與未知
進階選項的PROTOCOL_ERROR腿，缺mode不改原schema預設語意。


## 2026-09-09 SCHEMA_UNKNOWN 真反例

更新後邊界334 checks有9FAIL：三輪各subdiv/maxIter/tol被巢狀schema默默接受。
原schema缺additionalProperties=false；依原凍結的未知選項拒絕硬線補上，未擴充ABI。
首輪原始輸出、schema開放版原生庫SHA與來源保留作SCHEMA_UNKNOWN反例；這是實際漏驗，
不是預期能力遷移。其餘325項已過；完整334固定結論待新schema重建後驗證。
