# v0.2a 發布後審核（2026-08-23）

審核對象：**已公開發布**的 v0.2a（tag `v0.2a` = `b9d5327`，asset 公開於 `2026-08-23T06:28:06Z`）
以及 Main（`8470253`，比 tag 多 4 個 commit）。

方法：8 個 Opus 代理分互斥維度實跑，主編者對每一條 high 以上的發現獨立重現。
證據檔在 `.audit_v02a/R{1..8}_*.md`。

---

## 結論

**上一輪 8 條 §A 阻擋，實質全部處理了，其中三條修得比我要求的更好。** 這不是客套：

- A-1 的自重與內力我用手算逐位對過（rel err 1.95e-16），跨材料接點現在是**剛接**，25 種材料組合全過
- A-4 把判準從單點容差**升級成收斂階 gate**（p = 2.0 ± 0.075），這比原本強——參考常數若錯，誤差序列會出現地板、p 會掉出範圍
- A-8 的根因治好了：`verify.py` 自己印 `ALL PASS (251 checks)`，數字不再靠 `grep` 數
- `docs/GATES.md` 的移線登記五欄齊全、下游降級明列；D-025／D-026 兩條新決策都備了理由與否證條件
- 逐條比對整個 PR 區間，**沒有偷偷放寬任何既有容差**

**但這一輪找到 3 條比原本 §A 更嚴重的，而且都是修復本身造成的。** 加上發布流程有一個結構性缺口。

發布已經出去了，所以下面按**你現在能做什麼**分類，不按嚴重度。

---

## §1 · 現在就能改，不用重發（10 分鐘）

### 1-1 🔴 已公開的 Release 頁面有一句不實陳述

Release body 逐字：`every number is gated: 251 acceptance checks against closed forms and invariants per build`

- **前半是全稱句而且是假的**。`docs/GATES.md:130-142` 是你自己寫的：「**SHEAR 與 TORSION 兩個模式至今零 oracle**…依鐵則 2，這兩個模式**不得當成已驗證能力引用**」
- 同一句把 251 條全部歸類成 closed forms and invariants，實際閉合解比對是 **41 項**（`evidence/VERIFICATION.md:32-36`：31 非零 + 10 零參考），其餘多為布林性質斷言
- 同頁另一句 `reports per-member and per-plate demand/capacity with the governing fibre named` —— governing fibre 可以是 SHEAR 或 TORSION
- 「What it deliberately does not do yet」四條裡沒有這一條

```bash
gh release edit v0.2a --notes-file <改好的檔案>
```
改法：`251 acceptance checks against closed forms, solver-independent invariants and transport-equivalence oracles`（照 `README.md:207` 的三類寫法），並在 does-not-do 補一條 SHEAR/TORSION。

**Release body 其餘部分我逐條核過，沒有其他假話**：九種方塊對得上 `BRContent` 九次 register、權限對 `BrPermissions` 完全一致、`.exe` 確實是 PE32+ x86-64、macOS 說明對 `install.sh:163-171` 屬實、非 draft 非 prerelease、發布後未經人工編輯。

### 1-2 🔴 SHEAR/TORSION 的揭露只覆蓋 2/10 個對外面

寫得最好的那兩份（`README.md:194-201` 與 `docs/GATES.md:130-142`）沒人會讀；八個會被讀到的都缺：
Release body、`docs/RESEARCH_BRIEF.md:67-78`（Known limits）、`dist/START-HERE.txt:121-132`、`dist/讀我-中文.txt:103-111`、`docs/outreach/OUTREACH.md:63-65`、`COMMUNITY.md:35-37`、`QUICKSTART.md:343+`。

`OUTREACH.md:63` 最嚴重——它在**逐條列舉**「Honest boundaries, stated in the README」時，漏掉 README 裡唯一一條被鐵則 2 點名的邊界。教授會拿這封信當你的誠實基準。

改 `dist/` 的兩份說明書時**必須同時改 `scripts/dist-docs/` 的來源副本**，否則 `package.sh:122-123` 會 cp 回舊版。

### 1-3 六個過期數字，其中三個是本次發行自己明文撤回的

| 檔案:行 | 現在寫 | 應該是 |
|---|---|---|
| `docs/RESEARCH_BRIEF.md:55` | `1.6e-10` | **1.216e-14** |
| `docs/RESEARCH_BRIEF.md:57` | `0.57%` | **0.28%** |
| `docs/RESEARCH_BRIEF.md:61` | `52 ms median` | **45.8 ms median**（52 是 max 51.881，把 max 當 median 報）|
| `QUICKSTART.md:310` | `0.57%` | **0.28%** |
| `README.md:215`／`:423`、`QUICKSTART.md:333` | `4.5 ms / 28.3 ms` | **2.2 / 14.42 ms（省 84.7%）** |

`1.6e-10` 與 `0.57%` 都是你自己在這次發行裡撤回的：`README.md:209` 逐字寫「that floor turned out to be the old wire's 10-digit truncation」、`evidence/VERIFICATION.md:172-173` 逐字寫「the published figure was not trustworthy」。README 與 QUICKSTART 都改了，**只有給教授看的那份沒改**，而 `OUTREACH.md` 的信件範本就叫對方讀它。

