# 檔案庫發現

掃描 Drive `V1` / `V2` / `V3` / `教學` / `backup` 的結果。補 `PORTING_INVENTORY.md` 之外的材料。

---

## 0. 版本演進：三份手冊其實是一份

完整標題 diff 的結果乾淨得出乎意料：

| 差異 | 內容 |
|---|---|
| **V1 → V2** | **只加了一節** `## 1.2.5 快照層架構與純計算 Contract`。其餘 291 個標題完全相同 |
| **V2 → V3** | **標題數完全相同（302 vs 302）**，唯一差異是文件標題 `v2.0 → v3.0`。內文差異是 5 個 Java 檔的並行安全修補 |

→ V1、V2 皆 `SUPERSEDED`，V3 與已消化的 `v3fix` 同源。**手冊只需讀一份。**

### 關鍵字掃描證實 member/section 是真空白

對 V3 全文（9,773 行）：

| 關鍵字 | 命中 |
|---|---|
| 斷面 / 截面 | **0** |
| 慣性矩 | **0** |
| 節點剛度 / 接合 | **0** |
| 桁架 | **0** |
| 彎矩 | **0** |
| 樑 / 剪力 | 1 / 1 |

**重建這部分沒有前人資產可繼承。**（但 ArchSim 有——見 `ARCHSIM_PORT.md`。）

---

## 1. 產品定位 ⭐ 全檔案庫最強的商業文件

`backup/docs/competitive-analysis.md`（2026-04-03）。研究對象：Iris Shaders、Complementary、BSL、OptiFine、LabPBR。

> 所有競品都在問：「**這個貼圖長什麼樣？**」
> Block Reality 可以問：「**這個結構是混凝土還是鋼？養護到幾成？應力利用率多少？正在蓋還是蓋好了？**」
> 這不是「更好的光影」，這是「**第一個懂建築的光影引擎**」。

三個競品架構上無法擁有的優勢：

| # | 優勢 | 為什麼競品做不到 |
|---|---|---|
| A | **知道材料是什麼** | Iris/OptiFine 只看得到 RGB，LabPBR 只看得到 smoothness/F0 通道 |
| B | **知道結構的物理狀態** | 利用率、養護進度、崩塌中狀態 |
| C | **知道施工進度** | 藍圖 → 施工中 → 養護中 → 完工 → 損壞 |

**設定 UI 的差異化**：競品全是分類滑桿（自訂深度中～中高），本專案是**節點圖 + 卡片選擇器**（自訂深度無上限）+ 8 種藝術風格預設。

五個殺手級功能（附優先級）：

- **P0 材料感知 PBR 自動配置** — 零資源包依賴。混凝土 roughness 0.85 / metallic 0.0 / SSS；鋼 0.25 / 1.0 / 各向異性 0.6；木材 0.70 / 0.0 / 各向異性 0.4
- **P0 結構應力熱力圖** — 三模式 Off / Overlay / **X-Ray（穿牆看所有結構應力）**。「這不只是光影，這是工程輔助工具」
- **P1 施工階段渲染**
- **P1 社群風格包生態** — `StylePreset` → JSON → **8 位風格碼（Base62）可貼 Discord 分享**。「玩家創作的風格包 = 分發護城河，Iris 無法複製，因為它的 shader pack 是巨大 GLSL 檔需要圖形學知識」
- **P2 建築視覺化模式**（對標 Unreal ArchViz）— 正交視圖 + 無 Bloom 硬線條、**剖面切割**、黃金時刻固定光源、材料圖例、尺寸標注。「配合 NURBS/STEP/IFC 匯出，成為 Minecraft 中唯一能做專業建築簡報的模組」

⚠️ **P0 熱力圖在 FEA 下能力升級**：per-member 內力讓「X-Ray 模式」可以顯示彎矩圖與中性軸，不只是色階。

---

## 2. 必須在設計階段就避開的工程地雷

`backup/AUDIT_REPORT.md`（2026-04-10，58 條問題，掃描 `api/` 361 Java + `fastdesign/` 263 Java）。

### 2.1 🔴 批次放置繞過所有領地保護

> `FdActionPacket` / `PastePlacePacket` 的 `handlePlaceMulti`、`handlePaste`、`handleBuildSolid` 雖檢查 `isSpectator()` 與 `requireBuildPermission()`，但**沒有**檢查 `WorldBorder`，也**沒有觸發 `BlockEvent.EntityPlaceEvent`**。

