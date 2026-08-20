# 修理總表 2026-08-20（給主線的統整報告）

**這份文件是什麼**：把三個來源合併去重成一份可照著修的清單——
(1) 八維度全域審核 88 條（每條經獨立對抗覆核，詳見 `REVIEW_2026-08-20.md` 與逐條證據 `REVIEW_2026-08-20_findings.json`）；
(2) issues #27–#34 與 P0 清單的逐條查證（`REVIEW_2026-08-20_verdicts.md`）；
(3) GitHub issues #20–#55 現況。

**基線**：`abc8e78`（main HEAD）。所有 file:line 都對這個 commit。

**查證標記**：✅ 已由獨立代理打開檔案逐行覆核屬實　⚠️ 部分屬實（修正內容寫在該條）　⬜ issue 存在但本輪未查證（照登，修前先驗）

---

## 先做決定：hardening 分支怎麼辦

`origin/claude/purity-shm-hardening` = `abc8e78` + `87c6114`（/br load 任意荷載 + timber/brick 進 catalogue，先 gate 後方塊）+ `1174e69`（36 條 binary wire gates，含 ±30M 座標 guard 補進 shm 路徑，commit 自述 "binary wire is not a side door"）。

- **#27/#28 的大半、#32/#33 的修復宣稱都在這條分支上**。issues #27–#34 已被關閉，但 main 沒有這些修——「分支上修了」不算「修了」。
- **建議**：先審後併這條分支（或 cherry-pick `1174e69`），不要在 main 重寫一遍。併入前注意：`87c6114` 的 `/br load`/`unload`/`loads` 權限 0（#45 的範圍因此擴大——任意玩家可對他人伺服器的任意方塊加任意力向量）。
- 併完後對照 Phase 3 逐條驗殘量。

**同理已關但 main 上還在的**：#20（macOS 誤宣稱）→ 本表 2.4；#22/#23（README 國際化 / release notes）→ 未查證修復位置，主線自行確認。

---

## Phase 0 · 前提（不先做，後面所有「修好了」都無法證明）

| # | 問題 | 修法 | 驗收 |
|---|---|---|---|
| 0.1 ✅ | **沒有任何自動化 gate 跑 Java 測試**（=#46 / TEST-1 / CI-1）。`.github/workflows/release.yml` 是唯一 workflow、只在推 tag 時打包、零 test step；`scripts/package.sh:90` 明確 `build -x test`；跨語言測試不帶 `-Dbr.sidecar` 即全 skip | 加 push/PR CI 跑 `mod` 與 `forge` 的 `gradlew test`；package.sh 移除 `-x test`；release job 前置 test job | CI 綠燈成為 merge 條件；package 在測試紅時中止 |
| 0.2 ✅ | **`forge/gradlew.bat`、`mod/gradlew.bat` blob 從未 renormalize**（HYG-1），乾淨 checkout 永遠 dirty | `git add --renormalize forge/gradlew.bat mod/gradlew.bat` 後 commit（一次根治） | 乾淨 clone 後 `git status` 乾淨 |
| 0.3 ✅ | **`.gitattributes` 未涵蓋 `dist/` 與 `scripts/dist-docs/` 的 .txt 與 SHA256SUMS.txt**（HYG-3），行尾隨 autocrlf 漂移 | 補 `dist/** text eol=lf`（或逐型別釘）＋ renormalize | Windows checkout 上 `sha256sum -c` 可過 |
| 0.4 ✅ | **`dist/SHA256SUMS.txt` 記錄的是 CRLF 變體雜湊**（REL-1），release workflow 的 checksum gate 必掛；origin 只有 v0.1a tag，[README.md:24](../README.md) 的 v0.2a 下載連結**現在就是 404** | 0.3 之後在 LF 工作樹重跑 `(cd dist && sha256sum -- * > SHA256SUMS.txt)` 並 commit；發出 v0.2a | 推 tag 走完 workflow；README 連結可下載 |

---

## Phase 1 · 必崩與熄燈（玩家會直接撞上的）

