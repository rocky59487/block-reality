# W3 — corot arc 延續、預覽隔離、歷史提交與資格顯示：判準凍結

狀態：凍結（先於實作 commit）。本檔把 `docs/design/V2_FORGE_INTEGRATION.md §7` 的要求
落成可判定的 wire/驗證/交易/顯示規則與 oracle/反例清單；實作與測試不得放寬本檔，
發現與 native 契約衝突時先回本檔修正並留下修訂記錄。

## 0. 邊界

- 力學全在 native；Java 只做編碼、嚴格解碼、身分綁定、儲存與顯示。不在 Java 重算
  任何位移/內力/能量。
- W2 已交付 `finite/*`（FiniteSession/FiniteLane/FiniteStore/FiniteRegistry/
  FiniteSource/FiniteCodec/FiniteResult）與 `corot.solve`。W3 不重寫這些基礎，
  只在其上加 arc、preview、history commit 與資格模型。
- 不碰 fracture/motion（W4–W6）。不改既有 BUILD/EDIT/UNDO 交易語意。

## 1. `bsi.corot.arc.advance` wire（ABI9，`bsi_corot_arc.h`）

### 1.1 請求

- method `bsi.corot.arc.advance`；payload **必須為空**（host `fractureBody(..., emptyPayload=true)`）。
- body 逐欄位對應 `corot.arc.advance.body`（schema 行 1154）：
  `expected{domain,revision}`、`initialAnalysis{context,sequence}`、
  `radius/minRadius/lengthScale/loadScale`（皆 >0 且有限）、
  `relativeTolerance/forceTolerance/momentTolerance/linearTolerance/pathTolerance`（≥0 有限）、
  `maxIterations/maxAttempts/maxBacktracks/linearIterations/restart`（整數 1..1_000_000）、
  `initialDirection ∈ {-1,+1}`。`additionalProperties:false` — 不寫 schema 之外的鍵。

### 1.2 回覆（`corot.arc.advance.response`）

- envelope：`bsi==1`、`kind=="response"`、`id` 逐字相同、`method=="bsi.corot.arc.advance"`、
  `revision` 為精確整數且等於請求值、`status=="advanced"`。
- `basis{domain,revision}` 與請求 expected 完全一致；`artifactNamespace` 與 domain 綁定一致；
  `generation` 為 `^[0-9a-f]{16}$`；`token{context,sequence}` 為有效 token（context≠0、
  sequence 為 16 hex、非零），且必須是**新** token（≠ initialAnalysis）。
- `fullyConverged` 必須為 JSON 布林 **且為 true**：native 端在回寫前檢查
  `view.fullyConverged>1`，非全收斂不會產生 advanced 回覆；Java 端看到
  `fullyConverged==false` 一律視為協定違規拒絕。
- JSON 純量例外（binary section 之外）：`loadFactor`（有限數）、`radius`（>0 且
  ∈ (0, request.radius]，native 以 `radius<=0||radius>request.radius||radius<minRadius` 拒絕；
  Java 端驗 `radius>0 && radius<=request.radius`，`minRadius` 下限由 native 仲裁）、
  `constraintResidual`（有限且 ∈ [0, 1e-10]，schema 上限）。
- `sections`：canonical 順序、不重不漏（§2）。

### 1.3 前置條件（Java 端，發送前拒絕）

- session 必須為 RESTORED 且有 live token（來自 solve 或 checkpoint import）。
- live token 的分析必須 `fullyConverged`——arc 只能從全收斂的 native token 出發。
- `initialAnalysis` 只能是最後一次 solve/import/arc 回來的那個 token；
  任何舊 token（被新回覆取代的）視為 stale，拒絕。
- 本次宣告世界的 `prescribed` 中只要有任一 `value[i] != 0`（非零預定位移路徑），
  arc 一律拒絕；該路徑由 solve 負責，不借 arc 執行。
- direction/radius/budget 超界 → 本地拒絕，不送出。

### 1.4 回覆驗證（接收端）

§1.2 全部 + §2 section 目錄驗證 + §3 逐記錄語義驗證。任何一項失敗 → 拒絕並使
session fail-closed（與 solve 同一紀律）。

