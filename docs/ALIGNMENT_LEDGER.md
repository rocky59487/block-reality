# ALIGNMENT_LEDGER — block-reality ↔ tectonic2 對位帳（2026-09-03；兩倉逐位相同）

> **地位**：稽核帳，不是裁決。每條發現指向負責倉的裁決落點（`DECISIONS.md` / `GATES.md` / `docs/specs/`），裁決仍在各自倉做。
> 兩倉各存一份，**位元組逐位相同**（`sha256sum docs/ALIGNMENT_LEDGER.md` 互比；不同 = 有人單邊改了）。
> 修訂一律 **dated 追記，原文一字不改**（§8）。
>
> **量測基準**：block-reality `Main` = `b64c637`（PR #88 併入，2026-09-03 03:12Z）；tectonic2 `main` = `0a4ee1d`（PR #19 併入，2026-09-03 03:11Z）。
> 契約 `contract/` 42 檔、雜湊 `c45f51fe7fca…`，兩倉 `diff -r` **空**（本輪實測）；`check_contract.py` 兩倉皆綠。
>
> **本帳的三個來源**：(1) 上一個共同 PR（BR #88 / T2 #19）落地後兩倉的實際內容；(2) 兩倉全部 open issues；
> (3) tectonic2 #18「target perfect editable structural engine」與 `docs/EDITABLE_WORLD_RESEARCH.md`（多個專家模型的研究綜述）。

---

## 0. 一句話

契約已經是同一份（雜湊釘死、實測逐位相同），但**契約兩側各有一層還沒對齊**：引擎側的判準檔（MC64/MC65a）與轉接器在三個列舉上跟契約說不同的話（§2 A1–A4），
消費者側的權威文件（`SWAP_PROGRAM.md` 權威版、`CLAUDE.md`、`ENGINE_BOUNDARY.md`）停在 D-044 之前（§3），
而「兩倉互相押著」的 CI 機制只押住了「我跟我釘的那個 commit 一致」、押不住「兩條主線一起走」（§2 A12）。
perfect engine（#18）的要求對現有引擎是**增量**而非重寫，但它有兩個直接落在契約上的加法（島生命週期／逐族有效性、碎塊交棒），
和一個消費者今天明文不用的能力（編輯幀重分析梯，D-008）（§6）。

---

## 1. 事實地圖（PR #88 / #19 之後）

| 項 | block-reality `b64c637`（0.4.0-dev；最後發布 v0.3c） | tectonic2 `0a4ee1d`（`kEngineVersion` 1.2.0 未 bump） |
|---|---|---|
| 出貨形狀 | **D-044**：進程內共享庫 + JNA（`mod/core/.../engine/`），jar 零可執行檔、零子行程；N24-a/b 已凍並首跑 | **D2-013**：`libbsi_tectonic.so`（host + `bsi_adapter.cpp` + `tec_capi` 靜態 + OpenBLAS/METIS 靜態），L7 自足實測、只匯出契約符號 |
| 契約實作 | `core/bsi/`（frame、header、records、response）+ `core/engine/`（`BsiNative`、`InProcessEngine`、`EngineLocator`、`BundledNatives`）；309 項 Java 測試 | `core/capi/bsi_adapter.cpp`（vtable 十函式中 8 個；`world_edit`/`cancel` NULL）；**`capabilities()` 回空集合**；`gate/bsi_expected_red.json` 三案（C-5、C-6、C-11 端格） |
| 遊戲流程 | **仍走 `SidecarClient` 子程序**（`StructureManager.java:149`）；`InProcessEngine` 無一行遊戲碼呼叫（#89） | — |
| 建置 / CI | `ci.yml` 四 job：`engine-gates`（sidecar）、`java`、`contract`（host + stub + 打包 N24-a）、`engine-linux`（**需 `TECTONIC2_TOKEN`，今天量不到東西**） | 第一個 CI（`ci.yml`）：CMake 臂、host 套件、stub 三傳輸、L1–L8、MC68 三條變異腿；八套 Windows-bound 與收割只 RECORDED |
| Linux 臂 | — | L1–L5/L7/L8 綠；**L6 四套短少**（engine 733/734、mc4 235/237、fix1 94/98、mc6 131/132）；LB-F1（`selInvEntryFf` 離對角 2.643，零呼叫者）（#21） |
| 語料 | `run.py` 8 檔；BR CI 對 stub 跑（力學家族 SKIP） | 收割：執行 4 / SKIP 4 / 帳上紅 2；C-4 兩案閉合解 1e-9 綠；MC68-M1..M3 全咬 |
| 判準 | N19–N24 已凍（GATES 2026-09-02/03）；`sidecar/expected_red.json` 14 腿 | MC61–MC68、MC60d、LINUX_BUILD 已凍；MC63/64/65a/65b/60d/66a/66b **未實作** |
| 側枝 | — | `claude/tectonic2-dev-plan-khld1u` = `03de26c` **仍在 origin**（本輪實測）；LINUX_PORT1/MC61/MC62 合流未做 |

**已一致、不必重做**：契約逐位相同；出貨形狀兩倉裁決同步（D-044 ↔ D2-013）；host 共用；`bsi_capi.h` 五函式 Java ↔ ctypes 同一條路；D-041 §3/§4/§6 與 D2-010 §4–§6 同義。

---

## 2. 契約 ↔ 實作分歧帳（A 系列；每條附出處、影響、處置、落點、動哪一倉）

