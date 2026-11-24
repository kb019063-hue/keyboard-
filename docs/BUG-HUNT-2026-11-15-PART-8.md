# Bug hunt — 2026-11-15, part 8: `a11y/`

Part 7 went into `voice/`. This one opens `a11y/` — 696 LOC over three files, previously unhunted,
and the highest-stakes package for a keyboard: if TalkBack cannot drive it, the keyboard is not
"less accessible", it is unusable.

| # | Finding | Severity | State |
|---|---|---|---|
| V6 | The accessibility tree has one node per *key*; corner symbols get none, so `é`/`è`/`ç` cannot be typed through TalkBack on the Spanish layout | Medium (accessibility) | reported, pinned by test |
| V7 | The one file in the package with no test at all is the one that talks to the framework | Low (process) | reported |
| V8 | `TestRunnerListDriftTest` parses the runner arrays non-greedily, so a `]` in a *comment* silently unregisters every class below it | Medium (test infrastructure) | **fixed** |
| — | Three of my own suspicions were wrong; they are recorded below so nobody re-opens them | — | checked |

## V8 — a comment that unregistered thirty tests

CI run `44919059108` failed the drift test with roughly thirty classes reported as unregistered. They
were all registered. The cause was a comment I had just added to `build.gradle`:

```groovy
// 2026-11-21: the a11y tree builds one node per KEY (keys[0]); a key's eight corner symbols
'tribixbite.cleverkeys.a11y.AccessibilitySubkeyReachTest',
```

and the way the drift test reads that array:

```kotlin
val block = Regex("""def\s+$name\s*=\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL).find(text)
```

`(.*?)` is non-greedy, so the capture ends at the **first** `]` in the array body — and `keys[0]`
contains one. Everything below that point disappeared from the test's view, so ~30 registered classes
looked unregistered. I confirmed the mechanics directly: with a bracketed comment mid-array, the regex
returns `'a.BTest'` and never sees `'c.DTest'` two lines later.

This is the failure mode the drift test exists to prevent, sitting inside the drift test. Its whole
job is "a class that is not in the list is never run"; here a class that *was* in the list became
invisible for the same reason, and the only signal was a failure that blamed the wrong thing.

The fix is a guard rather than a cleverer parser: if the captured body contains a `]`, the test now
fails with an explanation ("usually a comment... every entry below it would go unregistered")
instead of returning a short list. Refusing to guess beats parsing harder, because comment-stripping
inside a Groovy array is a second place to be wrong. The comment in `build.gradle` is rephrased
without brackets, and says why.

Two lessons worth the ink. **An error message can be confidently wrong**: the failure said thirty
tests were missing from `build.gradle` when the truth was that this test could not see them, so the
first thing to check is the instrument, not the data. And **a comment is not inert** when the tooling
around it is a regex — the same class of bug as part 7's V1 (`?.` on a null turning a stop into a
no-op) and this wave's own "cheap no-op" check: a construct that looks like documentation or
shorthand, quietly doing something else.

## And one genuine test bug of my own

The same run's other failure was mine, in `AccessibilitySubkeyReachTest`: a one-key row asserting two
nodes.

```kotlin
val rows = listOf(KeyboardData.Row(listOf(dWithAccents, key(KeyValue.makeCharKey('f'))), 1f, 0f))
assertThat(rects).hasSize(2)
```

`KeyboardData.Key` is a **data class**, so two `Key`s built by separate `key(...)` calls with identical
fields are `==`. `computeKeyRects` skips the second, and one rect comes back. The assertion was wrong
about the fixture, not about the geometry.

Corrected to assert the finding itself — one key carrying three symbols (`d`, `è`, `é`) yields exactly
one node — plus a second check that two distinct keys still yield two. Worth recording because it is
the third time in this wave that a claim of mine failed when tested: `rowAt`'s accumulation, the
`\'` escape, and now this. The pattern is consistent — I was right about the production code and
wrong about my own reasoning about it.

## V6 — a key has one node, and it is the key face

`KeyboardAccessibilityHelper` exposes one virtual view per non-placeholder key, and
`KeyboardGeometry.computeAccessibilityKeyRects` supplies the symbol by taking `keys[0]`:

