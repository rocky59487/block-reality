package com.blockreality.api.render;

import java.util.List;

/**
 * How a stress number becomes a colour.
 *
 * <p>Two rules govern everything here.
 *
 * <p><strong>Sign picks the hue, magnitude picks the saturation.</strong> A member is
 * never "just red". A bent member is tension on one face and compression on the other at
 * the same station, and the overlay has to show that or it is decoration. The ramp from
 * neutral to full hue is <em>linear</em> in stress so the picture stays quantitatively
 * readable: half the saturation is half the stress, and a figure in a paper can be read
 * off against its legend.
 *
 * <p><strong>Colour is never the only channel.</strong> {@link #hatchFor} returns a
 * pattern for the same value; see {@link Hatch}.
 *
 * <h2>Which way round?</h2>
 * {@link #SIGNED_DEFAULT} paints <strong>tension blue and compression red</strong>. This
 * is the convention the project owner specified, and it is the one a Minecraft player
 * meets first: the top face of a loaded cantilever goes blue, the bottom red.
 *
 * <p>{@link #TEACHING} uses the token set from TEACHING_PORT instead — tension teal
 * {@code #0C6266}, compression {@code #315E80}, zero {@code #7A8587} — which is what the
 * course material already trains readers on. Both are shipped because a screenshot in a
 * paper and a screenshot in a lesson have different audiences, and silently using one
 * where the other is expected is worse than offering the choice.
 */
public enum StressPalette {

    /** Tension blue, compression red. The default. */
    SIGNED_DEFAULT("signed", 0x2F6FB0, 0xB0342A, 0x7A8587),

    /** TEACHING_PORT tokens: tension teal, compression slate blue. */
    TEACHING("teaching", 0x0C6266, 0x315E80, 0x7A8587);

    // --------------------------------------------------------- utilisation ramp
    // DEMO_V0 §6, from Issue #2: cold when safe, safety amber approaching the limit,
    // vermilion past it. Vermilion is deliberately a different red from the
    // compression red above — in UTILIZATION mode red means "spent", in stress mode
    // it means "compressed", and the two must not be confusable at a glance.
    private static final Rgb DC_SAFE = Rgb.hex(0x2E6F8E);
    private static final Rgb DC_NEAR = Rgb.hex(0xC8860D);
    private static final Rgb DC_OVER = Rgb.hex(0xD0392B);

    /** Below this fraction of the member's peak, a fibre reads as neutral, not as a weak hue. */
    private static final double NEUTRAL_BAND = 0.02;

    private final String id;
    private final Rgb tension;
    private final Rgb compression;
    private final Rgb zero;

    StressPalette(String id, int tension, int compression, int zero) {
        this.id = id;
        this.tension = Rgb.hex(tension);
        this.compression = Rgb.hex(compression);
        this.zero = Rgb.hex(zero);
    }

    public String id() { return id; }

    public Rgb tensionColour() { return tension; }

    public Rgb compressionColour() { return compression; }

    public Rgb zeroColour() { return zero; }

    /**
     * Colour for a signed stress, normalised against a reference magnitude.
     *
     * @param sigmaMpa tension-positive stress (see {@link com.blockreality.api.Fibre})
     * @param peakMpa  the magnitude that maps to full saturation, typically the member's
     *                 own peak so its internal distribution is visible. A non-positive
     *                 or non-finite peak yields the neutral colour rather than a
     *                 division by zero.
     */
    public Rgb signedStress(double sigmaMpa, double peakMpa) {
        double t = normalise(sigmaMpa, peakMpa);
        if (t == 0) return zero;
        return Rgb.lerp(zero, sigmaMpa > 0 ? tension : compression, t);
    }

    /** Pattern channel for the same value — see {@link Hatch}. */
    public Hatch hatchFor(double sigmaMpa, double peakMpa) {
        double t = normalise(sigmaMpa, peakMpa);
        if (t == 0) return Hatch.NONE;
        return sigmaMpa > 0 ? Hatch.DIAGONAL_UP : Hatch.DIAGONAL_DOWN;
    }

    private static double normalise(double sigmaMpa, double peakMpa) {
        if (!Double.isFinite(sigmaMpa) || !Double.isFinite(peakMpa) || peakMpa <= 0) return 0;
        double t = Math.abs(sigmaMpa) / peakMpa;
        if (t < NEUTRAL_BAND) return 0;
        return Math.min(t, 1.0);
    }

