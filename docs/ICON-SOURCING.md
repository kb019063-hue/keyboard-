# Where the toolbar icons come from (and where Gboard's do)

_2026-11-04. Written after the owner asked "who knows whether some great guy has them or
similar or has info"._

## Short answer

Gboard's toolbar icons are **Material Symbols** — Google's own open-source icon set. So the
"great guy" is Google, and the licence is Apache-2.0, which we can ship with attribution.
Nothing had to be copied out of the Gboard APK, and nothing was: the seven toolbar glyphs in
`res/drawable/ic_tool_*.xml` are path data from
[google/material-design-icons](https://github.com/google/material-design-icons) with a
NOTICE entry, plus one hand-drawn glyph where the set has no match (below).

## What Gboard's toolbar actually holds

Confirmed against the owner's Gboard 16 screenshot and the write-ups that describe the same
row (9to5Google, "How to customize your Gboard toolbar and shortcuts", 2023-06-02;
Android Police, "I was using Gboard wrong", 2026-01-25 — both describe the four-square grid
on the left, undo, text editing, clipboard, settings and the mic on the right):

| Slot | Gboard draws | We draw |
| --- | --- | --- |
| 1 | `grid_view` — four rounded squares, opens the tool drawer | `ic_tool_more` = Material Symbols `grid_view` |
| 2 | `undo` | `ic_tool_undo` = Material Symbols `undo` |
| 3 | text editing — an I-beam with a triangle each side | `ic_tool_select`, **hand-drawn** (see below) |
| 4 | clipboard with lines (`assignment`) | `ic_tool_clipboard` = Material Symbols `assignment` |
| 5 | `settings` gear | `ic_tool_settings` = Material Symbols `settings` |
| 6 | translate (two overlapping plates, `G` + 文) | `ic_tool_gif` = Material Symbols `gif_box` — we have GIF, not translate |
| 7 | `mic` knocked out of a filled circle | `ic_tool_mic` = Material Symbols `mic` + a disc, `fillType="evenOdd"` |

`redo` is Material Symbols `redo`, mirrored by the platform (`android:autoMirrored`).

## The one glyph the set does not have

Material Symbols has `text_select_start`, `text_select_end`,
`text_select_move_forward_character` (I-beam with ONE arrow) and friends, but nothing with a
triangle on both sides. Gboard's is custom. So `ic_tool_select` is drawn here from four
rectangles and two triangles on the 960-unit Symbols grid — our own geometry matching
Gboard's shape, not Google artwork.

## Licence and attribution

* Source: <https://github.com/google/material-design-icons> (Material Symbols / Material
  Icons), Copyright Google LLC, **Apache License 2.0**.
* Attribution lives in `NOTICE` ("Material Symbols / Material Icons — toolbar and key
  artwork"). Apache-2.0 is compatible with this project's GPLv3.
* The pre-existing dialog icons (`ic_content_copy`, `ic_delete`, `ic_clipboard_*.xml`,
  `ic_check_box_*.xml`, `ic_expand_more`, `ic_clear_all`, `ic_media_*`) are from the same
  set and are covered by the same NOTICE entry.

## What we do NOT ship

* No Gboard APK resource, no Google Sans, no `Gboard` logo, no translate icon of theirs.
* No Material font file or stylesheet — only vector path data.
* "Gboard-style" in this repo means an arrangement and a look; it does not imply affiliation
  with or endorsement by Google.

## How to pull another icon

```bash
ic=assignment
curl -s "https://api.github.com/repos/google/material-design-icons/contents/symbols/web/$ic/materialsymbolsrounded/${ic}_24px.svg" \
  | python3 -c "import json,sys,base64;print(base64.b64decode(json.load(sys.stdin)['content']).decode())"
```

The SVG's `viewBox` is `0 -960 960 960`, i.e. **y runs upwards**. Paste the path into a
`<vector android:viewportWidth="960" android:viewportHeight="960">` wrapped in
`<group android:translateY="960">` and it lands upright. `--` is illegal inside an XML
comment, and the Symbols grid means `android:width/height="24dp"` is the natural size — the
toolbar draws it at 22 dp to match Gboard's optical weight (`KeyboardToolbar.ICON_SIZE_DP`).

## Checking it without a phone

`python3 tools/render_layout_preview.py --theme GboardLight --out /tmp/kb.png` now rasterises
the *shipped* `ic_tool_*.xml` path data into the toolbar strip, so the preview shows the same
glyphs the app draws instead of stand-ins.
