# BSI v1 — Block Structural Interface（方塊結構介面）

> **狀態：契約凍結（2026-09-02）。** 本檔、`bsi.schema.json`、`bsi_engine.h`、`conformance/` 在
> **block-reality** 與 **tectonic2** 兩倉各存一份，**位元組逐位相同**，雜湊釘在各自的
> `contract/CONTRACT_SHA256`。改契約的唯一方式：改 `contract/`、重算雜湊、**兩倉各自 bump**；
> 只改一邊，另一邊的一致性 gate 會紅（Part F）。修訂一律 **dated 追記，原文不改**（Part G）。
>
> 一句話：**任何力學引擎實作 `bsi_engine.h` 的十個函式，就得到方塊世界、材料詞彙、零複製傳輸、
> 精度控制、判定下發與一套會咬的一致性語料；封包、排序、驗證、記憶體全由共用 host 做。**

---

## Part A · 原則（每條對應一個可執行的 gate）

| # | 原則 | gate |
|---|---|---|
| P1 | **引擎中立**：介面只說方塊、材料、載重、判定；不說節點、自由度、元素、剛度矩陣。構件/板明細是 opt-in，且只用樑理論與板理論的共通語言。 | 語料斷言不引用引擎內部符號（C-0） |
| P2 | **事實上行、判定下行**：消費者只送世界事實；引擎回判定（D/C、超載、失效類型、穩定性、島歸屬、未入模理由）。消費者永不重算判定。 | C-7 |
| P3 | **一律 SI**：m、N、Pa、kg·m⁻³、N·m、m/s²。顯示單位是呈現層的事。 | schema `unit` |
| P4 | **數值走二進位、文字不帶浮點**：浮點只在 payload（LE IEEE-754）；header 只有整數、字串、布林、列舉。 | C-2、C-9 |
| P5 | **規範序**：契約定義全序（Part A §8）；同輸入 → 逐位相同 payload（同 build 同機器）。 | C-9 |
| P6 | **fail-closed，但可部分實作**：不認得的非 `x-` 欄位、越界值、版本不符 → 錯誤碼；引擎以能力字串宣告它有的功能，沒有的動詞回 `UNSUPPORTED`，不假裝。 | C-3、C-11 |
| P7 | **加法演進**：新區段/欄位一律 opt-in；不要求時既有回覆逐位不變；改語意 = 主版 bump。 | Part E |
| P8 | **零複製**：世界、載重、結果都住在共享記憶體 arena；引擎把結果**直接寫進** arena，消費者**就地讀**。 | C-2、B-perf（RECORDED） |
| P9 | **精度是請求的一部分**：`precision` 選項決定軌別、目標殘差、儲存精度、暖啟動；回覆帶 `quality` 說明實際達到什麼。判定旗標只在 `commit` 軌有效。 | C-7、C-12 |
| P10 | **判準綁介面**：能力宣稱 = `conformance/` 綠。任何實作、任何傳輸，同一套語料。 | Part F |

---

## Part B · 契約（訊息、記錄、慣例）

### B.1 訊息模型

訊息 = **header**（UTF-8 JSON 物件，鍵序 = schema 順序）+ **payload**（位元組串，可空）。
每則請求帶 `id`（字串，回覆原樣回填）與 `revision`（i64 世界版本 token，回覆原樣回填）。

### B.2 動詞

| 動詞 | payload | 回覆 | 最小能力 |
|---|---|---|---|
| `bsi.hello` | — | 引擎身分、`contractSha256`、能力字串、執行緒數、arena 需求 | `bsi.core` |
| `bsi.vocab.declare` | — | 各表計數 + id 表 | `bsi.core` |
| `bsi.vocab.query` | — | id ↔ 名稱表 | `bsi.core` |
| `bsi.world.declare` | 方塊記錄 ×N（+ 可選 `attrs` 區段） | `diag` | `bsi.core` |
| `bsi.world.edit` | 差分記錄 ×N | `edit.class/downgraded`、`diag` | `bsi.world.edit` |
| `bsi.solve` | 施力記錄 ×N | header + 結果區段 | `bsi.core` |
| `bsi.cancel` | — | `targetId` | `bsi.core` |

第一則非 `bsi.hello` → `EXPECTED_HELLO`；不認得的動詞 → `UNKNOWN_METHOD`；引擎未宣告能力的動詞 → `UNSUPPORTED`。

#### `bsi.hello`
請求 body：`{"bsi":1,"client":"block-reality/0.4.0","contractSha256":"…","arena":{"supported":true,"maxBytes":268435456}}`
回覆：`{"bsi":1,"engine":"tectonic","version":"1.5.0","buildSha":"…","contractSha256":"…","capabilities":[…],"threads":8,"arena":{"required":false,"minReplyBytes":65536},"precision":{"tiers":["commit","display"],"storage":["f64","f32"],"warmStart":true}}`

`bsi` 主版或 `contractSha256` 不符 → `BSI_VERSION`，之後所有動詞拒絕。能力字串是**保證**（只列語料家族全綠者）：

