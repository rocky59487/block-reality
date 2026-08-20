# Block Reality

**English** · [中文](README.zh-TW.md)

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
| `/br reset` | restart the engine after it has been disabled (OP only) |

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
| Engine | `sidecar/verify.py`, 208 checks, all passing, each against a closed form, a solver-independent invariant, or a transport-equivalence oracle |
| Java | 136 tests, all passing; 28 of them start `br-sidecar` and run FrameCore for real |
| Closed form | 31 non-zero references, worst relative error 1.2e-14; 10 zero references, worst absolute residual 1.5e-08. (Two earlier releases quoted 1.6e-10 here — that floor turned out to be the old wire's 10-digit truncation, not the engine) |
| Transport | numbers cross as raw little-endian doubles in shared memory, never textualised; the JSON fallback prints 17 significant digits. Gate: three representative solves bit-identical across both transports |
| Shell convergence | clamped square plate at 20 elements per side: span moment 0.57%, recovered support moment 2.7% |
| Shear wall | slender walls (h/w ≥ 3) agree with beam theory to 1e-7 on both shear flow and overturning; a square wall is 1e-3 to 1e-2 |
| Buckling | single-element column against the textbook value, 1.6e-05; the 1/L² law, 2.3e-10 |
| Determinism | 8/8 cases byte-for-byte identical, Linux native against the Windows cross-build |
| Performance | 202 members and 768 DOF: 7.0 ms for the whole round trip including buckling over shared memory (40.9 ms over JSON), and not on the tick thread |

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
| [`docs/outreach/`](docs/outreach/OUTREACH.md) | academic outreach, community posting and funding playbooks |
| [`CLAUDE.md`](CLAUDE.md) | development guide and invariants |

## License

Block Reality is licensed under the Apache License 2.0; see `LICENSE` and `NOTICE`.

FrameCore, the mechanics backend, is an external source dependency outside this
repository's licence and is covered by its own project's MIT License. Other third-party
components keep their own licences and copyright notices.
