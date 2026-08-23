
# reviewer 0
VERDICT: 我負責的段落**大體可信但不能照單全收**：機制與 file:line 幾乎全部經得起二次驗證（§A-6 的 Forge 六段引用逐字命中，§B 四條抽查全中，§A-5 我用 dist 引擎實跑重現了 `ok:false`），但有三處會直接誤導執行者，必須先改再拿去當必修清單。

**必改（會導致做錯事）**
1. §A-6「這是**本 PR 新增的面**」是錯的。`sidecarPath` + `ModConfig.Type.SERVER` 在 merge base `593ef2a`（= Main）就存在，`git diff 593ef2a..HEAD -- BRConfig.java` 是空的，SidecarLocator 只改了一句診斷字串。這條要從「發布前必修」重新定位成既有債，否則整份報告最顯眼的 high 之一站在一個可被一行 git 指令推翻的前提上。
2. §A-5 修法「引擎已回 `unassigned`」是錯的——失敗回應裡沒有這個欄位。照做會撲空。
3. §A-5「這正是 lang 檔 `br.hint.first_use` 教玩家做的第一件事」是錯的：該鍵 Java 零引用（死鍵），而真正出貨的 START-HERE 教的是先蓋五格梁再加荷載。A-5 的**機制**成立且值得修，但這個嚴重度加乘是假的，拿掉之後 A-5 仍站得住、只是不再是「第一次就撞牆」。

**應調整（方向對、力道錯）**
4. §A-6「沒有任何入口提示」過頭：絕對路徑已經進 log（`StructureManager.java:97`）與 OP 的 `/br status`，缺的只有來源標記；修法 (1) 已完成大半。
5. §A-5「每次 solve 都回 ok:false」不精確：`:524` 的 UNAVAILABLE 早退不回置 `dirty`，實際是**停止再算**，與 §B DF-03 是同一個早退點。
6. §B MECH-02 反而被**低估**：不是訊息錯，是模型裡沒有跨中節點、跨中量無法表達，等級應對齊 §A-1 而非 §B。

**已二次確認、可放心照做**：§A-5 的核心機制（單顆結構方塊 + 荷載 → 全域拒絕 → 整維度熄燈，附我實跑的引擎輸出）、`SnapshotLoads.java:53` 與測試覆蓋缺口、§A-6 的完整技術鏈（含單人從 `saves/<world>/serverconfig/` 載入這個樞紐，我從 Forge 的 `IntegratedServer.java.patch` 原文確認）、§B 的 MECH-02 / MECH-03（2.3× 我量到 2.250，配置相依）/ MECH-10（:53 行號精確，5 個維度我自己數過）/ DF-01。
NEW: 四條報告漏掉的東西：

1. **§A-5 的修法在現行協定下做不出來**（已列為 corrections 之一，但值得單獨拉出來當阻擋項）。`ok:false` 的回應體只有 `{"ok","op","revision","error"}`，`unassigned` 是 `ok:true` 專屬（sidecar/main.cpp:1148-1156 的 `return out;` 早於 :1498 的 `writeBlocks(w,"unassigned",...)`）。若照報告的必修清單直接派工，工程師會先花時間找一個不存在的欄位。forge 端也從未消費 `unassigned`（唯一引用是 `BRCommand.java:223-224` 印個數）。

2. **四個死 lang 鍵**，RESOURCES 維度沒抓到。我對 `en_us.json` 全部 `br.*` 鍵做交叉引用（扣掉 `br.scan.mode.*`／`br.fibre.*` 兩組動態拼接，見 `StressHud.java:68/:210`、`ClientStressState.java:279`），剩下 **`br.hint.first_use`、`br.hud.peak`、`br.hud.engine_off`、`br.hud.islands`** 四個 Java 零引用。其中 `br.hud.engine_off` 特別諷刺——§B DF-04 抱怨「引擎拒絕模型被顯示成引擎不可用」，而專門描述引擎關閉狀態的那個字串根本沒接上。63/63 翻譯齊備（§E 的宣稱）成立，但「齊備」不等於「用得到」。