## 2. Section 目錄（solve / import / arc 共用 canonical 表）

回覆 `sections` 必須恰好是下列順序（ABI10 shell 材料尾部僅當世界宣告 shell 材料時存在）：

```
corotOptions, corotPhysical, corotIslands, corotText, corotNodes, corotMembers,
corotShells, corotJoints, corotSources, corotSourceIndices, corotArtifacts,
corotArtifactMembers, corotArtifactShells, corotProfiles, corotFibers,
corotStations, corotPoints
[corotShellProfiles, corotShellLayers, corotTensorPoints]   // 僅 shell 材料世界
```

- 每項 `name/offset/bytes/count`，`offset` 嚴格接續前一項（contiguous），
  最後一項 `offset+bytes == payload.length`；`bytes == count*stride`（`corotText`
  例外：stride=1、`count==bytes`），`count*stride` 不得溢位。
- stride 表（schema `x-records` 凍結值）：options 176、physical 80、islands 96、
  text 1、nodes 208、members 472、shells 1184、joints 208、sources 56、
  sourceIndices 4、artifacts 24、artifactMembers 4、artifactShells 4、
  profiles 88、fibers 104、stations 40、points 120、shellProfiles 88、
  shellLayers 16、tensorPoints 472。
- 多出未知 section 名、順序錯、重複名 → 拒絕。

## 3. 逐記錄語義驗證（移植 host `bsi_corot_validate.hpp`）

- **corotOptions**：count==1，可解碼，其 `expected.{domain,revision}` 與回覆 basis 一致。
- **corotPhysical**：count==7（physical + weightGroups[6]）；每筆 mass≥0 有限、
  center[3] 有限、inertia 前三分量≥0 且全有限。
- **corotIslands**：`status ≤ 4`（0 CONVERGED/1 MECHANISM/2 NOT_CONVERGED/3 UNSUPPORTED/
  4 INACTIVE；≥5 為未知碼，**拒絕**，不得猜義）、`flags ≤ 7`、
  forceResidual/momentResidual/storedEnergy/dissipatedEnergy 有限≥0、historyLoadFactor
  有限、historyError ≥0 或 +inf（NaN/負 拒絕；收斂島要求有限）、reason 範圍落在
  corotText 內。arc 回覆要求**所有島 status ∈ {0,4}**（與 fullyConverged 一致）。
- **corotNodes**：island ∈ [0,nIslands)；`available == (islands[island].status==0 ? 1 : 0)`；
  fixed[6] ≤1；reserved[2]==0；reference/position/reaction/applied 有限；available 時
  orientation 為單位四元數（|q|²=1±1e-8），否則 position/orientation/reaction/applied 全 0。
- **corotMembers**：active∈{0,1}；active→island∈[0,nIslands)，inactive→island==-1；
  nodes[2]∈[0,nNodes)；available≤7；`available&1 == (active && island.status==0)`；
  sourceFirst/sourceCount 為全域 cursor（與 shells 串接後恰用完 corotSourceIndices）；
  sourceCount==0 → material==-1&&section==-1；否則 material∈vocab、section∈vocab、
  且等於首個 source block 的 mat/（sect<0→material 預設 section）；
  profile==-1 → `available&6==0`；profile≥0 → ∈[0,nProfiles) 且對應 profile.member==本 index
  且其 available==member.available&7；length≥0 有限（available&1 時 >0）、storedEnergy≥0、
  frame 與三組 [12] 力/位移欄位全有限、available&1 時 frame 為單位四元數。
- **corotShells**：同上規則（nodes[4]、material 無 section、thickness/area>0 於
  available&1、sections[40] 有限）。
- **corotJoints**：kind≤4、available≤3、index≥0、node∈[0,nNodes)；kind==1 時
  index∈[0,nMembers)&&end∈{0,1}；座標/力全有限；available 時 kind>1 要單位四元數。
- **corotSources**（56B）：block 40B + artifact u64 + weightDisposition u32 + reserved u32。
  key (x,y,z) 不重複；若座標在宣告世界中 → 40B block 記錄逐位相同、mat∈vocab、
  artifact == owner（無 owner → 0）、weightDisposition ≠ RETIRED(5)；不在世界中 →
  disposition==RETIRED && artifact==0。**世界每個 block 都必須恰好出現一次**。
