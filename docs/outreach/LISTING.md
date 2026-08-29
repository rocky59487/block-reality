# 上架：CurseForge 與 Modrinth

貼上就能用的欄位與文案。每一句宣稱都對得上倉庫裡可查的東西——那是這個專案唯一的
賣點，寫得比實情漂亮一次就全毀了。

> 兩個平台的規則會改。送審前**再讀一次當下的 submission guidelines**，本檔說的是
> 「要準備什麼、要主動講什麼」，不是它們此刻的條款原文。

---

## 0 · 送審前必須為真的事

**狀態以 2026-08-29 v0.3c 為準。** 勾起來的是已驗過的，沒勾的是**還缺**，不是「大概好了」。

- [x] `Main` 上的 CI 綠（分支保護已把它設為 merge 必要條件）
- [x] `scripts/check_bundle.py` 通過：jar 內的引擎 == manifest == evidence == `dist/` 獨立檔
- [x] `scripts/check_docs.py` 通過：34 處引用數字對得上實測（282 / 215 / 41 / 28）
- [x] tag `v0.3c` 已推、release workflow 綠、Releases 頁上有 `blockreality-0.3c.zip`
- [x] `README.md` 的下載連結指向這一版（中英各兩處，共七處版本字串）
- [x] 圖示 `forge/src/main/resources/blockreality_icon.png`（400×400）在 jar 裡，
      `mods.toml` 的 `logoFile` 指得到它
- [ ] 🔴 **下載回來的 jar 實際安裝過一次**：丟進 `mods/`、開遊戲、放一根懸臂、看到讀數。
      **這條只有你能做，而且是唯一的硬阻擋**——所有自動化 gate 驗的是位元與數值，
      沒有一條驗過「Forge 真的載入它、玩家真的看到讀數」

素材（一次做齊，兩個平台共用）：

- [x] **圖示** 400×400 PNG —— `scripts/make_icon.py` 產生，可重跑，非 AI 圖
- [ ] **截圖 5 張** —— 目前 `docs/images/` 只有 2 張（`utilisation-lens.jpg`、
      `section-view.jpg`）。還缺：挫屈警告、剪力牆、`/br members` 文字輸出
- [ ] **GIF 1–2 個**（<10 MB）—— 放樑 → 加載 → 變紅；細長柱挫屈
- [ ] **60–90 秒影片** —— 仍是最大缺件

> 兩個平台都**允許先建專案、上傳檔案、把描述填好而不發布**（CurseForge 存草稿等審核；
> Modrinth 專案預設是 draft，要按 Submit for review 才進佇列）。所以截圖與影片不擋你
> 今天就把文字與檔案就位——但**在真的按下發布之前，上面那條紅的要先做掉**。

---

## 1 · 最重要的一段：原生執行檔申報

**主動寫在描述裡，不要等審核問。** 這個 mod 內含並執行一顆原生二進位，這在兩個平台
都是會被特別看的事。它是完全正當的，但正當性要由你說出來，不是由審核者推測。

貼這一段（英文，放在描述的顯眼處）：