| 能力 | 意義 |
|---|---|
| `bsi.core` | hello/vocab/world.declare/solve；`blocks`、`equilibrium`、`unassigned`、`diag` |
| `bsi.world.edit` | 差分編輯 |
| `bsi.readback.members` / `.stations` / `.shells` | 對應區段 |
| `bsi.buckling.eigen` / `.eigen.shells` / `.screen` | 挫屈模式 |
| `bsi.precision.display` / `.f32` / `.warmstart` / `.timeout` | 精度控制（`.timeout` = 認得 `maxTimeMs`，見 Part G 第 16 條）|
| `bsi.material.orthotropic` / `.composite` / `.rope` | 材料模型 |
| `bsi.section.custom` / `bsi.block.shape` / `bsi.block.attrs` | 自訂斷面、體素形狀、方塊屬性 |
| `bsi.fracture` | 容量面/斷裂 lane（v1 只保留字串，動詞後議） |
| `bsi.dc.<scheme>` | D/C 定義具名（例：`bsi.dc.fibre3`、`bsi.dc.vm5`） |

### B.3 詞彙（`bsi.vocab.declare` body）

```json
{"version":1,
 "materials":[
  {"name":"steel","role":"member","model":"isotropic","E":2.0e11,"nu":0.3,"rho":7850,
   "allow":{"sigmaC":2.5e8,"sigmaT":2.5e8,"tau":1.45e8},
   "capacity":{"fc":2.5e8,"ft":2.5e8,"tauS":1.45e8},
   "defaultSection":"steel_rect_200x400","eulerBernoulli":false},
  {"name":"timber","role":"member","model":"orthotropic",
   "E":[1.1e10,7.0e8,4.0e8],"G":[7.0e8,6.0e8,6.0e7],"nu":[0.35,0.4,0.45],"rho":600,
   "allow":{"sigmaC":2.0e7,"sigmaT":1.4e7,"tau":2.0e6},"defaultSection":"timber_rect_140x240"},
  {"name":"rc","role":"member","model":"composite_rc","E":2.0e11,"nu":0.3,"rho":7850,
   "Ec":3.0e10,"rhoC":2400,"allow":{"sigmaC":2.0e7,"sigmaT":2.0e7,"tau":3.0e6},"defaultSection":"rc_300x400_4d25"},
  {"name":"rope","role":"member","model":"rope","E":1.0e11,"nu":0.3,"rho":1500,"allow":{"sigmaT":4.0e8},"defaultSection":"rope_20"},
  {"name":"concrete_slab_200","role":"panel","model":"isotropic","E":3.0e10,"nu":0.2,"rho":2400,
   "shellThickness":0.2,"allow":{"sigmaC":3.0e7,"sigmaT":3.0e6,"tau":4.0e6}},
  {"name":"concrete","role":"monolith","model":"isotropic","E":3.0e10,"nu":0.2,"rho":2350,
   "allow":{"sigmaC":3.0e7,"sigmaT":3.0e6,"tau":4.0e6}},
  {"name":"ground_rigid","role":"support","supportKind":"fixAll"},
  {"name":"formwork_prop","role":"support","supportKind":"translationOnly","temporary":true},
  {"name":"x-mymod-jelly","role":"member","model":"x-mymod:hyper","E":1.0e6,"nu":0.49,"rho":1000,
   "allow":{"sigmaC":1e5,"sigmaT":1e5,"tau":5e4},"x-mymod":{"c10":1.2e5}}
 ],
 "sections":[
  {"name":"steel_rect_200x400","kind":"rect","p":[0.2,0.4]},
  {"name":"rebar_round_d25","kind":"circle","p":[0.025]},
  {"name":"h400","kind":"h","p":[0.4,0.2,0.008,0.013]},
  {"name":"voxel_shape_17","kind":"custom","A":0.0123,"Iy":1.1e-5,"Iz":2.3e-5,"J":8.0e-6,
   "cy":0.09,"cz":0.11,"Asy":0.01,"Asz":0.01,"Zy":1.2e-4,"Zz":2.1e-4,"principalAngle":0.0}
 ]}
```

| 欄位 | 規則 |
|---|---|
| `role` | `member` / `panel` / `monolith` / `support` / `nonstructural`。**角色由材料宣告**，引擎永不從形狀反推。 |
| `model` | `isotropic`（E, nu 或 G）/ `orthotropic`（E[3], G[3], nu[3]，軸向 = 構件局部軸）/ `composite_rc`（鋼基 + `Ec`,`rhoC`）/ `rope`（tension-only）/ `x-<vendor>:<name>`（引擎自訂；未宣告 `bsi.material.x-<vendor>` 能力的引擎回 `UNSUPPORTED`，**不降級成 isotropic**）。 |
| `rho` | kg·m⁻³；member/panel/monolith 必填；support/nonstructural 不需要力學欄位。 |
| `allow` | 彈性 D/C 篩允許應力 Pa。三者皆給 → 非對稱纖維篩；只給 `sigma` → 對稱篩。 |
| `capacity` | 容量面（斷裂 lane）；缺 → `bsi.fracture` 對該材料 fail-closed。 |
| `shellThickness` | panel 必填 > 0（板厚掛材料）。 |
| `eulerBernoulli` | `true` 剪力面積歸零；預設 `false`（Timoshenko）。 |
| `supportKind` | `fixAll` / `translationOnly`；固定度由引擎依此決定。`temporary` = 模板撐。 |
| `kind` | `rect(b,d)` / `circle(d)` / `h(h,bf,tw,tf)` / `box(h,b,t)` / `pipe(d,t)` / `rcrect(b,d,As,lever)` / `custom`（直接給 A, Iy, Iz, J, cy, cz, Asy, Asz, Zy, Zz, principalAngle；來自體素造型器或 datapack 作者）。 |
| `x-*` | 任何層級的 `x-<vendor>` 鍵是廠商擴充：引擎不認得則**忽略並計數**（`diag.ignoredExtensions`）；**非 `x-` 的未知鍵 = `PROTOCOL_ERROR`**。 |

