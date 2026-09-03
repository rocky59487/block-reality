# 判準紀律

這套方法論借自 `tectonic` 的 gate 制度。採用它的理由很具體：**「這次會寫仔細一點」擋不住技術債，能抓到漂移的 gate 才會。**

前身 `PFSF-CORE` 的失敗有一部分不是數學問題而是驗證問題——對稱性測試只在 `shearWeight=1` 下跑，而 production 預設是 0，那條路徑從未被覆蓋；效能宣稱寫在成績表裡，而支撐它的程式碼路徑生產環境不會走。這兩種問題都不是「不夠小心」，是**沒有機制**。

## 三條鐵則

### 1. 判準在實作之前 commit

一個能力的驗收判準必須在寫該能力的第一行程式碼**之前**進 git，並且該 commit 的 SHA 要寫進 evidence。

事後移線不是禁止，是**要登記**：在本檔的異動表新增一列，記下原判準、實測、移線理由、以及下游結論的降級。

> 凍結數字線 median MISS 時，除非 spec 內有 pre-signed 的噪音/可達性條款，否則下游結論至少降一級（PASS→CONDITIONAL），**不得以換臂、換 fixture、或事後重詮釋維持原判**。

### 2. 沒有 gate 執行過的能力，不得寫進能力清單

「檔案在」不算「有」。移植品未被執行 = 未被驗證。

死碼二選一：**驗證它或移除它**，不留「檔案在就算帶到」的假象。

### 3. 輸格照登

量到比對照組差，寫進文件。不換對照組、不換 fixture、不重新定義勝負條件。

單條事後重詮釋都有可辯理由；**聚合起來是 gate 失去牙齒的簽名**。

## 判準分級

| 級別 | 語意 |
|---|---|
| **Judged** | 達不到即 FAIL。不得事後移線（除非走上面的登記流程） |
| **Recorded** | 記錄不判準。用於已知有噪音、或尚無可達性依據的量測 |

新能力的效能數字**預設是 Recorded**。要升成 Judged 需要先有一次穩定的基線。

## 力學能力的 oracle 來源

本專案的力學層是別人的引擎，所以我們的 gate 驗的是**接線**不是**數值核**。分工：

| 驗什麼 | oracle |
|---|---|
| 構件抽取正確 | 手排 fixture：已知的方塊配置 → 已知的 member 清單 |
| 模型建置正確 | 閉合解。懸臂 `PL³/3EI`、簡支 `5wL⁴/384EI`、固端 `wL⁴/384EI`、propped `5wL/8` |
| 靜不定處理正確 | 全域平衡：Σapplied + Σreactions = 0（力與對原點的矩） |
| 座標系一致 | 旋轉等變性：整個模型任意旋轉 R，要求 `u_rot = R·u` |
| 兩軌不背離 | 顯示軌 vs 承諾軌在同一 fixture 上的差 ≤ 顯示軌容差 |
| 決定論 | 同輸入 ×3 逐位元相同 |

前兩項用引擎自帶的解析 oracle；**旋轉等變性與全域平衡特別值得自己做**，因為它們檢查的是我們的座標轉換與載重組裝，那是接線層最容易出錯的地方，而且它們不需要知道正確答案就能驗。

## 兩條 fixture 硬規則

### 1. 🔴 第一批 fixture 全部用非正方形斷面

前身踩過這個雷兩次，而且兩次都是**綠燈掩蓋座標系錯誤**：

`PFSF-CORE` #71 —— 同一份 Manifest 產生三種載重方向（負 Z / 純量 / 負 Y）：
> 現有樑沿 X 軸且截面為正方形，負 Y／負 Z 都可能得到相同 root region，**讓 smoke test 在座標錯誤下仍命中**。

`architect_simulator` AS-72-u2 review NIT —— `Cy/Cz` pairing 對調，dormant 因為所有 v2 包絡都是正方形，**第一個非正方形斷面會讓弱軸 D/C 差 2 倍**。

而本專案「斷面與方塊尺寸解耦」（D-004）意味著**非正方形斷面是常態**。

驗收條款（照抄 #71）：
> 非正方形截面樑：同一樑分別施加 −Y／−Z，兩端必須選用對應彎曲軸，**結果不得因錯誤軸對稱而相同**。

同時：**自重方向、外力方向、耦合力方向必須分開，禁止所有 source 都被視為同一重力方向。**

### 2. 焊接把機構變成結構（驗收案例，照抄 ArchSim D11）

一個**銷接**的鋼構 L 形（柱 + 梁、頂端載重）是**機構** → solve 奇異、利用率恆為 0。
兩次焊接後 L 變成剛性懸臂 → 兩根構件都有真實 `D/C > 0`。

> **「Welding turns a mechanism into a structure」，用數值證明。**

這個案例同時驗證三件事：`bEnableReleases` 真的開著、焊接動詞真的改寫 release set、機構偵測真的來自因子代數。**任何一件沒接上，這個測試就過不了。**

## 判準異動登記表

| 日期 | 判準 | 原線 | 實測 | 裁決 | 下游影響 |
|---|---|---|---|---|---|
| 2026-08-20 | SidecarEngineTest「兩構件在共享節點報同一斷面彎矩」 | 絕對 1e-9 | 對 ~2.9e7 的彎矩,絕對 1e-9 = 相對 3.5e-17,**嚴於 double 的最後一位**;舊實作恰好同一條浮點路徑所以 bit 相等,D-020 改用引擎重建後端 J 在 x=L 求值、端 I 原生,代數相等但末位捨入不同（實測差 6e-8 絕對 = 2e-16 相對） | 改為**相對 1e-12**（仍比承諾軌的 1e-9 嚴三個數量級）。原線斷言的是實作巧合,不是力學語意 | 無下游結論降級:該 gate 驗的「節點彎矩連續性」在新線下仍成立到 2e-16 |
| 2026-08-20 | verify.py [S9] 跨材料板 vm 比（新 gate 的撰寫史,commit 前修訂兩次,照登） | 第一版:argmax 處 vm 比 = (ρs/ρc)(tc/ts),容差 5e-3 | FAIL 2.8%:推導漏掉夾支邊的切向伴隨彎矩 ν·M（vm 要乘 √(1−ν+ν²)）;補上後仍 FAIL 1.0%:argmax 取樣點在邊界層內半格,閉合解在那裡本來就只準 ~1% | 觀測點**移到場的駐點**（板中心,n=8 網格）,ν 因子化在該處二階精確;實測落在 1.6e-4 / 3.8e-4,容差維持 5e-3 | 無下游降級:此 gate 首度執行於本次,未曾支撐過任何能力宣稱;兩次失敗都是**參考解推導錯**,引擎數字自始未動 |
| 2026-08-20 | evidence.py 總 gate | provenance 缺席可過（實際出貨過 commit=unavailable + dirty 的紀錄） | 兩版 release 的 verification.json 皆無可解析引擎 SHA（根因:WSL 讀 NTFS 的 dubious-ownership） | **收緊**:引擎 commit 可解析 + worktree 乾淨 + 失敗 case 為零,三者任缺 gate 即紅（#47） | 舊 evidence 的 identity 節追溯視為**無效**;數字本身由本次重生成覆蓋 |
| 2026-08-20 | 紀律違規自登:SIDE-2/5/6 的 parser 修復先於 gate 一個 commit（鐵則 1 要求判準先 commit） | — | 修復在 23b0b37,gate（TS 深巢/溢位 revision 回顯/截斷診斷,+3 項）在下一個 commit 補上,三條全綠 | 順序滑失**照登**;gate 內容以修復後行為凍結 | 該三項修復的「已修」宣稱由補上的 gate 支撐;無其他下游 |
| 2026-08-20 | verify.py `check()` 零參考語意（TEST-8） | expect=0 時 rel=\|got\|/1e-30,任何容差都退化成 exact-zero 斷言——呼叫者以為給了 slack,實際沒有 | 五個零參考站點:四個 tol=1e-30（新舊語意等效）,一個 tol=1e-10（C11 平衡殘差,舊語意實際要求恰為 0 且恰好一直是 0） | expect=0 改走**絕對比較** \|got\|≤tol,與 evidence.py 一貫的雙指標同構 | C11 那條從「恰為 0」放寬到「≤1e-10 絕對」——這是**放寬**,照登;放寬後的線即為作者當初寫下 1e-10 時的本意 |
| 2026-08-20 | `SidecarEngineTest.shmAndJsonTransportsAgree` 比對集合（TEST-2） | 只比部分欄位 | 比對洞:六分量端力兩端、islands/singularIslands/unassigned、field 14 分量、shell 8 內力+ex/ey/n/material/thickness 全未比 | **加嚴**:全欄位、容差 0.0,對真引擎執行 | 加嚴無需降級;先前「T-gate 保護 Java 解碼器」的 javadoc 宣稱為假,已同步改寫 |

