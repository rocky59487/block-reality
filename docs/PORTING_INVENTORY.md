# 移植清冊

前身設計資產的逐項分類。來源：Google Drive `block-reality` 資料夾。

| 來源文件 | 日期 | 覆蓋 |
|---|---|---|
| `block-reality-manual-v3fix.md`（400 KB / 9,991 行） | 2026-03 | 全文 |
| `Block Reality Node-Based Visual Configuration System v1.0` | 2026-03-28 | 全文 |
| `Block_Reality_完整審核報告.md` | 2026-04-02 | 全文 |
| `想法`（原始概念 v1.1） | 2026-03-22 | 全文 |
| `教育程式_需求與任務規劃_v3.md` | 2026-06-05 | 全文 |

## 使用方式

四級分類：

| 級別 | 意義 |
|---|---|
| `PORTABLE` | 與物理引擎無關，照抄 |
| `REINTERFACE` | 設計成立，但資料來源或輸出契約要換 |
| `CONTAMINATED` | 設計本身假設純量場，要重新設計不是移植 |
| `OBSOLETE` | 綁死在舊物理內部，刪除 |

**第三類會偽裝成第一類。** 動手前先讀 §B。

---

## 0. 時序：污染早於 PFSF

手冊（2026-03）**完全早於 PFSF**——裡面沒有 φ 勢場、沒有 conductivity、沒有 σ 正規化。它的物理是 Union-Find 連通 + BFS 錨定 + 距離衰減純量。

但污染的本質相同，而且在最原始的 `想法` 文件就已存在：

```
├─ RBlock（單一方塊的物理狀態標記）
│   ├─ structureId: int      // 所屬複合結構體 ID
│   ├─ isAnchored: boolean   // 錨定狀態快取
│   └─ stressLevel: float   // 當前應力（0.0 ~ 1.0）
```

**PFSF 不是問題來源，它是同一個錯誤的更昂貴版本。** 因此「不帶 PFSF 就乾淨了」是錯覺——`stressLevel: float` 會從設計稿第一頁跟著走。

**但作者已經自己轉向了。** Node 報告（03-28）與審核報告（04-02）比手冊晚，方向正確：`SubBlockShape` 已輸出 `A/Ix/Iy/Wx/Wy`、`MaterialConstant` 已有 `E/ν/fy`、審核報告的物理專家直接點名 ΣM=0 缺失與量綱錯誤。

> **移植方向以 Node 報告 + 審核報告為準，手冊只當 UX / 工法 / 資料格式的來源。**

---

## A. 功能清冊

### A-1 平台與基礎設施

| # | 功能 | 分類 |
|---|---|---|
| A1 | Forge 1.20.1 MDK / ForgeGradle 6 / JDK 17 建置鏈 | `PORTABLE` |
| A2 | Prism Launcher 多實例 + `copyToDevInstance` symlink 工作流 | `PORTABLE` |
| A3 | Git 四分支策略 | `PORTABLE`（分支名要改，`api` 已無意義） |
| A4 | TypeScript Sidecar（JSON-RPC over stdio、`CompletableFuture` pending map、30s timeout） | `PORTABLE` — 純 IPC |
| A5 | `BRConfig`（ForgeConfigSpec）分層 config | `REINTERFACE` — 容器可留，半數 key 是純量場參數 |

### A-2 資料層

| # | 功能 | 分類 |
|---|---|---|
| A6 | `RMaterial` 介面 | `REINTERFACE` — 見 §E-1 |
| A7 | `DefaultMaterial` 7 種材料數值表 | `PORTABLE` — 純資料，見 §E-2 |
| A8 | `CustomMaterial.builder()` immutable builder | `PORTABLE` |
| A9 | `RBlockEntity`（NBT 持久 + client sync） | `REINTERFACE` — 承載機制可留，`br_stress`/`br_structure_id` 是污染欄位 |
| A10 | `BlockType { PLAIN, REBAR, CONCRETE, RC_NODE }` | `PORTABLE` — 角色語意在 FEA 下仍成立 |
| A11 | **快照層** `RWorldSnapshot / RBlockData / WorldSnapshotBuilder / ResultApplicator` | `PORTABLE` ⭐ **本清冊最有價值的架構資產** |
| A12 | 純計算 Contract `IStructureEngine / IStressEngine / IAnchorChecker / IFusionDetector` | `REINTERFACE` — 分層對，四個簽名全換 |

> **A11 值得單獨說**：主線程擷取不可變快照 → 異步純 Java 計算 → 主線程回寫。這個 pattern 與 FrameCore 完全相容（擷取 → 建模型 → 解 → 回寫），而且它是唯一能讓「全量重分解 async 跑」（D-008）成立的架構。**直接搬。**

### A-3 物理／分析層