傳輸數字比值剛好還對得上（84%），所以肉眼掃不出來。引進它的 `301aa18` 訊息逐字寫「every published number is the record's」。

`QUICKSTART.md:310` 特別難堪：`:307` 上面三行才剛警告過另一個被撤回的數字。

### 1-4 出貨的 zip 沒有 LICENSE、沒有 NOTICE

已發布 zip 共 8 個檔，`LICENSE` 與 `NOTICE` 都在倉庫根目錄但**沒進包**。Apache-2.0 §4(a)/(d) 未滿足。
更深一層：FrameCore（MIT）與 **Eigen（MPL-2.0，`sidecar/CMakeLists.txt:6` 的 `EIGEN_MPL2_ONLY`）都被靜態連結進兩顆出貨二進位**，倉庫裡沒有任何一份第三方授權全文；`NOTICE` 只用一句「Other third-party components retain their respective copyright and license notices」帶過，沒點名 Eigen。

`package.sh` 加兩行把 LICENSE/NOTICE 放進 zip 與 SHA256SUMS，倉庫加 `licenses/` 放 MIT 與 MPL-2.0 全文。

### 1-5 沒有 Mojang 商標免責聲明

`NOT AN OFFICIAL MINECRAFT PRODUCT` 在專案文件 0 命中。你計畫上架 CurseForge/Modrinth 並開 Sponsors——涉及金流後這條的檢視力度會提高。

### 1-6 一次 API 呼叫：`enforce_admins`

分支保護真的開了，而且 required context 名稱與實際 job 逐位元吻合、`strict:true`、force push 與刪除都禁——做得對。
但 `enforce_admins: false`，而 collaborator 只有 `rocky59487 admin` 一人。**PR #26 就是他自己合的**，那個合併無論 check 什麼狀態都會過。

```bash
gh api -X PUT repos/rocky59487/block-reality/branches/Main/protection/enforce_admins
```

---

## §2 · 需要 v0.2b（程式碼修正）

按修正成本排序，最便宜的在前。

### 2-1 🔴 引擎會 stack overflow 而死，且不可復原

`sidecar/main.cpp:1209-1223` 的遞迴 `DisjointSet::find` 沒有 union-by-rank。

**我自己重現的**：
```
CONTROL 單一材料      n=60000  ok=True islands=1        <- 方塊更多，不崩
交替材料              n=40000  NO REPLY rc=0xc00000fd   <- STATUS_STACK_OVERFLOW
```
process 整個死掉，同一條 pipe 上後續請求完全沒有回覆，stderr 是空的，Java 側只看到 pipe 關閉。伺服器端 = 該維度分析停擺到有人重啟。

`DisjointSet` 是舊碼（`551cf79`），**新的是誘因**：A-1 的 extension pass 把原本各自獨立的島串成一條 `parent[b0]→b1→…→bN` 的長鏈。門檻約 19,000 個 segment。舊的 alternating-**section** 路徑在 100,000 顆也會崩。

**修法三行**：`find` 改成迭代（兩趟路徑壓縮），或加 union-by-rank。一次關掉兩條路徑。

### 2-2 🔴 鏡像的同一座結構會得到不同的 D/C，足以翻轉 pass/fail

`sidecar/main.cpp:396-399`：extension 無條件往 `+axis` 長，所以共用的那一格永遠歸座標較小的材料。

**我自己重現的，逐位吻合**：
```
TTBB          applied_y = -1817.0082   maxDC = 0.26335   members = [(timber,2000),(brick,1000)]
BBTT (鏡像)   applied_y = -3040.7076   maxDC = 0.45258   members = [(brick,2000),(timber,1000)]
```
**同一組方塊、只是排列方向不同，模型自重差 67%。**

| n | P maxDC | 鏡像 maxDC | 偏差 |
|---|---|---|---|
| 5 | 0.66746 | 0.85417 | +28.0%（都過）|
| **6** | **0.86744** | **1.18173** | **+36.2%（P 過、鏡像倒）** |
| 7 | 1.50179 | 1.81032 | +20.5% |
| 9 | 2.66984 | 3.10133 | +16.2% |

實體對稱的結構（brick3｜timber3｜brick3，支承在 x=0 與 x=8）被建成不對稱模型：左墩 L=3000 dc=2.0777，右墩 L=**2000** dc=1.7787——同一根柱子少 17% 需求。

**這觸發了你自己登記的否證條件。** `docs/DECISIONS.md` D-025 否證條件 (2) 逐字：「若實測顯示『延伸一格』造成的長度誤差在真實玩家結構上使 D/C 偏離超過 **5%**，改為在面上插入零長度剛性連結」。實測 16–36%。

`J2` gate 只驗 member **數量**與 section 名稱，**從不驗 `lengthMm`**；沒有任何鏡像 gate。

修法：接合格的歸屬要有對稱裁決（按兩側原始塊數比／固定歸軸向較長者／兩邊各分半米），並補一條「結構與其鏡像的 maxDC 必須相等」的 gate。

### 2-3 🔴 全落地島把玩家的荷載靜默吞掉，而且比修復前更安靜

