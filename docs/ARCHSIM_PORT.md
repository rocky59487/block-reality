# ArchSim 移植清單

`architect_simulator` 已經在 Unreal 側實作並 gate 驗證了本專案要的整條營建鏈。
**這批東西是移植，不是重新設計。**

來源：commit message（40–60 行的完整設計論證）與 `docs/logs/S-15/research_R*.md`（五份 13–25 KB 工程研究報告）。
⚠️ 該倉庫的設計日誌**不在 issues**——8 個 issue 有 7 個是純 bug tracking。

---

## 1. 兩階段構造（AS-72-u2, `0c76ec0d`）

`Skeleton / Cured` 狀態機把玩家的放置旋鈕變成 solve 參數：

| 階段 | 斷面 |
|---|---|
| `Skeleton` 鋼骨 | 裸鋼骨吊裝驗算（E=200000 鋼材 + 薄壁 H 斷面） |
| `Cured` | transformed composite section — SRC（H 核 + 固定 500 包絡）或 RC（400 包絡、八支主筋、每支 `ρ·Ag/8`） |

**對 `MEMBER_SEMANTICS.md` §9.3 的重要修正**：

> A Skeleton REBAR cage **does not self-support**（R3 W1：slenderness buckling long before yield）— `BuildSolveView` 在 census 前 deactivate 那些 row，orphan grounding 讓它們的專屬節點 inertize，與 active member 共用的節點保持自由。

也就是：**鋼筋籠在未澆置前不是承載構件，但它已經宣告了 member 的存在與位置。** 這正是本專案要的語意，而且連 orphan node 的邊界情況都處理好了。

**Oracle catch 值得抄**：gate 第一次跑就抓到單位不一致——library 鋼材預設用 Eurocode `E=210000`，但整條 R3 鏈（SectionMath Es、所有模數比）是 ACI `E=200000`。

---

## 2. 三旋鈕與斷面（`research_R2_rebar_steel.md`）⭐

這份 13 KB 報告是「member 由鋼筋/鋼骨宣告 + 斷面與方塊尺寸解耦」的完整工程依據。

### 2.1 三個旋鈕

| 旋鈕 | 檔數 | 內容 |
|---|---|---|
| ① 種類 | 2 | 鋼筋 / 鋼骨 |
| ② 大小 | 6 | 鋼筋 D10–D36 / 鋼骨 H150–H400 |
| ③ 密度 | 4 | **配筋率 ρ = 1 / 2 / 4 / 6 %** |

附 **CNS 560 完整表**（D10–D36 標稱直徑、斷面積、單位重）與 **JIS G 3192 H 型鋼斷面性能表**（A、Ix、Iy）——可直接灌進 `Section`。

### 2.2 「密度」的正確抽象 ⭐

**不要讓玩家數支數，用配筋率 `ρ = As/Ag`。**

理由：ρ 與斷面大小解耦、可直接餵 FEA。支數 `n = ⌈ρ·Ag/A_bar⌉` 只是**視覺派生**。

保護層/間距不硬擋，只在 ρ 高到違反最小間距時軟警示 + 密度封頂——「這自然讓『大斷面才能配高 ρ』，是免費的真實感」。

### 2.3 構造系統湧現而非選單 ⭐

```
放鋼筋 → 澆              = RC
放鋼骨 →（不澆）          = S
放鋼骨 →（澆）            = SRC 核
鋼骨 + 鋼筋 → 澆          = 完整 SRC
```

材料旋鈕只需 2 檔，第三種構造系統靠**疊放湧現**。

### 2.4 複合斷面（§7）

```
A_tr = A_c + (n−1)·A_s
I_tr = I_c + (n−1)·[Σ(As_i·y_i²) + I_鋼核]
模數比 n = Es/Ec ≈ 7–9；取 E = Ec 則 EI = Ec·I_tr
```

> 放置器輸出的不是裸鋼筋 member，而是**參數包 `{種類, 斷面, ρ, 佈置}`**；澆築動作把它結算成一根複合 `Section`。

這句話一字不改就是本專案的規格。

---

## 3. 動詞 → FEA 映射（AS-76-u2, `04b9ecb3`）

| 真實接合 | 傳結構力？ | FEA 對應 | 動詞 |
|---|---|---|---|
| 鋼筋綁紮 | **否**（力靠混凝土握裹） | **不改 joint DOF** | 綑綁 |
| 鋼骨焊接 | 是（剛接） | `Release` 全 false | 焊接 |
| **鋼骨螺栓** | 是（鉸接） | 端彎矩釋放 | 螺栓 |

