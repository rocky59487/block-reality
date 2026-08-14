# FastDesign 移植清單

來源：`rocky59487/block-realityapi-fast-design`。

⚠️ **`docs/L1-fastdesign/` 全部是摘要層級**（最長 117 行），不含鍵位細節、封包欄位、參數範圍。**本檔的具體數值全部來自原始碼。**
⚠️ `docs/L1-api/L2-physics/` 的六個 FEA 相關 L3 文件**已被掏空**（只剩標題 + `<!-- removed: 類別已於 v2.1 移除 -->`），但**原始碼還在**。文件與程式碼有多處不一致，**以程式碼為準**。

---

## 1. ⭐ 兩個最重要的發現

### 1.1 `SubBlockShape` 是查表，不是體素積分器 — 而且 `CUSTOM` 破功

這直接關係到 D-004（斷面與方塊尺寸解耦）與自由模式。

`SubBlockShape` 的每個 enum 常數在建構子**直接帶入預先算好的 6 個 double**：
```java
SubBlockShape(name, fillRatio, crossSectionArea,
              momentOfInertiaX, momentOfInertiaY,
              sectionModulusX,  sectionModulusY)
```

`VoxelGrid`（10³）**只用來算 `fillRatio` 與渲染，從不參與 A/I/W 計算**。`generateVoxelGrid()` 是形狀 → 體素的**單向**生成，沒有反向積分。

🔴 **而 `CUSTOM`（玩家自訂雕刻）一律回傳全塊值**：

```java
public double crossSectionArea() {
    return isTemplate() ? shape.getCrossSectionArea() : PhysicsConstants.BLOCK_AREA; // 1.0
}
public double momentOfInertiaX() {
    return isTemplate() ? shape.getMomentOfInertiaX() : PhysicsConstants.FULL_MOMENT_OF_INERTIA; // 1/12
}
```

**玩家把方塊挖成細針，引擎照樣當成 1m×1m 實心算。** javadoc 稱之為「保守近似」，但方向是反的——**它高估容量，不保守**。

> **這是全設計最大的正確性破口，而且它正是自由模式必須解掉的東西。**
> 新引擎需要一個真正的 **voxel → section properties 積分器**：`A`、`Ix`、`Iy`、`Ixy`、形心、`Sx`/`Sy`、扭轉常數 `J`、翹曲常數 `Cw`。

### 1.2 `BEAM_NS` / `BEAM_EW` 是既有設計裡唯一體現真實斷面效率的地方

I 型梁有真幾何（翼緣 + 腹板的體素座標）：

| 形狀 | fillRatio | A | **Ix** | **Iy** | Wx | Wy |
|---|---|---|---|---|---|---|
| `BEAM_NS` | 0.44 | 0.44 | **0.04167** | **0.01389** | 0.08333 | 0.02778 |
| `BEAM_EW` | 0.44 | 0.44 | 0.01389 | 0.04167 | 0.02778 | 0.08333 |

**強弱軸比 3.0。** 而且體素實填率自洽：翼緣 2×(8×2×10)=320 + 腹板 (2×6×10)=120 = 440/1000 = 0.44。

**這是「斷面與方塊尺寸解耦」的既有先例**，也是驗證新積分器的現成測試案例——用 `buildVoxelGrid()` 的 I 型梁幾何跑積分器，應該得到 0.04167 / 0.01389。

---

## 2. ⭐ `StructuralSafetyHud` — 最高價值的可攜資產

525 行，**文件完全沒提**。而且它的語意**本來就是 FEA 語言**：利用率 0–100%，`> 100%` 即失效——直接對應 AISC / Eurocode 的 demand/capacity ratio。

```
RENDER_DISTANCE      = 32.0    3D 標籤最大渲染距離（格）
OVERLOAD_THRESHOLD   = 1.0f    超過即失效
MAX_CRITICAL_ENTRIES = 6       2D 面板最多列 6 個臨界元素
MIN_DOT_FORWARD      = -0.3    相機可見錐，忽略玩家背後
TOGGLE_HUD           = KEY_J
```

**兩層渲染**：

**(A) 3D 世界空間 billboard** — 每個結構方塊上方顯示利用率 %，朝相機。剔除：距離 > 32 格、`dot(forward, dir) < -0.3`。

