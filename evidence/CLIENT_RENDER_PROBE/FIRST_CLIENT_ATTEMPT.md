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
