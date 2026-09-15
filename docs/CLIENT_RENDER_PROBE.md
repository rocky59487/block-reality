# CLIENT_RENDER_PROBE (CRP) — supplemental client baseline, frozen first

2026-09-10. Module only, based on #117 `8f51589`. The installed Windows
1.20.1/Forge47.4.10 baseline remains untouched behind a security dialog. Build a
separate Linux development-client renderer to obtain actual game images and
exercise module delivery while that required installed-client gate remains open.

## Environment and isolation

Use a fresh WSL/ext4 checkout, Java17, Forge1.20.1/47.4.13 development client,
Xvfb/software OpenGL and a fresh dedicated test server/world. Bind game/RCON to
loopback ports25594/25595 only after checking they are free. Use the synthetic
name BRRenderProbe and test-only offline identity. Record source, versions,
native-library hash, GL renderer, physical framebuffer size and GUI scale.
Do not use account credentials, change Windows permissions, connect to public
servers, replace the installed Windows jar, or touch the existing CM world/server.
Only the already delivered #37 native library is consumed; no engine builds/edits.

The opt-in client probe may join this test server, change its own test-profile
language/window/scan settings, capture Minecraft's framebuffer, and exit. Server
fixtures and camera positions use RCON on this isolated world. A bounded control
file permits only fixed capture settings, not arbitrary code or commands. Probe
classes are absent from normal source sets/jars and forbidden by the jar guard.
Retain first setup/runtime failures. Bound each client session to ten minutes;
dependency download/setup time is separate. Clean up only processes started here.

## Gates and scope

* CRP-1: real Forge client and dedicated server connect; only actual decoded server
  updates may populate ClientStressState. No synthetic packet injection or direct
  replacement of verdicts, samples or revisions. Native results exist before join;
  bootstrap after join requires no further structure edit or resolve.
* CRP-2: capture baseline images of supported beam/column/panel scenes and all nine
  products with X/Y/Z/undeclared states. Record observed omissions or identical
  cube models as existing failures, not as successful direction rendering.
* CRP-3: collect English/Traditional Chinese at1280x720 and1920x1080 with recorded
  GUI scale, actual renderer/framebuffer and matching server/client revision.
  Resource/connection/refusal errors must be visible and retained. Inspect real
  screenshots before any material or HUD layout edit.
* CRP-4: ordinary Forge build/jar, packet goldens and existing tests stay valid;
  no client-only probe class may load on the normal server or enter the default jar.

These are supplemental development-client rendering/delivery checks. They cannot
pass CM-1..6/N25's installed Windows jar/profile, actual user interaction, or native
auto-extraction requirements. Software rendering is not a hardware/FPS benchmark.
Travel/respawn/reconnect, placement input, latest native packaging and lifecycle,
collapse/crushing/rolling remain separately required.

The CM workflow previously required the installed-client baseline before resource
edits. After a real CRP baseline, source-level material/HUD candidates may proceed,
but their conclusion is explicitly downgraded to a supplemental candidate until
the unchanged CM gates are completed. Preserve the original installed jar for its
own before/after comparison; never substitute these images for Windows evidence.
