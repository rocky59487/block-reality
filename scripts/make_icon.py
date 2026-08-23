#!/usr/bin/env python3
"""Draws the mod icon, from code, at 40x40 and scales it up with hard pixels.

    python3 scripts/make_icon.py

Writes forge/src/main/resources/blockreality_icon.png (400x400, the size CurseForge
wants) and docs/images/icon-64.png, which is roughly how big it is in a mod list.

Why a script and not an image someone drew once: this repository's whole argument is that
you can check where a number came from. An icon is not a number, but a binary blob in the
tree that nobody can regenerate is still the wrong habit. This is forty lines of
rectangles; edit them and rerun.

What it draws is the mod's own output rather than a logo: four blocks cantilevering out of
a stone wall, painted with the utilisation ramp and sagging under a load. The root is red
because that is where a cantilever's moment is largest — an icon that got that backwards
would be a small lie on the front of a structural-analysis mod.
"""
import os

from PIL import Image, ImageDraw

N = 40           # logical pixels; everything is drawn on this grid
SCALE = 10       # 40 * 10 = 400, the size CurseForge asks for
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

BG = (15, 20, 27)
GRID = (28, 38, 52)
STONE = (112, 120, 132)
OUTLINE = (9, 12, 17)
LOAD = (250, 210, 90)

# Utilisation ramp, low to high: the same story the lens tells in game.
RAMP = [(59, 130, 246), (32, 190, 200), (76, 192, 96), (222, 205, 60),
        (233, 148, 48), (233, 66, 58)]


def ramp(t):
    """Colour at 0..1 along the utilisation ramp, linearly between stops."""
    t = min(max(t, 0.0), 1.0) * (len(RAMP) - 1)
    i = int(t)
    if i >= len(RAMP) - 1:
        return RAMP[-1]
    f = t - i
    a, b = RAMP[i], RAMP[i + 1]
    return tuple(round(a[k] + (b[k] - a[k]) * f) for k in range(3))


def shade(c, k):
    return tuple(min(255, max(0, round(v * k))) for v in c)


def block(d, x, y, w, h, fill, top_k=1.38, bottom_k=0.58):
    """One chunky block: outline, body, lit top edge, shaded bottom edge."""
    d.rectangle([x, y, x + w - 1, y + h - 1], fill=fill, outline=OUTLINE)
    d.rectangle([x + 1, y + 1, x + w - 2, y + 1], fill=shade(fill, top_k))
    d.rectangle([x + 1, y + h - 2, x + w - 2, y + h - 2], fill=shade(fill, bottom_k))


def main():
    img = Image.new("RGBA", (N, N), BG)
    d = ImageDraw.Draw(img)

    # Blueprint grid: texture, not pattern.
    for i in range(0, N, 8):
        d.line([(i, 0), (i, N - 1)], fill=GRID)
        d.line([(0, i), (N - 1, i)], fill=GRID)

    # The wall the beam is built into. Three stone blocks, drawn first so the beam's
    # root sits ON them — a beam that floats a pixel clear of its support is the one
    # thing this mod would call a mechanism.
    for by in (12, 21, 30):
        block(d, 1, by, 10, 9, STONE, top_k=1.22, bottom_k=0.72)

    # Four beam blocks cantilevering right, hot at the root, and sagging: one logical
    # pixel more on each block outward.
    for k in range(4):
        block(d, 9 + k * 7, 15 + k, 8, 8, ramp(1.0 - k / 3.4))

    # The load that put it there, landing on the tip.
    tip_x, tip_y = 33, 18
    d.rectangle([tip_x - 1, 1, tip_x + 1, tip_y - 7], fill=LOAD)
    d.polygon([(tip_x - 4, tip_y - 8), (tip_x + 4, tip_y - 8), (tip_x, tip_y - 2)], fill=LOAD)

    # Rounded corners, so it sits well in both platforms' round-rect frames.
    mask = Image.new("L", (N, N), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, N - 1, N - 1], radius=5, fill=255)
    img.putalpha(mask)

    big = img.resize((N * SCALE, N * SCALE), Image.NEAREST)
    out = os.path.join(ROOT, "forge", "src", "main", "resources", "blockreality_icon.png")
    big.save(out)
    small = img.resize((64, 64), Image.NEAREST)
    small_out = os.path.join(ROOT, "docs", "images", "icon-64.png")
    small.save(small_out)
    print(f"wrote {out} ({big.width}x{big.height}) and {small_out} (64x64)")


if __name__ == "__main__":
    main()