```kotlin
val kv = span.key.keys[0]        // KeyboardGeometry.kt:306
if (kv != null) {
    rects.add(KeyRect(virtualId++, span.key, kv, ...))
}
```

A `Key` carries nine slots — the face plus eight corners (`nw`, `ne`, `sw`, `se`, `w`, `e`, `n`, `s`
in the layout grammar; `key1`..`key4` map to `nw`, `ne`, `sw`, `se` in `KeyboardData`). The corners
are real symbols a sighted user produces by swiping from the key. They get no node, and so are
absent from the tree TalkBack reads.

On the shipped Spanish layout that gap is not cosmetic:

```xml
<key key0="d" key1="accent_grave" key3="accent_aigu"/>
<key key0="c" key1="loc accent_cedille" key2="&lt;" key3="."/>
<key key0="ñ" key1="·" key3="ç"/>
```

`ñ` has its own key, so it is fine. The acute accent (`é`), the grave accent (`è`) and the cedilla
(`ç`) exist **only** in corner slots on that layout — I checked, and none of them is a `key0`
anywhere in `latn_qwerty_es.xml`. A sighted user swipes from `d` to reach `è` and `é`. A TalkBack
user swipes to move focus, because that is what touch exploration *is*, so there is no route to
those symbols from the key tree at all.

Two related things I checked, because they would each have changed the finding:

- **Long-press is not a second route — for anybody.** `KeyModifier.modify_long_press` returns
  character keys unchanged on purpose (long-press on a letter is key-repeat, `Pointers` sends
  `onPointerHold`). So corner symbols are swipe-only for every user; the accessibility tree is the
  *only* non-swipe channel that could exist, and it is the one that drops them.
- **It is a corner-symbol problem, not a letter problem.** On `latn_qwerty_us` the corners hold
  punctuation (`!`, `\#`, `€`), so spoken-letter coverage there is complete. The severity is
  layout-dependent, which is why this is rated medium rather than high.

**Why this is reported and not fixed.** The fix is a design decision, not a patch. Three plausible
answers, each with a cost: add an `ACTION_LONG_CLICK` to the parent node (but long-press is
key-repeat, so the action would not correspond to anything the keyboard does); add a custom subkey
action (TalkBack's custom-action menu, discoverable but buried two levels deep); or emit a separate
node per symbol positioned in the corner's quadrant (a much bigger tree, and it changes the
traversal order of the whole keyboard). Choosing among those needs a screen-reader user's input, and
guessing would be worse than reporting.

What I did instead is make the claim falsifiable: `AccessibilitySubkeyReachTest` asserts the current
reach, so the finding is a fact in the suite rather than a paragraph in a document, and any future
change to `keys[0]` fails a test. Its fixture is copied slot for slot from the real Spanish `d`
(`key1` = nw = grave, `key3` = sw = acute) — including the fact that `key4` is `se`, not `sw`
— because an earlier draft of mine had that wrong.

## V7 — the untested file is the one that talks to the framework

`a11y/` has 33 tests across `KeyLabelsTest` (11), `KeyboardGeometryTest` (16) and
`SplitKeyboardGeometryTest` (6). All three cover pure objects. `KeyboardAccessibilityHelper` — the
class that subclasses `ExploreByTouchHelper`, receives framework callbacks, and decides what a
screen reader is told — has no test.

The architecture invites this: the deliberate split (lambdas injected, geometry pure, labeller pure)
means everything *except* the framework adapter is testable off-device, so the adapter is exactly
what gets skipped. That is the same shape as part 7's `DictationController` finding, in a different
package. Unlike that one, this pass found no bug in the adapter — but nothing was watching it either.

A Robolectric test could drive it: `AccessibilityNodeInfoCompat.obtain()` is a plain object, and
`onPopulateNodeForVirtualView` / `onPerformActionForVirtualView` are protected methods a subclass can
reach. That would pin the two contracts the class actually owes the framework — a non-empty
description, and a non-empty bounds rect on every populated node. It is test infrastructure, not a
bug fix, so it is named here as the honest next step rather than bolted onto this wave.

## Checked and found sound

This package is good, and most of this pass went into confirming that rather than into the table
above. Where a claim was checkable against the framework source, I checked it.