| # | 分歧 | 出處 | 影響 | 處置建議 | 落點 / 誰動 |
|---|---|---|---|---|---|
| **A1** | **BSI 沒有每格挫屈判定。** 契約 `blocks.flags` 只有 bit0 overloaded、bit1 indicative；`buckling` 區段是每島 `state/factor`。MC64 §1.1 的每格 `stability` u8 只在 v2 wire（`verdict:true`）。BR N19-b 要求「`bucklingCritical == 引擎 stability == critical`」——在 BSI 上沒有來源；消費者若自己比 `factor < 1` 就違反 P2「消費者永不重算判定」 | `contract/bsi_engine.h:106-115`；`bsi.schema.json x-records.blocks.x-flags`；T2 `MC64_VERDICT.md` §1.1；BR `GATES.md` N19-b | MC66a 落地後，BSI 消費者拿不到 N18/N19 要的 boolean；HUD 要嘛自己算、要嘛永遠「未評估」 | **契約加法（提案，本 PR 不動契約）**：`blocks.flags` bit2 = `bucklingCritical`，引擎在 double 上定案「所屬島 `computed ∧ factor < 1`」，只在 `commit` 軌有效；`BSI.md` Part G dated + schema `x-flags`。MC64 §10 前向引用；MC66a wire 落地前完成 | T2 契約提案 + MC64/MC66a；BR N19-b 挫屈半邊在此之前 **[暫]** |
| **A2** | **`unassigned.why` 列舉三方不一致。** MC65a §1.2 說引擎會產生 `GROUND`；契約 `x-enums.unassignedWhy` 沒有 `GROUND`；契約有 `FULLY_SUPPORTED`，MC65a 未列。轉接器今天：地面格 `ownerKind=0 (none)`、不進 `unassigned` | T2 `MC65A_ACCOUNTING.md` §1.2；`bsi.schema.json x-enums`；`bsi_adapter.cpp:308-309`；host `bsi_writer.cpp:120-135`（未知碼排最後）、`bsi_session.cpp:412-413`（未知碼不查 `reason`） | MC65a 若照 spec 送 `GROUND`，host 不擋、**語料 C-1 的 schema 驗證會紅**；同一件事在 v2 wire 與 BSI 上答案不同 | MC65a §10 dated：BSI 上地面 = `ownerKind none`、`reason 0`、不進 `unassigned`（與今日轉接器一致）；`GROUND` 只存在於 v2 `readback.unassigned`；`FULLY_SUPPORTED` 列為保留碼（本引擎不產生；BR D-026 dated 已載「tectonic v0 對全支承格回有結果的 member」） | T2 MC65a（本 PR dated） |
| **A3** | **轉接器對「沒人擁有」的格猜 `RUN_TOO_SHORT`。** MC65a §1.2 明寫本引擎不產生 `RUN_TOO_SHORT`（singleton run 是合法 member），擷取層丟棄的格是 `REFUSED` | `bsi_adapter.cpp:316`；`MC65A_ACCOUNTING.md` §1.2 | 語料無 case 觸發此路 → 一個沒被 gate 過的理由碼；理由碼「描述了它沒查證的事」（N17-f 反面） | MC65a `blockWhy` 落地前，預設改 `REFUSED`（誠實「引擎丟了它」）；登記為 MC68 轉接器 pre-MC65a 邊界 | T2 MC68 §10 / registry（本 PR 登記；程式碼隨 MC65a 動） |
| **A4** | **警告碼誤用。** 轉接器把**全部**擷取警告記在 `BEARING_SKIPPED_FIXED` 下；引擎警告是自由文字（`McModel::warnings`），沒有文字→碼對映；契約 `warningCode` 七碼（含 `BUTT_UNSTITCHED`，MC61 F4 的情形） | `bsi_adapter.cpp:181`；`bsi.schema.json x-enums.warningCode` | 一個 `BUTT_UNSTITCHED` 世界會被報成「承載接觸跳過」 | 引擎側每個 push 點帶碼（與 MC65a `blockWhy` 同機制），轉接器依碼轉發；契約提案加開放碼 `UNCLASSIFIED`（加法）供無碼警告誠實計數。在那之前照登 | T2 MC65a（併入 `blockWhy`）+ 契約提案；registry 登記 |
| **A5** | `members.section` 回**宣告值** `sect`；`sect = -1`（材料預設）時回 -1，契約說它是斷面 id | `bsi_adapter.cpp:364`；`BSI.md` B.5 `members` | 消費者無法把 -1 對回斷面，除非自己重推材料預設（P2 反面） | 轉接器解析 `defaultSection` 的 id；語料補一條斷言（契約變更，併入 C5 批次） | T2 MC68 轉接器；契約批次 |
| **A6** | **端力號向沒有執行中的語料押著。** C-4 用 `abs_eq`；C-8（號向）要 `bsi.readback.stations`（MC65b）→ SKIP。轉接器 `endI/endJ` 的號向映射只有推理 | `bsi_adapter.cpp:369-372`；`conformance/README.md`；收割 `harvest_2026-09-03.txt` | 「j 端與 i 端同號向、拉為正」目前是宣稱不是量測 | MC65b 落地即跑 C-8；或 C-4 加一條帶號斷言（契約變更） | T2 MC65b；契約批次 |
| **A7** | **語料缺口（集中登記）**：無 `dc > 1` case（T2 #20、BR #91 已記）；無 `status:"partial"`/`maxTimeMs`；無 `numThreads` 越界 fail-closed（v2 wire MC62-13 有）；無 `bsi.cancel`；無 `precision.storage:"f32"` | `contract/conformance/cases/`（8 檔） | `bsi.precision.f32` 依 MC68-03 **永遠無法宣告**（沒有家族可綠）；`partial` 語意（MC68 B2）沒有腿 | 一次契約加法批次補齊（§4 C5），避免多次 hash bump | 契約批次（T2 主導、兩倉同步） |
| **A8** | **`conformance/README.md` 的家族表列了不存在的 case 名**：`C3-hello-contract-mismatch`、`C10-euler-tip`/`C10-greenhill`、`C11-axis-out-of-range` 等三個、`C12-f32-display`、`C13-x-extension-ignored`/`C13-custom-section-equals-rect`。實際 `cases/` 8 檔（C10/C11/C13 各一檔含 variants/steps；C-3 是 C11 的一個 step；**C-12 不存在**） | `contract/conformance/README.md` 家族表 vs `cases/` | 讀 README 會以為 C-12 有腿 | README 表更正（契約變更，因 README 在雜湊內）；C-12 case 隨 A7 批次補 | 契約批次 |
| **A9** | `SWAP_PROGRAM.md` §5 把 BSI 求解的區段選單寫成 `readback[]`；契約是 `include`（`x-enums.include`）。`readback` 是 v2 wire（MC60d）的欄位 | BR `docs/SWAP_PROGRAM.md` §5；`bsi.schema.json` | 文件混用兩套 wire 的欄位名 | 文件更正（本 PR dated） | BR SWAP §8 |
| **A10** | `bsi_capi_open` 選項：BR 送 `{"log":0,"numThreads":N}`；host 只解析 `log/probe/assumeCaps`，未知鍵**靜默忽略**；契約 `x-capi` 沒有 open 選項 schema | BR `InProcessEngine.java:46`；T2 `contract/host/bsi_capi.cpp:49-51` | `numThreads` 在 open 選項無效（每請求 body 的才有效，Java 已送）；未知鍵靜默違反 P6 精神 | BR 移除 open 選項的 `numThreads`；契約提案：open 選項 schema + 非 `x-` 未知鍵拒絕 | BR #89；契約批次 |
| **A11** | `bsi.hello` 送 `arena.supported=true, maxBytes=256 MiB`，但進程內路徑 Java 沒有 arena 實作（T-B 是 dev/CI 臂） | BR `BsiHeaders.java:29`、`InProcessEngine.java:62` | 無害但不實 | T-A 路徑送 `supported:false` | BR #89 |
| **A12** | **跨倉漂移偵測不對稱且弱。** BR N23-b 比的是「本倉 pin == `.github/tectonic2-contract-ref` 記的雜湊」，ref 指向 T2 固定 commit `c9628bc`；T2 `main` 若改契約並重釘，BR 兩邊仍各自一致 → **BR CI 綠**；有 token 的 `diff -r` 也是對 pinned commit 比 → 仍綠。T2 CI **完全沒有**對 BR 的檢查。`contract/README.md`「只改一邊 → 另一邊 CI 紅」在 CI 層不成立，只在執行期握手（`BSI_VERSION`）成立 | BR `ci.yml:136-150, 307-309`；T2 `ci.yml`；BR 公開（raw `CONTRACT_SHA256` HTTP 200 實測）、T2 私有（404） | 兩倉主線可以各自持有不同契約而兩邊 CI 都綠 | T2 CI 加一步：抓 BR `Main` 的 `contract/CONTRACT_SHA256`，不等即紅（**無需 token**）；BR CI 有 token 時改對 T2 `main` 比對（不是 pinned commit），無 token 時照現狀 [暫]；`contract/README.md` 措辭改為「執行期握手必紅；CI 層見 A12 的兩步」 | T2 CI；BR CI；契約 README（批次） |
| **A13** | N23-f 與 MC68-04 寫 `run.py --adapter tectonic` / `--adapter framecore`；runner 實際 adapter 是 `engine / capi / sidecar / frame_v2` | BR `GATES.md` N23-f；T2 `MC68_BSI.md` §5 MC68-04；`conformance/README.md` | 判準指名不存在的旗標 | 文件 dated 更正：tectonic = `--adapter capi --lib libbsi_tectonic.so`（或 `--adapter engine --hostd`）；FrameCore 臂見 A14 | BR GATES；T2 MC68 §10（本 PR） |
| **A14** | **FrameCore 的 BSI 臂不存在。** D-043 §2「FrameCore 對數臂也實作同一個 vtable」、N22（`sidecar/diff_engines.py` + `br-sidecar-fc`）、N23-f `--adapter framecore` —— 三者都沒有實作、**沒有 issue**；`sidecar/diff_engines.py` 不存在 | BR `DECISIONS.md` D-043 §2、D-044 §5；`GATES.md` N22；`sidecar/`（19 檔，無 diff_engines） | 差異帳（發布條件 N22-c「零 blocker」）今天無法產生 | 開 issue 認領（§4 C1）；裁決要不要保留 FrameCore 臂：若保留，實作 = 把 `sidecar/main.cpp` 的抽取 + FrameCore 包成 `bsi_engine_vtable`（dev/CI 臂，不進 jar）；若不保留，N22 改為「語料 + `verify.py` 對 tectonic 臂」並 dated 降級 | BR（裁決 + issue） |
| **A15** | MC68 §1.4 說 `hello.contractSha256` 是編譯期常數 `-DBSI_CONTRACT_SHA256`，建置腳本先跑 `check_contract.py`；實作是 host 的 `bsi_schema_embed.cmake`（configure 期跑 `check_contract.py`，不符拒建，`kEmbeddedContractSha256`） | T2 `MC68_BSI.md` §1.4；`contract/host/bsi_schema_embed.cmake:7-29` | 等效；spec 文字與機制不同 | MC68 §10 dated 對位 | T2（本 PR） |
| **A16** | **兩套能力字串沒有對照表**：v2 `hello.capabilities`（`tectonic.h` `beam.eb`…`loads.applied.point`；MC62 要加 `constraint.*`）與 BSI `x-capabilities`（`bsi.*`）。BR SWAP §6「T2 側加法」把兩套混列 | T2 `core/tectonic.h:59-111`；`bsi.schema.json x-capabilities`；BR SWAP §6 | 消費者/文件容易把 v2 字串當 BSI 能力 | MC68 §1.3 加 v2 ↔ BSI 對照表（文件） | T2 MC68 §10（本 PR） |
| **A17** | D-041 §6「`numThreads` 預設 1（決定論形狀）」：Java `InProcessEngine.solve` 由呼叫者傳；runner DET 注入 1；`hello.threads` 回報引擎自選 | BR `InProcessEngine.java` `solve(...)`；`conformance/README.md` DET 段 | #89 接線若不傳 1，`same_build_same_machine` 之外的宣稱都不成立（本來就不宣稱，但 N24-b5 的「與 runner 逐位」會斷） | 列為 #89 驗收條件 | BR #89 |
| **A18** | `evidence.py` 無 BSI 臂 → natives 形狀雜湊鏈兩腿不是三腿 | BR #91 | 已有 issue | — | BR #91 |
| **A19** | #80 的併發缺陷只在 `BundledNatives` 修（`isGood(target)` 先採用），`BundledEngine`（sidecar 形狀）未修；issue 未更新 | BR `BundledNatives.java` `ensure()`；`BundledEngine.java`；#80 | sidecar 形狀隨 #89 退場，缺陷隨之消失；但 issue 讀起來像還在 | #80 加一則說明（隨 #89 結案） | BR #80 |

