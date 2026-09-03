# SWAP_PROGRAM — block-reality ↔ tectonic2 換裝總綱（權威版，2026-09-02）

> 兩倉各存一份：本檔是**權威版**；tectonic2 的 `docs/SWAP_PROGRAM.md` 是引擎側鏡像（只放單元表、加法清單、issue 回覆草稿）。
> 凡與鏡像衝突，以本檔為準並在鏡像 dated 追記。
> 裁決落在 `docs/DECISIONS.md` D-038..D-043；判準落在 `docs/GATES.md`（N19–N23 + 2026-09-02 登記表）；
> 契約落在 `contract/`（BSI v1，與 tectonic2 逐位相同，`contract/CONTRACT_SHA256` 釘死）。
> 本檔**不含程式碼變更**；Phase 0 只動文件、判準與契約。

---

## 0. 目的與範圍

兩倉各自把力學做對了，卻沒有一條契約跨過去。tectonic2 的使命線就是本倉（其 ROADMAP 明載），
本倉 D-034 裁「v0.4 主軸 = 換裝」、D-037 裁「引擎側能力一律由 tectonic2 提供」；但兩邊的 wire 完全不同，
兩邊的文件對彼此的認知都停在一週前，而且有五道設計題互等對方。

本檔做四件事：
1. 把**事實**攤開（§1）與**落差**記帳（§2，G1–G32，每條附 file:line）；
2. 把**裁決**編號並落到 D-0xx（§3）；
3. 把**判準**先凍（§4）——包含哪些既有 gate 會在換裝期紅、為什麼、何時轉綠（`sidecar/expected_red.json`）；
4. 定**介面**（§5，BSI v1）與**順序**（§6），並把四個玩家面問題的**明示假設**記下（§7）。

範圍外：程式碼（Phase 1–5）、tectonic2 側的實作（它自己的 spec 與 gate）、發布。

---

## 1. 事實地圖（2026-09-02）

| 項 | block-reality（`f8a656f`，0.4.0-dev；最後發布 v0.3c） | tectonic2（`fcdf036` = 總綱起算點；`kEngineVersion` 1.2.0 未 bump；分支 `claude/two-projects-integration-design-nzzbpq` 已凍 11 份判準） |
|---|---|---|
| 力學所在 | sidecar C++（抽取 + FrameCore v4 靜態連結 + 3 patch）；**Java 仍做三件力學**：`StressFieldSpec` 站位重生、`ShellFieldSpec` MITC4 內插 + Mohr + vM、三處 `>1.0` 判定 | `core/mc/extract.h`（D-010 語意、§1d 面節點、monolith、MC60c 承載耦合）、`readback.h`（站位 D/C、纖維號向、中性軸）、`live.h`（編輯串流兩軌） |
| wire | `hello/solve/solve.shm/bye`；protocol 2 / shm 2 / 封包 "5"；mm·MPa·N·mm；字串 token；每 solve 全量方塊 | frame_v2 C ABI：16 method；`world.declare`（40 B 記錄）、`solve.request`（9 B/塊）、`readback.members`（MC56 opt-in）、`buckling.query`（Euler 篩）；SI；i32 詞彙索引；26 錯誤碼 |
| 方塊記錄 | `{x,y,z,mat:str,section:str,support:bool}` | `x,y,z,mat,sect,axis,joint,axisRot,fill,strength`；支承 = 相鄰 Support 角色方塊；**axis 必填** |
| 詞彙 | 寫死 steel(E=200 GPa)/rebar(圓)/concrete/timber/brick、實心矩形、板 token | `Vocab::builtin()` + `vocab.declare`；`SectKind{Rect,HShape,Box,Pipe,RcRect}`（無圓形，MC63 補）；Rect 一律 `Asy=5/6A`（Timoshenko） |
| 板網格 | 2×2 方塊一片、角在方塊中心、邊緣少半格 | 一方塊一片、角在中面格角；孤立格/板條也出 facet |
| 載重 | 受載方塊成節點（切段；N16 病因） | MC54 不切；端格拒絕（`LOAD_AT_NODE`，MC64 改節點載重） |
| 島 | 連通分量含未接地（D-017），奇異島 MECHANISM 列報（N17） | 接地分量才是島；未接地 → 整包 Singular（MC65a 對位） |
| 挫屈 | 全域特徵值 λ_cr、每島、四態 | Tier 2.5 Euler 篩；特徵值原在 v3 版圖（MC66a/b 提前到 v1.5） |
| 顯示場 | 場參數 + `stations[]`（11 站，client Java 重生） | MC56 opt-in 欄位；`stations[]` 不交付（MC65b 補） |
| 判定 | Java 三處 `>1.0` | 引擎給 double（MC64 下發 boolean） |
| 建置 | CMake + GCC；Windows 由 Linux MinGW 交叉；可重現；jar 內建引擎（D-027） | `.bat` + MSVC + conda；不可重現；側枝 `khld1u` 有 Linux g++ 臂（八套全綠）**未合流** |
| 對彼此的認知 | 文件寫「capi 沒有 LiveSession」（那是 FrameCore 的 capi） | 停在 `bdba72c`（8-30 10:37）；一小時後凍的 N16/D-037/`#14` 未看到 |

