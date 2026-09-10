# v1 模組交付程序

2026-09-10。使用者指示：持續自主整合到 v1，可發布中途版本；**引擎另有人開發，本工作只做模組**。

## 責任與發布邊界

本倉負責玩家宣告、世界快照與持久化、BSI 消費、網路、渲染、UI、資源及 jar。
tectonic2 的求解、抽取、後處理、斷裂、壓碎、接觸與剛體運動由引擎團隊交付。
只讀取其已交付分支、契約與資產；不修改或合併引擎倉、不替引擎發布。
不在 Java 補公式、閾值判定、重力積分、碰撞衝量或碎裂規則。缺少能力時明示不可用。
Java 可做資料驗證、SI 顯示單位轉換、引擎樣本的視覺插值與材質繪製。

「完美」用下面的可重現驗收定義；尚無證據的項目維持待驗，不以 stub 或 SKIP 充數。
v1 必須使用發布當時最新**已通過模組驗收**的 tectonic2 資產，保存來源 commit、契約及各平台 hash。
若最新引擎未通過相容性，先修消費端或登記阻擋，不把舊資產標為最新。

## 交付順序

| 單元 | 模組工作與既有 issues | 出口 |
|---|---|---|
| GAME_INPUT | #89/#13：材料目錄、引擎 ID 回覆、不可變快照、明確軸向、六面地面記錄 | `GAME_INPUT.md` 的純 Java 與真原生入口測試 |
| GAME_RUNTIME | #89：原生 session、專用 executor、inprocess/off、狀態、停止/切世界/失敗、舊程序退場 | 無遊戲 subprocess；N19–N21 新路徑；真 server 分析；離線 jar |
| WORLD_IDENTITY | #86/#17/#12/#4：持久區域、交易 revision、載入/卸載、損傷與 lineage | 重開世界與跨 chunk 結果一致；舊承諾不寫世界 |
| UI_INTERACTION | #7/#2：放置軸向/截面、材料資訊、掃描/HUD、局部危險與世界未評估分開 | N25 客戶端；雙語；封包預算；截圖與指令同一 revision |
| COLLAPSE_PRESENTATION | #16/#3/#2：消費引擎 lifecycle、fracture/crushing、剛體姿態，伺服器權威同步 | 真引擎事件回放；重複/亂序去重；兩客戶端一致；滾動/靜止/重載實測 |
| CONSTRUCTION | #5/#6/#8/#13：損傷外觀、拆除回收、重新安裝與材料/質量帳 | 有身分的原子交易；庫存與世界不重複/不遺失；引擎帳對位 |
| RELEASE | #90/#91：Win/Linux jar、來源/授權、BSI evidence 鏈、效能與遊戲 soak | 下列 v1 門檻全過；中途版明列尚缺能力 |

#65/#75/#94 與 #9 中的求解/抽取缺陷屬引擎需求；模組保留複現場景與消費驗證，
不自行修物理，也不只因上游有程式碼就關 issue。macOS 未有合格資產前明示不支援分析。

## 分支整合

起點 Main `264a00c`；模組 #97→#98→#99→#100→#101→#102→#103→#105 是依賴鏈，
本工作從 #105 `6e72d99` 接續。#104 已被 #103 取代，保留歷史、不重新合入。
先檢查每個 PR 的實際 CI 步驟與增量差異，再按依賴合併；不 force push。
Main 契約變更需與引擎團隊的交付狀態對位，避免讓兩倉預設分支落入未說明漂移。
目前 CI 原生 job 因缺 token 跳過建置，不能當作 native 資格；保留本地真引擎證據。

## v1 發布門檻

1. 一個離線可安裝 jar，零可執行檔、零子程序；Windows/Linux 均有真 JNA、來源與授權清單、
   正確自解/競爭測試。平台發布政策在發布時查證。不能把符合檔案規則說成平台已審核。
2. Java 生產路徑零力學公式、零物理後處理；結果/事件/姿態有引擎來源與 revision。
   native capabilities 不足、hash 不符、缺資料時停用相應能力，不能退回舊 sidecar。