| # | 問題 | 修法 | 驗收 |
|---|---|---|---|
| 1.1 ✅🔴 | **同一 session 開第二個世界必崩**（=#37 / CONC-1≡FORGE-1）。[AnalysisExecutor.java:31,54](../forge/src/main/java/com/blockreality/impl/server/AnalysisExecutor.java:54) static final POOL 被 `shutdownNow` 後永不重建；世界B 放結構方塊 → `RejectedExecutionException` 穿 event bus → server crash | pool 掛 server 生命週期（ServerAboutToStart 建 / ServerStopped 關）或 `pool()` 偵測 `isShutdown()` 重建；`execute` 包 REE | JUnit：shutdown→pool()→execute 不擲；手測開A退出開B放方塊 |
| 1.2 ✅🔴 | **背景 solve 拋出 → `inFlight` 永久卡死**（=#36 / CONC-4≡FORGE-8）。[StructureManager.java:263-272](../forge/src/main/java/com/blockreality/impl/server/StructureManager.java:263) try/finally 只包 apply，solve 裸奔。考古：初版即無，非 regression | 整個 lambda 包 try/catch/finally；catch 記 log；finally 復位 inFlight（或投遞復位） | JUnit：注入拋例外的 sidecar stub，斷言 inFlight 復位＋有 log |
| 1.3 ✅🔴 | **snapshot 超 8ms 無限重試 livelock**（=#35 / CONC-5）。[StructureManager.java:257](../forge/src/main/java/com/blockreality/impl/server/StructureManager.java:257) 超預算 return 不改狀態，下一 tick 原樣重跑；大世界分析永不執行＋每 tick 全額成本 | 超標即直接送出（背景解算不吃 tick 預算），或 snapshot 改增量，或至少指數退避＋告警 | JUnit：巨型 structural 集合下 N tick 內必 dispatch 或必退避 |
| 1.4 ✅🔴 | **荷載掛在 unloaded chunk → 整維度分析靜默熄燈**（=#38 / TEST-3）。[StructureManager.java:301-306](../forge/src/main/java/com/blockreality/impl/server/StructureManager.java:301) 守衛是 `structural.contains` 不是「本輪真的收進」；引擎 load-on-no-element 整批拒絕。`87c6114` 的 stale-load 清理**未覆蓋此 case** | 荷載迴圈改判「本輪 builder 實際收進的 key 集合」；跳過的荷載與方塊同進退 | JUnit：兩 chunk 結構、卸載荷載所在 chunk，斷言 solve 送出且 ok |
| 1.5 ✅ | **dimension unload 時 in-flight solve 重新拉起 sidecar，孤兒程序無人管**（CONC-3，無 issue）。[SidecarClient.java:348](../mod/core/src/main/java/com/blockreality/core/sidecar/SidecarClient.java:348) close() 設回 IDLE 非終態，ensureReady 照樣重 spawn | close() 設終態（closed flag / DISABLED），ensureReady 拒絕復活；或 unload 先等 inFlight 落地 | lifecycle 測試：close 後 solve 斷言不 spawn 新程序 |
| 1.6 ✅ | **純樓板結構完全不畫應力層**（FORGE-3，無 issue）。[StressSurfaceRenderer.java:96](../forge/src/main/java/com/blockreality/impl/client/StressSurfaceRenderer.java:96) members 空就 return，但 drawPlates 不依賴 members——demo 兩種方塊之一整個看不到 | early return 條件改 members 與 shells 皆空 | 手測：只鋪 concrete_slab，戴眼鏡可見等值圖 |
| 1.7 ✅ | shutdownAll 順序顛倒（CONC-8）：先 close sidecar 再停 pool，主動製造 close-vs-solve 併發窗 | 先 shutdown pool（等 drain）再 close sidecar | 隨 1.1/1.2 的生命週期測試 |
| 1.8 ✅ | SidecarClient 非 thread-safe 卻被主執行緒 close/reset/status 併發碰（CONC-2，覆核降 medium）；server 停止後 `getServer().execute` 會 inline 跑 apply（CONC-6） | close/reset 投遞到分析執行緒或上鎖；apply 前判 server 狀態 | 併發測試（見 6.3） |

---

## Phase 2 · 發行鏈