**已一致、不必重做**：Java 說方塊／引擎擁有模型（D-006 ≡ `extract.h:3-7`）；面節點 §1d ≡ D-028/D-035；`McBlock.axis` ≡ D-029；
雙倍座標 ≡ 半格單位；monolith ≡ D-030（T2 已出貨）；server 單點權威；D-008 全量重解 ≡ T2 E-C 176 ms @ 51K 格。

---

## 2. 落差總帳（G1–G32；處置 → §3 的裁決、§6 的單元）

| # | 落差 | 出處 | 處置 |
|---|---|---|---|
| G1 | wire 形狀/verb 完全不同；wire 版雙引擎 gate 不存在 | `ENGINE_BOUNDARY.md:51-66`；T2 `tec_capi.cpp:1873-1894` | **R0**（BSI v1）、R10（MC60d） |
| G2 | 單位 mm·MPa·N·mm vs m·Pa·N·m；g 9.81 vs 9.80665 | `sidecar/main.cpp:8,1052,1108`；T2 `solve.h:33` | R0（介面一律 SI；mm 是顯示層換算）；**g 由請求送入，兩後端同 g，不移線** |
| G3 | 詞彙：字串 vs 索引；timber/brick 缺；E_steel 200 vs 210；實心矩形 vs H；rebar 圓形無 kind；板厚掛材料；驗證器要 Support 有 E/G/rho、Pipe 查錯欄位 | `main.cpp:101-165`；T2 `vocab.h:25,77-110`、`tec_capi.cpp:1489-1516` | R3 → MC63 |
| G4 | 支承：`support:bool`（Java 裁決、隱含全固）vs 相鄰 Support 方塊 | T2#15 / #85；`StructureManager.java:625-639` | R4 → D-039、MC63 |
| G5 | 特徵值挫屈：T2 無；N16 零對應；`[C14]` 需殼 Kg | T2 `buckling.h:5-6`；`GATES.md:470-538`；`verify.py:577-700` | R5 → D-040、MC66a/b；N16-c 修訂 |
| G6 | 顯示軌方向相反（#11 vs #3）；Java 三處公式；站位不在 T2 wire；殼只有形心 N/M | `StressFieldSpec.java`；T2 `MC56_DISPLAY.md:103-105`、`readback.h:169-180` | R6 → D-038、MC65b |
| G7 | 判定 boolean 在 Java；穩定性三態 | T2#10；`StressResultPacket.java:193,317,357` | R7 → MC64、N19 |
| G8 | `unassigned` 理由碼、島 id、`governingFibre` 在 T2 wire 無對應；方塊引用是 sorted 索引 | `main.cpp:68-74`；T2 `tec_capi.cpp:1786-1800` | R8 → MC65a |
| G9 | 板網格慣例不同；受載切段 vs 不切；`lengthMm`/member id 在承載耦合下會變 | `main.cpp:242-250`；T2 MC60c B3 | R9 → D-042、登記線移動 + expected-red |
| G10 | T2 側枝未合流；編號衝突；axis 越界仍在 main | T2 `extract.h:310,769-771` | R11 → T2 D2-012、MC61/MC62 |
| G11 | T2 無 Linux/MinGW 建置、不可重現；BLAS 執行緒數不在 wire；無 `TEC_CAPI_STATIC` | T2 `sn_chol.h:64-68`、`tec_capi.h:132-140` | R12 → T2 D2-011、MC62 |
| G12 | 帶耦合世界只能 `solveLinear`；`world.edit` 靜默降級；`track:"display"` 被忽略 | T2 `live.h:252-266,319-343` | R13（v0.4 全量 declare，不生效）；MC62 可觀測；MC67 低優先 |
| G13 | 板永不被旋轉夾持 → `readback.shelledges` 恆 0；patch 0002 F76 能力消失 | T2 MC58b-10 | R14（F76 退場，登記） |
| G14 | 分析範圍綁載入 chunk；N11 未做 | #86 | R15（持久 registry；Q2） |
| G15 | Windows 併發解包競賽 | #80 | R15 |
| G16 | 文件過期（本倉：capi/LiveSession、H-400、`BucklingPolicy` javadoc 72.8 s、CITATION 0.3c；T2：Q9 註解、行號漂移、README 1.1.0、registry 無 MC60a/b/c） | 各處 | Phase 0（本輪；T2 已修） |
| G17 | `solveInfluence` 無耦合守衛；MC60a/b/c 無 capability 字串 | T2 `influence.h:46`、`tectonic.h:61-111` | MC62 |
| G18 | **D/C 定義不同**：T2 三元組纖維篩 + 殼 Mohr；FrameCore 五比值 argmax + 殼 vM；本倉 SHEAR/TORSION 零 oracle | T2 `readback.h:92-182`；`main.cpp:690,1171`；`GATES.md:217-229` | R10：差異帳歸 `convention`（N22） |
| G19 | **Timoshenko 預設**（T2 Rect `Asy=5/6A`）vs 本倉 EB | T2 `model.h:79`、`assemble.h:45` | R2/R3（EB 旗標為對數臂）；D-042（遊戲預設 Timoshenko） |
| G20 | **端格載重被拒**（`LOAD_AT_NODE`）；本倉幾乎所有載重 fixture 掛端格 | T2 `tec_capi.h:124`；`verify.py:159,588,1135` | MC64（節點載重） |
| G21 | 未接地分量不是島、整包 Singular；無 `singularIslands`；無島 id | T2 `island.h:81-88`、`solve.h:1341-1344` | MC65a；D-017 dated |
| G22 | 殼只有形心合力（無 Q、無角點、無底面） | T2 `solve.h:43-47` | MC65b |
| G23 | `PLATE_LONE/STRIP/SOLID` 在 T2 不存在（孤立格/板條也出 facet） | T2 `extract.h:1329-1340` | expected-red `[S6]`；MC65a 對表 |
| G24 | Phase 3 時 `verify.py` 不可能全綠（λ 腿等 MC66） | — | expected-red 帳（§4） |
| G25 | datapack 驗證器缺陷（Support E/G/rho、Pipe p2） | T2 `tec_capi.cpp:1489-1516` | MC63 |
| G26 | `numThreads` 不上 wire | T2 `tec_capi.cpp:636-649` | MC62（`body.numThreads`） |
| G27 | 靜態連結無 `TEC_CAPI_STATIC` | T2 `tec_capi.h:132-140` | MC62 |
| G28 | g 可由請求覆寫（免移線） | T2 `tec_capi.cpp:640-647` | R2 |
| G29 | `DYNAMIC_ARCH` vs 單一 TARGET 的決定論/相容 | T2 `tectonic.h:76-88` | R12（same-build-same-machine 維持；跨機只宣稱 verdict parity） |
| G30 | 靜態 OpenBLAS 讓 jar 暴增 → D-027 否證 (2) | `DECISIONS.md` D-027 | R12（jar 只帶 tectonic 一顆/平台） |
| G31 | `verify.py` fixture 全無 axis；BSI 記錄 axis 必填 | `main.cpp:87-92`；T2 `extract.h:64-67` | R3（fixture 機械補 axis） |
| G32 | 兩倉「島」定義不同 | 同 G21 | D-017 dated 追記 |

