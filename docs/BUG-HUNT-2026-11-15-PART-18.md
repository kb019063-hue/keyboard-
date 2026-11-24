# Bug hunt, part 18 — the tools: a panel taller than the keyboard it opens on, a dismiss button TalkBack cannot name, and a smoke test that never saw either

Date: 2026-11-15
Branch: `arena/cbf26556-keyboard`
Base: `6d94ecb` (part 17, `popover/`)
Scope: the two surfaces a user reaches for when they want a *function* rather than a letter —
`MoreToolsPanel.kt` (158 lines, the pure model), `MoreToolsPanelView.kt` (393, the Android
renderer), `ToolbarCompactPolicy.kt` (31), `ToolbarConfig.kt` (116), `KeyboardToolbar.kt` (440) —
together with the code that hosts them: `KeyboardReceiver.showToolsPanel` / `onToolTap` /
`panelState`, `CleverKeysService.setToolsPanelSwitch` and the five setters behind it,
`KeyboardComponentGraph.initialize` (where the toolbar is built), and the
`SWITCH_TOOLS` / `MoreToolsPanel.Key` branch of `Keyboard2View.onCustomShortSwipe` — plus, in the
second pass, `scripts/ci/emulator-smoke.sh`, which is the only witness any of these surfaces has on
a device.

This is the last surface part 17 left untouched. Part 16 scoped `customization/` and part 17 the
popover; both appear here only where they route a toolbar tap or panel tap, and neither is
re-opened.

Two defects are fixed (P1 and P5). Three are reported with measured numbers, because each changes
behaviour or layout and wants a decision rather than a patch. Two measurements deliberately came out
the **other** way from my expectation — the 247-command routing sweep, which I expected to find dead
buttons in and did not, and the panel's key-hiding, which I had drafted as a finding before
measuring that only the *clipboard* hides the keys — and both are in "Checked and found sound"
rather than dropped.

A second pass turned to the question the first pass had no evidence for: does any of this work on a
device? For the tools panel, it did not — and the reason was in the harness, not the product: the
smoke test has been aiming 16 px above the toolbar since floating mode shipped, so the one check the
panel has ever had has reported `silent` on every run it was measured in (P5).

## A note on two passes

The first pass here (commit `c97fd30`) fixed P1 and reported P2–P4. Reviewing it raised the
harness question above, which is a different surface (the CI script) reached through the same
subsystem, so it is recorded as P5 in this same document rather than a new one. Nothing in P1–P4
changed except P2, which gained a floating-mode measurement that was not available from source
alone.

---

## Summary

| # | Finding | Severity | State |
|---|---------|----------|-------|
| P1 | The tools panel's only dismiss control is a `clickable` + `focusable` wrapper with **no** `contentDescription`, so TalkBack focuses an unnamed node; the other three panes all label theirs | medium (the panel cannot be dismissed by a screen-reader user) | **fixed** + drift test |
| P2 | The panel needs **308 dp** (44 header + 3 × 88 rows) but is given 30 % of the **screen**, not of the keyboard — on every non-tablet device measured the third row is mostly cut off (Pixel 8: 20 of 88 dp visible, 23 %), and SETTINGS is the cell that lands there | **medium** (a tool is unreachable without discovering the panel scrolls) | **reported** |
| P3 | `ToolbarConfig.MAX_TOOLBAR_KEYS = 12` is enforced **only** by the settings Add button's `enabled` state; `parse` — the read path every consumer uses — accepts any number, so a restored/imported/hand-edited preference yields >12 buttons and the rows scroll | low (needs a path outside the settings UI) | **reported** |
| P4 | The tile is a fixed 88 dp inside a non-scrolling vertical section, so at `fontScale ≥ 1.7` the two-line caption is clipped (2 lines = 44.9 dp in a 40 dp box at 1.7, 52.8 dp at 2.0) | low (large-text accessibility setting) | **reported** |
| P5 | The smoke test's `kb_toolbar_center()` measured the toolbar from the top of the input-method frame while `FloatingRoot` put a 26 dp grip **above** the content — and section 7d deliberately leaves floating **on**, so 7e/7g had been tapping 16 px *above* the toolbar. 7g had therefore only ever reported `verdict=silent`: CI never once confirmed the tools panel opens | **high for the harness** (two checks silently unverifiable, and the emitted warning blamed the product's toolbar layout) | **fixed** — verified on device: 7g `shown`, 7e `ok` |
| P6 | The root cause of P5 was in the product, not the harness: the floating keyboard's drag grip sits across the **top** of the window, where Gboard's is along the **bottom** (five sources, 2018–2024). A top band is inside the input-method window, pushing the toolbar 26 dp down — and it rules out the "drag down to dock" gesture that is the reason the handle lives at the bottom at all | medium (ergonomics, plus the P5 root cause) | **fixed** + drift test; drag-to-dock itself still absent |

---

## P1 — the fix: the panel's dismiss control had no accessible name

