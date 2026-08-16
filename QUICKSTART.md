# 明天早上

**什麼都不用建。** `blockreality-0.0.1-demo.zip` 就是 release，`dist/` 是它的內容。

---

## 0 · 一鍵

### A · 已經有 Minecraft 1.20.1 + Forge 47.x

解開 zip，**直接雙擊 `install.bat`**（Linux 是 `./install.sh`）。

**不用給參數**——它會自己找 `.minecraft`、PrismLauncher、MultiMC、Modrinth、
CurseForge 的實例。找到剛好一個就直接裝；**找到好幾個會列出來要你指定**，
而不是亂猜一個。

裝完就好了，用你平常的啟動器開遊戲。

### B · 什麼都沒有，只想看它動

```
Windows：  run.bat
Linux ：   ./run.sh
```

**這會直接開起遊戲。** 只需要 PATH 上有 JDK 17——Gradle 用 wrapper，
Minecraft 與 Forge 由 ForgeGradle 第一次執行時自己抓（要幾分鐘，只有一次）。

> 為什麼不能做成「解壓縮就能玩」的完整包：**Minecraft 與 Forge 不能轉散布**，
> 所以任何可以交出去的檔案裡都不可能含有它們。B 這條是唯一存在的一鍵開遊戲路徑。

zip 裡有什麼（2.3 MB）：

| | |
|---|---|
| `blockreality-0.0.1-demo.jar` | Forge mod（api + core + impl 全在裡面，117 KB） |
| `br-sidecar.exe` | Windows 引擎，**只 import KERNEL32 與 msvcrt**，沒有要額外附的 DLL |
| `br-sidecar` | Linux 引擎，只依賴 libc/libm |
| `install.bat` / `install.sh` | 會自己找實例的安裝器 |
| `START-HERE.txt` | 同樣的說明，英文，放在 zip 裡 |

> **沒有 FrameCore.dll 這種東西。** FrameCore 是**靜態連進 `br-sidecar`** 的，而
> `br-sidecar` 是**獨立程序**不是 mod 載入的函式庫（D-013）。這樣 C++ 這側如果 segfault，
> 代價是一次分析失敗，不是整個伺服器加存檔。

---

## 1 · 自己重建引擎（想改 C++ 才需要）

**依賴只剩 Eigen**（header-only）。METIS / OpenBLAS / LAPACKE 都不用了——
FrameCore 的 supernodal lane 由 `FRAMECORE_SUPERNODAL` 在編譯期關掉，而
`useSupernodalPrimary` 本來就是 `false`，所以每次求解走的一直都是 Eigen LDLT。
**實測開與關的數字到最後一位都相同**，78 項 gate 兩邊都全過。

```bash
sudo apt-get install -y libeigen3-dev cmake g++

cmake -S sidecar -B sidecar/build -DCMAKE_BUILD_TYPE=Release -DBR_STATIC_RUNTIME=ON \
      -DFRAMECORE_DIR=/path/to/architect_simulator/Plugins/FrameSolver/Source/FrameCore
cmake --build sidecar/build --parallel
```

**確認：**

```bash
python3 sidecar/verify.py sidecar/build/br-sidecar
```

最後一行要是 `ALL PASS`（78 項）。**這一步失敗就不要往下走**——後面看到的任何東西都不可信。

一次把全部（Linux + Windows + jar）打包好：

```bash
scripts/package.sh /path/to/Plugins/FrameSolver/Source/FrameCore
```

Windows 那顆要 `apt-get install -y g++-mingw-w64-x86-64`；沒裝就只出 Linux 版。

---

## 2 · 確認 Java 那條線通

```bash
cd mod
gradle test -Dbr.sidecar=../sidecar/build/br-sidecar
```

82 個測試，0 failures。其中 18 個會**實際啟動 `br-sidecar` 子程序**跑真的 FrameCore。

沒帶 `-Dbr.sidecar` 那 18 個會 skip 而不是假裝通過。

---

## 3 · 進遊戲

```bash
./run.sh          # 或 Windows 的 run.bat
```

引擎路徑**不用設環境變數**。`SidecarLocator` 依序找：

1. `config/blockreality-server.toml` 的 `sidecarPath`
2. `-Dbr.sidecar` 系統屬性
3. `BR_SIDECAR` 環境變數
4. `<遊戲目錄>/br-sidecar` 或 `<遊戲目錄>/blockreality/br-sidecar`
5. `PATH`

`run.bat` / `run.sh` 已經幫你放好第 4 項了。

---

## 進去以後做什麼

創造模式，物品欄搜 `Block Reality`。兩樣東西：**結構鋼**、**應力眼鏡**。

### 最小案例：懸臂

1. 找一面**實心方塊牆**（石頭就好）
2. 從牆上往外放**五格結構鋼**，排成一直線
   - 貼著牆那一格會被判定為**接地**（下方是實心非結構方塊）
3. 拿**應力眼鏡**
4. **蹲下右鍵最外面那一格** → 加 20 kN 測試荷載

**應該看到：** 沿著梁有一條線，顏色是**利用率**（冷 → 琥珀 → 朱紅）。**看向那根梁**，
左上角就會出現它的**斷面圖**：一條垂直軸、往兩側伸出的應力剖面、中性軸的位置，
以及**用文字寫出來的**「上緣 受拉 +24.24 MPa／下緣 受壓 −24.24 MPa」。

### ⚠️ 「上拉下壓」只對懸臂成立

這是我上一版文件寫錯的地方，**很可能就是你看到「相反」的原因**：

| | 變形 | 上緣 | 下緣 |
|---|---|---|---|
| **懸臂**（一端固定、另一端懸空） | 上凸（hogging） | **受拉** | 受壓 |
| **兩端有支承的梁**（樓板、橋） | 下垂（sagging） | **受壓** | **受拉** |

