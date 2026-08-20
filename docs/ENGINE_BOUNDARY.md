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

## `worldRevision` — 貫穿全部訊息的版本 token

**每一則上行與下行訊息都帶 `worldRevision`。** 它的用途是讓過期的分析結果無法造成傷害（Issue #2–#8）。

| 規則 | |
|---|---|
| 任何 topology 變更（放/拆方塊、切割、重新安裝）**產生新 revision** | |
| 過期 revision 的**失效事件被拒絕** | 不是延後處理，是丟棄 |
| 過期 revision 的**應力圖不得顯示成最新結果** | 必須明示「分析中／過期」 |
| 掉落的 member **攜帶產生它的 revision** | 避免重複生成或舊事件覆蓋 |
| 損傷紀錄**記錄造成它的 revision** | 重複或過期事件不重複累加同一筆損傷 |
| 重新安裝／重新連接**必須建立新 revision 並重新分析成功**才恢復承載 | 視覺接觸不等於承載 |

這也精確化了 D-007 的兩軌：顯示軌可以 stale，但**「舊」與「過期」是不同的**——revision 變了就是過期，不是「舊但可用」。

> **兩軌的實作現況（2026-08-20，D-023）**：不是兩次求解。承諾軌＝server 在 double
> 上定案的判定（D/C、超載、挫屈臨界）以 boolean 隨封包下發;顯示軌＝封包裡的
> float32 場,client 對齊 server 裁決、永不重算分類;revision 前進即廣播 pending,
> HUD 對過期畫面標 stale。顯示軌精度預算 rel ≤ 1e-5 有可執行 gate
> （`DisplayTrackPrecisionTest`）。細節與否證條件見 D-023。

---

## 目標協定（未實作——本節與下行目錄是方向，不是現況）

> **實作狀態（2026-08-20，INV-8 修正）**：以下 `world.declare` / `world.edit` /
> `solve.request` 與下行的 `result.*` / `event.*` / `diag.*` **全部不存在**。
> 現行協定只有四個 op：`hello`、`solve`、`solve.shm`、`bye`（見「傳輸（實作現況）」節），
> 每次求解送**全量快照**，沒有 delta、沒有事件、沒有損傷。這個目錄過去以現在式
> 書寫，讀起來像已實作——那是文件錯誤。對照表：
>
> | 目標訊息 | 現況 |
> |---|---|
> | `world.declare` / `world.edit`（delta） | 併入 `solve` 的全量 blocks 陣列 |
> | `solve.request`（軌別、載重工況） | `solve` / `solve.shm`（無軌別欄位；載重只有 point loads） |
> | `result.utilization` / `result.forces` / `result.shell` | 併入 `solve` 回覆的 members/shells |
> | `result.damage` / `result.material` | 不存在（損傷系統未開始） |
> | `event.failure` / `event.mechanism` | 不存在；機構以 `singular` + 診斷欄位回報 |
> | `diag.status` | `/br status` 由 Java 側狀態機拼裝，非引擎訊息 |

## 上行（Minecraft → 引擎）——目標形狀

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

## 下行（引擎 → Minecraft）——目標形狀

| 訊息 | 內容 |
|---|---|
| `result.utilization` | per-block 的 D/C + **主導模式**（`AXIAL` / `BENDING` / `SHEAR` / `TORSION` / `COMBINED`） |
| `result.forces` | per-member 的 `N, Vy, Vz, T, My, Mz`，沿桿取樣點 |
| `result.shell` | per-panel 的上下層應力（`σxx, σyy, τxy, σ1, σ2, vM, θ`） |
| `result.damage` | per-member 的 `DamageRecord[]` 影響——受損位置、模式、是否仍可承載 |
| `result.material` | per-member 的材料等級、斷面 token、混凝土批次／齡期、回收來源 |
| `event.failure` | 失效事件（見下） |
| `event.mechanism` | 機構偵測：哪個子結構失去穩定 |
| `diag.status` | 求解狀態、殘差、軌別、**revision 是否過期** |

### 失效事件的形狀

只有兩種型別，兩者都攜帶 `worldRevision`、位置、**法向／方向**、嚴重度、受影響構件：