後果：玩家可透過 ghost preview 在世界邊界外、FTBChunks/Claim 保護區、他人領地放置方塊。

> **新版的批次放置必須從第一天就 post `EntityPlaceEvent` 並檢查 `WorldBorder`**，否則所有領地保護 mod 全部繞過。

### 2.2 全域 static singleton 擋住多維度與單元測試

`PFSFEngine`、`StructureIslandRegistry`、`ConnectivityCache`、`CollapseManager` 全是 static singleton——「所有維度的 block 都登記在同一張 map」「queue 和 overflow buffer 無法按 dimension 隔離」「**無法進行單元測試**」。

→ 新版從一開始就 **per-dimension 實例**。

### 2.3 封包上限四種錯法

| 問題 | 後果 |
|---|---|
| `FluidSyncPacket.decode()` 在 `size > 8192` 時 `throw` | Netty 視為協定錯誤，**直接 kick 玩家**。應安全丟棄回傳空 packet |
| `CollapseEffectPacket.encode()` **無上限** | client OOM |
| `StressSyncPacket` encode 無截斷但 decode 有 65536 上限 | **封包解析錯位** |
| `AnchorPathSyncPacket.decode()` 超限回傳殘缺列表 | 靜默資料損毀 |

### 2.4 GC 壓力

`PFSFDataBuilder` 每 island 每 tick 新建 `float[N]×4 + float[6N] + byte[N]`——1M 方塊島 = **每次分配 30–40 MB heap**。`buildMortonLayout()` 用 `Integer[]` 排序 → 1M 個 Integer 物件。

→ staging pool + `int[]` + 自訂排序。

### 2.5 熱圖 cache 形同虛設

```java
worldTick = event.getPartialTick() == 0 ? 0 : System.nanoTime()
```
`getPartialTick()` 幾乎永不為 0 → **每幀重建 BufferBuilder 並 iterate 最多 4096 筆**。應改 `level.getGameTime()`。

### 2.6 tick 沒有跨 tick 排程

`ServerTickHandler` 把 PFSF、Fluid、Thermal、Wind、EM、Coupler 全塞在單一 `ServerTickEvent.END` 順序執行，**沒有剩餘工作排程機制** → 超過 50 ms 直接掉 TPS。

### 2.7 持久化靜默吞資料

`VoxelGrid.fromLongArray()` 長度不符時只 log warning + zero padding。`BlueprintNBT` 的動態材料 `dynRcomp` **沒有數值範圍驗證，可能讀入 NaN/Inf**。

---

## 3. 效能預算（`教學/07-adaptive-scheduling.md`）⭐

### 3.1 50 ms tick 的分帳

| 項目 | 預算 |
|---|---|
| 世界載入 | 2 ms |
| 實體 AI | 8 ms |
| 方塊隨機更新 | 5 ms |
| 網路 | 3 ms |
| Forge 事件 | 2 ms |
| **結構分析** | **8 ms**（可配置 2–20） |
| 流體 | 4 ms |

### 3.2 LOD 距離表

| 距離 | 精度 |
|---|---|
| < 32 格 | 1.0× |
| 32–96 | 0.5× |
| 96–256 | 0.25× |
| > 256 | **DORMANT（0×）** |

喚醒後維持 5 tick 全精度。

### 3.3 多人規則 ⭐

> 取所有玩家到 island 中心的**最小距離**——「只要有一個玩家靠近，island 就獲得高精度」。

簡單、正確、無爭議。直接採用。

### 3.4 其他可轉用的

- **收斂跳過**：`STABLE_TICK_SKIP_COUNT = 3`。「典型生存模式 50~200 個 island，其中 **80~95% 在任一時刻是靜止的**」
- **LRU 驅逐**：每 20 tick 檢查，記憶體 > 70% 觸發，`MIN_AGE = 100 tick` 防抖
- **五層發散偵測**：NaN/Inf → 急遽成長 → 短期振盪 → 持續低幅振盪 → 局部發散

⚠️ 大部分細節是為 GPU 迭代求解器寫的，直接法沒有「步數縮減」可調。**但 tick 預算、LOD 距離表、多人最小距離規則、以及「80–95% island 靜止」這個觀察，在 FEA 下完全成立且更有價值**——靜止的 island 根本不需要重解。

---

