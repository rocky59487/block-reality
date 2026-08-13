# 決策紀錄

倒序排列（最新在上）。每條的格式：**決定 / 理由 / 否證條件**。

沒有否證條件的不是決策，是偏好。否證條件成立時，這條決策要重新裁決，不是靜默沿用。

---

## D-009 · 本倉庫的 CLAUDE.md 從空白撰寫

**決定**：`CLAUDE.md` 不從 `PFSF-CORE/CLAUDE.md` 改寫。舊檔的「不變式」章節整章作廢，並在新檔以「反不變式」形式列出，供辨識用。

**理由**：舊不變式（σ_max 正規化、26 連通一致性、hField 寫入權、maxPhi 例外）全部建立在純量勢場模型上。以 diff 方式改寫會保留條目結構，使錯誤的約定以「不變式」的權威等級被繼承。

**否證條件**：無。這是一次性的清理決定。

---

## D-008 · 建築尺度不需要增量求解

**決定**：不移植 Woodbury ladder / LiveSession / stale-factor 迭代。放新方塊觸發**全量重分解**，在 worker thread 上跑，結果延遲數 tick 落地。`ReSolveSession`（同拓撲 Woodbury）只用於開關既有構件。

**理由**：15,360 DOF 的完整管線實測約 108 ms（`tectonic2/docs/ROADMAP.md`：compile 17 + assemble 4 + analyze 18 + factor 64 + solve 4 + recover 1）。單棟建築約 2k–20k DOF，落在這個量級。整套增量機制的存在理由是 100k–1M DOF 的世界尺度，本專案不在那個尺度。

同時得到一個乾淨的時機切分：**建造時可以慢**（加節點 → 全量重分解，async），**崩塌時要即時**（開關既有構件 → 精確 Woodbury，毫秒級）。

**否證條件**：實測單棟建築超過 50k DOF，或全量重分解在目標硬體上超過 500 ms。屆時重新評估 LiveSession（`tectonic/core/Private/LiveSolver.cpp`，3,468 行，bordered Schur 處理新節點，代數精確）。

---

## D-007 · 兩軌精度分離

**決定**：顯示軌（熱圖、互動回饋）容許 stale，rel ≤ 1e-5。承諾軌（D/C 判定、崩塌觸發、netcode）不容許，rel ≤ 1e-9。兩軌共用同一個 factor，差別只在允許多少 staleness。

**理由**：取自 FrameCore `LiveSolver` 的既有設計（`commitSolve()` 強制全精度 DP lane）。它比「每 N tick 同步一次」精確且便宜——後者的誤差無界且不可稽核。

**否證條件**：顯示軌與承諾軌出現玩家可察覺的不一致（熱圖顯示安全但結構倒塌）。屆時收緊顯示軌或改為同軌。

---

## D-006 · 引擎邊界：Java 說方塊，C++ 擁有模型

**決定**：Java 側只傳「方塊 + 材料 + 支承 + 增刪 delta」，不知道節點、構件、斷面。C++ 側負責構件抽取、節點管理、Session 生命週期。回傳只有 per-block/per-member 的 D/C、內力、失效事件。

**理由**：若 Java 側自行抽構件並傳 `Model{nodes, members, shells}`，就把 frame 抽象寫死在 Minecraft 側，換離散化要動遊戲碼。這個切法也剛好對齊 `Session::edit` 的 dirty-assembly 語意——送出的就是引擎要的 delta。

唯一必然的耦合是「材料語意詞彙表」（什麼是鋼骨、什麼是澆置後的板）。那是產品概念不是求解器概念，換引擎時不變。

**否證條件**：構件抽取需要 Minecraft 側才有的資訊（例如玩家意圖、方塊 NBT 的細節），無法在 C++ 側完成。

詳見 `docs/ENGINE_BOUNDARY.md`。

---

## D-005 · 板殼用 MITC4，不用格梁化

**決定**：樓板與牆用 MITC4 平面 facet 元素。不採用 Hambly 格梁化（用交錯桿件表達板）。

**理由**：`tectonic/core/Public/FrameCore/Grillage.h:20-22` 逐字：「Only the OUT-OF-PLANE action is physical, so **the in-plane DOFs (Ux, Uy, Rz) are restrained at every node**」。格梁化在每個節點固定面內自由度，**完全無法承受面內荷載**——而剪力牆的荷載路徑正是面內對角剪力，那是建築物抵抗側向力的主要機制。

另三個問題：格梁化不收斂到精確板值（自承 "does NOT converge"）、橫向彎矩已知高估、在 codebase 裡的實際形態是矩形板 fixture 產生器而非可編織進任意幾何的元素。

「Minecraft 無曲面所以 MITC4 浪費」是誤解：MITC4 本來就是**平面 facet**（節點投影到最佳擬合平面），從不處理曲面。它買到的是不剪力鎖死、膜作用、扭矩 Mxy、Poisson 耦合。成本上也不虧——一格一個元素掛在既有 4 節點，**不增加任何 DOF**。

**否證條件**：實測顯示 MITC4 的稀疏樣式（4 角全耦合含對角配對）在目標規模造成不可接受的 fill-in，且結構中無承受面內荷載的牆。

---

## D-004 · 斷面與方塊尺寸解耦

**決定**：方塊是斷面的**載體**不是斷面本身。一格「鋼骨」承載一個真實斷面（H 型鋼 / IPE / HEA / Box / Pipe），由材料與玩家選擇決定，與 1m 格距無關。

