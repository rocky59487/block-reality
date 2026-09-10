# INSTALLED_NATIVE_SERVER — 2026-09-10

The production jar now runs in an ordinary installed Linux Forge dedicated server,
including first-analysis native extraction and persistence/cache reuse after restart.
Its mod description and installation text have been corrected to describe Tectonic2
in-process loading, lazy extraction and the actual cache layout. They no longer promise
the retired FrameCore subprocess or native crash isolation.

Criteria `474fb12` preceded installation, the receipt helper and metadata changes.
Final source `ae6a4eb`; base NCR #121 `8981b394629531425b491bcd8de9de99f1f1e697`.
No engine code, source pin, contract, native bytes or Java code changed in this unit.

| Executed check | Result |
|---|---|
| Official Forge 1.20.1 / 47.4.13 installer | SHA1 matches the independently frozen official-page value; installation exits 0 |
| Installer manifest dependencies | All 63 listed artifacts match size/SHA1 in both installations; 0 absent |
| Control installed jar | 10 native server status gates; restart/readouts/cache comparison PASS |
| Final installed jar | Same 10 native status gates; restart/readouts/cache comparison PASS |
| Runtime loading | All 4 starts: ordinary generated run.sh, source=BUNDLED, expected SO hash/hello |
| Class origins | Actual module classes load from mods/blockreality-0.4.0-dev.jar; no development/probe source set |
| Final jar comparison | Only META-INF/mods.toml differs; all 204 class entries and both native libraries identical to NCR |
| Final Forge build / jar guard | PASS; tests explicitly reused, not rerun; 2 legacy-injection arms compile then are refused |
| Final distribution | Positive bundle gate PASS; second negative run catches all 9 injections |
| Shutdown | Both control and final servers stop normally after first and restart runs; owned ports released |

The ten server checks are the unchanged game_runtime_smoke.py --check-readouts sequence:
cantilever, support removal/refusal and restoration, beam/column/panel plus an unrestrained
structure, undeclared-axis refusal and recovery, session reset, current mixed readout,
native local-critical warning in an incompletely evaluated world, and warning removal.
The installed jar contains no synthetic state-event command; that separate NCR evidence
is not repeated or counted here. Restart compares the native command readouts, installed
jar hash and cache path/size/hash/mtime, after saving and stopping normally. Source and
manager revision identities are not forced to remain equal across a new server session.

The control jar is NCR's `5a93c66bfe5f5a3dd8c1104a6e97fe000a2a0d2a8e66bf602821993d2a4c9b79`,
15,203,560 bytes. It is preserved with its obsolete metadata and remains that unit's artifact.
The final candidate is **15,203,634 bytes**, SHA256
**`7bdfce5d1581bfadf43b769d8aeabc0cec06e7f73fe48cd1c9b1fd6c5fdb7b3c`**.
The distributable ZIP is **15,091,986 bytes**, SHA256
`8718aba5c5e4828a08d32eb6afa2edbac539e328f3a565fb86c6fa0c6aac7979`.
It contains the jar, installation text, candidate limitations, LICENSE/NOTICE, third-party
texts, native provenance and a complete SHA256SUMS inventory. It remains a local candidate.

The engine is the same delivered 42e10f5 build of Tectonic2 1.3.0, contract
`4b11cc738790d32d576ac71ecd1cb57f04d3ed9186a9e43f89492ae13c258801`, with SO
`53aae7156b94c0a761ef5abb2322ad47a708362f417a50ca3f04105d9c50abf1` and DLL
`9761d73586277614a56132ec3492d324ae83c8489068c00c266c3d56f530acea`.
The distribution retains the candidate provenance basis. Formal engine v1.3 is unchanged.

NCR's full Windows509 PASS/12 platformSKIP, Linux520 PASS/1 platformSKIP, 12 packet goldens,
both-platform 48-frame replay/cache/fault/concurrent-JVM results are **reused for identical
executable bytes**, not relabeled as fresh INS tests. Only the TOML text differs in the jar.
The new artifact itself is rebuilt, inspected, bundle-gated and run in installed Forge.
Installer-generated patched Minecraft files are validated by the installer; they are not
counted among the 63 independently rechecked profile dependency artifacts.

First failures remain visible:

* WSL urllib receives HTTP403 while fetching the official installer, before installation.
  Windows curl retrieves the same official URL and matches frozen SHA1
  `790949ee0cb4671175a806befa370d69008b4b4e`. No alternate unsigned artifact is substituted.
* The first final-bundle negative run starts the parent Python in UTF-8 mode but its child
  still emits CP950. It catches three injections, then decoding the next diagnostic fails.
  That run is FAIL, not nine passes. The second invocation sets PYTHONUTF8=1 and
  PYTHONIOENCODING=utf-8 for parent and child; the unchanged nine injections all reject.

Raw files and original hashes are listed in raw-sources.json. Large class-loading logs are
compressed losslessly; their uncompressed hashes remain recorded. The actual launch has no
BR_ENGINE or Java property/environment injection, uses a fresh cache and one mod in mods/.
The original Windows profile/jar/security dialog and CM server are untouched. All activity
uses new owned offline-test worlds bound to loopback 25596/25597.

This establishes an **installed Linux dedicated-server** candidate. It does not accept
Windows installed-client CM/N25, a real installed client, full input/material directions,
FPS, general config-file notification reliability or engine-driven fracture/crushing/
rigid-body motion. No public release, v1 claim or default-branch merge is made.

Official references used for the fixed installer and installation shape:
[Forge downloads](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.20.1.html)
and [Forge build/install documentation](https://docs.minecraftforge.net/en/1.20.1/gettingstarted/).
The fixed test target is not a claim to be the newest Forge release.