| 利用率 | 顏色 |
|---|---|
| `> 1.0` | **閃爍紫** `f = 0.7 + 0.3·sin(millis·0.01)` |
| `>= 0.9` | 紅 `0xFFFF2222` |
| `>= 0.8` | 橙 `0xFFFF8800` |
| `>= 0.5` | 黃 `0xFFFFDD00` |
| `< 0.5` | 綠 `0xFF44FF44` |

超載加前綴：`(now/400) % 2` 交替 `§d⚠` / `§c⚠`（2.5Hz）。

**(B) 2D 右上摘要面板** — `panelW = 200`，位置 `screenW − 208, 10`。**整體健康評分 A–F**、最大利用率、臨界/警告計數、Top-6 臨界元素清單（依 `stressLevel` 降序）。

平滑：`smoothMaxUtil += (maxUtil − smoothMaxUtil) · min(1, dt·4f)`（時間常數 1/4 秒）。
分類門檻：`>= 0.90` critical、`0.50–0.90` warning。

**⚠️ 過期機制已經內建**：`isStale(now)` = 超過 **10 秒**未更新即視為過期，每 200 tick 清一次。
→ 這與 Issue #2/#7 的 `worldRevision` 概念**方向一致但機制較弱**——時間過期不等於版本過期。**應改用 `worldRevision`。**

🔴 **`StructuralSyncPacket` 在全倉庫不存在。** HUD 有完整渲染層但**從來沒有資料來源**。

| 項目 | 分類 |
|---|---|
| 全套渲染 + 剔除 + 色階 + A–F 評分 + Top-6 | **PORTABLE** ⭐ 最高價值 |
| 資料鍵為 `BlockPos` | **REINTERFACE** → `memberId`（一個 member 跨多格） |
| 10 秒時間過期 | **REINTERFACE** → `worldRevision` |
| 資料通道 | **缺口（不存在）** |

---

## 3. 鍵位與互動（PORTABLE）

### 3.1 核心設計：持工具/不持工具分流

`FdKeyBindings.isHoldingTool()` — 主手或副手持 `ChiselItem` / `FdWandItem` → `handleChiselKeys()`，否則 `handleSelectionKeys()`。**同一組鍵在兩種語境下語意完全不同。**

| 鍵 | 常數 | 非工具模式 | 工具模式 |
|---|---|---|---|
| **G** | `OPEN_PANEL` | 開 `ControlPanelScreen` | 同 |
| **V** | `CYCLE_BUILD_MODE` | 循環 `BuildMode` + 清預覽 | 同 |
| **J** | `TOGGLE_HUD` | 開關結構安全 HUD | 同 |
| **↑↓** | `TOOL_HEIGHT_*` | 選取框整體上下移一格 | 選區高度 ±1 |
| **→←** | `TOOL_WIDTH_*` | **依玩家水平朝向**左右平移 | 選區寬度 ±1 |
| **H** | `TOOL_EDGE_LENGTH` | **按住**抓最近選取面拖曳縮放 | 邊長 +1（Shift+H = −1） |
| **X** | `TOOL_ERASE` | 取消選取（Shift+X = 排除單一方塊） | **按住**橡皮擦 on/off |
| **左 Alt** | `TOOL_MENU` | 開 `PieMenuScreen` | 雕刻刀→`ChiselToolScreen`；法杖→Pie |

朝向映射：`NORTH→east, SOUTH→west, EAST→south, WEST→north`。

**H 拖曳的面偵測**：對 6 面算 `|target.axis − sel.axis|` 取最小，面名 `"x-"/"x+"/"y-"/"y+"/"z-"/"z+"`。以 `grabbedFace` + `lastSentValue` 去抖，值不變不重送。

### 3.2 BuildMode（6 模式，V 循環）

```
NORMAL / LINE / WALL / CUBE / MIRROR_X / MIRROR_Z
```

流程：**Ctrl+右鍵**設鏡像錨 → 第一次右鍵設錨點 A → 第二次右鍵送 `PLACE_MULTI`。

payload：`"<MODE>;<ax,ay,az>;<bx,by,bz>;<mx,my,mz>"`

