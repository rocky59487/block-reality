# CLAUDE.md

Block Reality 開發指引。**寫的是現況**，不是歷史。

> **本檔從空白撰寫，不是前身 `PFSF-CORE/CLAUDE.md` 的改寫**（D-009）。
> 前身的不變式清單建立在純量勢場模型上，該模型已判定不成立（D-001）。
> 若發現本檔某條與前身雷同，那是巧合或錯誤，不是繼承。
>
> **2026-09-03 整理**：先前的 dated 追記層已收進正文。歷史留在它們該在的地方——
> **決策** `docs/DECISIONS.md`（已加索引，含「被取代／作廢」一欄）、
> **判準與線的移動** `docs/GATES.md`、**兩倉對位** `docs/ALIGNMENT_LEDGER.md`、
> **換裝總綱** `docs/SWAP_PROGRAM.md`。本檔不再累積追記層；它過期就重寫。

## 專案

Minecraft Forge 的結構工程沙盒。真實工法 + 真實有限元素分析。

**原生引擎在同一 JVM process 內計算力學；Java 負責模組整合。** v0.4 的形狀：
本倉是 **Minecraft 側**（方塊、材料、工法狀態機、CAD 工具、渲染），力學引擎是
**jar 內的原生共享庫**（`libbsi_tectonic.so` / `bsi_tectonic.dll`），以 JNA 綁 `contract/bsi_capi.h`
的五個 C 函式**進程內載入**（D-044）。**jar 零可執行檔、零子行程。**

- **引擎** = tectonic2（`github.com/rocky59487/tectonic2`），原始碼靜態連結成上面那顆庫。
- **介面** = **BSI v1**（`contract/`），與 tectonic2 **逐位相同**、雜湊釘死（D-043）。
- **代價照登**：進程內載入**放棄了崩潰隔離**——原生崩潰會帶掉 JVM。四條比「隔離」弱的防線寫在 D-044，不繞過去。

### 現況（0.4.0-dev，尚未發布）

2026-09-10：使用者指定引擎另有人負責，本工作只做模組（D-047、`docs/V1_MODULE_PROGRAM.md`）。
引擎最新正式交付為 v1.5（runtime1.5.0），來源 `42ba7eb9`、契約 `5d4367f40de8…`。
模組 Main #126 已整合；本分支已封裝並驗證該 SDK，沒有修改、建置或發布引擎。

- NATIVE_V15_CONSUMER 的來源/完整測試/封裝/JNA/真安裝專服門檻1–5已通過。
  同一顆15,286,415B jar、SHA9d8cb6795e9c…带 v1.5 双平台庫，236類別與46資源對CT前版逐位不變。
  Windows561PASS/12平台SKIP、Linux572PASS/1平台SKIP；573登錄，原生適用測試全部執行。
  兩平台各96frame×3直連與3個jar/JNA工作階段一致，涵蓋C14旋轉/鏡像/厚殼indicative原生回覆。
  真安裝Linux Forge10項遊戲守門及重啟讀數/快取一致通過。NOTICE增量封裝缺陷已修，首敗照存。
  詳 `evidence/NATIVE_V15_CONSUMER/RESULTS.md`；本版真client127模型/14互動/45守門及22圖通過，厚殼警示遊戲輸入仍未觀測；CI f7f9533四項通過/15原生步驟SKIP。
  v1.5提供線性梁殼共同挫屈，未交付非線性倒塌/壓碎/接觸/剛體運動；不以Java補做。
  以下NCR/INS/MG是保留的較早候選證據，不代表最新引擎版本。

- NATIVE_CANDIDATE_RUNTIME 已整合 #119 及其最新交付文件，保留 #120 HUD。
  同一顆15,203,560B jar帶雙平台42e10f5庫，兩平台48frame×3、解包/快取/權限/雙JVM通過；
  Windows509 PASS/12平台SKIP，Linux520 PASS/1平台SKIP，原生相關全部執行。
  真Forge開發環境由bundled資源解包，16項server狀態、24個合成玩家事件、45項client守門/16圖通過。
  首次空世界前置錯誤與漏通知失敗保留；詳 `evidence/NATIVE_CANDIDATE_RUNTIME/RESULTS.md`。
  這是候選與dev遊戲驗收，未替換正式資產，也不等於installed-jar/Windows CM/N25/FPS合格。