---

## 3. 文件層分歧帳（B 系列；「誰過期、正確現況是什麼、本 PR 是否清掉」）

| # | 檔 | 過期敘述 | 正確現況 | 本 PR |
|---|---|---|---|---|
| **B1** | BR `CLAUDE.md` 專案段 | 「sidecar 退為傳輸轉接（stdio 門鈴 + 零複製 arena）」「FrameCore 保留為 CI 對數臂」；未提 D-044 | D-044：進程內共享庫 + JNA、jar 零可執行檔、`contract/host` 鏡像、N24；FrameCore BSI 臂未實作（A14） | dated 追記 ✔ |
| **B2** | T2 `CLAUDE.md` 現況段 | HEAD `fcdf036`；未提 PR #19（CMake 臂、`libbsi_tectonic.so`、轉接器能力空集合、`run_linux.sh`、CI）、D2-013、#20/#21；build/test 段只有 `.bat`；變異腿「十三條」 | HEAD `0a4ee1d`；Linux 臂 L1–L8；十四條變異腿（`gate/build_mc68_mut.sh` 是第一支 Linux 版） | dated 追記 ✔ |
| **B3** | BR `docs/SWAP_PROGRAM.md`（**權威版**）§8 修訂為空 | 無 PR #87 併入 SHA、無 D-044 改形、無 PR #88/#19、無單元順序反轉（MC68 轉接器先於 MC63–65b）；§1「T2 無 Linux 建置」；§5 傳輸列以 T-B 為目標；§6 Phase 3「sidecar 重構 `wire/`、`backend_*`」 | 鏡像（T2）反而有 2026-09-02 修訂 → **鏡像領先權威版**，違反「以 BR 版為準」 | §8 補 2026-09-02/03 兩則 ✔ |
| **B4** | BR `docs/ENGINE_BOUNDARY.md` | 2026-09-02 追記仍寫「sidecar 是 BSI T-B 傳輸轉接… 逾時 → sidecar 自行結束」與「不變的東西：程序隔離（D-013）」 | D-044：程序隔離結束；T-B 為 dev/CI 臂 | dated 追記 ✔ |
| **B5** | BR `docs/API_ARCHITECTURE.md` | 「sidecar 程序（獨立 process）」「sidecar 殺掉 → 遊戲照常跑」 | v0.3c 事實；v0.4 起見 D-044 | dated 指向 ✔ |
| **B6** | BR `docs/V04_PLAN.md` §4 2026-09-02 追記 | 「B1 薄宿主 → 傳輸轉接 + 原始碼靜態連結；B8 Java BsiCodec + sidecar T-B」 | B1 = `InProcessEngine` 接線（#89）；B8 codec 已落地（PR #88）；B2 對數臂無主（A14） | dated 一行 ✔ |
| **B7** | BR `docs/DECISIONS.md` D-043 §2 | 「sidecar = 傳輸轉接」未 dated（D-044 只 dated 了 D-041/D-027/D-013） | 同 B4 | dated 追記 ✔ |
| **B8** | T2 `docs/SWAP_PROGRAM.md` 鏡像 §1/§3 | MC68 = v1.6、v1.6 ↔ B8 | 轉接器（部分）與 Java codec 都已落地（順序反轉）；能力空集合；`bsi_expected_red.json` | 2026-09-03 修訂 ✔ |
| **B9** | T2 `docs/ROADMAP.md` v1.3 / v1.6 列；`docs/README_TECTONIC.md`；根 `README.md` | v1.3「側枝合流 … + CMake」；v1.6「MC68」；README_TECTONIC「以同一條 frame_capi_v2 wire 取代 FrameCore」；根 README 是 CHARTER 時代（MKL PARDISO、M1 進行中） | CMake 由 LINUX_BUILD 自零落地（非側枝）；MC68 部分落地；換裝走 BSI | dated 追記 ✔（根 README 加指向段） |
| **B10** | T2 `docs/GATE_LINE_REGISTRY.md` | 無 LINUX_BUILD 首跑/第二跑、L6 四套短少、LB-F1、MC68 收割與變異腿 | #21 說「照登入帳」，冊裡沒有 | 補一節 ✔ |
| **B11** | T2 `docs/SPEC_INDEX.md` §E | MC68 v1.6 [硬]；LINUX_BUILD 未註首跑 | 部分落地；L1–L5/L7/L8 綠、L6 [暫] | dated 一行 ✔ |
| **B12** | T2 `docs/specs/LINUX_BUILD.md` 檔頭 | 「側枝 `khld1u` 的 LINUX_PORT1 **不在 origin**」 | `claude/tectonic2-dev-plan-khld1u` = `03de26c` 在 origin（2026-09-03 實測 GitHub API）；D2-012 的 cherry-pick 可執行 | dated 一行 ✔ |
| **B13** | T2 `docs/EDITABLE_WORLD_RESEARCH.md`（`3e5de10`） | 未被 `CLAUDE.md`/`ROADMAP`/`SPEC_INDEX` 引用 | 它與 #18 是同一份要求的兩個版本 | `CLAUDE.md`/`SPEC_INDEX` 加引用；差距分析在 `docs/specs/PE_GAP.md` ✔ |
| **B14** | BR `README.md` / `QUICKSTART.md` / `CONTRIBUTING.md` / `docs/RELEASING.md` | sidecar 子程序、`br-sidecar` 搜尋順序、`package.sh` | 是**已發布 v0.3c** 的事實，不算錯；v0.4 的發布流程（`package_natives.sh`、natives manifest、evidence BSI 臂）尚無文件 | 不動；屬 #89/#91 交付 |
| **B15** | BR `.github/tectonic2-contract-ref` | 釘 `c9628bc`（PR #19 分支上的 CI commit） | 契約未變所以不紅；下次契約變更改釘 merge commit 或 tag | 不動；A12 一併處理 |
| **B16** | 兩倉 issue 串 | T2 SWAP §5 的 12 則回覆草稿**一則都沒貼**；BR #85 仍寫「等 tectonic2#15 裁決」（裁決已在 D2-010/D-039/MC63）；BR #69 的三個決定已被 BSI 取代/吸收，未結案 | 見 §5 | 草稿補在 §5；由 operator 張貼 |

