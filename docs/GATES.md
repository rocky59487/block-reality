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

| 2026-08-21 | verify.py [S4]／[S9] 夾支方板中心彎矩參考係數 | `0.0231`（Timoshenko Table 35 / Roark 11.4 case 8a）,8 元素容差 1% | 參考係數本身是錯的。13 點差分解雙調和方程 n=20/40/80 + Richardson 外推得 **0.02290512**;同一次運算把該表另外兩欄逐位重現（w_max 0.00126532 對 0.00126、M_edge −0.0513338 對 −0.0513）。三取二相符、不符的那個差 0.85%,而三位有效數字捨入只能解釋 ±0.217%。改用真值後,8/12/20 元素的誤差是 1.75%／0.79%／0.28% | 判準**改成收斂階**：per-mesh 線 2.0%／1.0%／0.4%,加上 h→h/2 誤差比對應的 p = 2.0 ± 0.075（實測 1.983、2.014）。8 元素那條線 1% → 2%,照鐵則 1 登記 | 下游降級:evidence 舊表的「收斂地板」敘事**撤回**——那是錯參考解造出來的假象,不是 MITC4 的性質。舊表引用的 20 元素 0.57% 亦作廢（真值 0.28%,雖然更好,但那個數字當時不可信）|
| 2026-08-21 | `.github/workflows/ci.yml` engine-gates job | 「CI 綠是 merge 條件」——實際上該 job **恆綠** | `cmd \| tail -3` 在 GitHub 隱含的 `bash -e`（無 pipefail）下回報 tail 的退出碼。本機兩路實測:`bash -e` → 0,`bash -eo pipefail` → 1 | workflow 層 `defaults: run: shell: bash`,使 GitHub 改用 `bash --noprofile --norc -eo pipefail` | 下游降級:**2026-08-20 之前所有「CI 綠」的引用一律無效**,包含本表上一節末句與 RELEASING.md。第一次真正紅的執行:commit `7d9982e`,run 32621276032,engine-gates job failure,失敗原因是本次新增的 19 條 gate |
| 2026-08-21 | 紀律自登:本輪 [C16]/[J1]/[J2]/[J3]/[P1]/[P2] 六節新 gate | — | 判準先 commit（`7d9982e`,對出貨引擎 **FAILED 19 of 251**,輸出全文在該 commit 的 CI run 32621276032）,實作在下一個 commit（`e8c39cb`）轉綠 | 鐵則 1 **完整滿足**,含 D-4 建議的「先 commit 紅版 gate」 | 無;這是本表第一次不必登記順序滑失 |
| 2026-08-21 | 出貨文件的檢查項計數 | 人工抄寫 | 「219」出現在九份文件與 GitHub Release body,真值 218（`grep -c PASS` 把結尾 ALL PASS 也算了）;151／164／216 三個更舊的數字同時仍活在 outreach 文件裡 | verify.py 自印總數,`scripts/check_docs.py` 逐份文件比對,pattern 對不上檔案即 **FAIL 而非 skip** | 舊 Release body 的「219 acceptance checks」為**對外不實陳述**,以本次發行的真值取代 |
| 2026-08-21 | dist/br-sidecar.exe | 無任何 gate:不比雜湊、不在 evidence、連存在與否都沒人檢查 | 端到端模擬:拿掉 .exe 後照 package.sh 重生 SHA256SUMS,四道 gate 全綠 | package.sh 缺 mingw 直接 `exit 1`（除非 `ALLOW_NO_WINDOWS=1`）;evidence 記 `identity.binary_windows`;ci.yml 與 release.yml 各比一次雜湊 | 下游:v0.1a／v0.2a 之前所有「跨平台出貨」宣稱只有 Linux 那顆有紀錄支撐 |

### 2026-08-21 新增的 gate

引擎側（`sidecar/verify.py`,總數 218 → 251）：

- `[C16]` — `steel_rect_150x300` 對懸臂閉合解。它是唯一「有方塊、有 README 宣稱、但在本檔與全部 Java 測試裡各 0 次」的 token
- `[J1]` — 跨材料接點:木樑架磚柱是**一座**結構;自重含樑的 791 N;玩家掛的 500 kN 進得了 `applied`;外加**反例**（同一根樑高一格、誰也不碰 → 仍是三座島）
- `[J2]` — 共線對接只產生兩根構件,各保有自己的斷面（接合處那一公尺不得被建模兩次）
- `[J3]` — 單一方塊不因為碰到板就變成構件（MECH-03 的自重放大 2.3 倍）
- `[P1]` — 兩端落地的樑要被解出來:固端彎矩 wL²/12、跨中 wL²/24,實測相對誤差 1e-16
- `[P2]` — 整根貼地的 run 是「完全支承」不是機構

Java 側：

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