- INSTALLED_NATIVE_SERVER 已把同一候選裝進普通 Linux Forge 47.4.13，control/final 各10項
  server守門及重啟讀數/原生快取恢復通過，類別實際來自mods/中的jar。最終只修mods.toml舊介紹，
  204類別與兩庫bytes不變；jar15,203,634B、SHA7bdfce5d1581…，安裝文字同步改正。
  詳 `evidence/INSTALLED_NATIVE_SERVER/RESULTS.md`；Windows/真安裝客戶端、材質方向與v1仍待驗。
- MATERIAL_GEOMETRY 已使4種矩形產品依宣告尺寸呈現，模型/目標/碰撞框共用bounds，
  X/Y/Z端面與未宣告警示可見，原生掃描保留寬深與站點不連續。Java不增加力學/運動。
  真Linux客戶端127項模型、14項原版入口互動、原16圖/45項守門與6張補充圖通過。
  最終528登錄：Windows516PASS/12平台SKIP、Linux527PASS/1平台SKIP；3個可編譯反例被抓到。
  jar15,216,948B/SHA8e587a715ebd…帶原42e10f5雙庫；207class/2負向臂與bundle9反例通過。
  Windows首個直連/jar逐位比較因兩程序BLAS設定不同而失敗，設定一致後48frame×3通過；
  首次只改Python環境仍失敗，後以啟動環境統一測試driver。原始回覆/失敗照存。
  詳 evidence/MATERIAL_GEOMETRY；panel仍是材料格，Windows CM/N25/FPS/動態仍未接受。
- `GAME_INPUT` 已接 engine-assigned vocabulary ID、SI 目錄與不可變 GameWorldSnapshot。
- `GAME_RUNTIME` 已將 Forge 採集→worker→apply 換為真正的 BSI/JNA 原生 session。
  玩家宣告 axis；觀測六面 sturdy 接觸為 ground；舊方塊未宣告要明示拒絕。
  off 先於選庫/解包，無效明確路徑拒絕，關世界/reset/逾時使舊结果失效。
  native 工作若不返回，Java 不能強殺；cleanup 僅在 worker/daemon，D-044 崩潰代價仍成立。
- 生產 SidecarClient/SidecarProcess/SidecarConfig/ShmRegion 已退到 test；NoSubprocess ALLOWED={}。
  預設 jar 無 exe、無 process launcher；Gradle 已拒絕 executable 封裝。
- CONSTRUCTION_TRANSACTIONS 已先凍 CT-1..9/D-048，新增核心日誌與復原協調器，尚未接 Forge 施工入口。
  核心現有30項測試，新增15項Forge玩家檔/NBT/精確欄位持久化測試；兩平台各37個真JVM
  中斷/復原與跨程序鎖通過。先checkpoint完整玩家基線再PREPARED；3個新可編譯反例被抓到。
  這些玩家adapter仍沒有正式Forge施工呼叫者；不是已完成遊戲庫存/區塊原子交易。
  日誌驗證規則快取使4096格配置約9MB→3.36MB，144份/平台檔案逐位相同；時間僅Recorded，
  Windows p95增加、Linux降低，不能稱FPS資格。完整材料/區塊/身分/undo/UI仍待接合，#12/#17不關閉。
  詳 `docs/CT_PLAYER_PARTICIPANT.md`、`evidence/CONSTRUCTION_TRANSACTIONS/PLAYER_PARTICIPANT/RESULTS.md`。
  #125仍是draft；9976fc0的4項CI通過、15項native step跳過，不能代替本地原生實跑。
- Windows core449/Forge124：573登錄、561PASS、12平台SKIP；Linux572PASS、1平台SKIP。
  20項 native 相關全部執行。新庫Linux真 server 梁柱板/混合機構/支承恢復/reset 已實跑；
  完整結果 `evidence/GAME_RUNTIME/RESULTS.md`，首敗保留、三故障臂具名咬合。
- 全部失去支承時原生只回 SOLVE_FAILED；Java 保留拒絕，不能從 message 捏造 MECHANISM。
  高性能、全機構 typed 展示、真客戶端 N25 尚未驗收。
- UI_VERDICTS 已使共用 HUD/命令讀出保留 local-critical，世界未完整評估時不捏造因子；
  命令列結果 revision 與 stale/current、修正 solved 數量，殼警示讀原生旗標。
  真 server 長柱/混合世界已驗到警示與拒絕並存及移除後消失；`evidence/UI_VERDICTS/RESULTS.md`。
  真 jar 已進 1.20.1/47.4.10 多人列表；尚未進世界，不算 N25 材質/布局驗收。