3. N19–N25 與既有 DISPLAY_DELIVERY/NATIVE 判準保留。原 41.7 ms 與 Linux 7 FAIL 不覆寫。
   高性能另以固定場景量 gather、編碼/JNA/解碼、封包、apply、FPS 與記憶體；凍 fixture/硬體/
   分位數/預算後才優化，未量到之前不宣稱高性能或零拷貝。
4. 材質貼圖覆蓋所有可放置結構材料與損傷狀態；放置方向可見，無缺失貼圖。
   梁/板/混合機構世界的掃描、hover、HUD、載重編輯於真客戶端驗收。
5. 倒塌、壓碎、整體脫落、剛體滾動與靜止由真引擎驅動，保存/重載、多玩家、事件重播不重複改世界。
   引擎尚未交付的事件/動態通道是明確依賴，不能用 Java 手寫 fallback 通過。
6. 每个發版保存 jar/hash/版本矩陣/實跑 log/未完成項；v1 無未解決的上述阻擋。

## 現況

最新交付已更新為v1.5，模組Main #126的契約同步已整合。本工作只消費來源42ba7eb9、
契約5d4367f40de8的發布SDK；引擎倉未修改/建置/合併/發布。
目前模組來源338c490，開發jar15,355,886B、SHA828d24b47b23…；保留同一v1.5雙平台庫。
核心477項於d720995雙平台完整實跑，338c490核心來源逐位相同而沿用；Forge145項本輪雙平台全過。
合計622登錄：Windows610PASS/12平台SKIP，Linux621PASS/1平台SKIP。首個空手軸向EDIT已接
Forge交易服務，先checkpoint完整區塊/舊覆蓋，提交後才公開一次，啟動時先復原再開放分析。
普通jar隔離實裝控制與恢復控制各60項通過，完整庫存NBT不變；三個Minecraft JVM中斷
各兩次重啟通過，兩個可編譯反例被抓到。來源、首敗與證據見
`../evidence/CONSTRUCTION_TRANSACTIONS/FORGE_AXIS_TRANSACTIONS/RESULTS.md`。
`codex/forge-axis-transactions`接續PR133；一般放置/消耗庫存/blueprint/undo與C2S確認仍待接合，
完整CT-1..9及#12/#17保持開放。玩家是synthetic；真socket/client、FPS/soak與正式v1仍待驗。

原v1.5換裝單元jar15,286,415B、SHA9d8cb6795e9c…的來源/授權/封裝/JNA及真安裝專服門檻1–5通過。
完整573登錄：Windows561PASS/12平台SKIP、Linux572PASS/1平台SKIP；兩平台96frame×3直連
與3次jar/JNA回覆一致，涵蓋C14原生殼indicative警示。真專服10項與重啟讀數/快取通過。
詳 `../evidence/NATIVE_V15_CONSUMER/RESULTS.md`；本版client127模型/14互動/45守門及22圖通過，厚殼警示遊戲輸入仍未觀測；CI f7f9533四項通過/15原生步驟SKIP。
最新SDK是線性挫屈交付，不代表倒塌/壓碎/接觸/剛體已可用。以下保留較早單元證據。

MATERIAL_GEOMETRY 已在模組來源b10884b完成矩形產品外觀/目標/碰撞形狀、端面方向、
未宣告警示及原生取樣映射。真Linux127項模型檢查、14項程式驅動原版客戶端互動、
原16圖/45項守門及6張補充圖通過；Windows516PASS/12平台SKIP、Linux527PASS/1平台SKIP。
該單元jar15,216,948B、SHA8e587a715ebd…，兩庫為當時的42e10f5。來源/封裝/首敗見
`../evidence/MATERIAL_GEOMETRY/RESULTS.md`。這不是Windows CM/N25/FPS或v1資格。

最新唯讀同步：引擎#40在e20b416已關閉未合併；發布v1.5取代先前42e10f5候選。
新模組分支`claude/security-functionality-review-yftgf8`/cde57d5只補CI METIS下載pin，
已讀差異，待獨立凍門檻/驗收後整合；本單元不編譯或修改引擎。


