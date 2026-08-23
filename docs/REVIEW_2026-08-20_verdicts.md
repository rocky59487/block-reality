# P0/P1 清單與 issues #27–#34 查證（2026-08-20）

對以下清單的逐條查證，基準 `abc8e78`（main HEAD）：

- P0-1 snapshot >8ms 無限重試、P0-2 worker 失敗 `inFlight` 永久卡死、P0-3 load 必須只作用於本次 snapshot 的方塊
- P0-4 issues #27–#31、#33（Port fail-closed）
- P1 issues #32/#34 + evidence provenance + token-specific plate gates
- 附帶查證的說法：「已修過的可靠性 bug 被新功能 commit 帶回來（regression）」

**方法**：P0-1/2/3 由主審直接對行；#27–#34 與考古由 8 個獨立驗證代理（Opus）逐條打開檔案比對，
每條裁決附親自讀到的 file:line。裁決分 real / partial / not_real 三級——**partial 不是和稀泥，
是「宣稱的機制屬實、宣稱的後果或定級要修正」**，修正內容逐條寫明。

底線先講：**清單上的東西絕大多數是真問題**。兩處定級要修正（#29 不是 P0；#31 的「truncated」
措辭與實際擋板不符），regression 的描述不精確（零 revert——實際是「平行路徑未繼承防護」＋
「新指令複製從未修過的弱預設」，修法不同）。

---

## A · 確認為真（原樣成立）

### A-1 · P0-1 snapshot 超預算無限重試 🔴

[StructureManager.java:253-257](../forge/src/main/java/com/blockreality/impl/server/StructureManager.java:253)：
超預算的 `return` 在 `dirty = false` **之前**，也不設任何退避狀態。下一 tick `dirty` 仍 true、
`inFlight` 仍 false、`ticksSinceSolve` 只增——條件原樣成立，原樣重跑整份 snapshot，原樣丟棄。
`structural` 只增不減（unloaded chunk 的位置刻意 skipped-not-forgotten），世界大到一次超標之後：
該維度分析永不執行、無任何錯誤訊息、主執行緒每 tick 恆定損失全額 snapshot 成本。無恢復路徑。

### A-2 · P0-2 worker 失敗 `inFlight` 永久卡死 🔴

[StructureManager.java:263-272](../forge/src/main/java/com/blockreality/impl/server/StructureManager.java:263)：
`try/finally { inFlight.set(false) }` 只包主執行緒的 `apply()`；背景執行緒的
`sidecar.solve(request)` 裸奔。solve() 一拋出（併發 close 的 NPE、`requestBytes` 溢位的
BufferOverflowException、任何意外 RuntimeException），`getServer().execute` 不會執行，
`inFlight` 永久 true，該維度分析迴圈死鎖，`/br reset` 救不回。

**考古註記：這不是 regression。** 逐版比對六個版本（`3fcc2a3` 起），executor lambda 逐字未變，
初版就沒有外層 try/finally。是原生缺陷，第一次就該有測試。

### A-3 · P0-3 荷載必須只掛本次 snapshot 實際收進的方塊 🔴

[StructureManager.java:301-306](../forge/src/main/java/com/blockreality/impl/server/StructureManager.java:301)：
荷載迴圈的守衛是 `structural.contains(pos)`，而不是「這一輪真的進了 builder」。unloaded chunk
的方塊被跳過但留在 `structural`——荷載進請求、方塊不進。引擎端
[main.cpp:1210-1218](../sidecar/main.cpp) 對 load-on-no-element 全域整批拒絕（#14 的設計），
`apply()` 對 `!ok` 只發 status 就 return。結果：測試荷載掛在會被卸載的 chunk ⇒ 整個維度分析
靜默熄燈。

`87c6114` 新增的 stale-load 清理（方塊消失時移除荷載）**沒有覆蓋這個 case**——unloaded-chunk
的跳過路徑未被觸碰。

### A-4 · #27 SHM framing（5/5 條屬實）

- doorbell 只帶 `op` + `revision`（[ProtocolCodec.java:53-55](../mod/core/src/main/java/com/blockreality/core/protocol/ProtocolCodec.java:53)，
  javadoc 自稱 "Carries no numbers — that is the point"）
