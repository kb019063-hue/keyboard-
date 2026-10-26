#!/usr/bin/env python3
"""Render a shipped keyboard layout XML to a PNG preview (development aid).

This is NOT the product renderer — it is a lightweight, dependency-free
visualisation of the *declared* layout so a layout edit can be eyeballed against
a reference screenshot without building the APK. It parses the same XML that
`KeyboardData.load` reads on device and draws the centre labels plus any corner
hints, honoring `width`/`shift`/`height` and the Gboard-style layout's rules:

  - `loc ` corners are placement markers and are NOT drawn.
  - `embedded_number_row="true"` layouts already contain their digit row.
  - `bottom_row="false"` layouts already contain their own bottom row.

Usage:
    python3 tools/render_layout_preview.py \
        src/main/layouts/latn_qwerty_us_gboard_style.xml \
        docs/gboard-style-preview.png

Requires ImageMagick `convert` with a TrueType font (DejaVu Sans). Produces a
deterministic PNG. The generated file is a documentation artifact; keep it out
of the shipped APK (it lives under docs/).
"""
import subprocess
import sys
import xml.etree.ElementTree as ET

# CleverKeysDark theme (res/values/themes.xml) — the shipped default theme.
KB_BG = "#1E1030"
KEY_BG = "#2A1845"
KEY_EDGE = "#3D2060"
LABEL = "#E8E8E8"
SUBLABEL = "#8A8A8A"
ENTER_BG = "#3D5A9E"  # action/enter key is accented, like Gboard's blue key
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"

W = 1000
PAD_X = 10
KEY_W = (W - 2 * PAD_X) / 10.0
KEY_H = 118
GAP = 6
TOP = 24
# The shipped 7-icon toolbar (KeyboardToolbar / ToolbarConfig.DEFAULT_KEYS_STRING) sits above
# the key rows. It is a separate view on device; drawing it here makes the preview directly
# comparable to the owner's Gboard reference screenshot.
TOOLBAR_H = 96
TOOLBAR_ICONS = [
    ("⊞", "more_tools"), ("↶", "undo"), ("⬏", "select_word"),
    ("▤", "switch_clipboard"), ("⚙", "config"), ("G", "switch_gif"),
    ("◉", "voice_typing"),
]

# Centre-label substitutions for command keys (the layout stores key names).
GLYPHS = {
    # NOTE: `space` maps to a plain bar glyph, because the live IME draws the special-font
    # space symbol (KeyValue 0xE00D), NOT Gboard's "English" language label. Render honestly:
    # a wide neutral space bar. The Gboard reference prints "English" there; that is a renderer
    # feature this layout preview intentionally does not fake.
    "shift": "⇧", "backspace": "⌫", "enter": "↵", "space": "",
    "switch_emoji": "☺", "switch_numeric": "?123", "?123:switch_numeric": "?123",
    "delete": "del", "config": "⚙", "voice_typing": "🎤", "action": "↵",
}
CORNERS = ["nw", "ne", "sw", "se", "w", "e", "n", "s"]


def label_for(raw):
    if raw is None:
        return ""
    if raw in GLYPHS:
        return GLYPHS[raw]
    # "?123:switch_numeric" style label:key — draw the label.
    if ":" in raw and not raw.startswith("loc "):
        return raw.split(":", 1)[0]
    if raw.startswith("loc "):
        return ""
    return raw


def esc(s):
    return s.replace("\\", "\\\\").replace("'", "\\'").replace('"', '\\"')


def main():
    src, out = sys.argv[1], sys.argv[2]
    root = ET.parse(src).getroot()
    rows = list(root)
    heights = [float(r.get("height", "1.0")) for r in rows]
    total_h = TOOLBAR_H + TOP + sum(h * KEY_H for h in heights) + 24

    draws = []
    # Toolbar row: seven shipped default commands, mic last and circled like Gboard.
    cell = W / float(len(TOOLBAR_ICONS))
    for i, (glyph, _name) in enumerate(TOOLBAR_ICONS):
        cx = cell * (i + 0.5)
        cy = TOOLBAR_H * 0.52
        if i == len(TOOLBAR_ICONS) - 1:
            draws.append(
                "-fill '%s' -stroke none -draw \"circle %.1f,%.1f %.1f,%.1f\""
                % (KEY_EDGE, cx, cy, cx + 30, cy)
            )
        draws.append(
            "-font '%s' -pointsize 30 -fill '%s' -stroke none "
            "-draw \"text %.1f,%.1f '%s'\""
            % (FONT, LABEL, cx - 12, cy + 10, esc(glyph))
        )
    y = TOOLBAR_H + TOP
    for ridx, row in enumerate(rows):
        rh = heights[ridx] * KEY_H
        x = PAD_X
        for k in row:
            w = float(k.get("width", "1.0")) * KEY_W
            sh = float(k.get("shift", "0.0")) * KEY_W
            x += sh
            x0, y0 = x + GAP / 2, y + GAP / 2
            x1, y1 = x + w - GAP / 2, y + rh - GAP / 2
            c = k.get("c")
            bg = ENTER_BG if (c in ("enter",)) else KEY_BG
            draws.append(
                "-fill '%s' -stroke '%s' -strokewidth 1 "
                "-draw \"roundrectangle %.1f,%.1f %.1f,%.1f 10,10\""
                % (bg, KEY_EDGE, x0, y0, x1, y1)
            )
            # The live IME draws a special-font space glyph (a wide neutral bar), not text.
            if c == "space":
                bx0, bx1 = x0 + (x1 - x0) * 0.28, x1 - (x1 - x0) * 0.28
                by = (y0 + y1) / 2
                draws.append(
                    "-stroke '%s' -strokewidth 3 -fill none "
                    "-draw \"line %.1f,%.1f %.1f,%.1f\""
                    % (SUBLABEL, bx0, by, bx1, by)
                )
            # centre label
            cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
            main_lbl = label_for(c)
            if main_lbl:
                size = 30 if len(main_lbl) <= 2 else 22
                font = BOLD if len(main_lbl) == 1 else FONT
                draws.append(
                    "-font '%s' -pointsize %d -fill '%s' -stroke none "
                    "-draw \"text %.1f,%.1f '%s'\""
                    % (font, size, LABEL, cx - len(main_lbl) * size * 0.29, cy + size * 0.34, esc(main_lbl))
                )
            # one calm corner hint per character key (mirrors CornerLabelPolicy)
            is_char = c is not None and len(c) == 1 and c.isalpha()
            if is_char:
                for attr in ("ne", "nw", "se", "sw"):
                    v = k.get(attr)
                    if v and not v.startswith("loc "):
                        lbl = label_for(v)
                        draws.append(
                            "-font '%s' -pointsize 15 -fill '%s' -stroke none "
                            "-draw \"text %.1f,%.1f '%s'\""
                            % (FONT, SUBLABEL, x1 - 16, y0 + 18, esc(lbl))
                        )
                        break
            x += w
        y += rh

    # Build one shell-free convert invocation with all draw operations in order.
    import shlex
    args = ["convert", "-size", "%dx%d" % (W, int(total_h)), "xc:" + KB_BG]
    for d in draws:
        args += shlex.split(d)
    args.append(out)
    subprocess.check_call(args)
    print("wrote", out)


if __name__ == "__main__":
    main()