⚠️ **本專案的 `MEMBER_SEMANTICS.md` §7.6 漏了「螺栓」這個第三動詞。** 已補。

### Pre-flight 抓到的隱形前提

game solve **從來沒開過 member end releases**，所以焊接動詞本來會是個說謊的旋鈕。三件事一起落地：

1. solve 改 `bEnableReleases = true`
2. STEEL 在吊裝態註冊為**兩端彎矩釋放**（plastic-hinge DOF convention 4/5/10/11；扭轉/軸向保留）
3. 爪的焊接動詞翻 `bWelded` 改寫成剛接，capture-then-commit undo、失敗 rollback

### Oracle 設計 ⭐ 直接採用為驗收案例

PIE 測試 D11：一個**銷接**的鋼構 L 形（柱 + 梁、頂端載重）是**機構**，所以焊前 solve 奇異、利用率恆為 0。兩次焊接後 L 變成剛性懸臂，兩根構件都有真實 `D/C > 0`。

> **「Welding turns a mechanism into a structure」，用數值證明。**

「綁」是鋼筋組立標記：kind-guarded、idempotent，**model row 被釘住不得改動**（鐵絲不傳力，readout 也這麼說）。

---

## 4. 模板宣告 shell（AS-73-u2, `8c37cedb`）

`RegisterShellQuad`（4 個驗證過的 node、`Id == index`、完整 session invalidation + topology broadcast）與 `DeactivateShell`——註解直接寫「**the cure flow's structural entry**」。

三種模板動詞共用文法（畫 1–2 條 CV chain、RMB commit），MMB tap 循環 `Loft → Revolve → FourPoint`（gesture 中拒絕切換）。Revolve 的 seam 是重複頂點列，靠養護流程的 **1mm node weld** 閉合。

⚠️ **已記錄的 forward note：apex / seam-welded quads 必須跳過或三角化。** 這是 MITC4 的已知地雷。

FROZEN engine 的 `FrameModel.cpp:111 validate()` 被追認為 degenerate-shell backstop：重角 quad 在 `StartSession` 乾淨失敗，永不組裝垃圾。

---

## 5. 養護（AS-75, `c3955f3d`）

```
Complete → Setting (2s) → Hardening (4s，濕深灰 lerp 到淺灰) → Done
```

`Done` 時批次把每根歸屬構件（兩端點在濕料範圍 + 15cm margin）`SetMemberStage(Cured)`——鋼筋籠復活成 RC composite、裸鋼骨變 SRC——釋放濕混凝土載重，並**顯式 kick 一次 `RequestSolve`**。

### 濕混凝土載重的生命週期

`WetVolumeCm3 × 2400 kg/m³ × 9.81`，依長度分攤到該區的 STEEL 構件、每端一半，作為 **GLOBAL frame 節點載重**。

誠實記錄了為何不用 member-local UDL：
> member-local UDLs would force replicating the engine's localAxes convention game-side（convention-copy risk，honestly traded against end-load-vs-UDL bending fidelity）

本專案有真 6-DOF beam，可以改回 UDL，**但這個 trade-off 的推理要保留**。

### 三個必抄的細節

- **Void-pour rule**：任何會 broadcast 的外部模型變更（或 registry Reset）在 session 進行中都會取消該次澆置，並有 re-entrancy guard 過濾養護自己的 mutation
- 🔴 **Review BLOCKER**：F5 存檔在養護中會把 construction tail 永久化成 ~20 kN 的 authored load。**存檔必須剝離施工暫時載重**——本專案一定會踩的雷
- 「The cure keeps ticking through a stow — **concrete does not wait for the player**」

---

## 6. 拆模（AS-83, `dfa5d90e`）

一個按鍵三種語意：

| 語意 | 條件 | 後果 |
|---|---|---|
| `PlainRemoval` | 背後沒混凝土 | 舊行為 |
| `ProperStrip` | 已養護 | 模板卸下、混凝土露出 |
| `EarlyStrip` | **還在養護中** | **永久標記，該次澆置 session 作廢，`FinishCure` 永不執行，該區構件永遠達不到複合斷面** |

> The cost is **structural, not cosmetic**. The tint just tells the player which pour it happened to.

現實依據：臺北市工程施工規範第 03110 章——側模 12 小時、6m 梁底模 21 天。**遊戲保留規則、丟掉時鐘**（養護壓縮成秒級）。

