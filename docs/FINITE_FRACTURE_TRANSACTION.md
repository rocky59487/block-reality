# 有限元旋轉斷裂交易（W4-A，mod/core 層）

2026-09-14，基線 `Main@8368595`（W2/W3 已在）；判準先於實作獨立提交。
沿用 ConstructionLedger、AtomicConstructionCoordinator 及 FileTransactionJournal；
有限權威沿用 FiniteStore／FiniteRegistry／FiniteCodec 的內容定址物件與日誌 fold。
本單元只交付 `mod/core`：wire、receipt、私有 staging、持久 plan、driver、崩潰矩陣
與 core 測試。`contract/` 是唯一線上權威，不發明欄位、不派生語意、不在 Java 推
物理。不含 `Forge/`、不含真 Level 移除、不含碎塊生成、不含碰撞、不含 W4a 非阻塞
runtime driver；這些在後續單元凍結。

## §1 世界與來源模型

1. `FiniteSource` 新增不可變 `failureRules` 位元組（`bsi_corot_failure_rule`×n，
   n≤4096，長度恰為 40 的倍數）。來源雜湊、序列化、物件內容均涵蓋之；
   `build` 及解碼路徑均驗倍數與計數上限。既有 12 節來源物件繼續可解
   （規則視為空）；新編碼一律寫 13 節（`S_FAILURE_RULES=13`），decode 後
   `serialize()` 位元組穩定（解碼產物保留原始 payload），舊雜湊不回溯。
2. `materialRoles` 複本提供給 corot 世界與斷裂規則驗證：規則的 `material`
   必須是已知材料 id；`constituent` 為非負 u32；`accumulatedPlastic` 非負有限，
   `damageT/damageC ∈ [0,1]`，`minimumAreaFraction ∈ [0,1]`；這是輸入衛生而非語意。
3. `afterFracture(world)` 由 receipt 的 `physicalTotals`、`fractureCells`、
   `fractureFragments`、事件分組與剩餘世界嚴格重建：每個物理 cell 唯一屬於一個
   world 格；`group==1` 的 cell 從世界移除；retained 格保留原生 bytes；
   owners 中不再持有任何格者退役，仍持格者保留；世界格集合恰等於 receipt
   宣稱的 `remainingBlocks`。與 receipt 的 `fractureCells`/`corotSources` 逐
   byte 比對由 receipt 驗證器完成，Java 不重建分組。
4. 「生效輸入」= 來源的 materials／cellLoads／prescribed／generalized loads／
   殼法／殼層／options 位元組 + 本請求的 rules+budget。任一不同的重試必須在
   requestId 去重層被拒，不得靜默成為「不同輸入的同一交易」。

## §2 請求框架

`bsi.corot.fracture.prepare` 的請求主體欄位、順序與 `bsi.schema.json`
`bsi_corot_fracture_prepare_request` 完全一致：`options`、`materials`、
`cellLoads`、`prescribed`、`loads`、`requestId`、`rules`、`budget`、可選
`initialAnalysis`、有殼材時 `shellMaterials`+`shellLayers`（兩者必同時出現）。
payload 依序為：materials(152B) 位元組、cellLoads(64B)、prescribed(64B)、
loads(144B)、rules(40B)、shellLaws(56B)、shellLayers(16B)——長度恰為各段
總和，殼段全有或全無。`options.expected` 必須是 committed before world stamp；
`numThreads` 沿用 solve 路徑規則（>0 才寫）。`rules.count` 與 payload 規則
位元組一致。不發明請求欄位，不改既有 options 語意。

## §3 Receipt 嚴格驗證（`BsiCorotFracture.Prepared`）

回應驗證鏡像 `corot_wire::fracture` + `fracture_wire::receipt` + archive 群，逐項：

