# HUD_READABILITY (HR) — freeze before display changes

2026-09-10, module only, base CRP `dc94b2b` (#118). The actual supplemental
baseline shows English1280 refusal clipping and weak contrast over sky/structures.
Keep all16 original images, actual payloads and the failed first probe attempts.

## Display scope and direction

The player is building while reading a structural instrument. Keep the world as
the main canvas and the labelled native section as the distinguishing instrument.
Domain: placement, declared section, supports, demand, buckling, current revision.
Colour world: steel grey, concrete grey, timber brown, brick red, sky blue, warning
amber. Use the existing semantic text colours; add a quiet charcoal instrument
backplate so the sky cannot wash out text. Avoid a dashboard/card redesign,
oversized metrics or animated placement feedback. No control/input redesign here.

Hierarchy: lens, age/coverage/structural warnings, world metrics, focused section.
Critical and incomplete readouts precede supporting numerical detail. Use the
Minecraft font at its existing size, 12px row rhythm, 4px section gaps and6px outer
edge; text wrapping determines actual height. A flat content-sized backplate,
no decorative shadows or rounded cards, follows .interface-design/system.md.
Width stays below48% of the GUI and max304px. Leave44px at the bottom. Section
labels wrap inside their own measured area without changing graph samples.

## Gates

* HR-1: Repeat the same actual CRP scenes/camera poses, native fixture, both
  languages and resolutions at GUI scale2, using the unchanged coordinator.
  Retain all16 candidate PNGs/receipts and all45 revision/payload/framebuffer checks.
  The catalogue refusal's entire original message must be visible within bounds.
* HR-2: Native D/C, counts, separate world buckling warning, focused member/section
  labels and signed face values retain their meaning. Warning colours never
  re-derive native verdicts. No change to state delivery, payloads or physics.
  Review actual baseline/candidate screenshots, especially the1280 beam/column.
* HR-3: Size text with Minecraft font splitting. No overlap between wrapped text,
  diagram or following rows; backplate fits width/height budgets above. If extreme
  content cannot fit, display an explicit omission/footer with the existing
  `/br status` command, rather than silently clipping or shrinking the type.
  Real screenshots qualify only states actually encountered; synthetic fixtures
  cannot stand in for unobserved stale/local-critical/other runtime states.
* HR-4: Ordinary Forge build/tests, jar/no-legacy guards and12 existing packet
  goldens pass. Default jar excludes probes; all client classes remain client-only.
  No engine code/build, native packaging claim or installed Windows profile change.

This is a supplemental HUD candidate, not installed-client CM-6/N25 acceptance,
full material/interaction completion or an FPS/performance claim. Packet budgets,
native authority and prior failure records remain unchanged.