GAME_INPUT #106 與 GAME_RUNTIME #108 已接真原生迴圈；#121以交付42e10f5雙平台庫驗
目前HUD/持久化/資料路徑。Windows core412/Forge109=509PASS/12平台SKIP，Linux520PASS/1平台SKIP。
判準與舊來源證據見 `GAME_RUNTIME.md`；目前新庫與jar資格见 `../evidence/NATIVE_CANDIDATE_RUNTIME/RESULTS.md`。
持久已知格/建造身份已完成，其後仍欠原子施工交易與獨立區域排程。全未支承原生只回
SOLVE_FAILED，typed機構展示、原Windows CM/N25、材質方向互動及倒塌動態仍未完成，沒有v1發布。

上游 #107（文件）已合入目前模組分支，對位引擎 #39 frame_v2；本輪沒有改引擎。
歷史v1.3來源 #37 `95a03e82` 與同契約42e10f5候選證據保留；最新v1.5資格見本節開頭。
混合世界 HUD/命令警示已接並用真 server 驗文字；客戶端視覺仍待 N25。
STATE_DELIVERY 已接空模型/停用與來源/順序/revision 通知；真 server 16 項、24 個 synthetic
玩家事件與 native→封包→clock 已驗，詳 `../evidence/STATE_DELIVERY/RESULTS.md`。
CLIENT_MATERIALS 已完成 CM-7 宣告尺寸/角色資訊資料路徑；真 jar 只到多人列表，
安全性視窗待使用者處理，尚未進世界。詳 `../evidence/CLIENT_MATERIALS/RESULTS.md`。
NATIVE_ONLY_RESULTS 已將舊 field 公式、codec、比值推導判定的建構子移到測試專用區；
正式 jar/class 常數池檢查、兩條可編譯故障臂、12 組封包雜湊與 Linux 原生 22 項通過。
詳 `../evidence/NATIVE_ONLY_RESULTS/RESULTS.md`。
下一步仍是真客戶端補送/旅行時序、獨立區域排程、材質與互動。

WORLD_REGISTRY 已完成已知格持久索引、有界容量/損壞拒絕與完整輸入守門。真 Linux 原生
49 格梁跨 chunk 卸載/重啟/載回後封包逐位相同；只有支承觀測缺失也會等待。
Minecraft FULL 可讀性轉移有分批監測，apply 前重查整個範圍。現階段缺一部分仍延後
整個維度；獨立排程與引擎 damage/lifecycle 未完成，#86/#17 保持開放。
證據與首敗見 `../evidence/WORLD_REGISTRY/RESULTS.md`。

CONSTRUCTION_IDENTITY 已接永久 namespace/單調 ID、產品/宣告軸分組、拆分/合併與完整重建
譜系；保存 pending edits、重啟與卸載不丟身份，OFF 也可更新。`/br object <pos>` 唯讀顯示
身份與原生 preview links，`/br section 0` 可讀原生零號元素。這不承擔 FE 抽取、斷裂或
剛體物理；引擎 events、重新安裝與客戶端 identity 封包仍待交付。詳
`../evidence/CONSTRUCTION_IDENTITY/RESULTS.md`；#17 的身份部分推進，未整題關閉。

再次對照 #12/#17：目前身份是對已觀測世界的 metadata 批次發布，**尚不是施工交易**。
#12 要求確認時材料帳、全部方塊、artifact/ownership 與單次 worldRevision 一起原子提交，
另需重送去重、衝突拒絕、任一步故障回滾及 inverse undo。這些還沒有正式遊戲路徑與整合門；
不能以身份保存/分組測試代替。施工邊界/接頭、延長既有 piece 的規則，也須在該交易入口
明確化；觀測式分組與原生預覽連結不能直接授權破壞、掉落、損傷或材料產出。

CONSTRUCTION_TRANSACTIONS 已先凍完整 CT-1..9 與 D-048：每次確認的新 piece 都用新 UUID，
不默默延長舊身分。第一步實作模組核心日誌、原子決策與復原協調器；尚未接入 Forge 生產入口。
已有35項核心與15項Forge玩家持久化測試；精確NBT/欄位、完整基線checkpoint、原子替換、
保留孤兒檔配額及故障路徑已驗。兩平台37個中斷/恢復JVM與3個新可編譯反例通過；
玩家存檔成本仍是Recorded，Windows小fixture p95尾延遲照登。詳 `CT_PLAYER_PARTICIPANT.md`。
尚無正式Forge施工呼叫者；檔案/JVM故障測試不等於遊戲庫存/區塊交易或undo/UI完成。
接合限制見 `CONSTRUCTION_TRANSACTION_ADAPTER.md`；CT-1..9完整門檻仍全部保持開放。