1. 框架：`status=="prepared"`；id／method／revision 回聲；`requestId` 回聲；
   `before` 為 committed world stamp 且 domain 非零；`after = {same domain,
   revision=before+1}`；`artifactNamespace` 為 domain 命名空間；`token.context`
   非零、`sequence` 非空；`steps`、`flags≤3`、`remainingBlocks`、`end≤2`、
   `corotFlags≤15`、`pending≥-1`、剩餘 `fullyConverged` 布林、`remainingGeneration`
   u64 皆精確整數。
2. 目錄：恰好 fracture 七段 → corot 分析十七段（±殼尾三段）→ 判定五段 →
   封存八段（±退役殼尾三段）；每段 offset 嚴格遞增、`bytes==count×契約stride`
   （fractureCells=144、fractureFragments=96、fractureParents=8、
   fractureEvents=24、fractureEventCells=4、fractureMechanism=12、
   corotDecisions=48、corotPhases=88、corotFailures=40、corotDecisionCells=4、
   corotFractureLoads=72、corotArchives=592、corotRetiredMembers=1248、
   corotRetiredShells=1624、corotRetiredCells=20、corotArchiveOwners=20、
   corotRetiredProfiles=88、corotRetiredFibers=104、corotRetiredStations=40、
   corotRetiredPoints=120、corotRetiredShellProfiles=88、
   corotRetiredShellLayers=16、corotRetiredTensorPoints=472）；offset+bytes
   不得溢出 payload；總長恰等於 payload。
3. 物理群（鏡像 `fracture_wire::receipt`）：`physicalTotals` 恰 3 筆
   （beforeMass/remainingMass/brokenMass），各 80B，`physical()` 規則＝十個
   f64 全有限、mass≥0、inertia 對角（xx,yy,zz）非負；`fractureCells` 恰
   `nCells=nOwners`，序對應 structural（role∈member/panel/monolith）的
   canonical 格序；每格 40B `source` 位元組逐 byte 等於 committed source 的
   canonical block image、`artifact` 等於 committed owner、`panelNormal` 於
   panel 材料 ∈[0,2]、其餘==-1、`reserved==0`、`flags∈{0,1}` 且
   `flags&1 ⇒ group==0`（unrepresented 永不斷裂）、`group ≤ nFragments+1`、
   cell 的 `physical()` 同規則；`nFragments≤nCells`、`nMechanismCells≤nCells`、
   `steps ≤ budget+1`（native 以 request.budget+1 為上限）。
   fragments：`reserved==0`、`flags≤7`、`physical()` 同規則、`parentFirst`
   遞增鏈結且每 fragment 至少一個 parent；`fractureParents` 為各 fragment 的
   排序唯一 artifact id 串接、恰好完全覆蓋；`group>=2` 的 cell 歸入
   `parents[group-2]`，各 fragment 的首格按 (x,y,z) 嚴格遞增。
   `remainingBlocks` header 欄位恰等於 before 格數減去 `group!=0` 的 cell 數
   （非 structural 格永遠留存）。
4. 事件群：events `cellFirst` 嚴格鏈結、`cellCount≠0`、完全覆蓋
   fractureEventCells；每個 eventCell 索引 <nCells、該 cell `group==1` 且
   不重複，全部 `group==1` cell 恰被事件覆蓋一次；event `utilization` 有限
   且 ≥0、`face≤5`、`reserved[7]` 全零。fractureMechanism 每個 (x,y,z)
   必須是 committed owners 中存在的格座標。
