# MATERIAL_GEOMETRY (MG) — frozen before implementation

2026-09-10. Base installed-native-server #122 `fde86ccc0d92c31437a556786f747934760fff75`.
Module-only. Native source remains42e10f5 and the qualified Windows/Linux libraries
remain unchanged. The real Linux NCR/HUD captures are the supplemental before-state:
all9 product models are full cubes and scans paint cell-sized surfaces. The original
Windows CM/N25 profile/jar/security dialog and CM server remain untouched.

## Working visual brief

The player is selecting a structural product, placing it against a face and inspecting
its result in the same world. A declared rectangular member must look like its section,
and its long axis must be visible before analysis. Keep Minecraft's pixel atlas and
the existing HUD. The visual vocabulary is a cut section, longitudinal grain, material
face, declared placement axis, support contact and an explicit undeclared marker.
Steel silver/charcoal, concrete grey, brick red, oak tan and warning ochre come from
the material world. The signature is the same declared section and axis continuing
through item, placed silhouette, target outline and native-sample colour surface.
Use distinct end grain/cut faces and real rectangular proportions; a recoloured full
cube or an invented I-section would contradict the actual solid rectangular product.

Geometry dimensions read the same GameVocabulary/ProductGeometry SI declarations as
the existing tooltip/input path. Steel b×h are200×400,150×300,100×200 mm; timber140×240.
For axisX: depthY,widthZ; axisY: depthX,widthZ; axisZ: depthY,widthX. Longitudinal cell
length remains1m. No section-property calculation or engineering decision is added.
Concrete/brick monoliths remain1m³. Panel declaration axis is not a physical normal:
retain an honest material-cell representation and distinguish its declared axis by
surface marks; do not rotate a thin plate using that axis. Fully resolved panel form
and damage/motion remain separate engine/input-dependent requirements, not MG claims.

## Gates

* MG-1: every registered product's item/block model loads from the real atlas, without
  missing sprites. All9 products distinguish X/Y/Z/undeclared. Four frame products use
  the declared unequal dimensions; steel sizes differ in silhouette, not only colour.
  Undeclared/missing presentation data must remain visibly unresolved, with no invented
  structural axis or dimensions. Resource packs may change textures, not engine input.
* MG-2: one shared presentation geometry feeds the block target/collision shape and the
  model dimensions. Test non-square X/Y/Z bounds and changed declaration dimensions,
  unknown/invalid data and monolith/panel semantics. Actual client baked-quad bounds and
  real block shapes must agree with independently fixed expected extents. Model rotation
  or swapped depth/width must be caught; JSON existence alone cannot pass the gate.
* MG-3: member scan surfaces follow the presented bounds; neighbouring material cells
  cannot hide an exposed narrow face. Sample coordinates map to separate depth and width
  half-extents without changing the native sample values, side identities, flags or
  discontinuities. Existing equal-magnification behaviour remains available. Retain12
  packet goldens and native-only source/bytecode guards. Do not infer new engineering
  fields or verdicts, change native requests, or introduce Java motion/forces/damage.
* MG-4: rerun the original16 NCR/HR real-client scenes and45 coordinator assertions with
  the same delivered native library. Compare all native result/readout fields with the
  prior fixture. Add clear unscanned catalogue/close-up captures to inspect shapes,
  axes, end faces and missing-texture behaviour; capture actual item models too.
  Record en/zh and1280/1920 where HUD is present. Retain before images and first failures.
* MG-5: use actual vanilla client placement/use interaction over the test socket for
  face-based axis declaration and empty-hand sneak axis cycling. Observe the actual
  client hit/shape and server blockstate/revision; RCON only prepares/observes fixtures.
  An opt-in probe may drive the vanilla client interaction entry point, but must report
  that automation scope and cannot inject accepted results or model state. This remains
  supplemental and does not accept the original installed Windows CM-5.
* MG-6: full module/Forge suites with native-dependent cases executed, ordinary candidate
  jar without probe classes, source/class boundary checks and appropriate negative arms.
  Record native/library/jar/source hashes, raw XML/logs/screenshots and exact-head CI with
  skipped native steps. No performance/FPS or v1 claim without its original gates.

Existing current limits remain: the Linux renderer is software, the installed Windows
baseline remains unaccepted, in-process native crashes can terminate the JVM, and engine
fracture/crushing/rigid-body dynamics are not implemented in Java. This unit advances
material geometry and interaction; it does not redefine those remaining v1 requirements.
