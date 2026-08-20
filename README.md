# Block Reality

**English** · [中文](#中文)

A structural analysis mod for Minecraft 1.20.1. Blocks placed in the world are extracted
into 6-DOF beam members and MITC4 shell facets, solved by a finite element engine running
outside the game process, and the result comes back as stress contours on the block
surfaces and a demand-over-capacity ratio for every member and plate.

![Utilisation lens](docs/images/utilisation-lens.jpg)

The utilisation lens. The corner readout is the result of the solve behind it: member
count, plate facet count, peak D/C, and how many of the structures in the world are
unrestrained and therefore reported as mechanisms rather than given stresses.

## Requirements

- Minecraft **1.20.1**
- Forge **47.x** ([download](https://files.minecraftforge.net/net/minecraftforge/index.html))

## Install

1. Get the latest `blockreality-*.zip` from
   [Releases](https://github.com/rocky59487/block-reality/releases/latest), or
   [`blockreality-0.0.1-demo.zip`](blockreality-0.0.1-demo.zip) from this repository
   (about 2.4 MB).
2. Extract it, then run `install.bat` on Windows or `./install.sh` on Linux and macOS.
3. Launch the game the way you normally do.

With no arguments the installer looks for a Minecraft instance in the usual locations for
the vanilla launcher, Prism, MultiMC, Modrinth and CurseForge. If it finds several it
lists them and asks which. You can also name the game directory yourself:

```
install.bat "D:\games\my-instance\.minecraft"
./install.sh ~/.minecraft
./install.sh --list      # list what was found, install nothing
```

What is in the archive:

| | |
|---|---|
| `blockreality-*.jar` | the Forge mod, installed into `<instance>/mods/` |
| `br-sidecar` / `br-sidecar.exe` | the analysis engine, installed into `<instance>/` |
| `START-HERE.txt` / `讀我-中文.txt` | instructions, English and Chinese |
| `VERIFICATION.md` / `verification.json` | the verification record for this build |
| `SHA256SUMS.txt` | SHA-256 of every file |

FrameCore is statically linked into `br-sidecar`, so there is no separate library to
install. `br-sidecar` runs as its own process rather than as a library loaded by the mod
(D-013), so a fault in the C++ costs one analysis rather than the server and the save. The
mod locates the engine through the config file, `-Dbr.sidecar`, `BR_SIDECAR`, the game
directory, then `PATH`. The engine is optional: without it the mod loads and plays
normally, with analysis disabled and reported as such.

### Running from source

`run.bat` (Windows) or `./run.sh` (Linux) in the repository root starts the development
client. It needs a JDK 17 on `PATH`; ForgeGradle fetches Minecraft and Forge itself on the
first run.

Minecraft and Forge cannot be redistributed, so no archive that can be handed to someone
else can contain them, and there is no extract-and-play package.

## Using it

The creative tab "Block Reality" holds Structural Steel, Concrete Slab and Stress Glasses.
The two blocks are two different element types:

| Block | Token | Element | Size |
|---|---|---|---|
| Structural Steel | `steel_rect_200x400` | 6-DOF beam | 200 × 400 mm section |
| Concrete Slab | `concrete_slab_200` | MITC4 shell facet | 200 mm thick |

**What counts as grounded**: a structural block whose *directly below* neighbour is a
solid, non-structural block. Nothing else grounds anything — a beam butted sideways
against a wall is not held by it, and the analysis will correctly call the result a
mechanism.

A first cantilever: build a stone wall five blocks high, put one Structural Steel block on
top of it and four more in a line out from there into the air, hold the Stress Glasses,
and sneak-right-click the far end to apply a 20 kN test load. The same click removes it.
Look at the member and its section readout appears in the corner.

![Section readout](docs/images/section-view.jpg)

The section readout: member id and section token, D/C, governing fibre and the position
along the member, and the stress profile through the section depth with both extreme
fibre values and the neutral axis.

Whether the top fibre is in tension or compression follows from the structural form, not
from the member — a cantilever hogs and its top fibre is in tension, a beam on two
supports sags and its top fibre is in compression. The HUD therefore states tension and
compression in words rather than leaving them to be read off the colour.

Right-click the air to change lens: Utilisation, Stress, Material.

| Command | |
|---|---|
| `/br status` | engine state, every path searched, last result |
| `/br members` | per member: D/C, governing fibre, governing section, peak stress |
| `/br section <id>` | the whole stress profile of one member, as text |
| `/br scan [radius]` | re-read the chunks around you, default radius 4 — for blocks placed by command or WorldEdit |
| `/br resolve` | force re-analysis |
| `/br reset` | restart the engine after it has been disabled (OP only) |

More cases — slabs, shear walls, slender column buckling, loads inside a member — are in
[`QUICKSTART.md`](QUICKSTART.md).

## Scope

Implemented: 6-DOF beam members, MITC4 shells including floors and shear walls, linear
buckling with geometric stiffness for both beams and shells, per-member and per-plate D/C,
and stress contours on the block surfaces. The design and construction-sequence layers
have not been started.

Not implemented: the plate D/C is an elastic surface screen only — transverse shear is
recovered and reported but not screened, there is no per-plate buckling check and no plate
ultimate strength. There are no composite reinforced-concrete sections; the section
catalogue is solid rectangles and circles and is named accordingly. There is no nonlinear
post-buckling: the buckling factor is the linear onset, an upper bound on the real
critical load.

## Verification

| | |
|---|---|
| Engine | `sidecar/verify.py`, 151 checks, all passing, each against a closed form or a solver-independent invariant |
| Java | 107 tests, all passing; 26 of them start `br-sidecar` and run FrameCore for real |
| Closed form | 31 non-zero references, worst relative error 1.6e-10; 10 zero references, worst absolute residual 1.5e-08 |
| Shell convergence | clamped square plate at 20 elements per side: span moment 0.57%, recovered support moment 2.7% |
| Shear wall | slender walls (h/w ≥ 3) agree with beam theory to 1e-7 on both shear flow and overturning; a square wall is 1e-3 to 1e-2 |
| Buckling | single-element column against the textbook value, 1.6e-05; the 1/L² law, 2.3e-10 |
| Determinism | 8/8 cases byte-for-byte identical, Linux native against the Windows cross-build |
| Performance | 199 members and 1200 DOF: 52 ms for the whole round trip including buckling, and not on the tick thread |

Every solve returns a global equilibrium residual recomputed from geometry and density
rather than read back out of the assembled load vector. The full record is in
[`evidence/VERIFICATION.md`](evidence/VERIFICATION.md), generated by `scripts/evidence.py`.

## Documentation

| File | |
|---|---|
| [`QUICKSTART.md`](QUICKSTART.md) | install, build from source, what to do in game |
| [`docs/RELEASING.md`](docs/RELEASING.md) | packaging and release process |
| [`docs/ENGINE_BOUNDARY.md`](docs/ENGINE_BOUNDARY.md) | the interface contract between Java and the engine |
| [`docs/MEMBER_SEMANTICS.md`](docs/MEMBER_SEMANTICS.md) | how blocks become members |
| [`docs/DECISIONS.md`](docs/DECISIONS.md) | architecture decision record |
| [`docs/GATES.md`](docs/GATES.md) | acceptance criteria |
| [`evidence/VERIFICATION.md`](evidence/VERIFICATION.md) | verification record, generated |
| [`CLAUDE.md`](CLAUDE.md) | development guide and invariants |

## License

Block Reality is licensed under the Apache License 2.0; see `LICENSE` and `NOTICE`.

FrameCore, the mechanics backend, is an external source dependency outside this
repository's licence and is covered by its own project's MIT License. Other third-party
components keep their own licences and copyright notices.

---

# 中文

[English](#block-reality) · **中文**

Minecraft 1.20.1 的結構分析模組。放置的方塊會被擷取為 6 自由度樑構件與 MITC4 板殼 facet，
交由遊戲程序之外的有限元素求解器計算，結果以方塊表面的應力等值圖，以及每根構件、每片板的
需求容量比（D/C）回到畫面上。

![利用率鏡頭](docs/images/utilisation-lens.jpg)

利用率鏡頭。角落的讀數就是這一次求解的結果：構件數、板 facet 數、最大 D/C，以及世界中有幾棟
結構未獲拘束、因而被判定為機構而不給應力。

## 需求

- Minecraft **1.20.1**
- Forge **47.x**（[下載](https://files.minecraftforge.net/net/minecraftforge/index.html)）

## 安裝

1. 從 [Releases](https://github.com/rocky59487/block-reality/releases/latest) 取得最新的
   `blockreality-*.zip`，或用本倉庫的
   [`blockreality-0.0.1-demo.zip`](blockreality-0.0.1-demo.zip)（約 2.4 MB）
2. 解壓縮後，Windows 執行 `install.bat`，Linux 與 macOS 執行 `./install.sh`
3. 用平常的啟動器開遊戲

安裝器不帶參數執行時，會在原生啟動器、Prism、MultiMC、Modrinth、CurseForge 的慣用位置尋找
Minecraft 實例；找到多個會列出來讓你選。也可以自行指定遊戲目錄：

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
| `START-HERE.txt` / `讀我-中文.txt` | 說明文件，英文與中文 |
| `VERIFICATION.md` / `verification.json` | 這一版的驗證紀錄 |
| `SHA256SUMS.txt` | 每個檔案的 SHA-256 |

FrameCore 已靜態連結進 `br-sidecar`，沒有另外要安裝的函式庫。`br-sidecar` 以獨立程序執行而非
由模組載入（D-013），因此 C++ 端的錯誤只影響一次分析，不影響伺服器與存檔。模組尋找引擎的順序
為設定檔、`-Dbr.sidecar`、`BR_SIDECAR`、遊戲目錄、`PATH`。引擎為選配，沒有它模組仍正常載入，
分析功能關閉並顯示狀態。

### 從原始碼執行

倉庫根目錄的 `run.bat`（Windows）或 `./run.sh`（Linux）會啟動開發用客戶端，只需要 `PATH` 上有
JDK 17，Minecraft 與 Forge 由 ForgeGradle 於首次執行時取得。

Minecraft 與 Forge 不可轉散布，因此任何可以交給別人的壓縮檔裡都不可能含有它們，也就不存在
解壓縮即可遊玩的完整包。

## 使用

創造分頁「Block Reality」提供結構鋼、混凝土樓板與應力眼鏡。兩種方塊對應兩種元素型別：

| 方塊 | token | 元素 | 尺寸 |
|---|---|---|---|
| 結構鋼 | `steel_rect_200x400` | 6 自由度樑 | 斷面 200 × 400 mm |
| 混凝土樓板 | `concrete_slab_200` | MITC4 板殼 facet | 厚 200 mm |

**什麼叫接地**：一個結構方塊，其*正下方*是實心且非結構的方塊。除此之外沒有別的接地方式——
樑從側面頂著一面牆並不算被牆撐住，分析會正確地把結果判為機構。

第一個懸臂：蓋一面五格高的石牆，在牆頂放一格結構鋼，再往外接四格排成一直線伸進空中，拿起
應力眼鏡，蹲下右鍵最外側那一格施加 20 kN 測試荷載（同一個動作可以再把它移除）。看向該構件，
畫面角落就會顯示它的斷面讀數。

![斷面讀數](docs/images/section-view.jpg)

斷面讀數：構件編號與斷面 token、D/C、控制纖維與其沿構件的位置，以及沿斷面高度的應力剖面，
上下緣應力值與中性軸位置一併標示。

上緣受拉或受壓取決於結構形式而非構件本身——懸臂上凸，上緣受拉；兩端有支承的樑下垂，上緣受壓。
HUD 因此以文字標明拉壓，不要求從顏色反推。

對空氣右鍵可切換鏡頭：利用率、應力、材料。

| 指令 | |
|---|---|
| `/br status` | 引擎狀態、搜尋過的每一條路徑、上一次的結果 |
| `/br members` | 每根構件的 D/C、控制纖維、控制斷面、峰值應力 |
| `/br section <id>` | 單一構件的完整應力剖面，純文字 |
| `/br scan [半徑]` | 重讀你周圍的區塊，預設半徑 4——供指令或 WorldEdit 放置的方塊使用 |
| `/br resolve` | 強制重新分析 |
| `/br reset` | 引擎被停用後重新啟動（需 OP） |

更多案例——樓板、剪力牆、細長柱的挫屈、構件中段加載——見 [`QUICKSTART.md`](QUICKSTART.md)。

## 範圍

已實作：6 自由度樑構件、MITC4 板殼（含樓板與剪力牆）、含樑與板殼幾何勁度的線性挫屈、每構件
與每片板的 D/C、方塊表面的應力等值圖。設計層與工法層尚未開始。

未實作：板的 D/C 只是彈性表面篩選——橫向剪力有算出來也有回報，但不納入篩選；沒有逐片板的挫屈
檢核，也沒有板的極限強度。沒有 RC 複合斷面，斷面目錄裡是實心矩形與實心圓形，命名也照實寫。
沒有非線性後挫屈：挫屈倍數是線性起始點，是真實臨界載重的上界。

## 驗證

| | |
|---|---|
| 引擎 | `sidecar/verify.py` 151 項全過，每一項都對閉合解或不依賴求解器的不變量 |
| Java | 107 項測試全過，其中 26 項會實際啟動 `br-sidecar` 執行 FrameCore |
| 對閉合解 | 31 項非零參考，最差相對誤差 1.6e-10；10 項零參考，最差絕對殘差 1.5e-08 |
| 板元素收斂 | 固端方形板每邊 20 元素：跨中彎矩 0.57%，還原後的支承彎矩 2.7% |
| 剪力牆 | 細長牆（h/w ≥ 3）的面內剪力流與傾覆軸力對梁理論到 1e-7；方形牆則是 1e-3 至 1e-2 |
| 線性挫屈 | 單元素柱對課本值 1.6e-05；1/L² 關係 2.3e-10 |
| 跨平台決定性 | 8/8 逐位元相同，Linux 原生對 Windows 交叉編譯 |
| 效能 | 199 根構件、1200 自由度：完整往返 52 ms（含挫屈），且不在 tick 執行緒上 |

每一次求解的回覆都附帶全域平衡殘差，該值由幾何與密度重算，而非從組裝後的載重向量讀回。完整
紀錄見 [`evidence/VERIFICATION.md`](evidence/VERIFICATION.md)，由 `scripts/evidence.py` 產生。

## 文件

| 檔案 | |
|---|---|
| [`QUICKSTART.md`](QUICKSTART.md) | 安裝、自行建置、進遊戲後的操作 |
| [`docs/RELEASING.md`](docs/RELEASING.md) | 打包與發版流程 |
| [`docs/ENGINE_BOUNDARY.md`](docs/ENGINE_BOUNDARY.md) | Java 與力學引擎的介面契約 |
| [`docs/MEMBER_SEMANTICS.md`](docs/MEMBER_SEMANTICS.md) | 方塊如何成為構件 |
| [`docs/DECISIONS.md`](docs/DECISIONS.md) | 架構決策紀錄 |
| [`docs/GATES.md`](docs/GATES.md) | 驗收判準 |
| [`evidence/VERIFICATION.md`](evidence/VERIFICATION.md) | 驗證紀錄，自動產生 |
| [`CLAUDE.md`](CLAUDE.md) | 開發指引與不變式 |

## 授權

Block Reality 以 Apache License 2.0 授權，見 `LICENSE` 與 `NOTICE`。

力學後端 FrameCore 為本倉庫授權範圍之外的外部 source dependency，依其原專案的 MIT License
授權。其他第三方元件保留各自的授權與著作權聲明。