按鍵刻意重用 X：「it is already 指哪刪哪 muscle memory, and stripping is the same gesture. The difference lives in **the outcome and the message**, not in a new key.」

### Adversarial review 改了行為 ⭐

一片模板同時貼到兩個混凝土體是**真的會發生**（共用中間板、兩側各一次澆置）。回傳第一個重疊會讓判定依賴 actor 迭代順序。

改成 `FindTouching` 回傳全部，規則變成「**任一綠面就是早拆，且它暴露的每個綠體都毀了**」——order-independent 且物理正確。

---

## 7. 工法從幾何 derive（AS-84, `7c4dfecb`）

> v0.9.0 could already BUILD both of Taiwan's two common structural methods. It just never said so. … **This unit changes 0 lines of mechanics.**

四個必須繼承的架構決策：

1. **一切都是 DERIVED，不存 method 欄位**——「a stored method could immediately disagree with the geometry, a derived one cannot」
2. **SC 的階梯故意比較短**（2 步 vs RC 的 5 步）——「a welded bare frame is a **FINISHED** structure, and a shared five-step ladder would have shown it forever as 2/5」
3. **Step 是「有證據的最後一步」（你在這裡），不是「下一步做什麼」**——後者會讓同一狀態變歧義（沒上模板的鋼筋籠同時是「還在綁筋」和「可以組模了」），**而 HUD 不能顯示歧義**
4. **Fail-closed 細節**：`ResolveMethodFor` 從 `bFormworkNear = TRUE` 起算——**不是宣稱模板存在，而是拒絕為沒人看過的東西背書**

三條工序階梯（可直接搬進 HUD）：

```
RC   綁筋 → 組模 → 澆置 → 養護 → 拆模
SC   吊裝 → 焊接(剛接)
SRC  吊裝 → 焊接 → 外包 → 組模 → 澆置 → 養護 → 拆模
```

---

## 8. 骨架期兩階段驗算（`research_R1_concrete.md`）⭐

標為「引擎原生、最高價值之一」。

> RC 構件在硬化前，**混凝土完全不出力**；此時所有重量由鋼筋籠/鋼骨 + 模板 + 支撐承擔。

第一階段是一次獨立線彈性驗算，載重來自 ACI 347：

| 載重 | 值 |
|---|---|
| 濕混凝土死重 | 23.6 kN/m³ |
| 施工活載（最小） | 2.4 kPa |
| 活 + 靜組合（最小） | 4.8 kPa |
| 機動推車 | 6.0 kPa |
| 側向力 | 總靜載 2% 或板邊 1.5 kN/m 取大 |
| 模板支撐安全係數 | ≥ 1.5 |

> **「骨架若在此階段就超應力，代表『還沒硬就先垮』」**

真實、戲劇性，且與 6-DOF frame FEA 零阻抗接合。**這驗證了 `MEMBER_SEMANTICS.md` §9.1 的推論。**

### 模板側壓（旗艦機制候選）

新拌混凝土初凝前近似液體，`p = γh`，γ ≈ 23.6 kN/m³。3m 深 ≈ 70 kPa。
ACI 347 折減（SI 近似）：`p_max = Cw·Cc·[7.2 + 785R/(T+18)]` kPa。

**關鍵直覺：澆得越快（R↑）、溫度越低（T↓）側壓越高 → 越容易爆模。**

設計建議：澆置速率是玩家旋鈕（快 = 省時但爆模風險、慢 = 安全但耗時），每片模板有承壓上限，局部超限 → 漏漿（小）→ 爆模（大）。硬核模式用 ACI 式，休閒模式用 `p=γh` + 固定門檻。

### 明確裁決「該略過的」

- **養護化學細節**——「不可見、做細拖沓」→ 抽象成硬化計時器上一個係數（有養護 100% / 沒養護封頂 70–80% + 表面裂紋）
- **搗實/振動棒微操**——「FPS 手感差、視覺回報低」→ 一條品質 bar + 未搗實區出蜂窩瑕疵

強度曲線 `f'c(t) = f'c · t/(4.00 + 0.85t)`（ACI 209）。拆模門檻 70%、加載門檻分開。

---

## 9. 互動設計（`research_R5_interaction.md`）⭐

25 KB，含**從原始碼直接盤點的輸入預算表**（附 file:line）——這個方法本身值得抄。

核心論點：
> v2 不是「找新鍵」，而是**靠工具模態切分輸入空間 + 常駐 HUD 讓當前模態/參數永遠可見**。**可見性不是點綴，是讓超載鍵位系統可用的承重結構。**

