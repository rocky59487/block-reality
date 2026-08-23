### DISPUTE-A [low->low] confirmed
作者主張部分成立：技術事實全對，但「refuted 稽核」的框架不成立——稽核自己就已寫明同一件事

IMPACT: 對合併決策：#54 可以關，作者的技術判斷正確。對前一輪報告可信度：**沒有影響**——稽核在 FIX_ROLLUP 4.9 就已明確記載 stale 移除存在並把 #54 列為待驗。把它寫進「稽核宣稱被推翻」一節會讓讀者誤以為前一輪報告出過錯，實際上這一條前一輪報告是對的。建議改寫該條的標題範圍為「issue #54 的措辭部分 refuted」。

VERIFY: 逐項自查全部對上。(1) `git show abc8e78:forge/src/main/java/com/blockreality/impl/server/StructureManager.java` 我親自印出，第 284 行起 `for (BlockPos pos : structural)`、第 288 行 `if (!level.isLoaded(pos)) continue;`、第 293 行 `stale.add(pos)`、第 299 行 `structural.removeAll(stale);` —— 裁決者給的 284-299 精確無誤，作者「stale 移除本來就存在」屬實。(2) 裁決者的關鍵反擊也成立：docs/FIX_ROLLUP_2026-08-20.md:104 白紙黑字寫「注意 snapshot() 對 loaded chunk 的 stale 移除**是存在的**（StructureManager.java:290-299），#54 的殘留宣稱修前先驗」，:168 也把 #54 標成 ⚠️。所以「稽核宣稱被推翻」這個框架確實是錯誤歸屬，被 refuted 的是 issue #54 的措辭。(3) 保證來源的更正我也複查了：PR head StructureManager.java:409 `if (!level.isLoaded(pos)) return;` 擋方塊，SnapshotLoads.java:53-58 的 `included`/`tracked` 三分法擋荷載，兩條獨立；作者把兩者都算在「#38 三分法」名下確實不準。(4) 自癒鏈 :414 `cycleStale.add(pos)` → :424 `cycleStale.forEach(structural::remove)` 存在。另補一條裁決者沒查的：作者說「仍補了 /br scan 的 loaded 位置 reconcile」也是真的，StructureManager.java:160-171 的 `gone` 迴圈確實會移除掃描範圍內已非結構的追蹤位置。裁決正確且無偏袒，維持 low。

---
### DISPUTE-B [low->low] confirmed
作者主張不成立（稻草人）：Forge 沒有 MONITOR 是事實，但稽核從未建議 MONITOR，它建議的正是 LOWEST

IMPACT: 實作本身是對的、殘量登記也誠實，這條**不影響程式碼合併**。但它是唯一一條把不存在的稽核發言歸給前一輪報告、藉此宣告稽核出錯的條目。docs/FIX_ROLLUP 是這個專案的稽核信任基礎，在「輸格照登的反向」一節放一條反向的錯誤歸屬，效果和它想防的事情一樣壞。另附殘量觀察：javadoc 提到的自救路徑 `/br scan` 現已需要 permission 2（BrPermissions.java:60 `m.put("scan", LEVEL_OP);`），一般玩家在保護模組取消破壞後無法自行救回被誤刪的追蹤。