| # | 問題 | 修法 |
|---|---|---|
| 2.1 ✅ | **package.sh glob 複製 mod jar 不清舊版**（SCRIPT-2）：版本升級後重打包，zip 內兩顆 jar、兩平台安裝器各裝到不同版本。[package.sh:91](../scripts/package.sh:91) | 打包前 clean 或 `rm -f build/libs/blockreality-*.jar`；cp 前 assert glob 恰一檔 |
| 2.2 ✅ | package.sh 開頭 `rm -rf` tracked 的 dist/，中途失敗即刪空收場（SCRIPT-1） | 改組到暫存目錄，成功才替換 |
| 2.3 ✅ | 兩個安裝器 copy 失敗零檢查照樣報成功，install.bat 錯誤被 `>nul` 吃掉（SCRIPT-3） | 逐 copy 檢查 errorlevel/exit code，失敗即中止並顯示 |
| 2.4 ✅ | **macOS 使用者照文件安裝必然拿到 Linux ELF**（MAC-1，=#20 已關但 main 未修）：四份文件列 macOS，發行包無 macOS 引擎，install.sh 無 OS 判斷 | 文件明寫「macOS 無引擎、分析停用」；install.sh 加 `uname` 判斷走「缺席→停用並說明」路徑 |
| 2.5 ✅ | **evidence 無 provenance 照樣 PASS 出貨**（=#47 / EVID-1+TEST-9+SCRIPT-6）：出貨 verification.json `commit:"unavailable"`、dirty tree；gate 判定不含 identity；supernodal_lane 寫死 | evidence.py 的 gate 把 identity 可解析（引擎 SHA、clean tree、判準凍結 SHA）列為必要條件；GATES.md 規格已有，照做即可 |
| 2.6 ⬜ | #48：workflow_dispatch 的 tag/version 必須與 JAR/mods.toml/evidence 一致，禁止舊 binary 改名發布 | 修前先驗 workflow 實況 |
| 2.7 ✅ | 2.4MB 發行 zip commit 在倉庫根（HYG-2），與 dist/ 完全重複 | 移出版控（或明確決策留下並記 DECISIONS） |
| 2.8 ✅ | evidence.py 把 solve 失敗與 extractor None 默默排除在聚合外，準確度沒真跑到也 PASS（SCRIPT-5） | 失敗 case 計入並使 gate 紅；排除量入報告 |
| 2.9 ⬜ | #52：mods.toml `[47,)` / `[1.20.1,1.21)` 相容範圍超出實測矩陣 | 縮到實測版本；或建測試矩陣 |
| 2.10 ✅ | RELEASING.md「只改文件」流程寫死 `blockreality-0.1a.zip`（DOC/REL-1） | 改成從 jar 版本推導 |

---

## Phase 3 · Port / wire（先併 hardening 分支，再補殘量）

先做「先做決定」節的分支合併，然後逐條驗殘：