---

## 4. 無主項與缺失（C 系列）

| # | 項 | 現況 | 需要的動作 |
|---|---|---|---|
| **C1** | **N22 差異帳沒有 owner**（`diff_engines.py`、FrameCore BSI 臂、`evidence/differential.jsonl`） | 判準已凍（N22-a..e），零實作、零 issue | BR 開 issue；先裁決 A14（保留或降級 FrameCore 臂） |
| **C2** | N23-f「語料對真引擎兩臂各 exit 0」 | tectonic 臂：T2 CI 收割（永不綠）；BR CI 無 token 不跑；FrameCore 臂不存在 | A14 + C3 |
| **C3** | `TECTONIC2_TOKEN` 未設 | BR `engine-linux` job 每次 `::warning::`「量到 NOTHING」 | operator：加 repo secret（read 權限的 PAT） |
| **C4** | T2 #21：L6 四套短少 + LB-F1 未診斷；`run_all.ps1` staleness 守門不含變異掃描（十四條） | 照登未修 | 依 #21 二選一（修 / 具名平台差異），不改 `expected_counts.json` |
| **C5** | **契約加法批次 #1**（把 A1/A4/A5/A6/A7/A8/A10/A12 的契約面一次做完，一次 hash bump） | 分散在八條發現 | T2 主導判準（獨立 commit）；內容：`flags` bit2 `bucklingCritical`、`warningCode` += `UNCLASSIFIED`、`members.section` 語意註記、C-12 f32 case、`dc > 1` case、`partial` case、C-4 帶號斷言、open 選項 schema、README 家族表更正、README「CI 紅」措辭 |
| **C6** | BR #17/#16 中 BSI v1 **明文不涵蓋**的：`domainId`/`resultRevision`、artifact ↔ analysisMember 對映、`failureType`/`handoffType`、`bsi.fracture` 動詞 | v1「後議」；構件身分由消費者 registry 以 run 起訖鍵處理（GATES 2026-09-02 構件身分列） | 登記為 BSI v1.x 議題清單（§6 PE-4 給出 `bsi.fracture` 的形狀）；不是缺陷 |
| **C7** | `readback.shelledges` 恆 0（MC58b-10）、`supportKind` 對 Panel 不生效（MC63 B1）、樓板全合成（Q4(a)） | 兩倉皆已登記 | HUD 具名 indicative；列出以免遺忘 |
| **C8** | Windows/macOS natives（#90） | 只有 Linux | D2-011 兩條路（MinGW 交叉 / MSVC+vcpkg）擇一開工；先量 `DYNAMIC_ARCH=0` 體積 |

