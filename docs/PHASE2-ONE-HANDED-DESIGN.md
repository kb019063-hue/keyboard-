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