⚠️ **沒有 CAD 式吸附**——端點/中點/軸向/角度全部沒有，只有方塊格天然的整數對齊。**新引擎要自建。**

### 3.3 PieMenu 幾何（PORTABLE）

```
INNER_RADIUS = 30f    OUTER_RADIUS = 110f    ICON_RADIUS = 75f
angle = toDegrees(atan2(dx, -dy))，負值 +360   → 0° = 正上方，順時針
sectorSize = 45°，繪製時 endAngle − 1.5° 留間隙
展開動畫 150ms，Ease-Out cubic 1−(1−t)³
hover 縮放 target 1.08f，每幀 lerp 0.3f
底色 0xFF2B2B2B（Grasshopper 深灰）、高亮 0xFFFFF1A5
確認：mouseReleased / mouseClicked / keyReleased(342 或 346 = LEFT/RIGHT_ALT)
```

八扇區實際內容（**與文件不符，以碼為準**）：複製 / 開啟節點 / 貼上 / 全域重製 / 撤銷 / 清除選擇 / 填充材質 / 開啟面板。

### 3.4 Transform Gizmo — 渲染完整，輸入層不存在

```
AXIS_LENGTH = 3.0f   ARROW_RADIUS = 0.15f   ARROW_HEAD_LEN = 0.5f   SHAFT_WIDTH = 0.05f
中心 = (min + max + 1) / 2.0f
X 紅 (220,60,60,200)→(255,100,100,255)；Y 綠；Z 藍
disableDepthTest + disableCull
```

🔴 javadoc 宣稱「左鍵拖曳沿軸移動、Shift+滾輪旋轉 90°」，但**原始碼沒有任何 ray-pick / 拖曳處理器呼叫這些 API**。`highlightedAxis` 永遠是 `null`，`startDrag` 零呼叫者。**是純視覺骨架。**

---

## 4. Undo（`DeltaUndoManager` PORTABLE，但涵蓋範圍要擴）

三套獨立引擎，互不相通：

| 引擎 | 作用域 | 深度 | Redo |
|---|---|---|---|
| `UndoManager` | 世界方塊（全量快照） | 預設 10，範圍 1–50 | ❌ |
| `DeltaUndoManager` | 世界方塊（差異） | `MAX_HISTORY_STEPS = 50` | ✅ |
| `NodeCanvasUndoManager` | 節點圖 | `MAX_HISTORY = 100` | ✅ |

**`DeltaUndoManager` 的三段式 API 值得直接搬**：
```java
var before = DeltaUndoManager.captureBeforeState(level, positions);
for (BlockPos p : positions) level.setBlock(p, newState, 3);
DeltaUndoManager.commitChanges(playerId, level, before, "fill concrete");
```
`commitChanges` **只記錄真正變更的方塊**（前後比對）。新操作自動清空 redo 堆疊。設計參考 WorldEdit `EditSession`。

**語意**：完全 per-player（UUID key）、server main thread、**無持久化**（重啟即失）、**斷線清空**、**無跨玩家衝突偵測**（A undo 會覆寫 B 之後的修改）。

⚠️ **最重要的擴充需求**：現行 undo 只涵蓋 `BlockState` + BE NBT。**新引擎的 member / section / formwork 宣告不在 `BlockState` 裡**——undo 必須擴成「世界變更 + 分析模型變更」的**原子交易**，否則 undo 後模型與世界會不同步。

這與 Issue #8 的 transaction ID 要求是同一件事。

---

## 5. 網路（模式可搬，內容要換）

`FdNetwork` 獨立通道，`PROTOCOL_VERSION = "1"`，封包 ID 以 `AtomicInteger` 遞增（**註冊順序即 ID**），`registered` volatile flag 防重複註冊。

**值得保留的模式**：bounds check **越界退化為安全值而非拋例外**。`HologramSyncPacket` 的「**先讀完所有欄位再判斷**」尤其重要——避免 buffer 對齊錯誤。

**要換掉的**：

| 問題 | 說明 |
|---|---|
| `FdActionPacket` ordinal 越界 → `throw` | **在 decode 期間拋例外會踢連線** |
| payload 是 512 字元 ad-hoc 字串文法 | 無型別安全、無版本控管。新引擎的 member/section/load-case 指令需**結構化 payload** |
| `PastePreviewSyncPacket` 上限 65,536 blocks | 最壞 **650 KB 單包**，無分片無壓縮。應改傳 member list |
| 🔴 **完全沒有節流** | 全倉庫 grep `rate`/`throttle`/`cooldown` 在網路層零命中。`↑↓←→` 每次點擊一包可 spam |

