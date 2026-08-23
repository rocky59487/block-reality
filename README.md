# Block Reality

**English** · [中文](#中文)

> **A playable structural-analysis laboratory inside Minecraft.** Build a structure,
> apply loads, and inspect its load path instead of guessing why it works.

[Download v0.2a](https://github.com/rocky59487/block-reality/releases/download/v0.2a/blockreality-0.2a.zip)
· [Quick start](QUICKSTART.md)
· [Research brief](docs/RESEARCH_BRIEF.md)
· [Verification record](evidence/VERIFICATION.md)
· [Share technical feedback](https://github.com/rocky59487/block-reality/issues/new?template=research-feedback.yml)

![Minecraft 1.20.1](https://img.shields.io/badge/Minecraft-1.20.1-62B47A)
![Release v0.2a](https://img.shields.io/badge/release-v0.2a-3B82F6)
![Verification](https://img.shields.io/badge/verification-251_engine_%2B_187_Java_checks-passing)
[![License](https://img.shields.io/github/license/rocky59487/block-reality)](LICENSE)

Block Reality is a structural analysis mod for Minecraft 1.20.1. Blocks placed in the
world are extracted into 6-DOF beam members and MITC4 shell facets, solved by a finite
element engine running outside the game process, and returned as stress contours on the
block surfaces and a demand-over-capacity ratio for every member and plate.

![Utilisation lens](docs/images/utilisation-lens.jpg)

The utilisation lens. The corner readout is the result of the solve behind it: member
count, plate facet count, peak D/C, and how many of the structures in the world are
unrestrained and therefore reported as mechanisms rather than given stresses.

## Why Block Reality

- **Auditable mechanics:** structural blocks become explicit beam members or shell facets;
  the model, result fields and limitations are documented.
- **Honest failure states:** an unrestrained structure is reported as a mechanism instead
  of being assigned plausible-looking stresses.
- **Reproducible evidence:** the release records closed-form comparisons, convergence,
  equilibrium, determinism and end-to-end timing.
- **Game-safe isolation:** the C++ mechanics backend runs as a sidecar process, so a solver
  fault cannot take the Minecraft server or world save down with it.

Block Reality is experimental research and education software. It is **not** a building-code
checker and must not be used for real-world structural design or safety decisions.

## Help evaluate it

Independent use is the most valuable contribution at this stage. Try the first cantilever,
then a slab, shear wall or slender column from the quick start. If a result surprises you,
the [research feedback form](https://github.com/rocky59487/block-reality/issues/new?template=research-feedback.yml)
asks for the exact model and reference needed to investigate it.

Researchers and engineers can start with the [one-page research brief](docs/RESEARCH_BRIEF.md)
and the generated [verification record](evidence/VERIFICATION.md). If the project earns a
place in your bookmarks, a GitHub star is the simplest signal that this work should
continue.

## Requirements

- Minecraft **1.20.1**
- Forge **47.x** ([download](https://files.minecraftforge.net/net/minecraftforge/index.html))

## Install

1. Download
   [**`blockreality-0.2a.zip`**](https://github.com/rocky59487/block-reality/releases/download/v0.2a/blockreality-0.2a.zip)
   (2.4 MB). Later versions are on the
   [Releases page](https://github.com/rocky59487/block-reality/releases/latest).
2. Extract it, then run `install.bat` on Windows or `./install.sh` on Linux.
   (macOS: the mod installs and plays, but no macOS engine binary ships yet —
   analysis stays off unless you build `br-sidecar` from source.)
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

The creative tab "Block Reality" holds the structural blocks and the Stress Glasses.
A block's token decides which element it becomes — beams and plates are different
element types, not different colours:

| Block | Token | Element | Size |
|---|---|---|---|
| Structural Steel | `steel_rect_200x400` | 6-DOF beam | 200 × 400 mm section |
| Structural Steel 150x300 | `steel_rect_150x300` | 6-DOF beam | 150 × 300 mm section |
| Structural Steel 100x200 | `steel_rect_100x200` | 6-DOF beam | 100 × 200 mm section |
| Plain Concrete Beam | `concrete_rect_400x600` | 6-DOF beam | 400 × 600 mm, unreinforced — cracks in tension at 3 MPa, as it should |
| Timber Beam | `timber_rect_140x240` | 6-DOF beam | 140 × 240 mm sawn section |
| Brick Pier | `brick_rect_230x350` | 6-DOF beam | 230 × 350 mm masonry pier |
| Concrete Slab | `concrete_slab_200` | MITC4 shell facet | 200 mm thick |
| Concrete Slab 150 | `concrete_slab_150` | MITC4 shell facet | 150 mm thick |
| Steel Plate 20 | `steel_plate_20` | MITC4 shell facet | 20 mm thick |

Every token is gated against a closed form before it got a block (`verify.py` C1/C1b/C15).
There is deliberately no brick *wall* plate: the plate screen is an elastic von Mises
check, which cannot see the tension/compression asymmetry that governs a brittle
material — so brick only exists as a pier, where the beam screen's five separate
ratios handle the asymmetry honestly.

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
| `/br status` | engine state, every path searched, transport, last result |
| `/br members` | per member: D/C, governing fibre, governing section, peak stress |
| `/br section <id>` | the whole stress profile of one member, as text |
| `/br load <fx> <fy> <fz>` | apply a test load, in kN, to the block you aim at — `/br load 30 0 0` pushes a shear wall sideways |
| `/br unload` / `/br unload all` | remove the aimed block's test load / all of them |
| `/br loads` | list every test load |
| `/br scan [radius]` | re-read the chunks around you, default radius 4 — for blocks placed by command or WorldEdit |
| `/br resolve` | force re-analysis |
| `/br reset` | restart the engine after it has been disabled |

The four commands that CHANGE something — `load`, `unload`, `scan`, `resolve` —
and `reset` need operator level 2. In a single-player creative world you have it
already; in survival or on a server without it those commands do not appear in the
command tree at all. The Stress Glasses need no permission: they apply one
configured, downward test load, which is a thing to do with the mod rather than a
thing to do to the server. `/br load` takes an arbitrary vector of any magnitude,
which is not.

More cases — slabs, shear walls, slender column buckling, loads inside a member — are in
[`QUICKSTART.md`](QUICKSTART.md).

## Scope

Implemented: 6-DOF beam members, MITC4 shells including floors and shear walls, linear
buckling with geometric stiffness for both beams and shells, per-member and per-plate D/C,
stress contours on the block surfaces, and a zero-copy shared-memory transport between
the mod and the engine (JSON remains the fallback and the debug surface). Every mechanics
number on the wire is the return value of an engine function behind the engine's own
closed-form gates — the adapter computes nothing. The design and construction-sequence
layers have not been started.

Not implemented: the plate D/C is an elastic surface screen only — transverse shear is
recovered and reported but not screened, there is no per-plate buckling check and no plate
ultimate strength. There are no composite reinforced-concrete sections; the section
catalogue is solid rectangles and circles and is named accordingly. There is no nonlinear
post-buckling: the buckling factor is the linear onset, an upper bound on the real
critical load.

## Verification

| | |
|---|---|
| Engine | `sidecar/verify.py`, 251 checks, all passing, each against a closed form, a solver-independent invariant, or a transport-equivalence oracle |
| Java | 187 tests, all passing (155 pure-Java, 32 Forge-side); 28 of them start `br-sidecar` and run FrameCore for real |
| Closed form | 31 non-zero references, worst relative error 1.2e-14; 10 zero references, worst absolute residual 1.5e-08. (Two earlier releases quoted 1.6e-10 here — that floor turned out to be the old wire's 10-digit truncation, not the engine) |
| Transport | numbers cross as raw little-endian doubles in shared memory, never textualised; the JSON fallback prints 17 significant digits. Gate: three representative solves bit-identical across both transports |
| Shell convergence | clamped square plate: span moment 1.75% at 8 elements down to 0.28% at 20, observed convergence order 2.06; recovered support moment 2.7% at 20 |
| Shear wall | h/w ≥ 5 agrees with beam theory to 1.4e-7 (shear flow) and ~1e-9 (overturning); h/w = 3 is 2.7e-5 / 6.8e-7; a square wall is a deep beam, where beam theory itself is the wrong model (1e-3 to 2e-2 is the reference's error) |
| Buckling | single-element column against the textbook value, 1.6e-05; the 1/L² law, 2.3e-10 |
| Determinism | 8/8 cases byte-for-byte identical, Linux native against the Windows cross-build |
| Performance | transport, same 86-member solve both ways: 4.5 ms over shared memory vs 28.3 ms over JSON (84% saving); a 199-member, 1200-DOF frame completes its full round trip including buckling in ~0.1 s on the noisy reference laptop — and never on the tick thread, so this is latency to a result, not time taken from the game |

Every solve returns a global equilibrium residual recomputed from geometry and density
rather than read back out of the assembled load vector. The full record is in
[`evidence/VERIFICATION.md`](evidence/VERIFICATION.md), generated by `scripts/evidence.py`.

## Documentation

| File | |
|---|---|
| [`QUICKSTART.md`](QUICKSTART.md) | install, build from source, what to do in game |
| [`docs/RESEARCH_BRIEF.md`](docs/RESEARCH_BRIEF.md) | one page for reviewers: numerical scope, evidence, open questions |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | how to report a result you disagree with, with the evidence it needs |
| [`docs/RELEASING.md`](docs/RELEASING.md) | packaging and release process |
| [`docs/ENGINE_BOUNDARY.md`](docs/ENGINE_BOUNDARY.md) | the interface contract between Java and the engine |
| [`docs/MEMBER_SEMANTICS.md`](docs/MEMBER_SEMANTICS.md) | how blocks become members |
| [`docs/DECISIONS.md`](docs/DECISIONS.md) | architecture decision record |
| [`docs/GATES.md`](docs/GATES.md) | acceptance criteria |
| [`evidence/VERIFICATION.md`](evidence/VERIFICATION.md) | verification record, generated |
| [`docs/outreach/`](docs/outreach/OUTREACH.md) | academic outreach, community posting and funding playbooks |
| [`CLAUDE.md`](CLAUDE.md) | development guide and invariants |

## License

Block Reality is licensed under the Apache License 2.0; see `LICENSE` and `NOTICE`.

FrameCore, the mechanics backend, is an external source dependency outside this
repository's licence and is covered by its own project's MIT License. Other third-party
components keep their own licences and copyright notices.


---

# 中文

[English](#block-reality) · **中文**

> **Minecraft 裡可直接玩的結構分析實驗室。** 蓋出結構、施加荷載，再沿著荷載路徑理解它為什麼成立，
> 而不是只看一場預先編好的倒塌動畫。

[下載 v0.2a](https://github.com/rocky59487/block-reality/releases/download/v0.2a/blockreality-0.2a.zip)
· [快速上手](QUICKSTART.md)
· [研究簡報](docs/RESEARCH_BRIEF.md)
· [驗證紀錄](evidence/VERIFICATION.md)
· [提供技術回饋](https://github.com/rocky59487/block-reality/issues/new?template=research-feedback.yml)

Block Reality 是 Minecraft 1.20.1 的結構分析模組。放置的方塊會被擷取為 6 自由度樑構件與 MITC4
板殼 facet，交由遊戲程序之外的有限元素求解器計算，再以方塊表面的應力等值圖，以及每根構件、
每片板的需求容量比（D/C）回到畫面上。

![利用率鏡頭](docs/images/utilisation-lens.jpg)

利用率鏡頭。角落的讀數就是這一次求解的結果：構件數、板 facet 數、最大 D/C，以及世界中有幾棟
結構未獲拘束、因而被判定為機構而不給應力。

## 為什麼做 Block Reality

- **力學過程可稽核：** 結構方塊會成為明確的樑構件或板殼 facet；模型、結果欄位與限制都有文件。
- **不偽造合理結果：** 未獲拘束的結構會回報為機構，不會硬塞一組看起來合理的應力。
- **證據可重現：** 發行版保留閉合解、收斂、平衡、跨平台決定性與完整往返時間的紀錄。
- **不拖垮遊戲：** C++ 力學後端以 sidecar 子程序執行；求解器故障不會一起帶走伺服器或世界存檔。

Block Reality 是實驗性的研究與教育軟體，**不是**建築法規檢核器，也不能用於真實結構設計或安全判斷。

## 幫我實際檢驗它

現階段最有價值的不是泛泛稱讚，而是有人真的安裝、蓋出案例並指出哪裡值得追查。先跑快速上手的
懸臂，再試樓板、剪力牆或細長柱；如果結果出乎預期，可以用
[研究回饋表單](https://github.com/rocky59487/block-reality/issues/new?template=research-feedback.yml)
附上模型與參考值。

研究者與工程師可直接從[一頁研究簡報](docs/RESEARCH_BRIEF.md)與自動產生的
[驗證紀錄](evidence/VERIFICATION.md)開始。如果你認為這個方向值得繼續，GitHub star 就是最簡單、
最清楚的支持訊號。

## 需求

- Minecraft **1.20.1**
- Forge **47.x**（[下載](https://files.minecraftforge.net/net/minecraftforge/index.html)）

## 安裝

1. 下載
   [**`blockreality-0.2a.zip`**](https://github.com/rocky59487/block-reality/releases/download/v0.2a/blockreality-0.2a.zip)
   （2.4 MB）。之後的版本在
   [Releases 頁](https://github.com/rocky59487/block-reality/releases/latest)
2. 解壓縮後，Windows 執行 `install.bat`，Linux 執行 `./install.sh`
   （macOS：mod 可安裝可玩，但發行包尚無 macOS 版引擎——除非自行建置 `br-sidecar`，分析為關閉狀態）
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

創造分頁「Block Reality」提供全部結構方塊與應力眼鏡。方塊的 token 決定它成為哪種元素——
樑與板是不同的元素型別,不是不同顏色：

| 方塊 | token | 元素 | 尺寸 |
|---|---|---|---|
| 結構鋼 | `steel_rect_200x400` | 6 自由度樑 | 斷面 200 × 400 mm |
| 結構鋼 150x300 | `steel_rect_150x300` | 6 自由度樑 | 斷面 150 × 300 mm |
| 結構鋼 100x200 | `steel_rect_100x200` | 6 自由度樑 | 斷面 100 × 200 mm |
| 素混凝土樑 | `concrete_rect_400x600` | 6 自由度樑 | 400 × 600 mm,無筋——受拉 3 MPa 就裂,正該如此 |
| 木樑 | `timber_rect_140x240` | 6 自由度樑 | 140 × 240 mm 製材斷面 |
| 磚柱 | `brick_rect_230x350` | 6 自由度樑 | 230 × 350 mm 砌體柱 |
| 混凝土樓板 | `concrete_slab_200` | MITC4 板殼 facet | 厚 200 mm |
| 混凝土樓板 150 | `concrete_slab_150` | MITC4 板殼 facet | 厚 150 mm |
| 鋼板 20 | `steel_plate_20` | MITC4 板殼 facet | 厚 20 mm |

每個 token 在拿到方塊之前都先過閉合解 gate(`verify.py` C1/C1b/C15)。刻意**沒有**磚牆板：
板的篩選是彈性 von Mises,看不見脆性材料的拉壓不對稱——磚因此只以柱存在,樑篩選的五個
獨立比值(含受拉)能誠實處理不對稱。

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
| `/br status` | 引擎狀態、搜尋過的每一條路徑、傳輸方式、上一次的結果 |
| `/br members` | 每根構件的 D/C、控制纖維、控制斷面、峰值應力 |
| `/br section <id>` | 單一構件的完整應力剖面，純文字 |
| `/br load <fx> <fy> <fz>` | 對注視的方塊施加測試荷載（kN）——`/br load 30 0 0` 就是把剪力牆往側面推 |
| `/br unload` / `/br unload all` | 移除注視方塊的荷載／全部移除 |
| `/br loads` | 列出所有測試荷載 |
| `/br scan [半徑]` | 重讀你周圍的區塊，預設半徑 4——供指令或 WorldEdit 放置的方塊使用 |
| `/br resolve` | 強制重新分析 |
| `/br reset` | 引擎被停用後重新啟動 |

會改動狀態的四條指令——`load`、`unload`、`scan`、`resolve`——以及 `reset` 需要
OP 權限 2。單人創造世界本來就有；生存模式或未開作弊、以及伺服器上非 OP 的玩家，
這幾條連指令樹都不會出現。**應力眼鏡不需要任何權限**：它施加的是一個設定好的
向下測試荷載，屬於玩法動作；`/br load` 可以給任意方向、任意大小的向量，不是。

更多案例——樓板、剪力牆、細長柱的挫屈、構件中段加載——見 [`QUICKSTART.md`](QUICKSTART.md)。

## 範圍

已實作：6 自由度樑構件、MITC4 板殼（含樓板與剪力牆）、含樑與板殼幾何勁度的線性挫屈、每構件
與每片板的 D/C、方塊表面的應力等值圖，以及模組與引擎之間的零拷貝共用記憶體傳輸（JSON 保留為
fallback 與除錯面）。wire 上的每一個力學數值都是引擎函式的回傳值,由引擎自己的閉合解 gate
把關——轉接層不算任何東西。設計層與工法層尚未開始。

未實作：板的 D/C 只是彈性表面篩選——橫向剪力有算出來也有回報，但不納入篩選；沒有逐片板的挫屈
檢核，也沒有板的極限強度。沒有 RC 複合斷面，斷面目錄裡是實心矩形與實心圓形，命名也照實寫。
沒有非線性後挫屈：挫屈倍數是線性起始點，是真實臨界載重的上界。

## 驗證

| | |
|---|---|
| 引擎 | `sidecar/verify.py` 251 項全過，每一項都對閉合解、不依賴求解器的不變量,或傳輸等價 oracle |
| Java | 187 項測試全過（純 Java 155、Forge 側 32），其中 28 項會實際啟動 `br-sidecar` 執行 FrameCore |
| 對閉合解 | 31 項非零參考，最差相對誤差 1.2e-14；10 項零參考，最差絕對殘差 1.5e-08。（前兩版在這裡引用的 1.6e-10,後來查明是舊 wire 的 10 位截斷,不是引擎） |
| 傳輸 | 數值以 raw little-endian double 走共用記憶體,從不文字化;JSON fallback 印 17 位有效數字。gate:三個代表案兩種傳輸逐位元相同 |
| 板元素收斂 | 固端方形板：跨中彎矩 8 元素 1.75%、20 元素 0.28%，實測收斂階 2.06；還原後的支承彎矩 20 元素 2.7% |
| 剪力牆 | h/w ≥ 5 對梁理論：剪力流 1.4e-7、傾覆 ~1e-9；h/w = 3 是 2.7e-5 / 6.8e-7；方形牆是深樑，樑理論本身不適用（1e-3 至 2e-2 是參考模型的誤差） |
| 線性挫屈 | 單元素柱對課本值 1.6e-05；1/L² 關係 2.3e-10 |
| 跨平台決定性 | 8/8 逐位元相同，Linux 原生對 Windows 交叉編譯 |
| 效能 | 傳輸（同一個 86 構件模型兩種走法）：共用記憶體 4.5 ms、JSON 28.3 ms（省 84%）；199 構件、1200 自由度的完整往返（含挫屈）在充滿雜訊的參考筆電上約 0.1 s——且永不在 tick 執行緒上,這是「拿到結果的延遲」不是「從遊戲拿走的時間」 |

每一次求解的回覆都附帶全域平衡殘差，該值由幾何與密度重算，而非從組裝後的載重向量讀回。完整
紀錄見 [`evidence/VERIFICATION.md`](evidence/VERIFICATION.md)，由 `scripts/evidence.py` 產生。

## 文件

| 檔案 | |
|---|---|
| [`QUICKSTART.md`](QUICKSTART.md) | 安裝、自行建置、進遊戲後的操作 |
| [`docs/RESEARCH_BRIEF.md`](docs/RESEARCH_BRIEF.md) | 給審閱者的一頁：數值範圍、證據、未解問題 |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | 如何回報你不同意的結果，以及該附上什麼證據 |
| [`docs/RELEASING.md`](docs/RELEASING.md) | 打包與發版流程 |
| [`docs/ENGINE_BOUNDARY.md`](docs/ENGINE_BOUNDARY.md) | Java 與力學引擎的介面契約 |
| [`docs/MEMBER_SEMANTICS.md`](docs/MEMBER_SEMANTICS.md) | 方塊如何成為構件 |
| [`docs/DECISIONS.md`](docs/DECISIONS.md) | 架構決策紀錄 |
| [`docs/GATES.md`](docs/GATES.md) | 驗收判準 |
| [`evidence/VERIFICATION.md`](evidence/VERIFICATION.md) | 驗證紀錄，自動產生 |
| [`docs/outreach/`](docs/outreach/OUTREACH.md) | 學術合作、社群發佈與資助申請的行動手冊 |
| [`CLAUDE.md`](CLAUDE.md) | 開發指引與不變式 |

## 授權

Block Reality 以 Apache License 2.0 授權，見 `LICENSE` 與 `NOTICE`。

力學後端 FrameCore 為本倉庫授權範圍之外的外部 source dependency，依其原專案的 MIT License
授權。其他第三方元件保留各自的授權與著作權聲明。