`sidecar/main.cpp:853-867`：`if(!anyFreeNode)` 早退發生在 `nodalLoads` 已填好、`validate()` 已過**之後**，`appliedN` 累加**之前**。

**我自己重現的**：
```
Z4  500kN 掛落地島（旁邊另有正常島）
→ ok:true, error:null, singular:false, applied:[0,-24642.72,0]（只有另一島自重）, maxDC 與無荷載時一字不差
Z5  3x3 落地板 + 500kN
→ ok:true, applied:[0,0,0], maxDC:0, unassigned 9 格
```

`isSupported`（`StructureManager.java:532`）判「下方是實心非結構方塊 = 落地」，所以**任何平放地上的梁列或樓板**都走這條。

**比修復前更沉默**：`dropRefusedLoads` 只在上一次 `ok:false` 後的 probe 才跑，這裡 `ok:true` 不觸發，無訊息；`isUsable()` false、`allSingular()` 要 `singular==true` 而現在是 false → 既不 commit 也不廣播 MECHANISM，**HUD 完全不動**。修復前同一個世界是 `singular:true, members:[]` → 會廣播「沒有東西撐住」。**「講錯話」被換成了「不講話」。**

而 `main.cpp:614-619` 自己寫 `applied` 是「an INDEPENDENT statement of what should have been carried」；這裡它說了 0。`:1255-1262` 的 fail-closed 檢查剛好放行，然後早退吃掉——正是那段註解說要防的 silently-safe answer。

修法：早退前仍把 `nodalLoads` 與該島自重累進 `appliedN`/`reactionN`（兩者相等、residual 保持 0）；或該島有 nodalLoad 時改回 `ok:false`，讓現成的 probe/`dropRefusedLoads` 路徑接手。

### 2-4 單格墊塊仍然材料相依，整根柱從 members 消失

`sidecar/main.cpp:392` `joinable` 要求鄰塊已被某 run 認領，配合 `:342` 的 `run.size() >= 2` → 單顆方塊永遠不成 run。

**我自己重現的**（3 格鋼柱站在單格支承墊塊上）：

| 墊塊材料 | singular | members | unassigned |
|---|---|---|---|
| steel | False | **1** | [] |
| concrete / brick / timber | **True** | **0** | 只有墊塊那一格 |

換墊塊材料就換答案 → **D-025 宣稱修好的 §7.6 違反在單格情形殘留**。柱下墊石是標準工法。整根柱消失、`unassigned` 只列墊塊，玩家看不出哪幾格出問題，HUD 說「沒有東西撐住它」——正是 A-6 當初被提出的同一類錯誤訊息。

`J1` gate 用的是 **3 格**磚墩（`for h in range(3)`），所以這個缺口在 gate 的視野外——**gate 是照著修好的幾何寫的**。

修法：`joinable` 對「單顆非板方塊、與 run 端點面相鄰」放行（當純節點納入 run，不生成自己的 member）；`J1` 的 pier 高度參數化成 `range(1,4)`。

### 2-5 `onExplode` 漏了 `onBreak` 專門為此加的 LOWEST priority

`StructureManager.java:321`（`ce1662c` 新增）是預設 NORMAL；同一個檔 `:300` 的 `onBreak` 是 `@SubscribeEvent(priority = EventPriority.LOWEST)`，javadoc 說明得很清楚：保護類 mod 取消 break 之後才輪到我們。

而 `ExplosionEvent.Detonate.getAffectedBlocks()` 是**可變的、正是給保護 mod 清空用的**。LOWEST 的保護 mod 跑在我們之後 → 方塊沒被炸掉、模型卻已經刪了 → 分析在一棟完好的建築上算出一個不存在的洞。只有 chunk reload 或 `/br scan` 能恢復。

一行。

### 2-6 `p.dimension` 是三個 `writeUtf` 裡唯一沒 clip 的，而它是唯一由 datapack 決定的

`StressResultPacket.java:203` `buf.writeUtf(p.dimension, 256)`；`:226`／`:247` 兩個引擎 token 都 clip 了。

`9920eef` 的理由逐字是「encode 跑在廣播迴圈裡，一個過長 token 會炸掉對所有玩家的發送」。而 token 來自引擎目錄（「只有換引擎才可能觸發」），**dimension 來自 datapack 作者寫的 ResourceLocation，vanilla 不限長度**。306 字元的 dimension → `EncoderException: String too big`，在 encode 就拋。

一行：`writeUtf(clip(p.dimension, 256), 256)`。

### 2-7 A-5 的 probe 分支繞過 revision gate

`StructureManager.java:563-571`：probe 早退寫在 `gate.isStale(result)`（`:592`）**之前**，所以 probe 結果從不做時效檢查。而丟荷載是**承諾軌的變更**。`SidecarClient.solve` 的 javadoc 明文要求呼叫端自己比對 revision。（`javap -c` 確認出貨 jar 的 bytecode 順序一致。）

可觸發：加荷載到孤立方塊 A → probe 送出 → **probe 回來前**在旁邊放同材料 B（bump revision，A+B 成合法 run）→ probe 舊回覆說 `unassigned=[A]` → 荷載被刪，玩家收到「該方塊本身構不成結構元素」，**此刻這句是假的**。