5. 判定群（鏡像 `corot_wire::fracture` 判定段）：
   `nEvents ≤ budget`；`nDecisions == nEvents + (pending!=-1 ? 1 : 0)`；
   `pending==-1` 或恰 `== nEvents`（最後一筆判定索引）；
   `nDecisionCells` 由判定 cellFirst/cellCount 鏈結完全覆蓋；
   每筆判定 `microStep==i`（序號即索引）、`member≥0`、`station≥0`、
   `coordinate∈[0,1]`、`utilization` 有限且 `≥1`、`cellCount≠0`；
   `i<nEvents` 時其 cell 窗口必須與事件 i 的窗口等長且逐 cell 相同、
   `utilization==event.utilization`、該批 cell `group==1`；
   `i==nEvents`（pending 筆）時其 cell `group==0`；
   `phaseFirst` 嚴格鏈結。`corotFailures` 恰 `==remaining.nMembers` 筆且
   `failure.member==i` 依序覆蓋；`station≥-1`、`flags≤7`、`reserved==0`、
   `coordinate∈[0,1]`、`utilization≥0`，`(flags&4)⇒(flags&1)&&station≥0
   &&utilization≥1`；failure 的 `phaseFirst` 鏈接續在全部判定之後且完全覆蓋
   `corotPhases`。phase 記錄為 `bsi_corot_phase_failure`（88B）：
   `constituent`（u32）、`flags≤3`、`mode≤3`、`station≥0`、`fiber≥-1`、
   `coordinate∈[0,1]`、`value≥0`、`limit≥0`、`utilization≥0`、
   `failedArea≥0`、`totalArea>0`、`failedArea≤totalArea`、`fraction∈[0,1]`、
   `requiredFraction∈(0,1]`、`(flags&2)⇒(flags&1)`、隱含對齊位元組全零。
   `corotFractureLoads` 恰為 `nCellLoads` 筆、依序與輸入 `bsi_load`(64B)
   逐 byte 相同，`cell` 為 fractureCells 索引、`group==cells[cell].group`、
   且 `input` 的 (x,y,z) 恰為該 cell 的 sourceKey。
   `corotFlags` bit1（reusedInitial）成立僅當請求攜 `initialAnalysis`；
   本單元永不發送，故該位恆為零——置位即違約。
6. 剩餘分析群：鏡像 `corot_wire::analysis`（與 `BsiCorot.AnalysisView` 同一套檢
   查），但世界戳記為 after stamp、`corotSources` 必須逐 byte 等於由 §1.3 推
   得的剩餘世界 canonical block image、每列 artifact>0 且仍持格、disposition=0；
   `corotFailures` 的 member 序與剩餘成員序一致（§5 條）。
7. 封存群（逐行鏡像 `corot_wire::archiveValid`）：`corotArchives`（592B
   `bsi_corot_archive`）：`flags≤7`（hasBefore=1、sourcesEquilibrated=2、
   completeMechanicalFields=4）、`reserved==0`、`artifactNamespace` 等於
   回應命名空間、`after.domain` 非零、`after.revision≥0`；`flags&1` 時
   `before.domain==after.domain && before.revision<=after.revision`。
   封存**不**綁定本交易的 before/after stamp（僅自相一致＋命名空間）。
   `memberFirst/memberCount` 在 `corotRetiredMembers` 上依序鏈結且完全
   覆蓋；`shellFirst/…` 同理覆蓋 `corotRetiredShells`；`removedFirst/
   removedCount` 與緊隨的 `installedFirst/installedCount` 在
   `corotArchiveOwners` 上串接（`installedFirst == 累計 owners +
   removedCount`）並完全覆蓋；`energy[8]` 全部有限且 ≥0；五個
   `bsi_physical_properties`（before/retained/after/removed/installed
   Mass）各自有限、mass≥0、慣性對角非負。
   retired member（1248B）：`archive==歸檔索引`、`member≥0`、`island≥0`、
   `reserved==0`、`cellFirst/cellCount` 依序鏈結且完全覆蓋
   `corotRetiredCells`（member 先、shell 後）、`mechanics` 經
   memberValid（`available≤7`、`active≤1`、`length/storedEnergy≥0`、
   frame+localDisplacement+localAction+action+external 全有限、
   `available&1 ⇒ length>0 && rotation(frame)`）、referenceMaterial[4]/
   referenceSection[10]/referenceVector[3] 有限；每一端（2×）：
   referenceNodes/position 有限、orientation 為旋轉四元數，
   `releases[end].available!=0 ⇒ jointValid`（available==0 的 release
   欄位內容不檢查）。`profile!=-1 ⇒ profile<nRetiredProfiles`、未被
   其他 member 指名、且 `retiredMaterial.profiles[profile].member ==
   此 member 的原索引`；全部 retiredMaterial profiles 必被各 member
   指名恰好一次。retired shell（1624B）同型（`archive`、`shell≥0`、
   `island≥0`、`reserved==0`、cell 鏈、shellValid、referenceMaterial[4]
   有限、四端 referenceNodes/position 有限且 orientation 為旋轉）。
   `corotRetiredCells`/`corotArchiveOwners` 為 20B owner 記錄，各列
   `artifact>0`。retired 材質視圖（`corotRetiredProfiles/Fibers/
   Stations/Points`，恆輸出、可全空）逐列鏡像 `materialValid`；
   退役殼材視圖（`corotRetiredShellProfiles/Layers/TensorPoints`）
   存在時鏡像 `tensorMaterial`（profile.shell 為 retired shell 索引、
   唯一、available∈{0,6,7}、layer 鏈/point 鏈完全覆蓋、point 數
   `==available?4*layerCount:0`、層權重總合=1/一階矩=0/二階矩=1/12、
   逐點張量守門），整組存在或整組缺席；`corotFlags&8` 成立時
   Java 不額外要求該群缺席（native 僅以視圖存在與否編碼，不交叉旗標）。
   `corotFailures` 見 §3.5。