- **corotSourceIndices**：每個 < nSources。
- **corotArtifacts**：artifact 嚴格遞增、>0、≤Long.MAX；member/shell first+count cursor
  依序覆蓋 artifactMembers/artifactShells；artifact 集合 == 各 active member/shell 經
  source block 解析到的 owner artifact 集合；表內 member index 嚴格遞增、member active、
  且 member 的 sources 確含該 artifact（shell 同理）。
- **corotProfiles/Fibers/Stations/Points**：profile.member∈[0,nMembers)、available≤7、
  reserved==0、physicalSection∈{0,1}、fiber/station/point range 為全域 cursor 且可用時
  pointCount==fiberCount*stationCount、不可用時 pointCount==0 且三能量欄位==0；
  physicalSection→area/Iy/Iz>0。fiber：reserved==0、area>0、law 合法（kind≤2、reserved0、
  E>0、tension>0、compression>0、kinematic/isotropic≥0；kind0→fET=fEC=cp=0，
  kind1→kinematic=isotropic=0&&fET>0&&fEC>0&&0≤cp≤1，kind2 同 kind1 但 cp==0）。
  station：station∈(0,1] 嚴格遞增、weight>0、section[3] 有限（profile 不可用→全 0）。
  point：loading∈{-1,0,1}、reserved==0、14 個 f64 有限、accumulatedPlastic≥0、
  plasticDissipation≥0、maximumT≥0、maximumC≥0、damageDissipation≥0、storedEnergy≥0、
  dissipatedEnergy≥0、workPotential≥0、damageT∈[0,1]、damageC∈[0,1]。
- **corotShellProfiles/Layers/TensorPoints**（ABI10，僅 shell 世界）：profile.shell∈
  [0,nShells) 不重複、available∈{0,6,7}、E>0、nu∈(-1,0.5)、yield>0、isotropic≥0、
  kinematic≥0、三能量欄位≥0 有限；layerFirst/layerCount 與 pointFirst/pointCount 為
  cursor；layer position∈[-0.5,0.5] 嚴格遞增、weight>0、∑w=1、∑wp=0、∑wp²=1/12
  （±1e-12）；tensor point：strain/plastic/stress[6]/tangent[36] 有限、accumulated≥0、
  dissipation≥0、storedEnergy≥0、workPotential≥0、loading≤1、reserved==0、
  |plastic0+plastic1+plastic2|≤2e-10·max(1,accumulated)、|dissipation-yield·accumulated|
  ≤2e-10·max(1,dissipation)、|stress[2]|≤2e-12·(yield+E·max|strain|)、stress[4]==stress[5]==0。
- `available` 位元語意：&1 SOLVED（本步收斂的力學）、&2 CHECKPOINT（可能保留的歷史，
  即使未收斂）、&4 MATERIAL（材料歷史可用）。**不可用記錄的力學/歷史欄位必須為 0，
  不得把「不可用」當成「零力=安全」**。

## Errata 2026-10-13

- 上文 corotJoints 條原寫「available 時 kind>1 要單位四元數」，方向錯誤。權威實作
  （`contract/host/bsi_corot_validate.hpp` `jointValid`）的語意是：available 且
  **kind≤1**（0=node chart、1=member release）才要求 orientation/relativeOrientation
  為單位四元數；kind≥2 只要求各欄位有限。原句一字不改保留於上，本段為有效規格。

## 4. Session / Preview / History

### 4.1 權威 session（`FiniteSession` 擴充）

- 追蹤 `liveToken`、`liveConverged`（solve/import/arc 成功後更新）、
  `historyBasis`（該 token 所對應的 historySequence；import 時取 checkpoint basis 的
  historySequence，solve/arc 時沿用）。
- `arcAdvance(ArcOptions)`：§1.3 前置 → 送請求（空 payload）→ §1.4 驗證 → 更新
  liveToken/liveConverged → 回傳 FiniteResult（`basis.historySequence==historyBasis`，
  `arc{radius,constraintResidual}` 非空）。