### B.4 世界

**方塊記錄（40 B，LE）**

| offset | 型別 | 欄位 | 規則 |
|---|---|---|---|
| 0/4/8 | i32 | `x`/`y`/`z` | 格座標，y 向上；`|c| ≤ 1073741822` |
| 12 | i32 | `mat` | 材料 id |
| 16 | i32 | `sect` | 斷面 id；`-1` = 材料預設 |
| 20 | u8 | `axis` | run 軸 `0=x 1=y 2=z`，**由放置宣告**；member 必填，其餘填 0 |
| 21 | u8 | `joint` | `0=rigid 1=pinned` |
| 22 | u8 | `axisRot` | 主軸四分之一轉 `0..3` |
| 23 | u8 | `attr` | `0` = 無屬性；`1` = 本格在 `attrs` 區段有一筆（依方塊序對應） |
| 24 | f64 | `fill` | 填充率 (0,1]，預設 1 |
| 32 | f64 | `strength` | 養護強度比 [0,1]，預設 1 |

**方塊屬性區段（opt-in `attrs`，能力 `bsi.block.attrs`）**：每筆 `blockIndex u32; key u32; type u8; reserved[3]; value[8]`（16 B），`key` 為 `bsi.vocab.declare` 的 `attrKeys` 表索引；標準鍵：`shape`（`bsi.block.shape`：4×4×4 體素遮罩 64 bit）、`damage`（f64，0..1）、`temperatureK`（f64）；`x-<vendor>:<key>` 為擴充。引擎不支援的**標準**鍵 → `UNSUPPORTED_ATTR`（拒絕，指名格）；不支援的 `x-` 鍵 → 忽略計數。`shape` 未支援但宣告 `fill` 時以 `fill` 為準並記警告碼 `SHAPE_APPROXIMATED`。

**地面**：消費者依其玩法規則（六面或只下方——消費者裁決）把結構方塊的面相鄰非結構方塊，以 `support` 角色的方塊記錄送出（去重）。引擎規則：面相鄰 support 格使該構件格成為切點、其節點依 `supportKind` 固定；貼 panel 只鎖平移。

**差分記錄（41 B）**：`op u8`（`0 add 1 remove 2 update`）+ 40 B。回覆 `edit.class ∈ {A,B,C}`、`edit.downgraded`（例 `"coupling"`；無降級不輸出）。

越界 axis/joint/axisRot、`attr > 1`、座標越界、重複格 → `PROTOCOL_ERROR`（整包拒絕，`at` 指名格）。

### B.5 求解

**請求 body**
```json
{"selfWeight":true,"gravity":[0,-9.81,0],"loads":N,
 "buckling":{"mode":"eigen","budgetDof":20000,"K":1.0},
 "precision":{"tier":"commit","targetRel":1e-9,"storage":"f64","warmStart":true,"maxTimeMs":0},
 "numThreads":1,
 "include":["members","stations","shells","attrsEcho"]}
```

| 欄位 | 規則 |
|---|---|
| `gravity` | 預設 `[0,-9.80665,0]`；可覆寫。 |
| `loads` | payload = `N × 64 B`：`x,y,z i32; flags u32(=0); fx,fy,fz f64 (N，世界軸); mx,my,mz f64 (N·m)`。v1 力矩必為 0（`LOAD_UNSUPPORTED`）。端格（s∈{0,1}）= 該節點的節點載重（**不拒絕**）；殼/monolith/地面/非結構格 → `LOAD_TARGET`；繩索 → `LOAD_UNSUPPORTED`。 |
| `buckling.mode` | `none` / `eigen`（全域線性特徵值，每島最小正 λ）/ `screen`（per-member Euler 篩，`K` 有效）。回覆 `buckling.kind` 回填同字串。`budgetDof` 超過的島 `state=not-eligible-scale`。 |
| `precision.tier` | `commit`（全精度；**判定旗標只在此軌有效**）/ `display`（引擎可回 stale/低精度解，能力 `bsi.precision.display`）。 |
| `precision.targetRel` | 目標相對殘差（預設 1e-9）；引擎回 `quality.achievedRel`。 |
| `precision.storage` | `f64` / `f32`（能力 `bsi.precision.f32`）：`stations`、`facetSurfaces`、`blocks.dc` 以 f32 變體區段輸出（區段名加 `:f32`），頻寬減半；旗標/整數不受影響。 |
| `precision.warmStart` | 允許重用上一 revision 的分解/狀態（能力 `bsi.precision.warmstart`）；`quality.warmStartUsed` 回報。 |
| `precision.maxTimeMs` | 0 = 無限；超時 → `status:"partial"` + `quality.timedOut=1`，區段只含已完成島。**非 0 需要能力 `bsi.precision.timeout`；未宣告 → `UNSUPPORTED`**（Part G 第 16 條）。|
| `numThreads` | 引擎內部執行緒上限，**整數 1..256**；**省略** = 引擎自選（`hello.threads`）。**越界或 `0` → `PROTOCOL_ERROR`，不夾擠**——「讓引擎決定」的表達方式是省略這個鍵（Part G 第 17 條）。|
| `include` | 區段選單；不列 = 不輸出且其他區段位元組不變。 |

