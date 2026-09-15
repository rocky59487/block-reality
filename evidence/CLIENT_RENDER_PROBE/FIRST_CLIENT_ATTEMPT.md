# First client attempt — retained failure

The first real Linux development client created an OpenGL framebuffer but remained
on Minecraft's `AccessibilityOnboardingScreen`. The coordinator's original
60-second bootstrap gate failed; no connection or screenshot passed. The client
was then stopped through its probe-owned exit command (exit 0, 325.398 seconds).
The missing OpenAL device was logged; audio is unavailable in this environment.

The unchanged production source has not yet supplied a rendered baseline. For a
second attempt only the isolated test profile will set `onboardAccessibility:false`,
the same option written by the vanilla onboarding screen's Continue handler.
This is ordinary test-profile setup, not a security/authentication permission.
Use a new output directory and keep the original logs and failed capture record.
The existing native server fixture remains unchanged, so cached bootstrap still
requires the pre-join result with no structure edit or resolve.

The second client connected and observed revision 44/result 44, 2 members,
12 shells, max D/C 0.7597396436387274 and stale=false. Its coordinator still failed:
the probe incorrectly expected `ClientStressState.notice()` to remain RESULT,
but production deliberately clears that notice after accepting a summary. Fix
only the probe to report RESULT when the actual notice is null **and** hasData is
true; record both underlying fields. No production delivery/state is changed.
Preserve the second raw state and failed events before another client attempt.