8. `corotFlags` bit2（unrepresented）成立當且僅當任一 physical cell
   `flags&1`；該旗標為資訊性，不影響承諾資格。`end`：0=限內完成、1=不合格、
   2=budget 用盡；物理 `flags` bit0=budget 耗盡、bit1=機構回滾。
   承諾合格條件凍結為 `end==0 && pending==-1 && flags==0
   && (corotFlags&8)==0 && fullyConverged`；其餘情況回傳具名未完成狀態、
   不產出 after 物件、不寫日誌意圖。

## §4 Session 與私有 staging

1. `FiniteSession.fracturePrepare(requestId, rules, budget)`：只在 step∈
   {WORLD,RESTORED} 且無未結候選時允許；capability 先驗 `bsi.corot`、
   `bsi.corot.fracture`、`bsi.fracture`；`source.loads().length>0` 時再驗
   `bsi.corot.fracture.loads`；`source.hasShellMaterials()` 時再驗
   `bsi.corot.shell.materials`；`requestId` 非空 UUID、`budget∈[0,4096]`。
   原生回覆經 §3 全套驗證後成為記憶體候選；任何失敗立刻毒化 session。
2. `FiniteSession.fractureFinish(candidate, commit, afterSource)`：只在同一
   候選存在時允許；走既有 `bsi.fracture.finish` 共享權威，嚴格回聲
   （id/method/revision/requestId/token/basis/namespace；`result.revision`
   恰為 before+1）。`commit` 成立時：`afterSource` 必須與候選的剩餘世界逐格
   逐 owner 一致，session 的 source 切換為 afterSource、`worldRevision` 進位、
   liveToken 清空、state 留在 RESTORED 供 checkpoint export；`discard` 時
   清除候選、世界不動。finish 之後該 session 不得再次 prepareFracture 或
   solve/arc（authority session 的語意留給呼叫者：staging session 用完即關）。
3. `exportCheckpoint` 在 fracture commit 後亦允許（RESTORED + 剛承諾的斷裂）；
   `liveToken==null` 時 arc 一律拒絕——不留「已斷裂卻仍持舊 token」的假狀態。
4. `FiniteFracture.stage(lane, domain, rules, budget, format, byteBudget,
   allocBudget, elemBudget, sessionGeneration, actor, actorProvenance)` 鏡像
   FiniteHistory：讀 committed root+source → 私有 `FiniteSession`（新
   sessionGeneration）→ 私有宣告 vocabulary/world → 有 checkpoint 先 import
   → fracturePrepare → 合格才 fractureFinish(commit) → exportCheckpoint →
   發布 checkpoint 與 afterSource 物件 → 組裝 `Staged{plan(不含雜湊), next,
   retiredOwners, requestFrame, wireFrame, afterSource, afterRef, checkpointRef,
   session}`。失敗即關閉私有 session；native 候選隨 session 關閉即棄。
   不合格 receipt 一律 `fractureFinish(discard)` 後回傳具名未完成。