## 4. 材料表（`教學/08-system-integration.md` §8.2）⭐

**比手冊的 7 材料表好——它有 E 和 γ_m。**

| 材料 | Rcomp (MPa) | Rtens (MPa) | 密度 (kg/m³) | **E (GPa)** | **γ_m** |
|---|---|---|---|---|---|
| PLAIN_CONCRETE | 30 | 3 | 2400 | 30 | 1.5 |
| REBAR | 400 | 400 | 7850 | **200** | 1.15 |
| RC_NODE | 40 | 4 | 2400 | 32 | 1.5 |
| BRICK | 15 | 1.5 | 1800 | 15 | 2.5 |
| TIMBER | 12 | 10 | 600 | 11 | 1.3 |
| STEEL | 250 | 250 | 7870 | **200** | 1.15 |
| STONE | 100 | 8 | 2600 | 50 | 1.5 |
| GLASS | 100 | 30 | 2500 | 70 | 1.6 |
| SAND | 0.5 | 0.1 | 1600 | 0.05 | 1.4 |
| OBSIDIAN | 150 | 12 | 2300 | 70 | 1.5 |
| BEDROCK | 1e9 | 1e9 | 3000 | 1e9 | 1.0 |

γ_m 依 EN 1992 / 1993 / 1995。`CustomMaterial.Builder` 範圍：Rcomp 0.1–10,000 MPa、密度 100–25,000 kg/m³，**超出 clamp + WARN 不拋例外**。

⚠️ 注意鋼 E = 200 GPa（ACI）與 ArchSim 抓到的單位不一致問題（library 預設 Eurocode 210 GPa）——**`ARCHSIM_PORT.md` §1 的 oracle catch 就是這個**。選一個並在全鏈釘死。

---

## 5. 崩塌 UX 對照表（`教學/06` §6.5）⭐ 純 UX，與物理無關

| 失效類型 | 方塊行為 | 粒子 | 音效 |
|---|---|---|---|
| 懸臂斷裂 | `FallingBlockEntity` | 8 顆碎片 | 木頭斷裂（低音） |
| 壓碎 | `destroyBlock` | 30+15 顆大量噴射 | 石頭斷裂 + 鐵砧落地（**雙音效**） |
| 失去支撐 | `FallingBlockEntity` | 15 顆 | 石頭掉落 |
| 拉斷 | `FallingBlockEntity` | 12 顆**水平噴射**（模擬撕裂） | 鎖鏈斷裂（高音） |

失效型別要換成 FEA 的語意（D/C 超限分軸力/彎矩主導、機構形成、挫屈），但**「不同失效模式要有不同的視聽語言」這個設計原則直接沿用**，對照表是現成的起點。

流量控制：`MAX_COLLAPSE_PER_TICK = 500`、`MAX_CASCADE_DEPTH = 64`。

### 創造模式設計 ⭐ 很好的教育功能

`suppressCollapse = true` → **物理照跑、熱圖照顯示、崩塌不執行**。玩家可「預覽應力／測試設計／自由建造後切回生存驗證」。每 tick 結束自動重置。

---

## 6. 被試過並否決的設計（V1/V2/V3 共有的可行性審查表）

15 項功能評分 + 7 個架構決策，每個都有【推薦方案】【理由】【潛在風險】三段。與新版仍相關的：

| 決策 | 結論與理由 |
|---|---|
| **BlockEntity vs Capability** | 選 BlockEntity。NBT 持久化成熟、`getUpdateTag()` 自動同步、`BlockEntityTicker` 支援養護計時。風險：>10,000 方塊時 tick overhead，對策 `tickInterval` throttle |
| **異步執行緒池** | `CompletableFuture` + 自訂有界 `ThreadPoolExecutor(core=1, max=2, LinkedBlockingQueue(4), DiscardOldestPolicy)`。**明確禁止 `ForkJoinPool.commonPool()`**（會搶 GC thread）。結果必須經 `ServerLevel.execute()` 回主線程 |
| **Sidecar 格式** | JSON（初期），預留 MessagePack。1 MB 本地 IPC：JSON <50 ms、MessagePack <25 ms、Binary <10 ms |

⚠️ 第二條**直接適用於新版的 async 全量重分解**（D-008）。

---

## 7. 子網格解析度脫鉤的既有合約

`backup/FLUID_SUBBLOCK_JAVA_SPEC.md`（2026-04-11）：**1 個 Minecraft block = 10×10×10 sub-cells（0.1 m 子網格）**，附完整的上/下採樣合約：

