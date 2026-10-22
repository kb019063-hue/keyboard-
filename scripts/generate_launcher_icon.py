#!/usr/bin/env python3
"""Render the launcher icon for Mr. Brown's Keyboard at every density.

The icon is drawn, not traced from a raster: a caramel keycap wearing a bowler hat. Drawing it
gives exact transparency (the upstream assets are transparent-backed, and a flood-fill key of a
shaded raster either ate the hat's dark brim or left shadow smudge on the alpha), crisp edges at
48px, and reproducibility -- the same reason the dictionaries, compose data and settings search
index are generated rather than hand-edited.

Emits, matching the densities already present in res/:

  mipmap-<d>/ic_launcher.png            48/72/96/144/192   legacy icon, API 24-25
  drawable-<d>/ic_launcher_foreground.png  108/162/216/324/432   adaptive foreground

The adaptive foreground keeps the motif inside ~62% of the 108dp canvas: a launcher mask may crop
the outer 18dp ring, so content must live in the inner ~66dp to survive a circular mask. The
legacy icon lets it fill ~80%, since nothing masks it.

Colours are sampled from the approved artwork concept (warm caramel keycap, dark chocolate hat,
espresso backdrop); res/drawable/ic_launcher_background.xml carries the matching backdrop so the
masked adaptive shape and this foreground read as one composition.

Usage: python3 scripts/generate_launcher_icon.py
"""
import os
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

# Design space is 1000x1000; every shape is authored there and scaled per density.
SS = 4                      # supersample factor, downscaled with LANCZOS for clean antialiasing
DESIGN = 1000

# Palette (from the approved artwork).
BG          = (0x28, 0x16, 0x0D)   # espresso backdrop (also ic_launcher_background.xml)
SKIRT_TOP   = (0xA5, 0x6C, 0x39)
SKIRT_BOT   = (0x7C, 0x4E, 0x28)
FACE_TOP    = (0xE3, 0xB2, 0x72)
FACE_BOT    = (0xC8, 0x90, 0x4C)
FACE_GLOW   = (0xF2, 0xCC, 0x93)
HAT_DOME_T  = (0x5E, 0x44, 0x33)
HAT_DOME_B  = (0x35, 0x24, 0x19)
HAT_BRIM_T  = (0x4A, 0x34, 0x27)
HAT_BRIM_B  = (0x2B, 0x1C, 0x13)

LEGACY_DENSITIES  = (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192))
ADAPTIVE_DENSITIES = (("mdpi", 108), ("hdpi", 162), ("xhdpi", 216), ("xxhdpi", 324), ("xxxhdpi", 432))


def vgrad(draw, box, top, bot):
    """Fill box with a vertical linear gradient from top to bot."""
    x0, y0, x1, y1 = box
    hgt = max(1, y1 - y0)
    for y in range(y0, y1):
        t = (y - y0) / hgt
        c = tuple(round(top[i] + (bot[i] - top[i]) * t) for i in range(3))
        draw.line((x0, y, x1, y), fill=c + (255,))


def rrect_gradient(canvas, box, radius, top, bot):
    """A rounded rect filled with a vertical gradient, composited onto canvas."""
    layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    vgrad(ImageDraw.Draw(layer), box, top, bot)
    mask = Image.new("L", canvas.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle(box, radius=radius, fill=255)
    canvas.paste(layer, (0, 0), mask)


def ellipse_gradient(canvas, box, top, bot):
    layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    vgrad(ImageDraw.Draw(layer), box, top, bot)
    mask = Image.new("L", canvas.size, 0)
    ImageDraw.Draw(mask).ellipse(box, fill=255)
    canvas.paste(layer, (0, 0), mask)


def draw_motif(size):
    """The bowler-hatted keycap, transparent-backed, motif at ~80% of the canvas."""
    s = size * SS
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    k = s / DESIGN

    # Keycap body: outer skirt, with the lighter top face INSET inside it (a face that pokes
    # above the silhouette reads as a bottle neck, not a key).
    rrect_gradient(img, (int(250 * k), int(300 * k), int(750 * k), int(810 * k)),
                   int(110 * k), SKIRT_TOP, SKIRT_BOT)
    rrect_gradient(img, (int(295 * k), int(330 * k), int(705 * k), int(655 * k)),
                   int(80 * k), FACE_TOP, FACE_BOT)
    # Soft highlight across the top of the face, so the key reads as lit from above.
    glow = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(glow).rounded_rectangle(
        (int(325 * k), int(355 * k), int(675 * k), int(470 * k)),
        radius=int(58 * k), fill=FACE_GLOW + (60,))
    img = Image.alpha_composite(img, glow)

    # Bowler hat, sitting on the keycap's top edge. Order matters: the dome first, then a brim
    # clearly WIDER than the dome drawn across its base -- brim behind the dome made the hat read
    # as a top-knot. The wide flat brim is what says "bowler" at 48px.
    ellipse_gradient(img, (int(408 * k), int(148 * k), int(592 * k), int(292 * k)),
                     HAT_DOME_T, HAT_DOME_B)
    ellipse_gradient(img, (int(338 * k), int(252 * k), int(662 * k), int(322 * k)),
                     HAT_BRIM_T, HAT_BRIM_B)
    # A faint sheen on the dome's crown.
    sheen = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(sheen).ellipse(
        (int(445 * k), int(165 * k), int(545 * k), int(215 * k)), fill=(255, 255, 255, 24))
    img = Image.alpha_composite(img, sheen)

    img = img.resize((size, size), Image.LANCZOS)
    # Trim to the motif so the caller can place it at an exact fraction of the canvas.
    return img.crop(img.getchannel("A").getbbox())


def emit(motif, path, canvas, fill):
    img = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    scale = fill * canvas / max(motif.size)
    m = motif.resize((max(1, round(motif.width * scale)),
                      max(1, round(motif.height * scale))), Image.LANCZOS)
    img.paste(m, ((canvas - m.width) // 2, (canvas - m.height) // 2), m)
    img.save(path)
    return img.size


def main():
    motif = draw_motif(DESIGN)
    for d, px in LEGACY_DENSITIES:
        p = os.path.join(ROOT, "res", f"mipmap-{d}", "ic_launcher.png")
        os.makedirs(os.path.dirname(p), exist_ok=True)
        emit(motif, p, px, 0.80)
        print(f"  mipmap-{d:<8} ic_launcher   {px}x{px}")
    for d, px in ADAPTIVE_DENSITIES:
        p = os.path.join(ROOT, "res", f"drawable-{d}", "ic_launcher_foreground.png")
        os.makedirs(os.path.dirname(p), exist_ok=True)
        emit(motif, p, px, 0.62)
        print(f"  drawable-{d:<7} foreground    {px}x{px}")
    print(f"  background colour            #{BG[0]:02x}{BG[1]:02x}{BG[2]:02x}")


if __name__ == "__main__":
    main()