---

## 3. 裁決索引（每條的全文與否證條件在 `docs/DECISIONS.md`）

| R | 內容 | 落點 |
|---|---|---|
| **R0** | **BSI v1 通用介面**：引擎中立、傳輸無關、零複製 arena、精度可調；兩倉 `contract/` 逐位相同、雜湊釘死；Java 直接說 BSI；sidecar 退為傳輸轉接；FrameCore 對數臂也實作 `bsi.*` | **D-043**（取代 D-034(1)「Java 一行不動」） |
| R1 | 宿主 = 傳輸轉接（stdio 門鈴 + arena），引擎原始碼靜態連結（`TEC_CAPI_STATIC`）；逾時 → 結束子程序 → D-013 退避重啟；同 revision 三次 → `ENGINE_FAILED` | **D-041** |
| R2 | 介面一律 SI；mm/MPa 只在顯示層；**g 不移線**（請求送 `gravity`）；座標不換軸 | D-043、D-042 |
| R3 | 本倉目錄以 `vocab.declare`（BSI `bsi.vocab.declare`）送進引擎；斷面形狀不換（實心矩形仍 `rect`）；圓形走 `circle`（MC63）；axis 必填 | D-042、MC63 |
| R4 | 支承 = 宣告的地面類別（Support 角色材料 + `supportKind`）；Java 送「面相鄰的地面格是什麼」，引擎決定固定度；哪些鄰居算接觸 = 玩法裁決（Q1） | **D-039**（取代 D-022）、N21 |
| R5 | 挫屈 = tectonic2 特徵值 lane（MC66a 桿件、MC66b 殼）；`bucklingFactor` 語意不變 + `buckling.kind`；N16-c 改「上界、方向具名」；第五態 `solver-failed`；規模政策由宿主以 nodes/dof 決定（Q3） | **D-040**、N16-c/N18 登記 |
| R6 | 顯示軌由引擎預求值（站位陣列、殼角點/Q/底面）；Java 刪光公式；場參數（#3）RETIRED | **D-038**（D-021 作廢）、N20 |
| R7 | 判定 boolean 由引擎下發（`overloaded`/`stability`/`failureType`）；Java 刪三處 `>1.0`；`alignToVerdict` 對齊引擎旗標 | D-041、N19、MC64 |
| R8 | 帳目對位：`readback.unassigned` 理由碼（開放列舉）、島 id、世界座標、`governingFibre` | D-017 dated、MC65a |
| R9 | 採引擎慣例：板網格一格一片、載重不切段、承載耦合切逐格的代價；登記線移動 + expected-red | **D-042**、GATES 登記表 |
| R10 | 差異帳：同一語料兩引擎逐欄比對，分類規則先凍，零 blocker 才發布 | **N22**、MC60d |
| R11 | tectonic2 側枝合流、重新編號（MC61/MC62）、v1.3 | T2 D2-012 |
| R12 | 建置：T2 加 CMake；Linux apt 靜態；Windows 走本倉 MinGW 交叉鏈自建 OpenBLAS/METIS；`DYNAMIC_ARCH=1`；jar 只帶 tectonic 一顆/平台；FrameCore 臂不進 jar | T2 D2-011、D-041、D-027 dated |
| R13 | 每 revision 全量 `world.declare`（不用 `world.edit`）；顯示軌不宣稱兩次求解（D-023 形狀不變） | D-041、D-023 dated |
| R14 | 板支承只鎖平移不改；F76 能力退場 | D-042、GATES 登記 |
| R15 | Java 兩件：持久 registry（#86、N11；Q2）與 Windows 解包競賽（#80） | Phase 3 |