| 2026-08-23b | verify.py `[M1]` 鏡像不變性（新 gate 的第一版，照登） | 第一版斷言「上下鏡像後 maxDC 不變」 | 那不是對稱:重力不跟著翻,磚墩配木柱與木柱配磚墩本來就是兩座不同的結構,實測差 3.79 倍是荷載路徑不是離散化 | 上下鏡像只斷言**幾何與質量**（長度多重集合、自重）;完整不變性（含 maxDC）移到**水平鏡像**,那裡重力不受影響、沒有物理藉口可用 | 無下游降級:被移走的那半以更嚴的形式留下。原始缺陷（自重差 40.2%）由留下的那半抓住 |
| 2026-08-23b | D-025 的實作 | 「其中一方吞掉接合處那一格」 | 否證條件 (2) 觸發:自重 40.2%／12.9%／5.1%,maxDC 最多 3.5 倍 | 依該條指名的補救改為**面上的節點**（D-028）。修後 0.000%,長度變成幾何正確的 1500/1500 | 下游:v0.1a–v0.3a 所有跨材料對接結構的自重與 D/C **作廢**。單材料結構不受影響 |
| 2026-08-23b | `check_bundle.py` 的第三次加嚴（照登） | 只看預期路徑、512 KB 門檻、`set(namelist())` | 同名 entry 在真引擎前面塞 3 MB 填充 → **四道 gate 全綠、jar 只大 324 B**;511 KB 的多餘檔案免費通行;`dist/` 的多餘檔案（實測 `.env` 與 debug build）會被公開打包而 `sha256sum -c` 仍 exit 0 | 改走 `infolist()`、重名即 FAIL、門檻降到 64 KB、引擎目錄內未列於 manifest 者一律 FAIL、`dist/` 內容必須與 `SHA256SUMS.txt` 完全相符 | 兩種注入都實測會被抓（重名、`.env` + debug build）。**這是同一支 gate 第三次因為「只看自己預期之處」而被加嚴** |
| 2026-08-23 | Windows 引擎的位元可重現性 | 上一輪 commit 訊息宣稱「mingw 交叉建置是位元可重現的」 | **錯的**。當時之所以雜湊相同,是因為 cmake 判定 up-to-date 根本沒有重新連結。真正重建後兩顆 4108756 位元組的檔案**差 4 個位元組**:PE 標頭 0x88 的 TimeDateStamp 與 0xd8 的回音,其餘完全相同 | 加 `-Wl,--no-insert-timestamp`。兩次乾淨建置實測雜湊相同 | 上一輪的宣稱**撤回**並在此更正。Linux 建置本來就沒有這個欄位,一直是位元相同的 |
| 2026-08-21 | verify.py [S4]／[S9] 夾支方板中心彎矩參考係數 | `0.0231`（Timoshenko Table 35 / Roark 11.4 case 8a）,8 元素容差 1% | 參考係數本身是錯的。13 點差分解雙調和方程 n=20/40/80 + Richardson 外推得 **0.02290512**;同一次運算把該表另外兩欄逐位重現（w_max 0.00126532 對 0.00126、M_edge −0.0513338 對 −0.0513）。三取二相符、不符的那個差 0.85%,而三位有效數字捨入只能解釋 ±0.217%。改用真值後,8/12/20 元素的誤差是 1.75%／0.79%／0.28% | 判準**改成收斂階**：per-mesh 線 2.0%／1.0%／0.4%,加上 h→h/2 誤差比對應的 p = 2.0 ± 0.075（實測 1.983、2.014）。8 元素那條線 1% → 2%,照鐵則 1 登記 | 下游降級:evidence 舊表的「收斂地板」敘事**撤回**——那是錯參考解造出來的假象,不是 MITC4 的性質。舊表引用的 20 元素 0.57% 亦作廢（真值 0.28%,雖然更好,但那個數字當時不可信）|
| 2026-08-21 | `.github/workflows/ci.yml` engine-gates job | 「CI 綠是 merge 條件」——實際上該 job **恆綠** | `cmd \| tail -3` 在 GitHub 隱含的 `bash -e`（無 pipefail）下回報 tail 的退出碼。本機兩路實測:`bash -e` → 0,`bash -eo pipefail` → 1 | workflow 層 `defaults: run: shell: bash`,使 GitHub 改用 `bash --noprofile --norc -eo pipefail` | 下游降級:**2026-08-20 之前所有「CI 綠」的引用一律無效**,包含本表上一節末句與 RELEASING.md。第一次真正紅的執行:commit `7d9982e`,run 32621276032,engine-gates job failure,失敗原因是本次新增的 19 條 gate |
| 2026-08-21 | 紀律自登:本輪 [C16]/[J1]/[J2]/[J3]/[P1]/[P2] 六節新 gate | — | 判準先 commit（`7d9982e`,對出貨引擎 **FAILED 19 of 251**,輸出全文在該 commit 的 CI run 32621276032）,實作在下一個 commit（`e8c39cb`）轉綠 | 鐵則 1 **完整滿足**,含 D-4 建議的「先 commit 紅版 gate」 | 無;這是本表第一次不必登記順序滑失 |
| 2026-08-21 | 出貨文件的檢查項計數 | 人工抄寫 | 「219」出現在九份文件與 GitHub Release body,真值 218（`grep -c PASS` 把結尾 ALL PASS 也算了）;151／164／216 三個更舊的數字同時仍活在 outreach 文件裡 | verify.py 自印總數,`scripts/check_docs.py` 逐份文件比對,pattern 對不上檔案即 **FAIL 而非 skip** | 舊 Release body 的「219 acceptance checks」為**對外不實陳述**,以本次發行的真值取代 |
| 2026-08-21 | dist/br-sidecar.exe | 無任何 gate:不比雜湊、不在 evidence、連存在與否都沒人檢查 | 端到端模擬:拿掉 .exe 後照 package.sh 重生 SHA256SUMS,四道 gate 全綠 | package.sh 缺 mingw 直接 `exit 1`（除非 `ALLOW_NO_WINDOWS=1`）;evidence 記 `identity.binary_windows`;ci.yml 與 release.yml 各比一次雜湊 | 下游:v0.1a／v0.2a 之前所有「跨平台出貨」宣稱只有 Linux 那顆有紀錄支撐 |
| 2026-08-25 | 不變式 2 的適用範圍與 monolith 斷面語意（D-030,規劃輪裁決,**尚未實作**） | 一格 `concrete_rect_400x600` = 0.24 m² 宣告斷面（Z 0.024 m³）;`brick_rect_230x350` 同理 0.0805 m² | 產品裁決:monolith 一格 = 1 m³ 材料本身 → 混凝土 A 4.17×、Z 6.94×、每公尺自重 5,533 → 23,054 N（g=9.81 口徑）。二審（`V04_PLAN_REVIEW_2026-08-25` A）指出本表漏登——D-028 為更小的下游都登了 | 依鐵則 1 補登;實作落地於 v0.4（T3,tectonic 側） | 下游:**v0.1a–v0.3b 所有已發表的混凝土與磚 D/C、自重、挫屈值於 v0.4 落地時作廢**（倍數級,非精度級）。鋼、木、板 token 不受影響。v0.4 之前引用舊值必須註明「v0.3b 語意」 |
| 2026-08-25 | D-028 的釘住分支（支承/荷載釘在中心的共線對接） | 「維持舊的吞噬規則,材料名排序在後者長出去」 | T1 之後兩引擎慣例分歧:v0.3b 實測兩側釘住給**連通**模型（4 members/1 island,timber 依名序吞 x3;鏡像後長度多重集不變）;tectonic 1d 凍結為「單側=被釘格中心當接點;兩側=接地殘樁不建接點」。B2 對數在此類 fixture 必紅,且**不是引擎缺陷** | **以裁決退役**（D-035）:統一採 tectonic 1d 三條;br-sidecar 依 D-034 不改碼;B2 把釘住共線對接列**記名排除**（清單 ≤ 3 類,超過重議） | 下游:v0.3b 釘住共線對接 fixture 的結果於**換裝時由引擎側慣例取代**;verify.py [J] 系列中釘住案的數值屬 v0.3b 語意,不得跨引擎引用。D-028 否證 (2) 的 1% 線**未觸發**（本次是 B2/D-034 驅動的重裁,照實記） |
| 2026-09-02 | **N16-c**（挫屈細分「單調且保守」） | 「細分數增加時對 Greenhill 的誤差單調下降，且回報值**不得高於**精確解」 | 純 Python 重算（tectonic2 `gate/evidence/MC66/prefreeze_kg.txt`）：一致幾何剛度（沿桿線性 N，與元素同形函數）的 λ 是 Rayleigh-Ritz **上界**——Greenhill 單元素 **+0.66%**、2 元素 +0.25%、4 元素 +0.02%，全部**從上**收斂；「元素內取最大軸力」的 Kg 才給 −68/−47/−29/−16/−9%，逐項重現 FrameCore 序列。原線「不得高於精確解」與正解矛盾（線畫在數學天花板之上） | 改為「**有界且方向具名**：對**同理論**精確解的上界；EB 臂單元素對 Greenhill ≤ 1%、對 Euler 頂載 ≤ 1%（[暫]，tectonic 首跑釘）；細分**單調從上**收斂；Timoshenko 臂對 Engesser 各自具名」（D-040） | 下游降級：所有「λ_cr 偏保守 / 上界」敘述**撤回**；`[C12]` 的單元素課本值（1.6e-05 容差）於 v0.4 作廢，改閉合式 1%；N16-a 由「載重不切段」達成而非細分 |
| 2026-09-02 | **N18**（挫屈狀態四態） | `computed / no-positive-eigenvalue / not-eligible / disabled-by-request`（+ 宿主的 `disabled-by-scale`） | tectonic 的特徵值 lane 有第五個零：**Lanczos 未收斂**（`solver-failed`），且 `not-eligible-scale` 由引擎回（`budgetDof`）；另需 `buckling.kind` 標記篩 vs 特徵值 | 加第五態 `solver-failed`；`not-eligible-scale` 改為引擎態；加 `buckling.kind ∈ {eigen, screen}`（D-040）。矛盾檢查不變：有因子 ⇔ `computed` | 無降級；`[N18]` 在 tectonic 臂於 v1.5 前 expected-red（狀態字串在 v1.4 只有 `not-eligible`/`disabled`） |
| 2026-09-02 | 板網格（`[S1]/[S2]/[S4]` 與 evidence 的板數字） | 2×2 方塊一片、角在方塊中心、邊緣少半格、自重面積 (n−1)² | tectonic：一方塊一片、角在中面格角、面積 n²；孤立格/板條也出 facet（`extract.h:1329-1340`） | **採引擎慣例**（D-042）；fixture 於 Phase 3 改寫；`[S4]` 的夾支邊 oracle 改簡支閉合式（板永不被旋轉夾持，MC58b-10） | 下游：v0.1a–v0.3c 所有板數字（S 系列 evidence）於 v0.4 作廢；「板收斂階 2.0」的敘述限於 FrameCore 臂 |
| 2026-09-02 | 載重切段（`[C11]`「a load in the middle of a member splits it」） | 受載方塊成節點 | 那正是 N16 的病因（網格隨載重變）；tectonic MC54 不切、以站位載重掛在構件上 | 線**反轉**：受載構件**不得**因載重切段；端格 = 節點載重（tectonic MC64）（D-042） | 下游：`[C11]` 改寫；v0.3c 之前的「受載切段」結果語意作廢 |
| 2026-09-02 | 樑理論預設 | Euler-Bernoulli（Java/sidecar 目錄無剪切面積） | tectonic 每種斷面帶 `Asy/Asz`（Rect `5/6A`）⇒ Timoshenko；EB 只能經 `eulerBernoulli` 旗標（MC63） | 遊戲預設 **Timoshenko**（D-042）；FrameCore parity 與閉合式對數用 EB 旗標；換算 gate `[U]` 分兩臂各對各的閉合式 | 下游：撓度數字對深梁差 ≥ 3%（H-400 L/h=5），差異帳歸 `convention`；靜定內力不變 |
| 2026-09-02 | D/C 定義（`ElasticAllowable` 五比值 argmax + 殼 vM） | 五比值、argmax 控制纖維、殼 von Mises | tectonic：三元組纖維篩（壓面/拉面/剪+扭）+ 殼 Mohr 主應力（`readback.h:92-182`）；SHEAR/TORSION 零 oracle 缺口兩邊都在 | 採引擎定義（BSI `bsi.dc.fibre3`），差異帳歸 `convention`；**零 oracle 缺口照登不變** | 下游：兩臂的 D/C 值不可互相引用；「D/C = x」的對外數字自 v0.4 起是 fibre3 語意 |
| 2026-09-02 | F76（patch 0002 夾支邊彎矩）能力 | 「支承彎矩是還原的，不是讀的」`[S5]` | tectonic MC 世界的板永不被旋轉夾持（`extract.h:1387-1397` 只鎖平移；MC58b-10）⇒ 該能力在換裝後不存在 | **退場**：v0.4 不宣稱；`docs/outreach` 掃除該宣稱（Phase 3） | 下游：`[S5]` 退役；patch 0002 的 F76 敘述限於 FrameCore 臂 |
| 2026-09-02 | 支承語意（D-022 `support:bool`、隱含全固、Java 啟發式） | 「正下方是非結構實體 → 支承節點、6 DOF 全固」 | T2#15 / #85：裁決權要在引擎；tectonic 支承 = 相鄰 Support 角色方塊 + `supportKind`（MC63） | **D-039**：Java 送面相鄰地面格（Q1(a) 六面），引擎定固定度；`support:bool` 移除 | 下游：貼牆柱/坑內橋墩 D/C 趨零（照登）；`isSupported()` 的所有 Java 測試改為 N21 |
| 2026-09-02 | `[M1]/[M2]` 的 `brick_rect_230x350` fixture | brick 為 Member 角色斷面 0.0805 m² | D-030 之下 brick 是 monolith（一格 = 1 m³）；tectonic MC61 F4 對同一 fixture 的裁決是「2 members + 2 monolith 格、無共節點、警告 ≥ 1、Singular」（縫合是具名開口） | fixture 改寫為 Member 角色的木/鋼對接（tectonic MC61 F1 的 `brick_pier`/`timber_post` 是 gate 值，不宣稱真值） | 下游：`[M1]/[M2]` expected-red 到 Phase 3；2026-08-25 D-030 列已登記的作廢範圍不變 |
| 2026-09-02 | 構件身分（`lengthMm`、member id 跨 revision 對應） | 一根 run = 一個 member | tectonic MC60c：承載板的樑被切逐格（B3）；D-036 否證 (3) 兌現 | 採納（D-042）；hover/`/br members` 由 sidecar 依構造物件合併逐格 member；registry 改用 run 起訖鍵 | 下游：`MemberSnapshot` 的 id 語意改「構造物件」；v0.3c 的 member id 不可跨版本比對 |
| 2026-09-02 | 重力常數 | g = 9.81（`main.cpp:1052,1108`、`verify.py:15`） | tectonic 預設 9.80665 但接受請求的 `gravity`（`tec_capi.cpp:640-647`） | **不移線**：請求送 `gravity:[0,-9.81,0]`，兩後端同 g | 無下游；登記是為了證明它被考慮過而**不是**漏了 |

