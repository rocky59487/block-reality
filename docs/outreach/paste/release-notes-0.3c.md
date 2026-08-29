<!--
  貼上用：CurseForge 與 Modrinth 的 version changelog 欄位，兩邊同一份。
  來源 docs/outreach/LISTING.md §4。版本：v0.3c（2026-08-29）
-->

**0.3c — big structures stop stalling on buckling, and a limit gets named out loud**

Installing is still dropping one file into `mods/`; the analysis engine is inside it,
unpacks itself on first use against a hash recorded beside it, and downloads nothing.

**Buckling now has a size policy.** The eigenvalue solve grows roughly as the cube of the
model — 0.5 s at 200 nodes, 8.6 s at 500 and 73 s at 1000 on the reference laptop — so a
large build used to sit there. Above `bucklingBlockLimit` blocks (300 by default, `0` to
switch buckling off entirely) it is skipped, and the HUD now says **"buckling not
evaluated (structure size)"** rather than showing a blank that reads as *stable*. Small
models are unchanged.

**A limit we found while testing this release, stated rather than buried.** A straight run
of blocks is solved as one beam element unless a load or a junction forces an interior
node. Where the axial force is nearly uniform that is accurate — 0.5% from Euler for a
19 m cantilever under a top load. Where it varies it is not: the same column buckling
under **its own weight** reports 3.14 where the exact answer is 9.89, 68% low, reaching
9.15 once 19 elements exist. So the factor is conservative in that regime, and visibly
mesh-dependent — a one-newton test load at mid-height raises it by 68%. Earlier releases
called this number "an upper bound on the real critical load"; that sentence is wrong for
this case and has been removed everywhere. Both measurements ship as scripts you can run
against the engine you installed.

Also in this release: macOS and ARM players now get a plain sentence explaining that no
engine exists for their platform instead of a raw log line; the installer no longer claims
"no Forge found" inside a CurseForge instance; an explosion handler now runs last so
protection mods get to amend the block list first; a stale analysis probe can no longer
drop a player's test load; and an over-long dimension id can no longer throw while a
result is being broadcast.

Verification: 282 engine checks, 215 Java tests (179 pure-Java, 36 Forge-side), 41
closed-form comparisons with worst relative error 1.216e-14, cross-platform determinism
8/8. The two engine binaries are byte-identical to the ones shipped in 0.3b.
