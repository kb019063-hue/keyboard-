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