### 2026-08-30 新增的 gate（N17／N18 落地，B4）

引擎側（`sidecar/verify.py`，330 項，+44）：

- `[N17]` **帳目完整性**，八組 fixture 各三條斷言（覆蓋、不相交、理由）：浮空樑、接地柱
  旁的浮空樑、貼地樑、一格寬板條、疊成實體的板、孤立板格、一般跨、以及「什麼都沒少」。
  每一條都是「方塊進 → 回覆斷言」，不引用任何引擎內部符號（D-033）
- `[N18]` **挫屈狀態**，四個狀態各一條，外加「三個 0 必須是三個不同狀態」與
  「沒有任何回覆的狀態與其因子矛盾」

Java 側（268 項，+23）：

- `UnassignedReportingTest`（15 條）：理由碼的 wire 往返、未知碼降級為 `UNKNOWN`
  且**仍帶著它的方塊**、`formsNoElement()` 的分類，以及**未知碼不得授權刪除玩家的測試荷載**
- `StressResultPacketTest` +6：每個挫屈狀態的封包往返、`DISABLED_BY_SCALE` 只由宿主
  代入、小於 float 下限的因子被還原而不是被判矛盾、逐理由計數往返（含未知碼）、
  兩種矛盾封包被拒
- **`LangKeysTest`（4 條，新）**：每個遊戲取得到的翻譯鍵在兩種語言都解得出來、兩份 lang
  鍵集合相同、沒有任何 `br.*` 死鍵、同一鍵的 `%s` 數量在兩語言一致。鍵集合是**推導**
  出來的（原始碼字面 ∪ 列舉常數 ∪ 可達的 `ScanMode`），不是手抄清單

`LangKeysTest` 首次執行就抓到四個死鍵（`br.hint.first_use`、`br.hud.peak`、
`br.hud.engine_off`、`br.hud.islands`）——它們早在 `docs/pr26_findings/CORRECTIONS.md`
（2026-08）就被點名，一直沒人刪。缺的不是發現，是**一條會擋下重犯的線**。

### 2026-08-23b 新增的 gate（v0.3a 審核，v0.3b）

引擎側（`sidecar/verify.py`，282 項）：

- `[M1]` 鏡像不變性 —— 上下鏡像保長度與自重（實測 0.000%）；**水平**鏡像保一切，含 maxDC
- `[M2]` 對接節點在面上：磚墩配木柱各 1500 mm，自重是各材料自己的一半
- `[M3]` 全支承結構仍回報放在它上面的東西 —— 5000 kN 進 `applied`，殘差仍為 0 且**是因為對的理由**
- `[M4]` 單一異材質墊塊是墊塊不是洞：柱被解出來，墊塊自己進 `unassigned`
- `[M5]` 8000 格共線鏈答得出來；`bucklingFactor` 永遠在 wire 上

Java 側：

- `BundledEngineTest` +4 —— manifest 的檔名欄位若是路徑一律拒絕（等價 zip slip，但入口是
  manifest，一般掃描器看不到）、`Darwin` 不是 Windows、**八執行緒併發後拿回來的必須是磁碟上
  那一顆**（原本 87% 拿到錯的）、解出來的檔在 POSIX 上必須可執行（唯一合法需要 `assumeTrue`
  的場合）
- `SidecarPathsTest` —— Windows 檔案總管「複製路徑」給的帶引號字串必須是一條路徑而不是
  一顆 `InvalidPathException`。它會穿過 `computeIfAbsent`（不留 mapping）炸掉 server thread，
  **而且每次 chunk load 再炸一次**
- `check_docs.py` 新增 `CLOSED_FORM` 量與**禁句表**：`every number is gated`、
  「N acceptance checks run against textbook closed forms」、「另一台機器可重現」出現即紅

**honest limit（照登）**：`[M5]` 跑 8000 格，而實測的死亡門檻約 37,000 格。在真門檻上跑
一次要花掉 ~50 秒套件裡的 ~25 秒。8000 格能證明的是 union-find 撐得住那個深度的鏈——
一個深到會死的遞迴根本答不出來——而修法（改迭代）讓深度不再是變數。

### 2026-08-23 新增的 gate（引擎隨 jar，D-027）

- `BundledEngineTest`（11 條，`mod/core`）—— 解壓縮**就是安裝程序**,所以它會寫錯位元組、
  寫半個檔、或覆蓋玩家自己放的二進位,都比它取代掉的手動步驟更糟。逐條:manifest 半懂就
  報錯而非略過、平台選錯寧可不給、首次解出正確位元組且路徑以雜湊命名、第二次啟動不重寫、
  磁碟上被改壞會被換掉、**jar 位元組與 manifest 不符則什麼都不解**、開發用 jar 沒有引擎也
  照常、不支援的平台被告知該怎麼辦、舊引擎會清掉而無關資料夾不動、任何失敗都不得往
  mod 載入丟例外
- `scripts/check_bundle.py`（CI 與 release 各跑一次）—— **三個雜湊必須一致**:jar 裡的位元組
  == 旁邊的 manifest == evidence 紀錄驗過的那顆 == `dist/` 裡獨立出貨的那顆。任兩個相符
  都不夠;jar 對 evidence 那條抓的才是真正會痛的情況——用一顆從未過 gate 的引擎打包
- `check_bundle.py` 的**第二次加嚴**（同日,照登）—— 第一版只檢查它預期的路徑,結果 jar 裡
  同時帶著兩份引擎（資源目錄改名後,建置的 generated 資料夾留著舊的一份）**四道 gate 全綠**,
  jar 從 2.4 MB 變成 4.5 MB 沒人發現。現在改成:凡是 ≥512 KB 而不在預期清單裡的 entry 一律
  FAIL。**只看自己預期之處的 gate,看不見它沒預期的東西**
- **端到端（本輪實跑，非單元測試）**:用真正的 jar 在 Linux 與 Windows 各解一次,解出來的
  二進位**跑完整驗收套件 251/251**;把它改壞後再啟動會被換掉,換掉後仍 251/251

### 2026-08-21 新增的 gate

引擎側（`sidecar/verify.py`,總數 218 → 251）：

- `[C16]` — `steel_rect_150x300` 對懸臂閉合解。它是唯一「有方塊、有 README 宣稱、但在本檔與全部 Java 測試裡各 0 次」的 token
- `[J1]` — 跨材料接點:木樑架磚柱是**一座**結構;自重含樑的 791 N;玩家掛的 500 kN 進得了 `applied`;外加**反例**（同一根樑高一格、誰也不碰 → 仍是三座島）
- `[J2]` — 共線對接只產生兩根構件,各保有自己的斷面（接合處那一公尺不得被建模兩次）
- `[J3]` — 單一方塊不因為碰到板就變成構件（MECH-03 的自重放大 2.3 倍）
- `[P1]` — 兩端落地的樑要被解出來:固端彎矩 wL²/12、跨中 wL²/24,實測相對誤差 1e-16
- `[P2]` — 整根貼地的 run 是「完全支承」不是機構

Java 側：

- `StressResultPacketTest.everyNumberTheClientDrawsIsWithinTheDisplayBudgetOfTheServersOwn`
  —— 不變式 5 的**管線** gate。原本被當成這個角色的 `DisplayTrackPrecisionTest` 零專案 import,
  它驗的是 IEEE-754 的性質而不是這個封包（MECH-10,五個維度各自抓到）。新 gate 走 record
  component 反射比對整包每一個 double,所以之後新增欄位自動被涵蓋。**它第一次執行就抓到
  一個真缺陷**:`MemberSnapshot.endI/endJ` 過網路後被靜默歸零（沒有人讀,所以活了下來）
- `SnapshotLoadsTest` 兩條新 case — 荷載落在「有收進但形不成元素」的方塊上時,由無荷載 probe 的 `unassigned` 指認;未被加載的 unassigned 方塊不得被牽連

### 2026-08-20 新增的 Java 側 gate（6.2/6.3/6.4 落地）

- `DisplayTrackPrecisionTest` — 顯示軌 f32 降轉 rel ≤ 1e-5（不變式 5 首個可執行 gate;全數量級＋分類邊界值）
- `BrPermissionsTest` — 指令權限枚舉:白名單 {status, members, section, loads} 外一律 requires ≥ 2;表外 literal 無法註冊
- `BinaryCodecTest.decodeNeverThrowsForAnyTruncationOfAValidFrame` ＋ `StressResultPacketTest.aTruncatedBufferNeverThrowsItRejects` — 兩個解碼器的 never-throws 契約,合法 frame **全前綴截斷窮舉**
- 生命週期五連:pool 關閉重建（1.1）、solve 拋出 inFlight 恰復位一次（1.2）、gather 超預算讓出且續傳不重不漏＋零預算保底（1.3）、loads 與 blocks 同進退（1.4）、CLOSED 終態不復活（1.5）

這批 gate 的牙齒以 CI（`.github/workflows/ci.yml`,同日上線）為前提——CI 綠是 merge 條件。

> **這句話在 2026-08-21 之前是假的**,兩層都假:engine-gates job 因 pipefail 缺失而恆綠（上表),
> 而 `Main` 上根本沒有分支保護,所以「綠」從來不是合併的必要條件。兩者同日修好;分支保護的
> 設定是倉庫層設定,不在版控裡,因此在此登記為判準的一部分。

