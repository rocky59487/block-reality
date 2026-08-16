# 決策紀錄

倒序排列（最新在上）。每條的格式：**決定 / 理由 / 否證條件**。

沒有否證條件的不是決策，是偏好。否證條件成立時，這條決策要重新裁決，不是靜默沿用。

---

## D-017 · 一個世界有很多結構，各解各的

**決定**：擷取出來的元素先依**連通分量**分割，每一棟各自組一個 `FrameModel`、各自求解。
`singular` 的語意隨之改成「**至少有一棟**是機構」，並附上 `islands` / `singularIslands`
兩個計數；健全那幾棟的結果照常回報。

**理由**：兩棟不相連的建築沒有共同的平衡方程。把它們組進同一個勁度矩陣，唯一綁在一起
的是**命運**——維度裡任何地方有一根沒支承的樑，全域矩陣就秩虧、LDLT 分解失敗，於是
**整個世界每一棟建築都回報不出東西**。

這不是推論，是使用者在遊戲裡回報「多個不同的建築出現會導致模組失效」之後重現出來的：
一根支承良好的懸臂單獨解 D/C = 0.02640；在一百格外放一根沒支承的樑，同一根懸臂就回報
`singular` 且完全沒有結果。

順帶是效能上的淨賺而非取捨：直接分解的成本對模型大小是超線性的，所以 N 個小模型比一個
N 倍大的模型**便宜**——與「批次處理通常比較快」的直覺相反。

一個必須全域做、不能下放到各島的檢查：**荷載落點**。若讓每一島各自判斷「這個荷載不是
我的」而跳過，一個誰都不認領的荷載就會靜靜消失而回覆仍然 `ok`——正是 issue #14 要防的
那種「安靜地報平安」。所以荷載在分割**之前**就對全體節點集合驗過。

**否證條件**：出現一種必須跨越不相連結構的耦合（例如共用地盤的土壤互制），使兩棟沒有共
用節點的建築仍需要一起解。屆時把耦合明確建模成元素，而不是靠把它們塞進同一個矩陣。

---

## D-016 · 元素種類由 token 宣告，不由幾何猜

**決定**：方塊上的 token 決定它成為哪一種元素。板 token（`concrete_slab_200` 等）是殼
facet，斷面 token（`steel_rect_200x400` 等）是樑。兩組不重疊，認不得的 token 直接拒絕。

**理由**：讓幾何去猜的代價是可以量的。一片用**樑 token** 鋪出來的樓板會被擷取成**格梁**
——每個方塊同時屬於一條 X 向 run 與一條 Z 向 run，於是自重施加兩次、斷面勁度也算兩次，
而回覆裡沒有任何一個字說明這件事。由 token 宣告，兩種情況就**由構造上可分辨**：同樣一片
平面，板 token 是板，樑 token 是格梁，而格梁是一個合法的模型，不是錯誤。

同一條規則也讓「疊兩層的板」有話可說：它是**實心**，不是殼。切成三組互相穿插的面會讓質量
與勁度都變三倍，所以那些方塊回報 `unassigned` 而不是默默被鋪成網格。

厚度跟著 token 走，與方塊尺寸解耦（D-004 同一條精神）：一格一公尺的方塊宣告 200 mm 的板。

**連接方式**：run 允許**支承於**它撞上的板方塊——把 run 延伸一格進去，於是柱頂節點**就是**
板的節點。只提供這一種耦合；單純貼著板邊跑的樑不算接上去，因為幾何裡沒有任何東西說它應該
接上去。

**否證條件**：出現一種常見的結構意圖，其樑／板之分無法由玩家放置的 token 表達（例如同一
種方塊既要當樓板又要當邊梁）。屆時加的是**方塊狀態上的顯式切換**，不是形狀推論。

---

## D-015 · api 與 core 完全不碰 Minecraft 型別

**決定**：`mod/api` 與 `mod/core` 是**純 Java**，不 import 任何 `net.minecraft.*` / `net.minecraftforge.*`。幾何用自己的 `Vec3d` / `BlockKey` / `Aabb`，維度用字串 id。Forge 層在自己的邊界轉換，只轉一次。

`API_ARCHITECTURE.md` §1 原本允許 api 使用 `BlockPos`、`ResourceKey<Level>` 這類 vanilla 值型別。**這條比它更嚴。**

**理由**：`gradle test` 能在任何純 JDK 環境跑完整條線——包含**實際啟動 `br-sidecar` 子程序、送真的求解請求、把回來的應力場對閉合解**。論文的可重現性掛在這件事上：審稿人不需要 Minecraft 就能複現引擎邊界的數值。

如果 api 依賴 `BlockPos`，那條線就要拖進整個 ForgeGradle 工具鏈——反編譯、re-obf、資產下載——而那是**沒有網路就跑不起來的**。

代價很小：`BlockKey` 是三個 int 的 record，轉換集中在 `StructureManager.snapshot` 一個地方。