- C++ 把整個 mapping 當 request 合法範圍：[main.cpp:1707](../sidecar/main.cpp)
  `brshm::Reader rd{ g_shm.data(), g_shm.data() + g_shm.size() }`——frame 邊界完全由 frame 內
  自述的 counts 決定，count 被污染可讀到上一幀殘留 bytes 而不觸發 truncation 檢查
- C++ 寫 reply 時**有**寫 `bytes`（[main.cpp:1783](../sidecar/main.cpp)），Java **丟棄不用**：
  [SidecarClient.java:187/199](../mod/core/src/main/java/com/blockreality/core/sidecar/SidecarClient.java:187)
  只讀 ok/error/revision 就把 `shm.buffer()`（整個 mapping）丟給 decoder
- 更深一層：[BinaryCodec.java:120-121](../mod/core/src/main/java/com/blockreality/core/protocol/BinaryCodec.java:120)
  decode 內部 `duplicate().clear()`——就算呼叫端設了 limit 也會被重設回 capacity
- decoder 無 exact-consume 斷言；現有防護只有 magic/revision/ok 頭檢、count 上限 10M、try/catch

修法成本低：長度資訊已在 wire 上，Java 端撿起來用即可。

### A-5 · #28 binary wire 寬鬆解碼（6/6 條屬實）

- `governingKind` 非 1/2 → 空字串；`singular` 非 1 → false；shell flags 未知 bit 忽略
  （[BinaryCodec.java:140/152-153/237-239](../mod/core/src/main/java/com/blockreality/core/protocol/BinaryCodec.java:140)）
- C++ request flags / block flags 只看 bit0（[main.cpp:1719-1720/1735+1744](../sidecar/main.cpp)）
- member 與 shell token 走**同一張合併表**（sections 接 plates），兩端都只驗範圍不驗角色
  （[main.cpp:1739-1743](../sidecar/main.cpp)、[BinaryCodec.java:281-286](../mod/core/src/main/java/com/blockreality/core/protocol/BinaryCodec.java:281)
  的 `at()`）——corrupt frame 讓 member 指到 plate token 會被接受，靜默改變元素類型
- C++ encoder 未知 fibre → NONE（靜默、與真 NONE 不可區分）；未知 token → `-1`
  （這個**會**被 Java `at()` 擋成 failed，屬 fail-closed）
- reply 的 D/C、buckling、stress、geometry scalar 在 Java 端**零 finite 檢查**，NaN/Inf 直達
  `AnalysisResult`；只有渲染端各自點狀防禦，判定路徑無防

### A-6 · #30 JSON request schema 寬鬆（實質全真）

- `arr()` 對缺鍵/錯型別回空陣列、`boolean()` 對錯型別回預設
  （[json.hpp:44-47/65-69](../sidecar/json.hpp)）；parser 只解析前綴、不拒 trailing tokens
  （[json.hpp:76](../sidecar/json.hpp)）
- `loads:{...}` → 0 loads、typo `load` → 0 loads、`blocks:"..."` → 0 blocks、
  `support:"true"` → false（[main.cpp:1325/1341/1356](../sidecar/main.cpp)），無任何容器級 schema 檢查
- **最毒的一條**：`loads` 蒸發後因自重恆開（[main.cpp:687-690](../sidecar/main.cpp)
  "Self-weight is always on"），模型仍完整可解，回 `ok:true` 加看似正常的 maxDC——
  「這結構在我的載重下安全嗎」拿到的是「它在只有自重下安全嗎」的答案。
  這正是 [main.cpp:1280-1287](../sidecar/main.cpp) 註解自己譴責的 #18 模式：欄位級修了、容器級漏了
- 對照組證明作者知道這個 pattern：同函式的 `buckling` 有型別前置檢查
  （[main.cpp:1382-1384](../sidecar/main.cpp)），只是沒套到 blocks/loads/support
- 有一條天然擋板：空 blocks＋帶 loads 會被 load-on-no-element 全域拒絕——所以「blocks 蒸發」
  多半被攔，「loads 單獨蒸發」攔不住

### A-7 · #33 協定損毀後 session 留在 READY（5/5 路徑屬實）

