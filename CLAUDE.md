# CLAUDE.md

Block Reality 開發指引。

> **本檔從空白撰寫，不是前身 `PFSF-CORE/CLAUDE.md` 的改寫。**
> 前身的不變式清單建立在純量勢場模型上，該模型已判定不成立（D-001）。
> 若發現本檔某條與前身雷同，那是巧合或錯誤，不是繼承。

## 專案

Minecraft Forge 的結構工程沙盒。真實工法 + 真實有限元素分析。

力學不在這個 process 裡跑。這個倉庫是 **Minecraft 側**：方塊、材料、工法狀態機、CAD 工具、渲染。
力學引擎在 process 之外的 `br-sidecar` 裡（D-013）。**現況（v0.3c）**：FrameCore v4 以 C++ 原始碼靜態連結，
走本倉庫自訂的 line-JSON + 共用記憶體協定（protocol 2，D-019）。**v0.4 目標（2026-09-02，D-041/D-043）**：
引擎 = **tectonic2**（原始碼靜態連結），介面 = **BSI v1**（`contract/`，與 tectonic2 逐位相同、雜湊釘死），
sidecar 退為傳輸轉接（stdio 門鈴 + 零複製 arena），FrameCore 保留為 CI 對數臂一個發布週期。
總綱在 `docs/SWAP_PROGRAM.md`。`frame_capi_v2` 從未接上（D-002/D-013 的實況修正），也不再是換裝方向。

## 力學模型（必讀，決定了所有其他事）

離散化是**桿件系統**，不是體素連續體：

- 每節點 **6 DOF**：`Ux Uy Uz Rx Ry Rz`
- 樑柱：Euler-Bernoulli，opt-in Timoshenko
- 板殼：**MITC4** 平面 facet（膜 + 板彎 + assumed covariant 剪切 + drilling penalty）
- 求解：稀疏直接法（supernodal Cholesky / LDLᵀ）。**沒有 multigrid，沒有迭代主解法器**

## 不變式

| # | 不變式 | 違反後果 |
|---|--------|---------|
| 1 | **構件是共線 run，不是單一方塊。** 一個 1m³ 方塊的細長比 L/h = 1，樑理論不成立 | 平截面假設失效，內力全錯 |
| 2 | **斷面與方塊尺寸解耦——適用於 frame 材料。** 一格「鋼骨」承載的是一個真實斷面（出貨目錄是實心矩形 `steel_rect_200x400` 之類；H-400 是同一意思的另一個例子），不是 1m×1m 實心。monolith 材料（混凝土/磚）是明確例外：一格就是 1 m³ 材料本身（D-030） | 巨柱效應，D/C 恆為 0.01，什麼都壓不垮 |
| 3 | **結構角色由玩家用材料宣告，不由程式從方塊堆反推** | 形狀語意辨識是未解問題；反推會產生無法解釋的模型 |
| 4 | **連通性分析只能當前篩，不能當權威。** 權威判定是因子代數（pivot ratio） | 連通 ≠ 穩定。機構會被判為安全 |
| 5 | **兩軌精度分離。** 顯示軌可 stale（rel ≤ 1e-5）；承諾軌不可（rel ≤ 1e-9） | 玩家看到的和實際判定的不一致 |
| 6 | **承諾軌的消費者：D/C 判定、崩塌觸發、netcode。** 這些永不吃顯示軌的值 | 崩塌不決定性，多人不同步 |
| 7 | **引擎邊界不洩漏元素詞彙。** Java 說方塊/材料/delta，不說節點/構件/斷面 | 換引擎要動 Minecraft 側 |
| 8 | client/server 分離：`client/` 下類別必須 `@OnlyIn(Dist.CLIENT)` | 伺服器載入 client 類別 → crash |

## 反不變式（前身有、本專案明確不採用）

這些是 `PFSF-CORE/CLAUDE.md` 的「不變式」條目。它們在純量勢場模型下成立，在本專案**全部作廢**。
列在這裡是為了讓人認得出來，避免從舊碼或舊文件無意間帶回。

- ❌ **σ_max 正規化**（`rcomp`/`rtens`/`source`/`conductivity` 同除、`maxPhi` 不除）
- ❌ **26 連通一致性**（stencil `EDGE_P=0.5`、`CORNER_P=1/6`）—— 26 連通在本專案只是拓撲前篩的鄰域定義，沒有物理意義
- ❌ **`hField` 寫入權**、相場損傷演化
- ❌ **每方塊一個純量應力**。FEA 的輸出是 per-member 六個內力分量 + D/C 模式，per-shell 是上下層各 5 點的應力張量
- ❌ **flux 比較 `rcomp`/`rtens` 的失效判準**
- ❌ **island = 26 連通體素集合**。本專案的分析單位是「一個結構模型（一個 K）」

## 兩條原則

**算的歸算，演的歸演。**
FEA 決定失效集合；`FallingBlockEntity`、粒子、碎塊只負責演出。演出層永遠不回頭影響判定。
（取自前身 `教育程式_需求與任務規劃_v3` 對「遊戲物理 vs 工程力學」的裁決。）

**玩法即離散化的合法性條件。**
玩家用材料宣告結構角色，因此「照工序蓋」不只是遊戲規則，同時保證了模型可解。
這是不變式 3 的正面說法——見 `docs/MEMBER_SEMANTICS.md` §2。

## 慣例

- Java 17、Forge 1.20.1、Official Mappings、UTF-8
- **一格 = 1 公尺**。前身文件從未明確宣告這條，卻是所有換算的前提
- 物理量用真實工程單位：強度 MPa、楊氏模量 GPa、密度 kg·m⁻³
- 公開 API 標註 `@Nonnull` / `@Nullable`
- 測試 JUnit 5

## 紀律

判準先凍。見 `docs/GATES.md`。

三條鐵則：

1. **判準在實作之前 commit。** 事後移線要在 `docs/GATES.md` 登記，並且下游結論至少降一級
2. **沒有 gate 執行過的能力，不得寫進能力清單。** 「檔案在」不算「有」
3. **輸格照登。** 量到比對照組差，寫進文件，不換臂、不換 fixture、不事後重詮釋

第三條是從 tectonic 的 `GATE_LINE_REGISTRY.md` 借來的，那份文件的建檔動機值得一讀：
單條事後重詮釋都有可辯理由，聚合起來是 gate 失去牙齒的簽名。

## 決策

所有架構決策記在 `docs/DECISIONS.md`，每條附**理由**與**否證條件**。
沒有否證條件的決策不是決策，是偏好。

## 契約

`contract/` 是 BSI v1 引擎介面契約，與 tectonic2 的 `contract/` **逐位相同**（`contract/CONTRACT_SHA256`）。
改介面 = 改 `contract/` + `python3 contract/check_contract.py --write` + **兩倉各自 commit 同一份**；只改一邊，另一邊 CI 紅（N23）。
Java 的 `BsiCodec` 與 sidecar 的傳輸轉接只能實作契約，不得自創欄位；要新欄位先改契約。