## 發布鏈與驗證之間的接線（2026-08-23b 補上）

在此之前 `release.yml` 與 `ci.yml` **沒有任何接線**：release 不跑任何測試，tag push 不觸發
CI，標籤零保護。兩次發行沒出事，靠的是 `required_status_checks.strict = true` 讓合併後的
tree 與被測過的 PR head 逐位元相同——**一個沒人寫下來是承載性的設定**。實測繞法存在：在側
分支把版號、jar 檔名、SHA256SUMS 與 verification.json 一起改（`package.sh` 跑一次就全齊）
後直推標籤，三道 gate 全綠而 CI 從頭到尾沒跑。

現在：`ci.yml` 觸發加上 `push: tags: ['v*']`；`release.yml` 第一步等兩條 required check 在
**這一個 commit** 上結束並要求 success，逾時視為拒絕而不是通過。`enforce_admins` 已開啟
（先前為 false，admin 可繞過 status check）。

## 已知的 gate 缺口（鐵則 2 的照登）

`ElasticAllowable` 取五個比值的 argmax 決定控制纖維。驗收套件釘住其中三個對閉合解;
**SHEAR 與 TORSION 兩個模式至今零 oracle**——`verify.py` 裡除了 enum 名稱與剪力牆
(那是板的面內剪力,不是這兩個模式)之外沒有任何一條碰它們,而 PR26 審核的獨立手算與
引擎的扭轉比值差約 20%。

依鐵則 2,這兩個模式**不得當成已驗證能力引用**。README 的範圍段已明說,直到補上
oracle 為止,它們是指示值。這條登記在此的用意是:缺口有名字、有位置、有下一步,
而不是靠沒人提起而存在。

下一步是先判定那 20% 是誰的:實心矩形的 τ_max = T/(α b² h),α 隨 h/b 查表,而 FrameCore
用的是哪一個公式要先讀出來再對。**在讀出來之前不要動任何一邊的數字。**

### 2026-08-29 新登記:挫屈的網格依賴零 gate(v0.3c)

`verify.py` 的挫屈項驗的是**單元素柱對課本的單元素值**(1.6e-05)與 **1/L² 關係**
(2.3e-10)。兩者都不會動到網格——它們在同一個離散化裡自洽,所以**「回報值離真實臨界載重
多遠」這件事至今零 gate**。

實測(對出貨的 `dist/br-sidecar.exe`,兩支腳本隨倉):

| 情形 | 元素數 | 回報 λ_cr | 精確解 | 差 |
|---|---|---|---|---|
| 19 m 柱受**自重**(Greenhill) | 1 | 3.1376 | 9.8914 | **−68%** |
| 同上 | 2 / 4 / 10 / 19 | 5.28 / 7.05 / 8.55 / 9.15 | 9.8914 | −47 / −29 / −14 / **−7%** |
| 同一根柱,頂部載重 > 自重 400× | 1 | λ·P = 366.4 kN | Euler 364.5 kN | **+0.5%** |

兩件事因此成立,而且**方向相反**:軸力接近均勻時單元素幾乎沒有離散化代價;軸力沿桿變化時
單元素把整根桿都當成最大軸力,結果**偏保守**且可以差到三倍。連帶的玩家可見後果:在半高處
加一個**一牛頓**的測試載重會讓 λ_cr 跳升 68%,因為那段 run 被切成兩個元素——那是網格,
不是物理。

依鐵則 2,**「挫屈倍數是真實臨界載重的上界」這句宣稱已從所有對外文字移除**(README 中英、
QUICKSTART、LISTING、COMMUNITY、OUTREACH、D-016 的否證條件),改為照實敘述兩個方向。
依鐵則 1,元素細分策略**不在 v0.3c 修**:它是網格判準,要先凍再做,而 v0.4 的聚合抽取
(D-029/D-030)本來就必須裁決細分,這條併入該處理。凍結前需要的 oracle 已知:Greenhill
qL³/EI = 7.837(自重懸臂)與 Euler π²EI/(2L)²(頂載懸臂),兩者都是閉合解,可直接進
`verify.py`。

### 2026-08-29 凍結：N14 模型完整性（B6，#74）

**先凍後做。** 本節在實作之前 commit，依鐵則 1。

#### 為什麼 N14 的「二選一」選第二個

`V04_PLAN` §3 的 N14 留了兩條路：整體拒絕，或照解但標記。**選標記**，理由是實測出來的：

`StructureManager` 有 `onChunkLoad`（`:369`）**但沒有任何 per-chunk 的 unload 處置**
（只有 `onLevelUnload` 整個丟掉）。所以 `structural` 是**永不遺忘的集合**——它累積這個
維度裡曾經被掃到的每一個結構方塊，而 `visitForCycle:468` 對其中沒載入的一律靜默跳過。

在任何比載入半徑大的世界，這是**常態不是邊緣情形**。「有任一格被跳過就整體拒絕」會讓
分析在真實世界裡永久關閉，那不是誠實，是無用。

#### 判準

令 `S` = 本次 cycle 因 `!level.isLoaded` 被跳過的 tracked 方塊集合，
`I` = 實際進入請求的方塊集合。

| | 線 |
|---|---|
| **N14-a** | `visitForCycle` 跳過任何方塊時**必須留下紀錄**。`S` 為空與「沒查過」不得是同一個狀態 |
| **N14-b** | **截斷面** `T = { p ∈ S : p 有 6-鄰居 ∈ I }`。`T` 非空 ⇒ 本次回覆**被標記為截斷**，且標記隨結果上 wire |
| **N14-c** | 回傳的 member／shell 若其 `blocks` 有任一格 6-鄰接於 `T`，該構件的 **D/C 判定與上色一律withhold**，HUD 說得出理由。其餘構件照常顯示 |
| **N14-d** | **同一世界、同一結構**，完整載入解 vs 截斷解，兩者的 D/C 不得在**無標記**的情況下相異。這條是本組的目的，其餘三條是達成它的手段 |
| **N14-e** | `T` 為空時，行為與現況**逐位元相同**——這條修的是誠實，不是數字 |

#### 為什麼 `T` 用 6-鄰接而不是 26

構件抽取本來就沿軸走 6-鄰接（共線 run），而 shell 的 facet 也是共面。斜角相鄰不構成
傳力路徑，把它算進截斷面只會擴大誤報。**這是拓撲前篩，不是權威判定**（不變式 4）——
它只用來 withhold，不用來宣稱任何力學結論。

#### 明確排除

**不得把截斷面當成支承或任何邊界條件。** 那是在 Java 端發明力學，違反 D-033。
Java 在這條裡只做兩件事：記錄它跳過了什麼、以及把「這個答案的輸入不完整」這件
**請求層事實**傳下去。

#### 不在這一輪

- **`S` 的島級歸屬**：Java 不做連通分析，因此 N14-c 用的是「鄰接於截斷面」這個保守近似，
  可能 withhold 得比必要多一點。要更精確得由引擎回島級歸屬——列為 wire 需求，不在 B6。
- **`structural` 的無界成長**：`onChunkUnload` 會讓集合有界，但**永不遺忘正是截斷偵測
  成立的前提**（忘掉的方塊無法被發現缺席）。兩者要一起設計，不在 B6。
- **`isSupported` 用 `isSolidRender`**：同一個函式的另一個問題，B6 一起改（改成不依賴
  渲染述詞），但那是獨立的一行，不綁在 N14 的線上。

### 2026-08-29b 凍結：N17 帳目完整性、N18 挫屈狀態（B4／§2.7 協定變更）

**先凍後做。** 本節在實作之前 commit，依鐵則 1。

#### 動機：使用者回報的「方塊突然不參與計算」有第二個來源

N14 修的是**輸入端**的靜默截斷（未載入 chunk）。同一句抱怨還有一個**輸出端**的來源，
實測如下（`sidecar/repro_unassigned.py`，對 `sidecar/build/br-sidecar`，protocol 1）：

| 案例 | 輸入格數 | 被回覆涵蓋 | **帳目缺口** | member 間共用格 |
|---|---|---|---|---|
| 未接地的 6 格樑（機構） | 6 | 0 | **6** | 0 |
| 兩端接地的 6 格跨 | 6 | 6 | 0 | 1 |
| 貼地的 6 格樑（全支承） | 6 | 6 | 0 | 0 |
| 單顆方塊 | 1 | 1 | 0 | 0 |
| 一格寬板條 | 6 | 6 | 0 | 0 |
| 4 根接地柱托 4×4 板 | 32 | 32 | 0 | 0 |
| L 轉角 | 10 | 10 | 0 | 1 |
| 接地柱 ＋ 另一根浮空樑 | 11 | 5 | **6** | 0 |

**奇異島的方塊既不在 `members`、也不在 `shells`、也不在 `unassigned`。**
20 格的浮空樑回 `ok:true`、`islands:1`、`singularIslands:1`、`unassigned:[]`——
20 格從回覆裡整批消失。玩家蓋到一半、某段還沒接地的時候，這是**常態**。

這條不是新發現：`V03A_REVIEW_2026-08-23` 的 N4-2 已經登記過（「5000 格單跨 →
`members=0`、`unassigned=[]`，5000 格全部人間蒸發」），至今未修。本節是把它凍成線。

第二個實測：`unassigned` **今天只有座標**，而 `/br status` 把整份清單一律印成
「N blocks formed no element」。貼地樑的 6 格全部進 `unassigned`，回覆的
`diagnostic` 說的是 `fully supported: ... no internal response to solve`——
那句話是對的，逐格清單的那句話是錯的。同一個欄位至少承載三種互不相同的事實。

第三個實測（§2.6 的 wire 現況覆核，結論一致）：`buckling:false` 回
`bucklingFactor: 0`；全接地、`buckling:true`、沒有東西可挫屈也回 `0`；
回覆裡與挫屈有關的 key **只有 `bucklingFactor` 一個**。

#### N17 判準

令 `B` = 請求的方塊集合。

| | 線 |
|---|---|
| **N17-a** | **覆蓋**：`⋃members[].blocks ∪ ⋃shells[].blocks ∪ ⋃unassigned[].blocks ⊇ B`。任何輸入方塊不得三處皆不在 |
| **N17-b** | **不相交**：`unassigned` 與 `members ∪ shells` 的方塊不得相交。member 之間**允許**共用方塊（分段在節點共享邊界格，實測 D2／D7 各 1 格），本線不禁止那個 |
| **N17-c** | **理由碼**：`unassigned` 的每一格帶一個理由碼。列舉在 wire 上是**開放**的——遊戲側收到不認得的碼必須顯示為「未知理由」並照列座標，不得丟棄，也不得整包拒絕 |
| **N17-d** | **機構島**：奇異島的方塊以 `MECHANISM` 進 `unassigned`。這是引擎的判定（因子秩虧），adapter 只轉述，**不得**自己做連通分析去猜（不變式 4） |
| **N17-e** | **全支承 ≠ 形不成元素**：`FULLY_SUPPORTED` 與擷取失敗類的碼在玩家面是不同句子。今天它們共用一句，那句對前者是假的 |
| **N17-f** | **理由碼不描述力學**：碼只說擷取器／求解器把這格分到哪裡去了。任何需要內力數值才能決定的碼不得進入列舉（D-033） |
| **N17-g** | 沒有方塊落在 `unassigned` 時，回覆與現況**逐位元相同**——與 N14-e 同一條紀律 |