| # | 功能 | 分類 | 說明 |
|---|---|---|---|
| A13 | `UnionFindEngine` 26-conn + Versioned Epoch lazy rebuild | `CONTAMINATED` | 連通分量 ≠ 結構模型。可留作空間索引/髒區劃分，不可作分析單位 |
| A14 | `AnchorContinuityChecker` 沿 REBAR 6-conn BFS | `CONTAMINATED` | 「有方塊鏈通到地」不是邊界條件 |
| A15 | `SupportPathAnalyzer.hasSupport()` | `CONTAMINATED` | 純拓撲，無力平衡 |
| A16 | `RCFusionEngine` φ 加法融合公式 | `CONTAMINATED` | 審核報告：「與變換截面法理論差距 10 倍以上」。概念留，公式換成變換截面法 |
| A17 | 觸發式降級 SPH（距離衰減） | `OBSOLETE` | 手冊自評「❌ 原版不可行」 |
| A18 | `CollapseManager` 分批坍方 + FallingBlockEntity/ItemEntity 降級 | `PORTABLE` | 演出層。觸發源換成 FEA 失效構件集 |
| A19 | `RetainingWallEvents` 擋土板臨時錨定 | `REINTERFACE` | 概念很對，實作要變成臨時支承 BC |
| A20 | Verlet 鋼索（距離約束 3–5 迭代 + `BREAK_FORCE`） | `PORTABLE`（獨立子系統）/ `REINTERFACE`（若要耦合主結構） |
| A21 | `ForceEquilibriumSolver`（SOR、autoOmega、warmStart） | `REINTERFACE` | 求解器被直接法取代；**收斂曲線 UI 可留** |
| A22 | `BeamStressEngine`（`beamsAnalyzed / failedBeams / maxUtilization`） | `REINTERFACE` ⭐ | **最接近 FrameCore 的既有設計**，三個輸出可直接對映 D/C |
| A23 | `CoarseFEMEngine` | `REINTERFACE` |
| A24 | `PhysicsLOD` 三層距離精度（Full 32 / Standard 96 / Coarse 256） | `PORTABLE` | 概念與 FEA 相容 |
| A25 | `SpatialPartitionExecutor` | `PORTABLE` | ⚠️ 審核報告：**假功能，實際單執行緒** |

### A-4 Fast Design（CAD）

| # | 功能 | 分類 |
|---|---|---|
| A26 | Brigadier `/fd` + `PlayerSelectionManager` 兩點選取 | `PORTABLE` |
| A27 | `/fd box`、`extrude`、`line`、`rebar-grid` | `PORTABLE` |
| A28 | 三視角 CAD Screen（正交 TOP/FRONT/SIDE + 3D 預覽，Tab 切換） | `PORTABLE` |
| A29 | Blueprint 格式 `.brblp`（NBT + GZIP，version=1） | `REINTERFACE` — 容器可留，見 §B-8 |
| A30 | `BlueprintIO` GZIP save/load/paste | `PORTABLE` |
| A31 | `LitematicImporter` | `PORTABLE` ⚠️ 缺大小限制，安全風險 |
| A32 | NURBS 匯出（Java → Sidecar → Dual Contouring → PCA → Trust-Region NURBS → OBJ/DXF/STEP） | `PORTABLE` — 完全獨立於物理 |

### A-5 Construction Intern（施工）

| # | 功能 | 分類 |
|---|---|---|
| A33 | 藍圖全息投影（Litematica 式 ghost block） | `PORTABLE` |
| A34 | 施工工序狀態機 6 階段 + `BlockPlaceEvent` 攔截 | `PORTABLE` — 純狀態機，零物理依賴 |
| A35 | `ConstructionZoneTracker` AABB 線性掃描 | `PORTABLE` ⚠️ 無持久化 |
| A36 | 鋼筋間距檢測 | `REINTERFACE` — 規範檢查對，後果模型要換 |
| A37 | 蜂窩弱點（`honeycomb_prob=0.15` → `Rcomp × 0.6`） | `CONTAMINATED` — 每方塊純量折減 |
| A38 | `CuringBlockEntity` 養護計時 | `REINTERFACE` — 時間狀態機可留，折減方式要換 |
| A39 | R氏應力掃描儀（HEATMAP / ANCHOR 雙模式） | `REINTERFACE` — UX 骨架極有價值 |
| A40 | `StressHeatmapRenderer` 三段色帶 overlay | `REINTERFACE` — 渲染管線照抄，映射換成 per-member D/C |
| A41 | `AnchorPathHighlighter` REBAR 連通路徑高亮 | `CONTAMINATED` — 高亮的是連通性不是荷載路徑 |
| A42 | `ScannerNetworkHandler` + `ClientRBlockCache`（LRU 8192、50ms throttle） | `REINTERFACE` — 架構可留，payload schema 全換 |

### A-6 節點式視覺化設定系統（136 節點）

⚠️ **136 個節點全部未實作**（Node 報告 §12.1，總工時 364.5h）。這一節是純設計，沒有程式碼可搬。

| # | 內容 | 分類 |
|---|---|---|
| A43 | 節點引擎核心（DAG + 髒標記傳播 + 惰性評估 + 拓撲排序 + `NodeGraphIO`） | `PORTABLE` |
| A44 | Port 型別系統 13 型 + 合法連接矩陣 + 隱式轉換 | `PORTABLE` |
| A45 | Canvas 渲染（無限平移縮放、貝茲連線 + 流動粒子、內嵌 widget、搜尋面板、Undo/Redo） | `PORTABLE` |
| A46 | Category A 渲染節點 52 個 | `PORTABLE` |
| A47 | Category B 材料與形狀節點 32 個 | 混合 — **`ShapeSelector` 是關鍵資產**（見 §E-3），`RCFusion` 節點 `CONTAMINATED` |
| A48 | Category C 物理節點 22 個 | `REINTERFACE` — **C2 荷載節點群幾乎可原樣搬進 load case 定義** |
| A49 | Category D 工具/UI 節點 22 個 | `PORTABLE` |
| A50 | Category E 輸出節點 8 個 | `PORTABLE` |
| A51 | 簡化面板 + 節點圖雙向同步 | `PORTABLE` |

### A-7 專題／教育層