| # | 問題 | 殘量判斷 |
|---|---|---|
| 3.1 ✅ | **#27 SHM framing（5/5 屬實）**：doorbell 只帶 revision；C++ Reader 與 Java decoder 都把整個 mapping 當合法範圍；C++ 有寫 reply `bytes` 但 [SidecarClient.java:187/199](../mod/core/src/main/java/com/blockreality/core/sidecar/SidecarClient.java:187) 丟棄；[BinaryCodec.java:120-121](../mod/core/src/main/java/com/blockreality/core/protocol/BinaryCodec.java:120) 內部 `clear()` 連呼叫端設的 limit 都重設 | `1174e69` 是否已加 length framing 需驗；沒有就照 #27 驗收清單補（doorbell 帶 bytes、兩端 `0<bytes<=size`、exact-consume） |
| 3.2 ✅ | **#28 binary fail-closed（6/6 屬實）**：未知 enum/flags 靜默歸零；member↔plate 同一合併表只驗範圍不驗角色；NaN/Inf 直達 AnalysisResult（判定路徑零 finite 檢查）；緩解：C++ `-1` token 會被 Java `at()` 擋 | `1174e69` 覆蓋座標 guard 與部分 gates；角色錯配、finite policy、flags mask 需逐條對 |
| 3.3 ✅ | **#30 JSON request 容器級 schema（屬實）**：`loads` typo/錯型別 → 自重-only 模型回 `ok:true`（最毒）；`support:"true"`→false；parser 不拒 trailing。同函式 buckling 有防護，證明 pattern 已知 | blocks/loads/support 加 presence+type 驗證；parser 拒 trailing；gate：`loads:{}`、`load:[...]`、`support:"true"` 全紅 |
| 3.4 ✅ | **#31 + #53 reply 補預設與 latest 守門**：codec 對 `ok:true` 缺欄位補 0/false/空（全真）；位元組截斷有 parser+測試擋，危險是「語法完整缺欄位」；**[StructureManager.java:337/349-350](../forge/src/main/java/com/blockreality/impl/server/StructureManager.java:337) `latest` 無條件寫、`acceptForCommit` 回傳值被忽略、封包無條件廣播**；`/br status` else 分支印綠色 0.0000 | reply required-field 驗證（缺→failed）；apply 依 gate 裁決決定寫 latest 與廣播；/br status 加 isUsable 守門 |
| 3.5 ✅ | **#33 desync 留 READY（5/5 路徑屬實）**：五條協定損毀路徑只回 failed、不扣失敗預算、永不升級；javadoc 自證 lost sync | 三分類（REFUSAL / TRANSPORT / DESYNC）；desync → 至少 kill+restart 或 drop shm 並證明 stdio 已同步 |
| 3.6 ⚠️ | **#32 grow/fallback（部分）**：grow 單次重試即棄、reply 損毀不降級屬實且與自家註解矛盾；但 region 建立/映射類失敗**確實**可靠降 JSON | 修損毀路徑降級與 grow 迴圈（用 C++ 已回的所需大小或 doorbell 帶 needed bytes）；順修 PROTO-2 的 `contains("grow")` 字串耦合 |
| 3.7 ✅ | **#34 handshake（4/4 屬實）**：decodeHello 除 protocol 外全寬鬆；catalogue 順序即 binary index ABI、wire 只 range check；正面：protocol mismatch 已是永久 DISABLED | hello required-field+唯一性+section/plate 不重疊驗證；畸形 catalogue → 不啟用 binary 或拒絕 handshake |
| 3.8 ⚠️ | **#29 revision 域不一致（降為 P2）**：Java long / JSON double≤2^53 / SHM 64-bit 三域不一致屬實；但 C++17 下非 UB、每條 >2^53 路徑顯式 fail-closed、邊界要 9×10^15 次編輯 | 契約衛生：doorbell 不經 double（string 或 hi/lo）；不急 |
| 3.9 ✅ | requestBytes int 溢位 → BufferOverflowException 逃出 never-throws 的 solve()（PROTO-6，會觸發 1.2） | long 運算＋上限檢查 |
| 3.10 ✅ | main.cpp 殼結果迴圈整段重複、第一段死碼（PROTO-7≡SIDE-4≡INV-7，三個維度獨立發現） | 刪第一段 |
| 3.11 ✅ | bjson 遞迴無深度限制且 parse 在 try 外→深巢狀 crash（SIDE-2）；strtod 不查 endptr→畸形數字靜默改值（SIDE-3）；SIDE-5/6/7/8 低severity 各見 findings.json | 深度上限＋parse 入 try；endptr 檢查 |
| 3.12 ✅ | **plate gates 只蓋 1/3 token**：catalogue 三種 plate，verify.py 全部硬編 `concrete_slab_200`；hardening 分支的 +37 行是樑斷面 gate 與 plate 無關。違反鐵則 2 | verify.py 對 `concrete_slab_150`、`steel_plate_20` 各補閉合解 gate |
| 3.13 ⬜ | #49：SidecarProcess stdout queue 無上界，異常 child 無限輸出 → JVM OOM | 修前先驗 queue 實作 |
| 3.14 ✅ | Windows shm 暫存檔永久洩漏（CONC-7）：mapping 不 unmap、close 刪不掉、deleteOnExit 也失敗 | 顯式 unmap（invokeCleaner）或改名目錄集中清理 |

---

## Phase 4 · Forge / netcode / client

