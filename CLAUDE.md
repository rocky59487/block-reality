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

**力學不在這個 process 裡算，但引擎在這個 process 裡跑。** 這句話不矛盾，是 v0.4 的形狀：
本倉是 **Minecraft 側**（方塊、材料、工法狀態機、CAD 工具、渲染），力學引擎是
**jar 內的原生共享庫**（`libbsi_tectonic.so` / `bsi_tectonic.dll`），以 JNA 綁 `contract/bsi_capi.h`
的五個 C 函式**進程內載入**（D-044）。**jar 零可執行檔、零子行程。**

- **引擎** = tectonic2（`github.com/rocky59487/tectonic2`），原始碼靜態連結成上面那顆庫。
- **介面** = **BSI v1**（`contract/`），與 tectonic2 **逐位相同**、雜湊釘死（D-043）。
- **代價照登**：進程內載入**放棄了崩潰隔離**——原生崩潰會帶掉 JVM。四條比「隔離」弱的防線寫在 D-044，不繞過去。

### 現況（v0.4 進行中）

- **已落地**：`contract/`（BSI v1 + 共用 host，逐位鏡像 tectonic2）；Java 側 `mod/core/.../bsi/`（frame/header/codec）
  與 `.../engine/`（`InProcessEngine` + JNA 綁定）；N19–N24 判準已凍；CI 跑契約、打包、跨倉漂移。
- **2026-09-04 量到的整合現況**（Linux 箱，RECORDED）：契約逐位、pin 自洽；`InProcessEngineTest` **首次在 CI 之外**對本機建的 `libbsi_tectonic.so` 跑，
  `:core` 259 過 / 1 跳 / 0 敗；`sidecar/verify.py` 330 全過。當次引擎 `capabilities:[]`，members分支未執行。細節 `docs/GATES.md` 2026-09-04。
  引擎側的 v2 程序（融合 + perfect engine）在 tectonic2 `docs/V2_PROGRAM.md`；本倉對位 **D-046**：**等的是 tectonic2 #20，不等其拆 `Session`**；
  契約加法批次 #2（`include:"islands"`、`bsi.fracture.step`）綁 **v0.5 倒塌**，v0.4 的 N19–N24 一條不動。
- **2026-09-06 BSI_CORE**：配對分支引擎宣告core/members，Windows真CAPI :core 260總數/220過/40跳。
  members首次執行抓到測試root moment漏乘L，依契約原wL²/2修正；原記錄不改、見GATES.md。
  引擎 v1.3 已驗 Windows/Linux 自足原生庫；NATIVE 的消費者封裝與遊戲換裝 #89 未完成。
- **2026-09-07 BSI回收契約／codec**：facetBlocks、stations-only與f32布局已同步兩倉，hash59beed904d73…；
  Java facets/格索引/surfaces解碼與typed precision已接，binary區段範圍/有限值守門；
  271項=231PASS/40SKIP，真JNA五項全執行，七個Java變異具名咬合。首跑失敗見GATES.md。
- **2026-09-08 原生回收整合**：引擎已提供stations/shells/f32，真C6/C8/C12通過；
  新增InProcessRecoveryTest 3/3要求新能力，與原InProcessEngineTest 5/5使用真DLL全執行。
  Java274項=234PASS/40SKIP/0FAIL；殼幾何/格索引/上下面/f32直接由引擎提供。
  契約52檔hash b5ad59f1bfa9…同步，完整證據在tectonic2 MC65B_BSI_ADAPTER。
  殼顯示已遷移：BsiShellDisplay→ShellDisplayField→ShellMesh/HUD/renderer/channel6，
  客戶端只插值樣本，殼旗標獨立轉發；core283=271PASS/12SKIP、Forge57全過，真JNA9/9，八變異。
  ShellFieldSpec仍留在Sidecar相容入口一次取樣/舊診斷；梁BSI框架/面中心幾何已接通。
  53檔契約hash42a1b24c3c0b…；memberGeometry固定168B/f64，核心290=278PASS/12SKIP、Forge57PASS，
  真JNA10/10，三個Java幾何守門變異有具名FAIL；引擎564checks/五變異三箱與sanitizer詳見MC65B_MEMBER_GEOMETRY。
  梁顯示已接 BsiBeamDisplay→BeamDisplayField→ribbon/section/HUD/表面切分/channel7。
  不重算梁力學，完整站點/格集合/NA缺值/overloaded與控制站索引原樣傳送；舊field只在Sidecar相容入口/診斷保留。
  最新core305=293PASS/12SKIP、Forge64PASS，真JNA12/12、五身份變異具名咬合。
  stationIdentity opt-in沿原生共同核轉發雙側與主宰旗標；BsiBeamDisplay/StressStation.Identity/channel8保留f64身份，舊請求單側不改。
  契約54檔hash4977f57308e6，79checks/八變異三箱DET×3、sanitizer及兩箱48組舊回應逐位。
  首跑ground-only EMPTY_WORLD、host stub零筆證據降級均留GATES與MC65B_BSI_STATION_IDENTITY。
  下一單元先凍MC64_FORWARD全域/屈曲旗標與單一AnalysisResult入口，再凍display budget、接NATIVE/GAME_SWAP。
  #89 與消費者封裝仍未完成，版本對位引擎 1.3.0、模組仍 0.4.0-dev。
  正式引擎來源與原生資產見 tectonic2 v1.3 及套件 provenance.json；本倉 pin 鎖定同一引擎來源提交。