[SidecarClient.java](../mod/core/src/main/java/com/blockreality/core/sidecar/SidecarClient.java) 逐路徑核實：
malformed shm doorbell（:168-171）、doorbell revision mismatch（:194-197）、bad magic
（BinaryCodec:123-125）、region 內 non-ok frame（BinaryCodec:131-135，註解自己承認
「the two sides disagree about the conversation」）、JSON reply revision mismatch
（ProtocolCodec:115-119）——**全部只回 failed，不 kill、不丟 shm、status 留 READY，
且連 `consecutiveFailures` 都不扣**：持續性 wire 損毀永遠升不到 RECOVERING/DISABLED。
[ProtocolCodec.java:104-110](../mod/core/src/main/java/com/blockreality/core/protocol/ProtocolCodec.java:104)
的 javadoc 自己寫著 mismatch 代表 client/sidecar lost sync。

緩解：每次 solve 開頭的 `process.drain()` 能讓 stdio 錯位在下一筆自癒；但該筆已丟、session
未標記、shm 區域損毀無自癒。engine 語意拒絕（unknown token、load on no element）留 READY
是刻意且正確的設計——缺的是第三分類（desync）的升級處置。

### A-8 · #34 handshake 寬鬆 + catalogue 即 ABI（4/4 條屬實）

- `decodeHello` 除 `protocol` 欄位外全面寬鬆：缺 engine → "unknown"、缺 lists → 空、
  plate 缺 id → 空 token、duplicate 不拒、section/plate overlap 不拒
  （[ProtocolCodec.java:88-102](../mod/core/src/main/java/com/blockreality/core/protocol/ProtocolCodec.java:88)；
  [EngineCatalogue.java:48-52](../mod/api/src/main/java/com/blockreality/api/EngineCatalogue.java:48)
  註解宣稱 "the two never overlap" 但無處強制）
- SHM 以 catalogue **順序**作 binary index ABI，兩端 by construction 一致、wire 只有 range check
  （[main.cpp:1515-1517](../sidecar/main.cpp) 註解自承）——hello 一旦被寬鬆 decode 吞掉畸形，
  in-range 的錯 index 靜默解到錯的 material/section
- 正面宣稱也屬實：`ensureReady` 對 protocol mismatch 是**永久 DISABLED**
  （[SidecarClient.java:274-283](../mod/core/src/main/java/com/blockreality/core/sidecar/SidecarClient.java:274)），不 fail-open

### A-9 · evidence provenance（P1，先前已覆核）

出貨的 [verification.json:5-7](../evidence/verification.json) 引擎身分
`commit: "unavailable"`、`worktree_clean: false`；[evidence.py](../scripts/evidence.py) 的 git
失敗靜默回 unavailable，gate 判定不含 identity 條件——identity 失效照樣 PASS 照樣出貨。
1.2e-14 的對照組獨立（非自我印證），但無法證明是哪個引擎產生的。

### A-10 · token-specific plate gates（P1，真缺口）

catalogue 宣告三種 plate（[main.cpp:135-142](../sidecar/main.cpp)：`concrete_slab_200/150`、
`steel_plate_20`）；[verify.py](../sidecar/verify.py) 所有殼 gate 硬編 `concrete_slab_200`
（:683 的 `slab()` 預設參數無人覆寫，另兩個 token 全檔零出現）。hardening 分支對 verify.py
新增的 +37 行是 timber/brick **樑斷面** gates（C15），與 plate 無關。
按鐵則 2「沒有 gate 執行過的能力不得寫進能力清單」：宣告 3 種、gate 1 種，是真缺口。

---

## B · 確認但定級/措辭要修正

### B-1 · #29 revision 2^53 —— 機制屬實，P0 過高，實為 P2 契約衛生

三域不一致**屬實**：Java `WorldRevision(long)`（[0, 2^63-1]）、JSON doorbell 經 double 且
明確拒絕 >2^53（[main.cpp:1312-1315/1700-1703](../sidecar/main.cpp)）、SHM frame 完整 64-bit
（lo/hi 兩個 u32）。但兩條關鍵修正：