| # | 功能 | 分類 |
|---|---|---|
| A52 | Spark Profiler + 三項壓測基準 | `REINTERFACE` — 方法論可留，被測對象全消失 |
| A53 | 已知衝突模組清單（OptiFine / VS2 / Create / Physics Mod Pro） | `PORTABLE` |
| A54 | 論文結構 + 三個實驗 | 實驗一 `PORTABLE`；實驗二 `REINTERFACE`；**實驗三 `CONTAMINATED`**（見 §F-6） |
| A55 | 20 分鐘 Demo 腳本 + Q&A 準備 | `PORTABLE` |
| A56 | 40 週開發時間軸（455 小時，含段考/學測緩衝、MVP 優先序） | `PORTABLE` |
| A57 | **教育閉環「設計 → 施工 → 加載 → 崩塌 → 診斷 → 改」** | `PORTABLE` ⭐ **這是產品定義，務必保留** |
| A58 | 測量關卡（經緯儀 × 水準儀） | `PORTABLE` — 與結構物理零耦合 |

---

## B. 純量場污染點 ⭐

動手移植前必讀。每條附原文出處。

### B-1 每方塊一個純量

**`RBlockEntity`（手冊 §1.2）**
```java
// stressLevel : 最近一次 SPH 計算結果（0.0 ~ 1.0）
private volatile float stressLevel = 0.0f;
private static final String TAG_STRESS = "br_stress";
```

**`StressField`（手冊 §1.2.5）— 整個計算層的 output contract**
```java
public final class StressField {
    /** 每個方塊的應力值 0.0 ~ 2.0 */
    public final Map<Vector3i, Float> stressValues;
    /** 受損方塊（stressLevel >= 1.0）的座標 */
    public final Set<Vector3i> damagedBlocks;
}
```

**`RBlockData`（快照的每方塊資料）**
```java
public final float rComp, rTens, rShear;
public final float density;
public final boolean isAnchor;
public final int structureId;
```
缺 E、ν、斷面、方向。

> **後果**：FEA 下一根 member 有 6 個內力分量 × 兩端，D/C 是**組合驗算**結果，不可壓成方塊屬性。`IStressEngine` 必須整個重簽。

### B-2 island = 連通體素集合

**手冊 §1.3 開篇一句話定義了全書的錯誤**：
> 26-connectivity Union-Find 用於追蹤哪些 RBlock 屬於**同一個連續結構體**。

「連續」被當成「結構」。兩根用一個角接觸的柱子在 26-conn 下同體，力學上是兩個獨立體系。

**`想法` §三 — `RStructure`**
```
├─ RStructure（複合結構體）
│   ├─ nodeSet: Set<BlockPos>
│   ├─ compositeR: RMaterial    // 融合後的 R 氏數值
│   └─ anchorPoints: Set<BlockPos>
```
**整棟建築被壓縮成三個 MPa 數字。**

**`StructureGraph`** 的節點是**體素**、邊是**相鄰**。FrameCore 的節點是**結構節點（6-DOF）**、邊是**構件**。兩張完全不同的圖。

**坍塌的單位是連通分量**（`SupportPathAnalyzer`）——全有或全無。FEA 下失效是逐構件的，且要重解才知道連鎖後果。

### B-3 失效 = 純量通量 vs 每方塊強度

**手冊 §1.7 `computeStress()` javadoc — 污染最集中的一段**
```java
/**
 * 公式：stressLevel = (basePressure / distance²) × materialFactor / Rcomp
 *
 * materialFactor：
 *   PLAIN    → 1.0
 *   CONCRETE → 0.8（密度較高，傳壓較弱）
 *   REBAR    → 1.2（金屬傳壓更強）
 *   RC_NODE  → 0.7
 */
```
「**傳壓更強 / 傳壓較弱**」——這是 conductivity σ 的前身，同一個錯誤的兩個版本。

驗收標準本身就把距離衰減寫死成正確行為：
> 距爆炸中心 1 格的 PLAIN：`stressLevel >= 1.0` 標記 damaged
> 距爆炸中心 8 格的 RC_NODE：`stressLevel < 0.3`

### B-4 26-connectivity 被當成物理概念

**手冊 §5.1 論文摘要範本**：
> 系統核心採用 **Union-Find 26-connectivity 引擎進行結構連通性分析**……

作者打算把它當成**物理建模上的貢獻**寫進論文。

但 §1.5 的坑 #2 反證了它從來不是物理量——連通度是被**效能**決定的：
> BFS 在大型結構中卡主線程 ← 64 深度 × 26 鄰居最壞 ~40 萬次迭代 → **嚴格用 6-connectivity（而非 26）**

### B-5 同義污染：`materialFactor`、`getCombinedStrength()`

**`RMaterial.getCombinedStrength()`**
```java
/** 返回合力強度（用於快速 LOD 判斷）預設實作：幾何平均 */
default double getCombinedStrength() {
    return Math.cbrt(getRcomp() * getRtens() * getRshear());
}
```
把三個獨立破壞模式幾何平均成一個純量——力學上無意義。**刪除。**

材料表的單位標註是「MPa（**標準化**）」——單位被自己註記為非真實。

⚠️ 手冊的 `φ_tens` / `φ_shear` 是**折減係數**不是勢場，別與 PFSF 的 φ 混淆。但公式本身也錯（見 §F-1 PHY-3）。

### B-6 荷載「流動 / 擴散」的措辭

**可行性審查表 #6 — 最該被刪除的設計決策**
> 以**加權 BFS 熱傳導模型**替代真實 SPH…**無需真實連續方程式，效果接近且可 debug**

