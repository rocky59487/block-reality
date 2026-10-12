# V2 Forge integration — W2–W6 分階段設計

狀態：**DESIGN / NOT IMPLEMENTED**。2026-10-10。這次交付只增加本文件；沒有修改
Java、native、contract、fixture、threshold 或發行物。本文的類別、儲存格式、新動詞與
預算凡標示「擬」者均非現有 API，也不表示已取得遊戲或效能資格。

## 0. 基線、權威與結論

| 盤點來源 | 精確版本／用途 |
|---|---|
| block-reality `Main` | [`f19b834e9c5fb5cc1ac23c62caddbc4be56268b0`](https://github.com/rocky59487/block-reality/tree/f19b834e9c5fb5cc1ac23c62caddbc4be56268b0) |
| tectonic2 `main` | [`342d8173f0b900681a8a6ff44413ff664064ecab`](https://github.com/rocky59487/tectonic2/tree/342d8173f0b900681a8a6ff44413ff664064ecab) |
| 已釘 SDK | [native-release.json](../../native-release.json)：`v2.0.0-alpha.1` / `2.0.0`，source `ddaaa92c930393051b67e75f7ccfbd5224580b16`；**不是上述 main 的任意新 build** |
| contract | [BSI.md](../../contract/BSI.md)、[schema](../../contract/bsi.schema.json)，hash `77d78df7a530a8db12e8eafd4db83baaf3c12082f0985668b835632178246e94` |
| 引擎交付範圍 | [V2_DELIVERY](https://github.com/rocky59487/tectonic2/blob/342d8173f0b900681a8a6ff44413ff664064ecab/docs/program/V2_DELIVERY.md)、[backlog](https://github.com/rocky59487/tectonic2/blob/342d8173f0b900681a8a6ff44413ff664064ecab/docs/program/backlog.json)、[NATIVE_SDK](https://github.com/rocky59487/tectonic2/blob/342d8173f0b900681a8a6ff44413ff664064ecab/docs/NATIVE_SDK.md) |

已讀兩倉 `CLAUDE.md`。D-047 舊「模組限定」不限制本次兩倉設計；本次仍**只做文件**。
`ENGINE_BOUNDARY.md` 含已被 dated 追記取代的 sidecar／Java 應力舊段，不能拿它們
推翻 D-033/038/044。引擎 backlog 的 active/planned 不等於能力完全不存在，也不等於
alpha 已完成 v2。SDK/native gate 的成功不替代 Forge caller、普通安裝、真玩家與存檔驗收。

**建議交付順序**：W2 持久來源 → W3 有限分析／資格 → W4 帶載破壞交易 →
W5 可恢復的共同 rigid scene → W6 重啟／拆裝閉環。每階段都有獨立可驗出口，但不能把
W3 的非線性數字或 W5 的一次落下 demo 稱為完整可玩 v2。

三個必須先正視的缺口：

1. 每 revision 全量 `world.declare` 的線性路徑會丟失有限材料歷史；必須有來源 store
   及長存 finite session，不能只把 `bsi.solve` 改名。
2. 現有 `NativeFractureTransactions` 是自重／線性 receipt 的同步框架；沒有正式 Forge
   Host。直接在其 `publish()` 裡做 native finish 會讓 server tick 等全域 native 鎖。
3. ABI7 `bsi.corot.rigid.declare` 要求**同 handle、精確已提交 fracture token、未變世界**。
   ABI8 checkpoint import 產生的是新 analysis token，不是 fracture token。
   ABI4 reference-cell `rigid.declare` 也不能代替已塑化幾何。多次不同倒塌合併、有限碎塊
   跨程序恢复不能靠「保存 token／mesh」完成；W5 要加可攜 finite source 的 native contract。

### 0.1 本次確認的接線位置

| 現有程式 | 保留／新增的界線 |
|---|---|
| [GameNativeLoader](../../mod/core/src/main/java/com/blockreality/core/engine/GameNativeLoader.java)、[NativeGameRuntime](../../mod/core/src/main/java/com/blockreality/core/engine/NativeGameRuntime.java) | 保留載庫、off、generation 撤銷、worker cleanup；擬新增 finite runtime，不讓 reset 清除 durable history |
| [InProcessEngine.analyze](../../mod/core/src/main/java/com/blockreality/core/engine/InProcessEngine.java) | 現在 `declareWorld` → 線性 `bsi.solve`；保留此 API／舊 bytes，增加獨立 finite methods |
| [GameWorldSnapshot.identified](../../mod/core/src/main/java/com/blockreality/core/engine/GameWorldSnapshot.java) | 現在拒絕非空外載、用 observational graph owners；不能直接当 W2 manufactured authority |
| [NativeFractureTransactions](../../mod/core/src/main/java/com/blockreality/core/transaction/NativeFractureTransactions.java) | 保留 replay／planHash／durable decision 原則；finite 另加 typed receipt 與非阻塞 orchestration，不偷改舊 Options 語意 |
| [AtomicConstructionCoordinator](../../mod/core/src/main/java/com/blockreality/core/transaction/AtomicConstructionCoordinator.java)、[ConstructionService](../../forge/src/main/java/com/blockreality/impl/server/construction/ConstructionService.java) | 一個 global construction owner、同一 journal；現有 Host 是同步且 server-thread-only，不是可直接丟 worker 的物件 |
| [BsiNative](../../mod/core/src/main/java/com/blockreality/core/engine/BsiNative.java)、[AnalysisExecutor](../../forge/src/main/java/com/blockreality/impl/server/AnalysisExecutor.java) | 本文以使用者提供的 process-wide serialization 為 W1 前置。盤點 Main 尚為 per-instance synchronized；整合時要驗證已完成修補的來源，不在本設計重做 |

## 1. 不可妥協的邊界

- **C++ 唯一力學權威**：提取／約束／求解／強度與失效／材料歷史／質量慣量／速度交棒／
  CCD／接觸／摩擦／睡眠全在 native。Java 可以驗資料範圍、保存 bytes、核對身份、管理物品、
  轉換座標與插值畫面；不能重算 D/C、積分重力、依損傷百分比折減、把紅色 HUD 變成斷裂。
- 公開 CAPI1 五函式與 BSI1 frame 繼續使用；engine ABI7–10 是能力擴充，**不是 JNA
  應改用 vtable**。新功能依 `hello` capabilities + 精確 contract hash + build provenance
  admission；缺能力就拒絕該功能，不回退線性假裝 finite 成功。
- PREPARED 不對玩家、registry、native authority 或網路發布。COMMITTED 不得回滾；
  lost ACK 由 journal 裁決，不能再做一次破壞。preview 不消耗永久身份／材料歷史。
- 原 CT-1..9、首次 FAIL、原 CTP mixed-link removal、非零 prescribed arc、殼裂損、
  一般 articulated/released/tension-only 碎塊、Linux pins／S1／G2／P-Delta 邊界全部保留。
  fail-closed 是安全邊界，不是這些必交功能已完成的證據。
- 每個 future 都攜帶完整 basis；過期結果只能丟棄，不能只比較座標或一個 revision。
  native call 逾時只撤銷發布權，不可強殺 JNA、不在 tick `close()` 或等待鎖。
- engine `core/` 變更前必須先以**獨立 commit** 凍 `docs/specs/` oracle／硬線／邊界；
  本設計不是可代替該 spec 的 implementation commit。contract 改動兩倉逐位同步。

## 2. 分階段交付與進入條件

| 階段 | 可交付結果 | 必要依賴／拒絕條件 | 不可混稱 |
|---|---|---|---|
| W2 | stable owner mapping、有限來源 store、history checkpoint 重開、修改可追溯 | W1 native serialization；CT recovery 完成；完整已知 structural／support 觀測 | registry SavedData ≠ native history；mapping ≠ 力學 |
| W3 | 真 Forge finite solve／arc、資格 HUD、commit 與 preview 分離 | W2；材料目錄與 solver options 凍定；同 basis 的 worker completion | arc ≠ 真實時間；J2 yielding ≠ 殼裂損 |
| W4 | 自重＋外載下原生 receipt → chunks／metadata／checkpoint／碎塊出生共同提交 | W3；同一 construction arbiter；完整 after checkpoint 可先建立；破壞 hook admission | 線性 fracture helper ≠ finite Forge Host；commit ≠ 已落下 |
| W5 | 多出生批次共同 scene、native step、pose／mesh、chunk suspend/resume | W4；§11 A1/A2 新 contract；完整地形、scene 及 state 原子存檔 | 各批獨立不碰撞的 demo ≠ 共同物理；假立方體 ≠ finite geometry |
| W6 | restart/unload/reinstall、無重複物品、普通 jar 雙平台證據 | W2–W5；保留 retired history；產品問題 P1–P7 定案 | 原位置重裝 ≠ 任意移位／切割／重組；遊戲 gate ≠ 所有 engine 數值债清償 |

實作每階段以「oracle/spec → codecs/store → caller → crash/mutation → 真 Forge」完成。
可並行做 W2 codec 與 W3 decoder、W4 participant 設計與 W5 renderer；不可在 W2 durable
authority 尚未成立時讓 W3 commits 或 W4 世界破壞上線。W5 native A1/A2 可先獨立驗，
正式 caller 仍等 W4。最終收版还須 V2_DELIVERY 的 A/B/C 出口，不由本表豁免。

## 3. Java 資料模型與時鐘（擬新增）

以下 immutable records 置於 `mod/core`，不得引用 Minecraft 類別；Forge adapter 只負責
抓取／套用。`byte[]`、lists、ByteBuffer 要 defensive copy/read-only，不暴露 native pointer。

| 型別 | 必存內容／不變式 |
|---|---|
| `FiniteDomainId` | construction journal domain、dimension key、獨立 persisted BSI domain、artifactNamespace；每 dimension 一個有限 authority 起步，不假設能任意切島 |
| `ArtifactBinding` | 原 manufactured UUID 或 legacy provenance key → positive signed-i64 nativeOwnerId；birth tx、完整 declaration、現存 cells、退休／譜系；單 namespace 單調分配、永不回用 |
| `FiniteBasis` | domain、worldRevision、historySequence、sourceHash、vocabHash、ownersHash、sessionGeneration；token 只在 worker live view，不當磁碟 authority |
| `FiniteSource` | 精確 vocab request＋映射回覆、canonical blocks40、完整 owners20、材料律152、shell laws56/layers16、cellLoads64、prescribed64、generalizedLoads144、完整 options bytes |
| `FiniteCheckpointRef` | engine build／contract hash、format、basis、blob SHA256、length、byte/allocation/element budgets；opaque blob 原位保留，不 round-trip 浮點 JSON |
| `FiniteResult` | basis、fullyConverged、逐島 status／available／reason、完整 native section bytes與驗過的 typed views、loadFactor、limits；mechanics 與 history 可用性分開 |
| `FiniteFracturePlan` | server request UUID、actor/system provenance、before/after basis、完整 prepare request、receipt、afterSource／checkpoint ref、bornBodies、retired ownership、load ledger、planHash |
| `RigidBodyRecord` | server persistent positive i64 bodyId、birth domain/namespace/request/group/fragment、parents/source cells、A1 portable source ref、geometry hash、material archive ref、life status |
| `RigidSceneRecord` | independent scene domain/revision、body-set hash、terrain/coverage hash、topology tx、scene-source hash、motion generation；所有 body 同一 scene 時間 |
| `RigidStateBatch` | scene revision、step sequence、request UUID、輸入 state hash、完整 states128＋report104＋sleep/wake ids、durable sequence；不以 entity id 取代 bodyId |

### 3.1 六種序號不可混用

1. `worldRevision`：可見 structural/source 變動序；與 `identity.expected` 一致。
   identified `world.edit`／fracture 成功恰 +1；envelope revision 只是回音。
2. `historySequence`：同一 world revision 的有限 load/arc/material commits；由 host 持久排序。
   新 sequence 不假增 native worldRevision。HUD basis 同時比兩者。
3. `metadataOrder`：既有 server-global CT COMMITTED metadata 次序，不重設。
4. `sceneRevision`：地形、body 集合／來源改變時遞增；與 structural revision 不綁死。
5. `body.revision` / `body.time`：由 `rigid.step` 回覆；成功各 body +1、time +dt。
6. `sessionGeneration`：reset／close／timeout／reload 的 in-memory 撤銷域。新 session 的
   native context/token 不能與舊 session 比 bytes 冒充同一權限。

### 3.2 Observed 與 manufactured 身份

`ConstructionLedger` 的 grouping namespace/ID 與 `ManufacturedRegistry` 的 piece UUID
是兩種語意。相鄰同材 piece 不可因 observed merge 而共享一個可退款身份。
W2 建立 journal-owned mapping；新施工用 piece UUID 固定 owner，切割後的 lineage 由
native source 與 CT 事實建帳，不依 element index。legacy 只做一次明示 adoption：保留
舊 observed key、完整 declaration／cells 與 migration receipt，標 `LEGACY_NO_REFUND`。
未宣告 axis、缺資料或重疊 ownership 不得猜；維持未評估並要求玩家補宣告。
支承格包含在 world source，但 owners 只完整覆蓋 structural cells，不能漏 ground
或把支承誤作可掉落物。已載入順序、重新掃描、重啟不分配新 owner。

## 4. 持久化格式與單一權威

### 4.1 檔案布局（擬 v1）

在既有世界 construction domain 的相鄰儲存目錄新增 `finite/`，共享 world lock 與
construction service owner，不另開一個可競爭寫入的 registry。**journal 中的 committed
root descriptor 是 structural/history 權威**；磁碟 `HEAD` 若有，只是可刪除 cache。

```text
finite/
  objects/<sha256>.blob          # immutable source/checkpoint/receipt/finite-motion-source
  scenes/<scene-domain>/<generation>/
    state-0.bin                 # two alternating complete durable motion batches
    state-1.bin
  staging/<request-uuid>/        # 未決／失敗產物，保留受限證據
```

`objects` envelope 擬為 LE binary：magic `BRV2OBJ1`（8 bytes）、schema u32=1、kind u32、
payloadLength u64、payloadSHA256[32]、payload。檔名 hash 指完整 envelope；root descriptor
同時記長度與 hash。typed metadata 使用固定整數＋長度前綴 UTF-8，不用 Java serialization。
UUID→wire hex 以高 64 位在前、32 字 lowercase hex；正整數檢查 signed-i64，禁止 wrap。
列表明確計數、規範序、reserved=0，拒 duplicate／unknown version／trailing bytes。
f64 原始 bytes 保留；wire 允許的診斷 infinity 不擴大成所有數值可非有限。

擬 journal resource keys：`finite/domain/<dimensionHash>`、`finite/owner/<namespace>/<id>`、
`finite/root/<domain>`、`finite/body/<bodyId>`、`finite/scene/<sceneDomain>`。
root 記 schema、world/history seq、source/checkpoint refs、body/scene index refs、上一 root hash、
決定 request UUID。這是**CT resource schema 加法**，不是擅自塞進現在只認 BUILD/EDIT/UNDO
的 metadata decoder；須版本化 operation 與 migration，未知新 schema 由舊版拒載。

### 4.2 落盤與限制

1. bytes 在 worker 先驗範圍／digest；same-filesystem staging → force file → atomic move →
   force destination／支援的平台 directory → reopen readback。無 atomic replace 不假成功。
2. 必要的全部 objects 都 durable，才允許 PREPARED 引用；after checkpoint 不能等 COMMITTED
   之後才第一次 export。CT 原有 1 MiB value／16 MiB record／8192 resources／4096 cells／
   256 pieces 與總容量不變。大 blob 由 bounded digest ref 引用，**不是放大 CT 門檻**。
   任何物件丟失／外來 bytes 令相關域 fail-closed，不重建一份空白歷史。
3. 擬額外 admission：單 object payload 64 MiB、單 staged batch 256 MiB、每世界 objects
   4 GiB、staging 256 MiB；與原 CT 1 GiB 日誌分開計費且兩者都檢查。這是待凍的新容量，
   不改既有門檻。byteBudget 等傳 native 必須 <= host admission；frame 硬上限依 contract。
4. objects immutable；未經 COMMITTED 引用的不產生可見世界。失敗檔保留到有明示清理／
   證據匯出，容量不足拒絕新寫入。初版不做 history GC，永不刪去去重 keys／retired IDs。
   未來 GC 必須追全部有效 root、PREPARED、兩份 motion checkpoint、reinstall archives。
5. chunks/player 沿既有完整 NBT participant，不只存 block palette／一個 SavedData。
   保留 live capture witnesses、64-depth／tag／16 MiB chunk／64 MiB batch 等原上限。
   玩家 live 與舊 player.dat 正常遊戲差異不是 corruption；於 checkpoint/load/save 邊界
   做完整文件見證，不用「live saveWithoutId 必等於舊 disk」擋正常 gameplay。

### 4.3 Motion 不能每 tick 消耗永久 CT key

CT 的 262144 lifetime key 不可藉 eviction 避開；20 Hz 每步一筆會很快耗盡。
擬採專用 **scene kinematic checkpoint lane**，但不給它世界／物品／身份提交權：

- CT durable descriptor 錨定 scene generation、body set、terrain/source hash、initial state。
  新 generation 在 topology COMMITTED 前先建立並 force/readback 兩份相同 sequence=0 的
  initial slot。該 generation 的 worker 單寫 `state-0/1`，每筆是**完整 scene batch**，不是 per-body partial。
- envelope 含 scene/generation、sequence、前序 state digest、input request digest、全部 state
  與 checksum。inactive slot 原子替换／force/readback 成功後，該 sequence 才可發布、做下一步。
  不另以不安全 HEAD 決定成功；startup 驗兩份有效 sequence/鏈，最高完整匹配者為最新。
  僅初始兩份可同為 sequence=0 且須等於 CT initial digest，之後兩份須為相鄰 sequence。
  部分 staging 保留；任一已命名 slot 損壞、缺失或來源不符均拒絕，不默默倒回舊動作。
- 新出生／移除／地形 scene 變動／拾取／重裝，先 fence 舊 writer，排空在途 I/O，取 durable
  batch，再走 CT 新 generation。COMMITTED 後舊 writer 即使晚回也不能覆寫新 generation。
- HUD／render 只發 durable pose，控制模式預設不把未落盤步展示為 authoritative。
  一個純 motion step 不產生 items／entity damage／結構損傷；若產品要這些副作用，該次
  事件另走 CT 同一決定域，不能靠兩槽存檔宣稱 exactly-once damage。

## 5. 執行緒與 construction 整合

### 5.1 Owner 與鎖序

| Owner | 可以做 | 不可以做 |
|---|---|---|
| server tick | permissions／live snapshot、chunk coverage、lease admission、檢查完成通知、有限量原子 apply、S2C publication | JNA open/call/close、等 process-wide lock、join/get worker future、等 fsync、泵 main task queue |
| `NativeLane`（擬） | 所有 BSI 呼叫／NEED_BIGGER exact retry、private session、typed decode、checkpoint export、native cleanup | 存取 Level／Player／chunk live objects、發包、持有 CT lock 等 tick |
| persistence lane（擬） | immutable bytes hash／force／journal decision／readback；原 IOWorker 的 async barrier | 操作另一 RegionFileStorage、偷偷重寫不認識的 NBT、從 timeout 推定 rollback 完成 |
| client | 只接受同 epoch scene/body states；native mesh pose 插值、HUD 翻譯 | native authority、碰撞求解、預測積分、由 f32 顏色決定失效 |

所有 session 最後仍經 BsiNative process-wide serialization；選一個公平 native queue，
不要以多個 native worker 提高 OpenBLAS 平行度。最多一個在途 call，優先處理已入交易的
finish／cleanup 與 motion，之後 interactive commit，再 preview；各類保留配額避免飢餓。
每域最多一個 compute job＋一份「最新未派工」快照，preview 可合併，已 admitted transaction
不可丟棄。queue wait 與 native time 分開量測。現有 AnalysisExecutor 使用無界 queue，
不能從它的「bounded」註解推斷已具 backpressure。

不用 native-lock→CT-lock 或 CT-lock→native-lock 的巢狀等待。tick 只投遞 immutable message；
worker 回覆 basis/lease id。reset/timeout 立即撤銷 generation，不需取得 native monitor。
若 call 永不返回，整個 process 的 native lane 可能都停住；顯示故障、停止新 native admission，
保留存檔，需 operator restart。不可宣稱其他 dimension 能繞過 OpenBLAS lock 繼續。

### 5.2 同步 CT 是真實衝突，不是換 Executor 即可解決

現有 coordinator 的 `execute`／Host `checkpoint/write/flush/publish` 皆同步；Forge Host
會操作真 Level、再 await IOWorker。把整個 Host 放 worker 違反 thread ownership；在 tick
執行 native prepare／finish 違反無等待；只把 flush async 又會令 provisional 世界跨 tick 外洩。

**W4a 必做非阻塞 durable driver**，保留同一 coordinator 的 phase／journal／replay 規則，
不是第二個 fracture coordinator 搶同一 journal。擬加 resumable action API（舊同步 API
保留做相容與 oracle），yield 點只在已定義的 durable barrier；worker 完成事件讓 tick 推進。
每個 barrier 要重新驗 lease、basis、participant digest。全域只能一筆 PREPARED。

可實作的首選：**shadow participant images + mutation/save lease**。

1. tick 在 before-state 取得 global admission lease，抓完整 chunk/player before images。
   native 計算先在 lease 外完成，再 revalidate；不能拿著世界 lease 等長 native solve。
2. 在單一 bounded tick slice 做必要的 protected provisional validation，捕捉完整 after image，
   立即還原 live before。驗證 hook 本身可看候選（原 CT 例外），其他讀者只看 before。
   不支持可逆 capture 的 block entity/capability/hook 明示拒絕；不假裝一般 mod side effects
   可撤銷。fracture 不發放 placement 權限，使用獨立 server destruction/protection policy。
3. lease 期間隔離所有會改 participant 的入口：玩家包、scheduled/random/block-entity ticks、
   explosion/piston、chunk unload、vanilla saves／player saves。**只擋 StructureManager callback
   不足夠**。讀者看 before；immutable after NBT 由原 IOWorker 落盤，tick 不等 future。
4. before baseline durable → PREPARED → complete after participants durable/readback → COMMITTED。
   主執行緒取到 durable decision 才於單一 publish slice 套 live after、更新 metadata/root／
   revision、解 lease、通知。超過可原子套用預算，整筆 admission 拒絕；不分數 tick 半發布。
5. COMMITTED 後 publish 失敗保持 affected domains 不可變／不可分析，startup/recovery
   以最新決定恢復；不可寫 ABORTED。回滾走 disk before images，live 本來仍是 before。

這需要 Forge 儲存與 tick admission 的實際適用性 probe，**不是現有 participant 已支援**。
若不能證明以上 exclusion／shadow capture，W4a 不通過：暫留已知同步 CT 的誠實慢路徑作
控制測試，但不能稱無卡頓 v2，也不能為達 FPS 線降低原 CT 原子性。沒有 native 等鎖的慢
I/O 控制路徑與最終非阻塞路徑分開登錄，禁止以其中一條測試冒充另一條。

## 6. W2 — finite 世界身份與 checkpoint source store

### BSI 使用（現有 contract，無必需加法）

```text
worker: open → bsi.hello → bsi.vocab.declare
        → bsi.world.declare(identity, blocks40 + owners20)
        → bsi.corot.checkpoint.import(expected, format, budgets, bytes) [若有歷史]
```

初始化 body identity 要完整 domain/revision/artifactNamespace/owners、attrs=0。
同世界修改用 identified `bsi.world.edit`：edits41 + **完整 after owners20**，expected 是 before
revision；body identity 與外層 envelope 不混淆。一般 Session 編輯 ABI7 已有，毋須重做。
沒有 edits 但 host source revision 改變時要先定義 native clock mapping，不重宣告同 stamp
清掉 token；W2 初版把非物理觀測 epoch 與 worldRevision 分離。

after edit 接 `bsi.corot.solve(retainLoadPath=true, transferTopology=true)` 才建立轉移後歷史；
拓撲／owner remesh 不支援就整筆拒絕或明示鎖定未評估，不能 fresh solve 擦除損傷。
一般放置、軸向修改、拆支承也都要先經這條 source transition；普通 break／explosion／
piston 不能走無 journal 的旁路。W2/W3 未完成這些入口以前，finite 模式只能在明示鎖定的
測試域使用，不對 survival 宣稱可安全任意建拆。

export/import 記 `expected/format/byteBudget/allocationBudget/elementBudget`；export payload
空，import payload 為完整 blob，fresh handle 先重建相同 source／owners／vocab。
format 1 為基礎 finite、2 保留 arc direction、3 保留 shell tensor 與既有 arc history；
依**已用能力**選格式，不能含 tensor 降成 2。只有無既存 finite history 的世界可 import；
不在已推進 session 匯入舊 blob。匯入會有新 token，來源 hash／資格才是跨程序比較基礎。

### 執行／持久化／玩家行為

- 來源 store 是所有 finite 工作的起點；worker 不讀「目前世界」補缺失來源。
  已知結構／其支承觀測不完整時整域 `WAITING_CHUNKS`，不以空氣補區塊。
- 初次 adoption／source edit 用 CT 持久 root；owner mapping 只於 COMMITTED 可見。
  checkpoint export 與 disk I/O 都離 tick，source basis 不符就作廢 staged bytes。
- HUD 顯示「來源建置中／等待區塊／歷史已恢復／存檔拒絕」，native off 不刪 store。
  引擎版本/hash 不相容時只允許讀取舊世界事實，不靜默以新模型覆寫 blob。

**W2 驗收**：相同 world 不同 chunk 載入序 owner/source bytes 相同；legacy adoption 不退款；
同材相鄰新 piece 不合併 owner；split/delete/re-add 不復活 ID；corrupt/missing source 拒絕
且原檔不變；fresh JVM import 後卸載/重載對照不中斷原生歷史；兩次 restart 相同結果。
compiled mutations：回用 owner、漏 support、跳過 blob hash、失去 format3 分別咬中。

## 7. W3 — finite／arc 分析與資格展示

### BSI／codec（現有 ABI7/9/10）

`bsi.corot.solve` request 依序 materials152、cellLoads64、prescribed64、generalizedLoads144；
opt-in shellMaterials56／shellLayers16 接尾。counts、options、section ordering/stride 以
schema `x-records` 為唯一來源；寫 `BsiCorot`／`BsiCorotResult`（擬）而非濫用線性 decoder。
材料 law 與 failure profile 是有單位、經核定的產品資料，不在 Java 由強度猜硬化率。

`bsi.corot.arc.advance`：空 payload，expected＋initialAnalysis＋radius/minRadius/
lengthScale/loadScale＋完整 solver/path budgets。只從 fully converged token 推進；下一步
用新 token。arc loadFactor／radius／constraintResidual 是契約允許的 JSON scalar 例外，
mechanics／motion f64 仍在 binary，不把所有新輸出改成 JSON。

每次 decode 驗 header id/method、world/domain、namespace、section 完整覆蓋、count×stride
溢位、range／source membership、reserved、整數精度、有限值及明定例外。
`available & 1` 才有 mechanics；`&2` 可能只是未收斂時保留 history，`&4` 才有 material。
`fullyConverged`、逐島 `CONVERGED/MECHANISM/NOT_CONVERGED/UNSUPPORTED/INACTIVE` 均保留，
一個島未評估時不顯示全世界安全。新型別未知碼拒絕／顯示 unknown，不猜語意。

### preview 與材料 commit

`corot.solve`／arc 成功會推進 native 材料世界，**不是免費 read-only 分析**。
preview 用 private handle：從 durable source＋checkpoint restore、solve/arc，讀結果後丟棄；
preview 返回 token 不授權 construction。正式 history commit 也先在 private staging handle
推進、export 新 checkpoint；basis CAS＋durable root transaction 成功後才替換 authority。
原權威 session 在 durable decision 前不變。root-only history 提交需新增 CT operation
語意：增加 historySequence 而非偽造 worldRevision+1；舊 BUILD/EDIT/UNDO 規則保持。

線性結果可另作對照，標示 lane、revision／history sequence、current/stale。finite 不得
沿用舊 `bsi.solve` 過載旗標填空白；現有 corot sections 足以展示姿態、力、材料／耗散與
資格，但未交付的沿桿 display samples／強度 verdict 必須向引擎要（§11 A3）。
不在 Java 從 stress、damage 或 utilized fraction 補算「安全／斷了」。

### 玩家與驗收

擬 UI：「分析」為只讀 preview；「施加载荷／推進」是明確 server command/confirmation，
preview 用虛線／標籤，不把暫態變形寫回 Minecraft blocks。HUD 至少顯示 lane、basis、
native qualification、material history 是否可用、目前 load factor、budget/refusal reason；
en/zh＋朗讀，不用只靠紅綠。nonconvergence 不觸發倒塌。

arc 是靜態續算，不代表物理 dt、不自動將下降段作為落下軌跡。非零 prescribed path
目前明示拒絕 arc，solve 支援的路徑另走 solve。這個 v2 engine 邊界不能由 UI 隱藏。

**W3 oracle**：小變形與獨立線性解析；有限剛體轉動客觀性；兩島共同 load factor；
材料循環／卸載對既有獨立 oracle；arc 方程及方向 checkpoint continuity；unavailable 的
零值不可當無受力。UI 模擬錯碼／stale／mixed islands；compiled mutation 丟 available／
以舊 token apply／preview 污染 checkpoint 都必 FAIL。沿既有 fixture 硬線，不另發明新容差。

## 8. W4 — 帶載 durable fracture Host

### 8.1 呼叫與 receipt

擬 `FiniteFractureService`／`ForgeFiniteFractureHost` 加入**同一 ConstructionService**。
正式接 `bsi.corot.fracture.prepare`，不是目前 payload-empty 的 `bsi.fracture.prepare`。
payload = W3 四組＋failureRules40，shell law/layers 若使用則放 rules 後。
body 含 requestId、expected、budget、rules、完整 options、可選 exact initialAnalysis。
外載非空且需要 generalized loads 必驗 `bsi.corot.fracture.loads`；不丟外載求得好看結果。
cell force 與 generalized catalog 不重複計載，prescribed 始終綁 stable keys。

原生 physical七段＋remaining finite sections＋decision/phase/failure/load ledger/archive/
retired-material 全部保存。`end/flags/pending` 和 final failure complete/available 分開判讀；
budget exhausted／pending／mechanism rollback 不等於已完成倒塌。初版遇未完成級聯不
破壞世界，顯示 native 未完成；若未來允許分段提交，需另凍 path oracle，不隨便增 budget。
group0 remaining／1 broken／2+ fragments、parents、source cells 與 after world 精確對位。
壓碎依材料 phase/mode 與 native 語意；capacity face5 或一般 overload 不是混凝土壓碎。

### 8.2 預先 materialize after checkpoint：解開 crash window

現有 linear helper 是 durable COMMITTED 後才 finish live native；finite 如果照抄，會在
COMMITTED 後、export 前 crash，留下已破壞 blocks 卻沒有最新材料歷史。
首選 **雙 session private staging**，不加「Java 重建歷史」後門：

```text
S0 authority = durable before source + checkpoint
S1 worker private staging = restore(S0)
S2 prepare on staging → copy full receipt → validate
S3 finish(commit) on staging ONLY → export complete after checkpoint
   → [W5] corot.rigid.declare → A1 source export → copy geometry/initial states
S4 force/readback all immutable objects; no published native/world mutation
S5 tick revalidate basis / reserve participants; checkpoint true before images
S6 durable PREPARED → all after participant images durable → COMMITTED
S7 publish root/body birth/blocks/metadata/revision once; adopt staging session on worker
```

S3 的 native commit 只是**私有候選 materialization**，不等於 authority commit；任何可見
session/body/registry 在 S6 前都不能指向它。既有「early native commit」mutation 必須继续
驗**提早改可見 authority**會被抓，不能移走斷言以合理化它。若 staging after export/import
在特定未平衡來源不被 native 支援，整筆入 CT 之前拒絕；不得跳過 checkpoint。
若要避開兩 session 成本，替代方案為 native trial checkpoint export（§11 A4），不是
省掉 durability。S2 prepare 失敗不丟前一候選；staging discard/close 只在 worker。

### 8.3 交易 participant 與崩潰矩陣

同一 Intent 需綁 before/after：cells/chunks、manufactured ownership/undo eligibility、
finite source/root/history、worldRevision、body birth records、scene generation（若有）、
receipt hash/planHash。server/system fracture actor 有獨立 provenance；不能冒用玩家 client
transaction UUID 授權任意破壞。相同 key 不同 loads/material/options/basis 拒絕。

| 中斷點 | 恢復語意 |
|---|---|
| S1–S4，未 PREPARED | before world/history 不變；保留受限 orphan objects；沒有出生可拾取碎塊 |
| before checkpoint 失敗 | 不入 PREPARED；不得把未落盤 live baseline 当舊磁碟值 |
| PREPARED 後、任一 write/force 中 | 驗所有值為已知 before/after；排空舊 I/O；全部恢復 before、force/readback，再 ABORTED |
| decide ACK 遺失 | `journal.verify` 定案；不能依 exception 猜 ABORTED 或重新 prepare |
| COMMITTED 後、publish／adopt 前 | 從 committed after source/checkpoint/body refs 重建；不重播舊 token、不再刪材料或生第二份 body |
| publication 發了一半通知 | durable generation/revision 做 client replace/upsert；實體 UUID 是 bodyId 對應的呈現鍵，不授予第二份所有權 |
| 未知 chunk/player/object bytes | 保留原檔，相關世界進 RECOVERY_REQUIRED；不覆寫猜測修復 |

玩家看見的是原建物 → 一次 durable 移除／碎塊出生。無 W5 能力时，W4 只可在明示
測試模式驗證 retired receipts／靜態佔位，不能開預設生存倒塌。crushing effect 僅純視覺，
不具掉落、傷害或持久物件權威；durable effect outbox 去重盡力重送，不能宣稱 client 畫面
exactly-once（ACK 丟失仍可能重畫），但永不重發 items／body birth。

**W4 gates**：帶載兩柱／混合梁殼、存活載荷延續／退休不轉瞬時衝量；保護取消；拆支承與
同時 placement 競爭；100 identical retry 前後 restart；每 participant/write/force/decision/
publish SIGKILL＋各兩次 fresh JVM recover。真 Forge 验 region/player/native root 一致，
compiled mutants：省略 load hash、先 publish、少 flush、晚 export、重複 body birth。

## 9. W5 — native scene／step、chunk lifecycle 與 pose

### 9.1 一次出生可直接用的現有 API

同 private staging handle S3 之後，`bsi.corot.rigid.declare` payload bodies72、
endpointVelocities64、terrain120。body 有 persistent id/revision、fragment index、time、surface；
每 selected fragment 的 stable nodes 恰好一次 velocity，shell 用全部四角並去共享 key。
COM、完整 inertia、P/L、有限 geometry、kinetic/rigidKinetic/internalKinetic 都採 native 回覆。
material stored/dissipated energy 絕不由 Java 當作初速；quasi-static rest 的 zero input velocity
是明示初始條件，不宣稱已接一般倒塌動力（產品 P2、contract A5）。

後續 `bsi.rigid.step` 沿原 motion interface：

| 項目 | 精確內容 |
|---|---|
| request body | scene、requestId、states、forces、dt、gravity、maxStep、maxSubsteps、maxTrials、maxPairs、maxPoints、maxSurfaceTests、maxSweeps、enableSleep |
| payload | 完整 states128×N，forces56×F；不只送活動 body，sleeping bodies 也在集合 |
| response | motionReport104×1、motionStates128、motionSleeping8、motionWoken8；空段也保留 |
| retry | NEED_BIGGER 只 byte-identical request 重送；同 request 的最後成功 trial replay 不是跨 restart 去重 |
| error | 全 batch 不 apply、不部分移動、不改 dt 重試同 requestId；超 budget 明示暂停 |

### 9.2 必需的 finite portable scene 加法

既有 ABI4 `rigid.declare` sources192/cells144 是 reference physical source；不能拿 ABI7 的
deformed mesh 塞進去。保留 birth pre-checkpoint＋重跑 fracture 可以做單批診斷，但會依賴
舊 binary、token 生成序與整段計算，且無法讓不同 birth 批次自然在同一場景相撞。
**不採為正式 W5 恢復格式**。§11 A1 要讓 native 匯出 owning finite source blob，再以
多個 blob＋完整固定地形建立單一 scene；現有 `rigid.step` 可原封不動承接。
source 驗算仍由 native 做，Java 不組 hull／mass/inertia。blob 與 scene codec 必須有 decode
budgets、old ABI 無新增輸出、跨 native 版本明示相容／拒绝，不能用任意 triangle soup 取代。

### 9.3 時間、chunk 與地形

- server 為 clock authority；擬 `dt=0.05 s` 固定 input，native 依 maxStep 做子步。
  一個在途 step，前一步 durable 後才送下一步。慢算時顯示 simulation behind，不突然以
  wall-clock 大 dt 追趕、不 client extrapolate。offline 不補算數小時動力（產品 P3）。
- scene sources/terrain revision 改變前 fence 舊 step；native declaration 成功＋durable scene
  generation 后才接受新 state。插入新 body 時沿 native source 取得初值，**共同 scene time**
  及舊 body states 完整對齊；不得把舊 body 重置出生 time/revision/P/L。
- terrain 由 server 採集真已載入 voxel collision shapes，拆為 exact oriented boxes，含
  slab/stair 等實際子 box；材料 friction/restitution/rolling/spinning 是核定來源資料。
  dynamic shape 或過量 box 拒絕，不猜 unit cube；§11 A2 覆蓋 mask 守整段 sweep。
- 不強制生成未知 chunk，不以 unloaded=air。載入 domain 的完整 terrain＋step swept
  coverage 才派工；缺一格就 `SUSPENDED_CHUNKS`，停在最後 durable pose。chunk unload
  撤銷 scene generation；已在途結果仍需 apply 時核對 coverage/terrain hash，否則丟棄。
- 單一 body 跨多 chunk 只一份狀態。初版一個 bounded active scene/domain；可能互相碰撞
  的 bodies 不得按 chunk 拆成不相撞 scenes。scene 達容量是明示 pause，不消除遠處 body。
- terrain 改成穿入既有 body、刪除 body 下方地形、睡眠 body 受力都重宣告／喚醒，oracle
  驗 native response；不在 Java 補推開、摩擦、落地 snap。現有 rigid 對 static terrain
  不代表撞擊反向載入 FEA，瓦礫重新承載仍受 D-031／CT1 邊界。

### 9.4 客戶端

擬 additive `FiniteStatePacket`／`RigidScenePacket`／`RigidPosePacket`，使用未佔用的新 channel
ID（實作時從 registry 查，不在此臆定）。舊 channel10/11 goldens 不變。identity/basis/batch
header 先驗再配置，mesh 靜態一次傳、pose 每批增量，分包全收完才 atomic swap；亂序、重送、
超額、foreign dimension、reconnect/source generation 變動均有拒收／重送規則。
權威儲存／網路 pose 用完整 f64 + i64；render 最後轉相機相對 float，避免大座標抖動。

vertices 為返回 COM-relative，quaternion Hamilton `w,x,y,z`；triangle index 指完整 vertex
array，不是每 piece 局部陣列。跨兩個已確認 pose 可 lerp/slerp 僅作畫面；不回傳當力學。
server entity 只是 body proxy（禁 vanilla gravity/integration），authoritative collision 與
sleep/wake 都來自 native。不將 mesh bounding box 當結構強度或原生接觸替代品。
移出視距卸載 renderer，不銷毀 body；登入/跨維度 snapshot 以 scene revision+seq 重建。

HUD：native computing／durability pending／paused chunks／budget exceeded／sleeping／engine
unavailable 明確分開；過期 pose 留 ghost 或最後確定位置並標記，不顯示「正在物理運動」。
現行有限 receipt 未提供完整任意裂面渲染資料時，只畫具 provenance 的 native geometry／
位置，不由 block path 猜裂面。倒塌音效／粉塵使用 commit event，不在 prepare 播放。

**W5 gates**：自由落體／非對稱 rotation 獨立解析；同 source free-flight DET；地形碰撞、
兩個不同 birth 相撞／堆疊／sleep/wake；快速 sweep 跨 unloaded chunk 必拒；terrain 改變
撤銷舊 step；不在視野仍保身份；restart 所有 pose/P/L/time 接續；slow I/O／native stall 不
讓 tick 等鎖。通過現有 engine 指標只證明 native，還須真 client film、packet transcript。

## 10. W6 — restart、拆卸／重裝與產品閉環

### 啟動順序

1. world lock → journal owner → 驗 manifest／capacity，禁止玩家與分析先接觸未恢復參與者。
2. 排空舊 I/O，再恢復 PREPARED before images；讀 COMMITTED metadata/root/body/scene。
   不將歷史 committed chunk/player after images覆蓋後來合法編輯。
3. 驗全部 immutable refs／owner mapping／新版 schema；缺 hash/native binary 不清空歷史。
4. worker hello/vocab → identical identified world → checkpoint import；逐島資格原樣保留。
5. A1 scene 重建 → 讀 matching scene generation 的最新完整 motion batch → 等 terrain
   coverage → 第一個 step；sleep cache 可丟，material/P/L/pose/time 不可丟。
6. tick 收到全域／相關域 ready 才開 gameplay，送全量有序 snapshot。關服／off 只撤銷
   generation、完成能完成的 durable barrier；不等卡住 native 才准 Java 退出。

### 拆卸與原位置 reinstall

「移除支承導致 fracture」「玩家拆卸」「simple undo」「拾取 detached member」是不同命令。
simple undo 仍只適用未改／無依賴原 BUILD；損傷或拆卸永遠不得洗回 INTACT。
detach/reinstall 是 CT 新 operation，保留 nativeOwnerId、退休 archive 与物品 custody receipt；
永久 retired manufactured UUID 不復活。若重裝形成新的 installed piece UUID，需透過獨立
nativeOwner binding 指向同一材料身份，不能改 owners 令 native history 無法對位。

既有 `corot.options.reinstallRetired=true` 且 retainLoadPath/transferTopology，只允許完整
退休元素的原 reference nodes/cells/owners/material/section/fiber/release 全相同；完整 removal
只消耗一次。checkpoint 恢復 retired ledger 後在 private session edit＋solve→export，
確認 native 接受才準備 Forge after images。root/history、body removal、item custody/debit、
chunks、owner mapping／scene generation同一 CT；中斷不會 body 與 inventory 同時各有一份。

不能把 arbitrary moved fragment「snap 到鄰近格」當 reinstall；改位、切割、重新成形、重鑄、
養護、新複合斷面都需要 native history projection／material provenance（引擎批次 A）。
拒絕這些操作時保留物件與原歷史、UI 說明支持原位置條件；完整 v2 要求的缺項仍待清償。
crushing particles 永不可拾取。detached salvage item 的數量／質量不能按 Java 體積推算。

**W6 真 Forge 驗收清單**（每項記 PASS/FAIL/SKIP，SKIP≠PASS）：

- [ ] 相同 ordinary packaged jar 在 Linux/Windows client＋dedicated server；probes 不進 jar。
- [ ] 真 socket survival/creative 施工、取消／保護拒絕、同材相鄰 piece、材料精確消耗；原 CT/UI 不退化。
- [ ] 梁／板／混合建物 native finite 資格與 HUD 一致；荷載變動／塑化／卸載後 restart 不回復新材。
- [ ] 帶載破壞同次 receipt → world 移除／剩餘载荷／碎塊出生，100 重送＋斷線不複製。
- [ ] 原生 fall/rotate/contact/stop；兩批 fragment 同場碰撞；混凝土視覺與可持久 fragment 分流。
- [ ] source/terrain chunk 卸載及不同順序載回、跨維度、兩玩家觀察、renderer cull 後回來皆無換身份。
- [ ] motion durable-write 前後、各 CT barrier、native finish/adopt、publish 前後 SIGKILL，兩次獨立重啟。
- [ ] 真實完整 region/player/capability NBT 保留；外改／checksum corruption 拒絕且不覆寫。
- [ ] 退休→重啟→原位置重裝→再加載，native irreversible history／質量帳／可用性相符。
- [ ] 錯位重裝／重複 reclaim／被 damage 後 simple undo 拒絕，無物品 duplication。
- [ ] 關服開另一世界、engine off/on、reset 與卡住 native，generation 撤銷且 tick 不等 native 鎖。
- [ ] 320×240／大字級、en/zh／朗讀，paused/refused/current 不靠顏色；一般玩家不需 test command 才看見。
- [ ] 同 build DET×3、跨 worktree 舊功能 bytes 差分、原 engine 債務逐項登錄；效能 soak 見 §12。

## 11. Contract 變更清單：分清「現有」與「必需新增」

本文件**不變更 contract**。以下方法名僅為提案，實作前先在兩倉 contract 審定 schema、
capability、ABI append slots、failure/replay/ownership oracle，再凍 engine spec 獨立 commit。
公開舊簽章／語意不破壞，未 opt-in 時舊 request/reply bytes 不變；兩倉 hash 要一起更新。

| ID | 提案／理由 | 優先與能力邊界 |
|---|---|---|
| A1 | `bsi.corot.rigid.source.export` 匯出 native owning finite material/geometry source blob；`bsi.rigid.scene.declare` 接多個 immutable source blobs＋body ids＋terrain。精確 history/COM/inertia/finite geometry 由 native 驗；body state 仍用既有 step | **W5 完整出口必需**。新 cap 擬 `bsi.rigid.finite.sources`；format/checksum/source-version/budgets 明確；多 birth、無活 token、fresh process、輸入異序規範化均驗 |
| A2 | 在 step opt-in `coverage` 宣告已知碰撞空間 mask；native 用同 CCD/broadphase 檢查整段 sweep。缺 coverage 返回具名需要區域，原 state 不變；也可先 query required coverage | **W5 chunk 正確性必需**，除非先證明既有 API 可提供同等 full-sweep oracle。未知不是空氣；Java 不計 swept kinematics 或看終點猜安全 |
| A3 | finite display native sample/verdict opt-in；只在確認現有 sections 不足以畫所需 HUD／應力層時追加 | W3 資格 HUD 不依赖此加法；要 finite stress lens／材料失效結論則需要原生數據，不能套 Java 公式 |
| A4 | candidate checkpoint/source export，讓尚未對外 commit 的 fracture trial 取得完整 portable after state | 非首版必需；private staging 是既有 API 可行方案。若成本不合格或 native 無法 export 目標資格，先做此加法，不留下 crash window |
| A5 | native structural dynamics → finite retirement endpoint velocity／P-L handoff；支援一般 release/coupling/tension-only、任意 history projection／reinstall 等原 backlog 能力 | 不能以零速 quasi-static 宣称一般動力；由 engine 批次 A 定 spec／能力，不在 Forge 填補 |
| A6 | native contact events／entity collision/damage authority（若產品選傷害） | 現有 step report 沒有逐 contact 身體傷害訊息；D-031 的傷害要求未因本設計消失。與 server actor／transaction 去重一起設計 |

A1 source blob 不是 Java 發送的可任意認證物：它仍由 host 持久 store 保護真實來源，native
負責結構檢查／物理一致性，CRC 非安全簽章。即使 A1 能重建 finite body，也不授權其任意
移位重裝 FEA；那是 A5 的 history projection 問題。

## 12. 效能、容量與 backpressure 預算

下表為**擬定 admission／產品目標，尚無測量或資格**。實作前獨立凍；不得反向改原
fixture／threshold。保留原 90K mixed／41.7 ms、P-Delta 108K、PE5／display-band 的判決。

| 管線 | 擬初始預算／超限行為 |
|---|---|
| server tick | 全 mod gather/apply/dispatch 額外 p95≤2 ms、p99≤5 ms；每次 capture slices≤1 ms；native lock wait=0；I/O wait=0。無法單 slice 原子 apply 的交易拒絕而不是半發布 |
| native scheduling | 一個 in-flight call，全域 waiting jobs≤64；每 domain 1 active＋1 coalesced pending；motion 20 steps/s 目標，持續每步 p95≤25 ms 的**指定場景**才宣稱 realtime |
| structural analysis | 常見 gameplay fixture interactive p95≤250 ms 目標；長 call 顯示 processing，沿現有 configured watchdog；不能把 timeout 當物理不穩定 |
| motion scene pilot | ≤128 bodies、4096 source cells、16384 terrain boxes、262144 vertices；這是 host admission，native contract max 是另一層。全 scene 溢出暫停，禁止截掉 bodies |
| memory | per domain source/result/staging live bytes≤256 MiB，server finite cache≤512 MiB（含雙 session 需量測 native RSS）；超限拒 admission，不丟材料歴史。geometry cache 只逐 digest、可重建 |
| network | 新 snapshot 分包≤64 KiB、單 assembled payload≤256 KiB、每 client/s≤1 MiB 起步；geometry 超限明示 deferred/refused，pose 組批但不能少 body；既有 channel10 上限不變 |
| disk | §4 bounded objects；每步整 batch durability，p95 fsync/queue/readback 各自記錄；不滿 20Hz 就顯示 slow/pause，不以未落盤 pose 假流暢 |
| fairness | 多 dimension native 序列化時量 admission→start 最大等待、starvation、timeout revoked；不得以增加 Java threads 假造 native throughput |

量測凍定：單機單測、8 warmups＋40 samples／phase、同 build 三輪；包含原 CT 64/1024/4096
piece/cell fixtures、native原 90K mixed fixture，另凍 W5 1/16/128 bodies、睡眠與活動／
多 birth、loaded-boundary 場景。report 給 p50/p95/p99/max、tick budget violations、heap/native
RSS、bytes、I/O latency、queue age；保留所有樣本／FAIL，不挑快的一輪。具名 CPU/OS/JDK/
Forge/build/compiler/backend/BLAS threads/matrix@DOF；同箱比，不跨箱相除。
RECORDED_ONLY 不等於 performance PASS；60分鐘真 Forge soak＋至少一個完整重啟周期，
驗 history/cache/store 是否持續增長超過 admission。不能以低容量 pilot 宣稱 90K 過線。

## 13. 測試與獨立 oracle 分工

| 層級 | 需要驗什麼／oracle | 證據不能替代什麼 |
|---|---|---|
| codec/schema | 完整 finite/checkpoint/motion corpus；合法與溢位/重複/錯序/unknown/reserved/foreign basis；native direct reply 對 Java raw decode/encode | 只能證 wire，不證力學 |
| source/history | source hash 固定、身份不回用、snapshot immutable、same-build checkpoint restart／uninterrupted 分支；unknown bytes fail-closed | 不能只比兩段共用 decoder 的文字摘要 |
| mechanics | 既有解析／獨立 solver、客觀性、切線差分、步長收斂、質量/P/L/能量帳；新 engine 行為先凍 spec | 相同公式 Java 重打一遍不是 oracle；不降低舊門檻 |
| DET | 同 binary/build、相同 input/threads、完整三次逐位；process-local tokens 另記。固定 persistent IDs；如 harness需 canonicalize ephemeral envelope，規則先凍且留 raw，不能宣稱 raw全相同 | 三跑不證跨 build；跨版本另做 worktree differential |
| CT/process | 真 journal＋真 IOWorker/player files＋逐 crash point，兩次 recovery；新 driver 對原同步 driver 相同 intent 的 phase/result bytes | mocked futures／synthetic actor 不代替真 socket與玩家 |
| UI/game | ordinary jar、client/server、production入口、片段影片／原始 HUD/packet/server日志與存檔 hash | E2E 可用性不消除 native numeric debt |
| hostile mutations | 可編譯且正常跑完：略 source hash、略 owner、丟 available、先 publish、重放 step／掉 body、漏 full sweep、丟 checkpoint、跳 barrier | compile/link/載庫失敗不算抓到 mutation |

每個 evidence 包要有 source SHA、contract/build/jar SHA、完整指令/env、exit code、測試登録/
PASS/FAIL/SKIP、first-failure 保留、raw request/reply/decision/NBT hash、fixture version。
文件不得先寫「全部 PASS」再找數字。UI 證據記 installed/dev、OS、synthetic/real actor。

### 13.1 後續實作的現有命令入口（不是本次執行結果）

```bash
# 各自 repository root；不改 tracked dependency repositories
python3 contract/check_contract.py
python3 contract/conformance/run.py --selfcheck

# block-reality，具體 native path/options 依各 task 的既有 admission
./mod/gradlew -p mod :core:test
./mod/gradlew -p mod :core:constructionRecoveryGate
./mod/gradlew -p mod :core:nativeFractureRecoveryGate
./forge/gradlew -p forge test
```

新增 W2–W6 tests/tasks 的名稱與計數等真正註冊後再登錄，不能引用不存在的 runner 當證據。
Maven Central 429 用 repo 外 init script 指向 Google Maven Central mirror；apt native build
工具依兩倉指南。native 同源 Windows/Linux/JNA/i9/sanitizer 在能力批次完成時集中驗；
本次 docs-only 不需重建未改 core，不把未執行 runtime tests 算 PASS。

## 14. 與 construction transaction 的衝突清單

| 衝突 | 必須採取的整合，不可用的捷徑 |
|---|---|
| 兩個 coordinator 各自 ready | global arbiter 共用 journal/metadata order/leases；NativeFractureTransactions 不可旁開 owner |
| 舊 Request base+1 與同 revision history commit | 新 operation/version 明確区分 source vs history clock，舊 operation bytes/語意保持 |
| CT Host thread assertion vs native worker | split phase/message；不可 worker 讀 Level、tick 等 native；§5.2 為 W4a blocker |
| full chunk after 與 normal saves/ticks 競爭 | mutation/save lease、同 IOWorker drain；不能只 suppress analysis notifications |
| max 1MiB/16MiB 與 finite receipt/blob | bounded immutable CAS objects＋small refs；不提高原 CT caps、不偷偷漏 history |
| observational merge vs manufactured birth | §3.2 persisted owner bridge；不把 grouping當退款或 native damage authority |
| generic break／axis／explosion vs finite history | 都須 stage native topology transfer＋CT；不支援路徑拒絕，不能事後 fresh solve 修補 |
| finite fracture after export 的 crash window | 私有 staging 先 materialize，authority 在 durable COMMITTED 後 adopt |
| retirement 身份 vs simple undo | simple undo 永不復活 damaged pieces；custody/reinstall另交易，native owner保留，製造身份不復活 |
| 20Hz motion vs CT lifetime cap | §4.3 獨立 kinematic slots，無 world/item權；topology/custody仍 CT，fence後換 generation |
| protection mods 外部副作用 | 僅宣称既有允許的 validator exception；未知/不可逆 hook拒绝或明示相容範圍，不承諾任意 mod 原子性 |
| off/reset／關服 vs durable after | generation 撤銷計算權、不清 store；native 已變但未持久只丟私有候選，持久已定不可倒退 |

## 15. 需要使用者決策的產品問題

以下是設計選項，**尚未替使用者拍板／縮減原 v2 範圍**；每項在啟用對應 gameplay 前定案，
不阻擋先完成 codecs／store／oracle。未定案時維持不破壞的 preview／明示 unavailable。

| ID | 問題 | 建議與影響 |
|---|---|---|
| P1 | 生存預設自動倒塌，還是分析→明確確認？保護區誰有權被破壞？ | 建議按世界 opt-in，W4/5全 gate後才允許auto；未支持材料保持明示未評估，不可默認安全 |
| P2 | 第一個 playable milestone 接受「準靜態平衡後從靜止釋放」嗎？ | 可做具名有限切片，但不是一般 collapse dynamics 完成；A5非零速度/失穩動力仍是完整v2依賴 |
| P3 | unloaded／offline 暫停，還是持續持票模擬？ | 建議暫停到完整coverage，offline不catch-up；持票是額外CPU/記憶體與權限成本，不能無限force-load |
| P4 | D-031碎塊傷害實體的第一版範圍？是否要撞擊其他結構？ | D-031傷害仍有產品需求，A6必需；預設不向FEA回饋撞擊、不做瓦礫重新承載。刪傷害或延後必須明示裁決 |
| P5 | detached可拾取／搬運嗎？重裝僅原位置可接受作階段出口嗎？ | 建議有單一custody token、原位置history保留先交；任意搬移重裝/切割重組不能以Java snap補做，完整v2仍追engine缺口 |
| P6 | 延遲取捨：durable pose後發布（可能低於20Hz），或容忍crash回退的視覺預測？ | 建議durable-first；若准許speculative視覺，明確ghost且不授權拾取/傷害，恢復規則另凍 |
| P7 | 支援世界尺寸／同場碎塊上限／材料範圍與硬體基準？ | §12為pilot候選；不能拿128 bodies／小場景取代原90K/41.7ms，需指定最終產品基準與過載UI |
| P8 | arc續算是否給一般玩家操作？ | 建議一般玩家看load factor/資格，進階工具可明示步進；不把arc path當時間或自動倒塌觸發器 |
| P9 | 物理scene活動中能否施工／操作碎塊？互動是否暫停？ | 建議純檢視不暫停；改地形/拾取/重裝先fence、以最後durable pose保留lease，再走CT。不要暫停整個世界只為開HUD |
| P10 | 引擎版本更新與舊checkpoint不相容時，保留舊runtime還是遷移？ | 建議先完整備份、明示相容矩陣；native驗證式migration成功才換root，否則舊世界唯讀/拒絕finite，絕不fresh solve洗歷史 |
| P11 | 是否選配第三方Physics Mod作純視覺？ | 建議無硬相依；不得讓同一body被兩個物理引擎積分、讓粒子變可拾取物，任何renderer替換都保留native pose authority |

## 16. 設計參考與完成界線

主要消費者依據：[ENGINE_BOUNDARY](../ENGINE_BOUNDARY.md)、[DECISIONS](../DECISIONS.md)
（D-024/031/032/033/038/044/048）、[CT](../CONSTRUCTION_TRANSACTIONS.md)、
[CT persistence](../CT_FORGE_PERSISTENCE.md)、[CT axis](../CT_FORGE_AXIS_TRANSACTIONS.md)、
[CT placement](../CT_TRANSACTIONAL_PLACEMENT.md)、[CT boundaries](../CT_PLACEMENT_BOUNDARIES.md)、
[CT metadata](../CT_MANUFACTURED_METADATA.md)、[CT bootstrap](../CT_JOURNAL_BOOTSTRAP.md)、
[CT chunks](../CT_CHUNK_PARTICIPANT.md)、[CT bounded read](../CT_BOUNDED_CHUNK_READ.md)、
[CT live capture](../CT_LIVE_CHUNK_CAPTURE.md)、[CT player](../CT_PLAYER_PARTICIPANT.md)、
[native fracture transaction](../NATIVE_FRACTURE_TRANSACTION.md)、[WORLD_REGISTRY](../WORLD_REGISTRY.md)、
[CONSTRUCTION_IDENTITY](../CONSTRUCTION_IDENTITY.md)、[ALPHA_RELEASE](../ALPHA_RELEASE.md)。

新增 source/checkpoint/finite motion decoder 必須直接對照 contract 的
[bsi_corot.h](../../contract/bsi_corot.h)、[bsi_corot_arc.h](../../contract/bsi_corot_arc.h)、
[bsi_corot_checkpoint.h](../../contract/bsi_corot_checkpoint.h)、[bsi_corot_shell.h](../../contract/bsi_corot_shell.h)、
[bsi_motion.h](../../contract/bsi_motion.h)，而非把本文 byte數抄成第二套權威。

本文件完成表示**整合路徑、資料/執行緒/崩潰語意、必需contract缺口、驗收與產品選項可供
後續實作**；不表示 W2–W6 已完成、不代表真 Forge 已測或任何性能門檻已通過。