**回覆 header（鍵序固定）**
```
{"bsi":1,"kind":"response","id":…,"method":"bsi.solve","revision":…,"status":"ok|partial",
 "diag":{"blocks":B,"nodes":n,"members":m,"facets":f,"islands":i,"singularIslands":s,
         "refusedBlocks":r,"ignoredExtensions":e,
         "warnings":[{"code":"BEARING_SKIPPED_FIXED","count":2}]},
 "buckling":{"kind":"eigen","state":"computed"},
 "unassigned":[{"why":"MECHANISM","island":2,"blocks":[[x,y,z],…]}],
 "sections":[{"name":"blocks","offset":0,"bytes":…,"count":B},…]}
```
- 未接地分量**不使整包失敗**：以 `MECHANISM` 進 `unassigned`、`singularIslands` 計數，其餘島照常。
- `warnings` 是碼與計數（引擎診斷文字不出介面）；開放列舉，消費者遇不認得的碼顯示「未知警告」並保留計數。
- `unassigned.why` 開放列舉：`MECHANISM`、`FULLY_SUPPORTED`、`RUN_TOO_SHORT`、`PLATE_NO_FACET`、`BULK_UNSUPPORTED`、`BULK_GROUND`、`NON_STRUCTURAL`、`REFUSED`。理由碼**不描述力學**。
- `buckling.state`：`computed` / `no-positive-eigenvalue` / `not-eligible` / `not-eligible-scale` / `disabled-by-request` / `solver-failed`。

**payload 區段（LE；記錄大小固定；順序 = `sections` 序）**

| 區段 | 記錄 | 大小 | 說明 |
|---|---|---|---|
| `blocks` | `dc f64; island i32; owner i32; mode u8; ownerKind u8; flags u8; reserved u8; reason u32` | 24 B × B | 對齊規範方塊序。`mode`: `0 none 1 axial 2 bending 3 shear 4 torsion 5 combined 6 shell`；`ownerKind`: `0 none 1 member 2 facet 3 unassigned`；`flags` bit0 = overloaded（引擎在 double 上定案 `dc>1`），bit1 = indicative，**bit2 = bucklingCritical**（見 Part G 第 14 條）；`reason` = `why` 列舉序（0 無） |
| `equilibrium` | `applied[3] f64; reaction[3] f64; residual f64` | 56 B | `residual = |applied+reaction| / max(|applied|,1)`；健全解 ≤ `targetRel` |
| `quality` | `achievedRel f64; iterations i32; tierHonoured u8; warmStartUsed u8; storage u8; timedOut u8` | 16 B | P9 |
| `buckling` | `island i32; state u8; kind u8; reserved u16; factor f64` | 16 B × islands | `factor` 乘在當前全部載重上；非 `computed` 為 NaN |
| `members` | `id i32; island i32; blockFirst u32; blockCount u32; stationFirst u32; stationCount u32; material i32; section i32; lengthM f64; endI[6] f64; endJ[6] f64; maxDC f64; governingS f64; mode u8; governingFibre u8; flags u8; reserved[5] u8` | 160 B × m | 斷面力，局部軸 `[N,Vy,Vz,T,My,Mz]`，**N 拉為正**；`governingFibre`: `0 none 1 crush 2 tension 3 shear 4 bending 5 torsion 6 shell_vm` |
| `memberBlocks` | `x,y,z i32` | 12 B | 世界座標，由 `blockFirst/Count` 索引 |
| `stations` | `s f64; x,y,z f64; sigma[4] f64; tau f64; naY f64; naZ f64` | 88 B（f32 變體 44 B） | 方塊面站 ∪ 解析極值站 ∪ governing 站；`sigma` 序 `TOP_Y,BOT_Y,PLUS_Z,MINUS_Z`（拉為正）；無中性軸 = NaN |
| `facets` | `id i32; island i32; blockFirst u32; blockCount u32; material i32; reserved u32; thicknessM f64; corners[4][3] f64; ex[3],ey[3],n[3] f64; N[3] f64; M[3] f64; Q[2] f64; dc f64; flags u8; governingFibre u8; reserved[6] u8` | 280 B × f | `ex×ey=n` 右手系；`N` N/m、`M` N·m/m、`Q` N/m |
| `facetSurfaces` | `top[4]{s1,s2,theta,vm}; bottom[4]{…}` f64 | 256 B（f32 128 B）| 四角上下面主應力、主軸角、vM |
| `attrsEcho` | 同 `attrs` 記錄 | 16 B | 引擎實際採用的屬性（除錯/驗收用） |

### B.6 慣例（單一來源；每個引擎各自換算恰一次）

| 項 | 契約 |
|---|---|
| 座標 | 世界軸右手系，y 向上；格 `(x,y,z)` 中心在 `(x+0.5,y+0.5,z+0.5)` m |
| 構件局部軸 | `ex` = i→j；`ez = unit(ex×ref)`、`ey = ez×ex`；`ref=+Y`，`ex∥Y` 則 `+X` |
| 構件長度 | 節點到節點（面節點/中心節點依擷取慣例），由座標算 |
| 端力 | 斷面力，拉為正；j 端與 i 端同號向 |
| 纖維應力 | `σ(y,z) = N/A + Mz·y/Iz − My·z/Iy` |
| 板 | 中面；`corners` CCW 繞 `n`；上面 = `+n` 側 |
| 重力 | 自重 = ρ·V·g，恰記一次 |
| D/C | 引擎定義，能力字串具名；跨引擎比 D/C 值屬 `convention`，比**超載旗標**才是判定對數 |

### B.7 錯誤
`{"bsi":1,"kind":"error","id":…,"method":…,"revision":…,"code":"<TOKEN>","message":"…","at":[x,y,z]?}`