---

## 6. 節點編輯器（畫布 PORTABLE，物理節點 CONTAMINATED）

### 6.1 畫布機制（品質高，直接搬）

```
MIN_ZOOM = 0.1f   MAX_ZOOM = 10.0f   ZOOM_STEP = 0.1f
zoomAt: targetZoom = clamp(targetZoom + delta·ZOOM_STEP·targetZoom, MIN, MAX)   ← 乘性縮放
wire hover 命中：hitDist = 8.0f / zoom     ← zoom 自適應
右鍵端口斷線命中半徑：16×16（canvas 空間）
```

| 鍵 | 功能 |
|---|---|
| Tab / 雙擊空白 | 搜尋面板 |
| **F** | Fit All |
| Delete | 刪除選中 |
| Ctrl+G / Ctrl+Z / Ctrl+Y 或 Ctrl+Shift+Z / Ctrl+S / Ctrl+D / Ctrl+A | 群組 / Undo / Redo / 存 JSON / 複製 / 全選 |

DAG 生命週期：宣告 ports → `markDirty()` 沿線傳播下游 → `EvaluateScheduler.evaluateDirty()` 依 **Kahn 拓撲排序** → `evaluate()`。

8 種內嵌控件：`InlineSlider` / `Checkbox` / `Dropdown` / `ColorPicker` / `CurveEditor` / `BlockPicker` / `VoxelEditor` / `RecipeGrid`。

### 6.2 `IBinder` 架構（PORTABLE）

`bind(NodeGraph)` / `apply(T)` / `pull(T)` / `isDirty()` / `clearDirty()`。
`LivePreviewBridge` 在 **`AFTER_SKY`** 階段、**限流 16ms（60fps）** 依序 `apply()`。

`MutableRenderConfig` 的存在理由值得記：`BRRenderConfig` 用 `static final`（JIT 內聯）無法 runtime 修改，故以 `volatile` 欄位鏡像，同時維持 `fastdesign → api` 單向依賴。

### 6.3 Port 型別（14 種）需擴充

現有：`FLOAT / INT / BOOL / VEC2 / VEC3 / VEC4 / COLOR / MATERIAL / BLOCK / SHAPE / TEXTURE / ENUM / CURVE / STRUCT`

⚠️ **`STRUCT = CompoundTag` 是萬用逃生口**，所有 solver spec 都走這條，**等於沒有型別檢查**。

新引擎需新增：`MEMBER` / `SECTION` / `JOINT` / `LOAD_CASE` / `LOAD_COMBO` / `SHELL`，並廢除 `STRUCT` 逃生口。

### 6.4 🔴 物理節點是純配置面板，不是資料流

所有 solver 節點的 `evaluate()` 只把輸入打包成 `CompoundTag` 寫 `solverSpec`，然後把 `convergenceRate` / `iterationsUsed` / `residual` **硬編碼為 0**。

**這些節點從不接收真實求解結果。整個 `result/` 分類的輸入端沒有任何生產者。**

值得留的概念：`BeamAnalysisNode` 的 `beamsAnalyzed` / `failedBeams` / `maxUtilization` 三個輸出在 6-DOF FEA 下仍成立。
必須丟的：`ForceEquilibriumNode`（SOR ω=1.25）、`CoarseFEMNode`（`lateralFraction = 0.15` 這種 fudge factor）、`SupportPathNode`（`blockSectionModulus = 1/6` 硬編碼）。

---

## 7. `BeamElement` — 公式全對，前提全錯

`api/physics/BeamElement.java`（301 行）是既有設計裡**唯一真正的力學元素**。

**公式本身完全正確且是標準結構力學**：

