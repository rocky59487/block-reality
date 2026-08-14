package com.blockreality.api.render;

/**
 * An 8-bit sRGB colour.
 *
 * <p>Interpolation goes through linear light rather than straight sRGB bytes. Lerping
 * sRGB directly darkens the midpoint of a blue-to-red ramp visibly, which would put a
 * dark band right where the neutral axis is — the one place the reader is meant to be
 * looking.
 */
public record Rgb(int r, int g, int b) {

    public Rgb {
        r = clamp(r);
        g = clamp(g);
        b = clamp(b);
    }

    private static int clamp(int v) { return v < 0 ? 0 : Math.min(v, 255); }

    public static Rgb hex(int rgb) {
        return new Rgb((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }

    public int packed() { return (r << 16) | (g << 8) | b; }

    /** ARGB with the given alpha in 0..1, the form Minecraft's vertex consumers want. */
    public int argb(float alpha) {
        int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f);
        return (a << 24) | packed();
    }

    /** Linear-light interpolation. {@code t} is clamped to 0..1. */
    public static Rgb lerp(Rgb a, Rgb b, double t) {
        double u = t < 0 ? 0 : Math.min(t, 1);
        return new Rgb(
                enc(dec(a.r) + (dec(b.r) - dec(a.r)) * u),
                enc(dec(a.g) + (dec(b.g) - dec(a.g)) * u),
                enc(dec(a.b) + (dec(b.b) - dec(a.b)) * u));
    }

    private static double dec(int c) {
        double s = c / 255.0;
        return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    private static int enc(double l) {
        double v = l <= 0.0031308 ? l * 12.92 : 1.055 * Math.pow(l, 1 / 2.4) - 0.055;
        return (int) Math.round(Math.max(0, Math.min(1, v)) * 255);
    }

    @Override
    public String toString() { return String.format("#%06X", packed()); }
}
