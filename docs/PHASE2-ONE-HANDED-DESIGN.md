# Phase 2 — One-handed mode: design

Status: **proposed, awaiting approval.** No code written yet.

Goal: shrink the keyboard and hug it to one edge of the screen so it can be reached with one
thumb, with a control to flip sides and a way out that does not require opening Settings.

Gap analysis context: HeliBoard has 14 files touching one-handed mode, CleverKeys has zero.
It is the smallest of the three view modes (one-handed, floating, split) and the biggest
ergonomic win, which is why it is phase 2.

## What HeliBoard does, and what is worth copying

`app/src/main/java/helium314/keyboard/latin/KeyboardWrapperView.kt` (166 lines, GPL-3.0-only)
is a `FrameLayout` wrapping the keyboard. It keeps `oneHandedModeEnabled` and
`oneHandedGravity` (`Gravity.LEFT`/`RIGHT`), and overrides `onLayout`: when enabled it computes
`spareWidth = width - keyboardView.measuredWidth` and lays the keyboard out at `keyboardLeft = 0`
for left gravity or `spareWidth` for right. Three `ImageButton`s (stop, switch side, resize) are
laid out in the spare strip at 20%, 50% and 80% of keyboard height; the switch button is mirrored
with `scaleX = -1f` when gravity is left; the resize button is a drag gesture that writes a scale
preference clamped to 0.5–2.5. Stop and switch dispatch `KeyCode.TOGGLE_ONE_HANDED_MODE` and
`SWITCH_ONE_HANDED_MODE` through the `KeyboardActionListener`.

The part worth copying is the **interaction design**: enabled/side/scale as three independent
values, a visible exit control in the gap, and side-flipping as a mirrored arrow.

The part worth *not* copying is the **mechanism**. HeliBoard's keyboard is genuinely re-measured
narrower, which is why its wrapper needs a custom `onLayout` — and that re-measure lives in
`KeyboardBuilder`/`KeyboardSwitcher`, the layout engine. Porting it here would mean editing the
code that computes every key's rectangle, in a tree whose layout data comes from XML and a
generated compose table. That is the highest-risk surface in the project, and phase 5's layout
converter would then be building on modified ground.

## Proposed mechanism: a transform, not a re-measure

Apply `scaleX` with a `pivotX` of `0f` (left) or `view.width` (right) to the keyboard's root
container. Layout bounds are untouched, so:

- **Key geometry is untouched.** Every key rectangle, every hit-test, the swipe engine's
  coordinates and `KeyboardDimensionsHelper`'s height maths all keep working on the untransformed
  layout. Nothing in `Keyboard2View` needs to know one-handed mode exists.
- **Touch still lands correctly.** `ViewGroup.dispatchTransformedTouchEvent` applies the inverse
  of the child's matrix before handing the event down, so a tap on a visually-shrunk key arrives
  as the same local coordinate it would have unscaled. This is platform behaviour, not something
  this project has to implement — but it is the single assumption the whole design rests on, so
  it gets an explicit on-device check before anything else is built on top (see Verification).
- **The gap shows the app behind.** The IME window keeps its full width and its theme is
  transparent (`res/layout/keyboard.xml` documents that overdraw is suppressed *because* the
  window theme is transparent), so the unused strip is see-through rather than a coloured block.
  Needs confirming for whichever container ends up scaled, since a background on that container
  would fill the gap.

Cost: one small controller class and no changes to the layout engine. Risk concentrates in a
place that can be checked in isolation.

## The one real design choice: what shrinks

`inputViewContainer` is a vertical `LinearLayout` holding, in order, the phase-1 toolbar,
`topPane` (suggestion bar / content pane) and `keyboardView`.

- **(A) Keyboard only** — matches HeliBoard. Suggestions and toolbar stay full width and remain
  fully legible, but they then span the empty gap, which looks unfinished.
- **(B) The whole container** — toolbar, suggestions and keys shrink together and hug one edge as
  a single block. Cohesive, one transform, and the toolbar stays aligned with the keys it sits
  above. At 85% everything is still comfortably readable; below ~70% suggestion text gets small.

**Recommendation: (B)**, with the scale range limited to 70–100% rather than HeliBoard's 50–2.5,
precisely because suggestion text has to stay legible. Default 85%.

## Controls

Three preferences, following the `ToolbarConfig` pattern:

| Preference | Type | Default | Notes |
|---|---|---|---|
| `one_handed_enabled` | bool | `false` | Off by default; never surprises an existing install |
| `one_handed_side` | string `left`/`right` | `right` | Right-handed default |
| `one_handed_scale` | int percent | `85` | Clamped 70–100 on read, as `ToolbarConfig.parse` clamps |

Two new `CommandRegistry` entries, so phase 1's toolbar can drive this — the payoff the gap
analysis predicted:

