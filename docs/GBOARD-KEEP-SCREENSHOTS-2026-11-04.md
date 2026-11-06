# The five Keep screenshots (2026-11-04) — what was wrong and what changed

Owner's report, in his words: *"the more tools doesn't even work, it only writes it, only the
clipboard tool works… Part of the keyboard disappears when I type something… Gboard enter key is
blue and short and not too long."*

Screenshot 2 of the set (the translucent keys over a photo, `English` on the space bar, blue round
enter) is **Gboard**, not this app — the owner confirmed it and it is the reference. Screenshots
1, 3, 4, 5, 6 and 7 are this app.

## 1. Tapping the four-dot button typed `more_tools` into the note

*Symptom:* the note contains `more_tools`, twice, and the search box contains
`amore_toolsmore_tools`.

*Cause:* `KeyValue.getKeyByName` is **total** — an unrecognised name comes back as a String key
that spells the name. `CustomShortSwipeExecutor.executeRegistryCommand`'s fallback branch committed
whatever String key it got, so any catalogue command the executor does not implement was *typed*.
`more_tools` is exactly that: it is handled in `Keyboard2View`'s custom-command block, which only
runs when the executor returns false.

*Fix:* `CommandRouting.isOwnNameFallback(name, kv)` — the rule `CommandRouting` already used for
the key pipeline — now also gates the executor's commit. A command that resolves to its own name
is deferred to the keyboard view; anything nothing knows becomes a no-op instead of text in the
user's document.

## 2. "Part of the keyboard disappears when I type something"

*Symptom:* as soon as the strip carries words, the toolbar keeps only the grid and the mic and
five buttons vanish, leaving a mostly empty band.

*Reference:* the Gboard screenshot keeps every icon in the row while typing.

*Fix:* compact mode is no longer wired (`CleverKeysService` stopped calling
`KeyboardToolbar.setCompactMode`). The row always shows the configured buttons; a crowded row
scrolls instead of hiding anything. The class and its pure policy remain for a future single-row
header.

## 3. Enter key: long, flat, not blue

*Reference:* Gboard's enter is **one key wide**, a filled blue disc with a white glyph.

*Fix:* three parts.
- New theme attributes `colorKeyEnter` / `colorLabelEnter`. Unset = the old SPECIAL fill and the
  normal label colour, so every pre-existing theme renders identically; `GboardLight` and
  `GboardDark` set `#1A73E8` and white.
- A new `KeyRole.ENTER` picks that frame, and `Keyboard2View` draws it with a disc radius
  (`min(keyW, keyH) / 2`) instead of the theme's 14 dp corner.
- The layout's enter key is `width="1"` (was 1.5) and the space bar gained the half key
  (`width="4.5"`), keeping the row at its pinned total of 10 key units.

## 4. Space bar was blank

*Reference:* Gboard prints the language it is typing — `English`.

*Fix:* `LanguageDisplayNames.languageLabel(code, uiLocale)` (bare name, no region, no endonym
parentheses) and `Keyboard2View.drawSpaceLanguageLabel`, cached per language tag because it runs
every frame. Drawn in the sub-label grey at 1.25× the sub-label size.

## 5. Corner hints sat in the gap above their key

*Symptom:* the `%`, `@`, `*` hints looked like they belonged to the row above.

*Cause:* `keyPadding` is 2 dp and is measured from the **cell** edge, while `drawKeyFrame` insets
the tile by another half `keyGap`. The hint therefore straddled the tile's rounded top corner.

*Fix:* both sub-label paths inset by `keyPadding + gap / 2`, so the hint sits inside the shape it
belongs to.

## 6. INTERNET permission (owner instruction)

`android.permission.INTERNET` is now declared, with the reason recorded in the manifest: the mic
key hands audio to the device's speech recogniser, and a recogniser that proxies recognition under
the calling app's UID needs the caller to hold it. What did **not** change:

- no source file references a network API (`ReleasePackagingDriftTest.productionSourcesCallNoNetworkApi`);
- `ACCESS_NETWORK_STATE` and `ACCESS_WIFI_STATE` remain forbidden;
- audio leaves the device only if the user's chosen recogniser is a cloud one.