> ### About the bundled analysis engine
>
> This mod ships two native executables inside its jar — `blockreality-engine/br-sidecar`
> (Linux x86-64, 2 676 992 bytes) and `blockreality-engine/br-sidecar.exe` (Windows
> x86-64, 4 118 572 bytes) — and unpacks the one matching your platform to
> `<game directory>/blockreality/engine/<hash>/` the first time an analysis runs. Nothing
> is unpacked on any other platform. Please read this before you decide whether to trust
> it:
>
> - **It downloads nothing, ever.** There is no updater, no telemetry, no remote call. You
>   do not have to take that on faith: `br-sidecar.exe` names exactly two DLLs in its
>   import table, `KERNEL32.dll` and `msvcrt.dll` — no `WS2_32`, no `WININET`, no
>   `WINHTTP`. One command shows it:
>   `objdump -p br-sidecar.exe | grep "DLL Name"`. The Linux build links only `libm`,
>   `libc` and the loader, and `objdump -T br-sidecar` contains no socket, connect or
>   resolver symbols. The `sidecar/` sources include no networking header at all.
> - **It is verified before it runs.** `blockreality-engine/engine.manifest` in the jar
>   records the SHA-256 and byte length of each binary; a file that does not match is
>   deleted rather than executed, and the unpack folder is named after that hash so it can
>   never silently overwrite an engine you put there yourself.
> - **It is a solver, not a launcher.** It reads a structural model on stdin or through a
>   shared-memory buffer and writes numbers back — no shell, no file system beyond its own
>   temp buffer, no child processes of its own. It runs out of process precisely so that a
>   fault in the C++ costs one analysis instead of your server or your world.
> - **It is open source and Apache-2.0**, built from `sidecar/` in this repository. The
>   build pins the one field that made it non-deterministic, so two clean builds on the
>   same toolchain give identical hashes, and those hashes are published in the release and
>   in `evidence/VERIFICATION.md`. One caveat stated plainly: rebuilding it independently
>   is not possible today, because it statically links FrameCore, an external source
>   dependency this repository does not carry.
> - **It is not code-signed.** It is a MinGW cross-build from an unfunded open-source
>   project, so there is no certificate. Statically linked, unsigned binaries of this shape
>   do sometimes draw heuristic hits from individual antivirus engines; the SHA-256 values
>   below are published so you can check any scan result against the exact bytes we shipped
>   rather than a repack.
> - **You can refuse it.** Set `analysisEnabled = false` in the world's server config and
>   nothing is unpacked and nothing is started; or point `sidecarPath` at your own build,
>   which also means the bundled copy is never unpacked. (Deleting the unpacked folder does
>   not refuse it — the next launch puts it back.) Without an engine the mod loads and
>   plays normally with analysis disabled, and says so.
>
> ```
> br-sidecar.exe  windows x86_64  4118572  sha256 c4d571a5911b21d405394609d7430f428c0492520510af3bc92250ca140f56d2
> br-sidecar      linux   x86_64  2676992  sha256 d9721cc6070695e752b2211e065fdedce07a14fc3bc9b3273682eeec7606974f
> ```
>
> Source: <https://github.com/rocky59487/block-reality> · Verification record:
> [`evidence/VERIFICATION.md`](https://github.com/rocky59487/block-reality/blob/Main/evidence/VERIFICATION.md)

### 1.1 · 被轉人工審核／防毒掃描時（預期會發生）

CurseForge 對「jar 裡有 `.exe`」幾乎必然轉人工，這**不是被拒**，是流程。假設它會發生，
第一次上傳就把 §1 那段放在描述最上面，並準備好下面這封回信。

**回信範本（英文，直接貼）：**

> Hi — thanks for taking the time. Yes, the jar contains two native executables, and I
> expected this to need a human. Here is everything needed to check it.
>
> **What they are.** `blockreality-engine/br-sidecar` (Linux x86-64) and
> `br-sidecar.exe` (Windows x86-64) are the mod's finite-element solver. The mod is a
> structural-analysis tool; the numerical core is C++ because it is a sparse direct
> solver, and it runs out of process so that a fault in it costs one analysis instead of
> the player's server or world save.
>
> **Exact bytes shipped:**
> ```
> br-sidecar.exe  4118572 bytes  sha256 c4d571a5911b21d405394609d7430f428c0492520510af3bc92250ca140f56d2
> br-sidecar      2676992 bytes  sha256 d9721cc6070695e752b2211e065fdedce07a14fc3bc9b3273682eeec7606974f
> ```
> The same two hashes are recorded inside the jar at
> `blockreality-engine/engine.manifest`, in the GitHub release's `SHA256SUMS.txt`, and in
> `evidence/verification.json`. A CI gate (`scripts/check_bundle.py`) fails the build if
> those four ever disagree.
>
> **No network capability.** `objdump -p br-sidecar.exe | grep "DLL Name"` returns
> `KERNEL32.dll` and `msvcrt.dll` and nothing else — no `WS2_32`, `WININET` or `WINHTTP`.
> The Linux binary links `libm`, `libc` and the loader only, with no socket, connect or
> resolver symbols in `objdump -T`. The sources include no networking header. There is no
> updater and no telemetry of any kind.
>
> **Not code-signed.** This is an unfunded open-source project cross-built with MinGW, so
> there is no certificate, and unsigned statically linked binaries do sometimes draw
> heuristic AV hits. If a scanner flagged it I would genuinely like the engine name and
> detection string so I can report the false positive upstream.
>
> **Source and build.** Apache-2.0, <https://github.com/rocky59487/block-reality>, all
> engine sources under `sidecar/`. The Windows build pins its PE timestamp, so a clean
> rebuild of the same source on the same toolchain reproduces the hash above.
>
> **The player can refuse it.** `analysisEnabled = false` in the world's server config
> means nothing is unpacked and nothing is started; `sidecarPath` points at the player's
> own build instead. The mod loads and plays with analysis disabled and says so.
>
> Happy to supply anything else — a build log, the unstripped binary, or a walkthrough of
> the unpack path.

**附件順序**（對方要更多時，照這個順序給，一次一份）：`docs/DECISIONS.md` 的 **D-027**
（為什麼放進 jar、四條約束）→ `mod/core/.../BundledEngine.java`（那四條約束的實作）→
`mod/core/src/test/.../BundledEngineTest.java`（每一條都有測試）→
`scripts/check_bundle.py`（四方雜湊一致的 gate）→ `evidence/VERIFICATION.md`（引擎自身的
驗收紀錄）。

**不要做的事**：不要為了過掃描去加殼、改名副檔名、或把二進位拆成資料檔再在執行期組回來。
那些做法本身就是掃描器在找的特徵，而且會把一件正當的事變成看起來要躲。誠實申報＋可複現的
雜湊是唯一的路。

---

## 2 · CurseForge

| 欄位 | 值 |
|---|---|
| Project name | Block Reality |
| Summary（短描述，會出現在搜尋結果） | Real finite element analysis on the blocks you place — stress contours, demand/capacity per member, and buckling. |
| Categories | Technology、Education |
| Game version | 1.20.1 |
| Mod loader | Forge |
| Environment | Client **and** Server |
| License | Apache-2.0（連 repo） |
| Source | `https://github.com/rocky59487/block-reality` |
| Issues | `https://github.com/rocky59487/block-reality/issues` |
| Website | 你的 Block Reality 站台網址 |
| Release type | **Beta**（0.x 版號，功能會動；標 Release 會招來不必要的期待） |
| 上傳檔案 | `blockreality-0.3c.jar` 單檔即可——引擎在裡面 |

### 描述（貼上即可）

> **Real structural engineering inside Minecraft.**
>
> The blocks you place are extracted into 6-DOF beam members and MITC4 shell plates and
> solved by a real finite element engine. You get stress contours painted on the blocks, a
> demand/capacity ratio for every member and plate with the governing fibre named, and a
> linear buckling factor — so a slender column that "looks fine" on stress still tells you
> it is about to fold.
>
> **Install:** drop the jar in `mods/`. That is all — the analysis engine is inside it.
>
> **What it does**
>
> - Nine structural blocks: three steel sections, plain concrete, timber, a brick pier, two
>   slab thicknesses, a 20 mm steel plate. **They mix** — a timber beam on brick piers is
>   one structure, joined where the blocks touch.
> - Stress glasses with three lenses (utilisation, stress, material), a section readout
>   showing tension and compression through the section depth and the neutral axis, and a
>   HUD that names which fibre governs.
> - `/br load <fx> <fy> <fz>` puts a test load in kN, in any direction, on the block you
>   are looking at — push a shear wall sideways and watch the load path change.
> - Unsupported structures are reported as **mechanisms** rather than given plausible
>   looking stresses. Solves run off the tick thread, so the game does not stutter.
>
> **Why you might trust the numbers**
>
> 282 acceptance checks run on every build. **41 of them are comparisons against
> textbook closed forms** — worst relative error 1.2e-14 — and the rest are
> solver-independent invariants, transport-equivalence oracles and refusal cases. The
> whole record is public and regenerated by a script, not typed in by hand. Where the
> project cannot back a number it says so: the section catalogue is called "solid
> rectangle" because that is what is actually solved, and two of the five
> demand/capacity modes (shear and torsion) are documented as having no oracle at all.
>
> **What it deliberately does not do yet**
>
> Nothing breaks or falls: this is analysis, not collapse. No construction sequencing, no
> reinforced-concrete composite sections, no load combinations or wind. Everything is
> linear elastic, and the buckling factor is the linear onset — it says nothing about what
> happens after a member folds. It is also mesh-dependent, and the project would rather you
> heard that from us: a straight run of blocks is solved as **one beam element** unless a
> load or a junction forces an interior node, which is accurate to 0.5% when the axial
> force is nearly uniform but reports 3.14 against the exact 9.89 for a 19 m column
> buckling under its own weight — conservative, by a lot. Two scripts in the repository
> reproduce both numbers against the shipped engine.
>
> **Requires** Minecraft 1.20.1 and Forge 47.x. Windows and Linux x86-64; on macOS or ARM
> the mod plays normally with analysis disabled.
>
> *(接第 1 節的原生執行檔申報段落)*

---

## 3 · Modrinth

| 欄位 | 值 |
|---|---|
| Slug | `block-reality` |
| Summary | 同 CurseForge 的 Summary（Modrinth 限 256 字元） |
| Categories | Technology、Utility（Modrinth 沒有 Education 分類） |
| Client side | Required（渲染與 HUD 在 client） |
| Server side | Required（分析跑在邏輯 server） |
| License | Apache-2.0 |
| Links | Source / Issues / Wiki 各填 repo 對應頁；Website 填站台 |
| Version number | `0.3c` |
| Version name | `Block Reality 0.3c` |
| Loaders / Game versions | Forge / 1.20.1 |
| Release channel | **Beta** |

描述（body）用第 2 節同一份 Markdown，Modrinth 直接吃 Markdown，不必改寫。

Modrinth 對「內含原生執行檔」同樣要透明；第 1 節那段照貼。開 creator monetization
見 `FUNDING.md`。

---

## 4 · 版本說明（release notes，兩個平台共用）

> **0.3c — big structures stop stalling on buckling, and a limit gets named out loud**
>
> Installing is still dropping one file into `mods/`; the analysis engine is inside it,
> unpacks itself on first use against a hash recorded beside it, and downloads nothing.
>
> **Buckling now has a size policy.** The eigenvalue solve grows roughly as the cube of
> the model — 0.5 s at 200 nodes, 8.6 s at 500 and 73 s at 1000 on the reference laptop —
> so a large build used to sit there. Above `bucklingBlockLimit` blocks (300 by default, `0` to switch
> buckling off entirely) it is skipped, and the HUD now says **"buckling not evaluated
> (structure size)"** rather than showing a blank that reads as *stable*. Small models are
> unchanged.
>
> **A limit we found while testing this release, stated rather than buried.** A straight
> run of blocks is solved as one beam element unless a load or a junction forces an
> interior node. Where the axial force is nearly uniform that is accurate — 0.5% from
> Euler for a 19 m cantilever under a top load. Where it varies it is not: the same column
> buckling under **its own weight** reports 3.14 where the exact answer is 9.89, 68% low,
> reaching 9.15 once 19 elements exist. So the factor is conservative in that regime, and
> visibly mesh-dependent — a one-newton test load at mid-height raises it by 68%. Earlier
> releases called this number "an upper bound on the real critical load"; that sentence is
> wrong for this case and has been removed everywhere. Both measurements ship as scripts
> you can run against the engine you installed.
>
> Also in this release: macOS and ARM players now get a plain sentence explaining that no
> engine exists for their platform instead of a raw log line; the installer no longer
> claims "no Forge found" inside a CurseForge instance; an explosion handler now runs last
> so protection mods get to amend the block list first; a stale analysis probe can no
> longer drop a player's test load; and an over-long dimension id can no longer throw
> while a result is being broadcast.
>
> Verification: 282 engine checks, 215 Java tests (179 pure-Java, 36 Forge-side), worst
> closed-form relative error 1.216e-14, cross-platform determinism 8/8.

---

## 4.5 · 貼上目標檔（不必從這份文件裡挑）

這份是參考文件，中英夾雜、有表格有註解。真正要貼進網頁表單的兩段文字另外放，
可以整檔全選複製：

| 檔案 | 貼到哪 |
|---|---|
| `docs/outreach/paste/description.md` | CurseForge 的 Description、Modrinth 的 Body（同一份，兩邊都吃 Markdown） |
| `docs/outreach/paste/release-notes-0.3c.md` | 兩邊的 version changelog |

檔頭的 HTML 註解不會顯示，但要不要刪隨你。**改動請先改本檔再同步過去**，別讓兩份漂開。

---

## 4.6 · 只有你能做的事（我做不到的部分，照實說）

下面五件事需要帳號、需要憑證、或需要真的在遊戲裡看一眼。**建立帳號與輸入密碼我不會代做**，
上傳與按下送審也需要你自己決定，這裡把每一步寫清楚，讓你照著做就好。

1. **先在遊戲裡裝一次。** 把 Releases 頁的 `blockreality-0.3c.zip` 下載回來（或直接用
   `dist/blockreality-0.3c.jar`），丟進 `mods/`，開 1.20.1 Forge 47.x，放一根懸臂，
   看到 D/C 讀數。**這是 §0 唯一的紅燈**——沒有任何自動化 gate 涵蓋它。
2. **CurseForge**：登入 → Create Project → 選 Minecraft → Mods。依 §2 的表填欄位，
   Description 貼 `paste/description.md`。上傳 `blockreality-0.3c.jar`，Release type 選
   **Beta**，Game Version 1.20.1，Modloader Forge，changelog 貼
   `paste/release-notes-0.3c.md`。**預期會轉人工審核**（jar 內含 `.exe`），
   §1.1 的回信範本備著。
3. **Modrinth**：登入 → Create a project。slug `block-reality`，依 §3 的表填。
   Body 貼同一份 `paste/description.md`。上傳同一顆 jar，Release channel **Beta**。
   Modrinth 專案預設 draft，填完要按 **Submit for review**。
4. **截圖與影片**（§0 素材清單）——要進遊戲拍，我拍不了。
5. **Website 欄位**填你的站台網址；沒有就留空，兩邊都不是必填。

> 若之後想改成自動上傳（Gradle 的 `minotaur` 對 Modrinth、`CurseGradle` 對 CurseForge），
> 兩者都要一組 API token。**token 請你自己產生並存進 GitHub Secrets，不要貼進對話。**
> 目前倉庫刻意沒有這條路徑：`.github/workflows/release.yml` 只發 GitHub Releases。

---

## 5 · 上架後

- 兩個平台的頁面都連回 GitHub 與站台，而不是互相連——單點失效就少一點
- 第一週每天看一次留言與 issue：**回覆速度決定第一批人會不會留下來**
- 有人回報數字不對時，請他用
  [research feedback form](https://github.com/rocky59487/block-reality/issues/new?template=research-feedback.yml)——
  它問的正是要追查所需的最小重現模型
- 社群發文（Reddit 等）等平台審核通過、下載連結穩定後再發，見 `COMMUNITY.md`