| 型別 | 下游 |
|---|---|
| `FRACTURE` | 裂口張開 → 分離 → 剛體接管（持久物件，有傷害、可破壞、可重裝） |
| `CRUSHING` | 交給物理 Mod 播放碎裂動畫（**純視覺，不可撿取**） |

**單向交接**：一旦區域交給剛體／碎裂系統，就不再回到同一次 FEA 模型。

三條硬規則（Issue #2）：

1. **結構求解器是唯一決定「是否失效、何處失效、失效模式」的來源**
2. **剛體系統只接管已斷開的構件，不反過來決定結構強度**
3. **物理 Mod 只負責壓碎後的視覺動畫**

→ 未來更換物理 Mod 不必改寫結構求解器。

### 損傷的分層

- **Java 側只保存事實與呈現**：`DamageRecord[]`（模式、member 上的位置、方向、嚴重度、來源事件、時間）
- **引擎負責把損傷轉成剛度、強度、有效斷面或失效準則的變化**

遊戲側**不發明力學折減公式**。損傷模式：`FRACTURE` / `PLASTIC_BEND` / `BUCKLING` / `IMPACT` / `CRUSHING`。

⚠️ 明確拒絕單一「耐久度百分比」——相同百分比無法區分裂紋、屈曲與塑性彎曲的不同力學後果。

**注意 `result.utilization` 的主導模式欄位。** 前身的 wire 是每方塊一個純量，只能上色。有了主導模式，玩家能知道「這根柱子不是被壓垮的，是被彎垮的」——這是新增能力，舊設計裡不會有，重寫時要主動放進去。

## 唯一必然的耦合：材料語意詞彙表

wire 上必須有一份雙方同意的材料/角色詞彙表。這跑不掉。

但它是**產品概念不是求解器概念**——「鋼骨」「養護中的混凝土」「模板」在任何力學引擎下都是同樣的東西，換引擎時不變。詞彙表本身版本化，加項目不破相容。

**不可以放上 wire 的**（D-021 修訂）：**元素身分詞彙**——節點編號、DOF 索引、
元素型別、剛度矩陣內部。那些換一個引擎就沒有對應物，一旦洩漏就把 frame 抽象寫死
在 Java 側。

**明確允許**（D-021）：斷面幾何純量（A、Iy、Iz、cy、cz、J）與沿桿內力站值，作為
**可求值的顯示場**隨結果下發——client 用它們在任意剖面位置重建纖維應力畫圖。
這些是樑理論的共通語言，任何以樑為模型的引擎都有同一組數。本節第一版把它們
列進禁令，而實作一直在傳（INV-1）；裁決與否證條件見 D-021，這裡不再重複。

## 傳輸（實作現況，D-013 + D-019）

引擎跑在**獨立程序** `br-sidecar` 裡（D-013：C++ 的錯誤只賠一次分析，不賠伺服器與
存檔）。兩條 wire，一個語意：

- **控制通道**：stdio 上的 JSON-lines。握手（`hello`，宣告材料/斷面/板目錄與 `shm`
  能力）、關閉（`bye` 或 EOF）、錯誤,以及完整的 `solve`——JSON `solve` 是 wire
  契約、fallback 與除錯面,double 以 17 位有效數字序列化（無損下限）。
- **資料通道**：檔案背書的共用記憶體（D-019）。JVM 建立並映射 scratch 檔,
  `shm.open` 讓 sidecar 映射同一個檔;之後 `solve.shm` 的請求與回覆以 raw
  little-endian IEEE-754 直接躺在映射區裡,stdio 行縮成 ~60 byte 門鈴。一個從未
  文字化的 double 不可能在傳輸中被改變——這條性質由 verify.py 的 T 系列 gate
  押著：兩傳輸的回覆**逐位元相同**。

半雙工、一問一答;JVM 側每個 dimension 一個 client、一條分析執行緒,**不在 tick
thread 上等**（送出 → 下一個 tick 收,見 `AnalysisExecutor`）。這同時滿足了
OpenBLAS 執行緒數 process-global 的序列化要求。

`frame_capi_v2`（D-002 原案的 in-process C ABI）仍是日後換裝方向之一;屆時 wire
概念不變——門鈴變函式呼叫,映射區變 `MemorySegment`。

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