`BSI_VERSION` · `EXPECTED_HELLO` · `UNKNOWN_METHOD` · `UNSUPPORTED` · `UNSUPPORTED_ATTR` · `PROTOCOL_ERROR` · `VOCAB_AFTER_WORLD` · `VOCAB_ALREADY_DECLARED` · `VOCAB_INVALID` · `NO_WORLD` · `EMPTY_WORLD` · `EXTRACT_FAILED` · `LOAD_TARGET` · `LOAD_UNSUPPORTED` · `SOLVE_FAILED` · `OUT_OF_MEMORY` · `CANCELLED` · `INTERNAL` · `BUDGET_EXCEEDED` · `ARENA_NEED_BIGGER`（附 `required` bytes）· `ARENA_CORRUPT`。`message` 是診斷不是契約；消費者依 `code` 分流。

### B.8 規範序與決定論
1. 方塊：`(x,y,z)` 升冪；重複格拒絕；`blocks` 區段對齊此序。
2. 施力：`(x,y,z)` 升冪，同格以 64 B 原始位元組字典序決勝；同格兩筆 = 兩倍。
3. 構件/板/站位/未入模：`id` 升冪；`unassigned` 依 `why` 列舉序再依方塊序。
4. 決定論：同 build、同機器、同輸入 → header 逐字、payload 逐位相同（C-9 ×3）；跨機器只宣稱 verdict parity。`display` 軌與 `warmStart` 下**不宣稱逐位**，只宣稱 `quality` 誠實。

---

## Part C · 引擎轉接 ABI（`bsi_engine.h`；任何引擎的接入面）

**引擎只實作一個 vtable（十個函式），其餘都是 host 的事。** host（共用函式庫 `bsi-host`，C++17，隨契約以雜湊鏡像）負責：框架與傳輸、schema 驗證、規範排序、詞彙預驗證與型別化、arena 管理、結果封包（引擎透過 writer 寫**型別化結構**，host 決定排布與位元組）、錯誤碼、一致性 runner 的驅動。

```c
BSI_EXPORT const bsi_engine_vtable* bsi_engine_entry(uint32_t host_abi_version);

struct bsi_engine_vtable {
  uint32_t abi_version;                                   /* = BSI_ENGINE_ABI (1) */
  const char* (*name)(void); const char* (*version)(void); const char* (*build_sha)(void);
  uint32_t    (*capabilities)(const char* const** out);   /* 能力字串陣列 */
  bsi_engine* (*open)(const bsi_host* host);              /* 每 session 一個實例 */
  void        (*close)(bsi_engine*);
  int (*vocab)(bsi_engine*, const bsi_vocab* v);          /* host 已驗證、已型別化 */
  int (*world_declare)(bsi_engine*, const bsi_block* b, uint32_t n, const bsi_attr* a, uint32_t na, bsi_writer* w);
  int (*world_edit)(bsi_engine*, const bsi_edit* e, uint32_t n, bsi_writer* w);          /* 可為 NULL */
  int (*solve)(bsi_engine*, const bsi_solve_options* o, const bsi_load* l, uint32_t n, bsi_writer* w);
  int (*cancel)(bsi_engine*);                                                             /* 可為 NULL */
};
```

**規則**
- 執行緒：host 對一個實例**序列化**呼叫；引擎內部可平行，但受 `numThreads` 約束。
- 記憶體：`bsi_block`/`bsi_load`/`bsi_attr` 是 host 擁有的**型別化陣列**（`#pragma pack` 與 wire 記錄同排布），可直接指向 arena（零複製）；引擎不得寫入。
- 結果：只經 `bsi_writer_*`（`blocks`、`member`、`facet`、`unassigned`、`warning`、`equilibrium`、`quality`、`buckling`、`error`）。writer 直接寫 arena 的 reply 區（零複製）；host 校驗計數（`blocks` 必須恰 B 筆、每筆 `ownerKind` 與 `unassigned` 一致）—— 引擎寫少寫多都是 `INTERNAL`，不會靜默。
- 錯誤：回傳 `BSI_E_*`；`bsi_writer_error(code, msg, at)` 附訊息。host 把 `bsi_writer` 的部分內容丟棄，只送錯誤框。
- 部分實作：`world_edit`/`cancel` 可 NULL；`solve` 對未宣告能力的請求選項（例如 `buckling.mode=eigen` 但無 `bsi.buckling.eigen`）回 `BSI_E_UNSUPPORTED`，host 轉 `UNSUPPORTED`。
- 契約守衛：`open` 時 host 比對 `bsi_engine_entry(BSI_ENGINE_ABI)` 回的 `abi_version`；不符拒載。

**接入一個新引擎的最短路徑**：實作 vtable → 用 `conformance/run.py --adapter engine --lib libmyengine.so` 跑語料 → 綠的家族寫進 `capabilities()` → 完成。不需要碰任何傳輸、JSON、shm、排序或封包程式碼。

---

## Part D · 傳輸與零複製 arena

### D.1 傳輸
| 傳輸 | header | payload | 用途 |
|---|---|---|---|
| **T-A** frame_v2 框（同進程 / DLL） | 框內 JSON | 框內或 arena 指標 | 引擎原生、host ↔ 引擎 |
| **T-B** stdio 門鈴 + arena | 一行 JSON 門鈴（`seq`、offset/len） | **arena** | Java ↔ sidecar（跨程序、崩潰隔離）；未來 Java FFM 直連時門鈴變函式呼叫、arena 變 `MemorySegment` |
| **T-B′** stdio 行 | 一行 JSON（含 `payloadB64`） | header 內 | fallback / 除錯 |

傳輸等價（C-2）：同請求經任一傳輸，回覆 header 逐字、payload 逐位相同。傳輸層零語意。

