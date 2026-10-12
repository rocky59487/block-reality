# W4-G 有限域 bootstrap 與閘控遊戲內入口 — 凍結判準

2026-10，基線 `agent/w4g-finite-entry@8d4d16f`（W4-B0 runtime 與 W4-B fracture host 已在）；
判準先於實作獨立提交。

## §0 規格來源（誠實聲明）

任務書 `TASK-W4G.md` **未隨分支交付**——全部 worktree 搜尋僅找到
`E:/br-v2a/br-w4b/TASK-W4B0.md`。本判準由既有契約重建：

- `docs/design/V2_FORGE_INTEGRATION.md`（§3 資料模型、§4 持久化、§5 執行緒、
  「finite 模式只能在明示鎖定的測試域使用，不對 survival 宣稱可安全任意建拆」）；
- `docs/FINITE_FRACTURE_TRANSACTION.md` §9.5（私有 lane marshaling）與 §9.7（維度閘門）；
- 現況程式：`FiniteRuntime`、`ForgeFiniteService`、`FiniteLaneWorker`、
  `ManufacturedRegistry`、`FiniteDomainSource`、`BRConfig.finiteTestDimensions`；
- 引擎實測 fixture：`FiniteArcNativeTest`/`FractureTestFixtures` 的 152B material 與
  176B options 慣例（v1.5 實機已驗過的形狀，不是新發明）。

凡屬推定而非規格原文的語意一律標 **（推定）**，並取保守選項；不確定的引擎語義不捏造。

## §1 範圍（凍結）

本單元交付：

1. `FiniteEntryService`（`server/finite` 套件）——唯一受控遊戲內入口：
   `bootstrap(String dimension)` 與 `status(String dimension)`；`Core` 為 MC-free 測試核
   （worlds／lanes／guards／admittedDimensions 全部注入），與 `FiniteFractureService.Core`
   同型。
2. `FiniteObservation`——MC-free 觀測縫＋收集器：bound cells 的 live 一致性驗證、
   面鄰 sturdy 非結構格支承收集、unloaded chunk 具名計數。adapter **永不**主動載入
   chunk、永不生成、永不寫入世界。
3. `LevelFiniteWorld`——`ServerLevel` 上的生產 adapter（`getChunkNow` 探測，
   air→Air、結構塊→`Structural(declaration)`、其他→`Other`；sturdy 面檢查）。
4. `FiniteRuntime.unboundPlans(domain)`（枚舉該域 dimension 未綁定製造件為
   `Operation.BindingPlan`，單調序）與 `markWaiting(domain, missingChunks)`
   （觀測不完整的域標記，與 `commitSource` 早退分支寫同一記錄）。
5. `/br finite status` 與 `/br finite bootstrap`——`finite` literal 經 `BrPermissions`
   表掛 `LEVEL_OP`。
6. `PrivateLane.openNamed`——marshaling lane 工廠的完整執行緒名變體；
   fracture lane 名 `br-fracture-lane-*` 不變。

明確不做（邊界）：

- ❌ solve／qualification 驅動、`FiniteQualificationPacket`、HUD（W3 in-game 為另一線）；
- ❌ fracture 入口或 host 語意變更（§9 已凍）；
- ❌ 任何非 server-thread 觸發點、自動 adoption／refresh、event hook；
- ❌ `contract/` 修改、Java 力學推導、除 source 組裝外任何引擎語義假造；
- ❌ 空 admission 集下對生存維度的任何宣稱（design：empty ⇒ 無維度可收養）。

## §2 bootstrap 語意（凍結）

`bootstrap(dimension)` 單一受控序列，全部在 server thread（§9.5 named slow path）：

1. `entryGuard`：server thread＋construction＋finite runtime 可用；closed／failed 拒絕；
2. dimension ∈ `finiteTestDimensions`：不在集合 → 具名 `IOException`（空集拒絕一切，
   對 survival 無預設觸發）；
3. `runtime.adoptDomain(dimension)`——冪等（已收養則回既有 domain）；
4. `runtime.unboundPlans(domain)`：`metadata.ownedCells(dimension)` 枚舉 → owner 去重 →
   跳過已綁定、RETIRED、異域 piece → 依 `(birthOrder, id)` 排序 → `BindingPlan`；
   `declaration.axis() < 0` 的 piece 整批具名拒絕（缺 wire axis 不可進域，不猜軸向）；
   空 plans 跳過 bind；非空 → `bindArtifacts`（COMMITTED 以外回 `Refused` 帶 receipt）；
