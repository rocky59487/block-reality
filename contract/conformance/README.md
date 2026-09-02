# BSI v1 一致性語料（兩倉共用；斷言只引用 `BSI.md` 的欄位）

## 執行

```
python3 contract/conformance/run.py --adapter engine  --lib  <libengine.so|dll>     # 直接載入 bsi_engine.h 的 vtable
python3 contract/conformance/run.py --adapter sidecar --exe  <br-sidecar>            # 經 T-B（arena）與 T-B′
python3 contract/conformance/run.py --adapter frame_v2 --lib <tectonic_capi.dll>    # 經 T-A
python3 contract/conformance/run.py --list                                            # 列 case 與家族
```

`run.py`（B8 交付）做四件事：驗 `CONTRACT_SHA256`、驗 header 對 `bsi.schema.json`、跑每個 case 的斷言、
以 `--repeat 3` 做 DET ×3 逐位。任一 [硬] 案例紅 → exit 1。能力字串未宣告的家族**跳過並照登**（不算綠）。

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
- `grade`: `hard`（線凍死）/ `provisional`（[暫]：結構凍死、數字線首跑後 dated 釘）。

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