由 `:api:checkApiPurity` 這個 build gate 強制。它會抓 impl import、Minecraft import 與世界寫入呼叫（`setBlock` / `destroyBlock` / `addFreshEntity`）。**已實測會失敗**——塞一個 `import net.minecraft.core.BlockPos;` 進去，建置立刻紅。一個不會失敗的 gate 不是 gate。

**否證條件**：出現一個必須放在 api 層、又非得用 Minecraft 型別表達的資料型別。屆時把它下放到 Forge 層，而不是放寬 gate。

---

## D-014 · 第三方 mod 只能做特效，不能寫世界

**決定**：碎塊 SPI（`IFractureEffectHandler`）在 Block Reality **完成世界變更之後**才呼叫，收到的是一份**描述**，回傳值被忽略，例外被捕捉並停用該 handler。

`CRUSHING` 走 handler；`FRACTURE` 產生持久剛體 member，是 Block Reality 自己的生命週期，不外包。

**理由**：Issue #2 已定壓碎碎片是**純視覺**——不造成傷害、不成為持久物件、不可回收、不污染存檔。所以 handler 本來就只需要描述。**權限剛好等於需求，沒有多給。**

反面代價明確：第三方無法做「依真實方塊形狀的物理碎裂」。接受——因為那個效果不值得換來「第三方 bug 可以損壞玩家存檔」。

**否證條件**：出現一個第三方碎裂效果，其品質差異大到玩家明顯有感，且無法用描述式契約達成。屆時評估分兩級（設定檔白名單授權接管方塊）。

---

## D-013 · 力學引擎跑在獨立 process

**決定**：FrameCore 以 **sidecar 子程序**形式執行，走 `frame_capi_v2` 的 stdio 協定。不用 JNI / Panama 載進 JVM。

**理由**：引擎是 C++。同 process 時**一次 segfault = 整個伺服器死 + 存檔可能損壞**——這對要給別人裝的 mod 是不可接受的失敗模式。

而分析本來就是背景跑的（D-008：建築尺度約 100ms），**IPC 成本相對 100ms 可忽略**。走 stdio 而非 TCP，避開埠衝突與防火牆。

**失敗語意全部 fail-safe**：sidecar 沒安裝 → mod 正常載入、分析停用、明確提示（不是 crash）；分析中 crash → 該次請求失敗、**member 狀態完全不變**、指數退避重啟；版本不符 → fail closed 拒絕綁定。

**最重要的不變式：分析失敗絕不改動世界。結果只在成功時套用。**

**代價**：要管子程序的啟動、關閉、僵屍清理，三個作業系統都要測。防僵屍用「子程序監看 stdin EOF」。

**否證條件**：實測 IPC 往返成本佔單次分析比例超過 20%，或子程序管理在某平台無法可靠實作。屆時評估 in-process 作為 opt-in（PFSF-CORE 的 JNI 載入骨架可照抄形狀）。

---

## D-012 · 材料表以 `DefaultMaterial` 為基底

**決定**：以 `block-realityapi-fast-design` 的 `DefaultMaterial`（12 種）為權威材料表，補上 `教學/08-system-integration.md` 的材料分項係數 `γ_m`。衝突值以 `DefaultMaterial` 為準。

**理由**：它有 Poisson ratio（FEA 必需）、有來源標註（Eurocode 2 EN 1992 / AISC / GB 50010、50017），而且有幾個刻意的設計決定值得保留——`BEDROCK` 用有限大常數 `1e15` 而非 `Float.MAX_VALUE`（避免 `Infinity` 污染）、`SAND` `Rtens = 0` 表示無法懸臂、`TIMBER` `Rtens(8) > Rcomp(5)` 反映木材真實特性。

必須在寫第一個 fixture 之前完成合併，否則驗收 oracle 會建在錯的數字上。

⚠️ 已知待複查的衝突：混凝土 `E`（25 vs 30 GPa）、鋼 `Rcomp`（350 vs 250 MPa）、`REBAR` `Rcomp`（250 vs 400）、`STONE` `Rcomp`（30 vs 100）、`GLASS` `Rtens`（0.5 vs 30）。另有 ArchSim 抓到的鋼 `E` 單位不一致（Eurocode 210 GPa vs ACI 200 GPa）——**選一個並在全鏈釘死**。

**否證條件**：逐項複查發現 `DefaultMaterial` 的某個值與其宣稱的規範來源不符。屆時該項改用規範值，並記在本條下。

---

## D-011 · member 是持久物件，方塊是它的視覺表現

**決定**：member 有 registry 與穩定 ID。方塊的放置決定 member **何時誕生**，誕生之後 member 有自己的身分、損傷歷史與 lineage。

這解決了 D-010（純推導）與 Issue #3/#4/#6/#8（持久身分）的衝突。

**理由**：Issue #8 的**質量守恆**（`子A + 子B + 切割損耗 = 父`）與 **transaction ID** 需要一個能「原子性退役」的實體，那必須是持久的。`DamageRecord[]` 也需要一個能跟著走的宿主。

