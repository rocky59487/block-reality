# TASK-W4B0-GATES — Forge finite runtime 基礎接線判準（先凍）

本檔先於實作碼建立。每條判準列出可檢查證據（測試名／檔案斷言／指令）。
PASS 必須附實際執行證據；沒有 gate 跑過的能力不寫進能力清單；SKIP 不算綠；
失敗照登，不換 fixture 救判決。

執行環境：Windows、JDK 17（`C:/Program Files/Java/jdk-17`）。
證據日期：本單元提交前實跑。

## A. 檔案與接線結構

- [x] **A1** `forge/src/main/java/com/blockreality/impl/server/finite/` 存在且含
  `FiniteRuntime.java`、`FiniteJournalHost.java`、`FiniteDomainSource.java`、
  `FiniteLaneWorker.java`、`ForgeFiniteService.java`，全部編譯通過。
  證據：五檔存在；`./gradlew compileJava` BUILD SUCCESSFUL；`./gradlew test` 全部通過。
- [x] **A2** `ConstructionService` 暴露同一 `FileTransactionJournal`／
  `AtomicConstructionCoordinator`／`ManufacturedRegistry`／session／journal 目錄的
  共享存取；finite 服務不得第二次 `FileTransactionJournal.open` 同一目錄。
  證據：`grep -rn "FileTransactionJournal.open" forge/src/main/java/` 只命中
  `ConstructionService.java:74`；`recoverStartup` 經 `pendingRouter.route(entry)`
  路由 pending entry（finite entry → `FiniteJournalHost`，否則落回 construction `Host`）。
- [x] **A3** `FiniteStore` 由 `openAdjacentTo(journalDirectory)` 開啟，落點為
  `blockreality/finite`。證據：`storeLocationAdjacentToJournal` PASS，
  斷言 `store.root() == journalDir.resolveSibling("finite")`；
  `FiniteRuntime.open` 使用 `FiniteStore.openAdjacentTo(journalDirectory)`。
- [x] **A4** `ForgeFiniteService` 掛上 `MinecraftForge.EVENT_BUS`，用
  `ServerAboutToStart`／`ServerStarting`／`ServerStopped` 節奏，且 finite 的
  aboutToStart 在 ConstructionService（HIGHEST）之後（HIGH）。
  證據：`BlockRealityMod.java:44` 註冊 `ForgeFiniteService.class`；
  `aboutToStart`/`starting` 皆 `EventPriority.HIGH`，construction 皆 `HIGHEST`。
- [x] **A5** core 只加唯讀/委派面：`FiniteRegistry.domains()` 啟動列舉；
  `GameNativeLoader.openEngine` 為原 `open` 的純抽出委派（行為不變，回傳 raw engine）。
  `contract/`、`ManufacturedRegistry` 語意不動。
  證據：`git status` 的 core 變更僅 `FiniteRegistry.java` 與 `GameNativeLoader.java`；
  `contract/` 無 diff。

## B. finite intent 橋接（同一 coordinator 完整 PREPARED→COMMITTED）

- [x] **B1** `adoptDomain(dimension)` 產生 COMMITTED journal entry，intent 含
  `finite/order`、`finite/tx/<id-hex>`、`finite/domain/<dimHash>` 三類資源；
  `FiniteRegistry` fold 後 `domain(dimension)` 回傳含 journalDomain／dimension／
  bsiDomain／artifactNamespace 的 FiniteDomainId。
  測試：`adoptionCommitsFiniteIntent` PASS。
- [x] **B2** `bindArtifacts` 把 manufactured piece UUID 綁成 positive i64 ownerId，
  單調從 1 起、批次連號、retire 後不回用；支承格不進 owners。
  測試：`bindMintsMonotonicOwners` PASS、`retiredOwnerIdNeverReused` PASS；
  支承不進 owners 由 `sourceCoversOwnersAndSupports` PASS 斷言。
- [x] **B3** COMMITTED 後 `applyCommitted` 已發布：`binding(ns,id)`／
  `activeBindings(ns)` 查得到。
  測試：`bindMintsMonotonicOwners` 同測試斷言 PASS。
- [x] **B4** 同一 request id + 相同 request 重放 → 回傳先前 receipt、不二次決策、
  journal 僅一筆。測試：`replaySameRequestReusesReceipt` PASS。
- [x] **B5** 同一 request id + 不同 effective input → `ReplayConflict`（IOException），
  不得靜默接受。測試：`replayDifferentInputsRejected` PASS。
- [x] **B6** PREPARED finite entry 於 startup 經 `validatePrepared` →
  `coordinator.recover` → ABORTED；fold 不含其效果。
  測試：`pendingFiniteRecordRecovers` PASS。
- [x] **B7** finite applyCommitted 不一致 → registry poisoned，後續 finite op
  `IllegalStateException`；明示 service failure。
  測試：`poisonedRegistryRefusesFurtherOps` PASS（偽造 owner image 的 COMMITTED
  entry 餵入 `committed()` → IOException → `failure()` 非空 → 後續 submit 拒絕）。

## C. NativeLane worker（非阻塞、世代撤銷）

- [x] **C1** 所有 `FiniteSession`/lane 呼叫在 worker daemon 執行緒
  （scripted lane 記錄 `Thread.currentThread()`，斷言非呼叫者執行緒）。
  測試：`laneCallsRunOnWorkerThread` PASS（全部 recorded thread 名以
  `br-finite-lane` 開頭）。