**兩個都是對的。** 上緣是拉還是壓**不是梁的性質**，是結構形式的結果——而那正是這個工具要告訴你的事。

所以現在 HUD **用文字寫出來**，不要你從顏色反推。要純文字對照就打 `/br section <id>`，
它會把每一站的 `+/−` 與 `TEN`/`COM` 列出來。

### 三個鏡頭

**右鍵空氣**切換：`利用率` → `應力` → `材料`。

- **利用率**（預設）— 每根構件一條線一個顏色，依 D/C。**這是複雜建築唯一有用的視圖**：
  它回答「哪裡快壞了」
- **應力** — **只有你正在看的那根**會畫出四條纖維帶與中性軸。全部一起畫在真實建築上
  是一團霧，回答不了任何問題

### 故意弄壞

- **不接地**（懸空放）→ HUD 說「這是機構不是結構」。**這不是 bug，是正確答案**：沒有東西撐住它，就沒有應力可以報。
- **加很大的載重** → `max D/C` 超過 1，變紅。
- **中途換斷面** → 變成兩根共用一個節點的構件。

---

## 沒有客戶端也能測（我就是這樣驗的）

`gradle runServer` + RCON，不需要顯示卡。上面那些數字就是這樣量出來的。

```bash
# forge/run/server.properties
enable-rcon=true
rcon.password=<自己設>
rcon.port=25575
```

然後 `/setblock` 擺方塊、`/br scan` 讓 mod 認得它們（指令擺的方塊不會觸發 place event）、
`/br members` 看結果。

## 出問題就打 `/br status`

它會告訴你引擎在哪、**找過哪些地方**、目前 revision、幾個方塊、上一次結果是什麼。

```
/br status         引擎狀態 + 找過的路徑 + 上次結果
/br members        每根構件的 D/C、控制纖維、控制斷面位置、峰值應力
/br scan [半徑]    重掃已載入的區塊（指令/世界編輯擺的方塊用）
/br resolve        強制重算
/br reset          引擎被停用後重新啟動（需 OP）
```

**沒有引擎也能玩。** Mod 正常載入、方塊照放，只是不分析，而且會說原因。

---

## 驗證證據

`evidence/VERIFICATION.md`（release zip 裡也有一份）是**由 `scripts/evidence.py` 產生的**，
不是手打的表格。每一個數字都來自一次可重複的執行，並蓋上引擎 commit、二進位檔 hash、
原始碼 hash 與主機。

| | |
|---|---|
| 對閉合解（非零參考） | **28 項，最差相對誤差 1.623e-10**，RMS 3.165e-11 |
| 對恰為零的參考 | 5 項，最差絕對殘差 **1.49e-08 N·mm** |
| 無閉合解的性質 | **6/6**（機構偵測、單方塊、荷載拒絕、缺料拒絕、未知斷面拒絕、重複求解逐位元相同） |
| 跨平台決定性 | **6/6 逐位元相同**（Linux 原生 vs Windows 交叉編譯，比對整行回覆） |
| FrameCore | `6b40c08`，工作樹乾淨 |

**兩個誤差指標分開報是刻意的**：恰為零的量沒有相對誤差，把它折進同一個數字會把其他
所有比較都誤報成 1e-8 等級，而實際上非零比較好了好幾個數量級。

效能（含序列化與程序邊界的完整往返，九次中位數）：

| 構件數 | 中位數 | ms/構件 |
|---:|---:|---:|
| 1 | 0.20 ms | 0.200 |
| 19 | 3.66 ms | 0.193 |
| 99 | 17.8 ms | 0.180 |
| 199 | 40.8 ms | 0.205 |

**199 根構件 40.8 ms**，對照 Minecraft 的 50 ms tick——而且求解不在 tick 執行緒上，
所以這是「拿到結果的延遲」而不是「從遊戲拿走的時間」。每構件成本近乎常數。

`scripts/package.sh` 會在打包前跑這份證據，**沒過就不出貨**。

---

## 🔴 誠實邊界

**伺服器端已經在真的 Minecraft 裡跑過**（Forge 專用伺服器 + RCON，見 `forge/README.md`）：
mod 載入、指令、sidecar 自動尋找與啟動、真的 FrameCore 求解、機構偵測、乾淨關閉，
log 裡 0 個 ERROR、沒有殘留程序。上面那些應力數字都是實測並手算對過的。

**Windows 引擎已經實際跑過** — 用 Wine 跑完整 78 項 gate 全過，而且輸出跟 Linux 版
**逐位元相同**（`dc=0.069260057139999998`、`σ_top=24.241019999999999`）。

**客戶端渲染沒有人看過。** 沙箱沒有顯示卡，`runClient` 跑不起來。

- 編譯過了（真的 Forge、真的 MC 1.20.1）
- 應力渲染在**資料層**被 82 個測試釘住（顏色、位置、符號、梯度、中性軸）
- 但**畫面上長什麼樣是未知的**

最可能出問題的地方，按可能性排序：

1. **看不到帶子** — 纖維在方塊**內部**（斷面 200×400 mm，離中心線只有 0.2 格），
   所以渲染刻意關掉深度測試。如果還是看不到，先確認手上拿著應力眼鏡、`/br status`
   說引擎 READY、而且 `/br members` 有東西
2. **帶子位置整條偏移** — `RenderLevelStageEvent` 的相機位移沒對上
3. **HUD 沒出現** — `RenderGuiOverlayEvent.Post` 的 overlay type
4. **顏色反了** — 這個資料層有測試釘住，如果真的反了，問題在渲染端而不是資料

有任何一項不對，把 `/br status` 的輸出跟 `logs/latest.log` 貼給我。