| Command | Category | Symbol | Behaviour |
|---|---|---|---|
| `one_handed_toggle` | `SYSTEM` | `1H` | Enable ↔ disable |
| `one_handed_side` | `SYSTEM` | `1H⇄` | Flip left ↔ right |

Both declare an explicit `symbol` of ≤4 characters. That matters: `getDisplayInfo` falls back to
`command.symbol ?: commandName.take(4)`, and without a symbol the toolbar button would read
`one_`. Symbols are registry data, not UI strings, so they carry no translation burden.

**Exit affordance.** A toolbar button is not enough on its own: if a user enables one-handed mode
and has not put the toggle on their toolbar, they are stuck with a shrunken keyboard and must find
Settings to escape. So, as in HeliBoard, a small stop control is drawn in the gap itself — one
button, not HeliBoard's three, since side-flip and scale are available from the toolbar and
Settings. This is the one piece of new drawing in the phase.

## Where it hooks in

Every seam below already exists and was exercised by phase 1, so this is a known path rather than
an exploration:

1. **Dispatch** — `Keyboard2View.onCustomShortSwipe` already has a `when (actionValue)` block for
   view-level commands (`primaryLangToggle`, `secondaryLangToggle`, `showTextMenu`). The two new
   commands go there, next to their precedent. This avoids the alternative of adding a
   `KeyValue.Kind.Event`, which would ripple into the event enum and `XmlAttributeMapper`.
2. **State** — `Config.kt` `Defaults` + fields + `refresh()`, exactly as `toolbar_enabled`/`toolbar_keys`.
3. **Live refresh** — `saveSetting` → prefs → `Config.refresh()` → `PreferenceUIUpdateHandler` →
   a new `updateOneHanded()`. Changing side or scale in Settings applies to the open keyboard with
   no IME restart, which is already proven for the toolbar.
4. **Construction** — `SuggestionBarPane.initialize` in `wiring/KeyboardComponentGraph.kt` built
   the toolbar; it builds the controller the same way, returned through `InitializationResult` →
   `PredictionViewSetup.SetupResult` → `CleverKeysService`.
5. **Settings UI** — a new `OneHandedSection.kt` plus the five-point plumbing
   (`SettingsViewModel` expanded flag, `SettingsActivity` state + delegate, `SettingsPersistence`
   load, `SettingsScreen` registration, and both `SettingsSearch` `when(sectionKey)` maps —
   omitting the second of those silently makes the search result scroll nowhere).
6. **Backup parity** — the three keys go in `backup/SettingsDefaults.kt`.

Estimated footprint: ~2 new files, ~12 edited, no change to the layout engine.

## Verification

Split three ways, because these surfaces are not equally observable — the same lesson phase 1
taught twice.

1. **JVM unit test on the transform maths.** Side + scale + measured width → expected `scaleX`,
   `pivotX` and clamp behaviour. Pure arithmetic, runs in the sandbox with no emulator, and it is
   where a sign error (left/right swapped, pivot at the wrong edge) would otherwise hide until
   someone typed on a phone.
2. **Smoke §7c: the Settings screen.** `SettingsActivity` is an ordinary app window, so the enable
   switch, side dropdown and scale slider are all assertable by accessibility dump, reusing the
   safe-band repositioning and dump-based ANR detection that §7b now has.
3. **The shrunken keyboard itself is not assertable.** `uiautomator dump` does not capture
   input-method windows — phase 1 reports `toolbar=unverifiable` for exactly this reason, and
   one-handed mode is worse, since the effect is a transform with no text and no resource-id to
   match. The screenshot already captured as smoke evidence is the arbiter, and the touch-mapping
   assumption gets confirmed by hand on a real device before this is called done.

## Open questions

1. Choice (A) keyboard-only or **(B) whole container** — recommendation B.
2. Scale range 70–100% (recommendation) or HeliBoard's wider 50–100%.
3. Draw the stop control in the gap (recommendation yes), or rely on toolbar + Settings only.
4. Whether to add a drag-to-resize handle now or defer it. Recommendation: **defer** — it needs a
   touch listener competing with the keyboard's own, and scale is already settable in Settings.

## As shipped

All four open questions were resolved as recommended: **(B)** whole-container shrink, a stop
control drawn in the gap, drag-to-resize deferred, and a 70–100% range defaulting to 85.

Files added: `OneHandedConfig.kt` (layout maths, deliberately free of `android.*` imports),
`OneHandedRoot.kt` (the `FrameLayout` wrapper and its stop control),
`ui/settings/sections/OneHandedSection.kt`, and `OneHandedConfigTest.kt`. Files wired:
`Config.kt`, `CleverKeysService.kt`, `Keyboard2View.kt`, `PreferenceUIUpdateHandler.kt`,
`CustomShortSwipeExecutor.kt`, `CommandRegistry.kt`, `SettingsViewModel.kt`,
`activities/SettingsActivity.kt`, `ui/settings/SettingsPersistence.kt`, `SettingsScreen.kt`,
`SettingsSearch.kt`, `backup/SettingsDefaults.kt`, `res/values/strings.xml`, and
`scripts/ci/emulator-smoke.sh` (§7c).