1. **UB 定性不成立**：`static_cast<long long>(revHi) << 32` 在本專案的 C++17 下是 defined shift
   （E1 非負、乘積可表示於 unsigned long long），高位 set 時的轉換是 implementation-defined
   （gcc/mingw 保證 mod 2^64 迴繞），且迴繞出的負值會被
   [main.cpp:1716-1717](../sidecar/main.cpp) 的 doorbell/region skew 檢查攔下。
2. **無靜默 stale 路徑**：revision >2^53 時，doorbell 要嘛被 :1701-1703 顯式拒絕，要嘛 round 後
   與 SHM frame 精確值不等、被 skew 檢查拒絕——兩條路都是顯式錯誤。且 revision 每次世界編輯 +1，
   到達 2^53 需 ~9×10^15 次編輯，實務不可達。

該修（統一 domain、doorbell 不經 double），但它是契約衛生，不是安全洞。

### B-2 · #31 JSON reply 補預設 —— codec 全真，後果宣稱要修正兩點

codec 層**全數屬實**：`ok:true` 時 `singular=false`、`maxDC=0`、`governing=-1`、空 members/shells
等 permissive 讀取逐一在
[ProtocolCodec.java:124-146](../mod/core/src/main/java/com/blockreality/core/protocol/ProtocolCodec.java:124)
確認（三個刻意例外用 Optional.empty() 不用零補：shell field 角點不足、field 非 object、naY/naZ）。

後果修正：

1. **「truncated」字面不成立**：位元組級截斷缺右大括號，parser fail → `failed("malformed reply")`，
   且有測試釘著（ProtocolCodecTest `truncatedReplyIsAFailureNotAnException`）。危險情境是
   **語法完整但缺欄位**的 `ok:true`（版本錯配/引擎 bug）。
2. **擋板存在但有洞**：`isUsable()`（要求 members/shells 非空）擋住了 `lastAccepted` 承諾軌與
   HUD 數字。但——
   [StructureManager.java:337/349-350](../forge/src/main/java/com/blockreality/impl/server/StructureManager.java:337)：
   `latest = result` **無條件寫入**、`gate.acceptForCommit(result)` 的**回傳值被忽略**、
   `BRNetwork.sendResult` **無條件廣播**（netcode 是目前唯一存在的承諾軌消費者，卻不看 gate 裁決）；
   `/br status` 的 else 分支無 isUsable 守門，會印綠色 `0 members, max D/C 0.0000`。
   崩塌觸發與承諾軌 D/C 消費者目前不存在（`lastAccepted()` 全 repo 零消費者）——一旦未來消費者
   接 `latest` 或 `sendResult` 而非 `lastAccepted`，假安全值直通。

### B-3 · #32 grow/fallback —— 核心屬實，「各種失敗」涵蓋過廣

屬實且與自家註解（"A TRANSPORT failure … downgrades to JSON and keeps playing"）直接矛盾的：
grow 單次重試即放棄、該次 solve 回 failed 而非退回 JSON
（[SidecarClient.java:176-191](../mod/core/src/main/java/com/blockreality/core/sidecar/SidecarClient.java:176)）；
全部 reply 損毀路徑 shm 保留、下一筆照用同一塊可能已中毒的 region。

修正：region **建立/映射/上限類**失敗確實會可靠降回 JSON（reopenShm 開頭先 closeShm，失敗後
shm==null，之後所有 solve 走 JSON；三處 log 都寫 "staying on JSON"）。缺的是損毀路徑的降級。

---

## C · 不同意的描述：「已修過的 bug 被新功能 commit 帶回來」

git 考古（`-S` 全歷史 + 關鍵檔逐版 diff）：**零 revert**。四條線索全部查無「先修後拆」：

| 線索 | 事實 |
|---|---|
| ±30M 座標 guard | `1cf4e05`（8/14）加入 JSON parse 層，**此後一行未被移除**，`abc8e78` 仍完整在場 |
| fail-closed parse 全套 | 同 `1cf4e05` 進；後續 `d903d73` 是強化不是弱化 |
| tick() 的 try/finally | 六版逐字相同，**初版就沒有**——原生缺陷，非 regression |
| AnalysisExecutor static pool | 出生即 static + shutdownNow；唯一後續修改只調 pool sizing |

事實符合的描述是兩種不同的病：