---

## 4. 判準索引（全文在 `docs/GATES.md` 2026-09-02 節）

| 判準 | 受測者 | 一句話 |
|---|---|---|
| **N19** | Java | 判定旗標轉發：Java 不比 `dc > 1`，封包旗標 == 引擎旗標 |
| **N20** | Java | 站位/角點 f32 封包 vs sidecar f64 rel ≤ 1e-5；Java 零應力公式（grep 型） |
| **N21** | Java | 地面列舉：面相鄰、去重、規範序、鏡像等變；記錄數照登 |
| **N22** | sidecar/CI | 差異帳：分類規則凍結；`evidence/differential.jsonl` 零 `blocker`；記名排除 ≤ 3 類 |
| **N23** | CI | 契約一致性：`CONTRACT_SHA256` 重算相符且與 tectonic2 釘住的 tag 相同；`conformance/run.py --selfcheck` 綠；`hello` 雜湊不符 → `BSI_VERSION` |
| expected-red | CI | `sidecar/expected_red.json`：帳上的紅不算失敗、帳外的紅照樣紅、**帳上卻綠 = 過期 = 紅** |

**登記的線移動**（GATES 判準異動登記表 2026-09-02）：N16-c、N18 第五態、板網格/面積、載重切段、Timoshenko 預設、D/C 定義、F76 退場、
支承語意（D-022 → D-039）、`[M1]/[M2]` brick fixture、`lengthMm`/member id。