---

## 5. Issue 對照（兩倉 open issues；「對應」= 裁決/單元/契約落點；「建議」= 處置）

### tectonic2

| # | 題 | 對應 | 狀態 | 建議 |
|---|---|---|---|---|
| 21 | Linux 四套短少 + LB-F1 | LINUX_BUILD L6 [暫]；C4 | 未診斷 | 開放；先做 (a) mc4 路徑 fixture、(b) FNV 跨編譯器具名、(c) engine T28 根因 |
| 20 | MC65a + MC64 → `bsi.core` | `bsi_expected_red.json`；A2/A3 併入 | 未做 | 開放；判準已凍（MC64/MC65a），可動工；A2 的 dated 修訂先併 |
| 18 | perfect engine | `docs/specs/PE_GAP.md`（本 PR）+ §6 | 差距分析已交 | 開放；operator 核 PE-1..PE-5 順序與擬議 D2-014 |
| 15 | 支承語意 | D2-010 §1、MC63、BSI B.3/B.4 | 裁決已做、未回覆 | 貼 SWAP §5 草稿；MC63 落地後關 |
| 14 / 12 / 2 | 挫屈：沿桿 Kg、語意鴻溝、λ_cr parity | D2-010 §2、MC66a/b、BSI `buckling.*`；**A1** 補每格旗標 | 判準已凍、未實作 | 貼草稿；v1.5 關 |
| 13 | member ↔ shell 接合 | MC60a/b/c 已交付；wire 版 MC60d；BSI C-6 | in-process 已達成；wire/BSI 帳等 MC65a（C-6 在帳上） | 貼草稿；MC60d + C-6 綠後關 |
| 11 / 3 / 9 | 顯示場、欄位帳、端點取樣 | D2-010 §3、MC65b、BSI `stations`/`facets`/`facetSurfaces` | 判準已凍 | 貼草稿；v1.4 關；#3 的欄位帳 = BSI 記錄表（PARITY/MAPPED/RETIRED 逐欄在 SWAP §5） |
| 10 | 判定 boolean | MC64、BSI `flags` bit0（今天已由轉接器定案）；**A1** 挫屈半邊缺 | 半做 | 貼草稿；A1 契約加法後關 |
| 5 | 資格門（umbrella） | SWAP §6 Phase 5；N22/N23（C1/C2 無主） | 阻擋於 A14/C1 | 開放；加一則「D 節差異戰役需要 A14 裁決」 |
| 4 | Linux artifact + parity | LINUX_BUILD L1–L8 綠；LP-XP2 未裁決；D2-012 合流未做 | 半做 | 加一則：CMake 臂已取代「側枝合流」的 Linux 部分；MC61/MC62 仍待 cherry-pick |
| 1 | D-028 面節點驗收 | MC61（重新編號側枝 MC54） | 側枝綠、main 未合流 | 開放至 v1.3 |

### block-reality

| # | 題 | 對應 | 狀態 | 建議 |
|---|---|---|---|---|
| 91 | evidence.py BSI 臂 | A18；N24-a3 第三腿 | 未做 | 開放；與 #89 同批 |
| 90 | Windows/macOS natives | C8；D2-011 | 未做 | 開放 |
| 89 | 接線 InProcessEngine | A10/A11/A17 併入驗收 | 阻擋於 T2 #20 | 開放；驗收條件加 A17（`numThreads` 預設 1）、A10、A11 |
| 86 | 持久 registry（N11） | 純 Java；N21-f | 未做 | 開放；不依賴引擎 |
| 85 | support 布林換形狀 | **已裁決**：D-039/D2-010 §1/MC63/BSI B.4；`support:bool` 在 protocol 3 不存在（N21-e） | 待辦清單第一項可勾 | 加一則指向裁決；隨 #89（N21）關 |
| 80 | Windows 併發解包 | A19 | `BundledNatives` 已修；`BundledEngine` 未修 | 加一則；隨 #89 關 |
| 75 | 樑在板下不接合 | T2 MC60c 已交付（in-process）；BSI C-6 在 T2 帳上（MC65a 島帳） | 引擎能力已到、wire 未到 | 加一則：能力已在 tectonic 落地，N15 wire 腿等 MC60d/MC65a |
| 69 | 換裝前五阻擋項 | 五項全部進 MC54/55/56/57/58 與 BSI；三個「決定」中 ① 欄位對位 → 被 BSI 記錄表取代、② 載重掛方塊六分量 → BSI `loads` 64 B（力矩 v1 為 0）、③ 挫屈 enum → BSI `bucklingState` | 已被 SWAP/BSI 吸收 | 結案（附對照） |
| 65 | 自重挫屈網格依賴 | MC66a（沿桿線性 Kg；預凍證據重現 −68%） | 判準已凍 | 開放至 v1.5 |
| 17 | 身分與權威 revision 協定 | BSI `id`/`revision` 有；C6 其餘不在 v1 | 部分 | 加一則：v1 涵蓋範圍 + v1.x 清單（C6） |
| 16 | demand mode / 控制纖維 / 失效事件 | `mode` + `governingFibre` 在 BSI；`failureType`/`handoffType` 在 C6 | 部分 | 加一則 |

**兩倉共同的待貼草稿**（新增，接在 T2 SWAP §5 之後；由 operator 張貼、末尾加署名）：

> **BR #69（結案）**：五個阻擋項在 tectonic2 v1.2+ 以 MC54–MC58 清償，並在 BSI v1 落成契約欄位（`loads` 64 B、`stations`/`facets`、`governingFibre`、`bucklingState`）。三個決定的去向：① 欄位對位 → 以 BSI 記錄表取代（不再照抄 `StressStation`）；② 載重掛方塊六分量 → `loads` 記錄（力矩 v1 必為 0）；③ 挫屈理由碼 → `bucklingState` 六值。以 `docs/ALIGNMENT_LEDGER.md` §5 為對照，本 issue 結案。
>
> **BR #85（指向裁決）**：支承形狀已裁決：地面 = Support 角色材料的方塊記錄 + `supportKind`（D-039、tectonic2 D2-010 §1、MC63、BSI B.3/B.4）；`support:bool` 在 protocol 3 不存在（N21-e）。待辦「等 tectonic2#15 裁決」可勾；其餘隨 #89 的 N21 落地。
>
> **T2 #5（阻擋說明）**：D 節差異戰役的 FrameCore 臂目前無實作、無 issue（`ALIGNMENT_LEDGER.md` A14/C1）；在 block-reality 裁決「保留為 vtable 對數臂」或「降級為語料 + verify.py 對 tectonic 臂」之前，D 節無法開跑。