5. staging 期間不觸動 committed 權威；重試在同一權威上為全新私有重放。
   `initialAnalysis` 首版永遠省略（來源沒有可驗的 live token 綁定），
   不作為功能欄位寫入 wire。

## §5 持久 plan 與日誌操作

1. `FiniteFracturePlan`（`VAL_FRACTURE_PLAN=5`）欄位凍結：domain（journalDomain
   + dimension + bsiDomain + artifactNamespace）、before{worldRevision,
   historySequence, sourceHash, vocabularyHash, ownersHash}、
   after{worldRevision, historySequence, sourceHash, source Ref, checkpoint Ref}、
   actor+actorProvenance、`requestFrameHash`、`wireFrameHash`（兩者為 sha256
   的 hex64，框架位元組另作 chunked Value 存放）、token{context,sequence}、
   steps/flags/remainingBlocks/end/corotFlags/pending/fullyConverged/
   remainingGeneration、beforeCells、retiredOwnership（排序唯一）、bornBodies、
   loadLedger（≤1MiB）、archiveRecords（≤1MiB）。`planHash` 不序列化——解碼時
   由 sha256(編碼位元組) 派生，建構時即等值。`after.worldRevision==
   before.worldRevision+1`、`after.historySequence==before.historySequence+1`、
   domain/vocabulary 雜湊一致在建構時凍結。
2. `Operation.CommitFracture`（tag 7）：`next`、`retiredOwnerIds[]`（升冪唯一）、
   `planHash`、`planParts`、`requestParts`、`wireParts`、`intentHash`。意圖資源
   命名 `finite/fracture/<txid>/{plan,request,wire}/<i>`；`finite/root/<domain>`
   after=`next`；退役 owner 的 `finite/binding` after 改為 RETIRED。
3. `intentHash` 凍結為生效輸入的內容雜湊：sha256 於
   `magic || domain || before{rev,seq,sourceHash,vocabHash,ownersHash} ||
   requestId || actor || actorProvenance || format/byteBudget/allocBudget/
   elemBudget || sha256(requestFrame)`。呼叫者以 Request.planHash 承載；
   `prove` 對 CommitFracture 改驗 `request.planHash()==op.intentHash`。
   Registry 驗證器以 committed 狀態+解碼 plan+重組 requestFrame 全量重算
   intentHash 與 planHash；requestFrame 必須重放為
   `source.fracturePayload(rules)` 的精確位元組；wireFrame 必須通過 §3 全套
   receipt 驗證；plan 各欄位必須與 receipt 逐項一致（stamps、token、steps、
   flags、end、pending、corotFlags、remainingGeneration、beforeCells、
   retiredOwnership、bornBodies、loadLedger、archiveRecords）；next 必須等於
   `before{rev+1, seq+1, afterSource, checkpoint}` 且 checkpoint 物件存在、
   source 物件存在。全部成立才允許 durable；任何一項不符整體拒絕。
4. 退役 owner 集合＝committed owners 中在 afterSource.owners 不再出現者；
   恰好對應 receipt 的 retiredOwnership。重試同 key 由日誌 Request 逐欄位等
   值裁決；同 key 不同輸入為 ReplayConflict；同 input 不同 key 是新交易。
   planHash 不再是輸入綁（線性路徑的 raw receipt 語意由 wireFrame 承繼）。

## §6 Driver、Host 與崩潰矩陣

1. `FiniteFractureHost`（core 介面，Forge 直接實作 §8.2/§8.3 語意）：
   `checkAccess`、`revision`（持久修訂時鐘）、`read/checkpoint/write/flush`、
   `prepareImages(request,staged)`（host 自有參與者如 world cells/碎塊——
   core 預設為空意圖）、`publish(receipt,plan)`（可見發布，恰一次）、
   `publishRecoveredState`、`openLane()`、`sessionGeneration()`。
   任何 `request()` 原生呼叫只准出現在 staging 的私有 lane 上。