`MoreToolsPanelView.buildHeader()` builds the X as a `FrameLayout` wrapper that is deliberately
`clickable` and `focusable` (it is the 48 dp hit target; the `ImageView` inside is excluded from
accessibility so the tap is not swallowed — the same rule `KeyboardToolbar` documents). That is the
right structure, but the wrapper was never *named*:

```kotlin
val wrapper = FrameLayout(context).apply {
    isClickable = true
    isFocusable = true
    minimumWidth = dp(TOUCH_MIN_DP)
    minimumHeight = dp(TOUCH_MIN_DP)
    rippleBackground()
    addView(glyph, …)
    setOnClickListener { onClose?.invoke() }
}                                   // ← no contentDescription
```

The nine tiles are all named (`createTile` sets one, including the on/off word for switches), and
the three sibling panes name their dismiss affordance (`clipboard_close`, `emoji_close`,
`gif_close`). The tools panel was the one surface whose only way out was an empty node. Fixed by
using the existing, already-translated `R.string.common_close`:

```kotlin
contentDescription = context.getString(R.string.common_close)
```

`common_close` is present in `res/values/strings.xml` and in **all 21** shipped locale files
(checked by script), so this needs no new string and no translation debt.

### Why it shipped, and what now stops it

Every accessibility drift test in this project parses `res/layout/*.xml`
(`ClipboardEntryButtonA11yDriftTest` and its siblings). `MoreToolsPanelView` is built in *code*, so
none of them can see it, and the blind spot is exactly the size of the panel.

`MoreToolsPanelA11yDriftTest` closes it by reading the source off disk (the class imports
`android.view.*`, so it cannot run in the pure-JVM runner and cannot instantiate the view). It pins
three things: the wrapper block carries a `contentDescription`; the label comes from
`context.getString(R.string.…)` rather than an English literal; and that resource exists in the
default file **and** every shipped locale. Verified against the fixed source and — critically —
against the *unfixed* one first, where assertions 1 and 2 both fail, so the test has teeth rather
than being written to match what happens to be there.

**The first version of this test was itself buggy, and CI is how it was caught.** I asserted
`LOCALIZED_LABEL.matches(wrapper)`; Kotlin's `Regex.matches` anchors the *whole* input, so it
demanded that the entire 927-character wrapper block be nothing but that one assignment. CI run
`45261928680` failed with exactly one test — this one — against correct production code. The
assertion is now `containsMatchIn` (match anywhere), and the failure is left in the history rather
than force-pushed away. Worth recording because the mistake is invisible to inspection: the regex
is right, the block is right, and only the semantics of *which* `Regex` method was meant is wrong.

---

## P2 — reported: the panel is taller than the space it is given, on every phone I measured

The panel's own doc states the sizing contract: three rows, scrolling vertically if the pane is
short, with the pane height inherited from the clipboard pane's percentage. The arithmetic:

```
44 dp header + 3 rows × 88 dp (TILE_HEIGHT_DP) = 308 dp of content
```

The pane it receives is `contentPaneHeight = screenHeightPixels * clipboard_pane_height_percent /
100` (`KeyboardComponentGraph.calculateContentPaneHeight`), and the default percentage is **30**
(`Defaults.CLIPBOARD_PANE_HEIGHT_PERCENT`). That is 30 % of the *screen*, which only exceeds 308 dp
above 1026 dp of screen height — i.e. a tablet. On phones:

| device | screen | pane @30 % | grid area | third row visible |
|---|---|---|---|---|
| Pixel 8 (2400 px @3.0) | 800.0 dp | 240.0 dp | 196.0 dp | **20.0 / 88 dp (23 %)** |
| Galaxy A54 (2340 px @3.0) | 780.0 dp | 234.0 dp | 190.0 dp | **14.0 / 88 dp (16 %)** |
| Pixel 4a (2340 px @2.75) | 850.9 dp | 255.3 dp | 211.3 dp | 35.3 / 88 dp (40 %) |
| 720×1280 @2.0 | 640.0 dp | 192.0 dp | 148.0 dp | **0.0 / 88 dp (0 %)** |
| Tecno Spark 8 (1612 px @2.0) | 806.0 dp | 241.8 dp | 197.8 dp | 21.8 / 88 dp (25 %) |

The row that gets clipped is the third one, and because the model orders switches first, that row
holds exactly one cell: **SETTINGS**. So the one launcher a user is most likely to look for is the
one below the fold, with no scroll indicator (`setVerticalScrollBarEnabled(false)`) and no partial
row to imply more content on the 720×1280 case.

The panel is bigger than the pane in **floating** mode too, but by a different ratio — which is the
clearest evidence that the percentage is measuring the wrong thing. Using the floating window CI
actually produced (`[108,1302][972,2274]`, density 2.625):

| mode | height available to a pane | pane @30 % of the *screen* | ratio |
|---|---|---|---|
| docked, Pixel 8 | 167 dp (keys band) | 274 dp | **164 %** |
| floating, Pixel 8 | 344 dp (window minus the 26 dp grip) | 274 dp | **80 %** |