- STATE_DELIVERY 已接 channel 11：來源/順序/revision 守門、登入/重生/跨維度快照補送、
  EMPTY/OFF/拒絕分流與舊展示清除。真 server 16 項、24 個 synthetic 玩家事件及 Linux
  native→封包→clock 已跑；`evidence/STATE_DELIVERY/RESULTS.md`。真 socket/client 時序仍待 N25。
- CLIENT_MATERIALS CM-7 已從同一 SI 目錄提供角色/尺寸物品資訊，9 項資料/格式測試通過。
  世界內基準畫面與材質模型未完成；Windows 安全性視窗待使用者處理。
- CLIENT_RENDER_PROBE 已取得隔離 Linux 真客戶端16張基準圖、45項讀數/尺寸守門通過。
  登入即收到既有原生 revision44；未宣告目錄真拒絕並清除舊色面。原HUD長訊息截斷、
  文字對比及方向模型缺口已實見；首兩次探針失敗保留。這只是補充，CM/N25仍待驗。
- HUD_READABILITY 已用原16場景加2張大字級圖驗換行、底板、原生警示與明示省略。
  45項原始守門及16份完整讀數對照通過；首版與原生通知重疊已縮寬修正，首敗仍保留。
  Windows Forge108 PASS/1 SKIP、12封包golden與jar守門通過；詳 evidence/HUD_READABILITY。
- NATIVE_ONLY_RESULTS 已把舊 beam/shell 公式、JSON/shm codec 與數值推導 verdict 的相容 API
  移到 test/fixtures。正式快照只接受原生樣本/旗標；12 組封包 bytes 不變，Linux recovery/packet
  22 項無跳過通過。jar/class 常數池檢查與兩條可編譯故障臂通過，詳 `evidence/NATIVE_ONLY_RESULTS/RESULTS.md`。
- WORLD_REGISTRY 已持久保存已知結構格，容量/損壞拒絕、原子寫入；缺少結構或支承觀測
  時延後整個維度請求。真 server 卸載/重啟/載回的 49 格梁結果封包逐位恢復；首跑抓到
  FULL 可讀性早於 Unload 事件消失，已補分批監測與 apply 完整性重查。
  這是已知格索引與完整輸入守門；詳 `evidence/WORLD_REGISTRY/RESULTS.md`。
- CONSTRUCTION_IDENTITY 已在同一原子存檔保存宣告、永久物件 ID、待處理拆除與拆分/合併譜系。
  worker 依 metadata epoch 發布，OFF 仍可分組；`/br object` 以原生格清單提供精確 revision 的預覽連結。
  真 server 放置/拆分/重建/卸載/重啟已驗；詳 `evidence/CONSTRUCTION_IDENTITY/RESULTS.md`。
  分組只管理遊戲物件，不抽取物理元素；損傷、獨立區域排程與客戶端 hover identity 仍未完成。
- MODULE_PIPELINE_PROFILE 已接預設 OFF、有界樣本的 `/br profile start|stop|show`。
  真 Linux server 的 A49/F576/M832 各 10 次暖身、40 次量測完成，14 項功能檢查通過。
  M832 背景分析 p95 65.6 ms、apply 1.6 ms；含原生工作，不是 FPS 或 v1 高性能資格。
  詳 `evidence/MODULE_PIPELINE_PROFILE/RESULTS.md`；量測啟停不改 world/result revision。
- 尚未完成：模組正式發布、Forge施工交易與undo/UI、獨立區域排程 #86、
  原Windows CM/N25與厚殼警示遊戲輸入、FPS/soak、引擎 lifecycle/剛體姿態消費。
  #89 仍開放；既有公式已退出出貨來源，v1 全部基礎能力仍未完成。
- REGISTRY_SCALING 的原始基線與四版候選都已保存；最終 `0736baa` 通過原定相對性能與
  配置預算，600 份 bytes 一致。131K/ONE capture p95 714→6.0 ms、reconcile 9522→283 ms；
  快照配置增加 42.9%，冷編碼多一份獨立陣列，原始三版性能輸格不覆寫。
  Windows 490 PASS/29 SKIP、Linux 指定45項無跳過；詳 `evidence/REGISTRY_SCALING/RESULTS.md`。
  這不代表 FPS 或 v1 高性能；SavedData adapter 雙平台900次/720樣本已完成且資料一致。
  131K READY 同步存檔 p95 Windows260 ms、Linux202 ms，時間僅 Recorded，仍須改善。