**可行性審查表 #3**
> 降級為靜態樹搜尋：每次方塊放置僅做 1-hop BFS 檢查「是否有支撐」，**移除完整力學傳遞**

**教育程式 v3 §E**
> **載重路徑 / 支撐分析**：每個構件都要能往下追到「地基或錨點」的**傳力路徑**

⚠️ 荷載路徑是好的**呈現隱喻**，不能是**計算模型**。FrameCore 下應由求解後的 member axial forces 反推繪製，不是 BFS。

### B-7 支承／錨定冒充邊界條件

**`hasSupport()` javadoc**
```java
/**
 * 若分量中任何一個節點可抵達「支撐面」，回傳 true。
 * 支撐面：y <= minBuildHeight+1、基岩層、或 AnchorBlock。
 */
```
**任何一個**節點碰地就整個分量算穩定。完全無 ΣF=0 / ΣM=0。

**手冊 §1.5**：RC 融合的抗拉加成「只在 RC_NODE 真正**錨定到地面或有效支撐點**時才啟用……無法抵達 → **Rtens 加成歸零**」——拓撲條件冒充錨定/開展長度的力學條件。

### B-8 持久化格式中的污染欄位

**Blueprint v1（手冊 §2.3）**
```java
public static class BlueprintBlock {
    public int structureId;        // Union-Find 的 structure ID
    public boolean isAnchored;
    public float stressLevel;
}
public static class BlueprintStructure {
    public float compositeRcomp;
    public float compositeRtens;
}
```
**藍圖檔把分析結果寫進設計檔。** 新格式必須 bump version 並丟棄這四個欄位——分析結果本來就該能從幾何 + 材料重算。

### B-9 「方塊即斷面」的隱含假設 ⭐

**Node 報告 §7 C1-3 `SupportPath` 節點**
```
● blockSectionModulus ── FLOAT [默認 1/6]
```
`1/6` 正是邊長 1 的正方形斷面模數 `b·h²/6`。**「一個方塊 = 1m×1m 實心斷面」白紙黑字寫在這裡。**

刪掉這一行就是 `DECISIONS.md` D-004 的落地點。

同節點輸出 `maxMoment ── FLOAT`——單一全域最大彎矩純量，無方向、無構件歸屬。

---

## C. UX 決定（照抄）

這些是用出來才發現該這樣的決定，重新試錯很貴。

### C-1 Keybinding

| 鍵 | 動作 | 注意事項 |
|---|---|---|
| **`R`** | 切換應力熱圖 overlay | `onKeyInput` 開頭要 `if (mc.screen != null) return;` |
| **`Tab`** | CAD Screen 切換 TOP / FRONT / SIDE | **必須在 `super.keyPressed` 之前攔截**，否則被 widget focus 吃掉 |
| **右鍵** | 掃描儀掃描方塊 | |
| **Shift + 右鍵** | 掃描儀切換 HEATMAP ⇄ ANCHOR | 模式存 ItemStack NBT，跨伺服器保持 |

### C-2 節點編輯器互動

| 操作 | 鍵位 |
|---|---|
| 平移畫布 | 中鍵拖曳 |
| 縮放 | 滾輪（0.1x ~ 10x） |
| 框選 | 左鍵拖曳空白 |
| 連線 / 斷線 | 左鍵拖曳端口 / 右鍵連線 |
| 新增節點 | 雙擊空白 或 **Tab** |
| 折疊節點 | 雙擊標題 |
| 群組 | **Ctrl+G** |
| 複製 | **Ctrl+D** |
| 搜尋 | **Ctrl+F** |
| 全部適配 | **F** |

搜尋支援英文名（`ssao`）、中文名（`環境遮蔽`）、類別過濾（`render:bloom`）、模糊搜尋（`blom` → Bloom）。

### C-3 指令動詞與參數形狀

`RegisterCommandsEvent`（**FORGE bus**），`hasPermission(2)`。

**`/fd`**

| 指令 | 參數 |
|---|---|
| `/fd pos1` / `pos2` | — （per-player，**不持久化**） |
| `/fd box <x1 y1 z1 x2 y2 z2> <material>` | 6×int + word |
| `/fd extrude <direction> <distance>` | `up/down/north/south/east/west`，int **1–64** |
| `/fd rebar-grid <spacing>` | int **1–8** |
| `/fd save <name>` / `load <name>` | word |
| `/fd export` | 非同步 Sidecar，30s timeout |
| `/fd line <material> <x1 y1 z1 x2 y2 z2>` | |
| `/fd blueprint save\|load\|list\|delete <name>` | 支援旋轉 0/90/180/270° + X/Z flip |

**`/ci`**

| 指令 | 訊息 |
|---|---|
| `/ci hologram load\|clear\|move <dx dy dz>\|rotate` | `[CI] 投影旋轉 +90°` |
| `/ci zone create` / `advance` | 建立區域 / 手動推進工序 |
| `/ci guide` | 顯示當前工序提示 |
| `/ci start` / `abort` / `preview` | Session 生命週期 |

**訊息前綴慣例**：`[FD]`、`[CI]`、`[掃描儀]`、`[BlockReality]`（console）。錯誤一律 `§c`。掃描儀輸出用 `§6` 金標題 / `§f` 白內文 / `§a✓ 有效` / `§c✗ 未錨定` / `§b` 青色。

### C-4 工具模式

