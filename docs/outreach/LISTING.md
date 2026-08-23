# 上架：CurseForge 與 Modrinth

貼上就能用的欄位與文案。每一句宣稱都對得上倉庫裡可查的東西——那是這個專案唯一的
賣點，寫得比實情漂亮一次就全毀了。

> 兩個平台的規則會改。送審前**再讀一次當下的 submission guidelines**，本檔說的是
> 「要準備什麼、要主動講什麼」，不是它們此刻的條款原文。

---

## 0 · 送審前必須為真的事

- [ ] `Main` 上的 CI 綠（分支保護已把它設為 merge 必要條件）
- [ ] `scripts/check_bundle.py` 通過：jar 內的引擎 == manifest == evidence == `dist/` 獨立檔
- [ ] tag `v0.3a` 已推、release workflow 綠、Releases 頁上有 `blockreality-0.3a.zip`
- [ ] 下載回來的 jar **實際安裝過一次**：丟進 `mods/`、開遊戲、放一根懸臂、看到讀數
- [ ] `README.md` 的下載連結指向這一版（不是上一版）
- [ ] 圖示 `forge/src/main/resources/blockreality_icon.png`（400×400）在 jar 裡，
      `mods.toml` 的 `logoFile` 指得到它

素材（一次做齊，兩個平台共用）：

- [ ] **圖示** 400×400 PNG —— 已有，`scripts/make_icon.py` 產生，可重跑
- [ ] **截圖 5 張** —— 利用率鏡頭全景、斷面 HUD 特寫、挫屈警告、剪力牆、`/br members` 文字輸出
- [ ] **GIF 1–2 個**（<10 MB）—— 放樑 → 加載 → 變紅；細長柱挫屈
- [ ] **60–90 秒影片** —— 仍是最大缺件

---

## 1 · 最重要的一段：原生執行檔申報

**主動寫在描述裡，不要等審核問。** 這個 mod 內含並執行一顆原生二進位，這在兩個平台
都是會被特別看的事。它是完全正當的，但正當性要由你說出來，不是由審核者推測。

貼這一段（英文，放在描述的顯眼處）：

> ### About the bundled analysis engine
>
> This mod ships a native executable inside its jar (`br-sidecar`, ~2 MB for Windows and
> Linux x86-64) and unpacks it to `<game directory>/blockreality/engine/` the first time
> analysis runs. Please read this before you decide whether to trust it:
>
> - **It downloads nothing, ever.** No network access at any point. The bytes were already
>   in the jar you installed. There is no updater, no telemetry and no remote call.
> - **It is verified before it runs.** The SHA-256 of each binary is recorded in the jar
>   beside it; anything that does not match is deleted rather than executed. The unpack
>   folder is named after that hash.
> - **It is open source and reproducible.** `br-sidecar` is Apache-2.0, built from
>   `sidecar/` in the repository, and the build is byte-reproducible — compile it yourself
>   and you get the same hashes, which are published in the release and in
>   `evidence/VERIFICATION.md`.
> - **It is a solver, not a launcher.** It reads a structural model on stdin or through a
>   shared-memory buffer and writes numbers back. It runs as a child process precisely so
>   that a fault in the C++ costs one analysis instead of your server or your world.
> - **You can refuse it.** Point `sidecarPath` at your own build, or delete the unpacked
>   folder. Without an engine the mod loads and plays normally with analysis disabled and
>   says so.
>
> Source: <https://github.com/rocky59487/block-reality> · Verification record:
> [`evidence/VERIFICATION.md`](https://github.com/rocky59487/block-reality/blob/Main/evidence/VERIFICATION.md)

被打回時的回覆材料，按這個順序給：`docs/DECISIONS.md` 的 **D-027**（為什麼放進 jar、
四條約束）、`mod/core/.../BundledEngine.java`（那四條約束的實作）、
`mod/core/src/test/.../BundledEngineTest.java`（每一條都有測試）、
`scripts/check_bundle.py`（雜湊三方一致的 gate）、`evidence/VERIFICATION.md`（引擎本身的
驗收紀錄）。

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
| 上傳檔案 | `blockreality-0.3a.jar` 單檔即可——引擎在裡面 |

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
> 251 acceptance checks run against textbook closed forms on every build — worst relative
> error 1.2e-14 — and the whole record is public and regenerated by a script, not typed in
> by hand. Where the project cannot back a number it says so: the section catalogue is
> called "solid rectangle" because that is what is actually solved, and two of the five
> demand/capacity modes are documented as ungated.
>
> **What it deliberately does not do yet**
>
> Nothing breaks or falls: this is analysis, not collapse. No construction sequencing, no
> reinforced-concrete composite sections, no load combinations or wind. Everything is
> linear elastic; the buckling factor is the linear onset, an upper bound on the real
> critical load.
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
| Version number | `0.3a` |
| Version name | `Block Reality 0.3a` |
| Loaders / Game versions | Forge / 1.20.1 |
| Release channel | **Beta** |

描述（body）用第 2 節同一份 Markdown，Modrinth 直接吃 Markdown，不必改寫。

Modrinth 對「內含原生執行檔」同樣要透明；第 1 節那段照貼。開 creator monetization
見 `FUNDING.md`。

---

## 4 · 版本說明（release notes，兩個平台共用）

> **0.3a — the engine now travels inside the jar**
>
> Installing is dropping one file into `mods/`; the analysis engine unpacks itself on
> first use, verified against a hash recorded beside it, and nothing is downloaded.
>
> Mechanics fixed since 0.2a:
> - members of different materials that touch now share a node — a timber beam on brick
>   piers used to be three separate structures, and a load put on the beam was silently
>   dropped
> - a beam resting on the ground at both ends is now solved instead of being reported as
>   having nothing holding it up
> - a lone block touching a slab no longer becomes a spurious one-metre member
> - an explosion is noticed; a piston can no longer move a structural block to a position
>   the analysis does not know about
>
> Verification: 251 engine checks, 201 Java tests, worst closed-form relative error
> 1.216e-14, cross-platform determinism 8/8. Both engine binaries are now byte-reproducible.

---

## 5 · 上架後

- 兩個平台的頁面都連回 GitHub 與站台，而不是互相連——單點失效就少一點
- 第一週每天看一次留言與 issue：**回覆速度決定第一批人會不會留下來**
- 有人回報數字不對時，請他用
  [research feedback form](https://github.com/rocky59487/block-reality/issues/new?template=research-feedback.yml)——
  它問的正是要追查所需的最小重現模型
- 社群發文（Reddit 等）等平台審核通過、下載連結穩定後再發，見 `COMMUNITY.md`
