# Block Reality

Minecraft 1.20.1 的結構分析模組。放置的方塊會被擷取為 6 自由度樑與 MITC4 板殼元素，
交由外部的有限元素求解器計算，結果以應力等值圖與逐構件的需求容量比（D/C）回到遊戲畫面上。

![利用率鏡頭](docs/images/utilisation-lens.jpg)

利用率鏡頭。左上為本次求解的結果：構件數、板元素數、最大 D/C，以及未獲支承而被判定為機構的建築數。

## 需求

- Minecraft **1.20.1**
- Forge **47.x**（[下載](https://files.minecraftforge.net/net/minecraftforge/index.html)）

## 安裝

1. 取得 [Releases 頁最新的 `blockreality-*.zip`](https://github.com/rocky59487/block-reality/releases/latest)，或倉庫根目錄的 [`blockreality-0.0.1-demo.zip`](blockreality-0.0.1-demo.zip)（約 2.4 MB）
2. 解壓縮後，Windows 執行 `install.bat`，Linux 與 macOS 執行 `./install.sh`
3. 用平常的啟動器開遊戲

安裝器不帶參數執行時，會在原生啟動器、Prism、MultiMC、Modrinth、CurseForge 的慣用位置
尋找 Minecraft 實例；找到多個會列出來讓你選。也可以自行指定遊戲目錄：

```
install.bat "D:\games\my-instance\.minecraft"
./install.sh ~/.minecraft
./install.sh --list      # 只列出找到的實例，不安裝
```

壓縮檔內容：

| | |
|---|---|
| `blockreality-*.jar` | Forge 模組，安裝至 `<實例>/mods/` |
| `br-sidecar` / `br-sidecar.exe` | 分析引擎，安裝至 `<實例>/` |
| `START-HERE.txt` / `讀我-中文.txt` | 說明文件 |
| `VERIFICATION.md` / `verification.json` | 這一版的驗證紀錄 |
| `SHA256SUMS.txt` | 每個檔案的 SHA-256 |

FrameCore 已靜態連結進 `br-sidecar`，沒有另外要安裝的函式庫。`br-sidecar` 以獨立程序執行
而非由模組載入（D-013），因此 C++ 端的錯誤只影響一次分析，不影響伺服器與存檔。模組尋找
引擎的順序為設定檔、`-Dbr.sidecar`、`BR_SIDECAR`、遊戲目錄、`PATH`。引擎為選配，沒有它
模組仍正常載入，分析功能關閉並顯示狀態。

### 從原始碼執行

倉庫根目錄的 `run.bat`（Windows）或 `./run.sh`（Linux）會啟動開發用客戶端，只需要 PATH 上
有 JDK 17，Minecraft 與 Forge 由 ForgeGradle 於首次執行時取得。

Minecraft 與 Forge 不可轉散布，因此無法提供不需要另行安裝遊戲本體的完整包。

## 使用

創造模式分頁「Block Reality」提供結構鋼、混凝土樓板與應力眼鏡。兩種方塊對應兩種元素型別：

| 方塊 | token | 元素 | 尺寸 |
|---|---|---|---|
| 結構鋼 | `steel_rect_200x400` | 6 自由度樑 | 斷面 200 × 400 mm |
| 混凝土樓板 | `concrete_slab_200` | MITC4 板殼 facet | 厚 200 mm |

最小案例：從一面實心石牆往外放置五格結構鋼排成一直線（貼牆的一格判定為接地），持應力眼鏡，
蹲下右鍵最外側方塊施加 20 kN 測試荷載。看向該構件時，畫面角落會顯示其斷面讀數。

![斷面圖](docs/images/section-view.jpg)

斷面讀數：構件編號與斷面 token、D/C、控制纖維與斷面位置，右側為沿斷面高度的應力剖面圖，
上下緣應力值與中性軸位置一併標示。

上緣受拉或受壓取決於結構形式而非構件本身——懸臂上凸，上緣受拉；兩端有支承的樑下垂，
上緣受壓。HUD 因此以文字標明拉壓，不要求從顏色反推。

對空氣右鍵可切換鏡頭：利用率、應力、材料。

| 指令 | 說明 |
|---|---|
| `/br status` | 引擎狀態、搜尋過的路徑、上一次的結果 |
| `/br members` | 每根構件的 D/C、控制纖維、控制斷面、峰值應力 |
| `/br section <id>` | 逐站的純文字讀數 |
| `/br scan` | 重讀已載入區塊，供指令或 WorldEdit 放置的方塊使用 |
| `/br resolve` | 強制重新分析 |
| `/br reset` | 引擎被停用後重新啟動（需 OP） |

更多案例——樓板、剪力牆、細長柱的挫屈、構件中段加載——見 [`QUICKSTART.md`](QUICKSTART.md)。

## 範圍

已實作：6 自由度樑柱、MITC4 板殼（含樓板與剪力牆）、樑柱的線性挫屈、每構件與每片板的
D/C、方塊表面的應力等值圖。設計層與工法層尚未開始。

未實作：板殼的幾何勁度，因此挫屈不涵蓋板的面內挫屈；板的極限強度；橫向剪力的 D/C 篩選；
RC 複合斷面；非線性後挫屈路徑。

## 驗證

| | |
|---|---|
| 引擎 | `sidecar/verify.py` 151 項全過，全部對閉合解或不依賴求解器的不變量 |
| Java | 107 項全過，其中 28 項實際啟動 `br-sidecar` 執行 FrameCore |
| 對閉合解 | 31 項非零參考最差相對誤差 1.6e-10；10 項零參考最差絕對殘差 1.5e-08 |
| 板元素收斂 | 跨中 20 元素 0.57%；支承還原後 2.7% |
| 剪力牆 | 面內剪力流與傾覆軸力對閉合解 1e-7 等級 |
| 線性挫屈 | 對課本單元素柱值 1.6e-05；1/L² 關係 2.3e-10 |
| 跨平台決定性 | 8/8 逐位元相同（Linux 原生與 Windows 交叉編譯） |
| 效能 | 199 根構件完整往返約 50 ms（含挫屈），不在 tick 執行緒上 |

每一次求解的回覆都附帶全域平衡殘差，該值由幾何與密度獨立重算，而非從組裝後的載重向量讀回。
完整紀錄見 [`evidence/VERIFICATION.md`](evidence/VERIFICATION.md)，由 `scripts/evidence.py` 產生。

## 文件

| 檔案 | 內容 |
|---|---|
| [`QUICKSTART.md`](QUICKSTART.md) | 安裝、自行建置、進遊戲後的操作 |
| [`docs/RELEASING.md`](docs/RELEASING.md) | 打包與發版流程 |
| [`docs/ENGINE_BOUNDARY.md`](docs/ENGINE_BOUNDARY.md) | Java 與力學引擎的介面契約 |
| [`docs/MEMBER_SEMANTICS.md`](docs/MEMBER_SEMANTICS.md) | 方塊到構件的擷取語意 |
| [`docs/DECISIONS.md`](docs/DECISIONS.md) | 架構決策紀錄 |
| [`docs/GATES.md`](docs/GATES.md) | 驗收判準 |
| [`evidence/VERIFICATION.md`](evidence/VERIFICATION.md) | 驗證紀錄（自動產生） |
| [`CLAUDE.md`](CLAUDE.md) | 開發指引與不變式 |

## 授權

Block Reality 以 Apache License 2.0 授權，見 `LICENSE` 與 `NOTICE`。

力學後端 FrameCore 為外部 source dependency，不屬於本倉庫的授權範圍，依其原專案的
MIT License 授權。其他第三方元件保留各自的授權與著作權聲明。