- 掃描儀 `ScanMode { HEATMAP, ANCHOR }`；物品名動態顯示 `R氏應力掃描儀 [HEATMAP]`
- CAD 正交 `OrthoMode { TOP, FRONT, SIDE }`
- 選取 `Box / MagicWand / Lasso / Brush / Face`；布林 `Replace / Union / Intersect / Subtract`
- 筆刷 `Sphere / Cylinder / Cube`，radius 1–64 預設 3，滾輪調整
- 建造 `SINGLE / LINE / PLANE / VOLUME`
- 批次 `FILL / REPLACE / HOLLOW / WALLS`

### C-5 視覺回饋

**熱圖色帶**（⚠️ 三段色帶是純量思維的產物，新版應改為 per-member D/C，且 ArchSim master plan 決策 8 建議連續量用 **Cividis** 色盲友善色階）
- 0.0–0.3 藍 `(0, 80, 255, 80)`
- 0.3–0.7 黃 `(255, 200, 0, 100)`
- 0.7–1.0+ 紅 `(255, 30, 0, 130)`

**渲染細節（照抄）**：`RenderLevelStageEvent` / Stage `AFTER_TRANSLUCENT_BLOCKS` / `DefaultVertexFormat.POSITION_COLOR` / `GameRenderer::getPositionColorShader`（方法引用，不加括號）/ overlay 內縮 **0.001 格**避免 Z-fighting / **32 格**外不渲染 / client 快取 LRU **8192** / `syncToClient()` **50ms throttle**。

**掃描儀輸出格式**
```
§6[掃描儀] §f位置：(x, y, z)
  材料：<materialId>
  應力等級：%.3f
  錨定：§a✓ 有效 / §c✗ 未錨定
```
熱圖模式半徑 5 格、約 200 ticks 淡出。

**RadialMenu**：sectorCount 3–12 預設 **8**、openDurationMs 預設 **150**、deadZoneRatio 預設 **0.2**、easing `Linear/CubicOut/CubicInOut/Bounce`、gamepad deadzone 0.2、highlight `#FFCC00`、background `#AA000000`。

**節點色彩**：渲染 `#2196F3` / 材料 `#4CAF50` / 物理 `#FF9800` / 工具 `#9C27B0` / 輸出 `#9E9E9E`。畫布背景 `#1A1A2E`。連線是帶流動粒子的貝茲曲線；錯誤節點紅色脈動邊框。

### C-6 Undo 語意

- 節點編輯器：獨立於世界編輯的操作歷史
- 選取/建造：`undoDepth` 1–100 預設 **32**
- Blueprint：無 undo，靠 `paste` 重放
- **施工工序：無 undo**——`advance()` 單向。設計上「做錯讓他看到後果」

### C-7 放置規則

- `SelectionBox(min, max)` 自動正規化；`BlockPos.betweenClosed` 必須 `.immutable()`
- `Screen.isPauseScreen()` 必須回 `false`（SSP 不暫停）
- 大範圍填充：**每 tick 最多 1000 方塊**，用 `ServerLevel.getServer().tell(TickTask...)` 排隊
- Ghost block：`alpha / breatheAmp（呼吸動畫）/ scanSpeed（掃描線）/ collisionColor`

### C-8 產品定義

**教育閉環**：設計 → 施工 → 加載 → 崩塌 → 診斷 → 改

定位：高中職、**直覺優先**（靠熱圖學不是先算後蓋）、沙盒＋關卡並行、多人為核心。
關卡形式：「用 X 預算蓋出能撐 Y 載重的橋」。
**崩塌即回饋**——垮掉不是懲罰，是主要學習事件。

---

## D. 施工工法

### D-1 工序狀態機

```java
public enum ConstructionPhase {
    EXCAVATION ("開挖地基", {}),                    // 允許任意方塊
    ANCHOR     ("打錨定樁", {"anchor_pile"}),
    REBAR      ("綁鋼筋網", {"iron_bars", "rebar"}),
    FORMWORK   ("架模板",   {"oak_planks", "formwork"}),
    POUR       ("澆灌混凝土", {"wet_concrete"}),
    CURE       ("養護凝固", {});                    // 禁止放置
}
```

⚠️ **兩個版本互相矛盾，需裁決**：enum 是上面 6 階段；時間軸 §27 週寫的是「鋼筋框架 → 模板 → 混凝土 → 養護 → **拆模** → 完工」。

> **建議採 7 階段**：`EXCAVATION → ANCHOR → REBAR → FORMWORK → POUR → CURE → STRIP`
> 理由見 §D-4。

### D-2 攔截機制

- `BlockEvent.EntityPlaceEvent`（**FORGE bus**）
- guard：`instanceof Player`（排除 dispenser）+ `if (level.isClientSide()) return;`
- blockId 用 `ForgeRegistries.BLOCKS.getKey(block).toString()`（**必含 namespace**）
- 違規：`event.setCanceled(true)` + `§c[CI] 禁止放置！當前工序：<name>，只允許：<list>`

### D-3 工法品質三機制

| 機制 | 參數 | 需要 FEA？ |
|---|---|---|
| 鋼筋間距檢測 | `rebar_spacing_max = 3` 格，只比同一 Y 層 | 純幾何，照抄。但後果要換成「配筋不足 → 該 member 的 M_capacity 下降」 |
| 蜂窩弱點 | `honeycomb_prob = 0.15` → `Rcomp × 0.6` | 目前純狀態機。FEA 下應為斷面 defect 屬性或局部強度折減 |
| 養護計時 | `curing_ticks = 2400`（2 分鐘），未養護 `Rcomp × 0.3` | 時間軸是純狀態機。**Node B2-4 `CuringProcess` 已升級為對數增長曲線 + `ambientTemp` 輸入 + `strengthPercent` 輸出——用這個版本**，可直接餵 FEA 的 E 與 fc′ |