---

## 5. wire 對照表（protocol 2 → BSI v1 → frame_v2；逐欄）

| 概念 | protocol 2（現況） | **BSI v1（`contract/BSI.md`，目標）** | frame_v2（T2 現況；MC68 內部映射） |
|---|---|---|---|
| 握手 | `hello`（目錄 + shm 能力） | `bsi.hello`（`contractSha256`、能力字串、`threads`、`arena`、`precision`） | `hello`（capabilities） |
| 詞彙 | 寫死在 sidecar | `bsi.vocab.declare` / `.query`（材料模型、斷面 kind、`supportKind`、`eulerBernoulli`、`x-` 擴充） | `vocab.declare` / `vocab.query`（MC63 加法） |
| 世界 | `solve` 的全量 `blocks[]`（`support:bool`） | `bsi.world.declare`：40 B 記錄（`axis` 必填、offset 23 `attr`）+ 可選 `attrs` 區段；地面 = Support 角色記錄 | `world.declare`（40 B，同欄位序） |
| 增量 | 無 | `bsi.world.edit`（41 B 差分；`edit.class/downgraded`）— v0.4 不用 | `world.edit`（MC62 `explain`） |
| 求解 | `solve` / `solve.shm`（mm/MPa；`buckling:bool`） | `bsi.solve`：`loads` 64 B（SI；端格 = 節點載重）、`precision{tier,targetRel,storage,warmStart,maxTimeMs}`、`buckling{mode,budgetDof}`、`numThreads`、`readback[]` | `solve.request`（`body.gravity`、MC54 `loads`、MC62 `numThreads`/`explain`、MC64 `verdict`、MC65a `islands`、MC66a `buckling`、MC60d `readback`） |
| 每格結果 | `members[].blocks` 索引 + Java 反查 | `blocks` 24 B：`dc, island, owner, mode, ownerKind, flags(overloaded/indicative), reason` | 9 B → 16 B（MC64/MC65a） |
| 帳目 | `unassigned[{why,blocks}]`（7 碼） | `unassigned` 區段（開放列舉：`MECHANISM, FULLY_SUPPORTED, RUN_TOO_SHORT, PLATE_NO_FACET, BULK_UNSUPPORTED, BULK_GROUND, NON_STRUCTURAL, REFUSED`） | `readback.unassigned`（MC65a） |
| 構件 | `members[]`（端力、場參數、`stations[11]`） | `members` 160 B + `stations` 88 B（f32 44 B）：`s, x, sigma[4], tau, naY, naZ` | `readback.members include:"stations"`（MC65b） |
| 殼 | `shells[]`（形心上下層 5 點） | `facets` 280 B + `facetSurfaces` 256 B（四角 × 上下面 `s1,s2,theta,vm`；`n,m,q`） | `readback.shells`（MC65b） |
| 平衡 | `applied`/反力和（Java 加總） | `equilibrium` 56 B（`applied[3], reaction[3], residual`） | `readback:["equilibrium"]`（MC60d） |
| 挫屈 | `bucklingFactor` + `bucklingState`（四態） | `buckling` 16 B：`kind ∈ {eigen, screen}`、`state`（六值含 `solver-failed`、`not-eligible-scale`）、`factor` | `solve.request body.buckling`（MC66a）；`buckling.query`（`kind:"euler_screen"`） |
| 精度 | 封包 f32；rel ≤ 1e-5 | `quality` 16 B：`achievedRel, iterations, tierHonoured, warmStartUsed, storage, timedOut` | MC62 `track.honoured`、`relRes` |
| 錯誤 | 自由文字 | 21 碼（`bsi.schema.json` `x-errors`） | 26 碼（映射） |
| 傳輸 | stdio line-JSON + shm（`solve.shm`） | T-A（frame）/ **T-B（stdio 門鈴 + arena `BSIA`，零複製）** / T-B′（base64） | frame_v2 |
| 版本 | protocol 2 / shm 2 / 封包 "5" | `bsi:1` + `contractSha256`；破壞 = 主版 bump | `v:2` |