- SAVE_COMPRESSION 已保留同步 fsync/原子替換，改用 JDK 快速 gzip 與32KiB緩衝。
  原種子/900次對照全一致，131K READY 存檔 p95 Windows260→96.5 ms、Linux202→83.3 ms；
  檔案增大約18%，Windows完整Forge108 PASS/1 SKIP、Linux109 PASS/0 SKIP。
  原相對門檻全過，仍有同步阻塞成本；詳 `evidence/SAVE_COMPRESSION/RESULTS.md`。
- 上游模組 #107 的文件已整合，對位引擎 #39 frame_v2；來源見 `docs/MC66A_FRAME_V2.md`。
  不在本工作改引擎，也不把缺 TECTONIC2_TOKEN 而跳過的 CI native steps 說成已驗。

上一單元證據：`evidence/GAME_INPUT/RESULTS.md`、`evidence/BUCKLING_RETENTION/RESULTS.md`。
已接 display budget/channel11 envelope、直接樣本顯示與 per-island buckling 保存；歷史裁決與輸格保留在
`docs/GATES.md`、`docs/DECISIONS.md`、各單元 evidence，而不再堆進本現況檔。

## 力學模型（必讀，決定了所有其他事）

離散化是**桿件系統**，不是體素連續體：

- 每節點 **6 DOF**：`Ux Uy Uz Rx Ry Rz`
- 樑柱：Timoshenko 預設（`eulerBernoulli` 旗標只供 parity 對數）
- 板殼：**MITC4** 平面 facet（膜 + 板彎 + assumed covariant 剪切 + drilling penalty）
- 求解：稀疏直接法（supernodal Cholesky / LDLᵀ）。**沒有 multigrid，沒有迭代主解法器**

## 不變式

| # | 不變式 | 違反後果 |
|---|--------|---------|
| 1 | **構件是共線 run，不是單一方塊。** 一個 1m³ 方塊的細長比 L/h = 1，樑理論不成立 | 平截面假設失效，內力全錯 |
| 2 | **斷面與方塊尺寸解耦——適用於 frame 材料。** 一格「鋼骨」承載的是一個真實斷面（出貨目錄是實心矩形 `steel_rect_200x400` 之類），不是 1m×1m 實心。monolith 材料（混凝土/磚）是明確例外：一格就是 1 m³ 材料本身（D-030） | 巨柱效應，D/C 恆為 0.01，什麼都壓不垮 |
| 3 | **結構角色由玩家用材料宣告，不由程式從方塊堆反推** | 形狀語意辨識是未解問題；反推會產生無法解釋的模型 |
| 4 | **連通性分析只能當前篩，不能當權威。** 權威判定是因子代數（pivot ratio） | 連通 ≠ 穩定。機構會被判為安全 |
| 5 | **兩軌精度分離。** 顯示軌可 stale（rel ≤ 1e-5）；承諾軌不可（rel ≤ 1e-9） | 玩家看到的和實際判定的不一致 |
| 6 | **承諾軌的消費者：D/C 判定、崩塌觸發、netcode。** 這些永不吃顯示軌的值 | 崩塌不決定性，多人不同步 |
| 7 | **引擎邊界不洩漏元素詞彙。** Java 說方塊/材料/delta，不說節點/構件/斷面 | 換引擎要動 Minecraft 側 |
| 8 | client/server 分離：`client/` 下類別必須 `@OnlyIn(Dist.CLIENT)` | 伺服器載入 client 類別 → crash |
| 9 | **判定值由引擎定案，Java 不重算。** `dc > 1` 的比較在引擎的 double 上做一次，Java 讀旗標（N19） | 兩邊各算各的，邊界案例不一致 |

## 反不變式（前身有、本專案明確不採用）

這些是 `PFSF-CORE/CLAUDE.md` 的「不變式」條目。它們在純量勢場模型下成立，在本專案**全部作廢**。
列在這裡是為了讓人認得出來，避免從舊碼或舊文件無意間帶回。

- ❌ **σ_max 正規化**（`rcomp`/`rtens`/`source`/`conductivity` 同除、`maxPhi` 不除）
- ❌ **26 連通一致性**（stencil `EDGE_P=0.5`、`CORNER_P=1/6`）—— 26 連通在本專案只是拓撲前篩的鄰域定義，沒有物理意義
- ❌ **`hField` 寫入權**、相場損傷演化
- ❌ **每方塊一個純量應力**。FEA 的輸出是 per-member 六個內力分量 + D/C 模式，per-shell 是上下層各 5 點的應力張量
- ❌ **flux 比較 `rcomp`/`rtens` 的失效判準**
- ❌ **island = 26 連通體素集合**。本專案的分析單位是「一個結構模型（一個 K）」

