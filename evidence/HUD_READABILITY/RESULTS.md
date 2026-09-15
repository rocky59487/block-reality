# HUD_READABILITY — supplemental candidate qualified in observed scenes

Criteria `59e7809`, original production `ef77297`, final width correction
`299750b`. The original CRP baseline `dc94b2b` remains unchanged. Only StressHud,
the client-only HudPanel and the two language files change production behaviour.
No engine, solver input, packet or client-state change; no new physical calculation.

## Actual result

Long messages wrap with Minecraft's font inside a flat contrast surface. Native
buckling readouts precede normal metrics; D/C and lens receive typographic emphasis.
Labels wrap beside the existing native-sample plot. At a large GUI scale, content
that does not fit produces an explicit details footer instead of cropped text.

| Gate | Evidence and result |
| --- | --- |
| HR-1 | PASS, supplemental: same16 real scenes/languages/resolutions, GUI scale2; all45 coordinator assertions pass. Every observed receipt field matches CRP except PNG byte size. The complete English1280 catalogue refusal is visible on3 lines |
| HR-2 | PASS for observed states: max D/C0.760, 2 members/12 shells, world-buckling-incomplete warning, beam ±9.24MPa and column −0.31MPa labels remain readable and consistent. Actual MODEL_REFUSED clears coloured results |
| HR-3 | PASS for observed bounds: final backplate stays within44%/304 GUI pixels and above the44px bottom reserve. Two extra actual1280/scale3 images show the full warning and explicit omission footer, clearing the visible vanilla login toast |
| HR-4 | Ordinary Forge build, all109 registered tests (108 PASS/1 native SKIP),12 packet goldens and compiled jar guard mutation arms pass. Default jar excludes probes and legacy processing |

Final jar:204 classes,463832 bytes, SHA256
`8d8fe0f86a0d12ee41154e5a7b79a50ee64919a681fefc1d1bae4aeb8eacc0ce`.
The unchanged core's earlier384 PASS/28 SKIP remains its evidence, not a claimed
rerun here. Total current Windows registration remains521 /492 PASS /29 SKIP.
No Linux JUnit rerun is claimed; Linux ran actual client/server rendering with the
delivered native library. This default development jar does not bundle that library.

Final actual client sessions exited cleanly after142.089s and58.804s. The owned
server was stopped afterwards. The same #37 library was consumed:
`bbf38ff3d4633d4c9e0e7f42fb87e1e3183a6f9d7418ed88432b05392b36c121`, engine
`95a03e82bbc50c53eac97b5289b79d8e640f8075`, contract `4b11cc738790…`.
Normal scenes use revision44/result44; undeclared catalogue revision116 refuses;
restoring the supported fixture yields revision152 for the extra GUI check.

## Retained failures and limits

The incomplete first edit failed LangKeysTest for the missing footer translation;
first-build contains the failure/log/XML. The first full visual candidate passes
its16 original scenes but its scale3 English warning is partly covered by a vanilla
toast. Those18 images remain in candidate/ and overflow/, with FIRST_VISUAL_REVIEW.
The final candidate reduces width to44% without changing the frozen maximum,
font, native values or fixture. Both final large-GUI images retain the toast and
show a clear gap; the toast was not hidden or dismissed.

The final overflow launcher initially referenced a nonexistent checkout because a
setup string replacement changed its cwd. No child started; original launcher,
empty log and launcher-first-error.txt remain. A separate corrected launcher/log
started successfully; the coordinator completed without replacing its first record.

Final screenshots, hashes, real receipts and raw logs are in final/ and
final-overflow/. final/comparison.json records all16 complete receipt comparisons;
final-functional contains the final Windows test XML and jar/build checks.

These images do not qualify unobserved stale/local-critical/other runtime states,
arbitrary third-party toasts, item/placement interaction, material direction models,
installed Windows CM/N25, hardware FPS or newest offline native packaging. The
whole v1 programme, engine-driven fracture/crushing/rolling and client travel tests
remain open. Original Windows profile/jar/permissions and CM server are untouched.