`setChanged()` 節流：**每 100 tick 一次**，不要每 tick。

### D-4 舊設計完全沒做、但 FEA 下必須做的 ⭐

| 環節 | 舊設計 | FEA 下 |
|---|---|---|
| **模板承載** | **只是 allowedBlocks 白名單**——不承受任何荷載，不影響任何計算 | 模板應在 POUR→CURE 期間作為**臨時支承**進入模型：濕混凝土自重由模板傳到下層。**這是整個施工模組唯一真正需要 FEA 的地方，也是教育價值最高的一課** |
| **拆模（STRIP）** | 完全不存在 | 「移除臨時支承 + 以當時的 `strengthPercent` 重解」的 load case 切換。養護未完成就拆 → 失效 |
| **擋土板 / 臨時支撐** | 放置 → `isAnchored=true` | 臨時邊界條件的加/減，重解而非重跑 BFS |
| **施工序列** | 每階段獨立，無跨階段力學狀態 | **construction staging analysis**：每階段是一組 load case + BC，前階段內力影響後階段 |
| **塔式起重機** | 提及未實作 | 吊裝荷載 = 移動的 concentrated load |

---

## E. 材料與斷面

### E-1 `RMaterial` 介面

```java
public interface RMaterial {
    double getRcomp();     // MPa
    double getRtens();     // MPa
    double getRshear();    // MPa
    double getDensity();   // kg/m³
    String getMaterialId();
    default double getCombinedStrength();  // ⚠️ 刪除（§B-5）
    default boolean isDuctile();           // Rcomp/Rtens < 10，可留作 UI 標籤
}
```
🔴 **缺 E（楊氏模量）、ν（泊松比）、fy（屈服強度）——沒有 E 就沒有剛度矩陣，FEA 完全無法運作。**

### E-2 預設材料數值表（直接可搬）

| 材料 | Rcomp | Rtens | Rshear | density |
|---|---|---|---|---|
| PLAIN_CONCRETE | 25.0 | 2.5 | 3.5 | 2400 |
| REBAR | 250.0 | 400.0 | 150.0 | 7850 |
| CONCRETE | 30.0 | 3.0 | 4.0 | 2350 |
| RC_NODE | 33.0* | 融合公式 | 融合公式 | 2500 |
| BRICK | 10.0 | 0.5 | 1.5 | 1800 |
| TIMBER | 5.0 | 8.0 | 2.0 | 600 |
| STEEL | 350.0 | 500.0 | 200.0 | 7850 |

數值量級合理（C30 混凝土 fc′=30 MPa、鋼 fy=350 MPa 都是真實值），可作為材料庫起點。

### E-3 Node 版已補足的材料屬性 ⭐

`B1-1 MaterialConstant` 輸出已包含手冊缺的三項：
```
○ youngsModulus ── FLOAT （GPa）      ← 手冊沒有
○ poissonsRatio ── FLOAT              ← 手冊沒有
○ yieldStrength ── FLOAT （MPa）      ← 手冊沒有
○ maxSpan ── INT （最大懸臂 blocks）  ← 啟發式，應刪
```
`B1-2 CustomMaterial` 範圍：rcomp/rtens/rshear `0~10000` MPa、density `100~10000`、E `0.001~1000` GPa、ν `0.0~0.499`，另有 `validation ── BOOL` 與 `warnings ── STRING`。

> **這一版可以直接支撐 FrameCore。手冊版落後，以 Node 版為準。**

### E-4 斷面：`SubBlockShape` 就是解耦機制 ⭐

`DECISIONS.md` D-004（斷面與方塊尺寸解耦）**不需要新設計，機制已存在**：

```
B3-1 ShapeSelector
輸入：● shape ── ENUM [14 種 SubBlockShape]
輸出：○ crossSectionArea ── FLOAT （A, m²）
      ○ momentOfInertiaX ── FLOAT （Ix, m⁴）
      ○ momentOfInertiaY ── FLOAT （Iy, m⁴）
      ○ sectionModulusX ── FLOAT （Wx, m³）
      ○ sectionModulusY ── FLOAT （Wy, m³）
      ○ voxelGrid ── STRUCT （10³ 體素資料）

B3-2 CustomShape — 內嵌 10³ 體素編輯器 + autoCalcProperties
```
加上 `ShapeCombine`（Union/Subtract/Intersect）、`ShapeRotate`、`ShapeMirror`、`ShapeToMesh`。

**要補的**：
1. 刪除 `blockSectionModulus = 1/6`（§B-9）
2. 補 `Iz`、扭轉常數 `J`、剪力面積 `Av,y / Av,z`（Timoshenko 需要）、塑性斷面模數 `Zx/Zy`
3. 補**斷面主軸方向 / local axis rotation**——一根 I 型梁繞自身軸轉 90° 是完全不同的結構，舊設計無任何方向資訊
4. 明訂「一個方塊 = 1m 長度 × 由 voxel grid 決定的斷面」，而非「方塊本身是斷面」

### E-5 方塊狀態機

`PLAIN → REBAR → CONCRETE → RC_NODE`。教育程式 §D 稱為「**材料狀態機**：同一個構件會隨施工轉變身份」——這個概念在 FEA 下依然成立且很有價值（同一 member 的 section/material 隨施工階段變化）。

---

## F. 已知缺陷（作者自承）

### F-1 審核報告物理模型缺陷 — 評分 56/100（全審核最低）

