## [MECH] MECH-01 (critical, confirmed)
不同材料的構件不會接合——木樑架在磚柱上會被切成兩座孤島，磚柱回報的 D/C 完全不含樑的荷載
- file: sidecar/main.cpp:305
- impact: 本 PR 新增 timber_beam / brick_pier / concrete_beam 正是為了讓玩家混用；創造模式頁籤把它們並列。最自然的一個組合（木樑架磚柱、混凝土樑架鋼柱）產生的是「模型不是玩家蓋的那個結構」：載重路徑消失、承重構件回報安全、樑本身連結果都沒有。這是專案自己定義的最壞失效（silently safe）。verify.py C15 只各自單獨測了木懸臂與磚柱，沒有任何 gate 測過混合材料接點——牴觸鐵則 2。
- fix: 擷取層加上 MEMBER_SEMANTICS §7.4 規則 2：兩個 run 的端點方塊面相鄰時共用一個節點（新增一個接點節點或直接把相鄰端點 unite 成同一 nodeId）。在那之前，至少要把「屬於 singular 島的方塊」放進 unassigned 或新增一個 droppedBlocks 欄位，讓玩家看得到樑不見了；並補一條 verify.py gate（木樑架磚柱 → islands==1）。
- verify: 親自用 dist/br-sidecar.exe 重現，數字逐一吻合：兩根 3 格 brick_rect_230x350 柱（底部 support）+ 5 格 timber_rect_140x240 樑架柱頂，回覆為 {"singular":true,"islands":3,"singularIslands":1,"members":[兩根磚柱 dc=0.0035315999999999993],"unassigned":[]}，木樑整根消失。equilibrium.applied=[0,-5685.876,0]，我手算兩根磚柱自重 = 2×(1800×80500×1e-9×9.81)×2000 = 5685.88 N，與 applied 完全相同；木樑自重 600×33600×1e-9×9.81×4000 = 791.1 N 確實從未進入加總。dc 反推 = 1800×9.81×2/10e6 = 0.0035316，確認只有自重。全鋼對照組 islands:1、singular:false、三根構件共節點。根因在 sidecar/main.cpp:305-307 `return !isPlate(a.section) && !isPlate(b.section) && a.mat == b.mat;`（run 延續只比材料），bearing 延伸 main.cpp:332-335 