- **The stale-id path does not throw.** When `rectById` misses during a relayout, the helper sets
  `contentDescription = ""` and a 1×1 rect. `ExploreByTouchHelper.createNodeForChild` throws only
  when the description is `null` (`node.getText() == null && node.getContentDescription() == null`)
  and only when *both* parent and screen bounds are unset. An empty string is not null, so neither
  check fires. The comment's claim ("a valid, tiny, off-key node instead of throwing") is accurate.
- **The "cheap no-op when TalkBack is off" claim is true**, and this mattered enough to verify in the
  source rather than trust: `invalidateAccessibilityRoot()` is called from `updateFlags`, which runs
  on modifier changes. `invalidateRoot()` → `invalidateVirtualView(HOST_ID, SUBTREE)` →
  `if (virtualViewId != INVALID_ID && mManager.isEnabled())`. With no a11y service that is a field
  read and a branch. Safe on the hot path, as the comment says.
- **The three mandated host overrides are all present and correctly gated.**
  `dispatchHoverEvent` (guarded by `_a11yManager?.isTouchExplorationEnabled == true`, falling
  through to `super`), `dispatchKeyEvent` (`helper.dispatchKeyEvent(event) || super...`, so hardware
  key input is untouched), and `onFocusChanged` (calls `super` first, then the helper, which is the
  documented order).
- **No `onHoverEvent` override is needed.** The framework's snippet lists three overrides, not four;
  `dispatchHoverEvent` is the hook.
- **`getVirtualViewAt` matches the geometry's own half-open cell test** (`x >= left && x < right`),
  so the box TalkBack highlights is the box a finger hits — by construction, since both call
  `rowSpans`.
- **The host cannot trip the "real and virtual children" RuntimeException.** `Keyboard2View` is a
  plain `View` (not a `ViewGroup`) and calls `addView`/`removeView`/`removeAllViews` nowhere, so its
  real child count is always 0 — the one pre-existing way an `ExploreByTouchHelper` can hard-crash
  is structurally impossible here.
- **`onPerformActionForVirtualView` returns `false` for anything but `ACTION_CLICK`**, which is what
  lets `ExploreByTouchHelper` fall through for the focus actions it manages itself. It also sends
  `TYPE_VIEW_CLICKED` after activating, so the click is announced.
- **The checked state is real, not decorative.** `accessibilityCheckedState` returns `(true, checked)`
  only for Shift and CapsLock, and `checked` comes from `Pointers.getKeyFlags` masked with
  `FLAG_P_LATCHED or FLAG_P_LOCKED`, with `flags != -1` guarding an unknown key.
- **Activation goes through `Pointers`, not the key handler**, so a TalkBack click gets the same
  modifier latching, haptics and modifier application as a tap. This is the subtle one: calling
  `Config.handler.key_down/key_up` directly would silently skip latching, because
  `KeyEventHandler.key_up` no-ops modifiers.
- **`KeyLabels` names all 80 string resources it references, and none of them is blank.** Verified by
  parsing `res/values/strings.xml`: no missing entry, no whitespace-only label. So no key can fall
  through to a resource-not-found or an empty announcement.
- **`KeyLabels`'s hard rule holds.** A font key with no explicit mapping never echoes its
  Private-Use-Area glyph (0xE000–0xF8FF); it falls back to a Kind + value name. `KeyLabelsTest`
  covers this, and the reason is concrete: TTS reads PUA as "private use character U+E00A" or
  silence.
- **`KeyModifier.modify` is applied before labelling**, so a latched Shift announces "A", not "a",
  and an `action` key that has been swapped for Enter announces "Enter", not the action label —
  because the swap happens on the real `KeyValue` in `LayoutModifier`, not just visually.

## Three things I was wrong about

Recorded because each one looked like a finding, and a future pass should not spend time
re-deriving them.

1. **`rowAt` "ignores `row.shift` while the rect walk honours it".** It does not. Both accumulate
   `y += (shift + height) * rowHeight`, so the row boundary is identical; `keyAt`'s only special
   handling of `shift` is in the *x* walk. Modelled it numerically, then dropped it.
