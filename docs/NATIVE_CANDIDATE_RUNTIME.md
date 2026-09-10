# NATIVE_CANDIDATE_RUNTIME (NCR) — module-only integration, frozen first

2026-09-10. Base HUD #120 `db280caa2cf917c230c847831fd9819f1643955f`.
Consume module #119 `bbe9ccdda0a0a0dae545208aa4c7e7494a25b497` and the delivered
engine #40 candidate; do not modify, rebuild, merge or release engine sources.
The older #119 consumer had not yet integrated GAME_RUNTIME or the current HUD,
registry/identity, native-only source boundary and save improvements. Preserve all
those changes while integrating its staging/check scripts and source pin.

## Frozen identity and isolation

Expected source `42e10f5f7af166788588afcd2fb2fd97101d16dc`, version1.3.0,
buildSha42e10f5, contract `4b11cc738790d32d576ac71ecd1cb57f04d3ed9186a9e43f89492ae13c258801`.
Root SHA256SUMS `f587d8092e0798da4d1f9c8d0e7a4d1b806d64fbf5999d749622757181231cd5`.
Windows DLL `9761d73586277614a56132ec3492d324ae83c8489068c00c266c3d56f530acea`,31769600B;
Linux SO `53aae7156b94c0a761ef5abb2322ad47a708362f417a50ca3f04105d9c50abf1`,28500368B.
These expectations come from the delivery record, not from querying a candidate
and treating its self-description as the expected identity.

Read artifacts from the engine checkout's `.agent-work/mc66a-native/assets` only;
copy/stage into new module-owned output directories. Preserve originals and first
failures. Compare canonical Git contract bytes with the source/SDK; a worktree
newline discrepancy is a recorded setup failure, not permission to weaken a hash.
Do not touch the installed Windows profile/jar, its permission dialog or original
CM server/world. New game checks use owned isolated profiles/worlds/loopback ports.

## Gates

* NCR-1: source, SDK/contract, dependency/license receipts, ZIPs, root inventory and
  both libraries pass the existing provenance/staging gates. Explicit candidate
  basis survives into the jar. Source-chain/bundle negative arms still reject;
  retain their original identities and distinction between compiled and behavioural
  failures. No executable or legacy physics/probe classes enter the production jar.
* NCR-2: full current Windows core/Forge suites run with the delivered Windows DLL;
  full Linux core/Forge suites run with the delivered SO. Native-dependent tests
  may not skip. Enumerate actual platform-specific skips separately. Keep the12
  packet goldens and existing source/bytecode guards. Record new failures before
  determining whether they belong to the module or require engine-owner follow-up.
* NCR-3: build the current module as one candidate jar carrying both delivered
  libraries, provenance and licenses. On both platforms run the existing jar-only
  extraction/cache/permissions/identity/concurrent-JVM gate, all48 C5/C6/C8/C10
  frames (f64/f32), three repeats. Direct-vs-extracted byte equality is a transport
  check, not independent physical correctness or cross-platform byte equality.
* NCR-4: exercise current Forge GAME_RUNTIME with the delivered library and actual
  native bootstrapping, supported beam/column/panel, refusal and recovery. Capture
  actual client readouts through the established isolated renderer. Native-critical
  and world-evaluation flags must remain independent; Java must not reconstruct
  factors, damage, motion or verdicts. Record the real loading source. Explicit
  path/dev-classpath runs cannot qualify installed-jar automatic extraction.
* NCR-5: archive source identities, library/jar hashes, raw outputs/XML/captures,
  first failures and exact-head CI with its skipped steps. Integrate #119's module
  changes without regressing the current source boundary, saved data, UI or tests.

This is a local candidate qualification. Do not replace formal v1.3, publish an
unqualified v1 or merge engine work. Installed Windows CM/N25, independent-region
scheduling, construction transactions, directional materials/interaction, FPS and
engine-driven lifecycle/fracture/crushing/rolling remain separate requirements.
The two repositories' default-branch contract alignment must remain intact.