- `exportCheckpoint(format,byteBudget,allocationBudget,elementBudget)`：RESTORED 且有
  live token 才可匯出；回覆經 `checkExport` 全驗。arc 歷史匯出必須 format≥2
  （native 對 format1 拒絕）。
- 任一違規 → fail-closed（`refuse`），session DISABLED。

### 4.2 私有預覽 handle（`FinitePreview`）

- `restore(lane, domain, source, checkpointRef?, blob?, sessionGeneration)`：
  attach→declareVocabulary→declareWorld(committed worldRevision)→（有 checkpoint 才 import）。
- 之後 `solve()` / `arcAdvance(o)` 與 `exportCheckpoint(...)` 同 session API。
- **不動**權威 session、不寫 registry/store、不授權施工；`close()` 丟棄 handle。
- preview 的 sessionGeneration 必須與權威不同（token 是 generation 內的局部身分）。

### 4.3 歷史提交（`Operation.CommitHistory` + `FiniteHistory`）

- 新 op `CommitHistory`（codec tag 6）：payload 同 CommitRoot（revision u64、
  sequence u64、source ref、checkpoint slot）。
- `FiniteRegistry.prepareCommitHistory(request, domain, next)`：
  genesis 不存在 → 拒絕；`next.worldRevision==committed.worldRevision`（同世界）；
  `next.historySequence==committed.historySequence+1`（嚴格 +1）；`next.source`
  與 committed source 逐位相同；`next.checkpoint` **非空**（歷史提交必帶新 checkpoint）。
- 既有 `CommitRoot` 收緊為**只**接受 genesis（無 committed root）或
  `worldRevision==committed+1` 的世界邊界提交；同 revision 的歷史一律走 CommitHistory。
  這是 W3 語意拆分，不改 BUILD/EDIT/UNDO。
- `FiniteHistory.stage(lane, domain, arc|solve, format, budgets, sessionGeneration,
  publishRequest, engineBuild, contractSha256)`：
  讀 registry 的 committed root 與 durable source → 在**私有** session 重播
  vocab/world/import → 跑 solve 或 arc → export checkpoint → `store.publish` 為
  KIND_CHECKPOINT 物件 → 回傳 `Staged(result, blobRef, slot, next, session)`。
  任何一步失敗：權威不變、registry 不變；已發佈的孤兒物件依 §4.2 設計不具可見性。
- 提交：`prepareCommitHistory` → journal PREPARED → COMMITTED → `applyCommitted`；
  prevRootHash 鍊接自動由 validateCommit 保證。staging 與 commit 之間的 basis CAS＝
  Change before-image 與 committed root 位元一致（prepare 與 replay 驗證共同保證；
  競態下 accept 拒絕）。
- 匯出格式：solve-only 歷史可用 format1/2/3；arc 產生的歷史必須 format≥2。
  checkpoint 物件對 Java 不透明：不解析、不重編碼。

## 5. 資格模型與顯示

### 5.1 `FiniteResult` 增加

- `arc`（nullable `ArcStep{radius,constraintResidual}`）。
- `availability`：`Availability{reported, memberProfiles, materialProfiles,
  retainedProfiles, shellProfiles, materialShellProfiles, retainedShellProfiles}`；
  &4 計為 material，&2 且非 &4 計為 retained；無 profile 區段 → reported=false。
- `loadFactor` 改為「可未回報」：`loadFactorReported`+`loadFactor`；solve 回覆
  無此欄時為未回報（**不虛構**），arc 回覆必帶且有限。

### 5.2 `FiniteQualification`（core）

授權讀模型：worldRevision、historySequence、fullyConverged、loadFactor（含
「未回報」態）、per-island（status/statusName/flags/reason）、availability、arc。
statusName 0–4 對應 CONVERGED/MECHANISM/NOT_CONVERGED/UNSUPPORTED/INACTIVE；
其他 → `UNKNOWN`（不猜）。`assessed()` 僅當島清單非空；`materialHistoryPresent()`。

### 5.3 Forge

