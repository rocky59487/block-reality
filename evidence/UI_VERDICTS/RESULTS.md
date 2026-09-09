# UI_VERDICTS — 2026-09-10

The shared buckling readout now preserves an engine-supplied local-critical warning even when
the world has no factor. HUD and commands consume the same rows. The command reports result
revision/current/stale, counts solved islands excluding singular islands, and colors shell
capacity using the supplied overloaded flag.
The client formats these rows when a result arrives and clears them with world state, rather
than rebuilding formatted messages every frame. The final Forge build passed after this change;
no measured FPS or allocation claim is made.

| Check | Executed result |
|---|---|
| Windows core full check | 360 registered / 333 PASS / 27 SKIP |
| Windows Forge full build | 85 registered / 84 PASS / 1 SKIP |
| Total | 445 registered / 417 PASS / 28 SKIP / 0 FAIL |
| Shared readout cases | 3 PASS, including opposite factor/flag combinations and all world states |
| HIDE_LOCAL / RECOMPUTE_FLAG | each compiled and yielded its one named assertion FAIL |
| True native server text | ten smoke checks PASS, plus members/section CURRENT assertions |

The unchanged frozen 50-cell steel 100x200 column, under native self-weight, produced the
local-critical warning alongside world-not-eligible. The result had 3 members, 12 facets,
5 total islands/1 unrestrained, and no displayed world factor. Removing the column cleared
the warning. The original mixed scene correctly reports 3 solved/1 unrestrained. Ten status
responses explicitly labelled an old result STALE; result revision and world revision remain
separate. The source library is the same delivered #37 Linux dev library as GAME_RUNTIME,
with actual source/hash/contract logged. Server stopped normally; no engine changes.

First Forge run failed `LangKeysTest.noDeadKeys` because two retired buckling translations
remained. Its XML/log are retained. Both obsolete keys were removed, and the full language,
placeholder and build checks passed. No criteria were weakened.

`counts.json` is harvested from JUnit XML. `receipts.json` identifies original hashes and log
excerpts. RCON commands/replies are retained; credentials and server configuration are excluded.
The native-dependent and platform-dependent skips are not passes. The parent #108 CI run
34381927788 had four successful jobs but 15 native build/JNA/stage/jar steps skipped because
TECTONIC2_TOKEN was absent; it is not new native packaging evidence.

This verifies text/state consumption and real dedicated-server integration. It does **not**
verify the Minecraft client window, visual layout, textures, interaction, N25 or FPS. No new
physics, native library, interface pin or release was introduced. Empty-model/status packet
identity and persistence remain separate pending work; the v1 goal remains active.