Six things the implementation taught that the design did not anticipate:

1. **The two commands had to be listed in `CustomShortSwipeExecutor`'s view-level branch, or they
   would have typed their own names.** `KeyValue.getKeyByName` is total: an unrecognised name falls
   through `KeyValueParser.parse` and comes back as `makeStringKey(name)`. The executor's `else`
   branch then commits that string, so an unlisted `one_handed_toggle` would have inserted the
   literal text `one_handed_toggle` into whatever the user was writing — and returned `true`, which
   suppresses the `when (actionValue)` block in `Keyboard2View` that was supposed to handle it.
   `CommandRoutingTest.everyCommandWithoutAKeyIsHandledByName` exists precisely to catch this, and
   it scans the executor and `Keyboard2View` for the name as a literal; both now carry it.

2. **They are registered under `Category.EVENTS`, not `SYSTEM` as the design said.** Placed
   directly after `hide_keyboard`, beside `minimize_bar` and `minimize_fab`: all three change the
   keyboard's *shape* rather than what it types, and the palette groups by category.

3. **A generic preference writer silently reddens `SettingsSurfaceDriftTest`.** Its F-8 check
   resolves `const val` names to find each key's writers, so a `writePref(key, value)` helper reads
   as having none and the key is reported as live-but-unwritable. Both writers now name
   `OneHandedConfig.PREF_ENABLED` / `PREF_SIDE` at the call site, and `PREF_SCALE` is written by
   the section's slider. (`one_handed_enabled` initially appeared to pass for the wrong reason:
   `PREF_ENABLED` is also `ToolbarConfig`'s constant name, and the scan resolves names, not
   owners.) This is also why the settings section shipped in the same commit as the keyboard
   support — without it, `one_handed_scale` had no writer anywhere.

4. **`gapWidth` uses integer arithmetic.** `(contentWidth * (1f - scaleFraction(p))).toInt()`
   returns **161** for 85% of a 1080px keyboard, not 162, because `1f - 0.85f` lands just below
   0.15 and `toInt()` truncates. One pixel nobody would see, but `contentWidth * (100 - p) / 100`
   is exact and the test can state it without a tolerance.

5. **The theme is read through a provider, not captured.** `OneHandedRoot` deliberately outlives
   the keyboard view, so a captured `Theme` would keep colouring the stop control with the palette
   the user had just switched away from — the same trap `PreferenceUIUpdateHandler`'s header
   documents. It takes `themeProvider: (() -> Theme?)?` and the service supplies
   `_keyboardView.getTheme()` behind a `lateinit` guard.

6. **The stop control's gravity is set at `addView`, not left to `applyState`.** Assigning
   `layoutParams` calls `requestLayout`, and `applyState` runs from inside `onLayout`, so a first
   layout pass that had to correct the gravity would request another pass from within the one it is
   in. `stopGravity()` is now shared by both.

Two notes on the minimized keyboard, which must never be scaled: shrinking a minimized bar or
floating button makes it *harder* to tap, the opposite of the feature's purpose. `setInputView`
sets `scaleSuppressed = _minimizer.style != null`, and `applyState` resets the transform when it is
set. `isActive` accounts for it too, so diagnostics report the truth.

### §7c, and the bug the offline harness caught

`uiautomator` cannot see the shrunken keyboard (no IME windows, and a transform exposes no text or
resource-id), so §7c asserts the settings surface instead: the header renders, expanding composes
the switch, **the side and width controls are absent while it is off**, turning it on reveals them,
and the value reaches device-protected storage (best-effort — `run-as` does not always reach
`/data/user_de/0`, and a harness limit must not sink a run whose UI assertions passed).

The header reads `One-Handed Mode` and the switch row `One-handed mode`. `nodecenter` matches
case-insensitively and returns the first hit in document order, and the header sits *above* the
switch — so an unanchored pattern resolved to the header and tapping it **collapsed the section**
instead of flipping the switch. The offline fake-adb harness caught this: it resolves each tap
against the dump it last served, and reported both taps landing on the header. The switch lookup is
now anchored `^One-handed mode$`, which the emoji-prefixed header cannot match. All seven harness
scenarios pass, including the three failure paths (`no-header`, `no-switch`, `no-width`).

Still to be confirmed by hand on a real device: that `ViewGroup.dispatchTransformedTouchEvent`'s
inverse matrix makes taps on visually-shrunk keys land correctly. That is the one assumption the
whole mechanism rests on, and no amount of JVM or emulator testing substitutes for a thumb.