## [DISPUTE] DISPUTE-D (high, upgraded)
作者主張成立：patches 與數值核在整個 PR 區間零改動，既有容差無一放寬；且現行 S9 參考解經獨立核算為正確
- file: docs/GATES.md:85
- impact: 這是五條主張裡證據最硬的一條，且是鐵則 3 的核心測試——「不為了讓 gate 過而動引擎」。可查證的代理指標（patches 零改動、數值核零改動、既有容差零放寬）全部支持。唯一無法查證的是「commit 前修訂兩次」這段工作樹內歷史——git 對 pre-commit 迭代天生無記錄，這種自我登記在制度上是不可否證的，只能靠上述代理指標旁證。這點應該寫進 GATES.md 讓後人知道證據強度的邊界，而不是當成已驗證事實。
- fix: 在 GATES.md:85 那列補一句可查證性註記：pre-commit 迭代不留 git 痕跡，本列的可查證支撐是「sidecar/patches 與 main.cpp 數值核在 abc8e78..HEAD 零改動、verify.py 既有容差零放寬」。日後同類 gate 迭代建議先 commit 首版 gate（哪怕是紅的）再修，讓失敗本身進入歷史。
- verify: 這條裁決分兩半，一半我獨立證實，另一半我用兩個獨立數值方法**推翻**——而被推翻的正是任務指定「最重要」的那半。\n\n【成立的一半：鐵則 3 守住了】我親自驗：`git diff --stat abc8e78 HEAD -- sidecar/patches/` 輸出為空、`git log --oneline abc8e78..HEAD -- sidecar/patches/` 無任何 commit；main.cpp 在 PR 區間只被 87c6114/1174e69/23b0b37 動過，而 S9 gate 落地的 23b0b37 對 main.cpp 的 diff 我逐行讀過，只有刪掉 946-1019 那段死迴圈（第二迴圈 main.cpp:959-1003 逐欄位重寫 Nxx…vmTop/vmBot，Mc/McRaw 由 :1017-1029 的 recoverShellEdgeMoments 覆寫）＋ handleSolveShm 兩個 `if (!rd.ok) break;`。`git diff abc8e78 HEAD -- sidecar/verify.py | grep '^-[^-]'` 只有 9 行刪除，全是 check() 語意重寫與 shm 呼叫改簽名，**沒有任何既有容差被放寬**（新增的 check( 有 5 條不是裁決者說的 3 條，但全是新增）。

## [DISPUTE] CI-PIPE (high, confirmed)
新增的 CI 引擎 gate job 恆綠：verify.py 的退出碼被 `| tail -3` 吃掉，219 條引擎 gate 全紅 CI 仍會過
- file: .github/workflows/ci.yml:28
- impact: 這條抽掉了整個 PR 最核心的支撐。GATES.md 末段寫「這批 gate 的牙齒以 CI（.github/workflows/ci.yml，同日上線）為前提——CI 綠是 merge 條件」，FIX_ROLLUP 6.5 也寫「前提：0.1 的 CI 先上，否則以上全部只是『檔案在』」。實際上 engine-gates job 對 verify.py 的任何失敗（gate 紅、Python traceback、甚至 br-sidecar 根本跑不起來）都回報成功——連 `python3` 不存在都會綠。也就是說本 PR 宣稱的「219/219」在 Main 之後沒有任何自動化把關，正好落回鐵則 2 要防的「檔案在不算有」。另外此 job 只跑 Linux，Windows 原生那次是人工跑的。
- fix: 移除 `| tail -3`（或改成 `python3 sidecar/verify.py dist/br-sidecar | tail -3; exit ${PIPESTATUS[0]}`），並在該 step 明寫 `shell: bash` 以取得 `pipefail`。建議同時在 ci.yml 頂層加 `defaults: run: shell: bash`，讓往後所有 step 都拿到 `-eo pipefail`。合併前應該實跑一次刻意讓 verify.py 失敗、確認 CI 會紅。
- verify: 實查屬實。.github/workflows/ci.yml 全檔 47 行我讀過，engine-gates job 的 step 是 `run: |` 兩行，第二行 `python3 sidecar/verify.py dist/br-sidecar | tail -3`（:28），該 step 無 `shell:`，全檔無 `defaults: run: shell:`。GitHub Actions 在 Linux runner 的預設是 `bash -e {0}`，不含 pipefail。我在本機實測：`bash -e -c 'false | tail -3; echo "no-pipefail exit=$?"'` → `no-pipefail exit=0`；`bash -eo pipefail -c '…'` 則直接中止（無輸出，rc≠0）。verify.py 確實會回非零：檔尾 :1383-1385 `if fails: … return 1`、:1391 `sys.exit(main())`。所以整條 gate job 對 verify.py 的任何失敗都回報成功。對照組 release.yml:58 的判定命令在管線末端 `unzip -p … | grep -Eq … || { … exit 1; }`，寫法是對的，證明這是單點疏忽。影響評估我同意且要加碼：GAT

## [FLOW] DF-01 (high, confirmed)
爆炸／活塞／指令／WorldEdit 改動結構方塊完全不 markDirty：結構變了但分析不重跑，且連 stale 標示都不會出現
- file: forge/src/main/java/com/blockreality/impl/server/StructureManager.java:288
- impact: 一根柱子被 TNT 炸掉後：(a) revision 不動 → tick :367 的 `sendAnalysisPending` 不會發 → client 的 `stale()` 為 false，HUD 把舊結果當「當前」畫；(b) `dirty` 不動 → :381 `if (!dirty || ...) return;` 直接返回，永遠不重算；(c) 被炸掉的方塊還留在 `structural` 裡，連 `visitForCycle` 的 stale 清理（:411-415）都不會被叫到，因為那需要先有一次 gather。淨結果是玩家看到一個「安全」的 D/C，而那棟樓已經少了一根柱子——這正是不變式 6 要防的「玩家以為結構還安全」。onChunkLoad 兜底只在 chunk 卸載後重載才生效，在同一次遊玩中通常不會發生。
- fix: 至少補 `ExplosionEvent.Detonate`（對 `getAffectedBlocks()` 交集 `structural` 就 markDirty）與 `PistonEvent.Post`（推/拉會移動方塊位置，tracked 位置必須跟著移或至少 markDirty）。`/fill`、`/setblock`、WorldEdit 這類無事件路徑沒有通用解，但可以每 N 秒對 `structural` 抽樣驗證一小批位置（例如每 tick 驗 64 個，走現成的 `GatherCycle` 節奏），發現不符即 markDirty——成本與現在的 gather 同量級。無論怎麼修，docs 與 :297-308 的 javadoc 必須改成誠實敘述目前不涵蓋哪些路徑。
- verify: 親自核對 StructureManager.java:265/266 onPlace、:287/288 onBreak、:309/310 onChunkLoad——確為僅有的三個世界事件入口，行號完全吻合。全 repo `grep -rn "ExplosionEvent|PistonEvent" forge/src/main` 零結果。StructuralBlock.java 全檔 32 行（:18-32）只有 materialToken/sectionToken，無 onRemove/neighborChanged 覆寫。BRContent.java 九處 `BlockBehaviour.Properties.of()...strength(3.0f, 6.0f)`（:56/58 等），`grep pushReaction` 零命中 → 預設 PushReaction.NORMAL，活塞可推；爆炸抗性 6.0 等同石頭，TNT 可炸。Forge 1.20.1 的 BlockEvent.BreakEvent 只從 ServerPlayerGameMode.destroyBlock 觸發，爆炸走 Level.removeBlock/onBlockExploded，兩者不重疊。後果鏈也核實：markDirty()（:259-262）是 gate.bump()+dirty=true 的唯

## [FLOW] DF-02 (high, downgraded)
死亡重生／切維度清空 client state，但 server 沒有任何重播路徑：疊層永久空白，且 javadoc 的緩解說法是假的
- file: forge/src/main/java/com/blockreality/impl/client/ClientEvents.java:377
- impact: 玩家死一次、或 Overworld→Nether→Overworld 走一趟（建築在出生點常駐 chunk 內、不會觸發 ChunkEvent.Load），HUD 就變成「No analysis yet」、世界疊層完全消失，而 server 的 `/br status` 同一秒還印著「12 members, max D/C 0.83」。要救回來只能去放一顆方塊或 `/br resolve`（後者要 OP）。這是本 PR 新增 ClientEvents 造成的可重現退化：修 #41（跨維度畫錯）是對的，但沒補上另一半，把「畫錯」換成了「永遠不畫」。多人伺服器更明顯：玩家 A 死亡重生時，就算玩家 B 站在建築旁邊讓 chunk 常駐，A 也拿不回資料。
- fix: 加一個 server 端 `PlayerEvent.PlayerLoggedIn` + `PlayerEvent.PlayerChangedDimension` + `PlayerEvent.PlayerRespawnEvent` 的 handler，對該玩家所在維度的 manager 做「若 `latest != null` 且非 stale，就對這一個玩家 `CHANNEL.send(PacketDistributor.PLAYER...)` 重送一次；否則發一次 `AnalysisPendingPacket`」。BRNetwork 已經是逐玩家 send（:78-80），改成單播只是換一個 for 迴圈。在補上之前，ClientEvents 的 javadoc :356-357 那句必須刪掉——它現在是文件在替不存在的機制背書。
- verify: 問題屬實但行號錯且後果偏重。ClientEvents.java 全檔只有 42 行，onLoggingOut 在 :28、onClone 在 :39、被指控的 javadoc「State is cheap to rebuild (the server re-broadcasts on the next solve)」在 :16-18——報告寫的 :377/:366/:356-357 全部不存在（疑似把 ClientStressState.java 的 338 行連號了）。引用的字句本身逐字屬實。機制核實：clear() 在 ClientStressState.java:229-251 把 revision/pendingRevision 歸 -1；BRNetwork.sendResult 唯一呼叫點是 StructureManager.java:541，受 :381 `if (!dirty || ...) return;` 節制；`grep -rn "PlayerLoggedIn|PlayerChangedDimension|PlayerRespawn" forge/src/main` 零結果，確無重播路徑。但降為 medium：失效是 fail-safe（HUD 顯示 br.hud.no_data，不是錯資料），且復原門檻很低——onChunkLoad（:310-313）在玩家走

## [FLOW] DF-03 (high, confirmed)
任何 !ok 結果之後 dirty 都不會回置：引擎逾時／退避（RECOVERING）之後沒有任何自動重試驅動者
- file: forge/src/main/java/com/blockreality/impl/server/StructureManager.java:524
- impact: sidecar 逾時一次（大模型第一次 solve、磁碟忙、機器負載尖峰都可能），該維度的分析就此靜止到玩家下一次編輯世界為止。整套 backoff／maxRestarts／RECOVERING 狀態機在正常遊玩下等於沒有驅動者——`consecutiveFailures` 幾乎不可能自然累積到 `maxRestarts`。玩家看到的是 HUD 永遠停在「engine unavailable」，而 server 什麼都不再嘗試。
- fix: 在 UNAVAILABLE 分支加 `dirty = true;`，並用 `ticksSinceSolve` 之外的獨立退避計數避免打爆（例如失敗後把 `ticksSinceSolve` 設成 `-(backoffTicks)`，或加一個 `retryAtTick` 欄位）。重點是失敗必須是「稍後再試」而不是「就此不動」。
- verify: 逐行核實無誤：StructureManager.java:524-527 `if (display == UNAVAILABLE) { latest = result; BRNetwork.sendEngineStatus(...); return; }` 確實不設 dirty；唯一設 dirty=true 的失敗分支是 :534 的 stale 分支；beginCycle 在 :394-395 開頭就 `dirty = false`。RevisionGate.java:74 `if (!r.ok()) return Display.UNAVAILABLE;` 使任何 !ok 都走 :524。SidecarClient.java:459-471 failAndRestart 設 `status = RECOVERING; nextAttemptAtMs = now + backoffMs(...)`，而 :390 `if (System.currentTimeMillis() < nextAttemptAtMs) return false;` 只在下一次 solve 才被讀到。且比報告更完整的是：stale 的失敗也會先在 :524 返回，連 :534 都到不了。high 恰當——一次逾時就讓該維度分析靜止到下次世界編輯，整套 backoff/maxRestarts(=4，見 :1

## [FLOW] DF-04 (high, confirmed)
引擎「拒絕這個模型」（REFUSAL）被當成「引擎不可用」顯示，且 engineStatus 存的是 "READY" 這個非空字串
- file: forge/src/main/java/com/blockreality/impl/client/ClientStressState.java:262
- impact: 三重錯：(a) 引擎明明 READY，HUD 說「engine unavailable」，`/br status` 卻同時印綠色的「engine READY」——兩個消費者對同一件事給相反答案；(b) `latest` 被寫成 failed 結果（:525），`/br members`／`/br section` 的 `if (r == null || !r.isUsable())`（BRCommand.java:233/272）從此回「No usable analysis」，上一份好結果整個消失，而世界疊層還在畫舊資料——server 說沒資料、client 在畫資料；(c) 疊加 DF-03，`dirty` 不回置，狀態黏住直到玩家自己想到要把荷載拿掉。SnapshotLoads 的 javadoc（:196-199）自己描述過這個失效模式（「the whole dimension's analysis silently off with no error a player can act on」），但只堵了未載入 chunk 那一半，孤立方塊那一半沒堵。
- fix: 把 REFUSAL 與 transport failure 分成兩種封包語意：REFUSAL 是「模型被拒，原因是 X」，該走一個 model-rejected 訊息（不清空、不宣稱引擎壞掉、`latest` 保留上一份好結果或至少讓 `/br members` 能說「上一份結果來自 revision N」）；只有 `status != READY` 才配叫 engine unavailable。最小修法：`StressHud.java:72` 改成判斷 `!"READY".equals(engineStatus())`，並在 `apply` 對 REFUSAL 不覆寫 `latest`。
- verify: 三重錯全部復現。(a) SidecarClient.java:225-227 `case REFUSAL -> AnalysisResult.failed(rev, t.detail());` 註解明寫「No restart and no failure count」→ status 維持 READY；StructureManager.java:526 `sendEngineStatus(level, sidecar.status(), ...)`；BRNetwork.java:84 `new EngineStatusPacket(status.name(), detail)`；ClientStressState.java:262 `engineStatus = p.status();`；StressHud.java:72 `if (!ClientStressState.engineStatus().isEmpty())` → 字串 "READY" 非空，印 br.hud.engine_unavailable 並 return。同時 BRCommand.java:161 對 `s == READY` 上 ChatFormatting.GREEN。(b) :525 `latest = result;` 覆寫，BRCommand.java:234/273 `if (r == null 

## [ABUSE] ATK-01 (high, confirmed)
CI 的引擎驗收 gate 沒有牙齒：verify.py 的失敗退出碼被 `| tail -3` 吞掉
- file: .github/workflows/ci.yml:28
- impact: 整個引擎驗收套件（verify.py，含 T-cap、TS 截斷、shm parity 等全部 gate）在 CI 裡永遠 pass。ci.yml:1-3 自述「Green here is a merge condition (#46): a change that reaches Main without this passing is a capability claim without a gate」，docs/GATES.md:98 也寫「這批 gate 的牙齒以 CI 為前提——CI 綠是 merge 條件」。實際上這條 job 從第一天起就無法變紅。這正好命中 CLAUDE.md 鐵則 2（沒有 gate 執行過的能力不得寫進能力清單）：本 PR 的「gate 已上線」宣稱，對這一半而言不成立。log 裡看得到 FAILED 字樣，但 merge 保護看不到。
- fix: 改成 `set -o pipefail` 或直接拿掉管線：`python3 sidecar/verify.py dist/br-sidecar` （要縮短輸出就 `> verify.log || { tail -40 verify.log; exit 1; }`）。順手在 workflow 頂層加 `defaults: run: shell: bash`，讓全檔都吃 `-eo pipefail`。合併前務必用一個故意失敗的 case 驗證這條 job 真的會紅。
- verify: 逐項復核成立，且是本 PR 新增的缺陷。(1) ci.yml:25-28 確為 `python3 sidecar/verify.py dist/br-sidecar | tail -3`，全檔（1-47 行，已完整讀過）無 `shell:` 也無 `defaults:`；`grep -rn "shell:|defaults:" .github/workflows/` 回 NONE。(2) 我在本機實跑驗證了 shell 語意：`bash -e -c '<exit 1> | tail -3'` → exit 0；`bash -eo pipefail -c '<exit 1> | tail -3'` → exit 1。GitHub Actions 未指定 shell 時用 `bash -e {0}`（不含 pipefail），只有明寫 `shell: bash` 才是 `bash --noprofile --norc -eo pipefail {0}`。(3) verify.py:1383-1391 失敗確實 `return 1` 並經 `sys.exit(main())` 傳出——但被 tail 吞掉。(4) 加重情節：`git diff --stat 593ef2ae..HEAD -- .github/workflows/ci.yml` 顯示 +47 行、`git log 593e

## [ABUSE] ATK-07 (high, upgraded)
SERVER config 的 sidecarPath 決定伺服器要執行哪個二進位，而 SERVER config 隨世界存檔散佈
- file: forge/src/main/java/com/blockreality/impl/server/SidecarLocator.java:42
- impact: 世界存檔 / 整合包在使用者心智裡是「資料」，不是「程式碼」。一個下載來的地圖只要夾帶 serverconfig/blockreality-server.toml，玩家開啟世界時本 mod 就會啟動指定的執行檔（同一使用者權限）。同機的其他程式若能寫進 PATH 上任一目錄或遊戲目錄（SidecarLocator.java:57、70 的後備順位），效果相同。這不是本 PR 新增的，但這是「攻擊面盤點」該登記的最大一條，且沒有人審過。
- fix: 至少做到：(1) 啟動前把解析出的絕對路徑印進 log 與 /br status（OP 視圖）——現在只有 constructor 那一行，且沒有「這是從 world save config 來的」這個來源標記；(2) 拒絕 UNC / 網路路徑；(3) 更強的做法是讓 sidecarPath 只認 COMMON/檔案系統 config（不隨存檔走），世界存檔只能停用不能改路徑。並在 README/START-HERE 明寫「開啟他人存檔前檢查 serverconfig」。
- verify: 【註：orchestrator 把這條稱作「ATK-01 critical」，但 .audit_rescue/pr26_merged.json 的 ABUSE.judged 裡 sidecarPath 這條是 ATK-07、原評 medium；ATK-01 是 CI 的 `| tail -3`。以下用 JSON 的 id。】全鏈逐步查證，每一步都成立，且比原評更嚴重——但不到 critical。

(1) 註冊型別：BlockRealityMod.java:32-35 `registerConfig(ModConfig.Type.SERVER, BRConfig.SPEC)`；sidecarPath 定義在該 spec 內（BRConfig.java:19、28-33）。
(2) SERVER config 的實際位置（Forge 1.20.1 原始碼，非推測）：fmlcore ModConfig.java:115-122 官方註解「Server type config ... Stored in a server/save specific "serverconfig" directory. Synced to clients during connection.」；forge ServerLifecycleHooks.java:74 `LevelResource SERVERC

## [RENDER] R-01 (high, confirmed)
板的等值圖每個方塊有 3/4 面積是被夾住的邊界值，而程式註解宣稱「每個頂點都落在同一片 facet」——恰好相反
- file: forge/src/main/java/com/blockreality/impl/client/StressSurfaceRenderer.java:195
- impact: 每一片板方塊的可見表面有 3/4 的面積不是用它自己位置的應力上色，而是用被夾到 facet 邊界（節點連線）的值，而且四片相鄰 facet 中只採用了索引最小的那一片。後果不是「邊緣一圈不準」（ShellMesh javadoc 宣稱的範圍），而是整片樓板的內部都不準：在夾支邊附近彎矩一個元素內就掉掉一半，被凍結的那半格會把支承熱區畫成半格寬的死平台，而且左右不對稱（只往索引小的方向取值）。玩家看到的板應力分布在幾何上系統性偏移約半格，峰值位置與梯度都被抹掉。牆與樓板交界的共用方塊同樣是 score=0 平手，由清單順序決定歸誰——ShellMesh.java:43-45 宣稱「a floor and the wall meeting it do not steal each other's blocks」在角點方塊上不成立。
- fix: 改成 per-vertex 定位（每個網格頂點各做一次 locate，或直接用頂點所在的 1/4 象限去選對應的 facet），或在 locate 平手時改用「in-plane 距離最小」而非 score 嚴格小於；最低限度也要把 StressSurfaceRenderer.java:193-194 與 ShellMesh.java:16-25 的註解改成事實，因為現在它們正好說反，會誤導下一個維護者不去查這裡。
- verify: Geometry re-derived from source and it holds. sidecar/main.cpp:617 `frame::Node n(id, p.x*kBlockMm + h, ...)` with h=500 puts nodes at block centres, and main.cpp:193-197 says facet corners are those same block centres. So for block (3,·,3) the centre (3500,·,3500) is a shared corner of four facets whose centres are (3000,3000)/(4000,3000)/(3000,4000)/(4000,4000); ShellFieldSpec.java:84-92 edgeHalf gives halfX=halfY=1000/2=500, so paramAt (line 103-110) returns |xi|=|eta|=1 for all four. ShellMesh.java:57-60 then yields overX=overY=0, off=0, score=0 four times, and line 62 `if (score < bestSco

## [FIXREL] CI-1 (high, confirmed)
CI 的引擎驗收 gate 永遠不會紅：`| tail -3` 在 GitHub 預設 shell 下吃掉 verify.py 的 exit code
- file: .github/workflows/ci.yml:28
- impact: 整個 218 項引擎驗收套件在 CI 中形同不存在：dist/br-sidecar 就算換成一顆全部 gate 都紅的二進位，engine-gates job 照樣綠。而 ci.yml:1-3 的檔頭、docs/GATES.md:98、docs/RELEASING.md:60-61、docs/FIX_ROLLUP_2026-08-20.md:203 全都把「CI 綠」當成這批 gate 的牙齒。這正是 CLAUDE.md 鐵則 2「沒有 gate 執行過的能力，不得寫進能力清單」要防的失敗模式，只是這次是 gate 自己失去牙齒。
- fix: 在該 step 加 `shell: bash`（會帶 `-eo pipefail`），或改成不用管線：`python3 sidecar/verify.py dist/br-sidecar > verify.log || { tail -40 verify.log; exit 1; }; tail -3 verify.log`。後者的附帶好處是失敗時看得到哪幾項紅，`tail -3` 只印 ALL PASS 反而在失敗時最沒資訊。建議同時加一個負向自我測試（餵一顆刻意壞掉的 binary，確認 job 會紅）。
- verify: 親驗屬實。.github/workflows/ci.yml:25-28 該 step 無 `shell:` 鍵，內容確為 `chmod +x dist/br-sidecar` 與 `python3 sidecar/verify.py dist/br-sidecar | tail -3`。GitHub Actions 未指定 shell 時用 `bash -e {0}`（不含 pipefail；pipefail 只在明寫 `shell: bash` 時加）。我在本機實測：`printf 'false | tail -3\n' > t.sh; bash -e t.sh` → EXIT=0，`bash -eo pipefail t.sh` → EXIT=1，與報告一致。verify.py 的失敗回傳路徑也核對過：sidecar/verify.py:1383-1385 `if fails: print(f"FAILED …"); return 1`，:1391 `sys.exit(main())`——回傳 1 但被 tail 吃掉，且該行是 script 最後一條指令，故 step 恆綠。對照組 scripts/package.sh:24 `set -euo pipefail` + :54 同款管線確為 fail-closed，同一寫法兩支腳本一對一錯屬實。情境「引擎數值回歸但傳輸正常」下

## [MECH] MECH-02 (high, confirmed)
兩端落在地上的樑 = 零自由度 → 被判成「機構，沒有東西撐住它」，而且 note 謊稱沒有擷取到構件；文件卻正好教這個案例
- file: sidecar/main.cpp:803
- impact: 懸臂之後最自然的第二個建造動作，必然失敗，而且錯誤訊息把「過度約束」講成「完全沒有支承」，會把玩家推去做完全相反的補救（再加支承）。出貨文件教的案例做不出來，直接違反鐵則 2/3。
- fix: 最小修法：把 `fully constrained` 和 `mechanism` 在 wire 上分成兩種狀態（例如 `fullyConstrained:true`），HUD 給不同文案；並修掉 note 的假話（區分「沒擷取到」與「島被丟棄」）。根治：讓 run 內部至少保留一個非支承節點（例如支承方塊只在 run 端點成節點，或改成部分固定度，見 MECH-06）。
- verify: 完整重現。9 格 steel_rect_200x400、兩端方塊 support、無荷載，回覆逐字為 {"singular":true,"islands":1,"singularIslands":1,"diagnostic":"fully constrained (no free DOF)","nodes":2,"note":"no members or plates extracted","members":[],"maxDC":0}——與 evidence 完全一致。程式證據全部核對無誤：sidecar/main.cpp:803-808 `if (r.singular) { out.singular = true; ++out.singularIslands; ... return true; }` 把 fully-constrained 併進 singular 早退並丟棄構件；main.cpp:619 `if (blk != grid.end() && blk->second.support) n.fixAll();`；main.cpp:1193-1195 `if (out.members.empty() && out.shells.empty() && out.error.empty()) out.error = "no members or plates extracte

## [MECH] MECH-03 (high, upgraded)
bearing 規則在三個軸都會觸發：樑只要貼著板（側面或下方），就會被憑空長出 1 m 構件並被打碎成 1 m 段，自重灌水 2.3 倍
- file: sidecar/main.cpp:332
- impact: 「板下面放樑」是這個 mod 最常見的建造動作之一。送進引擎的模型不是玩家蓋的結構：質量灌水兩倍以上、剛度被憑空的短構件鎖死（樑的彎矩因此被嚴重低估，不安全方向），而且每根構件都掉到樑理論適用範圍以外。回覆裡沒有任何線索。
- fix: bearing 延伸只在「run 方向」上做（目前已是），但要排除「run 長度為 1、只靠 bearing 才湊到 2 格」的情況——那不是承壓，是側貼。建議：只有 `run.size() >= 2` 時才允許 bearing 延伸；並補 verify.py gate（樑貼板 → 構件數與自重不變）。
- verify: 問題存在且比報的嚴重。先確認 evidence：4×4 concrete_slab_200（四角 support）單獨解 applied=[0,-41496.30000000001,0]；把 4 格 steel_rect_200x400 放在板下 y 差 1 後，member 數 = 7，blocks 分別為 [[0,4,0],[1,4,0]]、[[1,4,0],[2,4,0]]、[[2,4,0],[3,4,0]] 三根水平段 + [[i,4,0],[i,5,0]] 四根憑空垂直短柱，applied=[0,-84621.06,0]，差值 43124.76 N = 6.16068 N/mm × 7000 mm，對照玩家意圖的一根 3 m 樑 18482 N，正是 2.333 倍。橫向貼合（樑在板旁同層 z=-1）我也跑了，同樣長出 4 根憑空橫向構件、applied 一模一樣，直接推翻 main.cpp:286-288 自己的註解「a beam merely running ALONGSIDE a slab is not attached to it」。根因確認在 main.cpp:332-335 的 bearing 延伸發生在 main.cpp:339 的 `if (run.size() >= 2)` 之前，所以長度 1 的側貼 run 被補成 2 格而存活。升級理由：審核者只說「

## [MECH] MECH-10 (high, upgraded)
DisplayTrackPrecisionTest 是套套邏輯：它只驗 IEEE-754 binary32 的性質，完全沒有碰到任何專案程式，卻自稱是不變式 5 的 gate
- file: mod/core/src/test/java/com/blockreality/core/DisplayTrackPrecisionTest.java:53
- impact: 這是本 PR 為了補上「不變式 5 沒有測試」而新增的東西，但它擋不住任何回歸。之後若有人改動封包編碼或 client 重建公式，這條 gate 不會響，而 CLAUDE.md 鐵則 2（沒跑過 gate 的能力不得宣稱）會被一個看起來有跑的 gate 繞過。
- fix: 改成端到端：建一個 MemberSnapshot/StressFieldSpec → StressResultPacket.encode → decode → 重算 sigmaAt，比對 server 端 double 的同一點值，斷言 rel ≤ 1e-5。並挑幾個中性軸附近的取樣點（sigma 接近 0 的 x/y）作為最壞情況。
- verify: 套套邏輯屬實，且波及範圍比審核者報的大。DisplayTrackPrecisionTest.java:52-58 唯一的斷言來源是 `double roundTripped = (float) value; double rel = Math.abs(roundTripped - value) / Math.abs(value); assertTrue(rel <= 1e-5, ...)`；整檔只 import org.junit（line 3、5），不呼叫任何 com.blockreality 類別；兩個 @Test 的取樣範圍是 exp -6..12 與 1.0 附近的邊界值，全部是 normal double，2^-24≈6.0e-8 恆 < 1e-5 —— 斷言在該取樣集上不可能失敗（javadoc line 17-18 自己把這個算式寫出來了）。審核者指出的實際編碼路徑也核對無誤：StressResultPacket.java 有 36 個 `buf.writeFloat((float) ...)`，集中在 205-325；client 重算公式在 StressFieldSpec.java:84-88 `s -= axialAt(x)/area; s += mzAt(x)*yMm/iz; s -= myAt(x)*zMm/iy;`（引用的 86-88 精確）。升級理由是審

## [UNK] F1 (high, confirmed)
#38 的封殺只補了一半：荷載落在「本輪有收進、但引擎形不成元素」的方塊上，整維度分析照樣熄燈
- file: forge/src/main/java/com/blockreality/impl/server/SnapshotLoads.java:53
- impact: 放一顆結構方塊、蹲下右鍵眼鏡（這正是 lang 檔 br.hint.first_use 教玩家做的第一件事），該維度的每一次 solve 都回 ok:false，整個結構分析功能停擺，直到荷載被移除為止。SnapshotLoads 的 javadoc（:19-21）宣稱這條規則就是防「the whole dimension's analysis silently off」的答案，但同一個熄燈只是換了觸發條件。SnapshotLoadsTest 三個案例（IN / UNLOADED / GONE）沒有一個涵蓋「included 但形不成元素」，所以回歸也擋不住。
- fix: 三選一：(a) 引擎在 reply 裡回報 unassigned 集合（已經有 AnalysisResult.unassigned），forge 端把落在 unassigned 的荷載在下一輪 request 裡暫時排除並在 HUD/chat 明說「這顆方塊還沒形成構件，荷載暫不計入」；(b) 加荷載的入口（toggleLoad / setLoad）先做最小可行性檢查——同軸相鄰至少有一顆同 section 的結構方塊，或本身是 plate 且能封閉 facet；(c) 至少把 SnapshotLoads 的 javadoc 改成陳述真實保證，並補一個 forge 側測試釘住「單顆方塊 + 荷載」的預期行為。
- verify: 親自沿路徑走過，全部屬實。sidecar/main.cpp:1129 的 nodeBlocks 是 runSolve **區域**變數，只由 :1132-1133（每個 seg 的頭尾）與 :1137（quad 四角）填入——與 extractRuns 內那個同名的 :352 nodeBlocks（有 :368-369 `for (const BlockPos& p : loadBlocks) if (grid.count(p)) nodeBlocks.insert(p);` 把載重方塊變節點）是兩個不同的集合。載重方塊只有在它被 :338 `if (run.size() >= 2)` 收成 rawRun、再被 :400 `if (seg.blocks.size() >= 2 && !seg.section.empty())` 收成 seg 之後，才會成為 :1132 的頭尾之一。因此單獨一顆 steel_beam（run.size()==1）落進 :420 unassigned，不在 :1129 的 nodeBlocks，:1148-1155 全域 fail-closed 直接 out.ok=false 回整批拒絕——而且 :1157 那段「segs.empty() && quads.empty() → ok:true」的早退刻意排在載重檢查**之後**，所以連「空結構 + 一個

## [UNK] F3 (high, upgraded)
GatherCycle 的「跨 tick 不會混兩個世界狀態」保證比 javadoc 宣稱的弱：支承條件讀的是不會 bump revision 的非結構方塊
- file: forge/src/main/java/com/blockreality/impl/server/GatherCycle.java:24
- impact: 兩層後果。(1) 跨 tick 的 gather 中途把地基挖掉，先訪問的方塊帶 support=true、後訪問的帶 false，同一個 K 混了兩個世界狀態，而因為 revision 沒動，這個結果會通過 isStale 並被 acceptForCommit 當成 CURRENT 提交（承諾軌，不變式 6 的消費者）。(2) 更常見的：把結構物底下的地基整片挖掉，模型永遠不會重新分析（要等到有人再動一次結構方塊或跑 /br resolve），玩家看到的仍是「有支承」的答案。GatherCycle 是這次為了可測性新抽出的 seam，它的保證陳述目前是不成立的，而 GatherCycleTest 全部用整數當 item、完全碰不到這條路徑。
- fix: 要嘛把支承來源納入 revision（對結構方塊正下方的方塊變動也 markDirty，例如監聽 BlockEvent 不做 StructuralBlock 過濾、只比對是否為某個 tracked pos 的下方），要嘛在 finishCycle 前對整份 included 重讀一次 isSupported（成本 O(n) 一次，且落在同一 tick 內），要嘛把 GatherCycle 的 javadoc 改成誠實陳述：一致性只覆蓋結構方塊集合，支承旗標是 best-effort。
- verify: 事實全部屬實，但後果比 medium 重。GatherCycle.java:22-24 的保證原文確實是「a finished request never mixes two states of the world」（報告寫 :24-28，實際 :22-27，偏 2 行，不影響）。StructureManager.java:268 `if (!(e.getPlacedBlock().getBlock() instanceof StructuralBlock)) return;` 與 :290 `if (!(e.getState().getBlock() instanceof StructuralBlock)) return;` 兩個 listener 確實先過濾；我把全檔 markDirty/bump 呼叫點列完（:117-118 建構、:174 scan、:222 toggleLoad、:240 setLoad、:249 clearAllLoads、:271 onPlace、:294 onBreak、:313 onChunkLoad），**沒有任何一處對非結構方塊的變動 bump revision**。而 :495-501 isSupported 讀的正是 `pos.below()` 的非結構方塊（`if (under.getBlock() instanceof Structur

## [GATE] GATE-1 (high, confirmed)
CI 的引擎 gate 被 pipe 吞掉退出碼——verify.py 全紅仍然綠燈合併
- file: .github/workflows/ci.yml:28
- impact: 本 PR 的整個公信力架構（「gate 綠是 merge 條件」、鐵則 2「沒有 gate 執行過的能力不得寫進能力清單」）在 CI 這一層是失效的。任何造成 verify.py FAIL 的變更——換 dist/br-sidecar 二進位、改協定、改 verify.py 本身——都會以綠燈進 Main。java job 沒有 pipe，仍然有牙齒；engine-gates job 完全沒有。
- fix: 改成不 pipe（`python3 sidecar/verify.py dist/br-sidecar`），或在 step 加 `shell: bash` + `set -o pipefail`，或用 `run: |\n  set -o pipefail\n  python3 ... | tail -3`。合併前應該先讓 CI 跑一次故意失敗的版本，確認它會紅。
- verify: Verified verbatim. .github/workflows/ci.yml:26-28 is `run: |` / `chmod +x dist/br-sidecar` / `python3 sidecar/verify.py dist/br-sidecar | tail -3`, with no `shell:` key and no `set -o pipefail` anywhere in the file; GitHub's default runner shell is `bash -e {0}` (no pipefail), so the step's exit code is tail's. I reproduced it locally: `bash -e -c 'python -c "...sys.exit(1)" | tail -3; echo exit=$?'` printed the FAILED line then `exit=0`, `step_status=0`. verify.py does return 1 on failure (sidecar/verify.py tail: `if fails: print(f"FAILED {len(fails)}..."); return 1`, `sys.exit(main())`), so 

## [GATE] GATE-8 (high, upgraded)
TORSION 是可達且會 governing 的 D/C 模式，全套 gate 零覆蓋；手算與引擎差約 20%
- file: sidecar/verify.py:297
- impact: 「一個 L 形加出平面載重」是玩家最早會蓋的東西之一，而在這個結構裡 D/C 的 governing 值來自一個從未被任何 oracle 檢查過的公式。README.md:132 把「per-member and per-plate D/C」列進能力清單，鐵則 2 要求能力必須有 gate 跑過。同理 SHEAR 模式（station 的 tau = k·V/A，我量到 1.7460893 = 1.5×93124.76/80000 完全正確）也沒有任何 gate 比對過。
- fix: 把 C6 的 L 形加一個 fy 載重，斷言 `T = P·L_z + w·L_z²/2`（純靜力學、不需要知道 α），再把 dc 對某個明示的 α 值鎖住，並在 docs 裡寫清楚採用的是哪個係數。這樣至少「引擎用的是 α=0.205 還是 0.246」變成一個登記過的決策而不是無人知曉的數字。
- verify: Confirmed and materially worse than reported: I resolved the question the finding left open. Coverage claim is true — no assertion in verify.py or scripts/evidence.py touches T or a torsional D/C (the only hits are the shm decoder at :1120 and the enum name string near :1550). Two transcription errors in the finding that do NOT change the substance: the C6 fixture at verify.py:298-301 is 4 blocks along +X (range(4)), not 5, and a load at (4,64,3) is refused by the engine ('load at (4,64,3) is on no structural element'). With the load at (3,64,3) I reproduce the reported numbers exactly: T = 17

## [JARPROV] BR-DIST-01 (high, confirmed)
發行鏈對 dist/br-sidecar.exe 零 gate：沒有 mingw 的機器可以打包出「沒有 Windows 引擎」的 release，而所有 gate 仍全綠
- file: C:/Users/wmc02/Desktop/block-reality/.claude/worktrees/pr26-head/scripts/package.sh:65
- impact: 在缺 mingw 的機器上跑 scripts/package.sh，會產出一份「只有 Linux 引擎」的 dist/，而 sha256sum、版本一致性、evidence、CI 四道 gate 全部照樣綠燈。發布後 Windows 玩家（Minecraft 的絕大多數）執行 install.bat 只會看到「警告：旁邊沒有 br-sidecar.exe」，mod 載入但分析永久關閉 —— 而 release 頁面正在承諾 Windows 引擎。同樣的洞也讓「舊的 .exe 混在新的 dist 裡」無法被偵測：.exe 是唯一一個沒有任何雜湊被記進 evidence 的出貨物。本次我用手動執行證明這一版的 .exe 是好的（Windows 11 原生 218/218 ALL PASS），但那是我跑的，不是鏈跑的 —— 違反鐵則 2「沒有 gate 執行過的能力，不得寫進能力清單」。
- fix: （a）package.sh 的 else 分支改成 `exit 1`，或加 `--allow-no-windows` 旗標才允許缺；（b）evidence.py 的 identity 加 `binary_windows: {path, sha256}`，在有 `--windows` 時記錄真正的 .exe（不是 wine wrapper）雜湊；（c）release.yml 的版本一致性 step 加一段：`test -f dist/br-sidecar.exe` 且其 sha256 等於 evidence 記的值；（d）ci.yml 加一個 `runs-on: windows-latest` job 跑 `python sidecar/verify.py dist/br-sidecar.exe`（我已驗證這在原生 Windows 上可行且會通過）。
- verify: 逐項重現無誤。package.sh:57-68 的 else 分支確為兩句 echo（無 exit）；package.sh:128 `(cd "$STAGE" && sha256sum -- * > SHA256SUMS.txt)`、:132-133 `rm -rf "$DIST"; mv "$STAGE" "$DIST"` 皆與引文一致 —— 清單是對 stage 現況生成的，所以 release.yml:45 的 `sha256sum -c` 必過。我親自跑 `grep -rn "br-sidecar.exe" .github/ scripts/*.py` → exit=1，零命中，整條 CI/發行鏈確實從未提及 Windows 二進位。release.yml 的版本一致性 step（實際 63-82 行，非 63-72）第 68 行確為 `with open("dist/br-sidecar", "rb") as f:`，只比對 Linux 產物；ci.yml:25-28 只跑 `python3 sidecar/verify.py dist/br-sidecar`。evidence.py:610-614 的 sources 名單逐字符合，無任何 Windows 產物；總 gate 條件在 evidence.py:761（非 769）`and (not determinism[

