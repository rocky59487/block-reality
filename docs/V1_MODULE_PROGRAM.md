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

GAME_INPUT 已在 #106，GAME_RUNTIME 已接 Forge 採集與真正原生迴圈；Linux server 混合
梁柱板/機構與地面變更/重設已驗，Windows core412/Forge109=492PASS/29SKIP。
判準及證據見 `GAME_RUNTIME.md`、`../evidence/GAME_RUNTIME/RESULTS.md`。
全未支承原生只回 SOLVE_FAILED，typed 機構展示未完成；客戶端、最新雙平台 native jar、
持久世界及倒塌動態仍待完成。沒有新遊戲版本發布。

上游 #107（文件）已合入目前模組分支，對位引擎 #39 frame_v2；本輪沒有改引擎。
正式引擎仍 v1.3，本次實跑來源仍 #37 `95a03e82` / contract `4b11cc738790…`。
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