5. `FiniteObservation.gather(activeBindings, world)`：
   - bound cell live 讀值分類：`Structural(decl)` 且 `decl.equals(binding.declaration())`
     → 一致；`Air`／`Other`／`Outside`／declaration 不等 → `drift` 具名清單
     （`cell/x/y/z`＋期待值與實際值）→ `IOException` 拒絕；
   - 任何 probe 回 `Unloaded` → missing chunk 計數＋1（同一 chunk 去重）；
   - 每 bound cell 六面鄰格：非 bound 格上 `Other` 且接觸面 sturdy → 支承集合；
     `Unloaded` 鄰格同計 missing；
   - drift 非空 → 具名拒絕（bind 已 durable 留存——拒絕只涵蓋 source 提交，訊息明示）；
   - missing>0 → `runtime.markWaiting(domain, missing)`＋回 `Waiting` 結果；
     **不開 lane、不建 spec、不寫 journal**；
6. vocabulary 對 **（推定：重用 durable pair，genesis 才 mint）**：
   - 無 committed root（genesis）：material role 預檢先於開 lane（§3）；再於
     **私有 lane** `PrivateLane.openNamed("br-entry-lane-<dim-sanitized>", lanes)` 上
     `vocab.declare(GameVocabulary.declaration())` → durable reply
     （`BsiVocabulary.decodeReply`）；`!ready()`／`!has("bsi.corot")`／錯誤回覆 →
     具名 `IOException`（引擎不可消費的域不提交）；
   - 已有 committed root：重用 durable `vocabRequestJson`/`vocabReplyJson`，**不開 lane**——
     durable 語義禁止每次 bootstrap 換 vocabulary；
7. `Spec` 組裝（§3/§4）：bindings 無 → 具名拒絕「domain has no bound cells」；
   `supportMaterial="ground_rigid"`；shell／load／prescribed 組皆為空；
8. `commitSource(domain, spec, DomainObservation.observed(supports))` →
   receipt COMMITTED → `Committed` 結果；其他 phase → `Refused`（具名理由）。

`status(dimension)`：read-only 快照——admitted／adopted／observation 狀態、
bindings 數與格數、committed root `(worldRevision, historySequence)`、
source 的 block/support 數、`laneStatus`。

## §3 material law 記錄（凍結）

engine-proven 形狀（`FiniteArcNativeTest.steelMaterial` 與 `BsiCorot.checkLaw`
對 kind 0 的驗證規則）：每 material 152B =
`[id:i32][reserved:i32][law:72B][concrete law:72B]`；law =
`[kind:i32=0][reserved:i32][E:f64][σT:f64][σC:f64][kinematic][isotropic][fET][fEC][cp]`，
kind-0 要求 E/σT/σC>0 且 fET/fEC/cp==0。concrete law 全零（v1.5 實測 fixture 同）。

- 資料只取自 durable vocab request `materials[]` 行的 `E`、`allow.sigmaT`、`allow.sigmaC`；
  缺欄／非正 → 具名拒絕，**不補預設值**；
- 記錄按 wire material id 升序排列（deterministic source）；
- role 預檢：`member`/`monolith` → 記錄；`panel` → 具名拒絕（shell law 語義未於 v1.5
  實測證明，不發明）；其他角色 → 具名拒絕；
- **（推定）** kind-0 為 v1.5 唯一實測 material law——Java 端不生成其他 kind。

## §4 options 模板（凍結）

與 core 測試 fixture 完全相同的 176B 欄位值：`loadFactor=1`、gravity=(0,-9.81,0)、
`flags=1`（selfWeight；`FiniteDomainSource.options176` 於 commit 時再 OR
retainLoadPath）、`relTol=1e-4`、`forceTol=1e-3`、`momentTol=1e-3`、`initialStep=1`、
`minStep=1e-6`、`maxStep=1`、`linearTol=1e-8`、`pathTol=1e-4`、`budgetDof=1<<20`、
`numThreads=0`、`maxIter=50`、`maxAttempts=4`、`maxBacktracks=8`、`linearIter=25`、
`restart=1`、`pointBudget=4096`、`fiberCells=8`、`radialCells=2`、`angularCells=8`；
`expectedDomain/expectedRevision` 由 `options176(domain,revision)` 於 commit 時重蓋，
模板值不參與 wire。

