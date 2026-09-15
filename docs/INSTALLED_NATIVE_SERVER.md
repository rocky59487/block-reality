# INSTALLED_NATIVE_SERVER (INS) — frozen before installation and metadata edits

2026-09-10. Base #121 `8981b394629531425b491bcd8de9de99f1f1e697`.
Module-only follow-up to NCR. The native resources in a development classpath have
already run; this unit must exercise the production reobfuscated jar in a normal
Forge installation, with no project classes or test probes added to the launch.

## Identity and isolation

Control jar: NCR 15,203,560B,
SHA256 `5a93c66bfe5f5a3dd8c1104a6e97fe000a2a0d2a8e66bf602821993d2a4c9b79`.
Engine source/version/build/contract and both platform hashes remain those in
NATIVE_CANDIDATE_RUNTIME.md. No engine build, edit, merge or publication.
Install Minecraft1.20.1 / Forge47.4.13 (the current build target) into a new owned
Linux directory. Official installer SHA1 from the Forge download page:
`790949ee0cb4671175a806befa370d69008b4b4e`; record SHA256 after download too.
Use a new runtime-smoke world on loopback25596/RCON25597 after checking ports.
Original Windows profile/security dialog/jar and original CM server stay untouched.

## Gates

* INS-1: official installer and its manifest dependencies are verified, and the
  ordinary generated run script starts a dedicated Forge server with only the
  candidate in mods/. Record Java, OS, installer/jar hashes and actual launch
  command. No Gradle runServer, sourceSet or optional probe in this launch.
* INS-2: clear BR_ENGINE/br.engine/config path overrides and start with no owned
  native cache. The first required analysis must extract from this jar and log
  source=BUNDLED with the expected SO hash; handshake/vocabulary/beam/column/panel,
  support removal/refusal/recovery and local-critical/world-incomplete readouts
  must pass the existing game_runtime_smoke.py --check-readouts sequence. Run the
  control before changing product metadata; preserve all failures. No state-event
  probe can be injected into the production jar to make this pass.
* INS-3: stop normally and restart the same installed jar/world. Verify persistence,
  automatic cache reuse and native recovery with the same installed artifact.
  Record the actual cache/library bytes and mod inventory; do not infer use from
  a jar-only probe. No install-time library download by the mod is allowed.
* INS-4: fix observed stale product information: mods.toml still claims FrameCore,
  a separate process and crash isolation; the library installation text states an
  outdated cache layout/first-launch extraction. Describe actual in-process
  Tectonic2 and lazy first-analysis extraction, candidate provenance and current
  supported platform scope without claiming fracture/physics/FPS completion.
  Preserve the control metadata and its hash. The final jar may change only in
  metadata/resources; verify the production classes and both native bytes are
  unchanged, retain license/provenance/zero-executable guards, and repeat INS-2/3
  with the final installed jar. Full code suites from NCR may be reused only if
  executable sources/classes are unchanged; explicitly state that reuse.
* INS-5: archive original outputs, exact sources/hashes and failures, with exact-head
  CI and native skip scope. No formal release or Main merge. This establishes a
  Linux installed dedicated-server result only; Windows installed-client CM/N25,
  client visuals/input, FPS and v1 remain separate requirements.

Official installation/build references: https://docs.minecraftforge.net/en/1.20.1/gettingstarted/
and https://files.minecraftforge.net/net/minecraftforge/forge/index_1.20.1.html.
The fixed Forge target is not a claim to be the newest available Forge release.
