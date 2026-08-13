# 引擎邊界

Minecraft 側與力學引擎之間的介面契約。目標：**換引擎不動 Minecraft 側**（D-002、D-006）。

## 分工

```
┌─ Minecraft 側（本倉庫，Java）────────────────────┐
│  方塊放置與拆除                                   │
│  材料語意（什麼是鋼骨、什麼是澆置後的板）           │
│  工法狀態機（綁筋 → 組模 → 澆置 → 養護 → 拆模）    │
│  支承宣告（什麼接地）                              │
│  應力視覺化、HUD、崩塌演出                         │
└──────────────────┬───────────────────────────────┘
                   │  方塊 + 材料 + delta   ↓
                   │  D/C + 內力 + 事件     ↑
┌──────────────────┴───────────────────────────────┐
│  力學引擎（外部 process / native，C++）            │
│  構件抽取（共線 run → member）                     │
│  斷面指派、節點管理                                │
│  模型組裝、分解、求解、內力回收                     │
│  機構偵測、失效判定                                │
└──────────────────────────────────────────────────┘
```

**Java 不知道什麼是節點、構件、斷面、K 矩陣。** 這是刻意的。

## 上行（Minecraft → 引擎）

只有三類訊息：

### `world.declare`
一次性宣告一個分析域。內容是方塊清單，每個方塊帶：

| 欄位 | 說明 |
|---|---|
| 座標 | 整數格點 |
| 材料 id | 詞彙表中的一項（見下） |
| 結構角色 | `MEMBER` / `PANEL` / `SUPPORT` / `NON_STRUCTURAL` |
| 斷面 id | 可選；未指定時由材料的預設斷面決定（D-004） |
| 接頭狀態 | `RIGID` / `PINNED`；鋼骨焊接前後不同 |
| 填充率 | 來自 chisel 的體素造型，影響斷面性質 |

### `world.edit`
增量。放/拆一個方塊、改材料、改接頭狀態、改斷面。**送 delta 不送全量**——這對齊引擎端 dirty-assembly 的語意。

### `solve.request`
要一次求解。帶軌別（`display` / `commit`）與載重工況。

## 下行（引擎 → Minecraft）

| 訊息 | 內容 |
|---|---|
| `result.utilization` | per-block 的 D/C + **主導模式**（`AXIAL` / `BENDING` / `SHEAR` / `TORSION` / `COMBINED`） |
| `result.forces` | per-member 的 `N, Vy, Vz, T, My, Mz`，沿桿取樣點 |
| `result.shell` | per-panel 的上下層應力（`σxx, σyy, τxy, σ1, σ2, vM, θ`） |
| `event.failure` | 失效事件：位置、型別、觸發判準值 |
| `event.mechanism` | 機構偵測：哪個子結構失去穩定 |
| `diag.status` | 求解狀態、殘差、軌別、是否 stale |

**注意 `result.utilization` 的主導模式欄位。** 前身的 wire 是每方塊一個純量，只能上色。有了主導模式，玩家能知道「這根柱子不是被壓垮的，是被彎垮的」——這是新增能力，舊設計裡不會有，重寫時要主動放進去。

## 唯一必然的耦合：材料語意詞彙表

wire 上必須有一份雙方同意的材料/角色詞彙表。這跑不掉。

但它是**產品概念不是求解器概念**——「鋼骨」「養護中的混凝土」「模板」在任何力學引擎下都是同樣的東西，換引擎時不變。詞彙表本身版本化，加項目不破相容。

**不可以放上 wire 的**：`Section` 的具體參數（A、Iy、Iz、J、Wy、Wz）、節點編號、DOF 索引、元素型別。那些是引擎的內部詞彙，一旦洩漏就等於把 frame 抽象寫死在 Java 側。

## 傳輸

實作端先用 `frame_capi_v2`（D-002）。它的形狀已經適合：

- **12 byte 固定 header**：`'F' 'C'` + FLAGS + LE u32 header 長度 + LE u32 payload 長度
- header 是 JSON（加欄位不破 wire），payload 是 raw little-endian double
- 不透明 handle、無全域狀態、**無 C++ exception 跨界**
- `frame_v2_abi_version()` 是嚴格單調整數，**兩個 minor 版本的相容 SLA**
- `transport.async`：send 非阻塞排隊 + 背景 worker

最後一項天然適配 tick 迴圈：**送出 → 不阻塞 → 下一個 tick 收**。不要在 tick thread 上等。

JVM 側用 Panama FFM 而非 JNI——`MemorySegment` 可以直接映射 12-byte header + double payload，不需要額外的序列化層。

## 已知限制

**`frame_capi_v2` 的 dispatcher 沒有暴露 LiveSession**，只有 `analysis.reanalysis_solve`（同拓撲 `ReSolveSession`）。也就是加節點無法增量，要全量重分解。

在 D-008 之下這不構成阻礙：建築尺度的全量重分解約 100 ms，async 跑掉即可。放方塊 → 全量；拆模、構件失效、tension-only 翻轉 → 開關既有構件 → 精確 Woodbury。

## 執行緒

`OpenBLAS` 的執行緒數是 **process-global**。多個 context 同時跑 supernodal session 會互相競爭。

Java 側必須**序列化引擎呼叫**，或全程走 LDLT lane。這不是效能建議，是正確性要求——競爭會破壞決定論，而決定論是多人同步的前提。

## 打包

原生二進位是這條路上真正的工作量，不是寫 shim：

- OpenBLAS + METIS（+ 可選 cuDSS）× 6 個平台三元組（`linux-x64`、`win-x64`、`mac-x64`、`linux-arm64`、`win-arm64`、`mac-arm64`）
- 授權、體積、版本相容

前身 `PFSF-CORE` 的 `NativeLibLoader` 有一套成熟且 fail-closed 的做法值得照抄形狀（不抄程式碼）：從 JAR 資源 `META-INF/native/<triple>/` 抽取到帶 SHA-256 digest 的暫存目錄，**先寫 per-PID staging 檔再 atomic move**，避免併發 JVM 載到半寫入的 binary。ABI 不符時 fail-closed 而非降級。
