#!/usr/bin/env python3
"""Render a shipped keyboard layout + a shipped XML theme to a PNG (development aid).

This is NOT the product renderer. It is a dependency-free (ImageMagick only)
visualisation of the *declared* layout and the *declared* theme, so a look can be
eyeballed without building the APK or booting an emulator. It reads the same two
files the app reads:

  src/main/layouts/<layout>.xml   -- key rows, widths, shifts, heights, corners
  res/values/themes.xml           -- colorKeyboard / colorKey / colorLabel /
                                     colorSublabel / colorSuggestionBar / keyGap /
                                     keyBorderRadius / blurRadius

and reproduces the two rules that decide what the glass actually looks like:

  * `KeyFillAlpha.of(colour, opacity) = alpha(colour) * opacity / 255` -- the
    keyboard field, the key tiles, the toolbar and the suggestion strip are all
    translucent, so the app behind them shows through at those alphas.
  * `SuggestionBarPalette.resolve(text, highlight, secondary, barBackground)` --
    each suggestion role keeps its theme colour only if it reaches 3:1 contrast
    against the bar; otherwise it falls back exactly as `Theme.kt` does.

The background is a simulated chat app, blurred under the keyboard the way the
API 31+ `blurRadius` backdrop blur blurs it, because translucent tiles drawn on a
flat grey lie about the look.

What it deliberately does NOT do: draw Google's icons or typeface (the toolbar
glyphs are geometric stand-ins drawn with ImageMagick, the letters are DejaVu),
or claim to be a device screenshot. For those, the emulator smoke test in CI
captures real `adb exec-out screencap` pixels -- see
`.github/workflows/emulator-smoke.yml` and `scripts/ci/emulator-smoke.sh`.

Usage:
    python3 tools/render_layout_preview.py --theme GboardDark  --out /tmp/dark.png
    python3 tools/render_layout_preview.py --theme GboardLight --out /tmp/light.png

Options:
    --theme NAME      <style> in res/values/themes.xml (default GboardDark)
    --layout PATH     layout XML (default latn_qwerty_us_gboard_style.xml)
    --themes PATH     themes XML (default res/values/themes.xml)
    --opacity N       suggestion-bar opacity percent (default 80, the shipped default)
    --width PX        output width (default 1080, Pixel 7 portrait)
    --no-blur         draw the app behind the keyboard sharp (to see the alpha alone)

The output is a scratch artifact: keep it out of the tree.
"""
import argparse
import os
import shlex
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"

# Geometry, in dp, from the shipped views (KeyboardToolbar, KeyboardComponentGraph, SuggestionBar).
TOOLBAR_DP = 40.0
STRIP_DP = 40.0
KEY_ROW_DP = 44.0          # Keyboard2View row height at the default 1.0 row height
STRIP_PAD_DP = 8.0         # SuggestionBar padding
CHIP_PAD_DP = 12.0         # suggestion TextView horizontal padding
CHIP_MARGIN_DP = 4.0
SUGGESTION_SP = 16.0
TOOLBAR_ICON_DP = 24.0
# Label metrics, from Keyboard2View.onMeasure + Config.Defaults: labelBaseSize is
# min(row height - vertical margin, (key width - horizontal margin) * 3/2) * CHARACTER_SIZE,
# then the main label is labelBaseSize * LABEL_TEXT_SIZE_FACTOR and a corner hint is
# labelBaseSize * SUBLABEL_TEXT_SIZE_FACTOR (x secondary_label_size_scale, default 1).
CHARACTER_SIZE = 1.18
LABEL_TEXT_SIZE_FACTOR = 0.36
SUBLABEL_TEXT_SIZE_FACTOR = 0.13
KEY_VERTICAL_MARGIN_DP = 1.5
KEY_HORIZONTAL_MARGIN_DP = 2.0
APP_DP = 372.0             # app chrome above the keyboard
TOOLBAR_COMMANDS = [
    "more_tools", "undo", "select_word", "switch_clipboard",
    "config", "switch_gif", "voice_typing",
]
# The three candidates the bar would show for "hel". The first is the highlighted one.
SUGGESTIONS = ["hello", "help", "held"]
TYPED = "hel"


# ---------------------------------------------------------------- colour helpers

def parse_theme(path, style_name):
    """Return {item name: raw value} for a <style> in a values XML file."""
    root = ET.parse(path).getroot()
    for style in root.iter("style"):
        if style.get("name") == style_name:
            return {item.get("name"): (item.text or "").strip() for item in style}
    raise SystemExit("no <style name=%r> in %s" % (style_name, path))