| # | 問題 | 修法 |
|---|---|---|
| 4.1 ✅ | 封包 registerMessage 未宣告 NetworkDirection（FORGE-4）：惡意 client 可反向送 S2C；LAN 主機被汙染顯示狀態 | 兩個封包補 `.direction(PLAY_TO_CLIENT)` |
| 4.2 ⚠️ | #39：StressResultPacket count clamp 未完整 consume frame → member/shell 解碼錯位。相鄰已證事實：decode 對截斷 buffer 會擲 netty IndexOutOfBounds，javadoc「never throws」不成立（TEST-12 覆核） | clamp 時按宣告 count 完整 skip；decode 包防禦；補 packet round-trip 測試 |
| 4.3 ⚠️ | #40+#50+#55：client packet 把 NaN/Inf/未知 enum 洗成 0/NONE、DTO 無不可能狀態防護、安全分類經 float 降轉後在 client 重算。同 pattern 在 BinaryCodec 已證實（#28）；packet 側逐條未驗 | 與 3.2/3.4 同一套 fail-closed 原則套到 StressResultPacket；安全分類 server 端定案隨包下發（呼應不變式 5/6） |
| 4.4 ✅ | ClientStressState 換維度/登出/換伺服器不清空（FORGE-10 / #41）：舊世界應力層畫在新維度同座標 | 包綁 dimension；LoggingOut/Respawn(換維度) 事件清 state |
| 4.5 ✅ | HUD mechanism 分支永不可達（FORGE-5 / #43）：`!hasData()` 先 return，全域機構被顯示成 No analysis yet | mechanism 判斷提到 hasData 前（它不需要 members） |
| 4.6 ⬜ | #42：overlay 截斷時必須保留 governing element 並在 HUD 明示不完整 | 修前先驗截斷實作 |
| 4.7 ⬜ | #44：member draw-distance 的 && 條件錯 → 十字型超遠渲染 | 修前先驗該條件 |
| 4.8 ✅ | **指令權限**（FORGE-7 / #45，含 87c6114 的 /br load）：scan（1089 chunk 主執行緒全掃）/resolve/load/unload/loads 權限 0；/br status 洩漏伺服器檔案路徑 | 除唯讀顯示外全部 `requires(hasPermission(2))`；status 對非 OP 隱藏路徑；配 6.2 的枚舉測試 |
| 4.9 ⚠️ | onBreak 在可取消的 BreakEvent 就移除追蹤（FORGE-6）；#54 宣稱 rescan 只 add 不 reconcile——注意 snapshot() 對 loaded chunk 的 stale 移除**是存在的**（[StructureManager.java:290-299](../forge/src/main/java/com/blockreality/impl/server/StructureManager.java:290)），#54 的殘留宣稱修前先驗 | onBreak 改聽 post-event（或 confirm 後移除）；驗 #54 的具體路徑再修 |
| 4.10 ✅ | EngineStatusPacket writeUtf(…,256) 無截斷，超長 diagnostic 踢掉全維度玩家（FORGE-2，覆核降 medium） | 發送前截斷 |
| 4.11 ✅ | SidecarLocator 指引玩家改 `config/` 但 SERVER config 實際在存檔 `serverconfig/`（FORGE-9） | 修訊息文字 |
| 4.12 ✅ | 顯示軌過期不標示（INV-4）：RevisionGate.displayState 生產碼零呼叫，client 把舊 revision 應力圖當最新畫 | 封包帶 revision + client 比對世界編輯計數，過期畫面加 stale 標示 |
| 4.13 ✅ | governingStation 未編入封包、client 硬編 0（INV-5）：主宰截面在跨中時剖面圖顯示錯誤截面 | 封包補該欄位 |
| 4.14 ⬜ | #51：run.sh/run.bat 優先用本次 build 的 sidecar 而非 committed dist 舊 binary | 修前先驗腳本 |

---

## Phase 5 · 不變式決策與文件真實性

這批不是改碼就完，**每條都要嘛改碼要嘛改文件並在 DECISIONS.md 登記**（含否證條件）：

