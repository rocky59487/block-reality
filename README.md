# Block Reality

**English** · [中文](#中文)

> **A playable structural-analysis laboratory inside Minecraft.** Build a structure,
> apply loads, and inspect its load path instead of guessing why it works.

[Download v0.3c](https://github.com/rocky59487/block-reality/releases/download/v0.3c/blockreality-0.3c.zip)
· [Quick start](QUICKSTART.md)
· [Research brief](docs/RESEARCH_BRIEF.md)
· [Verification record](evidence/VERIFICATION.md)
· [Share technical feedback](https://github.com/rocky59487/block-reality/issues/new?template=research-feedback.yml)

![Minecraft 1.20.1](https://img.shields.io/badge/Minecraft-1.20.1-62B47A)
![Release v0.3c](https://img.shields.io/badge/release-v0.3c-3B82F6)
![Verification](https://img.shields.io/badge/verification-282_engine_%2B_232_Java_checks-passing)
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

**Put `blockreality-0.3c.jar` in your `mods/` folder and launch the game.** That is the
whole procedure; the analysis engine is inside the jar and unpacks itself the first time
it is needed. Get the jar from CurseForge, from Modrinth, or from the
[Releases page](https://github.com/rocky59487/block-reality/releases/latest).

On first use the engine appears at
`<game directory>/blockreality/engine/<hash>/br-sidecar[.exe]`. The folder is named after
the binary's SHA-256, so a mod update lands beside the old one instead of overwriting it,
and a half-written file can never be mistaken for a good one. **Nothing is downloaded** —
the bytes were already in the jar you installed, their hash is recorded beside them, and
anything that does not match is refused rather than run. A binary you put there yourself
is never touched.

Windows and Linux on x86-64 only. On macOS or ARM the mod loads and plays normally with
analysis disabled and says so; build `br-sidecar` from source and point `sidecarPath` at
it if you want analysis there.

<details>
<summary>The full archive, for verifying or running the engine outside the game</summary>

[**`blockreality-0.3c.zip`**](https://github.com/rocky59487/block-reality/releases/download/v0.3c/blockreality-0.3c.zip)
also ships both engines loose, with `SHA256SUMS.txt` and installer scripts:

```
install.bat "D:\games\my-instance\.minecraft"
./install.sh ~/.minecraft
./install.sh --list      # list what was found, install nothing
```

| | |
|---|---|
| `blockreality-*.jar` | the Forge mod — this alone is a complete install |
| `br-sidecar` / `br-sidecar.exe` | the same engines, loose, for checking or for running standalone |
| `START-HERE.txt` / `讀我-中文.txt` | instructions, English and Chinese |
| `SHA256SUMS.txt` | SHA-256 of every file |

The loose binaries are byte-identical to the ones inside the jar, and a build gate holds
them to that. The build also pins the one field that made it non-deterministic (the PE
link timestamp), so two clean builds on the same toolchain give identical hashes —
measured. That is not the same as *you* being able to reproduce them: the engine links
FrameCore, which this repository does not carry, so an independent rebuild is not
something anyone outside can do today.
</details>

FrameCore is statically linked into `br-sidecar`, so there is no separate library to
install. It runs as its own process rather than as a library loaded by the mod (D-013), so
a fault in the C++ costs one analysis rather than the server and the save. The mod looks
for the engine in this order: the config file, `-Dbr.sidecar`, `BR_SIDECAR`, the copy
bundled in the jar, the game directory, then `PATH` — explicit settings first, so a path
you chose is never quietly overridden.

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
post-buckling: the buckling factor is the linear onset, and it says nothing about what
happens after the structure folds.

**A run of collinear blocks is one beam element, and the buckling factor feels it.** The
extractor turns each straight run into a single member unless something — a load, a
junction, a change of material — forces an interior node, and one element carries one
constant axial force. Where the axial force really is nearly constant that costs almost
nothing: a 19 m cantilever under a top load more than 400× its self weight comes back
0.5% from the Euler value on a single element. Where the axial force varies along the
member it costs a great deal. The same column buckling under **its own weight** reports
3.14 against Greenhill's exact 9.89 — 68% low — climbing to 5.28, 7.05, 8.55 and 9.15 as
2, 4, 10 and 19 elements are forced. So in that regime the reported factor is
conservative rather than an upper bound, and it is visibly mesh-dependent: dropping a
**one-newton** test load at mid-height raises it by 68%, because the run splits in two.
Both measurements are one command each against the shipped engine —
`sidecar/repro_selfweight_buckling.py` and `sidecar/repro_euler_direction.py`. Element
subdivision is a meshing policy, so it is registered as a v0.4 criterion rather than
changed inside a patch release; see `docs/GATES.md`.

**Two of the five D/C modes have no closed-form gate.** `ElasticAllowable` takes the
argmax of five ratios, and the acceptance suite pins three of them — CRUSH, TENSION and
the bending fibres — against closed forms. **SHEAR and TORSION are reported but ungated**:
nothing in `sidecar/verify.py` compares either against a reference, and an independent
hand calculation of the torsion ratio differed from the engine by about 20%. Until that
is settled, read a member whose governing fibre says SHEAR or TORSION as indicative, not
as a number this project has verified. Rule 2 of `docs/GATES.md` — no capability without
a gate that has run — is why this paragraph exists rather than a quieter omission.

## Verification

| | |
|---|---|
| Engine | `sidecar/verify.py`, 282 checks, all passing, each against a closed form, a solver-independent invariant, or a transport-equivalence oracle |
| Java | 232 tests, all passing (192 pure-Java, 40 Forge-side); 28 of them start `br-sidecar` and run FrameCore for real |
| Closed form | 31 non-zero references, worst relative error 1.2e-14; 10 zero references, worst absolute residual 1.5e-08. (Two earlier releases quoted 1.6e-10 here — that floor turned out to be the old wire's 10-digit truncation, not the engine) |
| Transport | numbers cross as raw little-endian doubles in shared memory, never textualised; the JSON fallback prints 17 significant digits. Gate: three representative solves bit-identical across both transports |
| Shell convergence | clamped square plate: span moment 1.75% at 8 elements down to 0.28% at 20, observed convergence order 2.06; recovered support moment 2.7% at 20 |
| Shear wall | h/w ≥ 5 agrees with beam theory to 1.4e-7 (shear flow) and ~1e-9 (overturning); h/w = 3 is 2.7e-5 / 6.8e-7; a square wall is a deep beam, where beam theory itself is the wrong model (1e-3 to 2e-2 is the reference's error) |
| Buckling | single-element column against the textbook value, 1.6e-05; the 1/L² law, 2.3e-10 |
| Determinism | 8/8 cases byte-for-byte identical, Linux native against the Windows cross-build |
| Performance | transport, same 86-member solve both ways: 2.3 ms over shared memory vs 15.7 ms over JSON (85% saving); a 199-member, 1200-DOF frame completes its full round trip including buckling in ~50 ms — and never on the tick thread, so this is latency to a result, not time taken from the game. These are one sample on a noisy reference laptop and they move: the same three figures were 3.9 / 24.3 / 85 ms one build earlier. The saving ratio is stable; the absolute numbers are not, so read them as an order of magnitude and take `evidence/VERIFICATION.md`, which re-measures on every build, as the record |

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
| [`docs/outreach/LISTING.md`](docs/outreach/LISTING.md) | CurseForge and Modrinth listing copy, and the bundled-binary disclosure |
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

[下載 v0.3c](https://github.com/rocky59487/block-reality/releases/download/v0.3c/blockreality-0.3c.zip)
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

**把 `blockreality-0.3c.jar` 丟進 `mods/` 資料夾，開遊戲。** 就這樣——分析引擎就在 jar 裡面，
第一次要用到的時候自己解出來。jar 可以從 CurseForge、Modrinth，或
[Releases 頁](https://github.com/rocky59487/block-reality/releases/latest)取得。

引擎會出現在 `<遊戲目錄>/blockreality/engine/<雜湊>/br-sidecar[.exe]`。資料夾以二進位的
SHA-256 命名，所以更新 mod 時新引擎落在舊的旁邊而不是覆蓋它，寫到一半的檔案也不可能被
當成好的。**全程不下載任何東西**——那些位元組本來就在你安裝的 jar 裡，雜湊記在旁邊，
對不上就拒絕執行。你自己放的二進位永遠不會被動到。

只有 Windows 與 Linux 的 x86-64。macOS 或 ARM 上模組照常載入、照常遊玩，分析關閉並說明原因；
想在那裡分析請自行建置 `br-sidecar` 並用 `sidecarPath` 指向它。

<details>
<summary>完整壓縮檔——想驗證引擎、或想在遊戲外執行它的話</summary>

[**`blockreality-0.3c.zip`**](https://github.com/rocky59487/block-reality/releases/download/v0.3c/blockreality-0.3c.zip)
另外附上兩顆引擎的獨立檔、`SHA256SUMS.txt` 與安裝腳本：

```
install.bat "D:\games\my-instance\.minecraft"
./install.sh ~/.minecraft
./install.sh --list      # 只列出找到的實例，不安裝
```

| | |
|---|---|
| `blockreality-*.jar` | Forge 模組——光是這個檔案就是完整安裝 |
| `br-sidecar` / `br-sidecar.exe` | 同樣的兩顆引擎，獨立檔，供查驗或單獨執行 |
| `START-HERE.txt` / `讀我-中文.txt` | 說明文件，英文與中文 |
| `SHA256SUMS.txt` | 每個檔案的 SHA-256 |

獨立檔與 jar 裡那兩顆逐位元相同，並且有建置 gate 釘住這件事。建置也把唯一那個造成不確定性的
欄位釘死了（PE 連結時間戳），所以同一套工具鏈上兩次乾淨建置得到相同雜湊——這是實測的。但那
不等於**你**重現得出來：引擎連結 FrameCore，而本倉庫不收錄它，外部獨立重建目前做不到。
</details>

FrameCore 已靜態連結進 `br-sidecar`，沒有另外要安裝的函式庫。它以獨立程序執行而非由模組
載入（D-013），因此 C++ 端的錯誤只影響一次分析，不影響伺服器與存檔。模組尋找引擎的順序為：
設定檔、`-Dbr.sidecar`、`BR_SIDECAR`、**jar 內附的那顆**、遊戲目錄、`PATH`——明確設定排在
最前面，所以你指定的路徑永遠不會被安靜地覆蓋。

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
沒有非線性後挫屈：挫屈倍數是線性起始點，它不告訴你失穩之後會怎麼樣。

**一段共線方塊是一個樑元素，而挫屈倍數吃這個。** 抽取層把每一段直線 run 變成單一構件，
除非有東西（載重、接頭、材料變更）逼出內部節點，而一個元素只帶一個定值軸力。軸力本來就
接近均勻時，這幾乎不花代價：19 m 懸臂柱在超過自重 400 倍的頂部載重下，單元素的結果距
Euler 值 0.5%。軸力沿桿變化時，代價很大——同一根柱**受自身重量**挫屈，回報 3.14，
Greenhill 的精確解是 9.89，**低 68%**；逼出 2、4、10、19 個元素後依序爬到 5.28、7.05、
8.55、9.15。所以在這個情形下回報值是**偏保守**而不是上界，而且它的網格依賴是玩家看得見的：
在半高處丟一個**一牛頓**的測試載重，倍數就上升 68%，因為那段 run 被切成兩個元素。
兩項量測各一行指令，對出貨引擎跑：`sidecar/repro_selfweight_buckling.py` 與
`sidecar/repro_euler_direction.py`。元素細分是網格策略，因此登記為 v0.4 的判準項目，
不在修訂版裡改；見 `docs/GATES.md`。

**五個 D/C 模式裡有兩個沒有閉合解 gate。** `ElasticAllowable` 取五個比值的 argmax，
驗收套件釘住其中三個（CRUSH、TENSION、彎曲纖維）對閉合解。**SHEAR 與 TORSION 有回報、
沒有 gate**：`sidecar/verify.py` 裡沒有任何一條拿它們對參考值，而獨立手算的扭轉比值與
引擎差約 20%。在這件事釐清之前，控制纖維顯示 SHEAR 或 TORSION 的構件請當作**指示值**，
不是本專案已驗證過的數字。`docs/GATES.md` 鐵則 2（沒有 gate 執行過的能力不得寫進能力
清單）就是這段話存在的理由，而不是安靜地略過。

## 驗證

| | |
|---|---|
| 引擎 | `sidecar/verify.py` 282 項全過，每一項都對閉合解、不依賴求解器的不變量,或傳輸等價 oracle |
| Java | 232 項測試全過（純 Java 192、Forge 側 40），其中 28 項會實際啟動 `br-sidecar` 執行 FrameCore |
| 對閉合解 | 31 項非零參考，最差相對誤差 1.2e-14；10 項零參考，最差絕對殘差 1.5e-08。（前兩版在這裡引用的 1.6e-10,後來查明是舊 wire 的 10 位截斷,不是引擎） |
| 傳輸 | 數值以 raw little-endian double 走共用記憶體,從不文字化;JSON fallback 印 17 位有效數字。gate:三個代表案兩種傳輸逐位元相同 |
| 板元素收斂 | 固端方形板：跨中彎矩 8 元素 1.75%、20 元素 0.28%，實測收斂階 2.06；還原後的支承彎矩 20 元素 2.7% |
| 剪力牆 | h/w ≥ 5 對梁理論：剪力流 1.4e-7、傾覆 ~1e-9；h/w = 3 是 2.7e-5 / 6.8e-7；方形牆是深樑，樑理論本身不適用（1e-3 至 2e-2 是參考模型的誤差） |
| 線性挫屈 | 單元素柱對課本值 1.6e-05；1/L² 關係 2.3e-10 |
| 跨平台決定性 | 8/8 逐位元相同，Linux 原生對 Windows 交叉編譯 |
| 效能 | 傳輸（同一個 86 構件模型兩種走法）：共用記憶體 2.3 ms、JSON 15.7 ms（省 85%）；199 構件、1200 自由度的完整往返（含挫屈）約 50 ms——且永不在 tick 執行緒上,這是「拿到結果的延遲」不是「從遊戲拿走的時間」。這三個數是充滿雜訊的參考筆電上的**單次取樣**,而且會動：上一次建置同樣三個數是 3.9 / 24.3 / 85 ms。比值穩定、絕對值不穩定,所以請當成數量級讀,以每次建置都重測的 `evidence/VERIFICATION.md` 為準 |

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
| [`docs/outreach/LISTING.md`](docs/outreach/LISTING.md) | CurseForge／Modrinth 上架文案與原生執行檔申報 |
| [`CLAUDE.md`](CLAUDE.md) | 開發指引與不變式 |

## 授權

Block Reality 以 Apache License 2.0 授權，見 `LICENSE` 與 `NOTICE`。

力學後端 FrameCore 為本倉庫授權範圍之外的外部 source dependency，依其原專案的 MIT License
授權。其他第三方元件保留各自的授權與著作權聲明。