VERIFY: 我自己解開 gradle cache 的 eventbus 6.0.5 sources 重驗，不採信裁決者轉述。net/minecraftforge/eventbus/api/EventPriority.java 的 enum 常數只有 `HIGHEST, //First to execute` / `HIGH,` / `NORMAL,` / `LOW,` / `LOWEST; //Last to execute` —— **沒有 MONITOR**（那是 Bukkit 概念），作者這半句是事實。SubscribeEvent.java 末兩行 `EventPriority priority() default EventPriority.NORMAL; boolean receiveCanceled() default false;`；ASMEventHandler.java:69 `if (!event.isCancelable() || !event.isCanceled() || subInfo.receiveCanceled())` —— 已取消的可取消事件確實不投遞。所以 LOWEST + 預設 receiveCanceled=false **真的能**避免「被取消的破壞事件仍移除追蹤」：任何在 HIGHEST/HIGH/NORMAL/LOW 取消的保護模組都在本 handler 之前生效，handler 直接不被呼叫。但作者的歸咎不成立：我在 PR head 全庫 `grep -rn MONITOR --include=*.md --include=*.json --include=*.java` 只命中一處，就是 FIX_ROLLUP_2026-08-20.md:212 這句反駁本身；而 docs/REVIEW_2026-08-20_findings.json 的 FORGE-6 suggestion 原文是「改監聽 EventPriority.LOWEST 並檢查 isCanceled()…至少對 BreakEvent 用 receiveCanceled 語意保護」——稽核建議的正是 LOWEST，作者照做的就是稽核第一選項。實作 StructureManager.java:287 `@SubscribeEvent(priority = EventPriority.LOWEST)`，:275-285 javadoc 誠實登記「同優先級後註冊的取消者」殘量。裁決者的稻草人判定正確，維持 low。

---
### DISPUTE-C [low->low] confirmed
作者主張就 CONC-10 而言不成立：AnalysisExecutor 在分支上逐字未動，行號在 main 與分支兩處都正確；「3.x 行號漂移」部分成立但那是稽核已宣告的基線

IMPACT: 這條把「稽核基線行號漂移」當成前一輪報告的瑕疵登記，實際上 CONC-10 這個具名例子完全不成立（該檔在分支上一個字都沒改），而通則部分則是稽核已在文件開頭宣告過的既定基線。淨效果是無端下修前一輪報告的可信度。對程式碼本身無影響：max(1)→max(2) 的修復是正確的，也確實解決了 CONC-10 指出的問題。

VERIFY: CONC-10 部分我逐條重跑，裁決者全對：`git diff abc8e78 1174e69 -- .../AnalysisExecutor.java` 輸出 0 行；`git show abc8e78:….java | sed -n 29p` 與 `git show 1174e69:….java | sed -n 29p` 逐字相同，都是 `private static final int THREADS = Math.max(1, Runtime.getRuntime().availableProcessors() / 4);`；`git log -S "Math.max(1, Runtime.getRuntime().availableProcessors()"` 只回 97b9c6d 與 3fcc2a3，`-S "Math.max(2,"` 只回 97b9c6d，與 87c6114/1174e69 無關。findings.json 的 CONC-10 evidence 欄也確實逐字引用了 `Math.max(1, …)`，所以「稽核漏看 max 保底」不成立；CONC-10 的主張自始是 cores/4 在 4 核機退化成單執行緒，現已修為 AnalysisExecutor.java:44-45 `Math.max(2, …)`。「3.x 行號漂移」部分屬實（`git diff --stat abc8e78 1174e69 -- "*.java"` 顯示 StructureManager +68、BinaryCodec +277、ProtocolCodec +531、SidecarClient +234、BRCommand +79）。**但裁決者這裡也照抄了一個沒查的前提**：它引 FIX_ROLLUP 第 8 行「基線：abc8e78（main HEAD）」為辯護，我實查 `git branch -a -v` 顯示預設分支 Main = 593ef2a，abc8e78 是 PR 線上比 Main 超前 6 個 commit（f38db43…abc8e78，含 v0.2a 發行 commit）的節點，**不是 main HEAD**。宣告的基線本身標錯，且意味著任何從 abc8e78 起算 diff 的審核都漏掉那 6 個 commit。這不改變 CONC-10 的結論，但裁決者「宣告過的基線不是錯誤」這句話建立在一個它沒驗證的宣告上。維持 low。

---
### DISPUTE-D [low->high] upgraded
作者主張成立：patches 與數值核在整個 PR 區間零改動，既有容差無一放寬；且現行 S9 參考解經獨立核算為正確