---

## 6. Perfect engine（tectonic2 #18 + `EDITABLE_WORLD_RESEARCH.md`）對兩倉的意涵

詳版（現況盤點附 file:line、差距表、最小分階段設計、擬議裁決）在 tectonic2 `docs/specs/PE_GAP.md`；本節只放兩倉都要知道的結論。

### 6.1 要求 → 現況 → 差距（摘要）

| #18 要求 | tectonic2 現況（有 gate 的） | 差距 | 提案單元（編號於凍結時指派；暫稱 PE-n） |
|---|---|---|---|
| P0 `ReanalysisController` 從 `Session` 拆出 | `session.h`：dirty assembly 逐位（T32/T37/T38）、stale 迭代 + 兩軌 tier、cap（4/8）、收縮比守門（連兩次 > 0.9 → rebaseline）、sparse-fwd 顯示 lane（T39）；計數器 `rebaselines_`/`staleIters_`/`staleSupportSize()`、S1–S4 時間 | 策略是**固定常數**，不是量測驅動；沒有獨立的 controller 型別 | PE-1（先遙測；重構只在遙測之後） |
| P1 自適應線性梯（sparse 修正 → old-factor Krylov → rebaseline） | 只有前後兩段；無 Krylov lane | PCG 即可（K 對接地島 SPD；不定 = 機構 = 狀態轉換，MC65a），**不需要 MINRES**——這是本引擎 fail-closed SPD 紀律（FIX-C pivot 下限）給的簡化 | PE-2 |
| P2 遙測 + 線上成本模型 | 有計數器、有 `explain`（MC62 待做）；無 EWMA、無 p50/p95/p99、無編輯型/支撐大小/殘差歷史 | 缺一份標準化的 per-solve 紀錄 | PE-1 |
| P3 持久島 / 拓撲狀態 | MC41 島 = 接地分量；MC65a（已凍）把未接地分量獨立成 `MECHANISM` 島；`SolveStatus`、`FractureOutcome.mechanism`、`buckling.state` | 沒有生命週期列舉、沒有正交旗標、沒有逐島逐族有效性 | PE-3（建在 MC65a 上） |
| P4 物理動力 / 碎塊交棒 | v2 wire `fracture.step`：`FragRec{blocks, mass, centroid, inertia[6], vel, angVel}`（MC27/28）；Newmark lane（MC29/30） | **BSI 只有 `bsi.fracture` 字串**，動詞「後議」 | PE-4（契約加法） |
| P5 先基準再造因子修改 | `bench_rho`（8f：真實編輯 ρ ≤ 0.5、每迭代 64 ms vs 新鮮分解 2.0–2.3 s @104K）；Woodbury 降為選項（D2-009）；無 CHOLMOD 對照 | 缺「編輯支撐比 × 成本比」的相圖 | PE-5（RECORDED，永不是能力） |
| 留言 1/2：狀態正交化、逐族有效性 | 兩軌 + `quality.tierHonoured` 是每回覆一筆 | 逐島逐族 | PE-3 |
| 留言 3–8：PhysicsPatchManager、數值記憶、階層子結構、表示法運行期選擇、CPU/GPU 排程、五選一控制器 | 無 | #18 自己列為「later/conditional」 | 不排程；PE_GAP §4 具名為延伸點 |
| 留言 9：Krylov 適用性元資料（對稱/定性/預條件子/零空間） | `SolveStatus` + pivot 下限 | 作為 PE-2 的 lane 元資料 | PE-2 |

### 6.2 落在契約上的（全部加法；本 PR 不動契約）

- **PE-3 → BSI**：opt-in `islands` 區段（每島：`lifecycle u8 ∈ {static, unsupported, fragment, retired}`、`flags u8`（mechanism-risk / buckling-risk / fracture-active）、`validity[4] u8`（displacement / reactions / stress / buckling ∈ {predicted, converging, certified, invalidated}））。今日對映：`commit` 軌 → certified；`display` 軌 → converging；`MECHANISM` 島 → 全族 invalidated；`buckling.state ≠ computed` → buckling 族 invalidated。
- **PE-4 → BSI**：`bsi.fracture.step` 動詞 + `fragments` 區段（`mass f64; centroid[3]; inertia[6]; vel[3]; angVel[3]; blockFirst u32; blockCount u32` = 96 B）+ `broken` 區段；**只在 `commit` 軌**（P9；BR #16「display tier 不得發出不可逆 handoff」）；DET ×3。
- **PE-1/PE-2/PE-5 不上 BSI**（#18 原則 10：不洩漏 PCG/Woodbury/Cholesky/快取內部）；遙測走 v2 `explain`（MC62 加法）。

### 6.3 落在消費者上的（block-reality）

- **D-008 使 PE-2 今天沒有消費者**：v0.4 每 revision 全量 `world.declare`（D-041 §4），不用 `world.edit`；編輯幀重分析梯只在 D-008 否證條件（單棟 > 50k DOF 或全量 > 500 ms）成立或 MC67 之後才有人用。**這不是缺陷，是要寫下來的排序依據。**
- **PE-3 直接服務 HUD**：N17（帳目）、N18（挫屈狀態）、D-023「過期必標」對映到逐島逐族有效性；一次編輯可以只作廢挫屈族而不作廢位移族。
- **PE-4 是 v0.5 倒塌的前提**（`V04_PLAN.md` §5：脫離集合 → `FallingBlockEntity`、初速取自 FragmentCluster）；「算的歸算，演的歸演」在 BSI 上的形狀就是 `fragments` 區段。
- **PE-1 服務 T2 #5 §E**（p50/p90/p99 走產品路徑量）。

### 6.4 建議順序（perfect engine 線）

PE-1（遙測，可與 MC62 `explain` 合併）→ PE-3（島生命週期，建在 MC65a 上，契約批次 #2）→ PE-4（碎塊交棒，契約批次 #2）→ PE-5（基準，RECORDED）→ PE-2（Krylov 梯；等 D-008 否證或 MC67）。#18 的成功條件「知道該重用多少、何時無效、何處升級」由 PE-1 + PE-5 的相圖定義，由 PE-2 實現；在相圖之前實作 PE-2 是 #18 自己禁止的「先造再量」。

---

## 7. 建議順序（跨倉；每步一個 PR）