修法：probe 分支開頭加 `if (gate.isStale(result)) { probeWithoutLoads = true; dirty = true; return; }`。

### 2-8 挫屈預設開啟，是三次方複雜度

（既有問題，本次 diff 未觸及，但值得知道）200 節點 0.44 s、500 節點 7.28 s、1000 節點 **65.7 s**、2000 節點 **>180 s**。關掉挫屈後整條鏈是線性的（500→15000 節點：0.12→4.45 s）。

一棟 2000 節點的建築，預設設定下一次 solve 要 8 分鐘，對伺服器等同 hang。

---

## §3 · gate 與流程的缺口

### 3-1 🔴 v0.2a 的檔案在它自己的 CI 跑完前 124 秒就公開了

**我自己核的時間戳**：asset published `06:28:06Z`；check-run `Java suites, cross-language against the shipped engine` completed `06:30:10Z`。

那個 job 的最後一步正是 `check_docs.py`——**當初因為「219 acceptance checks」印進公開 release 頁面才建立的那道 gate**。這次全綠是運氣，不是機制。

根因是結構性的：`release.yml` 無 `needs:`、無 `workflow_run:`、不查任何 check-run，只跑 `sha256sum -c`、版本一致性、evidence 雜湊比對，**從不執行 verify.py / Java 套件 / check_docs.py**。而 `ci.yml` 的 push 只掛 `branches:[Main]`，**tag push 根本不觸發 CI**；tag 又零保護（`tags/protection` → 404）。

所以 `git tag v0.3a <任一 commit> && git push` 只要 dist/ 內部自洽就會發布，該 commit 的 gate 一次都不跑。

修法兩件都要：(a) Publish 前加一步跑 `verify.py`（hosted runner 跑得動）；(b) 檢查被 tag commit 的 check-runs 兩條 required context 皆 success。

### 3-2 出貨的 Windows 引擎整條鏈零 gate

`package.sh:54` 的 `verify.py` **只跑 `$STAGE/br-sidecar`**（host/Linux 那顆）；`.exe` 是 `:64` 直接 `cp` 進 stage，**沒有任何一步驗過它的行為**。唯一綁行為的是 `evidence/replies-windows.jsonl` 的 8 條 determinism，而 `:112-121` 在雜湊對不上時**只印兩行提示就繼續**。

代理實測：把 cmake 換成產出 **12 bytes 的 `FAKE-WIN-EXE`** → mingw「存在」→ 完整走完打包、SHA256SUMS 自洽、release archive 產出、exit 0。

**設計是對的**（原生 Windows 錄回覆比 wine 更好），**強制力是選配的**。修法：把那個 mismatch 改成 `exit 1`。

另：`ALLOW_NO_WINDOWS=1` 逃生門把 A-7 原本的破壞行為原樣留著——刪掉入版控的已驗 `.exe` 並 exit 0。

### 3-3 `steel_rect_100x200` 是第二個沒有閉合解 gate 的 token，而 README 首頁宣稱每個都有

**我自己核的**：`steel_rect_100x200` 在 `verify.py` 只出現在 `:478`／`:483`，兩處都在 `[C10] a section change splits the run` 內，是拓撲斷言不是閉合解。另核 `concrete_rect_400x600` **確實有** `[C1b]` 的真閉合解 → **九個 token 八個有，只缺這一個**。

`README.md:125`（英）／`:353`（中）逐字：`Every token is gated against a closed form before it got a block`。

上一輪的 R-02 只修了我點名的那顆（150x300 → 新增 C16），**同一個洞在 100x200 原封不動**。附帶：括號出處 `(C1/C1b/C15)` 本身也過期，沒提 C16 與板的 S 系列。

建議把「每個有方塊的 token 都有閉合解 check」做成機械斷言，否則第三顆出現時同一件事會再來。

### 3-4 J1／J2／P2 三條新 gate 的覆蓋空白，正好對上三個 high

| gate | 空白 | 漏掉的 |
|---|---|---|
| `J1` | 用 3 格磚墩 | §2-4 單格墊塊 |
| `J2` | 只驗 member 數量與 section 名稱，不驗 `lengthMm` | §2-2 鏡像不對稱 |
| `P2` | 只測單一全支承島、無荷載案例 | §2-3 落地島吞荷載 |

正面的一半：把 J1/P1 的期望值改成修復前的行為，`FAILED 5 of 251`——**這幾條對「已回報的回歸方向」是真的有牙齒**。J1 還帶了反例（樑抬高一格仍須三座島），這是我要求的正確寫法。

### 3-5 不變式 5 在應力變號點附近是數學上做不到的

**我自己算的**（A=8123.456789012、Iz=2.6123456789e8、令 σ(y=50)=0）：

| 離中性軸 | rel |
|---|---|
| 0.01 mm | **3.43e-04**（超 1e-5 三十四倍）|
| 0.05 mm | 6.86e-05 |
| 0.20 mm | 1.72e-05 |
| 0.50 mm | 6.91e-06 |

絕對誤差上限 `8.84e-06 MPa`，對上峰值 386.7 MPa 是 **2.3e-08**。