So the same 30 % is a 164 % overrun docked and a comfortable 80 % floating — the pane's share of the
keyboard **halves** (1.64 / 0.80 = 2.05×) purely because the keyboard changed shape, while
`clipboard_pane_height_percent` never moved. Docked it still falls 34 dp short of the 308 dp the grid
needs.

Two things make this worse than a cosmetic shortage, and both are worth stating before anyone
"fixes" it by raising the percentage:

1. **The percentage is the clipboard's, and the clipboard wants more.** `clipboardHeight()` gives
   the clipboard everything above the keys' bottom edge — deliberately, since 2026-11-13. Whatever
   height the tools panel is given here is a number chosen for a different pane. Coupling them is
   the actual defect; the 30 % is only where it shows.
2. **The keyboard it opens on is shorter than the panel.** Default keyboard height is 27 % of the
   screen (`KEYBOARD_HEIGHT_PORTRAIT`), and the toolbar (40 dp) and strip (40 dp) are siblings
   *above* `topPane`, so the key area a pane can occupy is `27 % − 80 dp` — 136 dp on a Pixel 8.
   A 240 dp pane is therefore 1.8× the key area it replaces (1.7×–2.1× across the five devices
   above). The panel covers more than the keyboard.

Suggested fix, in the order I would try them: (a) give the panel its own height policy — either
`max(3 rows + header, …)` capped to the window, or a per-pane percentage — rather than inheriting
the clipboard's; (b) if the coupling stays, turn the scroll indicator on so the clipped row is
discoverable; (c) reorder so a *launcher* cannot be the cell below the fold. (a) is the real fix;
(b) and (c) are the cheap mitigations if the coupling is deliberate.

Not fixed here because it changes layout on every device and the right answer depends on whether
the clipboard coupling is intentional, which the code does not say.

---

## P3 — reported: the 12-button cap is a UI affordance, not an invariant

`ToolbarConfig` documents `MAX_TOOLBAR_KEYS` as a *usability* cap "enforced by the Add control",
and that is literally true — `ToolbarSection` disables the Add button at
`toolbarKeys.size < MAX_TOOLBAR_KEYS`. Nothing enforces it on the **read** path:

```kotlin
fun parse(raw: String?): List<String> {
    // drops blanks, `removed`, duplicates and unknown names …
    return if (kept.isEmpty()) DEFAULT_KEYS else kept.toList()   // no take(MAX_TOOLBAR_KEYS)
}
```

`parse` is what `KeyboardToolbar.applyFromConfig`, `SettingsPersistence` and the backup defaults
all consume, so the only way past 12 is a write outside the settings list UI: a restored backup, a
preference imported from another build, or a hand-edited file. It is not a crash — the row scrolls,
which is the documented behaviour past the cap (`KeyboardToolbar` is a `HorizontalScrollView` with
`isFillViewport = true`).

Measured, because "it scrolls" hides how much: at 411 dp of width, 7 default buttons get
411/7 = **58.7 dp** each (comfortably over `BUTTON_MIN_WIDTH_DP` = 40), while 12 get
411/12 = **34.2 dp** — under the minimum, so the row scrolls rather than shrinking. The margin is
thinner than it looks: on a 320 dp phone the seven defaults are already down to 320/7 = **45.7 dp**,
so the cap is what keeps a narrow device's row from scrolling in its *default* configuration.

Two-line fix when someone wants it: `kept.toList().take(MAX_TOOLBAR_KEYS)` in `parse` (with the
existing "nothing survives → `DEFAULT_KEYS`" rule after the take), and the same clamp on the
serialize side so a write cannot persist a longer list. I did not make it: truncating a user's
restored toolbar silently deletes buttons they chose, and which 12 to keep (first-in-order or
last-used) is a product decision. Reported.

---

## P4 — reported: a fixed tile height breaks at large text

`TILE_HEIGHT_DP = 88` and the caption is `CAPTION_TEXT_SP = 11f` with `maxLines = 2`. The box it
has to fit in is `88 − 2×8 (padding) − 26 (ICON_DP) − 6 (CAPTION_GAP_DP) = 40 dp`. At the default
font scale two lines need ≈ 26.4 dp, so there is room to spare — and it stays fine to about 1.5×.
Beyond that the multi-line `TextView` is squeezed by its fixed-height parent:

| fontScale | two caption lines | in a 40 dp box |
|---|---|---|
| 1.0 | 26.4 dp | ok |
| 1.3 | 34.3 dp | ok |
| 1.5 | 39.6 dp | ok |
| **1.7** | **44.9 dp** | **clipped by 4.9 dp** |
| **2.0** | **52.8 dp** | **clipped by 12.8 dp** |

