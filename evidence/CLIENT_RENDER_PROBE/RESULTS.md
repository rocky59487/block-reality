# CLIENT_RENDER_PROBE — actual supplemental baseline

Criteria `034902a`; production module inherited unchanged from #117 `8f51589`.
Client/server probe baseline `0afdecf`, frozen coordinator `32d9a7b`, corrected
client observation `7c57c9d`. Only the probe changed between attempts; production
rendering, delivery, resources and engine remained unchanged.

## Execution

Java17 / Forge1.20.1-47.4.13 development client, Ubuntu22.04 / Xvfb, llvmpipe
(LLVM15.0.7, 256bits), Mesa23.2.1 OpenGL4.5. Software rendering is not a hardware
or FPS qualification. Audio device initialization failed and remains in the logs.
The account-free synthetic BRRenderProbe client connected only to loopback25594;
fixtures used RCON25595 in the separate `render-probe` world. The original Windows
installed jar, permissions, profile and CM server/world were untouched.

Consumed engine #37 `95a03e82bbc50c53eac97b5289b79d8e640f8075`, library SHA256
`bbf38ff3d4633d4c9e0e7f42fb87e1e3183a6f9d7418ed88432b05392b36c121`.
No engine edits or builds. Both owned rendering processes exited cleanly.

| Gate | Observed result |
| --- | --- |
| CRP-1 | PASS, supplemental: cached native revision44 arrived on the real connection without another edit/resolve; 2 members, 12 shells, max D/C0.7597396436387274 agree with the pre-join server record |
| CRP-2 | Baseline collected: beam, column, panel and all9 products × X/Y/Z/undeclared. Existing direction/section rendering fails visually: states remain identical cubes |
| CRP-3 | 16 actual framebuffer PNGs, en_us/zh_tw ×1280x720/1920x1080 at GUI scale2. Receipts and45 coordinator assertions pass. Visual defects remain below |
| CRP-4 | Probe compile and ordinary Forge build/jar guard pass; production byte-for-byte jar unchanged, 201 classes. Existing suites reused unchanged; no claim of new test execution |

Ordinary jar SHA256
`b0a3f9447cfcc3c2f22659c0c6e0705fe24aa57b9591c48d10e300f6d8adfd35`.
The jar guard's two compiled mutation arms still reject forbidden legacy content.
Production has no probe classes. The previous #117 full suite and12 packet goldens
remain the unchanged-source validation: core384 PASS/28 SKIP, Forge108 PASS/1 SKIP
on Windows, full Linux Forge109 PASS/0 SKIP. #117 final-head CI receipt is
`../SAVE_COMPRESSION/ci-8f51589.json`; native packaging still has15 skipped steps.

## Visual findings and limitations

The English1280 catalogue refusal is cut off at the right edge. Text/section
labels sit directly over pale sky, clouds and structures with inconsistent
contrast. Traditional Chinese loads, but raw refusal detail remains English.
The existing world-buckling-incomplete warning remains visible alongside D/C;
it must survive any layout change. Adding the undeclared catalogue produces
actual MODEL_REFUSED revision116 and clears the old coloured result overlay.
All9 material textures load; this does not prove item inventory rendering or
placement input. Vanilla tutorial/chat toasts are visible and retained.

The first attempt failed its bootstrap timeout on AccessibilityOnboardingScreen.
The second connected but failed because the probe confused a null notice with
absence of an accepted result. Both raw attempts remain. The third reports the
actual notice and hasData separately, passes45 checks and exits after109.396s.
This was a probe correction, not a fabricated client result or production fix.
See FIRST_CLIENT_ATTEMPT.md and setup/SETUP.md for the setup failures.

Screenshots and SHA256 manifest are in baseline/screenshots and
baseline/screenshots.json; raw commands, receipts and logs accompany them.
CRP cannot pass installed Windows CM-1..6/N25, true placement/rotation interaction,
travel/respawn/reconnect, native auto-extraction, latest offline packaging,
frame-time goals or engine-driven collapse/crushing/rolling.