這不是實作 bug——f32 傳輸下 `N/A` 與 `M·y/I` 兩項相消，相對誤差必然發散，**任何 f32 顯示軌都一樣**。問題在於：宣稱守住不變式 5 的那條新 gate（`StressResultPacketTest:128-193`）比對的是「場的每個欄位」與極端纖維的 11 站，**從來沒有取樣過 σ≈0 的點，也沒有碰 `sigmaAtWorldMm`**——而後者正是 `StressSurfaceRenderer.java:286` 每個頂點都在呼叫的函式。

現況是「宣稱相對、實測不成立、gate 剛好避開」。正解是把不變式 5 改寫成「相對於該構件峰值應力」的預算（餘裕三個數量級），並照鐵則 1 在 `DECISIONS.md` 登記移線。

### 3-6 封包的 shell 半邊實質沒有測試

9 個 shell 側突變**全部存活**，包括 `mxx` 與 `myy` 在線上對調（樓板彎矩場整個轉 90°）、`totalShells` 一律送 0（`shellsTruncated()` 永遠 false，HUD 不再說「我沒畫完」）。原因是唯一的 shell fixture 在關鍵處全是退化值（nxy=mxy=qx=qy=0、nxx==nyy、mxx==myy、四個角完全相同）。

member 那邊已經有 `awkwardField(k)`，照抄一個 `awkwardShellField` 即可。

### 3-7 `check_docs.py` 的兩個問題

- 沒有 JUnit XML 時 11 條 JAVA_* 全部靜默 `skip`，然後 `:127` 印 `f"{len(TABLE)} quoted counts all agree"`——**印的是表長度不是實際比對數**，exit 0。實測只比了 16 條卻宣稱 27 條全合。而 `GATES.md` 對這支工具的承諾是「pattern 對不上檔案即 FAIL 而非 skip」，缺測量值這一軸沒有同等處理。
- 只比對**計數**，不涵蓋精度數字——§1-3 的六個過期數字就是從這裡漏出去的。
- `:85` 只讀 XML 的 `tests` 屬性，不看 `failures`/`skipped` → `README.md:208` 的「188 tests, **all passing**」的 all passing 完全沒有 gate。

### 3-8 出貨引擎無法被第三方重建

`identity.engine.commit = 10395c3c…` 只存在於作者本機（`gh api .../commits/10395c3c` → HTTP 422），remote push 是 DISABLED。

**但內容是公開可還原的**：代理逐位元比對過，`git format-patch v4.0.0..10395c3` 的輸出與 `sidecar/patches/0001~0003*.patch` 串接後完全相同（只差兩個 trailer 空行）。`git checkout v4.0.0 && git am sidecar/patches/*.patch` 可重建內容，但 `git am` 會蓋上執行者身分 → 不同 SHA，evidence 記的身分永遠對不上。

**沒有任何文件過度宣稱這件事**——`ci.yml:5-10` 已明說 CI 建不出引擎，`GATES.md:95` 也登記過歷史。這點記在你帳上。缺的是**後果沒被寫下來**：審稿人可以驗 `main.cpp`（原始碼雜湊有釘且逐一相符），但驗不了力學所在的那一半。

最小成本三步：把三個 patch commit 推上公開 repo 並打 tag（一次性）、evidence 記下 mingw 工具鏈版本與 `SOURCE_DATE_EPOCH`、CI 加一條固定容器重建比對。**前兩步就能把「無法重現」降級成「可重現，只是要自己編」。**

---

## §4 · 其他（medium / low，按主題）