### D.2 arena 排布（檔案背書 mmap；version 1）
```
struct BsiArenaHeader {            /* 128 B, LE */
  u32 magic = 'BSIA'; u32 version = 1; u64 capacity;
  u64 worldOff,  worldLen;         /* 方塊記錄 ×B（持久，Java 寫，跨 revision 保留） */
  u64 attrsOff,  attrsLen;         /* 屬性記錄（持久） */
  u64 loadsOff,  loadsLen;         /* 施力記錄（每次 solve） */
  u64 reqOff,    reqLen;           /* 請求 header JSON（每次） */
  u64 replyOff,  replyLen;         /* 回覆 header JSON + 區段（引擎直接寫） */
  u64 seq; u32 flags; u32 crc32;   /* seq 單調；crc32 只在 debug flag 下計算 */
};
```
- **零複製路徑**：Java 把方塊記錄寫進 `world`（`MappedByteBuffer`/`MemorySegment`），敲門鈴；sidecar host 把 `world` 區直接當 `bsi_block*` 交給引擎；引擎經 writer 把結果**就地寫入** `reply`；Java 對 `reply` 建 view 直接讀。整條路上唯一的複製在引擎內部。
- **持久世界**：`bsi.world.edit` 就地改 `world` 區 + 在 `req` 附差分索引；引擎有 `bsi.world.edit` 能力則增量，否則 host 以全量 `world_declare` 重送（對消費者透明，`edit.downgraded="full"`）。
- **成長**：`reply` 放不下 → 引擎/host 回 `ARENA_NEED_BIGGER{required}`；Java 放大（重新 mmap、`seq` 不變）、重敲；請求冪等（世界持久）。
- **半雙工**：每個 arena 同時只有一個在飛的請求；`seq` 不匹配的回覆丟棄（`revision` 另有語意）。
- **完整性**：`seq` + 長度；debug 建置加 `crc32`；門鈴行帶 `payloadSha256` 可選（一致性 runner 用）。
- **平台**：POSIX `mmap`／Windows `CreateFileMapping`，檔案在 game dir；網路檔案系統上 mmap 不同步時**fail-closed 回 T-B′**（能力協商在 `hello.arena`）。

### D.3 效能線（RECORDED，非 gate）
一致性 runner 附 `--bench`：記錄 arena 往返（門鈴→回覆）與 T-B′ 的差、`f32` vs `f64` 區段位元組、`warmStart` 命中率。數字附箱、執行緒數、build。任何「快」的宣稱以此表為準。

---

## Part E · 版本與相容
- `bsi` 主版 = **1**。加法（新區段、新 opt-in 欄位、新能力/理由/警告碼、新 `x-` 擴充）不 bump 主版，但**必須** bump `CONTRACT_SHA256`（兩倉同步）。
- 破壞（改排布、單位、號向、既有列舉值、動詞語意、ABI vtable 既有槽位）→ 主版 bump；`bsi.hello` 拒舊客戶端；ABI 以 `abi_version` 拒舊引擎。
- vtable 只能**尾端追加**函式（host 依 `abi_version` 決定讀到哪）。
- 能力字串命名空間 `bsi.<area>.<feature>`；`x-<vendor>.*` 為廠商能力。

---

## Part F · 一致性（強制機制）

`contract/conformance/` 是兩倉共用語料：case = 詞彙 + 方塊 + 施力 + 請求 + 斷言（格式見 `conformance/README.md`）。斷言只引用本契約欄位（C-0）。

| 家族 | 內容 | 級 |
|---|---|---|
| C-0 | 語料自檢：每條斷言路徑存在於 `bsi.schema.json` | 硬 |
| C-1 | schema：header 通過 schema；`sections` 大小 = 記錄 × count；規範序 | 硬 |
| C-2 | 傳輸等價：T-A / T-B / T-B′ 回覆逐位相同 | 硬 |
| C-3 | 握手：`contractSha256` 不符 → `BSI_VERSION`；ABI 版本不符拒載 | 硬 |
| C-4 | 閉合式：懸臂自重反力 `ρAgL`、根部 `|Mz|=wL²/2`；固端點載 `|V|=P/2`、`|M|=PL/8` | 硬（rel 1e-9） |
| C-5 | 帳目：每個輸入格恰一筆 `blocks`；`ownerKind` 與 `unassigned` 一致；未接地分量 `MECHANISM` 且其餘島有解 | 硬 |
| C-6 | 接合：樑托板單島、反力增量 = 板重（rel 1e-9）；鏡像/90° 旋轉等變 | 硬 |
| C-7 | 判定：`flags.overloaded ⇔ dc>1`（double）；`buckling.state/factor` 不矛盾；`display` 軌回覆 `quality.tierHonoured` 誠實 | 硬 |
| C-8 | 號向物理：向下彎懸臂 governing 站 `sigma[TOP_Y] > 0` | 硬 |
| C-9 | DET ×3 逐位（`commit` 軌） | 硬 |
| C-10 | 挫屈：Euler `π²EI/(2L)²`、Greenhill `qL³/EI=7.837`；無 `bsi.buckling.eigen` 的實作跳過並照登 | [暫] 線待首跑釘 |
| C-11 | fail-closed：越界 axis、施力在地面格、solve 先於 declare、未知非 `x-` 鍵、標準屬性不支援 | 硬 |
| C-12 | 精度：`f32` 區段對 `f64` rel ≤ 1e-5；`targetRel` 達成或 `quality` 誠實回報未達 | 硬 |
| C-13 | 擴充：`x-` 鍵被忽略且計數；自訂斷面 `custom` 與等價 `rect` 逐位同解 | 硬 |
| B-perf | arena vs T-B′、f32 vs f64、warmStart | RECORDED |