CT_JOURNAL_BOOTSTRAP 補上鎖內領域ID發現、損壞/遺失marker拒絕新身分及有界不可變交易key清單。
578登錄：Windows566PASS/12平台SKIP、Linux577PASS/1平台SKIP；兩平台37個中斷/復原JVM及
原生適用測試通過。最新開發jar73a491fba10e…只比前版改FileTransactionJournal.class，
雙v1.5庫/契約/資源不變。詳 `../evidence/CONSTRUCTION_TRANSACTIONS/JOURNAL_BOOTSTRAP/RESULTS.md`。
這仍不重播已提交world/player歷史；製造物件metadata後續進度如下，Forge施工入口仍開放。
前置#128已使公開利用率顯示API必須收原生旗標；37組實際jar讀數不變，反例被抓到，
6d1a0c2 CI四項通過/15原生步驟SKIP；#124須在實際Main整合後才關閉。

CT_MANUFACTURED_METADATA 已使明確piece計畫取得永久UUID、COMMITTED日誌唯一權威與精確格所有權。
切割/依賴編輯使舊piece不可逆失去整批undo資格，退役ID不復用；損壞history/即時核實失敗拒絕使用。
18項新測試及兩個可編譯反例通過，完整596登錄：Windows584PASS/12平台SKIP、Linux595PASS/1平台SKIP。
最新jar1315dce1e785…只新增14類別，原236類別、v1.5雙庫/契約/資源不變。
雙平台同種子864次成本操作內容一致；同步提交p95約13–46ms，僅Recorded，未接受FPS。
詳 `../evidence/CONSTRUCTION_TRANSACTIONS/MANUFACTURED_METADATA/RESULTS.md`；尚無Forge施工呼叫者。
metadata只提供整批undo資格，不代替原world/item影像、權限/依賴或退款校驗；CT-1..9仍全開放。

MODULE_PIPELINE_PROFILE 已將 metadata/gather/queue/BSI/解碼/封包/apply 分別量測，預設不收資料。
A49/F576/M832 三場景各 40 次實跑，數字只作 Recorded 基線；M832 原生呼叫占主要成本，
不能把背景 65.6 ms p95 說成 client frame time。尚未量 FPS、真玩家 socket、存檔或 registry 上界。
詳 `../evidence/MODULE_PIPELINE_PROFILE/RESULTS.md`；未以小場景改寫 v1 效能目標。

REGISTRY_SCALING 已完成原始基線與四版候選對照；`0736baa` 通過原定64條時間與16條
配置預算，600份 payload 一致。131K/ONE 快照 p95 714→6.0 ms、背景分組9522→283 ms，
增加的快照/冷編碼配置與前三版輸格都保留，詳 `../evidence/REGISTRY_SCALING/RESULTS.md`。
此結論只涵蓋登錄資料路徑，仍非 live tick/FPS 或 v1 資格。

REGISTRY_SAVE_PROFILE 已完成 Windows/NTFS 與 WSL/ext4 的真 SavedData adapter 量測：
共同種子、READY/PENDING64、各三個 JVM fork，共900次/720量測樣本，跨六fork資料一致。
131K READY 完整存檔 p95 Windows260 ms、Linux202 ms，僅 Recorded，仍是同步成本。
詳 `../evidence/REGISTRY_SAVE_PROFILE/RESULTS.md`；不能代替 live server tick、全世界存檔或 FPS。


SAVE_COMPRESSION 已完成固定SP對照，900次內容一致、36條時間/36條配置及所有檔案大小
預算全過。131K READY p95 Windows260→96.5 ms、Linux202→83.3 ms，檔案增大約18%。
仍同步完成 gzip/fsync/atomic replace 後才清 dirty，不把100ms級呼叫當作v1 tick資格。
詳 `../evidence/SAVE_COMPRESSION/RESULTS.md`；沒有引擎或遊戲時序改動。