**文件與宣稱**
- `SidecarEngineTest.java:578` 仍用已撤回的 `0.0231` 當參考——是 CI `java` job 實跑的**現役 gate**。數值上不掩蓋失敗（12 元素誤差 0.07%，改對後仍 <1%，測試保持綠），是紀律與漂移問題。板常數現有 **4 個寫死點**，這個已經漂了。
- `docs/ENGINE_FINDINGS.md:258-310`「發現 6」整節仍在教錯的教訓——把 0.0231 當正確表值、ν 換算當唯一問題，然後建立了那個已被撤回的「收斂地板」。`:295` 的結語「把單位當成收斂性在讀，是這一輪最容易寫進論文而不被發現的一種錯」現在反諷地適用於它自己。留著會讓後續的人從這裡把 0.0231 帶回來。
- `docs/DECISIONS.md:93`（D-023）與 `docs/GATES.md:119` 仍掛著 `DisplayTrackPrecisionTest` 是「不變式 5 首個可執行 gate」——`753afe6` 已在 `GATES.md:111` 加更正，但原句沒劃掉。
- `README.md:182-184` 與 `:194-201` 自我矛盾：前者說每個 wire 數值都在 engine's own closed-form gates 之後，十四行後說 SHEAR/TORSION 沒有 gate。
- `sidecar/README.md:46`「Wine 實測 251 項全過」——這台機器沒有 wine，本次 determinism 方法是原生 Windows 錄回覆（`VERIFICATION.md:281` 明寫）。而 `check_docs.py:59` 只驗數字，**gate 正在替一個錯誤的方法宣稱背書**。
- `QUICKSTART.md:311`「資料層另外被 188 個測試釘住」——188 是 Java 總數，`check_docs.py:47` 把 pattern 映到 `JAVA_TOTAL`，**gate 正在強制執行這個誤植**。
- `docs/MEMBER_SEMANTICS.md:24-32` 自陳「run ≥ 4 格（L/h ≥ 7.5）讀成定量，2–3 格讀成指示性」，**零個對外文件提及**。玩家蓋門楣、短懸挑、小樓板天天踩到。
- `docs/outreach/COMMUNITY.md:42`「7 ms for a 200-member frame」全倉庫無來源；evidence 是 199 構件 45.793 ms。同一份文件 `:75` 自己寫「貼文前重新對一次，別背數字」。
- `outreach` 四處「251 項閉合解」把總數當閉合解數（實為 41），而 `check_docs.py:53-57` 把數字釘住了——**gate 替一個錯誤的語意背書**。
- FrameCore 在「要 credit / 要錢 / 要背書」的五個面（CITATION.cff、OUTREACH、COMMUNITY、FUNDING、Release body）都沒被點名，只出現在「解釋軟體」的面。`OUTREACH.md:51` 寫「solved by **a** finite element engine」，教授合理讀成你寫了求解器。而 `GATES.md:40` 是你自己寫的：「力學層是別人的引擎，我們的 gate 驗的是接線不是數值核」。**補這半句是加分項**，它把分工講清楚。
- `CITATION.cff:5-7` 用 `- name: "Rocky"` = CFF 的 entity（組織）欄位，BibTeX 會產出 `author = {{Rocky}}`。schema 整體 0 errors、**沒有捏造 DOI**、version/date/license 與 release 全部相符。
- `START-HERE.txt:81-83`「a beam supported at both ends sags, so its top fibre is in compression」——落地端被建模成**六向全固接**，固端梁在支承處**上緣受拉**，而且那裡才是治理站（實測 gStation=0，TOP_Y=+1.54）。HUD 畫的正是 governingStation。**方向就是反的**，而且跨中彎矩 wL²/24 只有簡支的 1/3、峰值只有 2/3，偏不保守。玩家沒有任何方式宣告鉸支承（`MEMBER_SEMANTICS` Q6 仍 open）。
- `SnapshotLoads.java:36-38` 的 javadoc 把「`br.hint.first_use` 教玩家做的第一件事」寫進出貨原始碼，但那是**死鍵**（全 repo 唯一的 Java 引用就是這句註解），而 `CORRECTIONS.md:8` 早就標成 wrong。
- `StructuralBlock.java:36-40` 宣稱「其他每一種移動方式都會發本 mod 聽的事件」——`/setblock`、`/fill`、`/clone`、WorldEdit、凋零怪、終界龍都不發。（**活塞本身是對的而且完整**：代理逐條驗過 1.20.1 原始碼，推整排、黏液塊拖曳、黏性活塞回收全部封死；末影人、流體、火也動不了。只有指令與生物破壞會繞過。）
- `README.md:208`「188 tests, all passing」——實跑 188 tests / 149 passed / **39 skipped**。28 條是缺引擎的 `SidecarEngineTest`，另外 11 條是 `SidecarLifecycleTest` 被 `assumeTrue(!os.name.contains("win"))` 擋掉。**在 dist/ 唯一附引擎的那個平台上，21% 的測試不執行。**
- 出貨 Linux 引擎要求 **glibc ≥ 2.35**（`readelf -V`），Ubuntu 20.04 / Debian 11 / RHEL 9 全部起不來，沒有任何地方講。

**行為與程式碼**
- 荷載被 drop 後**不會恢復**（`:617` 是 `loaded.remove(p)`，無 pending 佇列）；該維度當下沒有玩家時**靜默刪除、無人被告知**；訊息廣播給全維度而非擁有者。
- probe **失敗**時會洩漏成玩家看到的 `latest` 與 engine status（`:563` 的 `&&` 短路），與 commit 說的「probe 永不被畫」矛盾。
- DF-03 的 `dirty=true` 讓任何持久性 `ok:false` 變成永久 2 Hz 的 gather+solve 迴圈，唯一節流只有 10 tick。
- 全支承島的 `unassigned` 是**每一顆方塊**：120×120 基礎筏 → 14,400 筆、161 KB JSON，**每次 solve 都傳**。
- 奇異島的方塊既不在 `members` 也不在 `unassigned`（400 個隨機結構掃描：168 個中招，全部對應到奇異島，反例 0 個），而 `main.cpp:217-220` 本次新寫的註解宣稱 `unassigned` 覆蓋所有無元素結果的方塊。
- 多島時「fully supported」診斷會霸佔唯一的 diagnostic 欄位，把真正的機構診斷蓋掉（`singular` 旗標仍正確，**不變式 6 守住了**）。
- decode 側的上限與 trailing-byte 拒收**沒有 gate**（四個突變全綠）。現行程式碼沒有洞——每個驅動配置的計數都在 `new ArrayList<>(n)` 之前夾好——但表示未來拿掉上限會綠燈通過。
- `readUtf(48)` 用字面量而非 `TOKEN_MAX`，兩個常數沒連動。
- `verify.py` 在 Windows 上用正斜線相對路徑會 crash（`Popen(['dist/br-sidecar.exe'])` → WinError 2；`./dist/...` 或絕對路徑就好）。文件只給 Linux 寫法，**沒有任何一處告訴 Windows 使用者怎麼驗證他手上那顆 .exe**。

