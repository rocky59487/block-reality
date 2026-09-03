# contract/host — bsi-host（共用 host；兩倉逐位鏡像，隨契約雜湊）

> 任何引擎只實作 `../bsi_engine.h` 的十個函式；**其餘全部在這裡**：傳輸與框架、schema 驗證、規範序、
> 詞彙型別化、零複製 arena、結果封包、錯誤碼、一致性 runner 的驅動。本目錄每個檔都進 `CONTRACT_SHA256`，
> **建置一律在 `contract/` 之外**（`-B build/host`）。

## 建置 / 測試

```
cmake -S contract/host -B build/host -DBSI_HOST_BUILD_TESTS=ON
cmake --build build/host -j
ctest --test-dir build/host --output-on-failure        # host_tests: HOST-SUITE ALL PASS (failures=0)
```

configure 期先跑 `../check_contract.py`：雜湊不符 **拒建**。開發時可 `-DBSI_HOST_DEV_UNPINNED=ON`（只印警告；**CI 禁用**）。
`bsi_schema_embed.cmake` 把 `bsi.schema.json` 與 `CONTRACT_SHA256` 嵌進二進位；`bsi.hello` 回的 `contractSha256` 就是它。

## 產物

| target | 用途 |
|---|---|
| `bsi_host`（STATIC） | 函式庫：`bsi_host.h` 的 `Session`、`Engine` 載入、三種傳輸 |
| `bsi-hostd` | 通用 host 行程（dev/CI 臂）：`--engine <lib\|static> --transport stdio-b64\|arena\|frame [--arena f] [--assume-caps a,b]` |
| `bsi_capi_obj` | `../bsi_capi.h` 的實作（OBJECT）；引擎作者把它與自己的 vtable 物件連成一顆 `.so/.dll`，即得到消費者（JNA/ctypes/FFM）要的五個 C 函式 |
| `bsi_stub_engine`、`bsi_stub_capi`、`bsi-hostd-stub`、`host_tests`（`BSI_HOST_BUILD_TESTS=ON`） | 零力學測試替身與 host 自己的 gate |

## 接入一個新引擎的最短路徑

1. 實作 `bsi_engine_vtable`（十個函式）並匯出 `bsi_engine_entry(uint32_t hostAbi)`（`hostAbi != 1` 回 NULL）。
2. 出貨：`add_library(bsi_myengine SHARED my_adapter.cpp $<TARGET_OBJECTS:bsi_capi_obj>)` + `target_link_libraries(... bsi_host)`；
   只匯出 `bsi_capi_*` 與 `bsi_engine_entry`（version script / visibility hidden）。
3. `python3 contract/conformance/run.py --adapter capi --lib libbsi_myengine.so`；綠的家族才寫進 `capabilities()`。

## host 規則（契約沒明說、由 host 定案的推導；每條恰做一次）

- `isotropic` 給 `nu` 不給 `G` → `G = E / (2(1+nu))`；兩者都給則照用。
- 材料 / 斷面 / attrKeys 的 id = 宣告順序（0 起）。`defaultSection` 名 → id，找不到 → `VOCAB_INVALID`。
- 斷面 `p` 元數：rect 2、circle 1、h 4、box 3、pipe 2、rcrect 4（As、lever 可 0）；custom 十個純量必填、`principalAngle` 預設 0、不得帶 `p`。
- `ignoredExtensions` = 詞彙裡的 `x-` 鍵（session 壽命）+ 本請求 header/body 的 `x-` 鍵。
- 能力閘在引擎之前：`buckling.mode≠none`、`tier=display`、`storage=f32`、`warmStart`、`include` 的 stations/shells/attrsEcho、`attrs`、`world.edit`
  對應能力未宣告 → `UNSUPPORTED`。host 只採納 schema `x-capabilities` 內的字串（`x-` 廠商能力除外）。
- `--assume-caps` / `bsi_capi_open("{\"probe\":true,\"assumeCaps\":[...]}")` 關閉能力閘，hello 回覆多一個 `x-host:{probe:true}`。**收割用，永不是能力宣稱。**
- 未知動詞 → `UNKNOWN_METHOD`（先於 `EXPECTED_HELLO`）；hello 雜湊不符 → `BSI_VERSION` 且 session 毒化。
- `bsi.world.declare` body 缺 `blocks` 時整個 payload 視為方塊記錄（Part G 第 4 條的預設）。
- 回覆 header 鍵序 = schema 的 `properties` 順序；`diag.warnings` 依 code 升冪合併；`unassigned` 依 why 列舉序、島、方塊序。
- `buckling.state`（header 摘要）：`mode=none` → `disabled-by-request`；無島記錄 → `not-eligible`；任一島 `computed` → `computed`；否則第一島的狀態。
- `storage=f32` 只轉 `stations`/`facetSurfaces`（schema 有 `x-f32` 者）；`blocks` 維持 24 B f64（schema 未定義其 f32 變體）。
- `finalize` 的 INTERNAL 條件：`blocks` 未寫 / 寫兩次 / 筆數 ≠ B；`ownerKind==3` 與 `unassigned` 列表不一致或 `reason` 不符；已擁有的格帶 `reason`；
  owner id 不存在；member/facet id 非嚴格遞增；站位 `s` 非遞增；缺 `equilibrium`/`quality`/`diag`；`flags.overloaded ≠ (dc>1.0)`。
- arena header：具名欄位 112 B + `reserved[16]` = 契約的 128 B；`reply` 區放一個 T-A frame。`world.edit` 走 arena 本版 `UNSUPPORTED`。
- `bsi.cancel`：host 序列化請求，永遠沒有在飛的請求；回 `{status:"ok",targetId}` 並呼叫引擎的 `cancel` 槽（可 NULL）。

## 誠實邊界

- Windows：`bsi_arena` 的 mmap 未實作（`open()` 回錯）；T-B′ 與 T-A 可建。`bsi_engine.h` 的 `BSI_ENGINE_BUILD` dllexport 怪癖見 BSI.md Part G 第 10 條。
- host 的任何修正 = 契約雜湊 bump + 兩倉鏡像（設計成本）。