def argb(value):
    """'#AARRGGBB' / '#RRGGBB' -> (a, r, g, b)."""
    v = value.strip().lstrip("#")
    if len(v) == 8:
        return tuple(int(v[i:i + 2], 16) for i in (0, 2, 4, 6))
    if len(v) == 6:
        r, g, b = (int(v[i:i + 2], 16) for i in (0, 2, 4))
        return (255, r, g, b)
    raise SystemExit("not a colour: %r" % value)


def alpha_rgba(colour, opacity=255):
    """KeyFillAlpha.of: multiply the colour's alpha by the user's opacity."""
    a, r, g, b = colour
    a = a * opacity // 255
    return "rgba(%d,%d,%d,%.4f)" % (r, g, b, a / 255.0)


def opaque_rgba(colour):
    _a, r, g, b = colour
    return "rgb(%d,%d,%d)" % (r, g, b)


def luminance(colour):
    _a, r, g, b = colour

    def channel(c):
        c = c / 255.0
        return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
    return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)


def contrast(a, b):
    la, lb = luminance(a), luminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)


def composite(fg, bg, opacity=255):
    """Flatten a translucent colour over an opaque one (for contrast, which ignores alpha)."""
    a = (fg[0] * opacity // 255) / 255.0
    return (255,) + tuple(int(fg[i] * a + bg[i] * (1 - a)) for i in (1, 2, 3))


def resolve_bar_palette(theme, bar_opaque):
    """Mirror of SuggestionBarPalette.resolve in Theme.kt."""
    text = argb(theme["colorLabel"])
    highlight = argb(theme.get("colorLabelActivated", theme["colorLabel"]))
    secondary = argb(theme.get("colorSublabel", theme.get("colorSubLabel", theme["colorLabel"])))

    def readable(c):
        return contrast(c, bar_opaque) >= 3.0

    if readable(text):
        resolved_text = text
    else:
        black, white = (255, 0, 0, 0), (255, 255, 255, 255)
        resolved_text = black if contrast(black, bar_opaque) >= contrast(white, bar_opaque) else white
    return (
        resolved_text,
        highlight if readable(highlight) else resolved_text,
        secondary if readable(secondary) else resolved_text,
    )


# ---------------------------------------------------------------- ImageMagick glue

class Canvas:
    """Collects ImageMagick argv fragments. Arguments are passed directly (no shell), so the
    only quotes that survive are the MVG-level ones around a text payload."""

    def __init__(self):
        self.ops = []

    def raw(self, *ops):
        self.ops.extend(ops)

    def rect(self, x0, y0, x1, y1, fill):
        self.raw("-fill", fill, "-stroke", "none",
                 "-draw", "rectangle %.1f,%.1f %.1f,%.1f" % (x0, y0, x1, y1))

    def roundrect(self, x0, y0, x1, y1, r, fill, stroke=None, stroke_w=0):
        if stroke:
            self.raw("-fill", fill, "-stroke", stroke, "-strokewidth", "%.1f" % stroke_w,
                     "-draw", "roundrectangle %.1f,%.1f %.1f,%.1f %.1f,%.1f"
                     % (x0, y0, x1, y1, r, r))
        else:
            self.raw("-fill", fill, "-stroke", "none",
                     "-draw", "roundrectangle %.1f,%.1f %.1f,%.1f %.1f,%.1f"
                     % (x0, y0, x1, y1, r, r))

    def circle(self, cx, cy, r, fill):
        self.raw("-fill", fill, "-stroke", "none",
                 "-draw", "circle %.1f,%.1f %.1f,%.1f" % (cx, cy, cx + r, cy))

    def line(self, x0, y0, x1, y1, colour, width):
        self.raw("-fill", "none", "-stroke", colour, "-strokewidth", "%.1f" % width,
                 "-draw", "line %.1f,%.1f %.1f,%.1f" % (x0, y0, x1, y1))

    def text(self, x, y, s, colour, size, bold=False, anchor="c"):
        if not s:
            return
        font = BOLD if bold else FONT
        # No text metrics without a font library: estimate the advance width.
        width = len(s) * size * (0.60 if bold else 0.58)
        left = x - width / 2.0 if anchor == "c" else (x - width if anchor == "r" else x)
        payload = s.replace("\\", "\\\\").replace("'", "\\'")
        self.raw("-font", font, "-pointsize", "%.1f" % size, "-fill", colour, "-stroke", "none",
                 "-draw", "text %.1f,%.1f '%s'" % (left, y + size * 0.35, payload))


def render(base_path, canvas, out_path):
    args = ["convert", base_path] + canvas.ops + [out_path]
    subprocess.check_call(args, stderr=subprocess.STDOUT)


def plain(width, height, colour, out_path):
    subprocess.check_call(["convert", "-size", "%dx%d" % (width, height), "xc:%s" % colour, out_path])


def blur_band(src, band_path, out_path, x, y, w, h, sigma):
    """Blur one band of `src` and composite it back in, leaving the rest of the app sharp."""
    subprocess.check_call(["convert", src, "-crop", "%dx%d+%d+%d" % (w, h, x, y), "+repage",
                           "-blur", "0x%.1f" % sigma, band_path])
    subprocess.check_call(["convert", src, band_path, "-geometry", "+%d+%d" % (x, y),
                           "-composite", out_path])


# ---------------------------------------------------------------- icons (stand-ins)

def draw_icon(canvas, name, cx, cy, size, colour):
    """A geometric stand-in for the command's vector. NOT Google's artwork."""
    s = size / 2.0
    w = max(1.6, size * 0.09)

    def ring(r):
        canvas.raw("-fill", "none", "-stroke", colour, "-strokewidth", "%.1f" % w,
                   "-draw", "circle %.1f,%.1f %.1f,%.1f" % (cx, cy, cx + r, cy))

    def arc(x0, y0, x1, y1, start_deg, end_deg):
        canvas.raw("-fill", "none", "-stroke", colour, "-strokewidth", "%.1f" % w,
                   "-draw", "arc %.1f,%.1f %.1f,%.1f %.1f,%.1f"
                   % (x0, y0, x1, y1, start_deg, end_deg))

    if name == "more_tools":
        d = size * 0.20
        for dx in (-1, 1):
            for dy in (-1, 1):
                canvas.roundrect(cx + dx * s * 0.42 - d / 2, cy + dy * s * 0.42 - d / 2,
                                 cx + dx * s * 0.42 + d / 2, cy + dy * s * 0.42 + d / 2,
                                 d * 0.3, colour)
    elif name == "undo":
        r = s * 0.78
        arc(cx - r, cy - r, cx + r, cy + r, 20, 300)
        canvas.line(cx - r * 0.82, cy - r * 0.18, cx - r * 1.20, cy + r * 0.22, colour, w)
        canvas.line(cx - r * 0.82, cy - r * 0.18, cx - r * 0.44, cy - r * 0.02, colour, w)
    elif name == "select_word":
        h = s * 0.85
        canvas.line(cx, cy - h, cx, cy + h, colour, w)
        for dy in (-h, h):
            canvas.line(cx - size * 0.17, cy + dy, cx + size * 0.17, cy + dy, colour, w)
    elif name == "switch_clipboard":
        bw, bh = size * 0.34, size * 0.44
        canvas.roundrect(cx - bw, cy - bh * 0.72, cx + bw, cy + bh * 1.05, size * 0.08,
                         "none", stroke=colour, stroke_w=w)
        canvas.roundrect(cx - bw * 0.42, cy - bh * 1.05, cx + bw * 0.42, cy - bh * 0.55,
                         size * 0.05, colour)
    elif name == "config":
        ring(s * 0.40)
        ring(s * 0.85)
        import math
        for i in range(8):
            ang = math.pi * i / 4
            canvas.line(cx + math.cos(ang) * s * 0.40, cy + math.sin(ang) * s * 0.40,
                        cx + math.cos(ang) * s * 0.85, cy + math.sin(ang) * s * 0.85, colour, w)
    elif name == "switch_gif":
        canvas.text(cx, cy, "GIF", colour, size * 0.62, bold=True)
    elif name == "voice_typing":
        canvas.roundrect(cx - size * 0.20, cy - s * 0.90, cx + size * 0.20, cy + s * 0.20,
                         size * 0.20, colour)
        arc(cx - size * 0.38, cy - s * 0.50, cx + size * 0.38, cy + s * 0.95, 0, 180)
        canvas.line(cx, cy + s * 0.95, cx, cy + s * 1.28, colour, w)


# ---------------------------------------------------------------- main

def label_for(raw):
    if raw is None:
        return "", False
    glyphs = {"shift": "⇧", "backspace": "⌫", "enter": "↵", "space": "",
              "switch_emoji": "☺", "switch_numeric": "?123", "?123:switch_numeric": "?123"}
    if raw in glyphs:
        return glyphs[raw], False
    if raw.startswith("loc "):
        return "", False
    if ":" in raw:
        return raw.split(":", 1)[0], False
    return raw, len(raw) == 1


def main(argv):
    ap = argparse.ArgumentParser(add_help=True, description=__doc__.splitlines()[0])
    ap.add_argument("--theme", default="GboardDark")
    ap.add_argument("--layout", default=os.path.join("src", "main", "layouts",
                                                    "latn_qwerty_us_gboard_style.xml"))
    ap.add_argument("--themes", default=os.path.join("res", "values", "themes.xml"))
    ap.add_argument("--out", required=True)
    ap.add_argument("--opacity", type=int, default=80)
    ap.add_argument("--width", type=int, default=1080)
    ap.add_argument("--no-blur", action="store_true")
    a = ap.parse_args(argv)

    theme = parse_theme(a.themes, a.theme)
    for required in ("colorKeyboard", "colorKey", "colorLabel"):
        if required not in theme:
            raise SystemExit("%s has no %s" % (a.theme, required))

    dp = a.width / 411.0                     # Pixel 7 is 411dp wide
    width = a.width
    gap = float((theme.get("keyGap") or "0dp").replace("dp", "")) * dp
    radius = float((theme.get("keyBorderRadius") or "0dp").replace("dp", "")) * dp
    blur_dp = float((theme.get("blurRadius") or "0dp").replace("dp", ""))

    field = argb(theme["colorKeyboard"])
    key = argb(theme["colorKey"])
    strip_colour = argb(theme.get("colorSuggestionBar") or theme["colorKey"])
    # SuggestionBarPalette.contrastRatio ignores alpha, so it is handed the bar's raw RGB.
    bar_opaque = (255,) + strip_colour[1:]
    text_c, high_c, sec_c = resolve_bar_palette(theme, bar_opaque)

    rows = list(ET.parse(a.layout).getroot())
    heights = [float(r.get("height", "1.0")) for r in rows]
    keys_h = sum(h * KEY_ROW_DP for h in heights) * dp
    toolbar_h = TOOLBAR_DP * dp
    strip_h = STRIP_DP * dp
    kb_h = toolbar_h + strip_h + keys_h
    height = int(APP_DP * dp + kb_h)
    kb_top = int(height - kb_h)

    is_light = theme.get("android:isLightTheme", "false").lower() == "true"
    ink = argb(theme["colorLabel"])
    work = tempfile.mkdtemp(prefix="kbrender-")

    # 1. the app behind the keyboard: enough chat to make the glass read as glass, with the
    #    input row sitting directly on top of the keys as it does on device.
    app = Canvas()
    bg_colour = "#FFFFFF" if is_light else "#101114"
    app.rect(0, 0, width, height, bg_colour)
    app.rect(0, 0, width, 56 * dp, "#FFFFFF" if is_light else "#1A1B1E")
    app.circle(34 * dp, 28 * dp, 14 * dp, "#DCE3EA" if is_light else "#3A3C42")
    app.text(58 * dp, 28 * dp, "Ama", "#202124" if is_light else "#E8EAED", 17 * dp, bold=True,
             anchor="l")
    if is_light:
        them, me = "#EDEFF2", "#1A73E8"
        them_ink, me_ink = "#202124", "#FFFFFF"
        field_bg = "#EDEFF2"
    else:
        them, me = "#26282C", "#1A73E8"
        them_ink, me_ink = "#E8EAED", "#FFFFFF"
        field_bg = "#24262A"
    app.text(width / 2.0, 78 * dp, "Today", "#5F6368" if is_light else "#9AA0A6", 12 * dp)
    for y_dp, text, (box, bubble_ink, right) in (
        (100, "Are we still on for 7?", (them, them_ink, False)),
        (152, "Yes — see you there", (me, me_ink, True)),
        (204, "Perfect. I'll bring the prints.", (them, them_ink, False)),
    ):
        w = len(text) * 15 * dp * 0.60 + 32 * dp
        x0 = width - 16 * dp - w if right else 16 * dp
        app.roundrect(x0, y_dp * dp, x0 + w, (y_dp + 46) * dp, 16 * dp, box)
        app.text(x0 + 16 * dp, (y_dp + 23) * dp, text, bubble_ink, 15 * dp, anchor="l")
    top = (APP_DP - 66) * dp
    app.roundrect(16 * dp, top, width - 16 * dp, top + 54 * dp, 24 * dp, field_bg)
    app.text(36 * dp, top + 27 * dp, TYPED, "#202124" if is_light else "#E8EAED", 16 * dp,
             anchor="l")
    app.text(36 * dp + len(TYPED) * 16 * dp * 0.58 + 2 * dp, top + 27 * dp, "|", "#1A73E8",
             16 * dp, anchor="l")
    app.circle(width - 44 * dp, top + 27 * dp, 20 * dp, "#1A73E8")
    app.text(width - 44 * dp, top + 27 * dp, "➤", "#FFFFFF", 15 * dp)
    blank = os.path.join(work, "blank.png")
    plain(width, height, bg_colour, blank)
    appshot = os.path.join(work, "app.png")
    render(blank, app, appshot)

    # 2. blur the part the keyboard covers, the way blurRadius does
    if a.no_blur or blur_dp <= 0:
        bg = appshot
    else:
        bg = os.path.join(work, "bg.png")
        blur_band(appshot, os.path.join(work, "band.png"), bg, 0, kb_top, width,
                  int(kb_h), blur_dp * dp * 0.42)

    # 3. the keyboard itself
    kb = Canvas()
    kb.rect(0, kb_top, width, height, alpha_rgba(field))
    kb.rect(0, kb_top, width, kb_top + toolbar_h, alpha_rgba(strip_colour, a.opacity))
    kb.rect(0, kb_top + toolbar_h, width, kb_top + toolbar_h + strip_h,
            alpha_rgba(strip_colour, a.opacity))

    icon_tint = alpha_rgba(text_c, 235)
    cell = width / float(len(TOOLBAR_COMMANDS))
    for i, name in enumerate(TOOLBAR_COMMANDS):
        draw_icon(kb, name, cell * (i + 0.5), kb_top + toolbar_h * 0.5,
                  TOOLBAR_ICON_DP * dp, icon_tint)

    sx = STRIP_PAD_DP * dp
    sy = kb_top + toolbar_h
    size_px = SUGGESTION_SP * dp
    # Gboard draws every candidate the same colour and marks the best one by weight; the
    # `secondary` role is for dictionary/prompt rows, not for ordinary candidates.
    for i, word in enumerate(SUGGESTIONS):
        colour = alpha_rgba(high_c if i == 0 else text_c, 255)
        w = len(word) * size_px * (0.60 if i == 0 else 0.58) + 2 * CHIP_PAD_DP * dp
        kb.text(sx + w / 2.0, sy + strip_h * 0.5, word, colour, size_px, bold=(i == 0))
        sx += w + CHIP_MARGIN_DP * dp

    y = kb_top + toolbar_h + strip_h
    key_w = (width - 0) / 10.0
    for ridx, row in enumerate(rows):
        rh = heights[ridx] * KEY_ROW_DP * dp
        x = 0.0
        for k in row:
            w = float(k.get("width", "1.0")) * key_w
            x += float(k.get("shift", "0.0")) * key_w
            x0, y0 = x + gap / 2.0, y + gap / 2.0
            x1, y1 = x + w - gap / 2.0, y + rh - gap / 2.0
            kb.roundrect(x0, y0, x1, y1, radius, alpha_rgba(key))
            c = k.get("c")
            lbl, single = label_for(c)
            cx, cy = (x0 + x1) / 2.0, (y0 + y1) / 2.0
            # Same size the view computes, so the preview's proportions are the app's.
            label_base = min(rh - KEY_VERTICAL_MARGIN_DP * dp,
                             (w - KEY_HORIZONTAL_MARGIN_DP * dp) * 1.5) * CHARACTER_SIZE
            main_px = label_base * LABEL_TEXT_SIZE_FACTOR
            sub_px = label_base * SUBLABEL_TEXT_SIZE_FACTOR
            if c == "space":
                kb.line(x0 + (x1 - x0) * 0.30, cy, x1 - (x1 - x0) * 0.30, cy,
                        alpha_rgba(sec_c, 200), 2.5 * dp)
            elif lbl:
                # The app paints letters with the system sans at normal weight; medium reads
                # closest in ImageMagick's DejaVu substitute (bold is too heavy at this size).
                kb.text(cx, cy, lbl, alpha_rgba(ink), main_px, bold=False)
            # Corner hint: one symbol in the north-east, exactly what CornerLabelPolicy
            # prints in calm mode, and only when the key types a character.
            hint = k.get("ne")
            if single and hint and not hint.startswith("loc "):
                pad = 5.0 * dp
                kb.text(x1 - pad, y0 + pad + sub_px * 0.62, hint, alpha_rgba(sec_c),
                        sub_px, bold=False, anchor="r")
            x += w
        y += rh

    render(bg, kb, a.out)
    print("wrote %s  (%s, %dx%d)" % (a.out, a.theme, width, height))



if __name__ == "__main__":
    main(sys.argv[1:])
