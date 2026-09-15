# CLIENT_MATERIALS — declaration information checkpoint, 2026-09-10

Build-log excerpts and hashes point to the complete retained local logs; counts come from JUnit XML.

CM-7 is implemented: product information reads the same SI catalogue sent to native registration.
Rectangular width/depth order, round diameter, panel thickness and solid-cell roles remain distinct.
Concrete/brick legacy section-like tokens never become fictitious member dimensions. English and
Traditional Chinese distinguish placement axis from member direction and panel normal.
This adds no section-property calculation, mechanics, engine changes, model geometry or blockstate changes.

| Executed check | Result |
|---|---|
| Windows core check with legacy compatibility executable | 373 registered / 346 PASS / 27 SKIP |
| Windows Forge build | 95 registered / 94 PASS / 1 SKIP |
| Combined Java | 468 registered / 440 PASS / 28 SKIP / 0 FAIL |
| New declaration and tooltip tests | 6 core + 3 Forge PASS |
| Documentation counts | 36 agree; compatibility sidecar suite ran 330 checks |
| Isolated Linux native server baseline, before client login | 10 status/readout checks PASS on parent `139a271` |
| Actual packaged client | Parent jar loaded in installed MC 1.20.1 / Forge 47.4.10; multiplayer list reached |
| CM-1..6 world, delivery, model and layout gates | NOT PASSED; no real player joined the world |

The catalogue tests change dimensions while retaining the original product token, reverse a
non-square section, change aliased panel thickness, remove declarations, duplicate names and
inject invalid roles, shapes and dimensions. A separate class loader removes the packaged
catalogue: both repeated tooltip calls return unavailable, both native declaration requests
throw an ordinary IllegalStateException, and the product binding remains available.

The first missing-resource test **failed** with ExceptionInInitializerError from the lazy
catalogue initializer. The resource holder now retains an ordinary I/O failure rather than
throwing during class initialization. The original failing XML/log are retained. No broad
Error catch or synthetic dimensions hide the failure.

The first full core run omitted `-Dbr.sidecar`, so it registered 372 tests with 317 PASS /
55 SKIP (the new missing-resource test had not yet been added). Together with Forge it was
411 PASS / 56 SKIP, not the anticipated 439 / 28. The count assertion caught that mismatch;
the first documentation check also rejected seven stale 459-count claims. Those receipts are
preserved. The final core run supplies the existing legacy compatibility executable. This is
test-only coverage, not proof of a Windows tectonic2 native library.

Reproduce the final checks from their respective directories:

```powershell
# Java 17; from mod/
.\gradlew.bat check '-Dbr.sidecar=C:\Users\wmc02\Desktop\block-reality\dist\br-sidecar.exe' --console=plain
# From forge/
.\gradlew.bat build --console=plain
# From repository root
python scripts/check_docs.py dist/br-sidecar.exe
```

The real client uses the installed "Block Reality N25" profile and a 1280×720 launch setting.
Windowed capture was required: the initial fullscreen capture showed stale Forge loading pixels
while the render thread had already reached onboarding. After F11 the actual onboarding,
main menu and multiplayer list were observed. A subsequent Direct Connection click was rejected
because Windows Security covered the target. A fresh capture confirmed the firewall permission
dialog for OpenJDK Platform binary. The user has been asked to handle it; no security setting
or permission was changed by the agent. No account screen or raw account-bearing client log is
committed. No GUI scale, in-world HUD, texture or screenshot acceptance is claimed.

The parent baseline jar installed in `.minecraft/mods` is 412,925 bytes, SHA256
`ad0b906d31c7fae9f549fd0f5756df22d1084f238ea5eba1f8d2e7befb8d52f9`.
The previous installed jar was backed up before replacement. The new material-information
development jar is separately hashed in `receipts.json`; it has **not** replaced the live
baseline client. Both jars exclude executables, native libraries and the opt-in event probe,
and contain LICENSE/NOTICE. Neither is a qualified self-contained release jar.

The same-host isolated Linux server runs the previously delivered #37 native development
library, SHA256 `bbf38ff3d4633d4c9e0e7f42fb87e1e3183a6f9d7418ed88432b05392b36c121`.
Its loopback game port is reachable from Windows. The baseline beam/column/slab and mixed-world
readouts were prepared before any real client login; `client-materials-server-baseline.json`
is the command receipt. It cannot demonstrate login bootstrap or CM-2.

Upstream read-only refresh still reports tectonic2 formal v1.3 at `c776ebc6d7755734e0ca25509a19aba3423cf42b`,
with #37 and #39 open. No engine source, pin or contract changed. Real socket travel/respawn,
material models and interaction, native release packaging, persistent registry, legacy Java
postprocessing retirement and lifecycle/rigid-body consumption remain work toward v1.