#### N18 判準

| | 線 |
|---|---|
| **N18-a** | 回覆必須說得出 `bucklingFactor` 是哪一種狀態：**算了有值** / **算了沒有正特徵值** / **請求方沒要** / **依規模關掉**。0 不得同時代表其中一種以上 |
| **N18-b** | 狀態欄與 `bucklingFactor` 不得互相矛盾（有值 ⇒ 狀態必為「算了有值」，且該值 > 0） |
| **N18-c** | 在 N18-a 落地之前，任何文件不得寫「三態已可分辨」（沿用 §2.6 的既有禁令） |

#### 為什麼理由碼住在 sidecar 而不是 Java

擷取在 sidecar 裡做，**它才是知道自己為什麼跳過那一格的人**。Java 去反推理由就得重做
一次擷取，那正是 D-033 禁止的「adapter／宿主仿造引擎能力」。理由碼是**簿記**不是力學：
它回答「這格被分到哪」，不回答任何內力問題——N17-f 就是為了守住這個界線而存在。

#### 明確排除

- **`unassigned` 的總量上界**：全支承筏逐格列報的成本已由 `V02A_POSTRELEASE_REVIEW`
  登記（120×120 → 14,400 筆、161 KB）。本輪把清單改成**按理由分組**，一個理由一個字串，
  至少不讓每格再多背一份字串；**筆數上界不做**——任何截斷上限都是在製造一條新的
  靜默遺失通道，那正是本節要關掉的東西。
- **島級歸屬**：`unassigned` 不說這格屬於哪一座島。要說得出來得由引擎回島 id，列為 wire
  需求，不在本輪。
- **`BULK_UNSUPPORTED`**（D-030 已命名）屬 v0.4 的新分解，本輪不產生這個碼。N17-c 的
  開放列舉就是為了讓它日後上線不需要再動一次協定。
- **挫屈狀態的第五種**（「引擎算了但失敗」）：今天 `bk.singular` 會落進「沒有正特徵值」
  同一格。要分得更細得先問引擎拿理由，不在本輪。

### 2026-08-30b 凍結：N15 member 側面 ↔ shell 接合（#75）

**先凍後做。** 本節在實作之前 commit，依鐵則 1。**這一條凍下來是紅的**，理由在下面第三節。

#### 實測的接合邊界（`sidecar/repro_member_shell_joint.py`，對 `dist/br-sidecar`）

| 接觸方式 | islands | 機構島 | facets | 判定 |
|---|---|---|---|---|
| 柱**端**頂住板（現行規則） | 1 | 0 | 8 | **接上** |
| 四根柱托一片板 | 1 | 0 | 8 | **接上** |
| 樑**端**插進板的邊、同平面 | 1 | 0 | 8 | **接上** |
| 板**擱在**樑上（#75 原文） | 2 | 1 | 0 | **沒接上** |
| 樑在板**旁邊**、同平面 | 2 | 1 | 0 | **沒接上** |
| 板擱在**兩根**樑上（正常樓層構架） | 3 | 1 | 0 | **沒接上** |

規律是乾淨的：**run 的「端」碰到板就接得上，「側面」碰到板一律接不上。**

最尖銳的一句在載重上，而且完全不用元素詞彙就說得完：

```
兩根樑單獨       applied = [0, -49285.4, 0]
兩根樑 + 樓板    applied = [0, -49285.4, 0]
```

那片板重約 **70.6 kN**（15 格 × 1 m² × 200 mm × 2400 kg·m⁻³ × 9.81），
**一牛頓都沒有進到模型裡**。玩家在樑上鋪滿樓板，樑回報的 D/C 和沒鋪之前一模一樣。

#### 判準

| | 線 |
|---|---|
| **N15-a** | 兩端接地的樑 + 其上 5×3 板 = **1 個 island**，`singularIslands == 0` |
| **N15-b** | 板出 facet（`shells` 非空）、樑出 member，兩者同時存在於同一個答案裡 |
| **N15-c** | **載重路徑穿過接面**：加上板之後 `applied` 必須增加板的自重，相對誤差 ≤ 1e-9；且樑的反力和 = 樑自重 + 板自重 |
| **N15-d** | 一格寬的板條**允許**不成 facet，但必須帶理由碼進 `unassigned`（**已由 N17 達成**，實測回 `PLATE_STRIP`） |
| **N15-e** | 接合只能由**幾何**決定，不得由材料 token 反推結構角色（不變式 3）；接合規則對鏡像與 90° 旋轉必須對稱（沿用 N2 的形式） |

N15-d 今天綠，其餘四條今天紅。

#### 為什麼它現在紅，而且不是加幾行就能綠

**接合的唯一機制是共享節點。** 板的 facet 角節點在**板方塊中心**，run 的節點在**它自己的
方塊中心**。端接之所以能接上，是因為擷取器把 run **延伸進**那格板方塊，於是 run 的端節點
就是板的節點——同一個座標。側面接觸沒有這個機會：樑在 y=64、板在 y=65，兩排節點差一整格，
相鄰不等於共用。

查過引擎能不能用別的方式接（讀 `FrameCore/Public/FrameCore` 的公開頭檔）：

| 需要的能力 | FrameCore 現況 |
|---|---|
| 剛性連結 / 零長度連結 | **沒有** |
| 多點約束（MPC）／節點耦合 | **沒有**。`Node` 只有 `fixed[6]` 與 `prescribed[6]` |
| 構件端部剛性偏心（rigid offset） | **沒有**。`Member` 有 `release[12]`、`tensionOnly`，沒有偏心 |
| `Grillage.h` | 是把**整片板**理想化成樑格的前處理器（Hambly），用來對板理論做基準，不是接合機制；且 D-005 已因為面內作用選了 MITC4 而否定格樑 |

所以剩下**兩條路**，而它們是**產品裁決**，不是實作細節：

**路線 1 — 引擎補能力。** 剛性連結或構件偏心任一個到位，樑就能待在玩家蓋的位置，
偏心如實進模型。列為引擎需求（tectonic 側或 FrameCore 側）。

**路線 2 — 擷取層改慣例：把樑的節點線搬到板的節點平面上。** 這是教科書上「樑放在板中面」
的非合成理想化，載重路徑會通，N15-a..c 會綠。**代價要說清楚**：
(i) 樑被搬離玩家蓋的位置一整格；
(ii) 合成作用的 Steiner 項被丟掉——200×400 的鋼樑 `A·e²` = 8.0e10 mm⁴，是它自身
`I` = 1.07e9 的 **75 倍**（這是解析式，不是實測）。非合成理想化本來就丟掉這一項，方向
偏柔、對撓度保守，但樑自身的內力分佈會偏離真實的合成樑。

~~**本節不替這個裁決做選擇。**~~ **已裁決（2026-08-30，D-036）：走路線 1**，能力由
tectonic2 提供，需求已提出（`rocky59487/tectonic2#13`）。路線 2 **明確不採用**——它會把
玩家蓋的東西搬走一整格。

代價照登：**在能力到位之前，樓板擱在樑上不會被算進去。** 這可接受的唯一理由是 N17 讓它
看得見（那些方塊以 `MECHANISM` 回報），**一個誠實的洞比一個搬走玩家建築的答案好**。
N15-a..c 因此**保持紅色**，直到引擎那一側落地；否證條件在 D-036。

#### 明確排除

**不得在 adapter 裡插一根高剛度的假樑把兩者連起來。** 那是 D-033 逐字禁止的
「引擎沒有、我先湊一個」。同理不得自己算板的反力再當成荷載加到樑上——那是把載重路徑
用手接起來，比不接更糟，因為它看起來會是對的。

#### 不在這一輪

- **facet 在機構島上不出現**：B 組三個案例 `facets=0`，因為奇異島在 `solveIsland` 裡提早
  return，殼還沒被寫進回覆。N17 之後那些方塊至少以 `MECHANISM` 回報得出來，但玩家看不到
  板自己的形狀。要修得動奇異島的回覆內容，與 N15 是兩件事。

### 2026-08-30d 凍結：N16 挫屈的網格收斂（#65）

**先凍後做。** 依鐵則 1。實作在 **tectonic2 側**（D-037），本節只凍判準——判準綁 wire，
所以同一套換裝前後都跑得動。

#### 缺陷不是「不夠準」，是「答案取決於玩家往哪裡戳」

實測（`sidecar/repro_selfweight_buckling.py`，對出貨引擎）：19 m 素柱，自重 117.1 kN。

| 情形 | 元素數 | λ_cr | 相對無荷載 |
|---|---|---|---|
| 無荷載 | 1 | 3.1376 | — |
| **1 牛頓**放在頂端方塊 | 1 | 3.1376 | −0.0% |
| **1 牛頓**放在頂端下一格 | 2 | 3.4753 | **+10.8%** |
| **1 牛頓**放在半高 | 2 | 5.2753 | **+68.1%** |

一牛頓是自重的 **0.00085%**。變的是網格：`extractRuns` 在**有荷載的方塊**切段，所以
玩家把測試荷載放在哪裡，就決定了引擎拿到幾個元素。**這是玩家可觸發的、68% 的答案變動。**

#### 收斂與方向

| 元素數 | λ_cr | 對 Greenhill 9.8914 |
|---|---|---|
| 1 | 3.1376 | **−68%** |
| 2 | 5.2753 | −47% |
| 4 | 7.0515 | −29% |
| 10 | 8.5506 | −14% |
| 19 | 9.1538 | −7% |

誤差 ≈ **140/n %**，也就是**一階**收斂。方向是**保守的**（回報值低於真實臨界載重）——
軸力沿桿變化時，單元素把整根桿當成同一個軸力。另一個方向已量過並照登：頂載懸臂
（軸力近乎均勻）**單元素就在 Euler 的 0.5% 以內**（`repro_euler_direction.py`）。

#### 細分不會動到線性答案（實測，這是本組最重要的前提）

`sidecar/repro_subdivision_cost.py`：同一根柱子細分成 1／2／4／10／19 個元素，
max D/C 與控制站的應力**逐位元相同**（`4.18046143e-03`、`1.463162` MPa，五個網格全同）。

> **量測方法本身踩過一個坑，照登**：第一版用 **1 N** 當切段荷載，得到 D/C 隨細分上升
> **+4.4%**，看起來就像網格敏感。它不是。垂直 run 上的 `fz` 是**水平力**：18 個 1 N 沿
> 19 m 柱是 171 N·m 彎矩，除以 Z = 2.67e6 mm³ 得 0.064 MPa——正好是觀察到的 1.463 → 1.527。
> 是探針自己壓彎了柱子。改用 1e-9 N 之後逐位元相同。**「網格移動了它」與「我的探針移動了
> 它」的差別就是整個量測。**

