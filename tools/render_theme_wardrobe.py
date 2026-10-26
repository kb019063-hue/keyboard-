#!/usr/bin/env python3
"""Render the five wardrobe themes as one comparison sheet.

Development aid only. Colours are the same hex values as res/values/themes.xml.
Output: docs/theme-wardrobe.png
"""
import subprocess
import xml.etree.ElementTree as ET

FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
LAYOUT = "src/main/layouts/latn_qwerty_us_gboard_style.xml"
OUT = "docs/theme-wardrobe.png"

THEMES = [
    ("Harbor", "Night navy · default", "#0C1826", "#1A3352", "#2B6CB0", "#F4F7FB", "#24466E", 0),
    ("Daybreak", "Cool daylight", "#E4EDF6", "#F8FBFE", "#1B5FBF", "#142033", "#C9D7E6", 1),
    ("Signal", "Electric blue on black", "#070B12", "#101826", "#E8F1FF", "#A9CFFF", "#2A4E86", 1),
    ("Ember", "Lacquer red edge", "#120E0C", "#241816", "#E24A38", "#F6EDE8", "#C44536", 1.2),
    ("Kiln", "Warm clay", "#F6EBE4", "#FFF8F4", "#B4332A", "#3C241E", "#E4C9BC", 1),
]

W, PAD, KEY_H, GAP = 920, 16, 46, 3
KEY_W = (W - 2 * PAD) / 10.0


def label(raw):
    if not raw or raw.startswith("loc "):
        return ""
    return {
        "shift": "⇧", "backspace": "⌫", "enter": "↵", "space": "",
        "switch_emoji": "☺",
    }.get(raw, raw.split(":", 1)[0])


def draw_theme(name, caption, bg, key, pressed, text, edge, border, rows):
    cap_h = 36
    body = 8 + sum(float(r.get("height", "1")) * KEY_H for r in rows) + 8
    h = cap_h + body
    draws = [
        "-fill '%s' -draw 'rectangle 0,0 %d,%d'" % (bg, W, h),
        "-font '%s' -pointsize 18 -fill '%s' -draw \"text 16,24 '%s'\"" % (BOLD, text, name),
        "-font '%s' -pointsize 14 -fill '%s' -draw \"text 150,24 '%s'\"" % (FONT, text, caption),
    ]
    y = cap_h + 6
    for row in rows:
        rh = float(row.get("height", "1")) * KEY_H
        x = PAD
        for k in row:
            w = float(k.get("width", "1")) * KEY_W
            x += float(k.get("shift", "0")) * KEY_W
            x0, y0 = x + GAP / 2, y + GAP / 2
            x1, y1 = x + w - GAP / 2, y + rh - GAP / 2
            c = k.get("c")
            fill = pressed if c == "enter" else key
            sw = border if border else 0
            draws.append(
                "-fill '%s' -stroke '%s' -strokewidth %.1f -draw 'roundrectangle %.1f,%.1f %.1f,%.1f 6,6'"
                % (fill, edge, sw, x0, y0, x1, y1)
            )
            lbl = label(c)
            if lbl:
                size = 16 if len(lbl) <= 2 else 12
                draws.append(
                    "-font '%s' -pointsize %d -fill '%s' -stroke none -draw \"text %.1f,%.1f '%s'\""
                    % (BOLD if len(lbl) == 1 else FONT, size,
                       "#071018" if c == "enter" and name == "Signal" else ("#FFFFFF" if c == "enter" else text),
                       (x0 + x1) / 2 - len(lbl) * size * 0.28, (y0 + y1) / 2 + size * 0.35, lbl)
                )
            x += w
        y += rh
    path = "/tmp/theme-%s.png" % name.lower()
    args = ["convert", "-size", "%dx%d" % (W, int(h)), "xc:" + bg]
    import shlex
    for d in draws:
        args += shlex.split(d)
    args.append(path)
    subprocess.check_call(args)
    return path


def main():
    rows = list(ET.parse(LAYOUT).getroot())
    parts = [draw_theme(*t, rows) for t in THEMES]
    subprocess.check_call(["convert", "-background", "#111111", "-append", "-bordercolor", "#111111", "-border", "12"] + parts + [OUT])
    print("wrote", OUT)


if __name__ == "__main__":
    main()