3. **A-5 情境下玩家沒有指令出路**。`BrPermissions.java:59-63` 把 `load`/`unload`/`reset`/`resolve`/`scan` 全部釘在 LEVEL_OP=2，只留 `status/members/section/loads` 開放。預設無作弊單人世界的玩家不是 OP，撞到 A-5 之後唯一能自救的是「再點一次眼鏡」（`Stre

## [overstated] §A-5：「每次 solve 都回 `ok:false`」
理由: 實際行為比這句更靜。`StructureManager.java:396 beginCycle()` 開頭就 `dirty = false`；失敗結果走 `apply()` 的 `if (display == RevisionGate.Display.UNAVAILABLE) { latest = result; sendEngineStatus(...); return; }`（:524-528），這條路徑**不回置 dirty**（對照 :529-536 的 stale 分支有 `dirty = true`）。所以是「失敗一次之後就完全不再 solve」，直到玩家下次編輯任何結構方塊才 markDirty 再試一次、再失敗。淨效果同樣是黑掉，但描述會誤導修法：真正的迴圈驅動缺口是 §B DF-03 那條，兩條是同一個 `:524` 早退。
改法: 改為「荷載落在『本輪有收進、但引擎形不成元素』的方塊上，該次 solve 回 `ok:false`；`apply()` 的 UNAVAILABLE 早退（`StructureManager.java:524`）不回置 `dirty`，於是該維度停止分析，之後每次玩家編輯觸發的重試都再失敗一次。與 DF-03 同一處。」

## [wrong] §A-5：「放一顆結構方塊、蹲下右鍵眼鏡——**這正是 lang 檔 `br.hint.first_use` 教玩家做的第一件事**」
理由: 兩層都不成立。
(1) `br.hint.first_use` 是**死鍵**：全 repo 只出現在 4 個 lang json（en_us/zh_tw 的 src 與 build 各一份），`grep -rn 'first_use' --include=*.java forge/src mod` 零命中，也沒有動態拼接（動態拼的只有 `br.scan.mode.` 與 `br.fibre.`，見 StressHud.java:68/:210、ClientStressState.java:279）。玩家永遠看不到這句提示。
(2) 出貨文件教的正好相反：`dist/START-HERE.txt:68-75` 的第一個案例是「1. 蓋五格高石牆 2. 放一格結構鋼再往外接四格 3. 拿眼鏡 4. 蹲下右鍵最外側」；`dist/讀我-中文.txt:66-69` 同義。照文件做會先蓋出 5 格 run，**不會**撞到這個 bug。
機制本身仍可達（`StressGlassesItem.java:50-61 useOn` 對任何 `StructuralBlock` 蹲右鍵就 `toggleLoad`，零可行性檢查；`StructureManager.java:217-223 toggleLoad` 也只 putIfAbsent + markDirty），但「文件教的第一件事」這個嚴重度加乘是假的。
改法: 刪掉 `br.hint.first_use` 那句，改為：「眼鏡的 `useOn`（`StressGlassesItem.java:50-61`）對任何結構方塊蹲右鍵就加荷載，沒有任何可行性檢查——玩家在照 START-HERE 蓋滿五格之前先試一顆，就會踩到。」並另開一條：`br.hint.first_use` / `br.hud.peak` / `br.hud.engine_off` / `br.hud.islands` 四個 lang 鍵無任何 Java 引用（死鍵）。

## [wrong] §A-5 修法：「**引擎已回 `unassigned`**，forge 端把落在 unassigned 的荷載下一輪暫時排除」
理由: 在**失敗的那一次回應裡引擎不回 unassigned**。我實跑的 ok:false 回應完整內容是 `{"ok":false,"op":"solve","revision":1,"error":"load at (0,64,0) is on no structural element; ..."}` — 沒有 `unassigned` 欄位。原因在 sidecar/main.cpp:1148-1156：載荷檢查 `return out;` 早於任何 `unassigned` 寫出（writeBlocks 在 :1498、binary 在 :1675-1676，都在後面）。`unassigned` 只在 `ok:true` 時出現。
 forge 端也沒有留存：`unassigned` 唯一消費者是 `BRCommand.java:223-224`（`/br members` 印個數），沒有任何地方拿它過濾荷載。
所以這個修法照抄會做不出來——要嘛引擎在 error 回應裡一併帶回 offending block（其實 error 字串已含座標，可解析但很脆），要嘛就走報告自己列的第二選項。
改法: 改為：「引擎在 `ok:false` 時**不**回 `unassigned`（只在 error 字串裡帶座標，main.cpp:1152-1154）。可行的修法是 (a) 在 `toggleLoad`/`setLoad` 入口做最小可行性檢查（同材料共線鄰居 ≥1），或 (b) forge 端快取上一次 `ok:true` 的 `unassigned` 集合並據以攔截，或 (c) 改引擎讓拒絕回應也帶 `unassigned`／`offendingBlock`。」

## [overstated] §A-5：「整維度分析停擺」持續到荷載被移除（任務問：是否有其他自癒路徑）
理由: 有四條自癒路徑，其中兩條是玩家自然會做的動作：
(1) **再放一格同材料相鄰方塊** → run.size() 變 2（main.cpp:339），受載方塊成為 segment 端點進 nodeBlocks（main.cpp:1129-1133），下一輪就通過。我實跑 5 格梁 + 端點荷載是正常 `ok:true`。
(2) **打掉那顆方塊** → `StructureManager.java:288-295 onBreak` 同時 `structural.remove` 與 `loaded.remove`。
(3) **再蹲右鍵一次** → toggleLoad 移除（眼鏡無權限閘）。
(4) **重開世界** → 荷載純記憶體，`StructureManager.java:76` 的 `loaded` 是 `ConcurrentHashMap`，類別非 `SavedData`，javadoc :73-74 明寫「In memory only」。
注意 `/br unload` 走不通：`BrPermissions.java:62` 把 unload 釘在 LEVEL_OP=2，預設無作弊單人世界的玩家用不了——這反而讓「眼鏡再點一次」成為唯一的指令外出路。
改法: 在 A-5 補一句：「自癒路徑有四條（補一格相鄰同材料方塊／打掉該方塊／再點一次眼鏡／重開世界），但沒有任何一條被告知玩家，而 `/br unload` 是 OP-2（`BrPermissions.java:62`），預設單人世界用不了。」

## [wrong] §A-6：「但這是**本 PR 新增的面**」
理由: sidecarPath 這條 SERVER config、以及 SidecarLocator 的第一順位不回退，**在 PR 的 merge base 就已經存在**，本 PR 一個位元都沒改。
・`git merge-base Main HEAD` → `593ef2a`（= Main）。
・`git show 593ef2a:forge/.../BRConfig.java | grep -n sidecarPath` → `19: ConfigValue<String> sidecarPath;` / `28: sidecarPath = b` / `33: .define("sidecarPath", "");` — Main 就有。
・`git show 593ef2a:forge/.../BlockRealityMod.java | grep -n registerConfig` → `34: registerConfig(` / `35: ModConfig.Type.SERVER, BRConfig.SPEC);` — Main 就有。
・`git diff 593ef2a..HEAD -- forge/.../BRConfig.java` → **空**。
・`git diff 593ef2a..HEAD -- forge/.../SidecarLocator.java` → 只有一個 hunk，改的是 `describe()` 的診斷字串（config/ → <world save>/serverconfig/）。
・引入者是 `55bf21c feat(forge): 伺服器端在真的 Minecraft 裡跑通…`（`git log --diff-filter=A -- .../BRConfig.java`），遠早於本 PR。
諷刺的是本 PR 對這個面唯一的改動反而**提高了可發現性**（現在明白告訴玩家路徑在世界存檔裡）。宣稱要改寫，否則會被當成「本 PR 引入的回歸」而誤導合併決策。
改法: 改為：「這個面**不是本 PR 新增的**——`sidecarPath` 與其 SERVER 註冊在 merge base `593ef2a`（Main）即已存在（引入於 `55bf21c`），本 PR 對 `BRConfig.java` 零改動，對 `SidecarLocator.java` 只改了診斷字串。它是既有債，不是本次回歸。因此可以不擋合併，但應在 v0.2b 排掉。」

## [overstated] §A-6：「且沒有任何入口提示」，以及修法 (1)「啟動前把解析出的絕對路徑寫進 log 與 `/br status`」
理由: 修法 (1) 已經做掉大半。`StructureManager.java:96-97`：
`this.location = SidecarLocator.locate();`
`BlockRealityMod.LOG.info("[{}] {}", dimension.location(), SidecarLocator.describe(location));`
而 `SidecarLocator.describe()` 在 found 分支（:89-90）輸出 `"engine: " + r.found().get()`，也就是**解析後的絕對路徑本來就會進 log**。`/br status` 也有：`BRCommand.java:168-172` 對 OP 印同一段。
真正缺的只有兩件事：來源標記（不會說「這條來自世界存檔 config」）、以及非 OP 玩家在 `/br status` 看不到（:168 的 `hasPermission(LEVEL_OP)` 閘門，理由 #45）。「沒有任何入口提示」寫過頭了。
改法: 改為：「絕對路徑其實已經進 log 與 OP 的 `/br status`（`StructureManager.java:97`、`BRCommand.java:168-172`），缺的是**來源標記**——沒有任何訊息說這條路徑來自世界存檔的 config。修法 (1) 縮小為：在 `describe()` 的 found 分支標注來源（config / -Dbr.sidecar / game dir / PATH）。」

## [overstated] §A-6 評級 high、「跟下載 modpack 本來就會執行任意程式碼相比是不是真正新增的攻擊面」（任務指定判斷）
理由: 機制成立，但作為**本 PR 的發布前必修**評 high 撐不住，理由是上面那條：它不是本 PR 新增的。
就攻擊面本身，我認為信任層級的落差是真的、不能用「modpack 本來就危險」搪塞：世界存檔（.zip）在 Minecraft 生態是遠低於 modpack 的信任物，玩家解壓到 saves/ 不預期它能執行程式。而且 payload 可以自帶：toml 寫相對路徑 `saves/<world>/x.exe`（`Path.of(configured)` 相對於遊戲 CWD 解析，SidecarLocator:42），或 Windows UNC `\\host\share\x.exe`（`Files.isRegularFile`/`isExecutable` 都會通過）——不需要受害者機器上先有惡意二進位。所以報告的機制描述與修法 (2)「拒絕 UNC 與相對路徑」方向是對的。
綜合：機制 high、但**本 PR 的必修清單裡應該降為「既有債，v0.2b」**，並把「本 PR 新增」那句拿掉。
改法: 把 §A-6 從「A · 發布前必修」移到 §B 或另立「既有債」段，嚴重度保留 high 但註明「pre-existing since 593ef2a，非本 PR 回歸」。若仍要擋發布，理由必須換成「首次公開發行 = 首次真的有人下載世界」而不是「本 PR 新增」。

## [understated] §B MECH-02 的嚴重度描述（僅止於「訊息與事實相反」）
理由: 比報告寫的更糟：不只是標籤錯，而是**模型根本無法表達這個結構**。實跑顯示兩端落地的 5 格梁只產生 `nodes:2, dof:12`——中間三格完全沒有節點，所以模型裡不存在任何跨中自由度，就算把奇異判定修好，這根梁也算不出跨中撓度或跨中彎矩。修法不是「改訊息」或「放寬奇異判定」，而是擷取層必須在支承 run 中間插節點。報告把它列成訊息問題，會誘導出只改文案的假修復。
改法: MECH-02 一句話改為：「兩端落地的梁只產生 2 個節點、0 個自由節點 → 判為『機構』且 HUD 印『nothing is holding this up』。標籤相反只是表症：模型裡沒有跨中節點，跨中彎矩／撓度**無法表達**，必須在擷取層對支承 run 加中間節點。」


# reviewer 1
VERDICT: 我負責的四段整體可信、可照做，但有一條必須先改寫再拿去當必修清單。

**§A-2（CI 引擎 gate 恆綠）：完全成立，是這四段裡證據最硬的一條。** 我沒照抄前一位代理的實測，而是在 WSL Ubuntu 22.04 用真的 `dist/br-sidecar` 做端到端重現：sabotage verify.py:90 讓一條 gate 真的紅，raw 執行 exit=1，但把 ci.yml:27-28 原封不動放進 `bash -e` 就 exit=0，加 `set -o pipefail` 才 exit=1。另外兩個失效模式（interpreter 不存在、Python 例外）也各測過，全是 exit=0。行號、引文、四處交叉引用（ci.yml:1-3 / GATES.md:98 / RELEASING.md:60-61 / FIX_ROLLUP:203）逐一開檔命中，兩種修法的 GitHub Actions 語法也向官方文件求證過。**這條可以原封不動照做**，只建議把對照組從 release.yml:58 換成 package.sh:54＋:23（同句型、有 pipefail），論證會更緊。

**§A-3（沒有分支保護）：完全成立，而且我確認過不是權限假象。** token 對這個 repo 有 `admin:true`，所以 `branches/Main/protection` 的 404 是權威答案；`branches/Main` 回 `protected:false`、`rulesets` 與含組織層生效清單的 `rules/branches/Main` 都是 `[]`，owner 又是 User 型帳號。PR #26 現在 `mergeable_state: "clean"`。四個獨立端點互相印證。

**§A-7（Windows 引擎零 gate）：主張成立，我做了報告沒做的端到端模擬**——把 dist/ 的 `.exe` 拿掉、照 package.sh 的方式重生 SHA256SUMS，四道 gate 全綠（sha256sum -c exit=0、版本一致性四項全過、ci.yml step `ALL PASS`、gate 檔案裡 grep `br-sidecar.exe` 零命中）。但有兩處要改：**「`.exe` 是唯一一個沒有雜湊被記進 evidence 的出貨物」是錯的**——七個出貨物裡有六個沒有，evidence 只記了 Linux binary 一個；以及後果被**低估**了：package.sh:132 先 `rm -rf dist` 再 mv，所以這條路徑不是漏加 `.exe`，是會把版控裡既有那顆已驗過的 `.exe` 刪掉。

**§A-8（數字對正）：五格裡四格站得住，一格必須改寫。**
- DOC-1（218 不是 219）成立，兩平台我都自己跑了（Windows 原生 218、Linux 原生 218，皆 0 FAIL），並查出 219 是 `ALL PASS` 那行被算進去的 off-by-one，且同一個錯誤在 commit `2ebcfd6` 的「216 -> 219」裡就已存在。
- DOC-2 的 164（4 處）、216（1 處）、107（1 處）數字精確；151 是 5 處，其中 4 處是現在式宣稱、1 處（DECISIONS.md:108）是回顧敘事不必改。「原封不動存活」也查證了：FIX_ROLLUP 宣稱修這條的 301aa18 根本沒碰 sidecar/README.md 與 docs/outreach/。107 的分類要修正——那是 Java 測試數不是 gate 數。
- R-02（steel_rect_150x300 無 gate）成立且我把它加強了：向出貨二進位問出真 catalogue，逐 token 數過，它是唯一一個有方塊、在 verify.py 與全部 Java 測試裡各 0 次的 token。
- **BR-DIST-02 的「預設單人世界照著 START-HERE 做必定失敗」不成立，這是四段裡唯一一條會誤導修的人的宣稱。** 權限表本身沒問題（四個指令確實升到 2、五份文件確實只標 reset），但「必定失敗」有三個獨立反證：START-HERE 的第一個案例走應力眼鏡而 `StressGlassesItem.java:50-61` 完全沒有權限檢查；1.20.1 的 `WorldCreationUiState.isAllowCommands()` 在創造模式預設回 true，經 `MinecraftServer.getProfilePermissions` 給房主權限 4；而 START-HERE:50 本來就要玩家從創造分頁拿方塊。真正受害的是多人伺服器的非 OP 玩家。報告把它的優先度定成「等同功能缺陷」是連帶誇大——**照現在的寫法排優先度會排錯**，請照我的 suggestedEdit 改寫。

順帶提醒：這幾條的修法要一起處理 `scripts/dist-docs/` 底下那兩份逐位元相同的來源副本，否則下次 package.sh（:122-123 直接 cp）會把舊文案蓋回 dist/。
NEW: 四點，都是我覆核過程順帶查到、報告沒寫的：

1. **219 這個數字的來源查出來了，而且它是系統性的，不是單次筆誤。** `grep -c 'PASS' verify_out.txt` = 219，`grep -c '\[PASS\]'` = 218 —— 差的那一行就是結尾的 `ALL PASS`（verify.py:1386）。更關鍵的旁證：PR 內 commit `2ebcfd6` 的訊息逐字寫「test(gates): parser hardening pinned — depth cap, overflow-revision echo, truncation diagnosis (216 -> 219)」，而 216 正是報告 DOC-2 抓到還活在 `docs/outreach/OUTREACH.md:57` 的舊數字。也就是說 216 和 219 是同一個 off-by-one 產生的，這條錯誤跨了至少兩代。**修法建議加一條**：光把 219 改成 218 沒有解決根因，應該讓 verify.py 自己在 `ALL PASS` 那行印出案數（例如 `ALL PASS (218 checks)`），文件數字從它的輸出貼入——這正是 FIX_ROLLUP 5.8 自己建議卻沒做的「數字改由腳本輸出貼入」。

2. **A-2 的修法漏了一個同類風險點。** `scripts/package.sh:54` 是同一個 `python3 verify.py … | tail -1` 句型，目前安全只因為 package.sh:23 有 `set -euo pipefail`。如果採用「頂層 `defaults: { run: { shell: bash } }`」這種全域修法，記得它只管 workflow，不管 shell 腳本；反過來如果日後有人把 package.sh 的 `set -euo pipefail` 拿掉或改寫成別的入口，同一個洞會在發行鏈重現。建議 A-2 的修法補一句「兩處都要保持 pipefail，並在 GATES.md 登記這是同一條判

## [wrong] §A-7:「`.exe` 是唯一一個沒有任何雜湊被記進 evidence 的出貨物」
理由: evidence 只記了**一個**出貨物的雜湊。`evidence/verification.json` 的 `identity` 只有 engine / binary / sources / host 四鍵，`binary` 就是 Linux 的 `dist/br-sidecar`（sha256 aac6b57a…），`sources` 是 5 個原始碼檔（main.cpp、json.hpp、verify.py、CMakeLists.txt、evidence.py），沒有任何出貨物。我把 dist/SHA256SUMS.txt 的 7 個雜湊逐一去 grep evidence/verification.json 與 evidence/VERIFICATION.md：只有 br-sidecar（aac6b57a）各命中 1 次，其餘 6 個（START-HERE.txt de5f966d、blockreality-0.2a.jar b4af3521、br-sidecar.exe f5071423、install.bat 9e0b44f7、install.sh eaf6af24、讀我-中文.txt 03677ae4）全部 0 次。所以「沒有雜湊被記進 evidence」的出貨物是 7 個裡的 6 個，`.exe` 不是唯一。
改法: 改成：「7 個出貨物裡，evidence 只記了 Linux `br-sidecar` 一個的雜湊；`.exe`、jar、兩支安裝腳本、兩份說明書都沒有。而 `.exe` 是其中唯一一個既無 evidence 雜湊、又完全不被任何 gate 觸及的**執行檔**——jar 至少還被 release.yml 的檔名／mods.toml 一致性檢查綁住。」

## [understated] §A-7（隱含）：這條的後果僅止於「產出缺 .exe 的 dist/」
理由: package.sh:132-133 是 `rm -rf "$DIST"` 後 `mv "$STAGE" "$DIST"`。dist/ 是**入版控**的目錄，目前裡面就有一顆好的 br-sidecar.exe（f5071423…，我實跑 218/218 通過）。所以在沒有 mingw 的機器上跑 package.sh 不只是「沒加上 .exe」，而是會**主動刪掉倉庫裡既有的那顆好 .exe**，而且刪完之後四道 gate 仍全綠、`git add -A` 一提交就永久生效。這比報告寫的「產出只有 Linux 引擎的 dist/」嚴重一階。
改法: 在 A-7 加一句：「而且因為 package.sh:132 先 `rm -rf dist` 再 mv，這條路徑不是漏加 `.exe`，是**刪掉版控裡既有的那顆**——一次沒裝 mingw 的重打包就能把已驗過的 Windows 引擎從倉庫裡抹掉，四道 gate 不會有任何反應。」

## [overstated] §A-8 DOC-2 附帶：107 是過期的 verify.py 案數
理由: QUICKSTART.md:348 的 107 在原文脈絡是「資料層另外被 107 個測試釘住（顏色、位置、符號、梯度、中性軸）」——講的是**Java 客戶端渲染測試**，不是 verify.py 的 gate 數。FIX_ROLLUP:126 把 107 併進「verify.py 案數」清單本身就分類錯了。實測現況：`grep -rn "@Test" mod/ --include=*.java`（排 build）= 155、`forge/src` = 29。所以 107 確實過期，但它過期的是「Java 測試數」這條線，不是 gate 數；修法不同（要重數 Java 測試，不是抄 verify.py 輸出）。
改法: 把 107 那項單獨標注「這是 Java 渲染資料層測試數（QUICKSTART.md:348），非 verify.py 案數；現況 mod 155 + forge 29 個 @Test，需分開重對」。

## [understated] §A-8 BR-DIST-02:「四份玩家文件仍只把 `/br reset` 標成 OP-only」
理由: 實際是**五份出貨/倉庫文件**，加上兩份完全相同的來源副本共七個檔位。逐一命中：README.md:124 `(OP only)`、README.zh-TW.md:108 `（需 OP）`、QUICKSTART.md:284 `（需 OP）`、dist/START-HERE.txt:111 `(OP only)`、dist/讀我-中文.txt:100 `（需 OP）`——五份都只標 reset，load/unload/scan/resolve 一律無標記。另 `diff -q scripts/dist-docs/START-HERE.txt dist/START-HERE.txt` 與 `讀我-中文.txt` 皆 identical，所以修的時候必須同時改 `scripts/dist-docs/` 底下那兩份，否則下次 package.sh（:122-123 直接 cp）會把舊文案蓋回 dist/。
改法: 把「四份」改成「五份（README.md:124、README.zh-TW.md:108、QUICKSTART.md:284、dist/START-HERE.txt:111、dist/讀我-中文.txt:100）」，並加一句「兩份 dist 說明書的來源在 `scripts/dist-docs/`，必須一起改，否則 package.sh:122-123 會把舊版 cp 回來」。

## [overstated] §A-8 BR-DIST-02:「**預設單人世界照著 START-HERE 做必定失敗**」；「BR-DIST-02 那條會讓新玩家第一次照文件操作就撞牆，優先度等同功能缺陷」
理由: 「文件標錯」成立，但「必定失敗／第一次操作就撞牆」不成立，三個獨立理由：(1) START-HERE.txt:67-76 的「First case — a cantilever」全程用**應力眼鏡蹲下右鍵**，不用任何指令；我讀了 `StressGlassesItem.java:50-61`——`useOn` 只檢查 `player.isShiftKeyDown()`，**沒有任何 hasPermission 檢查**，直接呼叫 `StructureManager.of(server).toggleLoad(...)`。權限 0 的玩家照樣做得到。(2) 單人世界的權限不是預設 0。我解出 1.20.1 反編譯源碼核對：`PlayerList.isOp` = `ops.contains(p) || server.isSingleplayerOwner(p) && server.getWorldData().getAllowCommands() || allowCheatsForAllPlayers`（PlayerList.java:603-605），`MinecraftServer.getProfilePermissions`（MinecraftServer.java:1457-1472）在 isOp 且非 ops 清單時 `isSingleplayerOwner → return 4`。而 `WorldCreationUiState.isAllowCommands()`（WorldCreationUiState.java:140-150）在玩家沒手動改過時 `return gameMode == CREATIVE`，經 `CreateWorldScreen.java:236` 送進 `LevelSettings` 第 5 參數。(3) START-HERE.txt:50 明寫方塊與眼鏡來自「Creative tab」——要拿得到就得開創造世界，而創造世界的作弊預設就是 ON → 房主權限 4 → 四個指令全部可用。真正會撞牆的是「生存 + 未開作弊的單人世界」與「多人
改法: 改寫成：「四／五份玩家文件把 `/br load`/`unload`/`scan`/`resolve` 列成無限制，實際需 OP 2（BrPermissions.java:59-62）。單人**創造**世界房主權限是 4（作弊預設開），不受影響；受影響的是多人伺服器的一般玩家，以及生存/未開作弊的單人世界——那裡這四條指令連指令樹都不會出現，玩家看不到任何錯誤訊息。優先度：文件正確性缺陷，不是首次上手阻斷（START-HERE 的第一個案例走應力眼鏡，`StressGlassesItem.java:50-61` 無權限檢查，權限 0 可用）。」


# reviewer 2
VERDICT: §A-1 與 §A-4 的核心結論都站得住，而且是我用自己的方法重跑／重算後站得住的，不是照抄。§A-1 的實機重現我逐欄複製到最後一位（islands 3、singularIslands 1、applied=[0,-5685.876,0]、dc=0.0035316），自重手算 2842.938×2=5685.876 N 與 791.0784 N 也自己重算過；§A-4 的 0.0229051 我用兩個自己寫的方法（Ritz 弱式譜法＋13 點雙調和 FD + Richardson，其中 Ritz 機器先在 1D 夾支樑上對閉合解精確驗過）算到六位數一致，再加引擎自己 n=9…33 的 Richardson 外推 0.02290495，三方會合；「3 條由綠轉紅、err 1.75/1.73/1.75%」我改了副本實跑、exit code 由 0 變 1，逐字重現。0.0231 高出 0.8509%、捨入只能解釋 ±0.2165%，兩個數字都對。

可以照做，但**照做前必修四處**，否則會誤導執行的人：

1. §A-1「沒有任何 gate 測過混合材料接點」是**錯的**——`verify.py:821-843` 的 [S7]（鋼柱承混凝土板）就是。真正的缺口是「跨材料的桿件對桿件接點」。這句不改，作者會以為要從零建 gate，也會錯過「bearing 那條路本來就通」這個關鍵事實。
2. §A-1「bearing 延伸（332-335）同理」是**錯的**——那段完全不比材料，我實測 brick 柱撐 concrete 板回 `islands:1`。它是唯一能用的跨材料路徑，不是同一個 bug。
3. §A-4 的修法「更正 VERIFICATION.md:196」**低估了範圍**：實際要重寫 :148-201 整節，含主表每一列的 span error。而且方向是好消息——用正確參考解重算後元素是乾淨的 O(h²)，現在文件寫的「收斂地板」是錯參考解捏造的。
4. 「真實誤差是宣稱的兩倍」只在 8 元素（gate 用的網格）成立；20 元素反過來，真實誤差是宣稱的一半。§結論第 3 點寫成通則會被打。

另有兩處行號要修：A-1 的 `main.cpp:305` 應為 :306；A-4 標頭的 `GATES.md:85` 不含該常數、`VERIFICATION.md:196` 也不是常數所在（常數在 :149-151，「三位有效數字」的辯解在 :157-160 與 :197-201），且常數同時被 S4 用（verify.py:700），不只 S9。

嚴重度評級我同意：A-1 critical 撐得住，而且我找到比報告更硬的證據（玩家親手放的 500 kN 荷載被靜默吞掉、`ok:true`）；但「silently」要加限定——HUD 確實會印黃字「1／3 棟結構沒有東西撐住」（`StressHud.java:106-113` + `br.hud.island_mechanism`），缺的是「哪一根」不是「有沒有警告」。
NEW: 四條報告沒寫、我覆核時順帶撞到的：

1. **荷載吞噬比報告展示的更嚴重（A-1 的加強證據）。** verify.py:670-678 有一條 gate 保證「落在不屬於任何元素的方塊上的荷載必須被拒絕」（`bad_load`，rev 42）。但那條只涵蓋「不屬於任何 element」；方塊若屬於某個 element、而該 element 所在的島是 singular，荷載就從縫裡漏掉。我實測：木樑中點加 500 kN，回 `ok:true`、`applied` 仍是 `[0,-5685.876,0]`、`unassigned:[]`、`maxDC` 不動、無 error。玩家看不到任何「這個荷載沒被算到」的回饋。建議補一條 gate：荷載落在 singular 島的構件上時，或拒絕、或明確回報。

2. **`docs/MEMBER_SEMANTICS.md:186` §7.6 標題就是「接合剛性由接法決定，不由材料」。** 現行 `main.cpp:306` 用材料決定連通，直接牴觸一條已裁決的專案決策。A-1 的修法引 §7.4 規則 2 是對的，但 §7.6 才是「這是回歸、不是新功能」的證據，值得補進報告，因為它把 A-1 從「設計缺口」升格成「實作違反已凍結決策」。

3. **A-4 的錯誤參考解讓 evidence 對元素做了反向誤判。** 用 0.0229051 重算後，MITC4 的中心彎矩收斂階是漂亮的 2.0（見上表），而 VERIFICATION.md 現在寫的是「不再繼續縮小／設下地板的不是網格密度」。也就是說這條 bug 不只讓數字樂觀，還憑空替引擎捏造了一個不存在的精度上限。修正後對外宣稱其實可以更強，不是更弱——這點報告完全沒說。

4. **`sidecar/README.md` 的「151 項」與實測 218 項不符**（我實跑 `grep -c '^  \[PASS\]'` 得 218，Windows 原生）。報告 §A-8/DOC-1 已抓到「218 vs 全站寫的 219」，但沒點名 README.md 這處寫的是 **1

## [wrong] §A-1 標頭：`sidecar/main.cpp:305`，接著引 `return !isPlate(a.section) && !isPlate(b.section) && a.mat == b.mat;`
理由: 行號漂移一行。`sed -n '305,307p' C:/Users/wmc02/Desktop/block-reality/.claude/worktrees/pr26-head/sidecar/main.cpp` 輸出：305 = `auto continues = [&](const InBlock& a, const InBlock& b) {`，**306** = 引文那行，307 = `};`。`grep -n` 亦確認 `a.mat == b.mat` 只出現在 306。引文本身逐字正確，機制描述正確——純粹是引用位置錯一行。
改法: 改成 `sidecar/main.cpp:306`，或引 `main.cpp:305-307`（整個 lambda）。

## [wrong] 「run 延續只比材料，bearing 延伸（`main.cpp:332-335`）同理。」
理由: 332-335 的行號對，但「同理」是反的。實際碼：`const BlockPos before = sub(run.front(), axis); if (shellNodes.count(before)) run.insert(run.begin(), before);`（334-335 對 `beyond` 同構）——**完全沒有材料比對**，只問對面那格是不是 shell 節點。我實跑驗證：4 根 brick_rect_230x350 柱（y64-66，底支承）撐一片 5x5 concrete_slab_200（y67），回 `islands:1, singular:false, members 4 根 brick lengthMm=3000（blocks 從 [0,64,0] 延到 [0,67,0]）, shells:16, unassigned:[]`。跨材料承壓**是通的**，而且是目前唯一通的跨材料路徑。照現行文字去修的人會跑到 332-335 找一個不存在的材料比較。
改法: 改寫成：「唯一存在的跨材料耦合是『桿件端點承在板方塊上』（`main.cpp:332-335`，該路徑刻意不比材料）；桿件對桿件的接點只有 `continues`（:306）與共用方塊計數（:347-353）兩條路，兩條都要求同材料，所以不同材料的樑柱永遠不共節點。」

## [overstated] 「木樑架磚柱 → 3 座孤島，**樑整根消失**。」
理由: 孤島數與「輸出裡沒有樑」都對，但「整根消失」在模型層不成立，會誤導修的人往擷取層找丟件點。實測回覆 `nodes:6, dof:36`——兩根柱各 2 節點共 4，多出來的 2 個正是木樑那根構件的；而且回報的 member id 從 **2** 開始（id 1 被木樑吃掉了）。樑有被擷取、有進 K、有被求解，只是它那座島 `r.singular` 為真，`main.cpp:803-808` 直接 `return true`，於是不輸出 member、不累加 applied（`main.cpp:772` 的 `if (!r.singular)` 守著），也不進 `unassigned`（`main.cpp:419-420` 只收「沒被任何 segment 覆蓋」的方塊，樑是被覆蓋的）。
改法: 改成「樑被擷取成構件、也進了 dof（nodes 6 / dof 36、member id 從 2 起跳），但它自成一座無支承的島，`main.cpp:803-808` 讓 singular 島只回 diagnostic：不出現在 members、不計入 applied、也不進 unassigned」。

## [wrong] 「`verify.py` 的 C15 只各自單測木懸臂與磚柱，**沒有任何 gate 測過混合材料接點**——牴觸鐵則 2。」
理由: 前半對，後半不成立。C15 部分我確認：`sidecar/verify.py:967-1003`，木懸臂（rev 700，5 格 timber）與磚柱（rev 701，5 格 brick）各自單獨。但我把 `Sidecar.call` monkey-patch 起來、跑完整 151/218 項 gate 掃全部 103 次請求的 `blocks[].mat` 集合，抓到 **1 筆混合材料請求：rev=90, mats=['concrete','steel']**——那是 `verify.py:821-843` 的 **[S7] a column bears on the slab it holds up**：四根 steel_rect_200x400 柱（y64-67）撐 3x3 concrete_slab_200（y68），斷言 `not a mechanism` / `one connected structure`（islands==1）/ `columns carry the total weight`。所以「混合材料接點」是有 gate 的，缺的是**跨材料的桿件對桿件接點**（不經板的那種）。
改法: 改成：「唯一的跨材料接點 gate 是 [S7]（`verify.py:821-843`，鋼柱承在混凝土板上），走的是 bearing/shellNode 路徑，該路徑本來就不比材料，所以驗不到 `continues` 的材料閘。**跨材料的桿件對桿件接點零 gate**——這才是牴觸鐵則 2 的地方。」

## [overstated] 「在那之前至少要讓玩家看得見樑不見了（把 singular 島的方塊放進 `unassigned` 或新增 `droppedBlocks`）」——隱含目前玩家看不到任何訊號
理由: 目前**有**一個訊號，報告沒提，會讓讀者以為是零回饋。`ClientStressState.java:259` `partialMechanism() = singular && hasData()`，在本情境為真（singular=true 且 members 非空）；`StressHud.java:106-113` 因此會用黃色 0xFFCC00 印 `br.hud.island_mechanism`＝en_us.json:49 "%s of %s structures are unrestrained" / zh_tw.json:49「%s／%s 棟結構沒有東西撐住」，也就是「1／3 棟結構沒有東西撐住」——玩家只蓋了一棟，看到 3 本身就是線索。`BRCommand.java:204-205` 也會用 YELLOW 印 islands/singularIslands。缺的是「哪些方塊」，不是「有沒有警告」。
改法: 改成：「現有訊號只有 HUD 黃字 `br.hud.island_mechanism`（1／3 棟沒有東西撐住）與 members 計數 2；玩家無法知道是**哪一根**掉了。在真修好之前，至少要把 singular 島的方塊放進 `unassigned` 或新增 `droppedBlocks`，讓眼鏡指得出來。」

## [wrong] §A-4 標頭：「`sidecar/verify.py`（S9 系列），`docs/GATES.md:85`，`evidence/VERIFICATION.md:196`」
理由: 三個定位有兩個錯。(1) 常數不只在 S9：`grep -n 0.0231 sidecar/verify.py` → **696（註解）、700（`C_CENTRE = 0.0231/1.3*(1+T_NU)`，PLATES 前言，S4 用）、926（S9 內重新推導）**；三條轉紅的 check 有一條（`span moment within 1% at 8 elements`, verify.py:767）屬 **S4** 不是 S9。(2) `docs/GATES.md:85` 我讀過了，整行是 S9 **跨材料板 vm 比**那筆判準異動登記，**不含 0.0231 也不談中心彎矩**——它只是報告後文引「引擎數字自始未動」那句的出處。(3) `evidence/VERIFICATION.md:196` 是「**The residual is not shear deformation.**」；常數本體在 **149-151**（含 ν=0.2 換算值 0.021323），「三位有效數字」那個說法在 **157-160** 與 **197-201**。
改法: 標頭改成：`sidecar/verify.py:696,700,926`（S4 + S9 共用同一常數）、`evidence/VERIFICATION.md:149-151`（常數與 ν=0.2 換算 0.021323）、`evidence/VERIFICATION.md:157-160,197-201`（三位有效數字的辯解）；GATES.md:85 移到後文引句處，別列在「常數所在」清單裡。

## [overstated] 「**真實誤差是宣稱的兩倍**」（同見 §結論第 3 點）
理由: 只在 gate 用的粗網格成立，在文件登記的細網格反向。我用引擎自己跑 n=9…33 得 implied C(換算回 ν=0.3)：0.0233070 / 0.0230849 / 0.0230058 / 0.0229694 / 0.0229496 / 0.0229378 / 0.0229301。對 0.0231 的誤差是 +0.896 / −0.065 / −0.408 / −0.566 / −0.651 / −0.702 / −0.735%（與 VERIFICATION.md:190-193 完全一致）；對 0.0229051 則是 +1.754 / +0.785 / +0.440 / +0.281 / +0.194 / +0.143 / +0.109%。8 元素：0.90%→1.75%，**確實是兩倍**；但 20 元素：0.57%→0.28%，**真實誤差是宣稱的一半**。
改法: 限定範圍：「gate 實際採用的 8 元素網格上，真實誤差是文件宣稱的兩倍（0.90%→1.75%）；細網格則反向，20 元素的真實誤差只有宣稱的一半（0.57%→0.28%）。」

## [understated] 「**修法**：常數改 0.0229051，重跑，把由綠轉紅的 3 條依鐵則 3 照登（不是調容差），並更正 `VERIFICATION.md:196` 把 −0.57% 殘差歸給三位有效數字捨入的說法」
理由: 修法方向對，但工作量被說成一行編輯，實際是一整節要重寫。錯的常數不只讓誤差百分比偏掉，還讓 VERIFICATION.md 講了一個**關於元素本身的假故事**：:177-181 說「span error 在約十二元素處穿零，然後停在另一側的幾成之一，**不再繼續縮小**，所以設下地板的不是網格密度」——用正確參考解重算，誤差是 1.75/0.79/0.44/0.28/0.19/0.14/0.11%，逐項比值 2.23/1.78/1.57（h 比 1.5/1.333/1.25），**收斂階恰好 2.0**，乾乾淨淨的 O(h²)，根本沒有地板，穿零與反號全是錯參考解造出來的假象。要改的是：`VERIFICATION.md:149-151`（常數與 ν=0.2 換算 0.021323 → 0.0211432）、:157-160、:162-171 整張表的 span error 欄（4 元素 6.83%→7.74%，8 元素 0.90%→1.75%…）、:188-193 兩個厚度欄、以及 :175-201 整節敘事。
改法: 把最後一句換成：「並重寫 `evidence/VERIFICATION.md:148-201` 整節——常數（:149-151）、主表 span error 欄（:162-171）、厚度對照表（:188-193）與『殘差不是剪力變形／不再縮小』的敘事（:175-201）全部作廢。用正確參考解重算後元素反而是乾淨的 O(h²)（1.75→0.79→0.44→0.28→0.19→0.14→0.11%，收斂階 2.0），『收斂地板』這個結論本身是錯參考解的產物。」


# reviewer 3
VERDICT: 整體可信、可照做，但**不能照抄頭部數字，也不能照抄三處修法**。

**可信的部分（我親自重跑／重算過）**：§A-1（引擎輸出＋自重手算逐位元吻合）、§A-2（造出真紅之後 `bash -e ... | tail -3` 實測 exit 0）、§A-3（gh api 兩查）、§A-4（我用第三種數值方法 Ritz/Galerkin 獨立得到 C_centre=0.0229051，再打補丁重跑恰好 3 紅、百分比逐字相同）、§A-6（連 Forge 內部四個行號我都從 sources jar 對出來，全中）、§A-7 前半、§A-8 的 DOC-2/R-02/R-03、以及 §E 的 #27/#28/#29/#30/#33/#34 六條技術宣稱與 sha256 7/7、verify.py 218/218。這份報告的 file:line 精準度明顯高於它所審核的 FIX_ROLLUP。

**必須先修才能發出去的三件事**：
1. **統計要去重**。124 是「維度回報列數」，其中至少 18 列是同一問題被多維度重複計。相異問題約 106，high 從 18 降到 15（光 ci.yml:28 一條就佔了 4 個 high 名額）。報告在內文承認了最大的兩處重複，卻沒修頭部的表——任何引用「124 條、18 條 high」的人都會高報約 17%。
2. **§A-5 的修法是錯的**，照做會撞牆：失敗回覆裡根本沒有 `unassigned` 欄位（我實跑確認，引擎在 `!s.ok` 分支提早 return）。必須改成入口可行性檢查或 probe solve。
3. **§D-3 引用自己的 `git diff` 說錯了**：`AnalysisExecutor.java` 在 abc8e78..HEAD 是 62+/8−，`max(1,…)` 已經改成 `max(2,…)`。正確範圍是 abc8e78..223682c。結論不變，但寫法會讓讀者去找一個已經修好的 bug。

**§E 沒有嚴重灌水，但有三處要收緊**：「155 通過」實為 144 過 + 11 跳過；「29 個新測試實跑全綠」是 CI 綠不是覆核者跑的（§F 自己說 forge 本機沒建）；「資源 63/63 翻譯齊備」把 9×7 資源矩陣說成翻譯數（lang 實為 62 key）。最該收的是「權限表紮實修好」——壓力眼鏡（StressGlassesItem.useOn，零權限檢查）把 BrPermissions 想擋的東西整個繞開，§E 與 §C 對同一件事給了相反評價。

**§A/§B 的分界有一處明顯錯位**：MECH-02 應該進 §A。我實測兩端落地的 5 格梁回 `members:[]、maxDC:0、singular:true`，而 dist/START-HERE.txt 出貨文案逐字承諾這個情境「both ends sags, top fibre in compression」——出貨文件承諾＋最基本的簡支梁＋零輸出，比 §A-8 那四條文件條目更該擋發布。反向來看 §A 沒有明顯不該擋的，只有 BR-DIST-02（資料裡是 medium/downgraded）被「必定失敗」講過頭，START-HERE 的第一段 walkthrough 走眼鏡、一個指令都不用。

**可執行性最大的坑是 A-2 與 A-4 的順序**：修完 A-2（CI 真的會紅）再修 A-4（3 條 gate 真的變紅）＋A-3（分支保護），這個 PR 就永遠合不進去；報告禁止調容差卻沒給任何綠色落地態，而專案自己的 GATES.md 判準異動登記表本來就允許登記＋降級。另外 A-1 的正解與 A-4 的 evidence 重生成都需要作者私有的 FrameCore（ci.yml:5-10 明說 CI 建不出引擎），「多數是一行到數十行」對這兩條不成立。

**§D 的自我登記不夠**：登了兩條，但至少還有四條同級的自登缺席——§D-3 自己的 git diff 說錯、§A-8「Linux 原生」與 §F「沒做 Linux」互打（我在 WSL2 Ubuntu 22.04 實跑確認是 218，所以數字對、標籤錯、§F 那句「沒做」也錯）、§E「實跑」與 §F「沒建置」互打、以及 124 沒去重。以本專案鐵則 3「輸格照登」的標準，這四條該進 §D-6。
NEW: 【報告漏掉、我順帶查到的】

1. **`scripts/package.sh` 是對的，這反而讓 A-2 的框架更強** — package.sh:23 有 `set -euo pipefail`，所以它第 54 行的 `python3 verify.py "$STAGE/br-sidecar" | tail -1` 不會吞退出碼。報告說 CI 那條是「單點疏忽」，我確認屬實（release.yml 也對）。這點值得寫進 A-2，因為它反證了「不是系統性習慣」，修一行就夠。

2. **`release.yml:117` 會把「219 acceptance checks」印進 GitHub Release 頁面** — 報告 A-8 DOC-1 只說「全站寫的 219」，沒點出這個數字會離開 repo、變成公開的發布宣稱。實測值 218。這使 DOC-1 從「文件不一致」升格為「對外不實陳述」，是 §A 裡少數真正非改不可的文件條目。

3. **`87c6114` 一個 commit 同時塞進 dist/br-sidecar.exe、dist/blockreality-0.2a.jar、evidence/verification.json 與 sidecar/main.cpp** — 我在驗 §D-5 時看到的。這解釋了 §C「0.2a 在本 PR 內已有四個內容不同的 jar」，也意味著二進位與 evidence 是隨手更新而非由 pipeline 產出，和 §C 的 EVID-2「evidence 不是 package.sh 產的」互相佐證。報告把這兩件事分散在 §C 兩個 bullet，沒有指出它們是同一個工作習慣。

4. **三支承連續梁同樣不可解** — 我測 x=0,2,4 三處支承的 5 格梁：`singular:true, nodes:3, dof:18, diagnostic:"fully constrained (no free DOF)"`。MECH-02 的敘述只提「兩端落地」，實際上任何「所有節點都落地」的 run 都會這樣，涵蓋面比報告

## [overstated] A-1 修法：「在那之前至少要讓玩家看得見樑不見了」
理由: forge 端其實已經會說。ClientStressState.java:259 `public static boolean partialMechanism() { return singular && hasData(); }`，HUD 會印 br.hud.island_mechanism。ALL.json 的 MECH-01 覆核理由自己也寫了這條（『HUD 並非完全不提』），但報告 §A-1 把它刪掉了，讀起來像玩家完全沒有任何提示。
改法: 改成：「HUD 已會印『N 個結構中有 1 個未受束制』（ClientStressState.java:259），但玩家無法得知是哪一根、也不知道那根的自重沒進平衡式；至少要把 singular 島的方塊列出來。」

## [overstated] A-4 標題行「`sidecar/verify.py`（S9 系列）」／結論段「S9 板中心彎矩的參考常數」
理由: 錯常數有兩個定義點：sidecar/verify.py:700 `C_CENTRE = 0.0231 / 1.3 * (1 + T_NU)`（給 [S4] 用）與 sidecar/verify.py:926 `ref = 0.0231 / 1.3 * (1 + nu) * q * a9 * a9`（給 [S9] 用）。我實測轉紅的 3 條裡有 1 條在 [S4]（span moment at 8 elements），只有 2 條在 [S9]。把整條掛成「S9 系列」會讓照做的人只改 926 那一處。
改法: 標題改「`sidecar/verify.py:700`（[S4]）與 `:926`（[S9]）共用同一個錯常數」，並在修法明說兩處都要改。

## [unverifiable] A-4 修法：「常數改 0.0229051，重跑，把由綠轉紅的 3 條依鐵則 3 照登（不是調容差）」
理由: 這條沒有落地態，照做會卡住。順序依賴：A-2 修好之後 CI 才會真的把 verify.py 的退出碼當數；此時 A-4 讓 3 條變紅 → CI 紅 → 加上 A-3 的分支保護就永遠合不進去。報告禁止調容差，卻沒給任何一個「CI 能綠」的收尾方式，而專案自己的機制（docs/GATES.md:82-89 判準異動登記表，含一條已登記的放寬）本來就允許登記＋下游降級。另外 A-4 連帶要更新 evidence/VERIFICATION.md:190-196 的數字，而 evidence 是 scripts/evidence.py 產的、需要 FRAMECORE_DIR（scripts/package.sh:26-31），本機無法重生成——這一層報告完全沒提。
改法: 補一段「收尾態」：明確二選一（a）8 elements 那條依 GATES.md 流程登記移線＋下游「1% 內收斂」宣稱降一級；或（b）刪掉 8 elements 該條、保留 12 elements（實測 0.79% 仍過），並登記刪除理由。另註明 VERIFICATION.md 的板收斂表需 FrameCore 環境重生成，屬作者本機工作。

## [wrong] A-5 修法：「引擎已回 `unassigned`，forge 端把落在 unassigned 的荷載下一輪暫時排除」
理由: 這個修法在這個情境下無法實作。我實跑：單顆 steel_rect_200x400（support=true）+ 同格荷載 → 回覆是 {"ok":false,"op":"solve","revision":1,"error":"load at (0,64,0) is on no structural element; ..."}，**整個回覆沒有 unassigned 欄位**。原因在 sidecar/main.cpp：`handleSolve` 在 `if (!s.ok)` 時只寫 error 就 return（1382-1387 附近），`writeBlocks(w,"unassigned",...)` 在 :1498，在 ok 分支之後。unassigned 只有在同一格「沒放荷載」時才拿得到（我另跑無荷載版本，確實回 unassigned:[[0,64,0]]）。所以 forge 端在失敗那一輪拿不到清單可以排除。
改法: 修法改為兩選一：(1) 入口做最小可行性檢查（`toggleLoad`/`setLoad` 先判該格是否屬於某個 run，即報告自己列的第二選項）；或 (2) 先送一次無荷載 probe solve 取 unassigned 再過濾。若要保留原修法，得先改引擎讓失敗回覆也帶 unassigned——但那是引擎改動，屬 A-1 同一批。

## [wrong] A-7：`.exe` 是唯一一個沒有任何雜湊被記進 evidence 的出貨物
理由: jar 也沒有。evidence/ 只有兩個檔（VERIFICATION.md、verification.json）；`grep -rn "jar\|b4af3521\|f5071423" evidence/` 零命中。verification.json 的 identity 只有 engine / binary（Linux 那顆 aac6b57…）/ sources（5 個 sidecar 檔）/ host。dist/blockreality-0.2a.jar 的 sha b4af3521… 在 evidence 裡一個位元都沒有。而 .exe 其實在 dist/SHA256SUMS.txt 裡（我跑 `sha256sum -c` 7/7 OK，含 .exe）。報告自己 §C 也寫了「`dist/` 的 mod jar 與 Java 原始碼之間沒有任何 gate」，兩處打架。
改法: 改成：「evidence/verification.json 的 identity 只記 Linux 引擎與五個 sidecar 原始檔；`.exe` 與 jar 兩個出貨物都沒有雜湊進 evidence。`.exe` 更糟一層：它連存在與否都沒有 gate。」

## [overstated] A-8 DOC-1：gate 實測是 218 …（Linux 原生與 Windows 原生兩邊都是 218）
理由: 數字對、平台標籤錯，而且和 §F 直接打架。我在 WSL2 Ubuntu-22.04（uname: Linux 6.6.87.2-microsoft-standard-WSL2、glibc 2.35）跑 dist/br-sidecar：218 PASS / 0 FAIL / ALL PASS / exit 0；Windows 端同樣 218/0。所以「兩邊都是 218」成立，但那是 WSL 不是 Linux 原生。而 §F 又寫「沒做：Linux 原生重跑 verify.py（218 是 Windows 量的）」——同一份報告一邊當事實陳述、一邊列進沒做。另外 219 這個數字不只在文件裡：.github/workflows/release.yml:117 的 release body 就寫「every number is gated: 219 acceptance checks」，會直接印在 GitHub Release 頁上，這比報告寫的「全站寫的 219」更該擋發布。
改法: DOC-1 改「Linux（WSL2 Ubuntu 22.04, glibc 2.35）與 Windows 原生皆 218」，並同步刪掉 §F 那句「沒做」。另補一行點名 release.yml:117 會把 219 印進 Release 頁。

## [overstated] A-8 BR-DIST-02：**預設單人世界照著 START-HERE 做必定失敗** … 優先度等同功能缺陷
理由: 機制真、後果誇大，而且它在資料裡是 medium/downgraded。我對過：BrPermissions.java 把 resolve/scan/load/unload/reset 全設 LEVEL_OP=2；`git show 593ef2a:...BRCommand.java` 顯示 Main 上 resolve/scan 註明「Available to everyone」且無 requires，load/unload/loads 根本不存在——所以確有降權。四份文件（README.md:124、README.zh-TW.md:108、QUICKSTART.md:284、dist/START-HERE.txt:111、dist/讀我-中文.txt:100）也確實只標 /br reset (OP only)。但 START-HERE 的第一個 walkthrough（:66-74）全程用壓力眼鏡蹲下右鍵，一個指令都不用；COMMANDS 只是參考清單。而且 .audit_rescue/ALL.json 裡 BR-DIST-02 的 finalSeverity 是 medium、verdict 是 downgraded，報告卻把它放進「發布前必修」並說「優先度等同功能缺陷」。
改法: 改成：「四份玩家文件把 `/br scan /resolve /load /unload` 列成人人可用，但本 PR 把它們升到 OP 2；權限 0 的來源（無作弊單人、多人非 OP）會拿到『Unknown command』而不是任何解釋。第一個 walkthrough 走眼鏡，不受影響。」並把「優先度等同功能缺陷」拿掉或降為「文件必修」。

## [overstated] §E：**出貨物本體誠實**：jar 內類別與現在的原始碼相符（含 6 個新 seam 類），資源 63/63 翻譯齊備、9 個 token 全數對得上引擎 catalogue
理由: 三段裡兩段對、一段說錯。(1) jar：我 unzip 後取 outer class 59 個，與 mod/api+mod/core+forge 的 59 個 top-level .java 完全同集合（diff 空）——名稱層成立，但這只證明類別集合相同，不證明 bytecode 由當前原始碼編出（§C 自己說「jar 與 Java 原始碼之間沒有任何 gate」）。(2) 6 個新 seam 類：`git diff --name-status 593ef2a..HEAD` 新增 8 個 main .java（ClientEvents、BrPermissions、AnalysisPendingPacket、GatherCycle、SnapshotLoads、SolveDispatch、BinaryCodec、ShmRegion），要湊成 6 需要額外定義。(3) **「資源 63/63 翻譯齊備」是誤述**：63/63 是 RESOURCES 維度的 9 方塊 × 7 資源欄矩陣（blockstates/models/item-models/loot_tables/en_us/zh_tw/tag），不是翻譯數；lang 檔我實際 parse 是 en 62 key、zh 62 key（無重複 key、無缺漏）。
改法: 改成：「jar 的類別集合與當前原始碼完全一致（59/59，含本 PR 新增的 8 個類）；9 方塊 × 7 種資源的 63/63 全到位；lang 兩語各 62 key 無缺無多；9 個 token 對得上引擎 catalogue。注意這是名稱層一致，bytecode 與原始碼之間仍無 gate（見 §C）。」

## [overstated] §E：**forge 側**：…權限表…八條紮實修好，29 個新測試實跑全綠
理由: 兩處要收。(1)「29 個新測試」數字對：forge/src/test 共 29 個 @Test，且六個測試檔全部是本 PR 新增（git diff --name-status 593ef2a..HEAD）。但「實跑」不是覆核者跑的——§F 自己寫「沒做：forge 側本機建置」。綠來自 CI：我查 `gh api commits/2d730ac/check-runs`，run 32368457029 兩個 job 都 success（2026-08-20T12:24Z）。報告在 §E 開頭說「覆核者逐條打開檔案並實跑」，這句在 forge 這條不成立。(2)「權限表紮實修好」與 §C 的「壓力眼鏡繞過剛加的 `/br load` OP 閘門」直接打架，而後者是真的：StressGlassesItem.java 的 useOn() 從頭到尾沒有任何 hasPermission 檢查，直接 `StructureManager.of(server).toggleLoad(...)`——BrPermissions 的 javadoc 自己說 load/unload 要 OP 的理由是「任何生存玩家對別人的建築施加 megaNewton 是錯的」，這個目的被眼鏡完全繞開。（LOWEST priority 那條倒是實打實：StructureManager.java:287 `@SubscribeEvent(priority = EventPriority.LOWEST)` 且不 receiveCanceled，正是 FORGE-6 的第一建議。）
改法: 「29 個新測試在 CI（run 32368457029）全綠；本機未跑」；權限表那項改成「指令權限表落地且有枚舉測試守門，但同一個能力經壓力眼鏡完全不設防（見 §C ATK-02），gate 只擋了指令面」。

## [overstated] §E：`mod/gradlew test` 155 通過
理由: 我自己跑（`./gradlew --no-daemon cleanTest test -Dbr.sidecar=<abs>/dist/br-sidecar.exe`，強制重跑不吃 UP-TO-DATE），解析 build/test-results：total 155、failures+errors 0、**skipped 11**。所以是 144 執行通過 + 11 跳過。SidecarEngineTest 28/28 ✓、ShmRegionTest 4/4 ✓、SidecarLifecycleTest 14 中 11 跳過。報告在 §E 末尾有揭露這 11 條，但頭一行的「155 通過」與 README.md:151 的「184 tests, all passing」一樣是同一種寬鬆計數——而本報告正在因為同類問題（219 vs 218）開 DOC-1。
改法: 改「155 條中 144 執行通過、11 條在 Windows 被 assumeTrue 跳過」。順帶把 README:151 的 184 也列進 A-8 DOC-1 的同一格。

## [wrong] §D-3：`AnalysisExecutor.java` 在分支上逐字未動（`git diff` 輸出 0 行）
理由: 對報告自己宣告的基線就是假的。abc8e78 是 HEAD 的直系祖先（`git merge-base --is-ancestor` 通過，線性歷史），而 `git diff abc8e78..HEAD -- forge/.../AnalysisExecutor.java` 是 62 insertions / 8 deletions（107 行 diff）。爭議標的那一行本身也被改了：25f4cef 上是 `Math.max(1, ...availableProcessors()/4)`，HEAD:45 已是 `Math.max(2, ...)`，改動落在 97b9c6d「fix(forge)…」。我逐段量：abc8e78..25f4cef / ..1174e69 / ..**223682c** 皆 0 行，abc8e78..97b9c6d 才是 107 行。所以正確說法是「在作者標為修復點的 223682c 之前逐字未動」——結論（作者把 CONC-10 記成 223682c 修的、且用行號漂移當理由不成立）仍站得住，但寫成「在分支上逐字未動」會讓讀者以為 CONC-10 至今未修，實際上 HEAD 已經是 max(2,…)。
改法: 改成：「`git diff abc8e78..223682c -- AnalysisExecutor.java` 為 0 行——作者標為修復點的那個 commit 根本沒碰這個檔，『行號漂移』站不住。真正把 `max(1,…)` 改成 `max(2,…)` 的是稍後的 `97b9c6d`，所以 CONC-10 在 HEAD 上已修，只是記錯了 commit。」

## [overstated] §D-4：可查證的部分全部支持作者：… 既有容差零放寬（`git diff verify.py | grep '^-'` 只有 9 行刪除，全是 check() 語意重寫）
理由: 兩處不準。(1)「9 行刪除」我複製得到（`git diff abc8e78..HEAD -- sidecar/verify.py | grep -c '^-[^-]'` = 9），但**只有 3 行是 check()**，另外 6 行是 shm doorbell 改帶 bytes 的重構（`shm_encode_request(mm, rev, ...)` / `bell = sc.call({"op":"solve.shm","revision":rev})` 三組）。「全是」不成立。(2)「既有容差零放寬」和專案自己的登記表相牴觸：docs/GATES.md 的判準異動登記表明文寫著「C11 那條從『恰為 0』放寬到『≤1e-10 絕對』——這是**放寬**,照登」。報告在 §A-4 把 GATES.md 當權威引用，在 §D-4 又下了一句與它相反的斷言。（sidecar/patches/ 零改動我確認為真：`git diff --stat abc8e78..HEAD -- sidecar/patches/` 無輸出。）
改法: 改成：「`sidecar/patches/` 零改動、main.cpp 數值核零改動；verify.py 的 9 行刪除中 3 行是 check() 零參考語意重寫、6 行是 shm doorbell 重構。唯一的容差放寬是 C11（恰為 0 → ≤1e-10 絕對），作者已自行登記在 GATES.md，不是隱藏的放寬。」

## [understated] §D 全段（含「兩個自己的錯誤自登：基線標錯、口頭轉述 MONITOR」）
理由: 自登的兩條我都驗過且成立——FIX_ROLLUP:8 逐字「**基線**：`abc8e78`（main HEAD）」而 Main 實為 593ef2a（gh api 查證，abc8e78 超前 6 個 commit）；eventbus 6.0.5 的 EventPriority 只有 HIGHEST/HIGH/NORMAL/LOW/LOWEST（我從 gradle cache 的 sources jar 解出來看），而 REVIEW_2026-08-20_findings.json 的 FORGE-6 suggestion 逐字寫「改聽 EventPriority.LOWEST 並檢查 isCanceled()」，全 repo grep MONITOR 只命中 FIX_ROLLUP:212。D-1（FIX_ROLLUP:104 與 :168）、D-5（23b0b37 17:40:48 → 2ebcfd6 17:50:47，中間隔 bbab7b9、5a0cd01 兩個；C15 gate 與 timber/brick section 與 timber_beam 方塊三者同在 87c6114）我也逐條複驗成立。但 §D 只登了兩條，至少還有四條該登：(a) §D-3 自己的 git diff 說錯（見上）；(b) §A-8 DOC-1 與 §F 對 Linux 量測互相打架；(c) §E「29 個新測試實跑」與 §F「forge 本機沒建置」互相打架；(d) 124 條的統計沒有去重（見下條）。
改法: §D 增列 D-6：本報告內部矛盾自登（Linux 量測的平台標籤、forge 測試的『實跑』歸屬、AnalysisExecutor 的 diff 範圍、124 的重複計數），並在 §結論 的方法段標注「以下四處已於 §D-6 登記」。

## [overstated] 方法段：「11 個維度、22 個 Opus 代理…**124 條 finding**」＋存活嚴重度表 critical 1 / high 18 / medium 63 / low 42
理由: 124 與表都對得上 .audit_rescue/ALL.json（我 parse：11 個維度、judged 合計 124；finalSeverity = 1/18/63/42 逐格吻合；verdict = confirmed 102 / downgraded 16 / upgraded 6，每條都有 verdictReason），但**這 124 是「維度回報列數」不是「相異問題數」**。我用 (file,line) 對撞算了一遍：12 組完全相同的 (檔案,行號)，共 16 列重複；再加上顯然同一問題只差一行的兩組（DisplayTrackPrecisionTest:52 的 R-06、RevisionGateConcurrencyTest:82 的 DF-10），至少 18 列是重複。最誇張的兩處正是報告自己在內文承認的：ci.yml:28 被 CI-PIPE/ATK-01/CI-1/GATE-1 四個維度各報一次（**四條都是 high**，直接把 high 從 15 灌到 18）；DisplayTrackPrecisionTest 被 DF-09/CORE-3/TEST-1/R-06/MECH-10 五個維度各報一次。其他重複：StressResultPacket:226（DF-11+ATK-10）、StructureManager:313（CHUNKLOAD-STARVE+DF-08）、StructureManager:524（DF-03 high + F2 medium，同一條 dirty 不回置）、verification.json:11/:16、evidence.py:610、README.md:150、GATES.md:87、BrPermissionsTest:23、RevisionGateConcurrencyTest:83。去重後大約是 **1 critical / 15 high / 53 medium / 37 low ≈ 106 條**。報告在 §A-2 與 §B MECH-10 內文有揭露這兩處重複，所以不是隱瞞，但頭部的表沒有修正，任
改法: 表改雙欄：「維度回報列數 124（含 18 列跨維度重複）／相異問題約 106：critical 1、high 15、medium 53、low 37」，並在表下註明「ci.yml pipefail 一條被 4 個維度計了 4 次，DisplayTrackPrecisionTest 一條被計了 5 次；跨維度重複是覆蓋率的證據，不是規模的證據」。

## [understated] §B MECH-02：`main.cpp:803` 兩端落地的梁 = 自由度為零 → 被判成「機構」，訊息與事實完全相反（玩家最可能蓋的東西）
理由: 實測比一句話嚴重，而且它被放在「合併後儘快」。我跑 5 格 steel_rect_200x400、x=0 與 x=4 support=true：回覆是 {"singular":true,"islands":1,"singularIslands":1,"diagnostic":"fully constrained (no free DOF)","nodes":2,"dof":12,"members":[],"maxDC":0,"note":"no members or plates extracted"}——不只是訊息相反，**整根梁一個 member 都不回、maxDC=0、玩家什麼都看不到**。三支承版（x=0,2,4）同樣 singular。原因是不變式 1：共線 run 只在兩端生節點（cantilever 版 nodes=2、dof=12、maxDC=0.0264 正常），兩端都固定就是零自由度。而 dist/START-HERE.txt 的出貨文案逐字寫「a beam supported at both ends sags, so its top fibre is in compression. Both readings are correct」——出貨文件正面承諾這個情境會有讀數。main.cpp:803 `if (r.singular) {` 是正確錨點。
改法: 把 MECH-02 從 §B 移進 §A（或至少在 §A 加一句交叉指向）：理由是它同時滿足『出貨文件明文承諾』＋『最基本的簡支梁』＋『零輸出而非錯輸出』，比 §A-8 的四條文件條目更該擋發布。

## [overstated] §結論：修完 §A 的 8 項（**多數是一行到數十行**）再合併發布
理由: §A 八項裡至少三項不是「數十行」等級，且有工具鏈前提報告沒說：A-1 的正解要改 extractRuns 的節點合併（sidecar/main.cpp），而 .github/workflows/ci.yml:5-10 自己寫著「The engine itself cannot be BUILT here — br-sidecar links FrameCore, an external source dependency this repository does not carry」，scripts/package.sh:28-31 要求 `$FRAMECORE_DIR/Public/FrameCore/FrameSolver.h` 才肯跑——所以 A-1 的修法只能在作者的私有 FrameCore checkout 上做，且 .exe 還要 mingw 交叉編譯，CI 完全驗不到。A-1 的降級選項（把 singular 島放進 unassigned / 新增 droppedBlocks）同樣是引擎改動，報告沒給純 forge 側的替代。A-4 連帶要重生成 evidence（同樣要 FrameCore）。順序依賴至少三組：A-2 必須先於 A-3（報告有說）；A-4 必須先於 A-8 DOC-1（gate 條數會變）；A-7(b) evidence 加 binary_windows 必須先於 A-7(c) release.yml 比對雜湊。
改法: §結論改：「§A 的 A-2/A-3/A-7/A-8 是純腳本與文件，一次可完；A-1 與 A-4 需要作者的 FrameCore 環境（CI 建不出引擎，見 ci.yml:5-10），且 A-4 會連帶重生成 evidence。順序：A-2 → A-3；A-4 → A-8 DOC-1；A-7(b) → A-7(c)。」

## [wrong] §F：前六個各配一個對抗覆核者；ABUSE/DISPUTE/RESOURCES/JARPROV 在第二輪補覆核
理由: 數目與歸屬都對不上。.audit_rescue/roundb.json 的 verdict_sets 恰好四組，內容是 ATK-01..12（ABUSE）、DISPUTE-*/CI-PIPE/…（DISPUTE）、R-01..R-08（RESOURCES，八條與 ALL.json 的 RESOURCES 一致）、BR-DIST-01..12（JARPROV）。11 個維度扣掉這 4 個，第一輪配對的是 7 個（FIXCORE、UNK/forge、FIXREL、MECH、GATE、RENDER、FLOW），不是六個。而且「爭議裁決」被同時寫進「前六個」和第二輪名單裡，同一個維度不可能兩邊都算。11 finder + 11 覆核 = 22 個代理這個總數倒是自洽。
改法: 改成：「第一輪七個維度（core/sidecar、forge、CI 與發行、力學語意、gate 方法學、渲染數學、資料流）各配一個對抗覆核者；濫用面、爭議裁決、資源完整性、出貨溯源四個在第二輪補覆核。11 + 11 = 22 個代理。」

