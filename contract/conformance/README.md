# BSI v1 一致性語料（兩倉共用；斷言只引用 `BSI.md` 的欄位）

## 執行

```
python3 contract/conformance/run.py --selfcheck                                   # C-0 + 契約雜湊 + 每個 case 能展開
python3 contract/conformance/run.py --list
python3 contract/conformance/run.py --adapter engine  --lib libengine.so --hostd build/host/bsi-hostd   # 經 bsi-hostd：stdio-b64 / frame / arena 三傳輸各跑一次（C-2）
python3 contract/conformance/run.py --adapter capi    --lib libbsi_engine.so                             # 進程內 ctypes 直呼 bsi_capi.h（消費者 JNA 走的路）
python3 contract/conformance/run.py --adapter sidecar --exe <host-process ...>                         # 任何吃 bsi-hostd 旗標的 host 行程
python3 contract/conformance/run.py --adapter frame_v2 --lib tectonic_capi.so                          # NOT RUN（exit 3）：引擎的 T-A bsi.* 動詞尚不存在
```

旗標：`--transports a,b`、`--repeat N`（DET，預設 3）、`--families C-4,C-5`、`--case NAME`、`--record report.json`、
`--stub`（允許 `x-bsi.stub` 測試替身；力學家族對它一律 SKIP 並印 `STUB (no mechanics)`）、
`--assume-caps a,b`（**收割模式**：當作能力已宣告，結果標 `ASSUMED`，**exit 4，永不綠**）、
`--expected-red account.json`（帳放在 `contract/` 之外；`{"cases":[{"case":…,"waits_on":…}]}`：帳上紅不算失敗，**帳上卻綠 = 過期 = 紅**）、
`--allow-all-skip`。

runner 對**每個回覆**做 C-1（schema、鍵序 = schema 屬性序、`sections` 連續且固定序、`bytes == count × 記錄大小`、
`blocks.count == B`、id 遞增、`unassigned`/`warnings` 規範序）與隱含 C-7（`flags.overloaded ⇔ dc > 1.0`）；
記錄解碼由 schema `x-records` 驅動，不硬編 offset。`requires` 未宣告的家族 **SKIP（列印，不算綠）**。
每個 world / variant 各開一個新 session；`steps` 模式在同一 session 逐步執行，**不隱式 declare**，`BSI_VERSION` 之後的步驟另起行程。
DET：未指定時注入 `numThreads:1`；header 逐字、payload 逐位。

exit：0 全綠且 ≥1 案執行；1 硬紅或帳過期；2 契約/用法；3 adapter not-run；4 收割；5 全部 SKIP。

## case 格式（`cases/*.json`）

```json
{"case":"C4-cantilever-selfweight","family":"C-4","grade":"hard",
 "requires":["bsi.core","bsi.readback.members"],
 "vocab":{…同 bsi.vocab.declare body…},
 "blocks":[[x,y,z,"mat","sect?",axis,joint,axisRot,fill,strength], …],
 "loads":[[x,y,z,fx,fy,fz], …],
 "solve":{…同 bsi.solve body…},
 "assert":[
   {"path":"equilibrium.reaction[1]","eq":24642.72,"rel":1e-9},
   {"path":"members[0].endI[5]","abs_eq":49285.44,"rel":1e-9},
   {"path":"diag.islands","eq":1},
   {"path":"unassigned","has":{"why":"MECHANISM","count":5}},
   {"path":"stations[governing].sigma[0]","gt":0},
   {"error":"LOAD_TARGET"}
 ]}
```

- `path` 用契約欄位名；`members[k]` 依 `id` 升冪；`stations[governing]` = governing 站。
- `eq/abs_eq`（`abs_eq` 比絕對值，號向由 C-8 另驗）、`rel`/`abs` 容差、`gt/lt/le`、`count`、`has`、`error`（預期錯誤碼）、`bitwise:3`（DET）。
- `grade`: `hard`（線凍死）/ `provisional`（[暫]：結構凍死、數字線首跑後 dated 釘）；單條斷言也可帶 `grade`。`rel` 為字串（`PROVISIONAL(...)`）時 runner 以 1e-2 評估並標 provisional。
- `blocks` 可為字串 DSL：`column(x=0,z=0,y=0..19,steel,axis=1) + ground_rigid at (0,-1,0)`。
- `worlds{A:[...],B:{extends,add},B_mirror:{transform:mirror_x,of}}`、`variants[{name,sect|loads|solve,assert}]`、`steps[{name,do,blocks|world,solve,loads,expectError,expectAt,expect,assert}]`、`extends`/`world`（引用另一 case 的 vocab/blocks/derive）。
- `transform`：`mirror_x` = `x→-1-x`、`fx→-fx`；`rot90_y` = `(x,z)→(-1-z,x)`、axis `0↔2`、`(fx,fz)→(-fz,fx)`。

## 家族與 case 清單

| 家族 | case | 級 |
|---|---|---|
| C-1 | `C1-schema-every-response`（隱含於 runner） | 硬 |
| C-2 | `C2-transport-equivalence`（runner 對每個 case 走全部可用傳輸） | 硬 |
| C-3 | `C3-hello-contract-mismatch` | 硬 |
| C-4 | `C4-cantilever-selfweight`、`C4-fixedfixed-pointload` | 硬 |
| C-5 | `C5-floating-beam-mechanism` | 硬 |
| C-6 | `C6-slab-on-beam` | 硬 |
| C-7 | `C7-overloaded-flag`（隱含：每個 case 檢查 `flags.overloaded ⇔ dc>1`） | 硬 |
| C-8 | `C8-sign-cantilever-top-tension` | 硬 |
| C-9 | `--repeat 3` 對每個 case | 硬 |
| C-10 | `C10-euler-tip`、`C10-greenhill`（`requires: bsi.buckling.eigen`） | 暫 |
| C-11 | `C11-axis-out-of-range`、`C11-load-on-ground`、`C11-solve-before-world` | 硬 |
| C-12 | `C12-f32-display`（`requires: bsi.precision.f32`） | 硬 |
| C-13 | `C13-x-extension-ignored`、`C13-custom-section-equals-rect` | 硬 |

閉合式的數字全部由 case 內的材料/幾何**算式**推得（runner 重算，不硬編）：
`w = ρ·A·g`、`L` = 節點到節點（構件由 5 格接地一端 → 4 m）。

## 誠實邊界

- 語料以**引擎中立量**斷言：反力、端力量值、島數、理由碼、旗標、站位號向。D/C 的**數值**不比（各引擎定義不同，能力字串具名），只比旗標。
- 板網格慣例（facet 數）不比；比板重進反力（C-6）。
- 挫屈家族在無能力的實作上跳過並照登，不得計綠。