**強制**：兩倉 CI 各跑 `conformance/run.py`；`CONTRACT_SHA256` 不符或任一硬案例紅 → CI 紅。tectonic2 的 `bsi.*`（MC68）、block-reality 的 host/sidecar/`BsiCodec`（B8）、FrameCore 對數臂，一律以此驗收。

---

## Part G · 修訂（dated 追記；上方原文一字不改）

（無）

- **2026-09-02（併入兩倉主線後，host 實作前；全部加法，`CONTRACT_SHA256` 重釘、兩倉同步）**
  1. **進程內 C ABI `bsi_capi.h`**（T-A 的函式呼叫形式）：`bsi_capi_abi_version / open / call / close / last_error`；一次 `call` = 一個 T-A frame 進、一個出；
     `NEED_BIGGER` 不消費請求。任何 host+engine 的共享庫建置都匯出這五個符號；消費者以 JNA / ctypes / FFM 綁定，零膠碼。ABI 尾端追加。
     這是消費者 D-044（jar 零可執行檔、不 spawn 子行程）在契約上的落點；T-B（sidecar 門鈴）降為 dev/CI 傳輸，C-2 仍要求三者逐位相同。
  2. **arena `reply` 區內容 = 一個 T-A frame**（12 B 前綴 + header + payload）；`sections[].offset` 相對 payload 起點；門鈴回覆 `replyLen` = frame 位元組數。
  3. **T-B′ 回覆行**在 `sections`（或錯誤框的 `message`/`at`）之後附 `"payloadBytes":N,"payloadB64":"…"`（N=0 時兩鍵仍出現，`payloadB64` 為空字串）；
     一致性 runner 在比對 C-1/C-2 前剝除這兩鍵（它們是傳輸，不是語意）。
  4. **`bsi.world.declare` body** `{"blocks":B,"attrs":A}`（`attrs` 可省略 = 0）；payload = B×40 + A×16，長度不符 → `PROTOCOL_ERROR`。
  5. **`bsi.cancel`** body `{"targetId":"…"}`；回覆 `{…,"status":"ok","targetId":"…"}`。host 對同一 session 序列化請求，`cancel` 只能命中尚未派發的請求（引擎 `cancel` 槽可為 NULL）。
  6. **`hello.response.transports`** token：`"frame"`（T-A / `bsi_capi`）、`"arena"`（T-B）、`"stdio-b64"`（T-B′）。
  7. **`Zy`/`Zz` = 塑性斷面模數**（rect：`b·d²/4`、`d·b²/4`）；彈性模數由 `I/c` 導出，不另設欄。`C13` 的 `custom` 數字據此更正、`J` 釘 `7.324166666666667e-4`，
     custom-vs-rect `bitwise` 線降 **provisional** 至首跑釘死。
  8. **`diag.warnings`** 依 `code` 字典序升冪、同碼合併計數（host 排）。
  9. **`steps` 模式不隱式 declare**：需要世界的步驟前必須有明確的 `bsi.world.declare` 步驟（`C11` 已補 `declare-world`）。`C4` 的 `requires` 去掉 stations（它不斷言站位），`C8` 自帶。
  10. `bsi_engine.h` 的 `BSI_ENGINE_BUILD` 會把 `bsi_writer_*`/`bsi_host_*` 在引擎 DLL 內標為 `dllexport`（Windows）；Linux 無害（巨集為空）。只登記；修法（獨立 `BSI_HOST_EXPORT`）留下次主版前的加法。
  11. **共用 host（`contract/host/`）與語料 runner 隨契約鏡像**：host 的任何修正 = 契約雜湊 bump（兩倉同步）。這是設計成本，照登。
  12. **`attrs` 記錄更正**：原文 B.4「`blockIndex u32; key u32; type u8; reserved[3]; value[8]`（16 B）」欄位加總是 20 B，
      與宣告的 16 B 矛盾（`bsi_engine.h` 的 `sizeof` 斷言在 host 首次建置時抓到）。更正為 `blockIndex u32; key u16; type u8; reserved u8; value[8]` = 16 B
      （`attrKeys` 索引 65535 個足夠）。`bsi_engine.h`、schema `x-records.attr`/`attrsEcho` 同步；沒有任何實作曾用過舊排布。
  13. **`bsi_engine.h` 的 `BSI_EXPORT` 補 ELF 分支**（2026-09-03）：出貨形狀是「隱藏一切、只留契約符號」的共享庫，
      但 `BSI_EXPORT` 在非 Windows 是空巨集 ⇒ `-fvisibility=hidden` 下 `bsi_engine_entry` 自己也被藏起來，
      而 version script **無法解除編譯期就標為 hidden 的符號**（首次建 `libbsi_tectonic.so` 時實測：`nm -D` 只剩 `bsi_capi_*`，host 回「not exported」）。
      改為與 `bsi_capi.h` 同一條規則：`__GNUC__` 且 `BSI_ENGINE_BUILD` 時 `__attribute__((visibility("default")))`。
      **純加法**：不定義 `BSI_ENGINE_BUILD` 的消費者、以及 Windows 分支，逐位不變。第 10 條記的 Windows `dllexport` 怪癖不受此影響、仍待下次主版前處理。