    // ------------------------------------------------------------- utilisation
    /**
     * Colour for a demand/capacity ratio. Safe below 0.6, ramping to amber at 1.0, and
     * flat vermilion beyond — a member at 3.0 is not three times more interesting than
     * one at 1.5, it has simply failed.
     */
    public static Rgb utilization(double dc) {
        if (!Double.isFinite(dc) || dc <= 0) return DC_SAFE;
        if (dc >= 1.0) return DC_OVER;
        if (dc <= 0.6) return Rgb.lerp(DC_SAFE, DC_NEAR, dc / 0.6 * 0.35);
        return Rgb.lerp(Rgb.lerp(DC_SAFE, DC_NEAR, 0.35), DC_NEAR, (dc - 0.6) / 0.4);
    }

    public static Hatch utilizationHatch(double dc) {
        if (!Double.isFinite(dc)) return Hatch.NONE;
        if (dc >= 1.0) return Hatch.CROSS;
        if (dc >= 0.6) return Hatch.DIAGONAL_DOWN;
        return Hatch.NONE;
    }

    /** Legend stops for the HUD, in order. Labels are translation keys, not display text. */
    public static List<LegendStop> utilizationLegend() {
        return List.of(
                new LegendStop("br.legend.dc.safe", 0.0, utilization(0.0), Hatch.NONE),
                new LegendStop("br.legend.dc.watch", 0.6, utilization(0.6), Hatch.DIAGONAL_DOWN),
                new LegendStop("br.legend.dc.limit", 1.0, utilization(1.0), Hatch.CROSS));
    }

    public List<LegendStop> stressLegend() {
        return List.of(
                new LegendStop("br.legend.stress.compression", -1.0, compression, Hatch.DIAGONAL_DOWN),
                new LegendStop("br.legend.stress.zero", 0.0, zero, Hatch.NONE),
                new LegendStop("br.legend.stress.tension", 1.0, tension, Hatch.DIAGONAL_UP));
    }

    // ---------------------------------------------------------------- material
    // The third lens. It answers a different question from the other two — not "how hard
    // is this working" but "what is it made of" — so it deliberately shares nothing with
    // the stress ramp: flat, unshaded, one colour per material, no magnitude at all.
    // Reading a saturation here would be reading a quantity that is not being shown.
    private static final Rgb MAT_STEEL = Rgb.hex(0x5B7C99);
    private static final Rgb MAT_CONCRETE = Rgb.hex(0x9C9A93);
    private static final Rgb MAT_TIMBER = Rgb.hex(0xA9773F);
    private static final Rgb MAT_BRICK = Rgb.hex(0x9E4B34);
    private static final Rgb MAT_UNKNOWN = Rgb.hex(0x6E6E6E);

    /**
     * Colour for a material token, as the engine spells it.
     *
     * <p>An unrecognised token gets its own neutral grey rather than borrowing another
     * material's colour: a token this build has never heard of is a fact worth seeing,
     * and painting it as concrete would hide exactly the case a reader needs to catch.
     */
    public static Rgb material(String token) {
        if (token == null) return MAT_UNKNOWN;
        switch (token) {
            case "steel": return MAT_STEEL;
            case "concrete": return MAT_CONCRETE;
            case "timber": return MAT_TIMBER;
            case "brick": return MAT_BRICK;
            default: return MAT_UNKNOWN;
        }
    }

    /** Legend stops for the material lens, in the order the creative tab lists them. */
    public static List<LegendStop> materialLegend() {
        return List.of(
                new LegendStop("br.legend.material.steel", 0, MAT_STEEL, Hatch.NONE),
                new LegendStop("br.legend.material.concrete", 1, MAT_CONCRETE, Hatch.NONE),
                new LegendStop("br.legend.material.timber", 2, MAT_TIMBER, Hatch.NONE),
                new LegendStop("br.legend.material.brick", 3, MAT_BRICK, Hatch.NONE));
    }

    /** One entry in a legend. */
    public record LegendStop(String translationKey, double value, Rgb colour, Hatch hatch) { }

    /** Looks up a palette by id, falling back to the default rather than throwing. */
    public static StressPalette byId(String id) {
        for (StressPalette p : values()) if (p.id.equals(id)) return p;
        return SIGNED_DEFAULT;
    }
}