- `FiniteQualificationPacket`：dimension、worldRevision、historySequence、
  fullyConverged、loadFactor（reported 旗標+值）、島清單（status u32 原樣傳，
  flags u32、reason utf≤128）、availability 六計數+reported、arc（reported 旗標
  +radius+residual）。encode/decode 有界、尾隨位元拒絕。
- `ClientFiniteState`：per-dimension 最新資格；packet→state 維度守門；
  clear/leaveDimension 連動既有 client 生命週期。
- `StressHud`：finite 區塊顯示 basis（rev+historySeq）、fullyConverged、逐島狀態名、
  材料歷史可用性、loadFactor（未回報時顯示「—」而非捏造）、arc 純量。
  - **nonconvergence ≠ 倒塌**：NOT_CONVERGED 顯示為不收斂，不寫 collapse。
  - 島清單為空 → 「not assessed」，不得顯示全域安全。
  - 未知 status 碼 → UNKNOWN。
  - 明寫「靜態延續（static continuation）」，不是物理時間、不是自動倒塌軌跡。
- en_us.json / zh_tw.json 同步新增鍵（兩檔鍵集合一致、JSON 合法）。

## 6. Oracle 與反例（測試矩陣）

| # | 類別 | 判定 |
|---|------|------|
| O1 | wire 請求 | body 欄位/型別/範圍逐項；缺欄、錯型、越界 budget、direction∉{±1} 拒絕 |
| O2 | wire 回覆 | happy-path canonical payload 全驗通過，token/radius/residual/loadFactor 正確 |
| O3 | 回覆反例 | method/status/basis/namespace/generation/token/fullyConverged=false/radius>request/residual>1e-10/負值/NaN 各拒絕 |
| O4 | section 反例 | 缺 section、未知名、順序錯、非連續 offset、count×stride 不符、payload 尾隨位元、island status≥5、member available>7、profile↔member backref 不符、source 缺世界 block/artifact 錯、fiber law 非法、point loading 越界、reserved≠0 各拒絕 |
| O5 | session | solve→arc happy path（新 token 供下一步）；非全收斂 source→拒絕+disable；非零 prescribed→wire 前拒絕；out-of-order→拒絕；engine error→disable |
| O6 | preview | 私有 lane 上 restore+solve/arc 不回寫權威 lane；token 不互通；close 不影響權威 |
| O7 | history | solve→export→publish→CommitHistory：seq+1、rev 不變、source 相同、prevRootHash 鍊接；第二次 arc 提交 seq+2 |
| O8 | history 反例 | CommitHistory 越 revision、缺 root、無 checkpoint、source 不同、seq 跳號、污染 checkpoint 的 import 失敗各拒絕；權威不變 |
| O9 | qualification | of(result) 欄位對映；unknown status→UNKNOWN；空島→not assessed；availability 計數正確；&2-only→retained |
| O10 | 去 available 反例 | profile/member 一致剝 &4 → availability 歸零（非捏造的「材料可用」）；不一致（member 稱有 material 而 profile 無）→ 拒絕 |
| O11 | 舊 token 重用 | 用上一輪 token 發 arc → native 端語意下 Java 拒絕（session 只認 live token） |
| O12 | packet | FiniteQualificationPacket encode/decode 往返、有界、尾隨拒絕、NaN 拒絕 |
| O13 | native（-Dbr.engine） | 真引擎：solve→arc.advance→export fmt2→關→新 session import→再 arc 的
  loadFactor/radius 連續性；無 engine 環境整檔跳過（不冒充已驗） |
| O14 | 文件計數 | 新增測試後更新 pinned Java 總數 |

## 7. 不做的事（明示）

- 不在 Java 內做線性參考解、剛體轉動重算、兩島 load factor 合成——O1–O13 裡
  力學 oracle 都以 native-gated 測試承載（O13），Java 端只驗 wire/儲存/顯示。
- arc 不是物理時間、不是自動倒塌軌跡；UI 不得暗示相反。
- 不為 fracture/motion 鋪路性實作（W4–W6）。
- preview 結果永不進 registry/store；preview 匯出的 blob 即使位元上可提交，
  也只在 staging 流程經正常 journal 提交才生效（授權在交易，不在 handle）。