---

## §5 · 確認真的修好的（合併決策的另一半）

這一節不是客套，是你做下一步決策時該有的正面依據。

| 條目 | 判定 | 證據 |
|---|---|---|
| **A-1 跨材料接點** | **FIXED** | 自重手算 rel err 1.95e-16；500 kN 靜力校驗 `500,395,539.200` 對 `500,395,539.2` 逐位；接點**剛接**（柱頂與樑端 Mz 等值反號）；**25 種材料組合全過**；1–5 格淨跨十組同材料與跨材料拓撲相同；負向控制有效（抬高一格仍三座島）|
| **A-6 兩端落地梁** | **FIXED** | 端點 wL²/12 = 8,214,240、跨中 wL²/24 = 4,107,120，**逐位相同**；11 站點對 M(x) worst rel 3.9e-15；掃 n=3..9 與 X/Z 兩軸皆 ≤ 8.2e-15 |
| **A-2 CI pipefail** | **FIXED** | 修在 workflow 層（正確層級）；**生產環境有紅色 run** `32621276032`（`FAILED 19 of 251`, exit 1）；三種失效模式在 WSL 逐一重現，舊寫法全綠、新寫法全紅 |
| **A-3 分支保護** | **FIXED（缺 enforce_admins）** | required context 與實際 job 名稱雙向 diff 皆空；`strict:true`；釘 `app_id 15368`（第三方 status 冒名封死）|
| **A-4 板常數** | **FIXED 且優於要求** | 獨立三法會合 `0.02290509`（先在夾支樑與 Navier 板上驗過機器）；判準升級成收斂階 gate；`GATES.md:91` 五欄齊全含下游降級；**逐條比對整個 PR 區間無偷偷放寬** |
| **A-5 荷載熄燈** | **FIXED（機制層）** | 引擎 probe 路徑實跑走得通（即使世界只有一顆方塊）；反向誤傷已驗（probe 的 unassigned 必為請求的子集）；**約 1.5 秒收斂，不是每輪重試**；修法確認在出貨 jar 裡 |
| **A-8 DOC-1** | **FIXED（根因）** | `verify.py` 自印 `ALL PASS (251 checks)`；已公開 body 與 workflow 模板逐行 diff **IDENTICAL** |
| **A-8 BR-DIST-02** | **FIXED（最乾淨）** | 七個對外面全部改對，含 `scripts/dist-docs/` 來源副本與**已發布 zip 內的兩份說明書** |
| **A-8 R-02 / R-03** | **FIXED** | 新增 `[C16]`（實跑 rel=0.00e+00）；讀我-中文 是「九種結構方塊」且逐條列九種 |
| 舊 P0-1 / P0-2 | **仍然好的** | GatherCycle 游標單調前進 + `CLOCK_STRIDE=64` 保底；`SolveDispatch.run` 旗標恰好釋放一次，4 條測試全過 |
| 發布包完整性 | **乾淨** | 發布 zip 與 Main 的 `dist/` **逐位元組相同**，manifest 自洽（我親自核）|
| evidence 可重現性 | **紮實** | 用真 FrameCore 重跑，48 個相異葉節點中 40 個在 `.performance`/`.host`；**所有 sha256、engine provenance、accuracy、properties、plate_convergence、shear_wall、determinism 全部逐位元相同** |
| 封包 gate | **有真牙齒** | 40 個突變殺掉 27 個（67.5%）；`endI/endJ` 歸零、NaN 洗成 0、拿掉 clip、對調 ay/az 全部被抓 |
| 不變式 6 / 8 | **守住** | BRNetwork 三個封包全部 pin 在 PLAY_TO_CLIENT，**根本沒有 serverbound 封包**；`impl/client/` 五個類別全有 `@OnlyIn`，兩個非 client 呼叫點用 DistExecutor + FQN |
| 引擎輸入健壯性 | **乾淨** | 100 萬字元 token、500 萬層巢狀 JSON、10 MB 單行、NaN/Inf/1e400 荷載、INT_MAX 座標、缺欄位、型別錯——**全部具名拒絕，無崩潰無 hang** |
| shm 傳輸 | **乾淨** | 長度欄位不被信任、無整數溢位面、未知 flag 拒絕而非忽略、revision skew 檢查；本次新增 5 條路徑 JSON↔shm 逐欄 **parity=True** |
| 行尾釘定 | **有效** | 出貨 `install.sh` 0 個 CR（LF 正確）、`install.bat` 195 個 CR（CRLF 正確）|
| 兩條新決策 | **合規** | D-025／D-026 都備了理由與否證條件；D-025 明說代價（延伸方長一公尺）|

---

## §6 · 本輪的模式

八個維度回來以後，浮出一個很一致的簽名：

> **每一條 §A 的「實例」都修對了，「類別」大多沒修。**