**D-010 的核心洞見在此之下仍然成立**——鋼筋／鋼骨的放置決定 member 何時誕生，「把柱子外面的混凝土拆掉，member 拓撲不變」這個檢驗依然通過。

**與 D-003 的關係（重要）**：D-003 記了一條約束「結構角色不得成為持久化格式的必填欄位」。這條**仍然成立**，因為兩者存在不同地方：

| 存在哪裡 | 內容 |
|---|---|
| **藍圖（設計檔）** | 幾何 + 材料。**不存 member ID、不存損傷** |
| **世界存檔** | member registry（id → 方塊集合、斷面、`DamageRecord[]`、lineage） |

推論：**把藍圖貼進新世界會產生全新的、無損傷的 member。** 這是正確的——藍圖是設計，不是那一棟蓋過的建築。

**對自由模式（D-003 長期目標）的要求**：推導負責**建立與更新** member，不負責每幀重新發明它們。也就是自動辨識也必須能給出穩定 ID。

**否證條件**：實測顯示 member registry 在大型世界造成不可接受的存檔膨脹或載入延遲。屆時評估選項 C（幾何指紋比對的 identity registry）。

---

## D-010 · 結構模型來自「骨」不是「肉」

**決定**：鋼筋與鋼骨定義 member，模板定義 shell，混凝土兩者都不定義——它只改變外觀與斷面性質。接合剛性由**接法**決定（焊接 → 剛接；綁紮 → 定位不傳彎矩；綁紮 + 澆置養護 → `RC_NODE` 剛接）。節點合併零容差，整數座標完全相等；對角接觸不構成連接。

**理由**：與真實 RC 一致——配筋圖就是結構模型，混凝土是受壓元件與保護層。把柱子外面的混凝土拆掉，member 拓撲不變。

這個切法同時解掉四件事：構件擷取不需要形狀語意辨識（D-003 的具體落地）；斷面自然是 member 層級屬性；T 型交會的語意明確（兩根共用節點，不是一根彎折）；而「綁完鋼筋結構還是軟的，澆置養護完成才剛接」是力學上為真且教學價值極高的一課。

零容差的理由是決定論：整數格點給零浮點歧義，任何容差都引入歧義，而歧義破壞多人同步的前提。

**否證條件**：玩家強烈期待「只放混凝土就能蓋出結構」而不願意先配筋。屆時需要 §9.3 的素混凝土規則升級為主要路徑，而非補充規則。

詳見 `docs/MEMBER_SEMANTICS.md` §7。

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

**補記（Drive digest 之後）**：這個機制**前身設計裡已經有了**——Node 報告 `B3-1 ShapeSelector` 從 10³ 體素造型自動輸出 `A / Ix / Iy / Wx / Wy`，`B3-2 CustomShape` 有內嵌體素編輯器。不需要新設計，要做的是補 `Iz` / `J` / 剪力面積 / 主軸方向，並刪除同報告 `C1-3` 的 `blockSectionModulus = 1/6`（那正是「方塊即斷面」假設的明確出處）。詳見 `docs/MEMBER_SEMANTICS.md` §3。

**否證條件**：玩家對「一格 = 一個看不見的小斷面」產生認知落差且無法用視覺化解決。

---

## D-003 · 結構角色由材料宣告，不由程式反推

**決定**：玩家放鋼骨/鋼筋/繩索 → 桿件；架模板灌漿 → 板殼。**不做**從任意方塊堆反推「哪些是柱、哪些是梁」。

**理由**：從方塊堆反推結構角色是形狀語意問題不是力學問題，三個既有倉庫都沒有解。用材料宣告把它消滅而非繞過，而且對應真實工程（結構圖說本來就標明構件）。這也讓工法玩法與離散化合法性成為同一件事。

**代價（v1 明確接受，但不是永久的）**：v1 放棄「對任意原版方塊建築出應力」。應力眼鏡只作用於用結構材料蓋的東西。

⚠️ **這是過渡不是終局。** 建築模式是技術不足時的權宜——用工法語言把「玩家必須先宣告結構角色」這個限制包裝成遊戲設計。包裝是誠實的（真實工程本來就這樣蓋），而且它讓 v1 可以出貨，**但它不能變成永久的藉口**。

**長期目標是自由模式全功能**：玩家隨便蓋，演算法自己搞定，**不是降級路徑**。那需要解掉「從任意方塊堆反推結構角色」的形狀語意問題——**這個問題沒有消失，只是被排序了**。

因此本決策有三條立即的架構約束（現在做很便宜，事後補很貴），詳見 `docs/MEMBER_SEMANTICS.md` §8：

1. 引擎邊界不得假設「玩家已宣告」——結構角色欄位未來要允許 `UNKNOWN` 由引擎推導
2. 結構角色不得成為持久化格式的必填欄位——藍圖存幾何 + 材料，角色可重算
3. 構件擷取要留 pluggable strategy 介面——`DeclaredExtractor`（v1）與未來的 `InferredExtractor` 共用同一輸出契約

**否證條件**：無。v1 的取捨已定，長期目標已定，剩下的是排序問題。

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