所以修挫屈**不會重述任何這個 mod 曾經顯示過的數字**。這件事必須先成立，否則這就不是修
一個缺陷，而是把全部既有結論一起搬走。

#### 判準

| | 線 |
|---|---|
| **N16-a** | **網格不由玩家的動作決定**：同一結構加上一個可忽略的測試荷載（≤ 自重的 1e-6）後，`bucklingFactor` 的相對變化 ≤ 1e-6。**今天實測是 +68%**，這是本組要修的那一條 |
| **N16-b** | **兩個閉合解**：自重懸臂對 Greenhill `qL³/EI = 7.837`、頂載懸臂對 Euler `π²EI/(2L)²`，兩者都進 gate |
| **N16-c** | **單調且保守**：細分數增加時對 Greenhill 的誤差單調下降，且回報值**不得高於**精確解 |
| **N16-d** | **均勻軸力不得倒退**：頂載懸臂細分後仍在 Euler 的 0.5% 以內。細分不得把已經對的答案弄壞 |
| **N16-e** | **細分不得改變線性答案**：同一結構細分前後，D/C、控制站應力與反力**逐位元相同**。今天成立，這條是把它釘住 |
| **N16-f** | **成本照登**：細分後的 DOF 倍增與挫屈耗時要量到並寫進文件，`bucklingBlockLimit` 對著細分後的成本重選（現行 600 是對**未細分**的成本選的，見 GATES 2026-08-30c） |

#### 明確不追的：一個絕對精度數字

一階收斂表示 5% 需要約 28 個元素、1% 需要約 130 個。**用元素數去買精度是錯的買法**——
更好的收斂階要靠引擎側的幾何剛度沿桿變化（axially-varying `Kg`），那是**能力需求**不是
網格參數。因此本組**不凍**「n 個元素下誤差 ≤ X%」那種線：凍了它只會逼出一個把元素數
往上堆的實作，而那正是 §2.6 已經在擔心的成本。

#### 不在這一輪

- **`bucklingFactor` 的三態**已由 N18 解決（2026-08-30b），與本組無關，列在這裡只為了讓
  讀到 #65 的人不必再查一次。

### 2026-08-30c 更正：挫屈成本錨錯了 80 倍，門檻的單位也不是成本的變數

`V04_PLAN` §2.6 的成本錨是「1000 節點 72.8 s、~n^3.1 擬合」，`BRConfig` 的註解據此寫著
「300 blocks lands near 2 s」。**兩者都不成立。** 實測（`sidecar/repro_buckling_cost.py`，
WSL/x86-64，對出貨引擎，取三次最小值）：

| 形狀 | blocks | nodes | dof | 關挫屈 | 開挫屈 |
|---|---|---|---|---|---|
| 直樑 | 300 | 3 | 18 | 1.2 ms | **1.2 ms** |
| 樓板 | 305 | 293 | 1758 | 16.8 ms | **26.8 ms** |
| 構架 | 504 | 202 | 1212 | 40.4 ms | **82.3 ms** |
| 樓板 | 1616 | 1604 | 9624 | 145.7 ms | **207.7 ms** |
| 構架 | 1004 | 402 | 2412 | 77.3 ms | **1005.1 ms** |
| 構架 | 1504 | 602 | 3612 | 127.8 ms | **3279.8 ms** |
| 構架 | 2004 | 802 | 4812 | 178.5 ms | **10373.9 ms** |

三件事，依序：

1. **舊錨差約 80 倍**。300 blocks 的樓板實測 27 ms，不是 2 秒。最可能的原因寫在程式碼自己
   的註解裡：`bopts.denseThreshold = 0` 強制走稀疏特徵解之後，一個 59-member 的解從
   51.7 ms 掉到 14.0 ms——**72.8 s 那個錨是那次修改之前量的**。舊數字未被撤回，就這樣被
   當成選門檻的依據用了兩輪。

2. **blocks 不是成本的變數**。同樣約 300 格，直樑 1.2 ms、樓板 27 ms、構架（504 格）82 ms
   ——**跨 20 倍**。門檻用 blocks 計，所以它對每個玩家的意思都不一樣，取決於他在蓋什麼。

3. **dof 也不是**。構架 2412 dof 要 1005 ms，樓板 2424 dof 只要 42 ms——**同樣的 dof 差
   24 倍**。長串相同開間會給特徵解算器一堆擠在一起的模態要分離，那是數值性質，不是規模。

**處置**：預設 300 → **600**，並把上表放進 `BRConfig` 的註解取代那句錯的成本宣稱。
600 讓構架落在 ~120 ms、樓板 ~55 ms。方向是**安全的那一邊**：挫屈關掉才是不安全
（細長柱可以 D/C 過關而失穩），所以放寬門檻是讓更多結構被檢查到。

**照登的限制**：任何單一 blocks 數字都是妥協，因為成本跨形狀差 20 倍。要真的按成本擋，
政策的輸入得是上一次回覆的 `nodes`/`dof`——那兩個欄位**在 wire 上但 Java 側沒有消費**
（`BinaryCodec` 明寫 `b.getInt(); // nodes: not consumed by the Java side today`）。
回饋式門檻列為後續，不在本輪。

### 2026-08-30 新登記：`BundledEngineTest` 的併發線在 Windows 上跑不起來

`mod/core` 的 `BundledEngineTest.whatComesBackIsWhatIsONDISK` 用八條執行緒同時解包引擎，
斷言**每一個呼叫者都拿到引擎**。它驗的是一條真的正確性性質（雜湊要取在最後落地的位元組上，
不是取在來源串流上），Linux／CI 上綠。

**在 Windows 上它紅**，而且不是本輪造成的：在乾淨的 `main` 上以同一台機器重跑同樣紅
（八條執行緒只有 3–4 條拿到引擎，數字每次不同）。根因指向 `BundledEngine` 的
`Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`：Windows 不允許覆蓋另一個 handle 正開著的檔案，
丟出的是 `AccessDeniedException`（一種 `IOException`），被上層 catch 成
`Optional.empty()` + 一行 log。

依鐵則 2，**「併發解包是安全的」這句話只對 Linux 成立**。實務影響有界但真實：單一客戶端
啟動是單執行緒，踩得到的是**同一台機器上共用 game 目錄的兩個 JVM**（例如客戶端配專用伺服器）
——那時其中一邊會靜靜地沒有引擎、分析關閉，只留一行 log。

下一步（不在本輪）：Windows 上改成「先試 move，`AccessDeniedException` 就退回檢查目標
是否已經是正確的位元組」，因為輸掉競賽的那一方要的答案本來就已經在磁碟上了。

### 2026-09-02 凍結：N19–N23（換裝總綱；Phase 0，先於任何程式碼）

> 出處：`docs/SWAP_PROGRAM.md`（權威）與 D-038..D-043。受測者標在每條上：**Java** = `mod`/`forge` gradle test；
> **sidecar/CI** = `verify.py` / `diff_engines.py` / workflow。引擎側的對應判準凍在 tectonic2 `docs/specs/MC6*.md`，**本倉不重複它們**。

#### N19 判定旗標轉發（Java）

| 線 | 內容 |
|---|---|
| **N19-a** | Java 原始碼（`mod/`、`forge/`）**零** `dc > 1.0` / `>= 1.0` 型比較（grep 型測試，白名單只允許測試檔） |
| **N19-b** | 封包 `overCapacity` == 引擎 `blocks.flags.overloaded`，逐格；`bucklingCritical` == 引擎 `stability == critical` |
| **N19-c** | `alignToVerdict` 對齊**引擎旗標**：f32 降轉值被推到旗標同一側（≤ 1 ulp）；旗標為 true 的格 f32 dc 必 > 1.0f，false 必 ≤ 1.0f |
| **N19-d** | 界線 fixture：引擎回 `dc = 1 + 5e-9, overloaded=true`（f32 讀成 1.0）→ HUD 判超載（用假 sidecar 注入，不靠真引擎湊數字） |
| **N19-e** | `stability == not-evaluated` 的格 HUD 顯示「未評估」，不顯示「穩定」 |

#### N20 顯示軌精度與 Java 零公式（Java）

| 線 | 內容 |
|---|---|
| **N20-a** | `DisplayTrackPrecisionTest` 延伸到站位（`s, x, sigma[4], tau, na, dc`）與殼角點（`s1, s2, theta, vm` × 上下面）：封包 f32 對 sidecar f64 rel ≤ **1e-5**（分母下限 = 該欄位量級的 1e-6 倍；零參考走絕對比較，沿 2026-08-20 TEST-8 慣例） |
| **N20-b** | Java 零應力公式：`StressFieldSpec`/`ShellFieldSpec`/`SectionDiagram` 的力學段刪除後，grep 型測試斷言 `mod/`、`forge/` 內**不存在** `Iy`、`Iz`、`/ A`、`vonMises`、`principal` 等力學識別字於非測試檔（白名單明列）；wire == in-process 逐位屬 tectonic MC65b，本倉不重複 |
| **N20-c** | client 對站位的內插只做**顯示**：兩站之間畫線，不重算任何應力；渲染測試以站位為輸入、以像素/頂點為輸出 |
| **N20-d** | `precision.storage:"f32"` 請求下，sidecar 直接轉發引擎的 f32 區段（不再自己降轉）；與 f64 路徑的差 rel ≤ 1e-5（同 a） |

#### N21 地面列舉（Java；依 D-039 Q1(a)）

| 線 | 內容 |
|---|---|
| **N21-a** | 對每個結構方塊，六個面相鄰的「站得住」原版方塊（datapack 對映到 Support 角色材料者）各成一筆地面記錄；**去重**；規範序（座標升冪） |
| **N21-b** | 鏡像/旋轉等變：世界鏡像後地面記錄集合等於原集合的映射 |
| **N21-c** | 非「站得住」方塊（空氣、水、可掉落方塊、非固體）不成地面；datapack 未對映的方塊不成地面（fail-closed，不猜） |
| **N21-d** | 記錄數照登：三個原型世界（地形上的房子、坑裡橋墩、貼地筏基 + 牆）的地面記錄數 / 結構方塊數比值進 evidence（D-039 否證 (2) 的量測；比值 > 2 觸發） |
| **N21-e** | `support:bool` 與 `isSupported()` 於 protocol 3 不存在（grep 型） |
| **N21-f** | N11 整合測試（#86 持久 registry）含地面鄰居列舉的**順序**穩定性：同一世界兩次列舉逐筆相同 |

#### N22 差異帳：分類規則先凍，零 blocker 才發布（sidecar/CI）

`sidecar/diff_engines.py` 對同一語料（`contract/conformance/` + `verify.py` fixture）驅動 `br-sidecar`（tectonic）與 `br-sidecar-fc`（FrameCore），逐欄比對，每筆差異歸入**恰一類**，寫 `evidence/differential.jsonl`：