1. **本 PR（兩倉）**：本帳 + §3 的 B 系列全部清掉；T2 `PE_GAP.md`；不動契約、不動 `core/**`、不動 Java。
2. **T2**：契約加法批次 #1（§4 C5）判準先凍 → 兩倉同步 hash bump → BR 更新 `.github/tectonic2-contract-ref`。
3. **T2**：MC65a + MC64（#20）含 A2/A3/A4 → `bsi.core`；`bsi_expected_red.json` 清空；`run.py` 不帶 `--assume-caps` 執行 C-4/C-5/C-11。
4. **BR**：#89 接線（驗收含 A10/A11/A17）；#91 evidence BSI 臂；N22 開 issue（A14 裁決先行）。
5. **兩倉 CI**：A12 的兩步（T2 抓 BR 雜湊；BR 有 token 時對 T2 `main`）；operator 加 `TECTONIC2_TOKEN`（C3）。
6. **T2**：PE-1 判準凍結；MC61/MC62 側枝合流（D2-012；側枝仍在 origin，B12）→ v1.3。
7. **BR**：#90 Windows natives（D2-011 擇一）。

---

## 8. 修訂（dated 追記；上方原文一字不改）

### 2026-09-03 — 同一輪把 §7 的第 2、5 步做完，並裁決了 A14/C1

> 本節是**結清單**。上方 §2–§7 的原文一字不改；哪幾條已經不再成立，看這裡。
> 兩倉逐位鏡像，本節也是。

#### 已結清（不必再看上方那一條）

| 條 | 上方說 | 現在 | 落點 |
|---|---|---|---|
| **A1** | BSI 沒有每格挫屈判定欄位 | **契約已有** `blocks.flags` bit2 `bucklingCritical`，host 雙向一致性檢查（漏設與亂設同罪），只對 `ownerKind ∈ {member, facet}` 適用 | T2 `8d31914`；BR `c45918e`、`1dd130e`（Java `BlockResult.bucklingCritical()`） |
| **A4** | 警告碼無「未分類」出口 | `warningCode` += `UNCLASSIFIED`（**出口，不是預設**） | 同上 |
| **A5** | `members.section` 回宣告值 | **已修**：解析材料的 `defaultSection`。新加的 C-4 斷言**首跑就抓到它** | T2 `b700731` |
| **A7** | `precision.maxTimeMs` 被靜默接受 | 上能力閘 `bsi.precision.timeout`；本引擎**不宣告**（直接法不可中斷）⇒ `UNSUPPORTED` | T2 `8d31914` |
| **A8** | `numThreads` 無界；契約與 MC62 判準不一致 | schema `1 ≤ n ≤ 256`，越界 `PROTOCOL_ERROR`、**不夾擠**；與 `MC62_GUARDS` §1.6 逐字一致 | 同上 |
| **A10** | `bsi_capi_open` 選項無 schema、未知鍵靜默忽略 | 有 schema（`log`/`numThreads`/`probe`/`assumeCaps`/`x-*`）；非 `x-` 未知鍵 → `NULL` 並指名該鍵 | 同上；BR 側 `InProcessEngine.openOptions()`（**上方 A10 的處置寫錯一半**：`Math.max(1, n)` 只保證下界，`n > 256` 會讓 open 回 NULL；見 BR `GATES.md` 2026-09-03d 的 dated 追記） |
| **A12** | 跨倉漂移：T2 CI 對 BR 零檢查；BR 只比 pinned commit | **兩個方向都有腿**。T2 CI 讀 BR `Main` 的雜湊（**實測公開，HTTP 200，不需 token**）；BR 有 token 時比 T2 `main`。嚴重度依「不符代表什麼」定：預設分支之間 = 紅，PR 上 = 警告；**fetch 失敗永不綠** | T2 `f6f98b1`；BR `f212dfe` |
| **A13** | 判準寫 `--adapter tectonic` / `--adapter framecore` | 更正為 `--adapter capi --lib libbsi_tectonic.so`；**FrameCore 臂不存在也不會存在**（A14） | 兩倉文件 dated |
| **A14 / C1** | FrameCore 的 BSI 臂無實作、無 issue；N22 無法產生差異帳 | **裁決：不做**（operator「2 不要 frame core」）。BR **D-045**：N22 改形為單臂語料 + 封閉解，**dated 降一級**，`blocker` 類失去執行機構、預歸類表降為遷移說明；T2 **D2-015**：舉證責任提高一級，「FrameCore 也是這個數」不再是 oracle | BR `0188014`；T2 `db07061` |
| **C5** | 契約加法批次 #1（八條發現的契約面） | **做完**。判準 `docs/specs/BSI_ADD1.md`（`155b422`，先於任何 `contract/` 改動）；語料 8 → 10 案；`host_tests` 77 → 90；真引擎收割 `hard_red=0`、帳未變長也未過期 | 兩倉 |
| **§7 第 2 步** | 契約批次 #1 + 兩倉 hash bump + BR 更新 ref | 完成。雜湊 `c45f51fe…` → **`717aacedcd70…`**（44 檔）；`.github/tectonic2-contract-ref` 兩行同步到 T2 `b700731`；`diff -r` 空 | — |
| **§7 第 5 步** | 兩倉 CI 各補一步 | 完成（見 A12）。`TECTONIC2_TOKEN`（C3）**仍未設**，BR 那一腿仍 [暫] | — |
| **#18** | perfect engine 的路線待核 | **D2-014**：增量演進、量測先於架構、不重寫 `Session`；PE-1 → PE-3 → PE-4 → PE-5 → PE-2；**PCG 而非 MINRES**（fail-closed 紀律把不定矩陣定義成機構）；**不授權任何實作**，#18 在 PE-1 判準進 repo 之前不得關閉 | T2 `db07061` |

#### 仍然成立（沒動）

- **A2/A3**（`unassigned.why` 三方不一致、未解釋格預設 `RUN_TOO_SHORT`）、**A6**（警告全記 `BEARING_SKIPPED_FIXED`）——三者都等 **MC65a** 的 `blockWhy`。
- **A9/A11/A15/A16/A17/A18/A19** 未處理；**A11**（`arena.supported` 送 `true`）與 **A17**（`numThreads` 預設 1）仍是 **#89** 的驗收條件。
- **C2**（語料對真引擎 exit 0）：臂數由兩臂減為一臂（N23-f 隨 D-045 改形）；tectonic 臂仍是收割模式，**永不 exit 0**。
- **C3**（`TECTONIC2_TOKEN` 未設）、**C4**（T2 #21 的 L6 四套短少）、**C6/C7/C8** 照舊。
- **B 系列**在上一輪已清；本輪兩倉的 `CLAUDE.md` 進一步**改寫成現況**（不再疊追記層），歷史留在 `DECISIONS.md`（兩倉都加了索引）與 `GATE_LINE_REGISTRY.md` / `GATES.md`。

#### 本輪新增的照登（不在上方任何一條裡）