## 兩條原則

**算的歸算，演的歸演。**
引擎交付權威事件與姿態後，Java 消費、同步並繪製它們。Java 不從D/C自行觸發破壞，
不以 FallingBlockEntity 或手寫重力/碰撞補出引擎尚未交付的動態；視覺插值不回頭影響判定。

**玩法即離散化的合法性條件。**
玩家用材料宣告結構角色，因此「照工序蓋」不只是遊戲規則，同時保證了模型可解。
這是不變式 3 的正面說法——見 `docs/MEMBER_SEMANTICS.md` §2。

## 慣例

- Java 17、Forge 1.20.1、Official Mappings、UTF-8
- **一格 = 1 公尺**。前身文件從未明確宣告這條，卻是所有換算的前提
- 物理量用真實工程單位：強度 MPa、楊氏模量 GPa、密度 kg·m⁻³。**wire 上一律 SI**，mm/MPa 只在顯示層
- 公開 API 標註 `@Nonnull` / `@Nullable`
- 測試 JUnit 5

## 紀律

判準先凍。見 `docs/GATES.md`。

三條鐵則：

1. **判準在實作之前 commit。** 事後移線要在 `docs/GATES.md` 登記，並且下游結論至少降一級
2. **沒有 gate 執行過的能力，不得寫進能力清單。** 「檔案在」不算「有」；**SKIP 不算綠**
3. **輸格照登。** 量到比對照組差，寫進文件，不換臂、不換 fixture、不事後重詮釋

第三條是從 tectonic 的 `GATE_LINE_REGISTRY.md` 借來的，那份文件的建檔動機值得一讀：
單條事後重詮釋都有可辯理由，聚合起來是 gate 失去牙齒的簽名。

**2026-09-03 的活例子**：契約加法批次 #1 在 `C4-cantilever-selfweight` 加了一條
`members.section >= 0` 的斷言，**首跑就抓到引擎回的是宣告值而不是解析後的斷面 id**。
那個缺陷在對位帳上躺了一天，因為沒有腿在查。**一個沒有腿的已知缺陷，
與一個沒人知道的缺陷，在下一次有人依賴它時是同一回事。**

## 決策

所有架構決策記在 `docs/DECISIONS.md`（**有索引**，含「被取代／作廢」一欄——
D-002、D-013、D-021、D-022、D-025、D-027、D-034(1) 都還在檔案裡，讀舊碼時會撞到）。
每條附**理由**與**否證條件**。沒有否證條件的決策不是決策，是偏好。

## 契約

`contract/` 是 BSI v1 引擎介面契約，與 tectonic2 的 `contract/` **逐位相同**（`contract/CONTRACT_SHA256`）。

改介面 = 改 `contract/` + `python3 contract/check_contract.py --write` + **兩倉各自 commit 同一份**
+ 更新 `.github/tectonic2-contract-ref` 的**兩行**（commit 與 sha256）。
Java 的 `BsiFrame`/`BsiHeaders`/`BsiResponse` 只能實作契約，不得自創欄位；要新欄位先改契約。

**跨倉漂移兩個方向都有腿（N23-b）**：

- 本倉 CI 比「自己的 pin ↔ 自己的目錄」與「pin ↔ ref 記的雜湊」（**不需 token**，永遠會跑）；
- **tectonic2 的 CI 讀本倉 `Main` 的 `CONTRACT_SHA256`**（本倉公開，`raw.githubusercontent.com` 一次 fetch，**不需 token**）；
- 本倉有 `TECTONIC2_TOKEN` 時另外比 tectonic2 `main`（**要 token**，沒跑時列進 job 末的「這次沒量到什麼」）。

**嚴重度依「不符代表什麼」定**：兩個預設分支之間不符 = **真漂移 → 紅**；
PR 上不符 = 變更**在途中** → 警告（否則每次合法的契約變更都從紅開始，然後所有人學會忽略它）。
**fetch 失敗永不綠。** 最後一道是執行期握手：`bsi.hello` 的 `contractSha256` 不符 → `BSI_VERSION` → 引擎停用並指名兩個雜湊。

## 2026-09-11 PGN候選

PGN已接production GameWorldSnapshot的physical自重，舊public overload仍analysis；JNA與兩故障已驗。
6916511靜態雙庫候選已換入同一jar，雙平台162frame DET3／雙JVM／production JNA235與native Java20通過。
正式release未替換，不能發佈新契約配舊庫。詳docs/PHYSICAL_GRAVITY_DELIVERY.md；#94與v2仍開放。
