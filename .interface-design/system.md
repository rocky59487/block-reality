# Block Reality instrument readouts

The player is actively building while holding Stress Glasses. The world remains the main
canvas; the corner instrument identifies the current lens, result age, structural warnings,
world evaluation state and the focused section. Keep these attached to the actual result revision.

Use Minecraft's font and GuiGraphics. The bounded instrument uses6px edge/inset padding,
12px wrapped rows and4px group gaps at GUI scale. Keep a flat charcoal backplate (#141B22,
235/255 opacity), with a maximum304px width and44% of the GUI width, leaving44px below.
Height follows content. Bold lens and max D/C identify the instrument and its main metric;
native warning/readout colours carry the existing semantics. No web fonts, rounded cards
or decorative shadows. Frequent placement/readout changes remain immediate.

Measure every paragraph with Minecraft Font.split. Section labels have their own wrapped
column beside a96×56 sample plot; the neutral line comes from the existing sample display.
When the complete detail cannot fit, retain the leading readouts and visibly name the
omission with “More details: /br status”; never silently crop or reduce the font size.

Existing semantic colors: critical #FF6B6B, incomplete/unevaluated #C8A24A, mechanism #FFCC00,
detail #AAAAAA, primary #FFFFFF, section focus #9FE8FF. Text must name the state; color alone
never communicates a verdict. Preserve the native critical/overloaded flags independently of
the displayed factors or rounded values.

The five recurring anchors are lens name, revision/stale notice, structural warning, separate
world assessment and focused section. Local buckling onset remains visible beside a refused
world factor; an absent factor is never displayed as zero. English and Traditional Chinese
must carry the same keys and placeholders.

Actual Linux development-client baselines and HUD candidates are retained in
evidence/CLIENT_RENDER_PROBE and evidence/HUD_READABILITY. This supplements the installed
Windows acceptance, which remains open. Preserve first failures, including the initial
large-GUI overlap with a vanilla login toast; no tutorial/security notice is dismissed to
make a screenshot look better. Directional materials, user input and N25 remain separate gates.