| # | 問題 | 處置 |
|---|---|---|
| 5.1 ✅ | **不變式 7 被違反且未登記**（INV-1≡WIRE-1）：ENGINE_BOUNDARY:116 明文禁止 A/Iy/Iz 上 wire，實作每根構件照送（main.cpp:1440 → StressFieldSpec.java:53），forge/README 還寫成亮點 | 二擇一並登記 D-條目：(a) 承認渲染需要可求值場、改 ENGINE_BOUNDARY 禁止清單；(b) 改與 frame 詞彙解耦的場描述。現狀（文件禁、程式傳、無登記）是最壞組合 |
| 5.2 ✅ | **「凍結 C ABI / frame_capi_v2」是假的**（ABI-1）：CLAUDE.md:14 與 D-013 現在式宣稱，實作直接 include FrameCore C++ 標頭靜態連結，frame_capi 程式碼零命中 | 改寫 CLAUDE.md 與 D-013 為實況（自製 JSON/SHM 協定），frame_capi_v2 標為換裝方向 |
| 5.3 ✅ | 支承角色由 isSupported 啟發式反推並固定 6-DOF 全固接（INV-2），偏離不變式 3；程式註解自知是 placeholder（MEMBER_SEMANTICS Q6 未決） | 在 DECISIONS 登記臨時決策＋否證條件；或實作玩家宣告 |
| 5.4 ✅ | **兩軌精度分離（不變式 5/6）未實作**（INV-3 + GATE-1 + TEST-12）：軌別欄位只存在於文件；顯示軌 rel≤1e-5 無任何 gate；float32 降轉無測試界定 | 要嘛實作兩軌，要嘛文件改為「規劃中」並下調宣稱；至少補顯示軌精度 gate |
| 5.5 ✅ | ENGINE_BOUNDARY 訊息目錄（world.declare/world.edit/delta/result.*/event.*）以現在式描述、全部不存在（INV-8≡DOC/PROTO-1）；實際每次 solve 送全量 | 該節標「目標協定，未實作」，加現況對照表 |
| 5.6 ✅ | QUICKSTART 引擎重建漏 patch 步驟，照做必然編譯失敗（QS-1）：main.cpp include 的 ShellEdgeRecovery.h 是 patch 0002 新增 | 補 checkout v4.0.0 + `git am sidecar/patches/*.patch` 步驟 |
| 5.7 ✅ | QUICKSTART「驗證證據」整節過期（QS-2）：五項數字全錯（1.6e-10 vs 實際 1.2e-14、宣稱 commit 乾淨 vs 實際 unavailable+dirty、宣稱 zip 含 VERIFICATION.md vs 實際不含…） | 整節重寫，數字從 evidence 檔生成 |
| 5.8 ✅ | **verify.py 案數沒有任何文件寫對**（CNT-1≡TEST-10）：實測 163（對 dist 二進位實跑），文件寫 164/151/107/104/26/18 | 全部改對；建議數字改由腳本輸出貼入 |
| 5.9 ✅ | 剪力牆「h/w≥3 到 1e-7」宣稱優於量測（SW-1）：evidence 的 h/w=3 剪力流誤差 2.7e-05、gate 線 1e-4；1e-7 只在 h/w≥5 成立 | README/QUICKSTART/outreach 同步改為 h/w≥5 或改寫實測值 |
| 5.10 ✅ | README 效能數字（202 構件/7.0ms SHM/40.9ms JSON）不在它指向的 evidence 裡（PERF-1）；evidence 效能表無 SHM 量測 | 把 bench_transport 結果納入 evidence 流程，或數字改引用現有紀錄 |
| 5.11 ✅ | outreach 板彎矩數字（39.7%→14.0%）與現行 evidence（39.8%→14.3%）不符、方向偏有利（OUT-1） | 對外文案數字全部重對 evidence；已發出的要更正 |
| 5.12 ✅ | forge/README 仍宣稱「客戶端渲染沒有人看過」，與 README 實機截圖及 QUICKSTART 矛盾（FR-1） | 更新 |
| 5.13 ✅ | D-001/D-003/D-009 否證條件為「無」，違反 DECISIONS.md 自訂原則（DEC-1） | 補寫或明文豁免類別 |
| 5.14 ✅ | sidecar/README「已實作的規則」表被左手系警告節攔腰截斷，5 列成孤兒（SR-1） | 修表 |
| 5.15 ✅ | 2 格 run（L/h≈1.67）仍建 Euler-Bernoulli 梁元（INV-6）：不變式 1 的細長比理由只在單方塊層級把關 | 在 MEMBER_SEMANTICS/DECISIONS 記載短 run 的適用性限制（或加最小 run 長度） |

---

## Phase 6 · 鎖死（讓這輪抓到的東西以後自動被抓）

考古結論先講：**這輪的問題零 revert**——不是「已修過的 bug 被帶回來」，而是三種病：
(a) 平行路徑未繼承防護（`1cf4e05` 8/14 進 guard → `7d0f84e` 8/20 shm 路徑未繼承）；
(b) 新指令複製從未修過的弱預設（`/br load` 沿用 scan 的權限 0）；
(c) 原生缺陷從初版就在（inFlight、static pool——六版 diff 逐字未變）。
所以鎖不是「防 revert 釘子」，是結構性的：