| §A 條目 | 實例 | 類別 |
|---|---|---|
| A-1 跨材料接點 | 兩根柱一根樑 ✅ | 單格墊塊仍壞（§2-4）；奇異島吞荷載的**根本機制**還在 |
| A-6 兩端落地梁 | 數值精確 ✅ | 全落地島變成**更安靜**的同一種病（§2-3）|
| A-7 缺 mingw | 會 exit 1 ✅ | `.exe` 仍然沒人驗過，12 bytes 假檔可完整發行（§3-2）|
| R-02 零 gate token | 150x300 有 C16 ✅ | 100x200 同一個洞（§3-3）|
| DOC-1 「219」 | 根因治好 ✅ | 但六個非計數數字沒有機械來源，照樣過期（§1-3）|
| A-2 CI pipefail | ci.yml 修對 ✅ | release.yml 沒拿到；而發布路徑**整條繞過 CI**（§3-1）|

另一個值得說的：**新 gate 是照著修好的幾何寫的**。`J1` 用 3 格墩、`J2` 不驗長度、`P2` 沒有荷載案例——三個空白正好對上這一輪的三個 high。gate 有沒有牙齒，取決於寫它的人有沒有先想過「這個修法可能在哪裡不成立」。

正面的一半也要說：把 J1/P1 的期望值改回修復前的行為，`FAILED 5 of 251`。**對「已回報的回歸方向」，這批 gate 是真的有牙齒。** J1 帶反例、`verify.py` 自印總數、`check_docs.py` 對 pattern 失配是 FAIL 而非 skip——這三個都是正確的設計選擇。

---

## §7 · 我這一輪自己的錯誤（照登）

1. **說 `install.sh` 是 CRLF、會在 Linux 上跑不起來——錯的。** 我用的 `grep -c $'\r'` 在那個 shell 沒真的比對 CR（197 行全中就是它壞掉的徵兆）。逐位元組重量：0 個 CR。行尾釘定是有效的。
2. **懷疑 2 格跨的跨材料樑仍然失敗——是我的幾何錯。** 我把樑放在 y=67、柱頂在 y=66，端點只是對角相鄰，本來就不該接。改成跨過柱頂正上方後，1–5 格淨跨十組全部正常。
3. **第一次跑引擎壓力測試時加了 `wantBuckling` 欄位**，被嚴格 schema 拒絕（`unknown field`），三組結果全是假的 `ok=False`。重做才拿到真結果。（附帶：那個拒絕本身是 #33 fail-closed 有在運作的正面證據。）
4. **上一輪 A-6 我寫「就算把奇異判定修好，這根梁也無法表達跨中彎矩」——不精確。** 一根 5 格梁就是一個構件、2 個節點，這本來就是正確的樑離散化，跨中彎矩由構件內部的 station 回復。真正的缺口是 `nf==0` 時引擎不回固端力，而作者的 D-026 否證條件 (2) 逐字寫的就是這件事。實際修法（加中間節點）也確實解了，但我當初的理由給錯了。

代理的錯我也擋下一條：**R4 宣稱出貨 jar 含 `onExplode` 與「dist 沒重建」矛盾——不成立。** `ce1662c` 與 `753afe6` 各動了 dist/ 兩個檔且都是 tag 的祖先；作者說的「刻意不重建」只針對 tag **之後**的 `9920eef`（實測動了 0 個 dist/ 檔）。R4 殘留的有效論點是「jar 與 Java 原始碼之間沒有 gate 綁著」。

---

## §8 · 方法與檔案

8 個 Opus 代理，維度互斥、不重複工作：引擎擷取（A-1/A-6）、CI 與分支保護（A-2/A-3）、板常數（A-4）、荷載熄燈（A-5）、發行鏈與數字（A-7/A-8）、C++ 新碼、Java 新碼、對外宣稱。

代理實跑的東西：引擎壓力與健壯性矩陣（含 crash 重現）、25 種材料組合、三種獨立數值方法重算板常數、40 個 Java 突變、6 處文件數字注入、stub 工具鏈端到端重演 `package.sh`、WSL 三種 CI 失效模式、真 FrameCore 重跑 evidence、PE import table 手解。

主編者獨立重現的（不採信代理結論）：251 gate 數與兩平台一致性、發布 zip 對 `dist/` 逐位元組、release 與 CI 的時間戳、`enforce_admins`／tag protection／collaborators、`ci.yml` 有 defaults 而 `release.yml` 沒有、SUP-1（Z4/Z5）、JOIN-1 四種墊塊材料、A-1 跨 1–5 格淨跨十組、鏡像不對稱全表、stack overflow 與其對照組、`steel_rect_100x200` 與 `concrete_rect_400x600` 的 gate 覆蓋、傳輸數字對 `verification.json`、`onExplode` 與 `onBreak` 的 priority、三個 `writeUtf` 的 clip、中性軸精度全表、zip 內授權檔、Mojang 免責、`package.sh` 的 `.exe` 驗證路徑。

逐維度證據：`.audit_v02a/R1_engine.md`、`R2_gates.md`、`R3_plate.md`、`R4_load.md`、`R5_release.md`、`R6_cpp.md`、`R7_java.md`、`R8_claims.md`