### 調參 UI

主推「修飾鍵 + 滾輪」快調（大小/密度是高頻連續值）+ 選配長按 radial 當種類的發現性入口。

🔴 **承重前提：準星旁必須常駐「種類 | 大小 | 密度」即時讀數。沒有這條讀數，方案不可 ship。**

### 四狀態視覺語言 — 形狀 + 材質 + 顏色 + 描邊四重冗餘

無障礙原則：**不要只靠顏色**。

| 狀態 | 剪影 | 材質 & 顏色 | 附加線索 |
|---|---|---|---|
| 骨架 | 細桿、鏤空、看得見內部 | 鋼筋 = 鏽橘 / 鋼骨 = 工程藍灰 | **FEA 熱圖仍可疊在骨架上** |
| 已上模板 | 被木/膠合板方盒包住，體積變實 | 木紋米黃、霧面 | 接縫線；半透明可瞥見內部鋼筋 |
| 澆灌中 | 模板內體積由下往上長 | **濕深灰**、微濕潤高光 | 百分比/進度環 |
| 硬化完成 | 實心飽滿 | **乾淺灰**（真實固化變淺）、霧面 | 完成脈衝 + ding |

⚠️ **合法性色（藍 = 可放 / 紅 = 硬阻擋 / 黃 = 軟間隙）必須與階段色分軌**，只在放置/瞄準時疊加，放好即撤——否則玩家會把「這步的材質」誤讀成「這裡不能放」。

### 澆置節奏

借 PowerWash Simulator / Construction Simulator：按住澆不要點擊澆；一個模板 = 一個澆灌單元；**噴到模板外的料不扣有效進度，絕不扣資源逼玩家重刷**；完成門檻調鬆不要逼玩家找最後 2%；節奏旋鈕是「澆一個模板要多久」而非懲罰。

### 教學

常駐「骨架 › 模板 › 澆灌 › 硬化」麵包屑 step tracker；情境提示綁工具裝備（JIT，不開場塞手冊）；首玩用零代價沙盒單元格走完整條鏈。

> **引導玩家去看熱圖**（「支承放好了，看熱圖從紅轉藍代表結構穩了」），**把驗算變成教學的一部分而非隱藏數據。**

---

## 10. 播測回饋（AS-81 / AS-80）

**產品層級的使用者授權，直接引用**：
> **all innovation budget went to the mechanics engine — the experience layer builds on PROVEN game idioms.**

三則真實回饋與修法：

- 「radial 很難用」→ 累積 aim **clamp 到 96px**（有界的反向滑動一定能翻選擇；不 clamp 時 overshoot 歷史會埋掉它）+ **sticky pick**（deadzone 重入保留上次高亮）+ hold 門檻 0.35s → 0.25s。clamp 數學讓 aim 自然 **orbit**
- 「怎麼放模板？好像沒有工具」→ 工具 radial 第 4 槽本來是「收起」（與 tap-B 重複），改成直接裝備。**發現性問題要用槽位解，不是用教學解**
- 「為什麼它自己彎了？」→ cardinal spline 取樣把玩家畫的直角意圖弄彎了；改成 WYSIWYG polyline，`FourPoint` 成為預設動詞

---

## 11. HUD（AS-88, `055a9d56`）

把七行 `AddOnScreenDebugMessage` 全數驅逐。**生產程式碼中該呼叫數 = 0 被當成驗收指標。**

- 工法工序 = 右上角 **pip panel**：方法名、Step/Count、每步一個 pip（done/current/pending）
- **「工序完成」時全 pip 亮起且沒有 current**——因為「完成」不能讀起來像「還有一步在手上」
- 狀態每幀從 `PlayerTick` **推送一次**（綁 Slate attribute 會讓每個 pip 每幀重探世界），**推送未變更的狀態不重建任何東西**，且有 rebuild counter 讓 gate 釘住這點
- ⚠️ `FText::AsNumber` 預設千分位會把 `3000 mm` 悄悄變成 `3,000 mm`——**CAD readout 是一個你會重打的數字**，關掉 grouping
- 靜態鍵位提示移到自己的 widget：「a fact that never changes has no business in a string rebuilt per frame」

---

## 12. 已診斷的地雷（AVOID）

### 12.1 正方形斷面會隱藏座標系錯誤 🔴 最高優先

`PFSF-CORE` #71：同一份 Manifest 產生三種不同的載重方向（FrameCore 負 Z、PFSF 只保留純量大小、BM-MSA 固定注入負 Y）。