| # | 鎖 | 抓什麼 |
|---|---|---|
| 6.1 | **兩傳輸 parity gates**：同一請求走 JSON 與 SHM 必須同 accept/同 reject/同數值 | (a) 類——新增傳輸/路徑沒繼承防護，加的當下就紅。`1174e69` 已是這個方向，補成對稱全套 |
| 6.2 | **指令權限枚舉測試**：走訪 Brigadier 樹全部 literal，白名單外斷言 `requires ≥ 2` | (b) 類——新指令自動被抓 |
| 6.3 | **生命週期 JUnit**：pool 關閉後重建（1.1）、solve 拋出後 inFlight 復位（1.2）、snapshot 超預算退避（1.3）、loads 與 blocks 同進退（1.4）、close 後不復活（1.5） | (c) 類——原生缺陷第一次就該有測試 |
| 6.4 | **既有測試缺口補課**（皆已覆核）：BinaryCodec 防禦路徑純 Java 可測零覆蓋（TEST-4）；shm 失敗模式全無測試、lifecycle stub 全 JSON-only（TEST-5）；RevisionGate/SidecarClient 併發零測試（TEST-6）；verify.py `check()` 對期望 0 退化成 exact-zero、容差是死參數（TEST-8）；SidecarLifecycleTest close 案斷言是套套邏輯（TEST-11）；BinaryCodec javadoc 宣稱被 T-gate 保護是假的——T-gate 用 Python 解碼器從不執行 Java（TEST-2，修 javadoc＋補 Java 端跨語言 gate）；test_harness.py 是死工具，等的檔案沒人寫、環境變數沒人讀（TEST-7，修通或刪除） | 覆蓋缺口 |
| 6.5 | 前提：0.1 的 CI 先上，否則以上全部只是「檔案在」 | — |

---

## 附錄 A · issue 對照與狀態

| issue | 對應 | 狀態 |
|---|---|---|
| #20 | MAC-1（2.4） | **已關但 main 未修**，✅ 屬實 |
| #22/#23 | README 國際化 / release notes | 已關，修復位置未驗 |
| #27–#31, #33 | 3.1–3.5（✅ 屬實；#29→3.8 降 P2） | **已關但 main 未修**，修在 hardening 分支 |
| #32/#34 | 3.6/3.7（⚠️/✅） | 同上 |
| #35–#38 | 1.3 / 1.2 / 1.1 / 1.4 | open，✅ 全屬實 |
| #39–#44 | 4.2 / 4.3 / 4.4 / 4.6 / 4.5 / 4.7 | open；#41/#43 ✅，其餘 ⚠️/⬜ |
| #45 | 4.8 | open，✅（87c6114 使範圍擴大） |
| #46–#48 | 0.1 / 2.5 / 2.6 | open；#46/#47 ✅，#48 ⬜ |
| #49–#52 | 3.13 / 4.3 / 4.14 / 2.9 | open，⬜（#50 與已證 pattern 同族） |
| #53–#55 | 3.4 / 4.9 / 4.3 | open；#53 ✅，#54 ⚠️（部分機制已存在，先驗），#55 ⚠️ |
| #13/#16/#17/#21 | 舊 demo-v0 / 版本定位，本輪未審 | open，不在本表範圍 |

**無 issue 的已覆核問題**（主線可直接修或補開）：1.5（CONC-3 孤兒 sidecar）、1.6（FORGE-3 純樓板）、1.7/1.8、2.1–2.3、2.7、2.8、2.10、3.9–3.12、3.14、4.1、4.10–4.13、Phase 5 全部、6.4 全部。

## 附錄 B · 未列入上表的 low（皆 ✅，詳見 findings.json）

CONC-9（close 阻塞主執行緒 4 秒/程序）、CONC-10（cores/4 → 4 核機單執行緒，與 javadoc 矛盾）、BUILD-1（.gitignore wrapper jar 反排除路徑錯）、BUILD-2（settings.gradle composite 註解與實況矛盾）、BUILD-3（CMake GLOB 無 CONFIGURE_DEPENDS）、SCRIPT-4（install.bat delayedexpansion 吃 `!`）、SCRIPT-7（rcon 單次 recv 短讀）、SCRIPT-8（Windows terminate 殺不到 JVM 子行程）、SCRIPT-9（bench 留 8MB 暫存＋rounds=0 NameError）、SIDE-5/6/7/8、PROTO-4/5（已併入 3.2/4.3 的 fail-closed 套件）。

---

**統計**：來源 88 條審核 findings（83 confirmed / 4 downgraded / 1 upgraded / 0 refuted）＋ issues #27–#34 逐條查證（8 個 Opus 代理）＋ #35–#55 對照。去重後本表 Phase 0–6 共 60 個修理項，其中 ✅ 已覆核 47、⚠️ 部分 6、⬜ 待驗 7。