- [x] **C2** CommitRoot COMMITTED 後 lane 依序執行
  vocab.declare → world.declare →（有 checkpoint 時）corot.checkpoint.import。
  測試：`commitDrivesSessionSync` PASS（genesis 無 checkpoint → 前兩步依序，
  `laneStatus == SYNCED`）。
- [x] **C3** server 端提交路徑不 join worker future：scripted lane 阻塞在
  latch 時 `sync`/`commitSource` 的投遞呼叫仍然返回。
  測試：`submitNeverBlocksCaller` PASS（lane gate 鎖住時 commitSource 仍
  COMMITTED 返回，耗時遠低於 5s 上限）。
- [x] **C4** reset/close 撤銷 sessionGeneration：世代已撤銷後完成的 sync 結果
  不發布、session 不留。測試：`staleGenerationDropped` PASS（reset 後
  generation=0 的 sync 產生 STALE event，lane 無新增 native call）。
- [x] **C5** `basis(dimension)` 對外暴露最新 committed basis（worldRevision／
  historySequence／三 hash＋sessionGeneration）。
  測試：`basisReflectsCommittedRoot` PASS。

## D. FiniteSource 建置（wire 組裝，無 Java 力學）

- [x] **D1** 由 active bindings＋支承觀測組 canonical blocks40＋完整 owners20；
  支承格在 blocks、不在 owners；亂序輸入 bytes 相同。
  測試：`sourceCoversOwnersAndSupports` PASS、`sourceCanonicalOrderStable` PASS
  （反序 bindings＋反序 supports 產生逐位相同 serialize）。
- [x] **D2** options176 依 contract 欄位布局、retainLoadPath 位元強制；
  materials 至少 1×152B。測試：`optionsRoundTrip` PASS（BsiCorot.options 解回
  expectedDomain/revision/retainLoadPath）、`sourceRequiresMaterials` PASS。
- [x] **D3** 觀測不完整 → 域狀態 WAITING_CHUNKS、不建 source、不寫 journal。
  測試：`incompleteObservationWaits` PASS（entryIds 不變、root 不存在、
  observation == WAITING_CHUNKS）。
- [x] **D4** 無 Java 力學：不計 stress/force/damage/verdict。
  證據：code review — `FiniteDomainSource` 只呼叫 `FiniteSource.build` 與 wire
  打包（`BsiCorot.options` 僅作自校驗 decode）；無任何數值推導 API。

## E. 生命週期與恢復

- [x] **E1** 關閉後於同目錄重開 → fold 結果逐項相同（domain、binding、root、
  order）。測試：`restartFoldIdentical` PASS（重開後 domain id 相同、
  nextOwnerId=2、worldRevision=0、order=3，且 syncAll → SYNCED）。
- [x] **E2** `stopped` 關閉 store＋worker＋撤銷 generation；之後任何 finite op
  拒絕且 `available()==false`、`closed()` 明示。
  測試：`closedServiceRefuses` PASS。
- [x] **E3** 篡改 `finite/order`（跳號或偽造 before）的 COMMITTED entry →
  `FiniteRegistry.load` 或 `applyCommitted` 必拒（IOException）。
  測試：`tamperedFiniteOrderRejected` PASS（after 跳號 5 → load 拋 IOException）。
- [x] **E4** committed root 引用的 source blob 遺失 → `source()` 失敗關閉
  （IOException）；root fold 本身仍成功（blob 內容按需驗證）。
  測試：`missingSourceBlobFailsClosed` PASS（刪 `objects/<sha>.blob` 後
  `runtime.source(domain)` 拋 IOException）。
- [x] **E5** 非 server 執行緒呼叫 finite op → IllegalStateException，不寫 journal。
  測試：`nonServerThreadRefused` PASS（entryIds 不變）。
- [x] **E6** 非 finite pending entry 不被 finite host 認領（provider 回 null，
  由 construction 路徑處理）。測試：`manufacturedPendingNotClaimed` PASS
  （`recoveryHost` 回 null，metadata pending 由 construction 路徑 ABORTED）。

## F. 文件與計數

- [x] **F1** `python3 scripts/check_docs.py` 通過；所有被引用文件的測試計數同步。
  證據：`python3 scripts/check_docs.py sidecar/legacy-dist/br-sidecar.exe` →
  `measured: engine=330 closed_form=41 java_total=837 java_engine_backed=28` ＋
  `36 quoted counts all agree with the suites`。
  備註：不帶參數的呼叫需要 `dist/br-sidecar`（本 worktree 不存在，
  屬既有環境限制，非本次變更造成）；legacy-dist exe 為腳本支援的 argv[1] 路徑。
  同步變更：README-pre-alpha ×3、QUICKSTART-pre-alpha、RESEARCH_BRIEF、LISTING
  的 813→837、Forge 側 177→201、Windows 729 pass→753 pass（84 skips 不變）。
- [x] **F2** `cd forge && ./gradlew test` 與 `cd mod && ./gradlew :core:test`
  以 JDK 17 實跑；失敗照登。
  證據：forge 201 registered → 200 PASS + 1 既有平台 SKIP
  （NativeResultPacketTest.realNative…，native exe 環境 gated，非本單元）；
  mod core 636 registered → 553 PASS + 83 既有 gated SKIP + 0 FAILED。
  本單元新增 24 項 `ForgeFiniteRuntimeTest` 全 PASS。