| ID | 問題 |
|---|---|
| PHY-1 | 牛頓第一定律誤用，應使用靜力平衡條件 (ΣF=0, ΣM=0) |
| **PHY-2** | **力矩平衡缺失** — 僅檢查 ΣF=0，完全忽略 ΣM=0 |
| PHY-3 | RC_NODE 參數無理論基礎，97/3 複合材料計算錯誤 |
| PHY-4 | 彎矩公式量綱錯誤 — L/4→L/8 量綱為長度，不是彎矩 (N·m) |
| PHY-5 | 無材料/幾何非線性，無法預測塑性鉸 |
| PHY-6 | 僅考慮自重，缺活荷載、風荷載、地震荷載 |

**「弱功能或假功能清單」**

| 功能 | 狀態 |
|---|---|
| 力矩平衡檢查 | ❌ 假功能 |
| RC 融合材料計算 | ❌ 錯誤 — **與變換截面法理論差距 10 倍以上** |
| 活荷載/風荷載 | ❌ 未實作 |
| 並行計算 | ❌ 假功能 — 宣稱「區塊級平行運算」但**實際單執行緒** |

**最終建議逐字**：「此專案目前**不適合用於真實工程設計驗證**」。

### F-2 其他

- **演算法**：SOR 收斂判定僅用全局殘差；穩定性判定用 90% 經驗閾值缺乏物理依據
- **程式碼**：`BFSConnectivityAnalyzer.java` **869 行** God Class；`SidecarBridge.java` 696 行；`BlockRealityMod` God Class；無自訂例外
- **API**：`RMaterial` 擴充無 default → 破壞性變更；核心求解器 package-private 無法擴展；**重複的 `IFusionDetector` 介面（`spi/` 與 `physics/` 各一）**
- **安全**：🔴 `BlueprintNBT` 缺深度限制；🔴 `LitematicImporter` 缺大小限制
- **相依**：🔴 LWJGL 版本衝突（MC 3.3.1 vs 模組 3.3.5）
- **專案管理 55/100**：無 CI/CD、`git_log.txt` 被提交污染倉庫、29 次提交 0 標籤 0 Releases
- **測試 60/100**：539 個 JUnit 測試但缺 Mock、`CollapseManager` 測試僅 77 行未測實際崩塌邏輯、無覆蓋率工具
- **使用者評審 30 人**：最想改進 — 降低顯卡需求 95%、簡化安裝 90%、效能優化 88%

### F-3 手冊自承的降級

| 功能 | 評定 | 降級 |
|---|---|---|
| 支撐點分析 + 坍方 | ⚠️ | 「降級為靜態樹搜尋…**移除完整力學傳遞**」 |
| 觸發式 SPH 熱圖 | **❌ 原版不可行**（難度 9/10） | 「加權 BFS 熱傳導模型…效果接近且可 debug」 |
| CAD 三視角 | ⚠️ | 放棄同步三視角 |
| NURBS 輸出 | ⚠️ | 降級為 Linear Polyline |
| RC 工法 | ⚠️ | 移除養護時間模擬 |
| PBD 鋼索 | ⚠️ | 降級 Verlet → 純視覺懸鏈線 |

### F-4 未完成的 TODO

- `isGrounded()`：「（**更完整版本應做遞歸 DFS**）」
- `getZoneBounds()`：`// TODO: 從 zone 數據取得` → **`return null;`**（POUR 階段的間距檢測實際會 NPE）
- `ConstructionZoneTracker` **沒有持久化**，伺服器重啟後 zone 消失
- `AnchorPathHighlighter.anchorData` 是靜態 Map，**多人互相干擾**
- 全息投影旋轉「簡化為繞原點」，應繞藍圖中心
- 論文引用 8 篇中 **4 篇標註「（請查閱確切作者與 DOI）」**
- Node 報告：**136 個節點全部未實作**（364.5h）

### F-5 教育程式 v3 的架構原則 ⭐

> UE5 的破碎物理是「遊戲物理」，不是「工程力學」，好看但不準。
> **建議折衷**：用簡化工程計算判定「安不安全、哪裡破壞」（教學正確性），用破碎物理演出「破壞後怎麼垮」（視覺震撼）。**算的歸算，演的歸演。**

### F-6 實驗三從設計上就是錯的

手冊 §5.2 要拿 `R_theory = φ_comp·R_comp,conc + φ_tens·R_tens,rebar` 對標 ACI 318-19 的 `P_n = 0.85 f'c (Ag − Ast) + fy·Ast`。

左式是**強度（MPa）的加權和**，右式是**軸壓承載力（N）= 應力×面積**。**量綱不同**，且左式完全沒有 `Ag`、`Ast`。這個實驗即使做出來，「相對誤差」也是無意義的數字。

**重新設計為**：以真實斷面（A, As）建 member → 解 → 比 `P_n`（N）。

---

## G. FEA 缺口（舊設計完全沒有）

### G-1 構件擷取 🔴 最大缺口

舊設計**只有方塊，沒有構件**。從方塊集合到 6-DOF 梁元的映射完全不存在。需要規定：

- 何謂一根 member？連續同向、同 blockType、同材料的方塊 run？
- 分段規則：T 型交會、材料變更、斷面變更、支承點 → 打斷
- 節點合併容差：兩根 member 端點多近算同一結構節點？
- **反向映射**：解完後 member 內力如何回寫到方塊？舊的 `ResultApplicator` 吃 `Map<座標, float>`，新的需要 `Map<memberId, MemberForces>` + `memberId → Set<BlockPos>` 索引
- 增量：挖掉一個方塊，哪些 member 要重新擷取？（舊的 epoch 機制可借鑑，作用對象從 component 換成 member）