| 類 | 定義 | 預歸類（凍結；不得事後改類） |
|---|---|---|
| `convention` | 兩邊都對，慣例不同（D-042） | D/C 值（G18）；撓度（Timoshenko，G19）；端力號向（MC59）；facet 數與板數字（G9）；`PLATE_*` vs `PLATE_NO_FACET`（G23）；`FULLY_SUPPORTED` 的呈現（D-026）；殼 QM6 vs MITC4 膜；端格節點載重的格內內力分佈（MC64 B3） |
| `old-defect` | FrameCore 臂錯、引擎臂對（有 oracle 證明） | 挫屈 −68% 序列（N16）；受載切段（C11）；浮空樑整包失敗以外的 D-017 差異 |
| `new-defect` | 引擎臂錯（有 oracle 證明）→ **回引擎修，不得帶病換裝**（D-034 否證 (2)） | — |
| `freedom` | 規格未定、兩邊皆可（例：tie-break） | `governingFibre` 對稱純彎 tie → TENSION（引擎定，本倉採） |
| `blocker` | 未歸類、或消費者需要而引擎沒有 | 任何未預歸類的差異**預設 blocker** |

| 線 | 內容 |
|---|---|
| **N22-a** | 靜定案反力與內力：EB 臂（`eulerBernoulli` 旗標）對 FrameCore rel ≤ **1e-9**（反力、位移）、端力 ≤ **1e-8**（tectonic MC32 凍結線；實測 1e-12 級但 1e-12 不是線） |
| **N22-b** | 殼帶級：合力 5e-4、應力 1e-2（MC32 殼帶級；drilling 實作自由度） |
| **N22-c** | 發布條件：`evidence/differential.jsonl` 零 `blocker`；`new-defect` 零（修完才發） |
| **N22-d** | 記名排除 ≤ 3 類（D-035 釘住共線對接 + 至多兩類），清單在 `diff_engines.py` 檔頭，超過重議 |
| **N22-e** | 分類規則改動 = 本表 dated 追記 + 下游結論降一級（鐵則 1） |

#### N23 契約一致性（CI）

| 線 | 內容 |
|---|---|
| **N23-a** | `python3 contract/check_contract.py` 綠：`contract/CONTRACT_SHA256` == 重算 |
| **N23-b** | 與 tectonic2 釘住 tag 的 `contract/CONTRACT_SHA256` **逐字相同**（CI 從該 tag 取檔比對；不同即紅——這就是「強制兩邊使用」的機制） |
| **N23-c** | `python3 contract/conformance/run.py --selfcheck` 綠（每條斷言路徑存在於 schema） |
| **N23-d** | Java `BsiCodec` 對 `hello.contractSha256` 不符回 `BSI_VERSION` 並拒絕啟用引擎（假 sidecar 注入） |
| **N23-e** | 契約檔的任何改動必須與 `CONTRACT_SHA256` 同 commit（CI 比對 diff 範圍） |
| **N23-f** | 語料對真引擎：`run.py --adapter tectonic` 與 `--adapter framecore` 各 exit 0（Phase 3 起；未實作前 CI 標 `not-run`，**不得標綠**） |

**expected-red 帳**：見下一節。

### 2026-09-02 新登記：換裝期的 expected-red 帳（鐵則 2 的照登，先於程式碼）

`verify.py` 對 tectonic 臂在 Phase 3 **不可能全綠**：14 條腿等引擎的 v1.4/v1.5 或等 fixture 依 D-042 改寫。
帳在 `sidecar/expected_red.json`（每條：tag、`verify.py` 行、原因、轉綠 Phase、等待的引擎單元）。**三條規則**：

1. 帳上的紅不算 CI 失敗；帳外的紅照樣紅。
2. **帳上卻綠 = 過期 = 紅**：讓一條腿轉綠的那個 change 必須同時把它從帳上刪掉。帳不會腐爛。
3. FrameCore 臂永遠不讀這份帳。

| tag | 行 | 原因（一句） | 轉綠 |
|---|---|---|---|
| `[S1]` `[S2]` | 781 / 803 | 板網格慣例（D-042） | Phase 3（fixture 改寫） |
| `[S4]` | 823 | 夾支邊 oracle；引擎板永不旋轉夾持 | Phase 3（改簡支閉合式） |
| `[S5]` | 849 | F76 退場 | Phase 3（退役） |
| `[S6]` | 872 | `PLATE_*` 碼在引擎不存在 | Phase 3（MC65a 對表） |
| `[C1c]` | 214 | 讀 `governingFibre` | Phase 3（MC65a） |
| `[C11]` | 547 | 線反轉：不切段 | Phase 3（改寫） |
| `[C12]` `[C13]` `[C14]` | 577 / 619 / 654 | 特徵值 lane 在 v1.5 | Phase 4（MC66a/b） |
| `[N17]` | 1379 | 未接地分量分離在 v1.4 | Phase 3（MC65a） |
| `[N18]` | 1449 | 狀態字串與第五態 | Phase 4（MC66a） |
| `[M1]` `[M2]` | 1247 / 1307 | brick = monolith | Phase 3（改寫） |

其餘腿（含 `[S0]`、`[S3]`、`[S7]`、`[S8]`、`[S9]`、`[C15]`、`[C16]`、`[J]`、`[P]`、`[T]`、N14/N15）**預期綠**；若首跑紅，照登進本帳（附原因與 Phase），不得靜默加入。

## Evidence

每次 gate 執行產生的紀錄（`evidence/verification.json` 的 `identity` 節）至少包含：

- 引擎 checkout 的 commit SHA 與 worktree 是否乾淨——**查不到即 gate 紅**（#47）
- 出貨二進位的 sha256
- 判準與轉接層原始碼的內容雜湊（`verify.py`、`main.cpp`、`json.hpp`、`evidence.py`）
- 主機平台

release workflow 另外驗證「evidence 記錄的二進位 sha256 == dist/ 出貨的那顆」，
過期紀錄擋發布（#48）。

> 本節第一版寫「用 `frame_v2_abi_version()` 這三個符號,它們是免費的」——那是
> frame_capi_v2 的符號,而 frame_capi_v2 從未接上（D-002 實況修正）。現行做法
> 是上面的 git SHA + 檔案雜湊,提供同等的可追溯性。

### 2026-09-03 凍結：N24 進程內引擎與打包合規（D-044；先於 Java 實作）

> 出處：`docs/DECISIONS.md` D-044（進程內載入、JNA、jar 零可執行檔）與 tectonic2 `docs/specs/LINUX_BUILD.md` L4/L7/L8。
> 受測者標在每條上：**Java** = `mod`/`forge` gradle test；**CI** = workflow；**引擎** = tectonic2 側（本倉不重複它的線）。

#### N24-a 打包合規（CI + `scripts/check_bundle.py`）

| 線 | 內容 |
|---|---|
| **N24-a1** | **jar 內零可執行檔**：任何 entry 名以 `.exe`/`.bat`/`.cmd`/`.sh` 結尾，或內容以 ELF（`7f 45 4c 46`）開頭而副檔名不是 `.so`，或以 PE（`MZ`）開頭而不是 `.dll` → FAIL。這條擋的是「換個名字就混進去」 |
| **N24-a2** | **出貨路徑不 spawn 子行程**：grep 型測試，`mod/`、`forge/` 的非測試原始碼零 `ProcessBuilder`／`Runtime.exec`；白名單只允許明示的 dev/CI 臂類別，且白名單本身入本檔 |
| **N24-a3** | natives 三方雜湊鏈不變（jar 位元組 == manifest == evidence），欄位加 `engineVersion`、`contractSha256` |
| **N24-a4** | 解包**不設可執行位元**（POSIX 權限測試）：函式庫不是要執行的東西，設了就是把它當成執行檔 |
| **N24-a5** | 授權：`META-INF/` 帶 OpenBLAS(BSD-3)、METIS(Apache-2.0)、tectonic2(Apache-2.0) 的授權文本；缺一即 FAIL |

#### N24-b 進程內引擎（Java）

| 線 | 內容 |
|---|---|
| **N24-b1** | `BsiNative.abiVersion() != 1` → 拒載並停用，**不嘗試呼叫任何其他函式** |
| **N24-b2** | `bsi.hello` 的 `contractSha256` 與 jar 內 `contract/CONTRACT_SHA256` 不符 → `BSI_VERSION`、引擎停用、**其後零動詞**（= N23-d，改以進程內路徑實作） |
| **N24-b3** | `NEED_BIGGER` 放大重試恰一次成功，且**同一請求**（不重送、不半途消費）；重試後仍不足 → 停用並說得出來 |
| **N24-b4** | `EngineLocator` 順序：設定 → `-Dbr.engine` → `BR_ENGINE` → 覆蓋目錄 → jar 內附 → 無。**覆蓋目錄優先於 jar 內附**（引擎可獨立更新的機制本身） |
| **N24-b5** | 跨語言：`-Dbr.engine=<libbsi_*.so>` 時走完 hello → vocab → declare → solve，回覆與 `run.py --adapter capi` **逐位相同**（C-2 的 Java 腿；沒有引擎時 `Assumptions` 跳過，**不假綠**） |

#### 誠實邊界（照登）

- **崩潰隔離沒有了**（D-044 的代價段）。本組**測不到**「原生崩潰不帶掉 JVM」——那不再成立。
  能測的是它的替代品：ABI 版本、雜湊協商、規模上限、可關掉的開關。**不得**把 N24 說成崩潰安全的證據。
- **Windows natives 本輪不存在**（tectonic2 LINUX_BUILD §5 B1）。N24-a 的 PE 分支與 N24-b 的 Windows 路徑
  **今天沒有 fixture**；有了 Windows 建置才算跑過。在那之前這兩條的 Windows 半邊是 [暫]。
- N24-b5 需要一顆真引擎；CI 有（`engine-linux` job 現建），開發機不一定有。

#### 2026-09-03 落地追記（N24-a 的實作與首跑；原文一字不改，本段為 dated 追記）

實作者：`scripts/check_bundle.py`（兩形狀分流）、`scripts/stage_natives.py`（provenance）、
`forge/build.gradle` 的 `bundleEngines`（一支任務兩形狀）、`scripts/package_natives.sh`、
`mod/core` 的 `BundledNatives` + `NoSubprocessTest`。

| 線 | 落地 | 首跑 |
|---|---|---|
| **N24-a1** | `check_bundle.py` `executables_in()`：副檔名（`.exe/.bat/.cmd/.com/.sh/.ps1/.msi/.app`）**或**魔數（ELF / PE / Mach-O）而名稱不說 library。natives 形狀硬 FAIL，sidecar 形狀**清點後照登**（那正是 D-044 要退掉的東西，數字每次都要看得見） | sidecar jar 清點 = **2 個**（`br-sidecar`、`br-sidecar.exe`），符合預期 |
| **N24-a2** | `NoSubprocessTest`：掃 `mod/api`、`mod/core`、`forge` 的 main 原始碼。白名單**只有一筆**：`SidecarProcess.java`（D-013/D-027 的出貨形狀，隨 SWAP_PROGRAM phase 3 一起退場）。**反向也查**：白名單上已不 spawn 的類別必須移除，否則測試紅 | PASS，掃到 >20 檔，非白名單 spawn = 0 |
| **N24-a3** | natives manifest 七欄 `os arch file sha256 size engineVersion contractSha256`；`stage_natives.py` **載入函式庫問 `bsi.hello`** 取得後兩欄，gradle 再對 jar 內位元組與 `contract/CONTRACT_SHA256` 重算比對 | 首跑：`tectonic-1.2.0+1605437-dirty`、contract `c45f51fe7fca…`、sha256 `67ce0d0d2db0…` |
| **N24-a4** | `BundledNatives.ensure()` **不呼叫任何 chmod**；`BundledNativesTest.theUnpackedLibraryIsNotExecutable` 直接查 POSIX 權限位元；`stage_natives.py` 也把暫存副本設成 `0644` | PASS |
| **N24-a5** | `third_party/` 新增 OpenBLAS(BSD-3)、LAPACK/LAPACKE(BSD-3 + Intel 變體)、METIS(Apache-2.0)、tectonic2(Apache-2.0)、GCC Runtime Library Exception 3.1；`check_bundle.py` 在 natives 形狀下要求 jar 內 `META-INF/third_party/` 具名含 OpenBLAS / METIS / tectonic2 | 五份文本已入庫 |