**單位**：BSI 一律 SI（m、Pa、N、N·m、kg/m³）。`mm`/`MPa`/`N·mm` 只存在於 Java 顯示層與 `/br` 指令輸出。

---

## 6. 順序與 tectonic2 版本綁定

| Phase | 內容 | 綁定 | 驗收 |
|---|---|---|---|
| **0**（本輪）| 兩倉文件、判準、契約；不動 `core/**`、不動本倉程式碼 | T2 判準檔 11 份已凍（`docs/specs/MC6*.md`） | 兩倉 `contract/CONTRACT_SHA256` 相同；`run.py --selfcheck` 綠 |
| **0.5** | 探針週（五個一週 spike，各產一張進判準的表：慣例差異血量、MC66 數值形狀、雙平台靜態建置、側枝實況、支承代價） | — | 表進 GATES/N22 分類規則、D-039 否證 (2) 量化 |
| **1** | T2 **v1.3**：側枝合流 + MC61 + MC62 + CMake；九套 Linux 全綠 | T2 D2-011/D2-012 | T2 `ALL PASS` ×9 + LP-DET |
| **2** | T2 **v1.4**：MC63 → MC64 → MC65a → MC65b → MC60d | 每單元先凍後做 | T2 各 spec §5 全綠 |
| **3** | 本倉宿主與 Java：sidecar 重構（`wire/`、`backend_tectonic/`、`backend_framecore/`）+ **BSI codec**（Java `BsiCodec` + sidecar 傳輸轉接）+ Java 公式刪除（D-038）+ `StructureManager`（地面列舉、axis、registry）+ `BucklingPolicy`（nodes/dof）+ #80 + `verify.py`（axis、expected-red）+ `diff_engines.py` + CI 建引擎 job + `package.sh` | T2 v1.4 | `verify.py` 除帳上的腿外全綠；差異帳零 blocker；N19–N23 綠 |
| **4** | T2 **v1.5**：MC66a/b；本倉 N16 綠、`[C12]/[C13]/[C14]` 轉綠、`bucklingBlockLimit` 重選、expected-red 帳清空 | T2 v1.5 | 帳空 |
| **5** | 資格門（T2#5）：差異語料零未解 blocker、真實伺服器重複編輯 soak、雙平台 artifact 雜湊進 manifest；v0.4 發布，預設 tectonic；FrameCore 臂保留為 CI 對數一個發布週期 | T2 v1.6（MC68 BSI 實作） | 發布 |

**T2 側加法（全部 opt-in，不要求時既有回覆逐位不變）**：MC62 `explain`/`numThreads`；MC63 `supportKind`/`circle`/`eulerBernoulli`；MC64 `verdict`；MC65a `islands`/`readback.unassigned`/`coords`/`governing`；MC65b `stations`/`readback.shells`/`storage`；MC60d `readback:["equilibrium"]`；MC66a `buckling`；MC68 `bsi.*`。

---

## 7. 玩家面假設（已問、未獲回答；**採推薦選項為明示假設**，核准時可覆寫；寫進對應決策的「假設」欄）

| Q | 題 | 採用 | 備選 | 落點 |
|---|---|---|---|---|
| **Q1** | 接地：哪些原版方塊算剛性地？ | **(a)** 六面接觸的「站得住」原版方塊都是剛性地（代價照登：貼牆的柱、坑裡的橋墩每格都是切點且全固，D/C 趨零——這是嵌固的物理） | (b) 只有下方；(c) 只有宣告的基礎方塊；(d) 地質類別（v1 後） | D-039、N21 |
| **Q2** | 持久 registry 上限 | **(a)** 可設定警戒線（預設 2,000,000 格/維度），超過拒登新結構方塊並提示 | (b) 只分析最近結構；(c) 硬上限擋放置 | R15（#86） |
| **Q3** | 挫屈規模政策 | **(a)** 宿主以每島 `nodes/dof` 決定是否請求特徵解；超預算島 `not-eligible-scale`，HUD「未評估（規模）」 | (b) 永遠算；(c) 指令時算 | D-040 |
| **Q4** | 樓板合成 | **(a)** v0.4 全合成（MC60c v0 無遮罩），HUD/文件具名 indicative | (b) 剪力釘宣告動詞（MC67 之後） | D-042 |

