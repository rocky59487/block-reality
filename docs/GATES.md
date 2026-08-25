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