> **現有樑沿 X 軸且截面為正方形，負 Y／負 Z 都可能得到相同 root region，讓 smoke test 在座標錯誤下仍命中。**

驗收條款可直接抄：
> 非正方形截面樑：同一樑分別施加 −Y／−Z，兩端必須選用對應彎曲軸，**結果不得因錯誤軸對稱而相同**。

同類：`architect_simulator` #3（`ElementRemovalTest` 用 `sec.Iz` 而非 `sec.Iy` 作閉合解參考，非正方形斷面時錯誤）與 AS-72-u2 的 review NIT（`Cy/Cz` pairing 對調，dormant 因為所有 v2 包絡都是正方形，**第一個非正方形斷面會讓弱軸 D/C 差 2 倍**）。

> **本專案「斷面與方塊尺寸解耦」意味著非正方形斷面是常態。第一批 fixture 就要全用非正方形斷面。**

### 12.2 從形狀推斷斷面的墓誌銘

`PFSF-CORE` #42「由 voxel geometry 建立 local section proxy」——2026-07-13 開到現在**從未完成**，而且是 ROADMAP M4 的阻擋項、也是 physical claim 無法恢復的原因。

**這反證了 D-010：斷面應該由玩家宣告，不是從 block shape 推斷。**

但 #42 有一個做法值得抄：**保留 `UNIT_VOXEL` 作為 ablation baseline / fail-closed fallback，不得靜默猜測。**

### 12.3 材料屬性不可互相推導

`block-reality-api` CHANGELOG v1.1.0：**鋼材 E 從 350 GPa 修正為 200 GPa（近似偏差 75%）**——原因是用 `Rcomp × 1e9` 當楊氏模量。木材偏差 50%。

**材料屬性必須各自獨立來源（Eurocode 2 / AISC / EN 338），不能互相推導。**

同版另一條值得抄：蜂窩判定從 `Math.random()` 改為 **FNV-1a 確定性 hash**（基於雙方 `BlockPos`），保證伺服器重啟後結果一致、跨執行緒安全、自動化測試可重現。

### 12.4 真 FEA 的具體 bug（`architect_simulator` #1–#7）

- **#1 / #4** `envelope()` 未傳播 `singular` 旗標 → **靜默垃圾包絡**；呼叫端未檢查導致 UB
- **#2** 非作用構件的 `memberForces[e].member` 保持預設 0，`combine()` 傳播錯誤 ID
- **#7** 非作用構件的力歸零斷言**由零初始化通過，而非由計算驗證**（假綠測試）
- **#6** 容許誤差不一致（1e-9 vs 1e-6）

### 12.5 gate 盲點（v0.10.0 release 自白）

1. Repo 從 AS-85 起就**無法打包**，連續四個「6-leg gate 全綠」的 unit 都建在打不出包的樹上——因為 gate 沒有 game-target compile leg，「now **fired four times**」
2. Esc 能開暫停選單但永遠關不掉——作者自承是**自己的假綠**：「test drove `TogglePauseMenu()` directly, so the state machine was correct and **the key never reached it**」
3. `EXPECTED_TESTS` 釘在 135（實際 186）兩個 sprint——**「a pin that lives in no gate never fires」**

文件紀律：ARCHITECTURE_INDEX 過期四個 minor 版本時，選擇加**醒目的過期橫幅**而非假裝更新——**「a map that misleads a subagent is worse than no map」**。

---

## 13. 移植優先序

| 優先 | 項目 | 來源 |
|---|---|---|
| 1 | 三旋鈕 + CNS 560 / JIS G 3192 斷面表 + ρ 抽象 | R2 |
| 2 | `Skeleton / Cured` 兩階段 + 複合斷面結算 | AS-72-u2, R2 §7 |
| 3 | 動詞三件套（綁/焊/螺栓）+ 機構→結構 oracle | AS-76-u2 |
| 4 | 模板宣告 shell + degenerate-quad backstop | AS-73-u2 |
| 5 | 養護狀態機 + 濕混凝土載重生命週期 + 存檔剝離 | AS-75 |
| 6 | 拆模三語意 + order-independent 判定 | AS-83 |
| 7 | 工法 derive + step 語意 + pip panel | AS-84, AS-88 |
| 8 | 骨架期兩階段驗算 + ACI 347 施工載重 | R1 |
| 9 | 四狀態視覺語言 + 常駐讀數 | R5 |
| 10 | 模板側壓 / 爆模（旗艦機制候選） | R1 |
