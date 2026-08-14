package com.blockreality.api.render;

/**
 * A non-colour channel carried alongside every colour this API produces.
 *
 * <p>DEMO_V0 §6 is explicit: the scale must add lightness, texture or an icon and must
 * not rely on red-versus-green alone. Roughly one in twelve men has a red-green colour
 * deficiency, and a tension/compression overlay that encodes its single most important
 * distinction in exactly that axis is unreadable to them.
 *
 * <p>Returning the hatch as data rather than baking it into a texture keeps the choice
 * with the renderer, which knows whether it is drawing a ribbon, a band or a HUD swatch.
 */
public enum Hatch {
    /** Tension: hatching that leans with the fibre, drawn on the tensile face. */
    DIAGONAL_UP,
    /** Compression: hatching that leans against it. */
    DIAGONAL_DOWN,
    /** Near zero: no hatching, so the neutral region reads as empty rather than as a third state. */
    NONE,
    /** At or over capacity: cross-hatch, legible even in a greyscale screenshot. */
    CROSS
}
