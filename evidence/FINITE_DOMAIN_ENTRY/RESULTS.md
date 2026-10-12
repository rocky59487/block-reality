# W4-G FINITE_DOMAIN_ENTRY — 有限域 bootstrap 與閘控遊戲內入口判準執行

判準：`docs/FINITE_DOMAIN_ENTRY.md`（先於實作提交於 `b5b3b27`）。
**誠實聲明**：`TASK-W4G.md` 未隨分支交付——全部 worktree 搜尋僅找到
`E:/br-v2a/br-w4b/TASK-W4B0.md`。判準由既有契約重建；推定語意於判準檔標（推定）。

執行環境：Windows、JDK 17（`C:/Program Files/Java/jdk-17`）。
證據日期：本單元提交前實跑。

## A. 檔案與接線結構

- [x] **A1** `FiniteEntryService.java`、`FiniteObservation.java`、
  `LevelFiniteWorld.java` 存在於 `forge/src/main/java/com/blockreality/impl/server/finite/`
  且全部編譯通過。證據：`./gradlew compileJava` BUILD SUCCESSFUL。
- [x] **A2** `BlockRealityMod.java:46` 註冊 `FiniteEntryService.class`（file assert）。
- [x] **A3** `/br finite status|bootstrap` 經 `BRCommand.java` `lit("finite")` 掛入命令樹
  （file assert：`then(lit("finite")...)`）；`finite` 於 `BrPermissions.java:66` 掛
  `LEVEL_OP`。`BrPermissionsTest` 四測試 PASS（枚舉＋白名單不變＋privileged 名單含
  finite＋未知 literal 拒絕）。
- [x] **A4** `PrivateLane` 新增 `openNamed` 完整執行緒名工廠；
  `open(name)` 仍產生 `br-fracture-lane-*`（fracture 執行緒名不變——
  `ForgeFiniteFractureTest` 全套 PASS 回歸）。
- [x] **A5** `FiniteRuntime` 只加 `unboundPlans`／`markWaiting` 兩個 public 方法；
  `contract/`、`mod/core` 無 diff。

## B. bootstrap 序列（adopt → bind → observe → mint-or-reuse → commit）

- [x] **B1** 全序列於 admitted dimension 完成：`bootstrapAdoptsBindsObservesAndCommits` PASS——
  journal 恰含 build＋adopt＋bind＋commit 四條；ownerId=1 單調 mint；
  committed source 含 2 owned＋1 sturdy 支承格（`sect==-1`）；
  `vocabReplyJson == minted durable reply`；Observation==SOURCED；
  authority sync 後 laneStatus==SYNCED。
- [x] **B2** 冪等：`bootstrapIsIdempotentOnSecondRun` PASS——二次 bootstrap 新綁定 0、
  mint lane 不重開（open==1）、worldRevision=1、journal +1 條。
- [x] **B3** 支承收集：`nonSturdyNeighbourIsNotASupport` PASS——非 sturdy 鄰格不入 source；
  happy path 中 sturdy `Other` 鄰格成 `sect==-1` 支承格且不進 owners。

## C. 閘門與拒絕

- [x] **C1** `unadmittedDimensionRefused` / `emptyAdmittedRefusesEverything` PASS——
  具名 IOException、journal 零增長、lane 零開啟。
- [x] **C2** `unloadedCellWaits` PASS——`Unloaded` probe → `Waiting(1)`、
  Observation==WAITING_CHUNKS、無 commit、無 lane。
- [x] **C3** drift：`airCellDriftRefuses`／`foreignBlockDriftRefuses`／
  `declarationMismatchDriftRefuses` PASS——drift 訊息含 `cell/x/y/z`、
  committed root 為空、lane 零開啟。
- [x] **C4** `panelMaterialRefusesBeforeAnyLane` PASS——panel 角色具名拒絕、
  mint lane 零開啟（role 預檢先於 lane）。
- [x] **C5** `noBoundCellsRefuses` PASS——空域具名拒絕。
- [x] **C6** `axislessPieceRefusedAtCommit` PASS——axis<0 declaration 於
  `prepareBuild` 即拒絕、journal 零增長（axis 不變式在 manufactured 層咬合）。

## D. 執行緒與 lane 不變式

- [x] **D1** `mintRunsOnPrivateLaneThread` PASS——mint lane 全部呼叫執行緒名
  `br-entry-lane-minecraft-overworld`；authority lane 全部 `br-finite-lane-*`；
  caller thread 兩者皆未出現。
- [x] **D2** `laneWithoutCorotRefuses` PASS——`has("bsi.corot")==false` →
  具名 IOException、journal 停在 build+adopt+bind。
- [x] **D3** `vocabRefusalRefuses` PASS——錯誤回覆 → 具名 IOException、無 commit。
- [x] **D4** `nonServerThreadRefused`／`closedServiceRefuses` PASS——
  IllegalStateException、journal 零增長。

## E. 查詢面

- [x] **E1** `statusReportsDomainState` PASS——adopted/waiting/sourced 快照一致：
  bindings/boundCells/root(rev,seq)/sourceBlocks/laneEvent(SYNCED)。

## 測試計數（實測）

```
./gradlew :core:test            → 656 tests, 83 skipped（平台/原生閘控）, 0 failed
cd forge && ./gradlew test      → 241 tests, 1 skipped, 0 failed
python3 scripts/check_docs.py sidecar/legacy-dist/br-sidecar.exe
                                → 36 quoted counts all agree（java_total 879→897）
```

文件釘點同步：`README-pre-alpha.md`（3 處）、`QUICKSTART-pre-alpha.md`（1 處）、
`RESEARCH_BRIEF.md`（795 pass→813 pass 等 2 處）、`LISTING.md`（2 處）。

## 殘留風險／未做邊界（誠實清單）

- `TASK-W4G.md` 原文未取得：`finite` 命令表面（status/bootstrap 命名）、私有 lane
  mint 對 authority `call()` 之選擇、panel 拒絕策略、material kind-0 唯一性皆為
  保守推定——真規格出現時逐條複核判準檔的（推定）標記。
- 真引擎端對端（`InProcessEngine` 實載 native）未驗：bootstrap 的 vocab mint 與
  authority sync 皆以 scripted lane 驗過 wire 層；native 適用測試需含庫環境。
- `bootstrap` 對已存在 committed root 的域總是提交下一 worldRevision（refresh 語意）；
  若真規格要求「重複 bootstrap 無變動時拒絕」，需加同 sourceHash 拒絕腿。
- EDITED 狀態 piece 的 binding 已涵蓋（unboundPlans 只跳 RETIRED/已綁定），
  但「piece 綁定後再被編輯」的 drift→rebind 流程屬後續單元。
- `/br finite` 於遊戲內真人操作未驗（command executor 於 Brigadier 註冊層 file-assert）。