1. **新增的平行路徑未完整繼承既有防護**——`7d0f84e`（8/20）的 shm 二進位解碼器**部分重實作**了
   防護（duplicate-coord、index range、finite force；solve 層共用的 load-on-no-element 未被繞過），
   但漏了座標範圍等 parse 層 guard。時序：防護先進（8/14）、新路徑後至且未繼承（8/20）。
2. **新指令複製了從未修過的弱預設**——`87c6114` 的 `/br load` 沿用 `/br scan` 一貫的權限 0
   （`git log -S "hasPermission"` 證明 scan 的權限 0 從無修正 commit 可被 revert），任何生存玩家
   可對他人伺服器的任意方塊加任意力向量進共用解算。

這個區分不是咬文嚼字，因為**修法不同**：regression 用「防 revert 的釘子測試」鎖；
未繼承用「parity gate」鎖（同一請求走 JSON 與 SHM 必須同判定）；弱預設用「結構性枚舉測試」鎖
（Brigadier 樹上所有 literal 除白名單外斷言 `requires ≥ 2`）。上游 `1174e69`
（hardening 分支，8/20 15:25）的做法正是補 parity——36 條 binary wire gates，commit 自述
「binary wire is not a side door」。

---

## D · 修復現況與鎖死建議

**現況**：#27/#28 的大半與 #32/#33 的修復**已存在但只在未合併的 `claude/purity-shm-hardening`
分支**（`1174e69`）；#32/#33 已被關閉，但 main（`abc8e78`）上尚未修。關 issue 的依據是
未合併分支——這本身值得留意（「檔案在」不算「有」，同理「分支上修了」不算「修了」）。

**鎖死順序**（前提先行）：

1. **TEST-1 先修**：目前沒有任何自動化 gate 執行 Java 測試（CI 零測試、`package.sh` 明確
   `-x test`）。這條不修，以下全部只是「檔案在」。
2. **兩傳輸 parity gates**：同一請求 JSON vs SHM 必須同 accept/同 reject/同數值——結構性地抓
   「新路徑沒繼承防護」這一類病，不必逐條列舉防護項目。
3. **指令權限枚舉測試**：新指令自動被抓，不靠 review 記得。
4. **生命週期 JUnit**：pool 關閉後重建（P0 crash）、solve 拋出後 inFlight 復位（P0-2）、
   unloaded-chunk 下 loads 與 blocks 同進退（P0-3）、snapshot 超預算的退避（P0-1）。
   這四條是原生缺陷，第一次就該有測試釘住。

## 附錄 · 裁決總表

| 項目 | 裁決 | 一句話 |
|---|---|---|
| P0-1 snapshot 無限重試 | 真 | 超預算 return 不改狀態，livelock + 每 tick 全額成本 |
| P0-2 inFlight 卡死 | 真（非 regression） | finally 只包 apply，solve 裸奔；初版即如此 |
| P0-3 load 掛未 snapshot 方塊 | 真 | 守衛是 contains 不是「本輪收進」；87c6114 未覆蓋此 case |
| #27 SHM framing | 真 5/5 | bytes 在 wire 上但 Java 丟棄；decoder clear() 重設 limit |
| #28 binary 寬鬆 | 真 6/6 | 角色錯配 token 被接受；NaN/Inf 直達 AnalysisResult |
| #29 revision 2^53 | 部分真 | 域不一致真；非 UB、無靜默路徑、邊界不可達——P2 非 P0 |
| #30 request schema | 真 | loads 蒸發 → 自重-only 模型 ok:true，最毒 |
| #31 reply 補預設 | codec 真／後果部分 | 位元組截斷有測試擋；latest/廣播/status 無守門 |
| #32 grow/fallback | 部分真 | grow 單試即棄真；建立類失敗確會降 JSON |
| #33 desync 留 READY | 真 5/5 | 連失敗預算都不扣，永不升級 |
| #34 handshake | 真 4/4 | 除 protocol 外全寬鬆；catalogue 順序即 ABI |
| evidence provenance | 真 | identity unavailable 照樣 PASS 出貨 |
| plate gates | 真缺口 | 宣告 3 種 plate、gate 1 種 |
| 「regression 帶回來」 | 不同意 | 零 revert；是未繼承 + 弱預設複製 + 兩條原生缺陷 |