| 方法 | 公式 |
|---|---|
| `axialStiffness()` | `E·A / L` |
| `bendingStiffness()` | `E·I / L³` |
| `eulerBucklingLoad()` | `π²·E·I / (K·L)²`，**K = 0.7**（AISC Table C-A-7.1） |
| `maxAxialForce()` | `min(Rcomp·1e6·A, P_cr)` |
| `maxBendingMoment()` | `Rtens·1e6·I / y_max` |
| `maxShearForce()` | `Rshear·1e6·A` |
| `utilizationRatio()` | `max( sqrt(axial² + moment²), shear )` — Von Mises 式 + Tresca 剪力獨立 |

K 值表完整（`0.5` 兩端固定 / `0.7` 一固一鉸 / `1.0` 兩端鉸接 / `2.0` 懸臂），選 0.7 的理由是「方塊格間連接介於鉸接與固定端之間」。

**但四個前提全是新引擎要推翻的**（javadoc 自承）：

1. 截面均為 1m×1m 正方形
2. 取兩端材料較弱值（木桶原理）
3. **只考慮 N 與 M，忽略扭矩**（因為方塊不旋轉）
4. **不求解全局剛度矩陣（太慢），改用局部梁判定**

以及三個硬編碼：`L = 1m` 固定、`y_max = 0.5` 硬編碼（**與 `Wx/Wy` 表重複且矛盾**——`W = I/c` 已在表裡卻不用）、`min(Ix, Iy)` **永遠取弱軸**且不區分實際彎曲方向。

**複合剛度用調和平均** `E = 2E_A·E_B/(E_A+E_B)` —— 新規則的「澆置 → 複合斷面」應改用**變換截面法** `n = E_s/E_c`。

---

## 8. ⚠️ 材料表衝突（需裁決）

**現在有三張不一致的材料表。**

| 材料 | `DefaultMaterial`（本倉庫） | `教學/08`（`ARCHIVE_FINDINGS` §4） | 手冊 v3fix |
|---|---|---|---|
| PLAIN_CONCRETE Rcomp / E | 25.0 / **25** | 30 / **30** | 25.0 / — |
| STEEL Rcomp / E | **350** / 200 | **250** / 200 | 350 / — |
| REBAR Rcomp | 250 | 400 | 250 |
| STONE Rcomp / E | **30** / 50 | **100** / 50 | — |
| GLASS Rtens | **0.5** | **30** | — |

本倉庫版有 **ν（Poisson ratio）**，`教學` 版有 **γ_m（材料分項係數）**，兩邊各有對方沒有的欄位。

本倉庫版的來源標註是 Eurocode 2 EN 1992 / AISC Steel Manual / GB 50010、50017，且有幾個刻意的設計決定值得留：
- `BEDROCK` 用有限大常數 `1e15` 而非 `Float.MAX_VALUE`
- `SAND` `Rtens = 0` 表示無法懸臂
- `TIMBER` `Rtens(8) > Rcomp(5)` 反映木材真實特性

**建議**：以本倉庫的 `DefaultMaterial` 為基底（有 ν、有來源標註、有設計理由），補上 `教學` 版的 γ_m，並逐項複查衝突值。**這件事要在寫第一行 fixture 之前做完**，否則 oracle 會建在錯的材料上。

---

## 9. 從未實作 / 不存在（明確清單）

| 項目 | 狀態 |
|---|---|
| `LoadCombination` + 6 條 LRFD 組合 | **不存在**，只有 `LoadType.java` 頂端的 TODO 註解 |
| `ForceVector3D`（6-DOF 力/力矩向量） | **不存在** |
| `LateralTorsionalBuckling`（AISC F2 / EC3 §6.3.2） | **不存在**，零公式留存 |
| `StructuralSyncPacket`（HUD 資料源） | **不存在** |
| Gizmo 拖曳/旋轉輸入處理器 | **不存在** |
| 網路節流 / rate limit | **不存在** |
| CAD 吸附規則 | **不存在** |
| **voxel → section properties 積分器** | **不存在**（查表 + CUSTOM 全塊 fallback） |
| MITC4 / shell / formwork | **不存在**（全是 solid block） |
| 節點圖 → 求解器的真實資料回流 | **不存在**（solver 節點輸出硬編碼 0） |
| Undo 持久化 / 跨玩家衝突處理 | **不存在** |