**照登的缺口與量測（不是待辦，是現況）**：

1. **N24-a3 今天只有兩條腿，不是三條。** sidecar 形狀的第三條腿是 `evidence/verification.json`，
   而 `scripts/evidence.py` 驅動的是 sidecar 協定、對 BSI 一無所知。natives 形狀的鏈是
   **jar 位元組 == manifest == 引擎自己講的話**（`bsi.hello` 的回覆）。後者確實獨立於建置腳本的變數，
   但它不是「跑過驗收套件」的意思。evidence 的 BSI 臂具名為 SWAP_PROGRAM phase 3 的工作。
2. **`check_bundle.py` 的 CAFEBABE 教訓（照登）。** N24-a1 第一版讀四個位元組就判定，
   於是把 jar 內**全部 97 個 `.class`** 判成可執行檔——Java class 的魔數與 Mach-O universal binary
   **完全相同**。一條每次都喊狼來了的 gate 等於沒有這條 gate。修法是再讀四個位元組：
   class 檔接的是主版本號（45 起跳），fat binary 接的是架構數（個位數）。**這是「只看自己預期之處」
   的反面版本：看得太寬也會失去牙齒。**
3. **函式庫 28.8 MB**（`strip --strip-unneeded` 後 28.0 MB，只省 2.8%）。體積幾乎全部來自
   靜態 OpenBLAS 的多架構 kernel。這對「一個 jar 丟進 mods/」是實質成本，**照登不粉飾**；
   縮減手段（`DYNAMIC_ARCH=0` + 指定 TARGET、或裁 kernel 集合）未實作、未量測，不列入能力。
4. **`-dirty` 的引擎不得進發行版**：`stage_natives.py --require-clean`（`package_natives.sh` 預設帶）。
   本輪首跑的引擎正是 `1605437-dirty`，所以**首跑是開發流程的量測，不是發行**。
5. **`dist/` 本輪不動**。natives 形狀寫進 `dist-natives/`，`package_natives.sh` 從不碰 `dist/`——
   `package.sh` 結尾是 `rm -rf dist`，而 `dist/` 是**追蹤中的**、已驗證的 sidecar 發行物。

##### N24-a 的牙齒（2026-09-03 首跑，`scripts/check_bundle_selftest.py`）

`check_bundle.py` 已被加嚴**三次**，每次都是因為某個注入帶著全綠走過去（本檔 2026-08-23b）。
所以這次不是讀出來的，是**試出來的**：七個注入打在 `package_natives.sh` 剛做好、且已經綠的 stage 上，
每一個都必須把 gate 打紅。`package_natives.sh` 與 CI 各跑一次。

| 注入 | 結果 |
|---|---|
| 函式庫改名成 `.exe` | CAUGHT（副檔名分支） |
| ELF 換成無辜檔名 `assets/blockreality/helper` | CAUGHT（魔數分支） |
| 塞一支 `#!/bin/sh` 腳本 | CAUGHT（shebang 分支） |
| 函式庫翻一個位元 | CAUGHT（jar ≠ manifest） |
| manifest 宣稱另一個契約雜湊 | CAUGHT（引擎與模組不同介面） |
| 抽掉 OpenBLAS 與 METIS 授權文本 | CAUGHT（N24-a5） |
| 同一個 jar 放兩種引擎形狀 | CAUGHT |

**`SELFTEST ALL PASS (7 injections, 0 slipped)`**。
每個 case 前都重算 `SHA256SUMS.txt`，否則七條全部會被「多餘檔案」規則攔下、**證明不了它們各自具名的那條**。

##### 2026-09-03b CI 首跑，兩個真紅（照登，原文一字不改）

判準登記之後的**第一次 CI 實跑**抓到兩件事。兩件都不是判準寫錯，是判準在工作。

**1. `LangKeysTest` 紅：`keys with no en_us string == [br.engine]`**

`LangKeysTest` 把原始碼裡每一個 `"br.*"` 字面量都當成「遊戲可能會要的翻譯鍵」——**推導而非列舉**，
這正是它的價值：新鍵不必有人記得登記就會被要求翻譯。代價是 `-D` 系統屬性名長得一模一樣。
`br.sidecar` 早就在 `NOT_LANG_KEYS` 裡，且註解寫著「唯一長得像鍵的字面量」；`br.engine` 是第二個。

處置：加進同一個集合，**並把「什麼情況才准加」寫進註解**——該字面量必須是傳給
`System.getProperty` 或等價物、且從不進翻譯查詢，**grep 可驗**。因此是具名屬性，
不是整段前綴豁免。**加進這個集合就是在弱化這道 gate，所以要有理由。**

**2. `engine-linux` 紅：`remote: Repository not found`**

**量到的事實**：tectonic2 對本帳號是私有的，`GITHUB_TOKEN` 讀不到另一個專案的私有倉庫。
所以這個 job **在 `TECTONIC2_TOKEN` 存在之前根本跑不起來**。

處置**不是**把它 skip 掉印綠字——那正是本檔一直在記的病。做的是**把規則搬到跑得起來的地方**：

| | 現在在哪 | 要不要 token |
|---|---|---|
| N24-a1 jar 零可執行檔 | `contract` job | **不要** |
| N24-a3 manifest 對位元組 + 契約一致 | `contract` job | **不要** |
| N24-a5 授權 | `contract` job | **不要** |
| 七個打包注入 | `contract` job | **不要** |
| 引擎建得起來、L7 自足 | `engine-linux` | 要 |
| N24-b5 跨語言逐位 | `engine-linux` | 要 |

搬得動的理由：**N24-a 測的是 jar，不是力學。** stub engine 是一支真的實作 `bsi_capi.h`、
會回 `bsi.hello` 的共享函式庫，`stage_natives.py` 與 `check_bundle.py` 看的東西它全都有。
實測：stub 過 `stage_natives.py`（`bsi-stub-0.0.1+stub`，契約雜湊相符）、jar 建得出來、
`check_bundle.py` 綠、`SELFTEST ALL PASS (7 injections, 0 slipped)`。

`engine-linux` 的每一步都用 `steps.reach.outputs.ok` 擋住，**最後一步 `if: always()`**，
沒 token 時發 `::warning::` 並寫 `$GITHUB_STEP_SUMMARY`，逐條列出「這次沒量到什麼」。
**登記為 [暫]，直到 `TECTONIC2_TOKEN` 存在為止。** 在那之前**不得**把
「引擎建得起來」或 N24-b5 當成 CI 驗過的事。

### 2026-09-03c 登記：對位帳（`docs/ALIGNMENT_LEDGER.md`）發現的判準缺口與更正（先於程式碼；沒有任何一條線因本節移動）

| 判準 | 發現 | 處置 |
|---|---|---|
| **N19-b** 挫屈半邊（`bucklingCritical == 引擎 stability == critical`） | BSI v1 **沒有每格挫屈判定欄位**（`blocks.flags` 只有 overloaded/indicative；`buckling` 是每島 `state/factor`）；tectonic2 MC64 的 `stability` u8 只在 v2 wire。消費者自己比 `factor < 1` 違反契約 P2 | 挫屈半邊 **[暫]**，直到契約加法批次 #1 落地 `blocks.flags` bit2 `bucklingCritical`（引擎 double 定案、只在 commit 軌）。在此之前 HUD 對挫屈只准顯示「未評估」（N19-e 的情形延長） |
| **N23-f** 旗標名 | `run.py` 沒有 `--adapter tectonic` / `--adapter framecore`；實際是 `capi` / `engine` / `sidecar` / `frame_v2` | 更正：tectonic 臂 = `--adapter capi --lib libbsi_tectonic.so`；FrameCore 臂見下 |
| **N22 / N23-f** FrameCore 臂 | `diff_engines.py`、`br-sidecar-fc`、FrameCore 的 `bsi_engine_vtable` **皆無實作、無 issue**；N22-c「零 blocker 才發布」今天無法產生差異帳 | 登記為無主項（對位帳 C1）；裁決「保留 vtable 對數臂」或「降級為語料 + `verify.py` 對 tectonic 臂」後開 issue；降級則 N22 dated 降一級 |
| **N23-b** 跨倉漂移 | 現行實作比的是「本倉 pin == `.github/tectonic2-contract-ref` 記的雜湊」，ref 釘 tectonic2 固定 commit；tectonic2 `main` 單邊改契約時**本倉 CI 仍綠**（有 token 的 `diff -r` 也是對 pinned commit）；tectonic2 CI 對本倉零檢查 | 登記為 **[暫]**：正確形狀 = tectonic2 CI 抓本倉 `Main` 的 `contract/CONTRACT_SHA256`（本倉公開，無需 token）+ 本倉有 token 時對 tectonic2 `main` 比。執行期握手（N23-d `BSI_VERSION`）仍是最後一道 |
| **N24-b5** 決定論前提 | Java 與 runner 逐位的前提是兩邊都 `numThreads:1`（runner 預設注入；Java 由呼叫者傳） | #89 接線的驗收條件：預設 1（D-041 §6），`hello.threads` 只回報不採用 |
| 語料缺口 | 無 `dc > 1`、無 `partial`、無 `numThreads` 越界、無 `bsi.cancel`、**無 C-12（f32）case**（`conformance/README.md` 表列的檔名不存在）⇒ N20-d 的 `precision.storage:"f32"` 在引擎側永遠不可宣告 | 契約加法批次 #1 一次補齊（tectonic2 主導判準，兩倉同步 hash bump） |
| `bsi_capi_open` 選項 | Java 送 `numThreads` 於 open 選項（`InProcessEngine.java:46`），host 只解析 `log/probe/assumeCaps`，未知鍵靜默忽略；契約無 open 選項 schema | #89 移除；契約提案 open 選項 schema + 非 `x-` 未知鍵拒絕（P6） |
| `bsi.hello` 的 `arena.supported` | 進程內路徑送 `true`（`BsiHeaders.java:29`），Java 無 arena 實作 | #89 改 `false`（T-A） |
| N24-a2 白名單 | 不變：`SidecarProcess.java` 一筆；`BundledEngine`（sidecar 形狀）的 #80 併發缺陷未修，只在 `BundledNatives` 修 | 隨 #89 一起退場；#80 加一則說明 |