### G-2 斷面指派 🔴

見 §E-4。額外未決：每個方塊各自帶 shape，還是 member 層級屬性？一根梁中間有一塊 shape 不同怎麼辦？

### G-3 支承 / 邊界條件語意 🔴

- 舊的「支承」是布林 + 「碰到 y≤minBuildHeight+1」。FEA 需要**每個受約束節點的 6 個自由度各自 fixed/free**
- 缺：固定 / 鉸 / 滾 / 彈性（地基彈簧）支承的區分與 UI
- 缺：`anchor_pile` 到底約束什麼（目前只是 boolean marker）
- 缺：**與地形接觸如何自動生成 BC**——方塊坐在泥土上是滾支？固接？
- 缺：**穩定性檢查**——支承不足時剛度矩陣奇異，Cholesky 失敗。舊設計沒有這個失敗模式的概念，也沒有玩家回饋（「你的結構是機構，不是結構」）

### G-4 接合剛性 🔴

- 舊設計「接合」= 方塊相鄰。**完全沒有剛接/鉸接概念**
- 需決定：兩根 run 交會時預設剛接還是鉸接？RC 現澆 → 剛接、鋼結構螺栓 → 鉸接？
- 需要 member end release 的資料模型與 UI
- ⭐ **`RC_NODE` 最自然的新語意就是「剛性節點」**，不是「融合後的複合材料方塊」

### G-5 荷載工況 🟡

Node C2 已有 `Gravity / DistributedLoad / ConcentratedLoad / MomentCalculator / WindLoad`——**節點群可直接搬**。但缺：

- load case 與 **load combination** 的資料模型（1.2D + 1.6L 這類）
- 荷載如何附著（member？節點？面？）與玩家如何施加（教育程式 §E 要求「可施加人、車、堆載，可動可變」，手冊完全沒實作）
- 自重的產生規則——density 只用於重量計算，從未有 member self-weight distributed load
- 施工階段荷載（濕混凝土、模板、施工活載）

### G-6 牆 → shell element 🔴

- 舊設計「牆」只是 `4×6×1` 方塊堆
- 缺：什麼樣的方塊排布會被辨識為 shell？厚度從哪來？MITC4 facet 的網格劃分規則？
- 缺：牆與樑柱交界處理（shell node 與 beam node 的 DOF 相容 — drilling DOF 問題）
- 缺：開口（門窗）如何從網格挖除
- 缺：樓板 vs 牆的區分（都是 shell 但荷載傳遞方式不同）

### G-7 其他

| 缺口 | 說明 |
|---|---|
| **E 與 ν** | 手冊完全沒有；Node 版有但未進手冊 |
| **單位系統** | 強度 MPa、模量 GPa、密度 kg/m³、長度「格」。**「一格 = 1 公尺」從未被明確宣告**，卻是所有換算的前提 |
| **D/C 呈現契約** | `UtilizationReport` / `maxUtilization` 存在但無 schema。需要 per-member 的 6 內力 + 各驗算式 D/C + 控制斷面位置 |
| **變形** | `DeflectionMap` 只是個名字。缺撓度限值（L/240、L/360）、放大倍率、serviceability vs strength 區分 |
| **求解失敗的 UX** | 舊的失敗是「BFS 超時」。FEA 的失敗是「矩陣奇異 / 病態 / 不收斂」，需要完全不同的玩家訊息與診斷（哪個節點缺約束？） |
| **分析模型持久化** | 建議：只存幾何 + 材料 + BC + load，member 每次重新擷取 |
| **二階效應 / 挫屈** | 完全缺席。柱的挫屈是建築教學最重要的破壞模式之一 |
| **非線性 / 塑性鉸** | 審核 PHY-5 已點名。「延性 vs 脆性破壞」是關鍵教學點（`isDuctile()` 顯示作者想教但無法計算） |
| **多人一致性** | 教育程式 §I 要求多人核心，但 FEA 是全域計算——誰來解？多人同改的鎖/衝突策略完全未設計（舊的 Union-Find 只需局部重算，FEA 不行） |

---

## 一頁總結

**照抄**：快照層三段式架構（A11）、Blueprint/NBT/GZIP 容器、`/fd` `/ci` 指令表面、工序狀態機、掃描儀雙模式 UX、熱圖渲染管線、節點引擎全套、Sidecar IPC、NURBS 匯出、教育閉環定義、40 週時程方法論。

**重新詮釋**：`RMaterial`（補 E/ν/fy，刪 `getCombinedStrength`）、`SubBlockShape`（升級為完整 section library，刪 `blockSectionModulus=1/6`）、`RC_NODE`（複合材料方塊 → **剛性節點**）、Node C2 荷載節點群（→ load case/combination）、擋土板與模板（→ 臨時支承 BC）、養護（→ 隨時間變化的 E 與 fc′）、坍塌演出（觸發源換成 FEA 失效構件集）。

**整個刪掉**：距離衰減 SPH、`stressLevel` float 欄位與其所有 NBT/packet/藍圖欄位、`hasSupport()`/`isGrounded()` 拓撲支撐判定、`AnchorContinuityChecker` 的 BFS 錨定、`RCFusionEngine` 的 φ 加法融合公式、26-connectivity 作為物理概念的所有敘述（含論文摘要範本與實驗三）、`StructureResult`/`StressField`/`AnchorResult` 三個結果物件。