- block → sub-cell：直接複製
- sub-cell → block：**該 block 最外層表面 sub-cells 的平均**

> 這份文件已經在做「**子網格解析度與 block 尺寸脫鉤**」。本專案要做的「斷面與 block size 脫鉤」（D-004）在概念上是同一件事的結構版，**上/下採樣合約的設計可以直接借鑑**。

同資料夾的 `BIFROST.md` 記載一套 ML 加速路線（`ChunkPhysicsLOD` 四階：SKIP / MARK / PFSF / FNO，路由門檻 `ShapeClassifier < 0.45`），現行 repo 已無。對 FEA 重建暫無直接用途，但「**LOD 四階 + 路由門檻**」的形狀可參考。

---

## 8. 三個需要處理的事實

### 8.1 教材不在 `教學/` 資料夾

`教學/` 的九篇是**大學等級的 PFSF 引擎技術手冊**（先修：線代大二、PDE 大二、數值方法大三、結構力學大三），不是給學生的教材。

而且第 1 篇 §1.1 有一張表專門論證「**為什麼不用 FEM**」——每體素 1 DOF vs 每節點 3–6 DOF、無需組裝 vs 全域剛度矩陣。**這份文件現在是舊架構的自我判決書。**

**真正的教學材料在 Drive 根目錄**：`力學講義ch9/ch11/ch12.pdf`、`ch12_梁內應力_解題.pdf`、`framecore_v2_course_lesson1.md`、`FrameCore_Freeform_UltraCanvas_Course.pdf`、`大安高工建築科第8組專題檔案.md`。

⚠️ **`ch12_梁內應力` 正是新版需要教的東西**（彎矩、中性軸、剪應力分佈）。下一輪應該掃這批。

不過 `01-potential-field-theory.md` 有一個**教學法**值得轉用——電路類比：電位 → 力學勢、電流源 → 自重、電阻 → 材料柔度、接地 → 錨固點。這套「Minecraft 玩家能懂的比喻」在新版可以改成水管網路或別的。

### 8.2 授權曾經是 MIT

`V1/block-reality-website.html` 的授權寫 **MIT**，現行 `PFSF-CORE` 是 **GPL-3.0**。商業模式寫「完全免費、開源共享，無 monetisation」。

本專案目前選 All rights reserved。**若要對外發布須先確認歷史授權承諾**——網站曾公開宣告 MIT。

### 8.3 FastDesign 的深層設計不在 Drive

`backup/docs/L1-fastdesign/index.md` 存在，但它指向的六份 L2 文件（`L2-client-ui` / `L2-command` / `L2-node-editor` / `L2-network` / `L2-construction` / `L2-sidecar-export`）**檔案本體未同步上雲**（多種查詢方式驗證）。

L1 index 透露的線索：

- 註冊三組指令 `/fd`、`/br_blueprint`、**`/br_zone`**
- **`UndoManager` 是 per-player in-memory stack，斷線即丟棄**（`UndoManager.onPlayerDisconnect()` 釋放記憶體）——這是檔案庫裡唯一的 undo 設計線索
- `item/` 有 **`FdWandItem`**（選取/放置工具）
- `client/node/` 是 **Grasshopper 風格節點編輯器**

**全文關鍵字掃描三份手冊：`gizmo` = 0、`pie / 圓餅選單` = 0、`undo 設計` = 0、`吸附 / snap-to-grid` = 0。手冊裡根本沒有這些。**

→ 若要那批設計，必須解壓 `backup/Block-Realityapi-Fast-design-main (2).zip`（1.8 MB）或 `Block-Realityapi-Fast-RT_TESTdesign-main.zip`（2.0 MB）取 `docs/L1-fastdesign/L2-*/`。這兩個 zip 遠小於其他（99–599 MB），代表是純原始碼/文件包。

---

## 9. 尚未讀取

`backup/` 根目錄，優先級低於本輪目標：

`FLUID_ML_RESEARCH_REPORT.md`（49.6 KB）、`README.md`（42.8 KB）、`AGENTS.md`（14.2 KB）、`CLAUDE.md`（14.9 KB，2026-04 版）、`docs/RT-渲染遷移合併計劃.md`（18.7 KB）、`docs/RT-IMPL-TASKBOOK.md`（30 KB）。
