# 明天早上

**什麼都不用建，`dist/` 裡已經是成品。**

---

## 0 · 一鍵（最快）

### 裝進現有的 Minecraft 實例

```
Windows：  dist\install.bat "%APPDATA%\.minecraft"
Linux ：   dist/install.sh  ~/.minecraft
```

PrismLauncher 的話指到 `instances\<名字>\.minecraft`。

它會把 mod 放進 `mods\`、把引擎放進遊戲目錄。**Minecraft 1.20.1 + Forge 47.x**，開起來就有了。

### 或者直接跑開發環境

```
Windows：  run.bat
Linux ：   ./run.sh
```

自動把引擎放到 `forge/run/` 再叫 `gradlew runClient`。**Gradle 用 wrapper，不用先裝。**
只需要 PATH 上有 JDK 17。

`dist/` 裡有什麼：

| | |
|---|---|
| `blockreality-0.0.1-demo.jar` | Forge mod（api + core + impl 全在裡面，117 KB） |
| `br-sidecar.exe` | Windows 引擎，**只 import KERNEL32 與 msvcrt**，沒有要額外附的 DLL |
| `br-sidecar` | Linux 引擎，只依賴 libc/libm |
| `install.bat` / `install.sh` | 上面那兩行 |

> **沒有 FrameCore.dll 這種東西。** FrameCore 是**靜態連進 `br-sidecar`** 的，而
> `br-sidecar` 是**獨立程序**不是 mod 載入的函式庫（D-013）。這樣 C++ 這側如果 segfault，
> 代價是一次分析失敗，不是整個伺服器加存檔。

---

## 1 · 自己重建引擎（想改 C++ 才需要）

**依賴只剩 Eigen**（header-only）。METIS / OpenBLAS / LAPACKE 都不用了——
FrameCore 的 supernodal lane 由 `FRAMECORE_SUPERNODAL` 在編譯期關掉，而
`useSupernodalPrimary` 本來就是 `false`，所以每次求解走的一直都是 Eigen LDLT。
**實測開與關的數字到最後一位都相同**，68 項 gate 兩邊都全過。

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

最後一行要是 `ALL PASS`（68 項）。**這一步失敗就不要往下走**——後面看到的任何東西都不可信。

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

60 個測試，0 failures。其中 14 個會**實際啟動 `br-sidecar` 子程序**跑真的 FrameCore。

沒帶 `-Dbr.sidecar` 那 14 個會 skip 而不是假裝通過。

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

**應該看到：** 沿著梁的上緣一條**藍帶**（受拉）、下緣一條**紅帶**（受壓），靠牆最濃、往外漸淡，末端幾乎透明。中間一條灰線是**中性軸**。

左上角 HUD 有鏡頭名稱、`max D/C`、圖例、滿刻度 MPa。

### 三個鏡頭

**右鍵空氣**切換：`利用率` → `應力` → `材料`。

- **應力**（預設）— 上拉下壓，每條纖維各自的顏色
- **利用率** — 整根一個顏色，依 D/C：冷 → 琥珀 → 朱紅

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

## 🔴 誠實邊界

**伺服器端已經在真的 Minecraft 裡跑過**（Forge 專用伺服器 + RCON，見 `forge/README.md`）：
mod 載入、指令、sidecar 自動尋找與啟動、真的 FrameCore 求解、機構偵測、乾淨關閉，
log 裡 0 個 ERROR、沒有殘留程序。上面那些應力數字都是實測並手算對過的。

**Windows 引擎已經實際跑過** — 用 Wine 跑完整 68 項 gate 全過，而且輸出跟 Linux 版
**逐位元相同**（`dc=0.069260057139999998`、`σ_top=24.241019999999999`）。

**客戶端渲染沒有人看過。** 沙箱沒有顯示卡，`runClient` 跑不起來。

- 編譯過了（真的 Forge、真的 MC 1.20.1）
- 應力渲染在**資料層**被 60 個測試釘住（顏色、位置、符號、梯度、中性軸）
- 但**畫面上長什麼樣是未知的**

最可能出問題的地方，按可能性排序：

1. **看不到帶子** — 纖維在方塊**內部**（斷面 200×400 mm，離中心線只有 0.2 格），
   所以渲染刻意關掉深度測試。如果還是看不到，先確認手上拿著應力眼鏡、`/br status`
   說引擎 READY、而且 `/br members` 有東西
2. **帶子位置整條偏移** — `RenderLevelStageEvent` 的相機位移沒對上
3. **HUD 沒出現** — `RenderGuiOverlayEvent.Post` 的 overlay type
4. **顏色反了** — 這個資料層有測試釘住，如果真的反了，問題在渲染端而不是資料

有任何一項不對，把 `/br status` 的輸出跟 `logs/latest.log` 貼給我。
