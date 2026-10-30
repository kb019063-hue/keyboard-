# Owner decisions — 2026-10-26

This file is the handoff for the next person, human or agent. The owner asked for these
things by name. Do not undo them because an older screenshot, an older default, or an
upstream CleverKeys habit disagrees.

## Install the current app

The phone installs the **debug** APK. It upgrades in place (same debug keystore, application
id `tribixbite.cleverkeys.debug`). Do not tell the owner to uninstall first.

Direct download, any phone from about 2017 onward:

https://github.com/kb019063-hue/keyboard-/releases/download/dev-builds/keyboard-arm64-v8a.apk

The rolling release page, which also has the 32-bit and emulator builds, is
https://github.com/kb019063-hue/keyboard-/releases/tag/dev-builds

That URL is overwritten by every green `Build APK` run. The file names stay the same.
`keyboard-armeabi-v7a.apk` is the old 32-bit phone. `keyboard-x86_64.apk` is the emulator.
GitHub Actions is the compiler: this sandbox cannot build an APK.

## The layout must match the Gboard screenshot

The reference is the owner's own Gboard (Gboard 16 on Android, portrait, number row ON)
and it is described here in words because the on-device screenshots that used to sit in
the tree are stale and have been removed:

- one toolbar row of seven commands, mic on the right
- a plain `1234567890` row
- lowercase QWERTY, with Gboard's single corner hint per letter
- bottom row `?123`, comma, emoji, space, period, action/enter
- keys are separate rounded tiles with a visible gap, not one continuous slab

The "before" state was the dense PC-style layout (Ctrl/Fn bottom row, symbols on every
letter); it is not the target.

Shipped arrangement, file `src/main/layouts/latn_qwerty_us_gboard_style.xml`:

- one toolbar of seven commands, mic last
- a plain `1234567890` row
- lowercase QWERTY with no printed corner clutter
- bottom row `?123`, comma, emoji, space, period, enter

English subtypes in `res/xml/method.xml`, including `en-GB` and `en-GH`, name that layout.
`en-GB` still carries `£`. `en-GH` carries `₵`. When no subtype resolves,
`KeyboardComponentGraph` loads `R.raw.latn_qwerty_us_gboard_style`, **not** the dense
`latn_qwerty_us`. `tools/check_layout.py` fails `preBuild` if that chain drifts.

An upgraded install whose `layouts` preference is still exactly `["latn_qwerty_us"]` or
`["latn_qwerty_gb"]` is re-pointed once by `GboardDefaultLayoutMigration`. The marker key
is `gboard_default_layout_migration_v1`. Never rename it. A custom layout, a second layout,
or any other named choice is left alone.

Not copied from the photograph, on purpose: Google's icons and artwork, the word "English"
on the space bar (that key must type a space), and a hardcoded search magnifier (the action
key already swaps when the field is a search field).

## The owner rejected the purple default

They called the deep-purple theme AI slop and asked for blue, or a cool red, and for more
than two themes they can switch between. Purple is still in the picker as
"Mr. Brown's Keyboard Dark". It is no longer the default.

`Defaults.THEME` is `harbor`. An install still stored as `cleverkeysdark` moves to Harbor
once (`ThemeDefaultMigration`, marker `theme_harbor_default_v1`). Never rename that marker.
Any other saved theme is left alone.

| Id | Name | What it is |
|---|---|---|
| `harbor` | Harbor | Night navy, white letters. **Default.** |
| `daybreak` | Daybreak | Cool daylight blue, white keys. |
| `signal` | Signal | Black field, electric-blue letters, light key when pressed. |
| `ember` | Ember | Charcoal keys with a lacquer-red edge. Not a flat red fill. |
| `kiln` | Kiln | Warm clay paper. Red only on the pressed key. |

A new built-in theme is not done until all of these agree: `res/values/themes.xml`,
`Config.builtInThemeStyleId`, `ThemeProvider.BUILT_IN_THEME_IDS`,
`ThemeProvider.getBuiltInColorScheme`, `ThemeSettingsActivity.BUILTIN_THEMES`, and a
`theme_builtin_*` string pair. `LayoutThemeBindingConfigTest` fails if the two id lists
diverge. `CustomThemePrefPolicy.FALLBACK_SWIPE_TRAIL_COLOR` must match Harbor's trail
(`0xFF9CC7FF`) while Harbor is the fallback id.

The comparison sheet (`docs/theme-wardrobe.png`) used to be checked in; it is a stale
render, is no longer in the tree, and is regenerated on demand with
`python3 tools/render_theme_wardrobe.py`. The coloured enter key in that render is the
press/accent colour, drawn so the accent is visible. On device the enter key uses the same
resting colour as the other keys until it is pressed, except Ember and Signal, whose
coloured edges are real borders.

## Bugs already fixed — do not reintroduce

Full write-up: [`BUG-AUDIT-2026-10-25.md`](BUG-AUDIT-2026-10-25.md).

The one that looked like "the toolbar does nothing": `KeyboardToolbar.createIconButton`
used to make the inner `ImageView` clickable, so it ate `ACTION_UP` and the wrapper's
click never fired. The wrapper owns the click. The icon is not clickable.

The offline smoke fake (`scripts/ci/offline/fakeadb.py`) must model **seven** toolbar
buttons with the mic last. The old ten-button model made the harness test the wrong
geometry. `scripts/ci/emulator-smoke.sh` uses `TB_N=7`.

`focus_activity()` in the smoke script exists because an emulator launcher ANR killed
Quickstep and left focus checks failing with no dialog to dismiss. Do not delete it
because a green run no longer shows that failure.

## Where the design notes live

- Layout and Gboard alignment: [`PHASE4-POLISH-DESIGN.md`](PHASE4-POLISH-DESIGN.md) parts 5, 7, 8, 9.
- Agent conventions, including the layout and theme rules: [`../AGENTS.md`](../AGENTS.md).
- CI storage limits: [`CI-STORAGE.md`](CI-STORAGE.md).