**`LoadType` 的 6 型與 γ 因子是 PORTABLE 的**（ASCE 7-22 §2.1）：`DEAD` γ=1.2/0.9、`LIVE` 1.6、`WIND` 1.0、`SEISMIC` 1.0、`SNOW` 0.5–1.6、`THERMAL` 1.2，含 `isLateral()` / `isGravity()` 輔助方法。TODO 裡的 6 條組合直接可實作。

---

## 10. 驗收 oracle（PORTABLE ⭐）

`docs/BENCHMARKS.md` 的閉合解公式**與純量場無關，可直接當新引擎的驗收 oracle**：

- **懸臂**：`σ_max = 6ρgL²/h²`；`M(x=0) = ½ρg·b·h·L²`
  校準值：`ρ=2400, L=4.0, h=0.2 → σ ≈ 56.5 MPa`；混凝土 `Rtens_eff = 3 MPa` → **4m 必斷**；0.5m 懸臂 `σ = 0.88 MPa` **安全**
- **半圓無鉸拱**：`M_c = wR²(1 − 2/π)`；`H = wR²/(π·h_arch)`；`N_crown = H + wR`
- **尺度律**：`L→2L`（h 不變）→ `σ×4`；`h→2h` → `σ×0.25`；等比放大 → σ 不變；`E ∝ L³`
- 深拱 `h=8` vs 淺拱 `h=2` → 推力比 **4×**

配合 `GATES.md` 已有的兩條硬規則（非正方形斷面、焊接把機構變成結構），這批就是第一輪 gate 的內容。

---

## 11. 分類速查

**PORTABLE（原樣搬）**
`FdKeyBindings` 全套 + 工具分流 · `BuildMode` 6 模式 + 兩點錨定 + Ctrl 鏡像 · `PieMenuScreen` 幾何與動畫 · `NodeCanvasScreen` 全套畫布 · `NodeCanvasUndoManager` · `IBinder`/`LivePreviewBridge`/`MutableRenderConfig` · `DeltaUndoManager` 差異模型 · `FdNetwork` 通道模式 + bounds-check-degrade · **`StructuralSafetyHud` 全套** · `ConstructionHudOverlay` 版面 + 指數平滑 · `DefaultMaterial` 12 材料（含 E、ν）· `LoadType` 6 型 + γ · AISC K 值表 + Euler 公式 · BENCHMARKS 閉合解 · `VoxelGrid` 10³/long[16]/128B 格式 + `buildVoxelGrid()` 幾何規則 · `NurbsExporter` 選項 · `ConstructionEventHandler` 的 `EventPriority.LOW`（讓物理先跑）

**REINTERFACE（設計對，契約要換）**
`FastDesignScreen` 三視角（+ 修 `gridWidth` bug + 選取要回傳伺服器）· `TransformGizmo`（渲染可用，輸入層要新建）· `ChiselToolScreen` 17 形狀（→ section 目錄）· `FdActionPacket`（→ 結構化 payload）· `PastePreviewSyncPacket`（→ member list）· Undo 涵蓋範圍（→ 世界 + 模型原子交易）· `PortType`（+ MEMBER/SECTION/JOINT/LOAD_CASE/SHELL，廢 `STRUCT`）· physics `load/` 7 節點（→ 6-DOF + load case）· `BeamElement` 全套公式（→ L 解耦、`y_max`→`c`、分軸、加扭矩）· 複合剛度（→ 變換截面法）· `SubBlockShape` 表（`STAIR_*`/`ARCH_*` 是手填估值需重算）

**CONTAMINATED**
`SupportPathAnalyzer` 加權 BFS 三判據（自承「偽真實力學」）· `LoadPathEngine` 支撐樹機制 · `ForceEquilibriumNode`/`CoarseFEMNode`/`SupportPathNode` · `ChiselState` 的 `CUSTOM` 全塊 fallback · `ConstructionHudOverlay` 的 `nodeStress` 純量與 `RC_NODE`/`ANCHOR_PILE` 二分

**OBSOLETE**
`UndoManager` 全量快照 · `BeamElement` 四大簡化假設 · `min(Ix,Iy)` 瓶頸取法 · `y_max=0.5` 硬編碼 · CTO「Java 近似 / TypeScript 精確 FEA」雙軌 · `MIGRATION-v0.3e-to-v0.4.md`（PFSF native ABI，唯一可留的是 additive-only semver contract 模式）