- **還沒接**：**遊戲流程仍走 `SidecarClient`**（protocol 2 / FrameCore），`InProcessEngine` 尚未接進遊戲迴圈（#89）。
  「檔案在」不算「有」——見下面的三條鐵則第 2 條。
- **不會做**：FrameCore 的 BSI 對數臂（D-045）。連帶 N22 差異帳改形為「單臂語料 + 封閉解」並**降一級**，
  代價寫在 `docs/GATES.md` 2026-09-03e，不用「改形」兩字帶過。

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
FEA 決定失效集合；`FallingBlockEntity`、粒子、碎塊只負責演出。演出層永遠不回頭影響判定。
（取自前身 `教育程式_需求與任務規劃_v3` 對「遊戲物理 vs 工程力學」的裁決。）

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

### 2026-09-08 MC64_FORWARD 現況

單一BsiAnalysisResult入口已接InProcessEngine.analyze；全域旗標取完整blocks bits，AnalysisResult與channel9獨立保存boolean/f64。
Forge與core共用Gradle契約資源規則；真原生→Forge封包已驗證，缺resource的首敗保留。
core316=304PASS/12SKIP、Forge71PASS，真JNA與十個具名變異通過；單處revision變異未逃逸照登。
遊戲仍SidecarClient，真HUD/換裝未驗收；BSI混合屈曲state/screen明示拒絕。下一單元DISPLAY_DELIVERY，
原DISPLAY_BAND仍依PE3/MC65B。引擎v1.3標籤未變、模組0.4.0-dev，沒有新效能宣稱。

### 2026-09-08 DISPLAY_DELIVERY 現況

channel10已整合完整元素交付：單包256KiB、總16384格/2048站、控制元素先保留，
每更新至多64梁/512殼候選。元素payload只編碼一次，廣播共用；收端配置前守累積預算。
控制元素本身超額時保留全域summary與原kind/id，明示未顯示，不裁它的格/站點。
空展示不冒充未分析或全機構；HUD接了文字但未實跑Minecraft視窗。
core316=304PASS/12SKIP、Forge80PASS，合計396登錄；十變異具名咬合、四語料DET3。
small配置約55.9KB→71.8KB的代價照登；AMD耗時僅RECORDED_ONLY，沒有i9/FPS/速度勝出宣稱。
原v1.3與契約54檔不變；下一單元NATIVE消費者封裝/解包競爭，再按依賴接GAME_SWAP。
原DISPLAY_BAND仍依PE3/MC65B，v2/v3/v4與歷史失敗保持原線。

### 2026-09-09 NATIVE消費者封裝

正式v1.3 Windows/Linux原庫已進同一0.4.0-dev jar，來源鏈/解包前契約/版本隔離/兩JVM競爭
與真BSI 24frames×3各平台逐位驗證。core327=315PASS/12SKIP、Forge80PASS，
407登錄/395PASS/12SKIP；實際InProcessEngineTest5+InProcessRecoveryTest8=13項真JNA，
舊Sidecar28項仍執行。十Java變異及來源/打包反例有完整證據；使用docs/NATIVE_PACKAGING.md。
遊戲仍走SidecarClient，未做GAME_SWAP/N25 HUD/FPS；勿刪最後相容入口。下一段為引擎MC66a。


### 2026-09-09 MC66A BSI eigen

BsiBuckling完整不可變每島結果與五態世界摘要已接BsiAnalysisResult；世界拒絕仍保留局部critical。
headers/solve/analyze新增EigenBuckling budget選項，舊簽章保留。core334=322PASS/12SKIP、Forge80PASS；
原13+新2真JNA全部執行，八Java故障有具名反例。contract55檔/hash4b11cc738790與引擎同步。
目前開發庫已驗，正式v1.3 jar未更新；遊戲仍Sidecar，HUD hasFactor分支尚需局部critical明示。
引擎frame_v2 eigen已local_verified：504項/十故障臂三箱DET3、60份舊frame逐位，見docs/MC66A_FRAME_V2.md。
下一單元更新兩平台native包與來源鏈/真JNA，再接混合世界局部Critical HUD/GAME_SWAP；BUCK_MEMBERS保持active。