- **2026-09-03b（契約加法批次 #1；判準 tectonic2 `docs/specs/BSI_ADD1.md`，先凍於 `155b422`；operator 裁決「1 做」）**
  14. **`blocks.flags` bit2 = `bucklingCritical`**（加法）：該格**所屬島**的挫屈記錄 `state == computed` 且 `factor` 有限且 `< 1.0`。
      與 bit0 一樣**由引擎在 double 上定案一次**，**只在 `commit` 軌有效**。沒有挫屈能力、或該島無 `buckling` 記錄 → 必為 0。
      host 在 finalize **雙向**查它（設了卻沒有記錄、記錄非 `computed`/`factor ≥ 1`、或該島確實臨界卻沒設）→ `INTERNAL`。
      **只適用於擁有元素的格**（`ownerKind ∈ {member, facet}`）；`none`/`unassigned` 的格（地面記錄、未入模格）**必為 0** ——
      沒有元素就沒有穩定性判定，讓位元通過等於在一格從未被分析的方塊上塗顏色。此條在實作期收緊，見 `BSI_ADD1.md` §10 修訂 1。
      **為什麼需要它**：`buckling` 區段是每島一筆，消費者要的是每格一個是非題以便上色；在此之前 BSI 上**沒有**任何每格挫屈判定，
      消費者只能自己拿 `factor < 1` 去比，那違反 P2（判定下行、消費者永不重算）。`bsi.schema.json` `x-records.blocks.x-flags`、`bsi_engine.h` 註解同步。
  15. **`warningCode` 追加 `UNCLASSIFIED`**（加法，開放列舉尾端）：引擎產生了一個它對不到本列舉的警告。
      **這是出口不是預設**——具名碼存在時必須用具名碼。`diag.warnings` 依 code 字典序合併不變。
  16. **`precision.maxTimeMs` 上能力閘**（**行為變更**）：非 0 需要新能力 `bsi.precision.timeout`，未宣告 → `UNSUPPORTED`。
      **量到的**（`BSI_ADD1` §2，改動前對 stub）：`maxTimeMs:1` 回 `status:"ok"`、`quality.timedOut = 0` —— 呼叫者的期限被靜默丟掉。
      一個被忽略的選項，是回覆自己的 `quality` 區段在說謊。
  17. **`numThreads` 界線 `1..256`**（**行為變更**）：越界或 `0` → `PROTOCOL_ERROR`，**不夾擠**。
      量到的：`0` 與 `99999` 改動前皆被接受（schema 只有 `minimum: 0`、無上界）。tectonic2 `MC62_GUARDS.md` §1.6 早在 2026-09-02 就凍了這條線，**契約當時漏寫**。
  18. **`bsi_capi_open` 選項 schema**（**行為變更 + 加法**）：`x-capi.openOptions` = `log`(0..3)、`numThreads`(1..256，**本 session 預設**，
      每次 `bsi.solve` 的 `body.numThreads` 覆寫之)、`probe`(bool)、`assumeCaps`(string[])、`x-<vendor>`(忽略)。
      **非 `x-` 的未知鍵、型別錯、越界 → `open` 回 `NULL`**，理由由 `bsi_capi_last_error(NULL)` 取得（open 失敗時沒有 handle 可掛，故以 thread-local 承載）。
      量到的：`{"totallyBogusKey":123}` 改動前開得起來。副作用是好的：消費者 block-reality 一直在送的 `numThreads` **從無效變成有效**。
  19. **`members.section` 語意**：回**解析後的斷面 id**；`-1` 只在材料沒有 `defaultSection` 時出現。
      `C4-cantilever-selfweight` 加一條 `section >= 0`（該世界宣告 `sect: null`，回 `-1` 的話消費者無從對映）。
  20. **語料三補**：`C7-overloaded-flag`（C-7 第一次有 fixture **站在線上**；在它之前語料裡沒有任何格 `dc > 1`，
      所以「永不設旗標」與正確行為不可區分）、`C12-f32-display`（C-12 家族**本來一個檔都沒有** ⇒ `bsi.precision.f32` 依 MC68-03 永遠不可宣告）、
      `C11-fail-closed` 加三個 step（第 16、17 條各自的反向腿）。`conformance/README.md` 的家族表更正——上一版列了**七個不存在的檔名**。
  21. **主版不 bump**，理由照登：Part E 對「破壞」的定義是「改排布、單位、號向、既有列舉值、動詞語意、ABI vtable 既有槽位」。
      第 16–18 條把**未定義行為**收緊成 fail-closed，不在其中；三者都是 P6 本來就要求的行為。
      **但它們確實會讓今天成功的請求開始失敗**，所以記在這裡而不是包裝成純加法。對兩個現有實作的實測影響為零
      （tectonic 轉接器早就自己拒 `maxTimeMs`；沒有 fixture 送越界 `numThreads`；block-reality 送的兩個 open 鍵都在新 schema 內且 `numThreads ≥ 1`）。
  22. **序言那句「只改一邊，另一邊的一致性 gate 會紅」在 CI 層不成立**（原文不改，此處具名）：
      強制有三層——本倉自洽（`check_contract.py`）、跨倉比對（CI）、執行期握手（`bsi.hello`）。
      **只有第三層是無條件的。** 跨倉那層取決於 CI 有沒有真的去抓**對面的主線**：比對一個釘死的舊 commit
      會讓對面主線的單邊改動完全看不見。逐層的抓得到/抓不到見 `contract/README.md`；兩倉的實況與修法見
      `docs/ALIGNMENT_LEDGER.md` A12。**這條不改任何欄位**，只是不讓序言被讀成比實際更強的保證。