## §5 執行緒與 lane 不變式（凍結）

- 所有原生呼叫僅在 `br-entry-lane-*`（mint）或 `br-finite-lane-*`（authority）執行緒；
  測試於 lane 內以 `Thread.currentThread().getName()` 斷言；
- server thread 不對 `FiniteLaneWorker` posted API（`call`/`sync`）做 get/join；
  bootstrap 的私有 lane 同步契約屬 §9.5 named slow path；
- mint lane 於 `vocab.declare` 完成後即關閉（engine＋執行緒一起死），不復用；
  genesis 之外不開私有 lane；
- `markWaiting`／`unboundPlans` 為 runtime 內部簿記／枚舉，不發 intent、不寫 journal。

## §6 命令與權限（凍結；命名推定）

- `/br finite status`：read-only 快照輸出（§2 status 語意）；
- `/br finite bootstrap`：執行 §2 序列，輸出 domain、新綁定數、支承數、receipt
  phase/id、或 waiting／refusal 明細；
- `finite` literal 於 `BrPermissions` 掛 `LEVEL_OP`（含 mutating 子指令 → 整棵 literal
  OP 級，與既有表粒度一致）；`READ_ONLY_WHITELIST` 不變。

## §7 測試腿 ↔ 不變式對照（凍結）

`forge/src/test/java/.../ForgeFiniteEntryTest.java`，沿用 `ForgeFiniteRuntimeTest`
harness（`@TempDir`、scripted lanes、AtomicBoolean 旗標；製造件經真 journal
`prepareBuild`→`decide(COMMITTED)`→`applyCommitted` 產生）：

| 測試 | 覆蓋不變式 |
|---|---|
| `bootstrapAdoptsBindsObservesAndCommits` | 全序列；journal 恰含 adopt＋bind＋commit 三條；owner id 單調 mint；committed source 覆蓋 bound cells＋sturdy 鄰支承；vocabReply==minted durable reply；SOURCED；authority sync 後 laneStatus SYNCED |
| `bootstrapIsIdempotentOnSecondRun` | 二次 bootstrap：0 新 bind、mint lane 不重開（open 計數==1）、commit worldRevision=1、bindings 集合不變 |
| `unadmittedDimensionRefused` / `emptyAdmittedRefusesEverything` | §2 閘門：具名拒絕、journal 無新 entry、無 lane 開啟 |
| `unloadedCellWaits` | `Unloaded` probe → `Waiting(missing≥1)`、Observation==WAITING_CHUNKS、無 lane、無 commit entry |
| `worldDriftRefuses` | live `Air`／`Other`／declaration 不等 → 具名 drift 拒絕（drift 訊息含 cell 座標）；source 未提交 |
| `panelMaterialRefuses` | bound material 角色 `panel` → 具名拒絕且 mint lane 未開（role 預檢先於 lane） |
| `noBoundCellsRefuses` | 空域 → 「no bound cells」具名拒絕，無 mint、無 commit |
| `nonServerThreadRefused` / `closedServiceRefuses` | entryGuard／closed → IllegalStateException |
| `mintRunsOnPrivateLaneThread` | 所有 lane 呼叫執行緒名為 `br-entry-lane-*`／`br-finite-lane-*`；caller thread 零 native |
| `laneWithoutCorotRefuses` / `vocabRefusalRefuses` | `has("bsi.corot")` false／錯誤回覆 → 具名 IOException、無 commit、journal 不增長 |
| `statusReportsDomainState` | status 於 adopted/waiting/sourced 各態回傳一致快照 |
| `axislessPieceRefuses` | `declaration.axis()<0` 的 piece → `unboundPlans` 具名拒絕、無 bind intent |
| 權限表 | `finite`→LEVEL_OP 經 `BrPermissionsTest` 枚舉測試＋privileged 名單斷言 |

## §8 測試命令與計數（凍結）

```bash
./gradlew :core:test          # core 不變式回歸
./gradlew compileJava test    # forge 測試（JDK 17 環境）
python3 scripts/check_docs.py sidecar/legacy-dist/br-sidecar.exe
```

交付時以實測數字更新文件釘點；SKIP 不算綠；首敗輸出必須保留並誠實報告。