IMPACT: 這是五條主張裡證據最硬的一條，且是鐵則 3 的核心測試——「不為了讓 gate 過而動引擎」。可查證的代理指標（patches 零改動、數值核零改動、既有容差零放寬）全部支持。唯一無法查證的是「commit 前修訂兩次」這段工作樹內歷史——git 對 pre-commit 迭代天生無記錄，這種自我登記在制度上是不可否證的，只能靠上述代理指標旁證。這點應該寫進 GATES.md 讓後人知道證據強度的邊界，而不是當成已驗證事實。

VERIFY: 這條裁決分兩半，一半我獨立證實，另一半我用兩個獨立數值方法**推翻**——而被推翻的正是任務指定「最重要」的那半。\n\n【成立的一半：鐵則 3 守住了】我親自驗：`git diff --stat abc8e78 HEAD -- sidecar/patches/` 輸出為空、`git log --oneline abc8e78..HEAD -- sidecar/patches/` 無任何 commit；main.cpp 在 PR 區間只被 87c6114/1174e69/23b0b37 動過，而 S9 gate 落地的 23b0b37 對 main.cpp 的 diff 我逐行讀過，只有刪掉 946-1019 那段死迴圈（第二迴圈 main.cpp:959-1003 逐欄位重寫 Nxx…vmTop/vmBot，Mc/McRaw 由 :1017-1029 的 recoverShellEdgeMoments 覆寫）＋ handleSolveShm 兩個 `if (!rd.ok) break;`。`git diff abc8e78 HEAD -- sidecar/verify.py | grep '^-[^-]'` 只有 9 行刪除，全是 check() 語意重寫與 shm 呼叫改簽名，**沒有任何既有容差被放寬**（新增的 check( 有 5 條不是裁決者說的 3 條，但全是新增）。這半成立。\n\n【被推翻的一半：S9 參考解仍然錯】裁決者宣稱「現行 S9 參考解經獨立核算為正確」，但它核算的只是 ν 縮放推導（M_centre ∝ (1+ν)、邊界係數 ν-free），對 0.0231 這個常數本身**照單全收**。我用兩個獨立方法實算夾支方板 ∇⁴w=q/D：(a) Chebyshev 譜方法 collocation（N=24/32/40 三個網格 8 位數不變）、(b) 13 點雙調和有限差分 + Richardson 外推（N=40/80/160）。兩者一致給出 w_max = 0.00126532 qa⁴/D（Timoshenko 表 0.00126 ✓）、M_edge = −0.051334 qa²（表 −0.0513 ✓）、**M_centre(ν=0.3) = 0.0229051 qa²，不是 0.0231**（β1 = 0.13743 vs Roark 0.1386）。三個表列值我重現了兩個到 4-5 位，第三個差 0.85%。\n\n第三個獨立見證是這個專案自己的引擎：我用 dist/br-sidecar.exe 跑 n=9…33 的網格收斂，implied C(ν=0.3) = 0.023307 / 0.023085 / 0.023006 / 0.022969 / 0.022950 / 0.022938 / 0.022930，單調收斂到 0.02290，**與我的譜解相符、與 0.0231 不符**。\n\n後果不是學術性的：我把 verify.py 的常數改成 0.0229051 後對同一顆二進位重跑，**3 條 check 由綠轉紅**——`[FAIL] span moment within 1% at 8 elements err=1.75%`、`[FAIL] slab_150: centre span moment within 1% err=1.73%`、`[FAIL] plate_20: centre span moment within 1% err=1.75%`。也就是說新 S9 gate 的兩條中心彎矩檢查現在是綠的，唯一原因是參考解偏高 0.85% 恰好抵掉離散誤差 +1.75% 的一半；真實誤差是宣稱的兩倍。GATES.md:85 登記的「兩次失敗都是參考解推導錯，引擎數字自始未動」在字面上成立（引擎確實沒動），但「參考解已經對了」不成立。裁決者在五條裡最重要的一條上過度讓步：它用「獨立核算」這個字眼背書了一個它沒有實算的常數。詳見另立的 NEW-S9-REF-CONST。嚴重度由 low 上修為 high。

---
### DISPUTE-E [low->low] confirmed
作者主張成立：git 時序確認鐵則 1 違規屬實且自我登記誠實；惟 GATES.md 寫「下一個 commit」實為隔兩個 commit

IMPACT: 自我登記誠實且可獨立驗證，這是這個專案 gate 制度真的有在運作的證據，對合併是正面訊號。措辭誤差（「下一個」vs 隔兩個）不影響結論，但既然這一列的存在目的就是精確記錄紀律滑失，措辭本身該精確。

VERIFY: 時序我自己跑 `git log --format="%h %ad %s" --date=format:"%m-%d %H:%M:%S" abc8e78..HEAD` 重建：23b0b37(17:40:48，含 json.hpp 深度上限 64、i64() guard、main.cpp 截斷 break，即 SIDE-2/5/6 的修復) → bbab7b9(17:41:07) → 5a0cd01(17:48:12) → 2ebcfd6(17:50:47)。`git show --stat 2ebcfd6` = `docs/GATES.md | 1 +`、`sidecar/verify.py | 23 ++`，正是那三條 gate。所以「修復先於 gate」屬實，鐵則 1 違規屬實，且 GATES.md:87 有登記、歷史未被 squash——這半裁決正確。裁決者的更正也對：GATES.md:87 寫「下一個 commit」而實際隔了兩個。裁決者說「兩者都不碰 sidecar/」略不精確——`git show --stat 5a0cd01` 顯示它動了 `sidecar/README.md`（文件，非程式碼），實質風險窗口的結論不變。另補一條裁決者沒查的同類殘量：`git log -S "C15" -- sidecar/verify.py` 與 `git log -S "timber" -- .../BRContent.java` 都只回 87c6114，也就是 C15 gate 與它宣稱「先於」的 timber/brick 方塊在**同一個 commit**，verify.py:986 的註解「These gates run BEFORE the game-side blocks were added」和 S9 的 pre-commit 迭代一樣不可由 git 否證，而 GATES.md 只登記了 SIDE-2/5/6 一條。維持 low。

---
### CI-PIPE [high->high] confirmed
新增的 CI 引擎 gate job 恆綠：verify.py 的退出碼被 `| tail -3` 吃掉，219 條引擎 gate 全紅 CI 仍會過

IMPACT: 這條抽掉了整個 PR 最核心的支撐。GATES.md 末段寫「這批 gate 的牙齒以 CI（.github/workflows/ci.yml，同日上線）為前提——CI 綠是 merge 條件」，FIX_ROLLUP 6.5 也寫「前提：0.1 的 CI 先上，否則以上全部只是『檔案在』」。實際上 engine-gates job 對 verify.py 的任何失敗（gate 紅、Python traceback、甚至 br-sidecar 根本跑不起來）都回報成功——連 `python3` 不存在都會綠。也就是說本 PR 宣稱的「219/219」在 Main 之後沒有任何自動化把關，正好落回鐵則 2 要防的「檔案在不算有」。另外此 job 只跑 Linux，Windows 原生那次是人工跑的。

VERIFY: 實查屬實。.github/workflows/ci.yml 全檔 47 行我讀過，engine-gates job 的 step 是 `run: |` 兩行，第二行 `python3 sidecar/verify.py dist/br-sidecar | tail -3`（:28），該 step 無 `shell:`，全檔無 `defaults: run: shell:`。GitHub Actions 在 Linux runner 的預設是 `bash -e {0}`，不含 pipefail。我在本機實測：`bash -e -c 'false | tail -3; echo "no-pipefail exit=$?"'` → `no-pipefail exit=0`；`bash -eo pipefail -c '…'` 則直接中止（無輸出，rc≠0）。verify.py 確實會回非零：檔尾 :1383-1385 `if fails: … return 1`、:1391 `sys.exit(main())`。所以整條 gate job 對 verify.py 的任何失敗都回報成功。對照組 release.yml:58 的判定命令在管線末端 `unzip -p … | grep -Eq … || { … exit 1; }`，寫法是對的，證明這是單點疏忽。影響評估我同意且要加碼：GATES.md:98（「這批 gate 的牙齒以 CI 為前提——CI 綠是 merge 條件」）與 FIX_ROLLUP 6.5 都把整個「gate 有牙齒」的論證掛在這個 job 上，而它連 `python3` 不存在都會綠。維持 high。

---
### GATHER-EPILOGUE [medium->medium] confirmed
#35 的分 tick gather 修好了主迴圈，但 finishCycle 每輪仍在 tick 預算之外做一次 O(全部追蹤方塊) 的 HashSet 重建

IMPACT: #35 的修復目標是「大世界的 gather 不再每 tick 燒滿預算」。主迴圈確實修好了（cursor 單調、必然完成），但每次完成一輪 gather 仍會在主執行緒上配置 N 個 BlockKey 物件並建一張 N 元素 HashSet，完全不受 8ms 預算約束。N=10 萬（大型建築伺服器不誇張）時單次約數十毫秒，足以打出可見的 tick spike；而這正是原始 finding 說要消滅的成本形態。不是 livelock（會完成），但在最大的世界上會週期性回來咬人，且沒有任何 gate 會發現。

VERIFY: 程式碼實讀屬實。StructureManager.java:434-437 `Set<BlockKey> tracked = new HashSet<>(); for (BlockPos p : structural) { tracked.add(new BlockKey(p.getX(), p.getY(), p.getZ())); }`，唯一消費者是 :442-443 傳給 SnapshotLoads.append 的第四參數，而 SnapshotLoads.java:56 只用它做 `!tracked.contains(key)`，查詢次數上限是 `loads.entrySet()` 的大小（測試荷載，通常個位數）。`structural` 宣告在 :68 是 `ConcurrentHashMap.newKeySet()`，`contains(pos)` 就是 O(1)，改傳 Predicate 可零配置得到同樣答案。預算邊界也對：:386 `long remaining = Math.max(0, TICK_BUDGET_NS - (System.nanoTime() - tickStart));`（TICK_BUDGET_NS = 8_000_000L，:59）只約束 :387 的 `cycle.step(...)`，:391 `dispatch(level, finishCycle())` 在 COMPLETE 之後跑，完全不看預算。:401 `cycle.begin(List.copyOf(structural), revision)` 同樣是 O(N)，落在 tickStart 之後所以會扣 remaining，但自身可獨吞整個預算。新測試確實碰不到這段（GatherCycleTest 測純狀態機、SnapshotLoadsTest 直接餵 Set.of）。唯一我要修正的是量級：N=10 萬時「數十毫秒」偏悲觀，JVM 建 10 萬元素 HashSet 加 10 萬次小物件配置一般是個位數到十餘毫秒，且頻率是「每次完成一輪 gather」（受 minTicksBetweenSolves 預設 10 tick 節流）而非每 tick。結論與嚴重度不變：這是不受預算約束的 O(N) 主執行緒工作、無 gate 覆蓋、修法是一行。維持 medium。

---
### BRPERM-SCOPE [medium->medium] confirmed
BrPermissionsTest 只走自己的表、不走真正的 Brigadier tree，擋不住它 javadoc 宣稱要擋的 #45 (b) 失效類

IMPACT: 這是本輪唯一一個「用來擋回歸的新 gate，實際擋不住它自己指名要擋的東西」的案例。表的存在確實比散落的 inline 好，但把它宣稱成結構性保證是超前的——按鐵則 2 的精神，沒被 gate 執行過的保證不該寫進 javadoc。合併風險不高（現況正確），但這條 gate 的牙齒是假的，下一個新增指令仍可能默默以權限 0 上線。

VERIFY: 實讀屬實。BrPermissionsTest.java:23 的迴圈是 `for (String literal : BrPermissions.literals())`，而 BrPermissions.java:70 `public static Set<String> literals() { return LITERALS.keySet(); }` —— 走的就是表自己。全檔四個測試沒有任何一個接觸 CommandDispatcher 或 Brigadier 節點樹；`anUnknownLiteralIsARegistrationError`（:57-61）只斷言 `BrPermissions.required("nuke")` 會擲，證明的是 helper 行為。所以 BrPermissionsTest.java:17 javadoc 的「a subcommand added to the tree without a table entry fails registration outright」與 BrPermissions.java:14-16 的「walks every registered literal」都是超前宣稱：那個保證只在開發者用 BRCommand.java:53-56 的 `lit()` 時成立，而 `Commands.literal(...)` 直接可用且就在同一段用了（BRCommand.java:87 `Commands.literal("all")`）。現況九個頂層 literal 全走 `lit()`（:60,61,62,66,68,75,85,88,89），所以合併風險低；但這正是 #45 原始病灶（新指令抄舊指令的弱預設）能再次無聲上線的縫，且違反鐵則 2 的精神（沒被 gate 執行過的保證不該寫進 javadoc）。維持 medium。

---
### CHUNKLOAD-STARVE [medium->medium] confirmed
任何含結構方塊的 chunk 載入都會 bump revision 並讓進行中的跨 tick gather 從 cursor 0 重來——#35 的 livelock 從無條件變成有條件，且無 gate 覆蓋

IMPACT: #35 原本是無條件 livelock（世界一大就永遠不解算）。修完之後變成有條件：只要含結構方塊的 chunk 載入頻率高於一次完整 gather 的耗時（大型模型跨數 tick），該維度的分析仍然永遠不產出、且同樣沒有任何玩家看得到的錯誤訊息——與原始 finding 的症狀完全相同。跨 tick 設計本身把 gather 暴露在這個風險下，是修復引入的新面。不是必然發生，需要大模型 + 頻繁 chunk 流動同時成立，所以 medium。

VERIFY: 機制實讀屬實，且我找到一個裁決者沒寫、會加重此條的細節。鏈路：StructureManager.java:309-314 `onChunkLoad` → `if (scanChunk(m, e.getChunk()) > 0) m.markDirty();` → :259-261 `markDirty(){ gate.bump(); dirty = true; }`；tick():378 `cycle.ensureCurrent(currentRevision)` → GatherCycle.java:82-88 `if (order != null && revision != currentRevision) { abandon(); return true; }` → :71-75 `abandon()` 把 cursor 歸零。`grep -rn "\.bump()" --include=*.java forge/ mod/ | grep -v /test/` 我自己跑過，生產呼叫點確實只有 StructureManager.java:117 與 :260，chunk 載入與玩家編輯走同一個 revision 通道。**加重的細節**：scanChunk 的內層迴圈（:186-196）對每個結構方塊無條件 `found++`，不管 `m.structural.add(...)` 是否真的新增——所以一個**內容完全沒變**的 chunk 重載也會 bump revision、放棄進行中的 gather。多人伺服器上視距邊界的 chunk 反覆載入／卸載是常態，這讓「模型不變卻持續 bump」變成日常而非例外。GatherCycle.java:22-27 的 javadoc 只承認玩家編輯這一種來源，GatherCycleTest.java:73-87 只把「revision 變動就放棄」測成正確性，確實沒有任何飢餓測試。仍維持 medium：需要「gather 跨 tick（大模型）」與「結構 chunk 持續流動」同時成立，且 minTicksBetweenSolves 預設 10 tick 的節流讓非 gather 期間的 bump 無害。

---