2. **"The `\'` in `key_descr_copy_private` and `cmd_append_possessive` is a broken escape that will
   be read aloud as a backslash."** `\'` is a real Android string escape, and aapt yields a plain
   apostrophe: *"Private copy (into Mr. Brown's Keyboard only, not the system clipboard)"*. And
   "Mr. Brown's Keyboard" is this product's own name — 26 occurrences in `strings.xml`, 0 of
   "CleverKeys" — so the label is correct, not a rebrand leftover.
3. **"A subkey with accent labels gives TalkBack garbage PUA glyphs."** It would, but it never
   happens: every corner of the Spanish layout I checked carries a real character (`è`, `é`, `<`,
   `.`, `ç`), not `accent_*` — accented letters are composed by the `accent_aigu` dead key, and dead
   keys that *are* referenced resolve to `key_descr_dead_key` ("Dead key"), which is a sane label.

## What this pass did not do

- **It did not fix V6.** Choosing between `ACTION_LONG_CLICK`, a custom subkey action and one node
  per symbol needs a screen-reader user's judgement; guessing would be worse than reporting.
- **It did not write the Robolectric test V7 names.** That is test infrastructure and its own change.
- **It did not test on a device with TalkBack.** Every claim is from source, the layout XML, and the
  androidx framework source fetched for the two contracts above.
- **It did not review `KeyLabels`' coverage of every exotic key** — only that all 80 referenced
  resources exist and are non-blank, plus the 11 behaviours its own test pins.
- **It did not touch `CornerLabelPolicy`** (the *rendering* decision about which corners to draw).
  That is outside `a11y/` and is separate from the tree: the policy decides what is painted, the
  helper decides what is announced, and only the latter has the gap described here.
- **`gif/` (2484 LOC), `ml/` (`SwipeMLDataStore` 735, `SwipeMLData` 532), the geometric
  `GesturePreprocessor`/`TemplateIndex`/`TemplateCache`, settings persistence, the emoji grid,
  `Pointers.kt`, `KeyEventHandler.kt`, `TrigramStore`, `BigramModel` remain unhunted.**

## Verification

Typecheck against the base (`scripts/ci/offline/typecheck.sh --tests 97ab129`): **0 diagnostics novel
to the tree**; the 48 in the new file are the expected missing Truth/JUnit noise. No production file
changed in this wave.

| Workflow | Run (on `c8d5174`) | Result |
|---|---|---|
| `Build APK` | `44922370047` | **green** |
| `Full unit-test suite` | `44922377169` | **green** — **4863 tests, 0 failed, 134 skipped in 434 classes** |
| `Emulator smoke test` | `44922370137` | **green** |

`4863 / 434` is +4 tests and +1 class over part 7's tip, which is exactly
`AccessibilitySubkeyReachTest`'s four cases. `skipped` stayed at **134**. No production file changed
in this wave, so the APK is functionally identical to part 7's.

## It took four commits to get one test green

Worth recording, because the fix history is more instructive than the fix. Three consecutive CI runs
failed, each time on something I had already "fixed":

| Run | Failure | What I had believed |
|---|---|---|
| `44919059108` | drift test: ~30 classes unregistered; my `hasSize(2)` on a one-key row | that a bracketed comment was harmless, and that two keys are two nodes |
| `44920544622` | **identical** | that my fix was in the commit — it was, but my *replacement* comment quoted the regex, brackets and all |
| `44921432688` | drift green; `expected 2 but was 1` | that making the two keys distinct was enough — the fixture's `shift = 1f` made each key two widths wide |
| `44922377169` | **green** | — |

Three distinct wrong mental models of the same four-line test, in sequence: `Key` is a data class so
"two keys" were one; `shift` is a leading offset so "two keys" were 200 px each inside a 200 px host;
and a comment is not inert when a regex reads it. The last run was the first one where I ported
`computeAccessibilityKeyRects`'s accumulation into python3 line by line and ran it **before**
pushing — which is the step that had been missing on the first three.

The lesson generalises past this wave: **the offline typecheck cannot catch a wrong expectation.**
It compiles the tree and reports novel diagnostics; it says nothing about whether `hasSize(2)` is
true. Modelling the assertion against the real algorithm is the only cheap substitute for running
the suite, and I skipped it three times because the assertion "obviously" held.