`scripts/verify-production-ready.sh`, `README.md` and the drift test were updated in the same
commit so the claim and the check cannot drift apart.

## Still open after this change

- Suggestions row has no background of its own in the light theme (words float on the app).
- `GboardDark` is untuned against the reference screenshot.
- Key corner radius (14 dp) reads rounder than Gboard's tiles.
- The mic key's recognition path is still unproven on a real device; the bracketed reason in the
  status line is the diagnostic.

---

# Round 2 — the suggestion strip (2026-11-04, after "tackle the rest")

Reference: Gboard, i.e. screenshot 2 of the owner's set, plus Google's own description of the
feature (`9to5google.com/2021/08/13/gboard-suggestion-highlight/`): the most likely word sits in
the **centre** of the strip, drawn on a box **hued with the return-key accent** — the same blue as
the enter key — so it is unmistakable among the others.

What this keyboard did: four grey words at the same weight, the "highlight" applied as a *text
colour* borrowed from `colorLabelActivated`. On the Gboard themes that colour is the same ink as
the other words, so there was no visible highlight at all — the owner's screenshot shows four
identical words.

What changed:

- **`SuggestionPillPolicy`** (new, pure): decides whether the accent pill is drawable at all.
  No accent, an unknown bar colour, or an accent that matches the bar (< 1.2 contrast) means "bold
  text only", i.e. the old behaviour rather than an invisible smudge. On a drawable accent the pill
  takes white ink, or black when the accent is pale.
- **`SuggestionBar`**: the commit suggestion is now bold (it asked for `Typeface.NORMAL` before, so
  even the weight was not being applied) and sits on a rounded pill filled with
  `colorKeyEnter` — the return-key accent the enter-key work introduced, which is exactly the
  colour Gboard uses. Pooled chips reset their background, padding and typeface on every bind, so
  a recycle cannot leave a stale pill behind.
- **`Theme` / `themes.xml`**: both Gboard variants now give the strip the keyboard's own field
  colour (`#F2E7EAEE` light, `#C4141619` dark) instead of a separate lighter band. Gboard's strip
  is one surface with the keyboard, not a second row floating under the toolbar.

Still different from Gboard, and deliberately not changed blind: Gboard centres the most likely
word in the middle slot; this bar keeps the ranked order and highlights the first entry, because
the highlighted entry is exactly what the space bar commits and re-ordering the display would
change which word a space press produces. That needs a decision, not a guess.

---

# Round 3 — measured against the owner's Gboard, 2026-11-05

New set in `Updated comparison/` (eleven screenshots, all this app; the Gboard reference is the
dark one from the previous set, kept in git history). The owner's instruction was to compare them
exactly rather than by eye, so this round starts from pixel measurements at the same 1080 px
width, on the same 10-column key pitch (88 px per cell):

| measurement | this keyboard (2026-11-05) | Gboard | change |
|---|---|---|---|
| letter `W` glyph width on an 88 px tile | 42 px | 64 px | label factor 0.36 → **0.48** |
| corner-hint size | 0.13 of key height | ~1.3× ours | sub-label factor 0.13 → **0.17** |
| tile corner arc | ~12 dp (blob on a 33 dp key) | ~5 dp | `keyBorderRadius` 14 dp → **6 dp** |
| hint inset from the tile edge | 2 dp (inside the rounded corner) | ~5 dp | `key_padding` 2 dp → **4 dp** |
| suggestion strip | opacity-multiplied band, visibly darker than the field | the keyboard surface itself | **`suggestionBarMatchesKeyboard`** |

The strip fix is a new theme attribute: when set, the bar paints `colorKeyboardBackground` at full
alpha instead of `suggestionBackgroundColor` × the user's bar opacity. Multiplying the theme colour
by 80 % can never land on the field colour (it would need 119 % alpha), which is why the old
comment "chosen to LAND on the field" was arithmetically impossible and the band always showed.
Off by default, so every other theme renders exactly as before.

Not changed, and why: the emoji search pane (screenshot 8) shows a large empty area after a few
results — that is a layout question of its own, not a Gboard-parity delta, and there is no Gboard
emoji screenshot in the set to measure it against.