**理由**：1m×1m 實心鋼柱在真實工程裡是誇張的巨柱，D/C 恆為 0.01，任何結構都不會失效，遊戲性歸零。FrameCore / ArchSim 的 `SectionLibrary` 本來就把 `Section` 與幾何分離（GB H 型鋼 / EU IPE、HEA、HEB / US W / Box / Pipe / Channel / Angle）。

**否證條件**：玩家對「一格 = 一個看不見的小斷面」產生認知落差且無法用視覺化解決。

---

## D-003 · 結構角色由材料宣告，不由程式反推

**決定**：玩家放鋼骨/鋼筋/繩索 → 桿件；架模板灌漿 → 板殼。**不做**從任意方塊堆反推「哪些是柱、哪些是梁」。

**理由**：從方塊堆反推結構角色是形狀語意問題不是力學問題，三個既有倉庫都沒有解。用材料宣告把它消滅而非繞過，而且對應真實工程（結構圖說本來就標明構件）。這也讓工法玩法與離散化合法性成為同一件事。

**代價（明確接受）**：放棄「對任意原版方塊建築出應力」。應力眼鏡只作用於用結構材料蓋的東西。原版方塊最多得到連通性層級的回饋。

**否證條件**：玩家強烈期待對既有存檔建築出應力，且該期待無法用「轉換成結構材料」的遊戲流程滿足。

---

## D-002 · 力學引擎 = FrameCore v4，透過 frame_capi_v2

**決定**：對著 `frame_capi_v2` 的 C ABI 開發。實作端先用 FrameCore v4.0.0（frozen）。

**理由**：三個候選中，FrameCore v4 是**唯一同時具備完整能力與 out-of-process C ABI** 的。

| | FrameCore v4 | tectonic 1 | tectonic 2 |
|---|---|---|---|
| 崩塌/碎塊/塑鉸/挫屈/模態 | ✅ | ✅ | ❌ 版圖 v3 |
| StressField / Fragment 慣性張量 | ✅ | ✅ | ❌ |
| 加/刪節點增量 | ❌ | ✅ LiveSession | ❌ 版圖 v2 |
| out-of-process C ABI | ✅ | ❌ 無 capi/Standalone | ❌ |
| 倉庫狀態 | FROZEN v4.0.0 | FROZEN 裁判艙 | 活躍 |

tectonic 2 的 capability 清單只有 26 條，且 `operator:「先不用管接到遊戲的」`（ROADMAP v4）。而 tectonic 2 v4 的完成定義逐字是「對 FrameCore v4.0.0 的系統級全面對數 → FrameCore 退場」——**它在複製完 FrameCore 之前無法取代 FrameCore**，因此 FrameCore 短期內不會消失。

`frame_capi_v2` 的形狀（不透明 handle、單調整數 ABI 版本 + 兩 minor 相容 SLA、JSON header + raw LE double payload、`transport.async` 非阻塞排隊）本來就是為 out-of-process 客戶端設計的，且已有 C# SDK 可作為 Java binding 的參考實作。

**已知缺口**：`frame_capi_v2` 的 dispatcher **沒有暴露 LiveSession**（只有 `analysis.reanalysis_solve` = 同拓撲 ReSolveSession）。在 D-008 之下這不構成阻礙。

**否證條件**：tectonic 2 到達 v4（系統級對數完成），或 FrameCore v4 出現無法自行修復的缺陷（上游 frozen，不會修）。屆時對同一條 wire 換實作。

---

## D-001 · PFSF 退場

**決定**：不使用 PFSF 及其任何衍生（LPBC、BM-MSA、shear tensor、向量場求解器）。力學層完全重做。

**理由**：PFSF 每體素只有 1 個純量自由度 φ，解的是穩態各向同性擴散方程 `−∇·(σ∇φ) = ρ`，不是 3-DOF 位移場的線彈性方程。純量場沒有位移向量、應變張量、應力張量、轉動自由度，因此**結構上不可能表示彎矩、剪力、Poisson 耦合或力矩平衡**——`∇×(∇φ) ≡ 0`。

它確實迭代到全域平衡（RBGS + Chebyshev + V-Cycle + PCG），所以失敗類型是「正確地迭代了一個錯誤的算子」，不是「沒迭代」。

實測誤差：懸臂 22.09%、拱 22.08%、板 72.49%。且懸臂的錯法是系統性的——失效判準的 `flux` 恆等於軸向平均 `N/A`，**與截面深度 h 無關**，真值是 `6ρgL²/h²`。

另有四個獨立次級缺陷（算子在異質 σ 下非對稱 14–28%、CG 內部 clamp 破壞共軛性、對角耦合不檢查空氣造成凹角虛擬接地、production 預設下 σ 退化為全域常數）。

**獨立確認**：淨室重寫版 `PFSF-engine` 的 `CLAIM_MATRIX.md` 把 `FEM, displacement, stress tensor` 列為 "Unsupported and prohibited"，`KNOWN_LIMITATIONS.md` 說 risk 是 "dimensionless scalar surrogate, not physical stress"，連「scalar risk 能預測失效區域」都標成 "Not evaluated"。`final-gate/PHYSICAL_GATE_QUARANTINE.json` 的 `required_physics_verdict` 是 `INVALID_EVIDENCE`。

**否證條件**：無。這是數學事實不是工程權衡。