2. `FiniteFractureDriver.execute(request, domain, rules, budget, format,
   byteBudget, allocBudget, elemBudget, actorProvenance, host)`：
   先 `journal.read(request.id())`——已存在且 Request 等值→回讀具持久 receipt
   的重放結果（不重新 prepare）；不等值→ReplayConflict。再驗
   `request.planHash()==intentHash(committed basis, inputs)`——basis 已動的
   陳舊請求在此被拒。之後走 `AtomicConstructionCoordinator.execute`：preparer
   內 staging（S0–S4），合格則 `prepareCommitFracture` 產生意圖；不合格
   抛 ValidationRefused→durable REJECTED（同 key 不得重用於不同輸入）。
   Publication adapter 在 durable COMMITTED 成立後才 `applyCommitted` →
   `host.publish` → Outcome 攜 plan+session 供呼叫者採用；原生 finish 已在
   私有 session 內完成，participant write/flush 不含任何原生提交。
3. 崩潰點涵蓋：私有 prepare 前後、finish 前後、checkpoint 輸出前後、物件
   發布前後、journal PREPARED、每次 participant write/flush、durable
   COMMITTED、applyCommitted、host.publish、lost ACK（`publish` 抛錯）。每點
   recover 後：PREPARED→回滾 before-images、不重複移除材料；COMMITTED→
   `registry.sync(journal)` fold-forward 套用未 fold 的 committed 交易
   （order 大於在冊 order 者逐一 `applyCommitted`）→ `publishRecoveredState`。
   兩次獨立重啟均得到同一 fold 結果。
4. preview handle、native candidate token 一律不進日誌為施工權威；token 只以
   「綁定 transaction+before/after stamp」的證據欄位存於 plan 與 wireFrame。
   重啟後不憑 token 提交任何東西——authority session 由 committed
   root+checkpoint 重建。

## §7 測試與變異腿

1. Wire corpus：`FractureFixtures` 提供 5 格世界（GROUND/STEEL/STEEL_B/
   PLATE/STEEL_C，artifact 1/1/2/1）的合法 receipt——cell2 與 cell3 斷裂
   （一 member+一 shell 退役）、artifact2 全退役、含判定/階段/負載/封存各段。
   每條 §3 規則至少一個變異測試：目錄順序/缺失/重複/stride/越界/padding、
   stamp/domain/namespace/requestId/token、cells/events/decision 鏈、phases
   鏈、failures 成員序、load ledger 位元組、archive 鏈與材質視圖、
   unrepresented↔flag、pending 與 decisions 關係、退役 owner 不符、
   剩餘世界與 receipt 不符、rules 衛生。
2. Session/staging：能力缺失拒絕、malformed success 毒化 session、
   不合格 receipt 走具名未完成且權威不動、staging 失敗後 committed 不動、
   finish 回聲不符拒絕、checkpoint export 失敗整體失敗。
3. Registry/driver：同 key 重放、同 key 異輸入 ReplayConflict、陳舊 basis
   拒絕、PREPARED 後崩潰回滾、COMMITTED 後崩潰 fold-forward、publish 中斷
   →recover 發布持久態、退役 binding 恰好被 RETIRED、plan/request/wire 段
   任一篡改在 validatePrepared/fold 全數拒絕（含截斷、未知 tag、trailing）。
4. 既有 74+core 全部回歸；`./gradlew :core:test`、`./gradlew test`、
   `forge/gradlew compileJava` 為准出。原生 DLL/SO/JNA 案例沿用
   FiniteProcessGate：無引擎時具名略過、不假裝通過。

## §8 範圍邊界

- 不改 `Forge/`、不實作真 Level 移除/碎塊/碰撞/W4a 非阻塞 runtime。
- 不削弱既有 W2/W3 驗證；不在 Java 推導力學；來源與封存只搬運原生位元組。
- 規格文件與實作分開提交；不 push、不開 PR；首失敗與原始輸出保留。