較早 #116/#117 的主分支契約4977f57308e6與開發契約4b11cc738790已由模組Main #126
同步至5d4367f40de8；本分支整合其歷史並保留舊原生證據。Linux 真客戶端曾完成16張補充基準圖與45項守門，
cached bootstrap/真拒絕清色已實見；材質方向與長訊息布局仍有缺陷。
詳 `../evidence/CLIENT_RENDER_PROBE/RESULTS.md`；原 Windows 安裝版驗收仍待安全性視窗。

HUD_READABILITY 已完成16份原場景相同讀數对照與2張大字級圖，長拒絕換行、讀數底板、
原生警示優先及超量明示省略已實見；首次通知重疊保留並縮寬修正。只動客戶端布局與翻譯，
未動引擎/封包。詳 `../evidence/HUD_READABILITY/RESULTS.md`；材質方向、真互動與CM/N25仍待完成。

NATIVE_CANDIDATE_RUNTIME 已按先凍判準整合 #120 與 #119（含其10146b5文件增量）。
來源42e10f5與目前契約相同；目前jar15,203,560B、SHA5a93c66bfe5f…，完整Windows509PASS/12平台SKIP、
Linux520PASS/1平台SKIP。兩平台同jar48frame×3/快取/權限/雙JVM，來源10反例/4守門移除與bundle9反例通過。
新庫真Forge開發环境16項狀態/24合成玩家事件/45項client守門與16張畫面通過，bundled資源自動解包。
首個空世界前置與漏通知失敗照存；不替代installed-jar/Windows CM/N25/FPS，不替換正式引擎。
詳 `../evidence/NATIVE_CANDIDATE_RUNTIME/RESULTS.md`；#119先後兩個候選的歷史數字/hash各自保留。

INSTALLED_NATIVE_SERVER 已用普通Forge安裝的Linux專服驗目前jar，沒有dev sourceSet/probe。
control與最終各10項原生守門及重啟/原生快取恢復通過；修正出貨介紹的舊FrameCore/子程序隔離說法。
最終jar15,203,634B、SHA7bdfce5d1581…；比NCR只有mods.toml不同，204類別與兩原生庫逐位相同。
其完整程式測試明示沿用NCR，未冒充重跑；新安裝與封裝證據見 `../evidence/INSTALLED_NATIVE_SERVER/RESULTS.md`。
這只補Linux安裝專服，不取代Windows安裝客戶端、CM/N25、材質方向/互動、FPS與引擎動態依賴。

CT_CHUNK_PARTICIPANT 補上同一IOWorker整批保存、future排空、force與完整NBT讀回比較。
7項新測試含真region檔案重開；双平台完整Forge131PASS，core沿用4737e3c相同來源測試。
目前603登錄：Windows591PASS/12平台SKIP、Linux602PASS/1平台SKIP；4類別新增，原250類別/
v1.5庫及資源逐位不變。省略force與遺失capability的可編譯反例被抓到，詳
`../evidence/CONSTRUCTION_TRANSACTIONS/CHUNK_PARTICIPANT/RESULTS.md`。
下一步正式host仍須解決ChunkSerializer吞掉capability例外、custom save資料擷取及原版
NbtIo無上限讀取配置；canonical上限不等於解析前上限。尚無Forge施工呼叫者，CT-1..9不關閉。

CT_BOUNDED_CHUNK_READ 已在原IOWorker/region cache讀取解壓16MiB+1上限後才建NBT tags，
並拒絕pending影像、重複鍵/深度/不可能長度與trailing資料。雙平台Forge137PASS，13項
區塊測試通過；stream cap/vanilla parser兩個可編譯反例被抓到。最新jar4bf732d83a1e…
已在隔離安裝Forge的真Level上通過12項checkpoint/after/恢復/箱子原物品/損壞檔拒絕檢查，
harness不含AT或正式類別，測試確實使用mods/內jar。專服正常exit0，原檔/完整收據保留。
609登錄覆蓋：Windows597PASS/12平台SKIP、Linux608PASS/1平台SKIP；core來源不變沿用前次。
詳 `../evidence/CONSTRUCTION_TRANSACTIONS/BOUNDED_CHUNK_READ/RESULTS.md`。這解決上一段的
模組讀取配置邊界；下一步仍需完整live capture、吞錯capability/custom hooks、施工host、
普通放置/藍圖/undo與UI。CT-1..9和v1仍開放，沒有Java物理或原生引擎改動。