1. **這批有三條是行為變更，不是純加法**（A7/A8/A10）：今天成功、之後失敗。三者都是**探針量到**的 fail-closed 洞，不是讀出來的。
   契約主版**不 bump**——Part E 的破壞性定義不含「把未定義行為收緊成 fail-closed」；此判定照登在 `BSI_ADD1.md` §7 B1 供日後爭議引用。
2. **bit2 的規則在實作期被收緊**：原文只說「該格所屬島」，而地面格與未入模格也有 `island` 欄位 ⇒ 照原文寫出來的檢查會要求地面格帶旗標。
   收緊為 `ownerKind ∈ {member, facet}`，**這是加嚴不是放軟**，dated 在 `BSI_ADD1.md` §10 修訂 1。
3. **MC68-M2b 落地**：語料有了站在線上的 `dc > 1` 格（`C7-overloaded-flag`）之後，「不設旗標」才咬得到；
   MC68 變異腿 3 → 4 開關，四條全咬。**正確的修法是補 fixture，不是把斷言喊大聲。**
4. **D-045 的代價照登**：對數臂是唯一會抓到「新引擎錯在封閉解看不到的地方」的機構。
   封閉解涵蓋靜定案、平衡不變量、等變性；**不涵蓋**超靜定內力分配、殼的實際數值、多構件互動。**這一塊沒有替代機構。**

#### §7 之後的順序（更新）

1. **T2**：MC65a + MC64（#20）→ `bsi.core` 可宣告；`bsi_expected_red.json` 清空；順帶清 A2/A3/A6。
2. **BR**：#89 接線（驗收含 A11/A17；A10 已不再是驗收項，`openOptions` 已合規）。
3. **operator**：加 `TECTONIC2_TOKEN`（C3）——它是 BR 那一腿與 `engine-linux` 整個 job 的前提。
4. **T2**：PE-1 判準凍結（**#18 關閉的前提**）；MC61/MC62 側枝合流 → v1.3。
5. **BR**：#90 Windows/macOS natives；#91 evidence BSI 臂。

### 2026-09-04 — 整合稽核落帳、v2 程序（D2-016 ↔ D-046）、§7 順序第三次更新

> 本節照登本輪**量到的**整合現況，並把 v2 程序對兩倉的意涵記在對位帳裡；兩倉逐位鏡像。
> 稽核細節 tectonic2 `docs/V2_PROGRAM.md` §1；裁決 tectonic2 D2-016、block-reality D-046。

#### 量到的（Linux 箱，RECORDED；不是 gate 執行紀錄）

| 條 | 結果 |
|---|---|
| 契約 | 兩倉 `contract/` `diff -rq` 空；雜湊 `717aacedcd70…`（44 檔）；BR pin 兩行 = T2 `b700731` + 同雜湊 |
| 本檔 | 兩倉 `diff -q` 相同（本節寫入後重新確認） |
| 引擎（T2 HEAD `7ac9b4f`） | L2 133/133、L3、L4（`capabilities []`）、L5 DET ×3、host 90、MC68-MUT 4/4 綠；**L7 本箱 MISS**（共享 METIS，非出貨建置）；無 caps 語料全 SKIP；收割 執行 5 / SKIP 5 / 帳上紅 2 / hard_red 0 |
| 跨語言（BR N24-b5） | BR `:api` + `:core` 對本輪建的 `.so`：259 PASSED / 1 SKIPPED / 0 FAILED；`InProcessEngineTest` 5/5 —— **首次在 CI 之外**對本機建的引擎跑 |
| FrameCore 臂（BR N22-a′） | `sidecar/verify.py dist/br-sidecar` ALL PASS（330） |
| 遊戲流程 | 仍走 `SidecarClient`（#89 等 T2 #20） |
| 沒量到 | Windows 九套、L6 其餘八套、L7 靜態自足、BR `forge` 模組、跨箱效能、`TECTONIC2_TOKEN` 那一腿（C3 仍未設） |

**一句話**：介面層已整合、能力層未整合、產品層未切換。

#### v2 程序對兩倉的意涵（全部加法；本節不動契約）

| 條 | T2 | BR |
|---|---|---|
| 設計鎖 / 計畫 | `docs/specs/V2_ARCHITECTURE.md`、`docs/V2_PROGRAM.md`；D2-016 | D-046（對位）；`SWAP_PROGRAM.md` §8 2026-09-04 |
| 軌 A（消費者對位）只等 V2_PIN | MC63 → MC64 → MC65a → `bsi.core` 宣告 + `bsi_exit`（#20）→ MC65b → MC60d → MC66a/b | #89 的引擎側前提隨 #20 成立；`sidecar/expected_red.json` 隨 T2 帳清空 |
| 契約加法批次 #2（v2.0-alpha） | `BSI_ADD2.md` 先凍：`include:"islands"` 12 B、`bsi.islands.state`、`bsi.fracture.step` + `broken` 24 B / `fragments` 136 B | 同步 hash bump、`.github/tectonic2-contract-ref` 兩行、Java 記錄型別；v0.5 倒塌吃 `fragments`（`V04_PLAN.md` §5 dated） |
| 版號 | `kEngineVersion` 1.3.0（v1.3）… 2.0.0（v2.0；major 標架構不標破壞） | `bsi.hello` 的 `version` 只顯示，不裁決；契約主版不 bump |
| 仍然成立 | A2/A3/A6 等 MC65a；A9/A11/A15–A19 未處理；C3/C4/C6–C8 照舊 | 同左 |

#### §7 之後的順序（第三次更新；取代 2026-09-03 節的同名小節）

1. **T2**：V2_PIN 判準凍結 → pins 落地（v1.3 出口之一）；側枝 `03de26c` 合流 MC61/MC62（D2-012 執行）。
2. **T2**：MC63 → MC64 → MC65a（建在 `island/component_graph.h`）→ **#20 關閉**、`bsi_expected_red.json` 清空、`bsi_exit` 第十套。
3. **BR**：#89 接線（驗收含 A11/A17）；#91 evidence BSI 臂；`expected_red.json` 隨之清空。
4. **operator**：`TECTONIC2_TOKEN`（C3）。
5. **T2**：V2_STATE → V2_LANES → PE-1（v1.6）；MC65b → MC60d → MC66a/b（v1.4 / v1.5）。
6. **兩倉**：`BSI_ADD2.md` 先凍 → PE-3 / PE-4 → 同步 hash bump（v2.0-alpha）。
7. **BR**：#90 Windows/macOS natives（T2 軌 C3 的 `.dll` 是前提）；v0.5 倒塌。
8. **T2**：PE-5 相圖 → PE-2 條件式 → V2_MOVE 或模組地圖 → v2.0。