(Lines estimated at 1.2 × `sp`; the estimate is what the table is for, not a claim about a specific
typeface's ascent/descent.) The `ImageView` above it is a fixed 26 dp, so the caption cannot borrow
space either. The screen reader still gets the full label — the tile's `contentDescription` is the
complete tool name plus its on/off word — so this is a visual truncation for large-text users, not
a loss of function. The fix is to let the tile height follow content (`WRAP_CONTENT` with a
`minHeight` of 88 dp) rather than pinning it. Reported because it changes the grid's ragged-tail
alignment guarantee that `gridRows`' null padding exists to provide: equal-height cells need a
height to agree on.

---

## P5 — the fix: the smoke test has never once confirmed the tools panel opens

Part 18's first pass fixed the panel's own accessibility and reported its geometry. This pass went
looking for the opposite: what evidence exists that any of it works on a device. The answer, for
the tools panel specifically, is **none** — and the reason is a harness bug, not a product one.

### The check, and what it actually reported

`scripts/ci/emulator-smoke.sh` §7g taps the toolbar's first button and reads logcat, because this
emulator's `uiautomator` dumps never carry IME-window nodes (documented since run 40). The verdict
vocabulary is deliberately unambiguous:

| logcat contains | verdict | meaning |
|---|---|---|
| `shown rows=` | `shown` | the panel opened — the check passes |
| `press` but no `shown` | `press` | dispatched and nothing appeared — **a real defect** |
| neither | `silent` | the tap hit nothing — harness's miss, warning only |

Two consecutive runs on this branch — `45261915906` and `45263705962` — both report
`MTSHEET-DIAG verdict=silent tap=169 1354 shown=mInputShown=true log='none'`. The assertion that
matters has never fired, and the warning text — *"toolbar may not carry the more key at position 1"*
— points at the product. It is the harness.

### The measurement

Run `45263705962` prints the frame in its `NFFRAME-DIAG` annotation:

```
frame=[108,1302][972,2274]   screen=1080x2400   density=420 (2.625)
```

`kb_toolbar_center(0)` computes `x = l + (r-l)(2·0+1)/(2·7) = 169` and `y = t + th/2`, where
`th = 40dp = 105px`, giving **169 1354** — exactly the tap observed. The arithmetic is right and the
tap is in the wrong place, which means the *model* is wrong.

`FloatingRoot` (26 dp grip) is a vertical `LinearLayout`: the grip first, then the `contentHolder`
that hosts the keyboard. So the IME window's frame starts at the **grip's** top:

```
real toolbar top    = 1302 + 26dp(68px) = 1370
real toolbar spans  = 1370 .. 1475      (centre 1422)
the smoke tapped    = 1354              ← 16 px ABOVE the toolbar, inside the grip
```

The tap lands on a drag handle, which correctly consumes it as a drag (that is what a grip is for)
and dispatches nothing — hence `press` never appears in logcat, hence `silent`.

### Why it has always been this way

Section 7d's own header says it: *"Floating and incognito are deliberately left ENABLED afterwards:
the rest of the run then exercises the keyboard in that state."* Floating was enabled before 7e and
7g and never turned off, so **both** of the checks that locate a toolbar button by computed
geometry have been measuring from the wrong top edge since phase 3 shipped:

- **7e** — the dictation self-test falls back to `kb_toolbar_center 6` (the mic, 7th of 7) when the
  dump carries no node, which is what `DUMPDIAG descVoice=0` says happens. Both runs emit
  `VOICE-DIAG verdict=key mic='910 1354' route=coord` — the *same* y as 7g's failed tap, and the
  same 16 px miss; only x differs (910 vs 169), because the mic is the 7th button. Its warning
  blames "the toolbar may not carry the mic at its default position".
- **7g** — as above, and it is the only check the tools panel has ever had.

### The fix

`kb_toolbar_center()` now adds the grip's height to the frame's top **when, and only when, floating
is actually on**:

```bash
if [ "${FL_VERDICT:-off}" = "on" ]; then
  t=$(( t + 26 * dpi / 160 ))     # FloatingRoot.GRIP_HEIGHT_DP
fi
```

`FL_VERDICT` is a new tracker set to `on` at the single point 7d confirms the floating switch took
effect, and defaults to `off`. That default is what keeps the fix honest: if 7d bailed early (no
text field to focus, switch never composed, a future refactor), no grip is on screen and no offset
is applied — rather than assuming a layout state the script did not verify. Verified by evaluating
the patched helper under `bash` for all three shapes:

| state | frame | tap |
|---|---|---|
| floating on (the shipped path) | `108 1302 972`, d=420 | **169 1422** — toolbar centre |
| floating off | `108 1302 972`, d=420 | `169 1354` — unchanged |
| full-width window, floating on | `0 1302 1080`, d=420 | `77 1422` |

`bash -n` parses clean. The `26` is hardcoded beside the `40` that was already there, with both
tied to their Kotlin constants in the comment (`FloatingRoot.GRIP_HEIGHT_DP`,
`KeyboardToolbar.HEIGHT_DP`); a magic number that mirrors a constant is acceptable only if the
comment names the constant, which is the rule the `TB_N=7` note above it already follows.

### Verified on device, same run

Commit `3010376`'s smoke run (`45278048972`) is the first in which either check has ever returned a
positive verdict. The two annotations, verbatim:

```
MTSHEET-DIAG verdict=shown tap=169 1422 shown=mInputShown=true
  log='I/MoreToolsPanel: press | I/MoreToolsPanel: shown rows=3 incognito=on floating=on
        split=on one_handed=on sound=off emoji=off clipboard=off theme=off settings=off'

VOICE-DIAG verdict=ok mic='910 1422' route=coord grant='granted' shown=mInputShown=true
  log='I/DictationController: press mode=DICTATION selfTest=true ... decision=COMMIT_SELF_TEST
        | self-test commit length=25 | committed 25 characters'
```

So the tap now lands on the toolbar (y **1422**, the centre computed above), the panel logs `press`
then `shown rows=3`, and the dictation self-test's coordinate fallback — which had never worked
either — committed its transcript. Two checks that had been reporting "unverifiable" or a
misattributed warning went green **without touching the product**, which is the proof that the
defect was the measurement.

It also means the panel has now been observed opening and drawing its three rows on a device, which
is the first device-side corroboration of the model's row arithmetic in P2.

### The grip is on the wrong edge, and Gboard is the evidence

The grip is a **product** decision as much as a harness one: a 26 dp drag handle above the keyboard
means the toolbar is not at the top of the IME window whenever floating is on, and any user aim near
that band hits the grip. This pass initially left it alone, on the claim that *"Gboard's floating
keyboard uses a grip of its own"* in that position. **That claim was mine, and it was wrong.** It
is corrected here, because a report that rationalises a defect with a false appeal to the
reference implementation is worse than one that leaves the defect open.

What the sources actually say, from Gboard's floating mode across its whole life:

| year | source | where Gboard's handle is |
|---|---|---|
| 2018 | PhoneArena, on the beta that shipped it | "Drag the keyboard around by the **'bump' on the bottom**"; "dock … drag it to the bottom of the screen" |
| 2018 | Forbes | "grab the keyboard by a small handle"; "grab the handle and **drag the keyboard down** to the bottom of the display to dock it" |
| 2020 | GoAndroid how-to | "move the keyboard anywhere … by tapping and dragging the icon **at the bottom**" |
| 2023 | Android Police (the auto-floating landscape layout) | "move the keyboard into any position using the **drag handle at the bottom**"; "drag it to the bottom edge … it will magnetize" |
| 2024 | UMA Technology how-to | "**dragging the handle at the bottom** of the keyboard" |

Five independent sources over six years, one answer: the handle is at the **bottom**, and dragging
it to the bottom edge is how you *dock* — which is the same gesture because the handle is already
there. One 2021 redesign report (XDA, via Sammy Fans) says the handle became "no longer focused on
the bottom" and is now attached to the window's frame, but that is a single second-hand line about
a Material You restyle and it contradicts every other source; it is not enough to justify the
opposite arrangement.

So the right edge is the bottom, and this codebase has it at the top. That matters beyond
aesthetics, and one consequence is already a recorded defect: **P2's panel-height arithmetic.** A
bottom grip sits under the keyboard and costs the content nothing; a top grip costs the content
26 dp and shifts every y coordinate inside the window — which is exactly what made 7g's computed
tap land in the grip instead of on the toolbar.

### The change

`FloatingRoot.column` now adds `contentHolder` first and `grip` second, so the grip is a band under
the keyboard and the content starts at the input-method frame's top. Two things had to move with it:

- **The grip stops 26 dp short of the right edge** (`rightMargin = dp(KNOB_SIZE_DP)`). The knob is
  `bottom|end` in the same `HANDLE_COLOUR` (`0x33808080`, 20 % alpha), so with the grip under it the
  knob would have been painted on top of an identical grey band — still hittable (it is drawn last)
  but **invisible**, losing the resize affordance. The margin keeps both handles at full size and
  makes the two touch zones disjoint: the band drags, the corner resizes.
- **The smoke test's workaround is removed, not kept conditionally.** With the grip below the
  content the toolbar is back at the frame's top, so the correct tap is the original `t + th/2`;
  the +68 px offset this pass added would now be wrong by exactly 68 px. Leaving a dead workaround
  behind is how the next reader learns the wrong thing, so it is deleted and replaced by a guard
  that warns if `column` ever starts with anything other than `contentHolder`:

  ```
  first=$(awk '/private val column/,/^    }/' "$fro" | grep -o 'addView([A-Za-z]*)' | head -1)
  case "$first" in
    'addView(contentHolder)') : ;;   # expected
    '') : ;;                         # layout reshaped beyond recognition; say nothing
    *) ann warning "TBGEOM FloatingRoot's column now starts with $first, …" ;;
  esac
  ```

  Verified in all three states: passes on the real source, warns on a simulated revert to
  grip-first, and stays silent when the file is absent (no false alarm from a moved script).

The clamp maths needed **no change** — `maxYFraction` was always about the whole window staying on
screen, which forbids both a content-sliding-off-above derangement and a grip-sliding-off-below one.
Only the prose that described it as "the grip" was grip-specific, and that is corrected in
`FloatingConfig` and in `PHASE3-PART3-FLOATING-DESIGN.md`. The user-facing string is already
edge-agnostic ("a smaller keyboard window you can drag and resize"), so no translation work.

`FloatingRootHandlePositionDriftTest` pins the arrangement, because `FloatingRoot` is built in code
and no XML-side drift test can see it. Three assertions: the `column` adds `contentHolder` then
`grip`; the grip reserves `KNOB_SIZE_DP` on the right; both constants keep their names (the smoke
test's comment cites them). Verified against the pre-change source first, where the first two fail.

### What this still does not do: drag-to-dock

Gboard's handle does a second job that this one does not. Dragging it down to the bottom edge
**magnetises the window and docks it**, with a confirmatory buzz — that is *why* the handle lives at
the bottom, and it is one gesture rather than the two this app needs (drag the window where you want
it, then toggle floating off). `FloatingRoot`/`FloatingConfig`/`CleverKeysService` contain no dock
path at all: no proximity test, no haptic, no edge snap.

Implementing it means a pure-Kotlin proximity decision in `FloatingConfig` (testable offline, the
way every other clamp here is), a buzz at the threshold, and a write of `floating_enabled = false`
on release inside it — which is the same preference the settings switch and `floating_toggle`
write, so the open keyboard would follow through `applyFloatingToOpenKeyboard` with no new path.
Recommended as its own pass: it is a new gesture with a haptic contract, not a layout tweak, and
this pass has already changed three files that a dock gesture would also touch.

---

## Checked and found sound

- **The panel leaves the keys visible, so its five switches can be seen working.** This is worth
  stating because I first wrote it up as a finding and then measured it the other way. Only the
  *clipboard* opener passes `hideKeys = true` (`showContentPane(height = clipboardHeight(), hideKeys
  = true)`, the 2026-11-13 change that gives the clipboard the whole keyboard); the emoji, GIF and
  tools openers all call the default `showContentPane()`, i.e. `hideKeys = false`. So a switch tap
  in the tools panel changes SPLIT / ONE_HANDED geometry, or the floating window, **on a keyboard
  that is still drawn underneath the pane**. The suspicion was wrong and is recorded here rather
  than in the findings table.
- **All five panel switches reach the open keyboard, through five different mechanisms.** INCOGNITO
  → `_config` write + `PreferenceUIUpdateHandler`, read per keystroke; FLOATING →
  `FloatingConfig.PREF_ENABLED`, applied by `applyFloatingToOpenKeyboard()` on the pref branch;
  SPLIT → `_config.edit { }` plus `invalidate()` on the split-pref branch; ONE_HANDED →
  `OneHandedConfig.PREF_ENABLED` → `updateOneHanded()`; SOUND → plain pref, and `KeyClickSound.play`
  reads `config.sound_enabled` on every press. None is a no-op.
- **`panelState` is the correct fix for the stale-read problem, and it is re-seeded.** The switches
  are *explicit setters* (`enabled`), never toggles re-reading `Config`, and `onToolTap` advances
  the state the panel is **drawing** (`current.with(key, next)`) rather than re-reading a
  read-model that lags the write — audit-item-7, correctly re-pinned for the pane. `panelState` is
  seeded from `Config` on every `showToolsPanel` and set to `null` on every `SWITCH_BACK_*`, so the
  "a new visit re-seeds" comment is literally true.
- **All 247 toolbar-picker commands resolve to a handler — measured, not assumed.** This is the
  check I expected to fail. `ToolbarConfig.availableKeys()` offers `CommandRegistry.ALL_COMMANDS`
  wholesale, and `KeyboardToolbar.createButton` only drops a name the registry does not know, so a
  command with no execution path would be a button that silently does nothing. Traced every one of
  the 247: **234** have an entry in `KeyValue.getSpecialKeyByName` — **30** of those as
  `editingKey` (routed by `CommandRouting.editingValue`, which falls through to the command name
  itself, so `select_word`, `clear`, `pasteAsPlainText`, `case_cycle`, `selection_cancel` and
  friends are covered where my first, naive parse had "found" 16 of them unrouted) and **204** as
  an ordinary character, string, event, key-event, modifier or timestamp key. The remaining **13**
  have no `KeyValue` entry at all and are the ones handled **by name**: the five `paste_pinned_*`
  in `CustomShortSwipeExecutor`, and the eight service-level commands
  (`one_handed_toggle`, `one_handed_side`, `incognito_toggle`, `floating_toggle`, `split_toggle`,
  `primaryLangToggle`, `secondaryLangToggle`, `showTextMenu`) in both the executor's
  `when` and `Keyboard2View`'s custom-command block. Unresolved: **0**.
- **The literal-text hazard is closed.** `getKeyByName` is total, so an unknown name comes back as a
  String key spelling itself; `CommandRouting.isOwnNameFallback` catches it and the executor defers
  instead of committing. This is the defect that typed `more_tools` into the owner's editor in
  2026-11-04, and it is correctly guarded on both paths.
- **`ToolbarCompactPolicy` positions, and the rebuild are consistent.** Replicated
  `rebuild()` + `applyCompact()` + `setCompactMode()`'s early-return as a script across 1, 2 and 7
  buttons and both compact states. The spacer is inserted only when `childCount > 1` and is always
  excluded from the button list by identity (`!== compactSpacer`), so a 1-button toolbar gets no
  spacer, no compact hiding and weight 1f; 7 buttons show first+last only when occupied; 2 buttons
  both stay visible. `applyCompact()` is called from `rebuild()` unconditionally, so a rebuild
  *while* compact re-applies weights even though `setCompactMode` early-returns — the stale-weight
  failure I went looking for is not there.
- **`ToolbarConfig.parse` validation.** Drops blanks, the `removed` placeholder, duplicates
  (`LinkedHashSet`) and unknown names by registry lookup, and falls back to `DEFAULT_KEYS` when
  nothing survives — so a corrupt value cannot hide the toolbar. The forward-safety claim (a
  command renamed upstream disappears rather than rendering dead) holds.
- **`MoreToolsPanel` geometry and its invariants.** 5 switches + 4 launchers partition `ALL` with no
  overlap; `State.with(launcher, …)` returns `this` unchanged and `cells()` gives launchers
  `checked = false`, and `MoreToolsPanelView.createTile` only consults `cell.checked` behind
  `isSwitch(cell.key)`, so a launcher can never be drawn lit. `gridRows` pads to a full `COLUMNS`
  row, so the ragged tail cannot make the last row's tiles a different width. 9 cells → 3 rows,
  verified.
- **The panel's theme lifecycle.** `isBuiltFor(theme)` compares identity, and `showToolsPanel`
  rebuilds the view on a theme change; the nine label and icon mappings are complete `when`s over
  `Key`, so no tool can render an unlocalised fallback. Colours fall back sensibly if a theme leaves
  a field 0 — except `colorKey` (below).
- **Rebuild-in-place from inside its own click listener.** `bind`/`rebuildGrid` replaces the grid's
  *children*, never the tile currently dispatching, so a switch tap repainting itself is safe.
- **The tile `contentDescription` includes state.** Switch tiles announce the tool plus an on/off
  word; launchers announce the name alone. The `ImageView`/`TextView` children are excluded so
  TalkBack does not read the caption twice.
- **`KeyboardToolbar` long-press naming.** `tooltipText` on API 26+, Toast below — the split that
  exists because a keyboard's toasts are dropped on Android 13+ and a long-click listener used to
  pre-empt the tooltip it was replaced with.
- **The 8-name icon map and the font-glyph fallback.** `toolbarIcon` covers exactly the seven
  defaults plus `redo`; everything else keeps its font glyph, and `dispatch` clamps `displayText` to
  `ShortSwipeMapping.MAX_DISPLAY_LENGTH` so no registry command can throw on construction.

---

## Two smaller observations (not defects)

- **`Modifier` on the tile background is chosen once**, correctly: a lit switch gets the
  pressed-ripple layered over the activated fill, an unlit one gets the plain ripple. The comment
  claims "setting a ripple after the fill would have overwritten it", which is why the branch exists.
- **`tileBackground`'s `colorKey` fallback is `0`** (transparent) with no legible second choice,
  unlike every other colour accessor here (`labelColor` → white, `subLabelColor` → 60 % white,
  `fieldColor` → dark). It is unreachable for shipped themes: 23 of the 26 `<style>` blocks in
  `res/values/themes.xml` declare `colorKey` outright, `AltBlack` inherits it from `Black`, and the
  two that neither declare nor inherit it (`BaseTheme`, `emojiTypeButton`) are a base style and an
  emoji button widget, not keyboard themes. So a tile on a theme that genuinely left it unset would
  vanish into the field — but no shipped theme can be in that state. Not worth a change; recorded so
  the asymmetry is not "fixed" by accident later.

---

## What this pass did not do

- **Did not run the app.** No Gradle, no emulator in the sandbox; CI is the only compile signal. P2
  and P4 are layout claims measured from the constants and the host's height arithmetic, not from
  screenshots, and P2's fix wants a device.
- **Did not change the panel height policy (P2), the 12-button cap (P3) or the tile height (P4).**
  All three are behaviour or layout decisions with a product question in them; each is pinned with
  measured numbers instead.
- **Did not add a test for P2–P4.** The figures come from source constants plus a script; a JVM test
  of the panel's height budget would have to pin a policy that has not been decided yet.
- **Did not audit `Pointers`' gesture arbitration or the clipboard/emoji/GIF panes.** Out of scope;
  waves 4–20 stay closed.
- **Did not implement drag-to-dock (P6's other half).** The grip now sits where Gboard puts it, but
  dragging it to the bottom edge does not dock the window: there is no proximity test, no haptic and
  no edge snap anywhere in `FloatingRoot`, `FloatingConfig` or the service. It is a new gesture with
  a haptic contract and its own product questions ("how close is close enough?", "should the buzz
  fire on approach or on release?"), so it belongs in its own pass rather than bolted onto a layout
  change. P6 records the shape the implementation should take.
- **Did not verify P6 on a device.** The layout change is four lines and compile-checkable, and the
  drift test pins its two invariants, but nothing in the sandbox can say how the 26 dp band *looks*
  under the keys or whether the right margin is wide enough to hit comfortably. That wants the next
  emulator smoke run at minimum, and ideally a real thumb.
- **Did not change anything else in the smoke test.** The grip offset was the one broken measurement
  found, and it has since been *removed* rather than retuned now that the grip no longer sits above
  the content. 7f's clip check compares *bottom* edges and was untouched throughout.
- **Did not re-open part 16's routing seam.** `CommandRouting` appears here only as the thing a
  toolbar tap passes through, and the sweep above exercises all 247 names through it.

---

## Verification

- P1 fix: `MoreToolsPanelView` +6 lines (387 → 393), `build.gradle` (+4, test registration),
  `MoreToolsPanelA11yDriftTest` (new, 3 tests).
- P6 fix: **four executable lines** in `FloatingRoot` (swap the two `addView` calls, add the
  `rightMargin`), plus comments; `FloatingConfig` changes are prose only (the clamp maths is
  untouched, so every one of `FloatingConfigTest`'s 14 tests holds unchanged). `build.gradle` +4
  (registers `FloatingRootHandlePositionDriftTest`), which adds 3 tests and 1 class.
- P6 was **not** shipped on the strength of the diff: the drift test was first run against
  `git show HEAD:` of the old `FloatingRoot.kt`, where its two layout assertions **fail**, and the
  smoke test's new guard was run in three states (real source → pass, simulated grip-first revert →
  warn, absent file → silent). `bash -n scripts/ci/emulator-smoke.sh` → clean.
- Test registration re-derived by script, not by eye: **447** test classes on disk, **311** pure +
  **136** mock registered, **0** unregistered, **0** stale, **0** overlap.
- CI on the first attempt (`45261928680`) was **red on my own test**, not on the fix: 4913 run /
  1 failed / 134 skipped / 441 classes, the failure being
  `MoreToolsPanelA11yDriftTest#the close label comes from a string resource` — the `Regex.matches`
  anchoring described above. Corrected to `containsMatchIn` and re-pushed forward (never forced).
- CI on the corrected run: **4913 run / 0 failed / 134 skipped / 441 classes**; Build APK success;
  Emulator smoke success (`fails=0-toolbar=unverifiable-tbui=picker-2-oh=toggled-nf=ok-keyboard=bound-anr=0`).
- Delta from part 17's `6d94ecb` (4910 / 134 / 440): **+3 tests, +1 class** — the three assertions in
  the new drift test, and the class itself. Production behaviour is unchanged apart from the one
  `contentDescription` assignment.
- Test-runner registration verified exactly: **446** test classes on disk, **310** in
  `pureTestClasses` + **136** in `mockTestClasses`, intersection **0**, unregistered **0**,
  registered-but-absent **0**. The new class is in the pure list beside the clipboard a11y drift
  test it is modelled on, and the arrays contain no `]` in a comment (the trap
  `TestRunnerListDriftTest` refuses to parse past).
- P5 fix: `scripts/ci/emulator-smoke.sh` only (+12 lines code, +18 comment), no product change.
  `bash -n` clean; the patched helper evaluated under `bash` for floating-on / floating-off /
  full-width-window and each matched the hand calculation.
- CI at `3010376` (the commit carrying the P5 fix): Full unit-test suite **success**, Build APK
  **success**, Emulator smoke **success** — and the smoke's own annotations report
  `MTSHEET-DIAG verdict=shown` and `VOICE-DIAG verdict=ok`, the first positive readings either
  check has produced.
- Every figure above was recomputed in one final script before being written: the 308 dp budget and
  the five device rows, the 40 dp caption box and its five font-scale rows, the 42 views per tap
  rebuild, the 58.7 / 34.2 / 45.7 dp per-button widths, the 234 / 30 / 204 / 13 / 0 routing split, and
  the 21-locale `common_close` and 23-of-26 `colorKey` checks, the floating grip offset (68 px at
  density 2.625) and the 164 % / 80 % pane ratios.
- Two expectations came out the other way, and both are recorded instead of dropped: the
  247-command sweep found **no** unrouted command (it is in "Checked and found sound"), and the
  panel does **not** hide the keys, so its switches are visible in action — the "switches apply
  behind a panel you must dismiss" claim I had drafted was my own error and was deleted, not
  softened. Two further figures were corrected in place during re-checking: a "25 of 26 themes
  define `colorKey`" line (real split: 23 declare it, 1 inherits, 2 are not keyboard themes) and a
  routing tally that counted the `editingValue` identifier inside a `when` as a handler.