---

## 8. 修訂（dated 追記；上方原文一字不改）

- **2026-09-02**：本檔與 D-038..D-043、N19–N23 隨 PR #87 併入 `Main` = `4cfa551`；tectonic2 鏡像隨其 PR #16 併入 `main` = `7b61cbd`。
- **2026-09-03（D-044 改形 + PR #88/#19 落地；原文不改）**：
  1. **出貨形狀**：D-044 取代 §3 R1「宿主 = 傳輸轉接（stdio 門鈴 + arena）」——引擎是進程內共享庫（tectonic2 D2-013），Java 以 JNA 綁 `contract/bsi_capi.h`；
     §5 傳輸列的目標改為 **T-A（`bsi_capi`，進程內）**，T-B/T-B′ 是 dev/CI 臂；R1 的逾時/重啟形狀作廢（D-041 dated）。
  2. **§6 Phase 3 改形**：不再有「sidecar 重構 `wire/`、`backend_tectonic/`、`backend_framecore/`」；Phase 3 = `InProcessEngine` 接線（#89）+ Java 公式刪除（D-038）
     + `StructureManager`（地面列舉、axis、registry）+ `BucklingPolicy`（nodes/dof）+ `evidence.py` BSI 臂（#91）+ `verify.py`（axis、expected-red）+ Windows/macOS natives（#90）。
     **B8 的 Java codec 已落地**（PR #88：`core/bsi/`、`core/engine/`、N24-a/b）。
  3. **單元順序反轉照登**：tectonic2 的 MC68 轉接器（§6 綁 v1.6）已**先於** v1.3/v1.4 落地（`core/capi/bsi_adapter.cpp`，PR #19），能力宣告空集合，
     語料 C-5/C-6/C-11 端格在其 `gate/bsi_expected_red.json` 帳上（MC65a/MC64）；§6 的版本綁定表 v1.6 列改讀為「MC68 能力宣告非空 + 語料全綠」。
     LINUX_BUILD（CMake 臂、`libbsi_tectonic.so`、L1–L8）亦已落地，**不是**側枝合流（側枝 `03de26c` 仍在 origin，MC61/MC62 合流未做）。
  4. **§1 事實地圖更正**：tectonic2「無 Linux/MinGW 建置、不可重現」→ Linux CMake 臂存在、L7 自足實測；「側枝未合流」仍成立。
     本倉 CI 新增 `contract`（host + stub + N24-a 打包）與 `engine-linux`（**需 `TECTONIC2_TOKEN`，未設前量不到東西**）兩個 job。
  5. **§5 用詞更正**：BSI 求解的區段選單是 `include`（契約 `x-enums.include`），不是 `readback[]`（那是 tectonic2 v2 wire MC60d 的欄位）。
  6. **FrameCore 對數臂**：R0/D-043 §2「FrameCore 對數臂也實作 `bsi.*`」與 N22 的 `diff_engines.py`/`br-sidecar-fc` **尚無實作、無 issue**（對位帳 A14/C1）；
     Phase 5 的差異戰役在此裁決前不能開跑。
  7. 契約 ↔ 判準的不一致（每格挫屈旗標、`GROUND`/`FULLY_SUPPORTED`、警告碼）、語料缺口、跨倉 CI 漂移偵測的不對稱，全部在 `docs/ALIGNMENT_LEDGER.md` §2（A1–A19）、§4（C1–C8）；
     處置以「契約加法批次 #1」一次完成（先凍再做，兩倉同步 hash bump）。
  8. §5 的回覆草稿與 tectonic2 鏡像 §5 的十二則**皆未張貼**；新增三則在對位帳 §5。
