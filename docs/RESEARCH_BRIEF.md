# Block Reality — research brief

**Release:** v0.1a · 20 August 2026  
**Repository:** https://github.com/rocky59487/block-reality  
**Platform:** Minecraft Java 1.20.1 · Forge 47.x  
**License:** Apache-2.0

## Research question

Can a block-building game serve as a transparent, reproducible front end for structural
analysis without replacing mechanics with scripted collapse rules?

Block Reality turns selected Minecraft blocks into an explicit finite-element model,
solves it outside the game process, and maps the results back onto the structure. The
current release is a structural preview: analysis and visualisation are implemented;
construction-sequence gameplay and nonlinear collapse are not.

## Current analysis path

1. The Forge mod snapshots structural blocks, supports and test loads.
2. Structural-steel runs become 6-DOF beam members; slab tokens become MITC4 shell facets.
3. The `br-sidecar` process builds and solves the structural model using FrameCore.
4. Results return as per-member and per-plate demand/capacity ratios, stress fields,
   equilibrium diagnostics and linear buckling factors.
5. The client renders utilisation, stress and material lenses on the blocks.

Running the C++ backend out of process is deliberate: a native fault invalidates one
analysis rather than crashing the Minecraft server or risking the world save. A missing
backend disables analysis explicitly while leaving the mod playable.

## Implemented numerical scope

| Area | v0.1a |
|---|---|
| Members | 3D 6-DOF beam/column members |
| Plates | MITC4 shell facets for floors and shear walls |
| Stability | Linear buckling with geometric stiffness for beams and shells |
| Recovery | Member fibre stresses, plate surface stresses and transverse shear |
| Screening | Per-member and elastic per-plate demand/capacity ratios |
| Diagnostics | Mechanism detection and independently recomputed global equilibrium residual |
| Interaction | Test loads, stress/utilisation/material lenses and section readout |

The supplied structural-steel token currently represents a **solid 200 × 400 mm rectangle**,
not a rolled I- or H-section. The slab token represents a 200 mm concrete shell. These
tokens are intentionally named by what the solver actually receives.

## Verification snapshot

| Evidence | v0.1a result |
|---|---|
| Engine checks | 251/251 passing; each uses a closed form or solver-independent invariant |
| Java tests | 187/187 passing; 28 start the real sidecar and FrameCore |
| Closed-form, non-zero | 31 references; worst relative error 1.6e-10 |
| Closed-form, zero | 10 references; worst absolute residual 1.5e-08 |
| Clamped square plate | 0.57% span-moment error at 20 elements/side; 2.7% recovered support-moment error |
| Slender shear wall | Shear flow and overturning agree with beam theory to 1e-7 |
| Column buckling | Textbook single-element value to 1.6e-05; inverse-length-squared law to 2.3e-10 |
| Cross-platform determinism | 8/8 cases byte-for-byte identical between Linux native and Windows cross-build |
| End-to-end timing | 199 members / 1200 DOF in 52 ms median, including buckling and process round trip |

The detailed, generated record is in
[`evidence/VERIFICATION.md`](../evidence/VERIFICATION.md). It should be treated as the
source of truth when this summary and the evidence ever disagree.

## Known limits

- Plate D/C is an elastic surface screen. Transverse shear is recovered but not screened.
- There is no per-plate buckling check or plate ultimate-strength model.
- Reinforced-concrete composite sections are not implemented.
- Buckling is the linear onset, not a nonlinear post-buckling path.
- Construction sequence, persistent damage, fracture and progressive collapse are not in
  v0.1a.
- The current support rule is intentionally narrow: a structural block is grounded only
  when the directly-below neighbour is solid and non-structural.
- This is research and education software, not a building-code checker or a tool for
  real-world design and safety decisions.

## Review requested

Independent criticism is more useful than a general endorsement. In particular:

1. Are the block-to-member and block-to-shell semantics understandable and mechanically
   defensible for an educational sandbox?
2. Which beam, shell, mechanism or buckling benchmark would most improve the verification
   set?
3. Are any current verification claims too broad for the evidence linked above?
4. Which minimum interaction would make this useful in a mechanics or structures class?

Please use the
[research feedback issue form](https://github.com/rocky59487/block-reality/issues/new?template=research-feedback.yml)
so the model, version and reference remain reproducible.

## Citation

The repository contains [`CITATION.cff`](../CITATION.cff). If you evaluate or use the
software in teaching or research, citing the exact release and linking your public notes
would provide especially useful independent evidence.
