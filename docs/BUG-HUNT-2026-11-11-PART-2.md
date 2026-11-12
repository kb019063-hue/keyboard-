# Keyboard bug hunt — 2026-11-11, part 2

## Why this part exists

[Part 1](BUG-HUNT-2026-11-11.md) stopped early because `main` did not build, and ended with a
list of what it had not looked at. The owner asked for the rest. This is that pass: the typing
path, the clipboard pane, the tools panel and toolbar, the floating keyboard, dictation, logging
and the size of the APK. **It is still not a pass over the whole codebase**; what was read and
what was not is listed at the end.

Seven defects are fixed here, each in its own commit with tests. Three more are reported and
left alone, because the right fix is a design decision or cannot be judged without a phone.
Five smaller notes follow them.

| # | Finding | State |
|---|---|---|
| F1 | The clipboard list jumped back to page 1 and collapsed its rows on every refresh; the To-do tab did it always, and the search box blanked the list on every keystroke | fixed, with tests |
| F2 | The installed build wrote the app and field you were typing in to the system log, on every focus | fixed, pinned |
| F3 | The tools panel kept the previous theme's colours after a theme change | fixed, pinned |
| F5 | Three confirmations and the toolbar's long-press labels could not be seen on Android 13+ | fixed, pinned; the tooltip is not seen on a device |
| F6 | The floating keyboard's resize knob did nothing at an ordinary drag speed | fixed, with tests |
| F8 | The suggestion bar wrote the words you add to your dictionary to the system log | fixed, pinned |
| D-a | The watchdog that ends a stuck dictation session could miss by one millisecond and then never run | fixed, with tests |
| F7 | A floating keyboard still pushes the app up as if it were docked | reported, needs a phone |
| D-b | Dictation's "language negotiation" never happens: the answer is read from the wrong place | reported, needs a phone to fix safely |
| D-c | The first mic press of a process has no watchdog until listening starts | reported, low |
| — | Five smaller notes | reported |

The labels are the ones the code comments and tests use. There is no F4: that lead did not hold
up.

## F1. The clipboard list reset itself on every refresh

**What you would have seen.** On the To-do tab: expand a row or two and use any row button
(pin, status, done, delete). Every expanded row collapsed again, and past the first 100 items
the list also jumped back to page 1. The same on any tab while a search or a filter was active,
and after swiping a row away. In the search box, the list went blank on each letter and came
back a moment later.

**Why.** PR #25 (`751a441`, 2026-11-10) moved clipboard search off the UI thread. Its version of
`ClipboardHistoryView.applyFilter` did this whenever the view was on screen and anything at all
was filtering the list:

1. empty the list and redraw it;
2. start a coroutine that filters in the background (after 100 ms while a search is typed);
3. publish the result.

Step 1 is the defect. Redrawing an empty list clamps the page number to the first page and
forgets every expanded row, whatever the caller asked for. A reload passes `resetView = false`
precisely to keep both, and `onPendingDeletionChanged` documents "page and expand state kept".

"Anything filtering" is wider than a search: a date, tag, size or private-only filter, a row
hidden by swipe-to-delete, and the To-do tab's status filter. That last one is on by default
(only active items are shown), so the To-do tab took this path on every refresh. And every row
action (pin, to-do, status, delete) ends in a refresh. The comment in `applyPagination` says
what was lost: "not on action reloads (pin, todo, delete). This prevents entries from collapsing
after an action."

**Why the tests did not see it.** They build the view without a window, and a view without a
window has no coroutine scope, so it always filtered in place. The broken path could not be
reached from a unit test.

**The fix.** Only a text query reads clipboard contents; every other filter compares a few
fields per row, which costs less than the redraw it feeds. So:

- with no text query, the filter runs in place, as it did before PR #25;
- with a text query, it still runs in the background, but the rows already on screen stay until
  the result replaces them. `filterPending` still blocks "select all matching" and the bulk
  actions while a query is running, which was the stated reason for emptying the list;
- a row that was just swiped away is taken off the screen at once, even while a query runs.

**Tests.** Four new ones attach a coroutine scope whose steps the test runs by hand, so the
state between "the filter started" and "its result arrived" can be asserted:

- `aReloadOfTheTodoTabKeepsThePageAndTheExpandedRow`
- `aSearchLeavesTheOldRowsOnScreenUntilItsResultArrives`
- `aReloadUnderASearchKeepsThePageAndTheExpandedRow`
- `aSwipedRowLeavesAtOnceWhileASearchIsStillRunning`

`docs/CLIPBOARD-SEARCH-2026-11-10.md` said the old rows were hidden while a query ran; it now
says what the code does. The instrumented test `ClipboardFilterDialogTest` asserted "0 rows
while pending" and was changed to match, but no workflow compiles `src/androidTest`, so that
edit is unverified.

## F2. The installed build logged every field you typed in

`build.gradle` set `R.bool.debug_logs` to true for the debug build type, unconditionally. The
service passes it to `Logs.set_debug_logs`, and with it on, `Logs.debug_startup_input_view`
runs on every `onStartInputView` and prints the focused field's `EditorInfo` to logcat: the
app's package name, the field's hint, label, id and name, its input type and its extras.

That is not the text you type. It is a record of which app and which field you were in, each
time a field gained focus. Reading logcat needs adb or a privileged app, and a bug report
includes it.

The debug build is the one that is installed (`AGENTS.md`, "The installed build is the DEBUG
variant"). Its other debug switch, `ENABLE_VERBOSE_LOGGING`, had already been made opt-in for
that reason; the comment beside it explains why at length. This line was two lines above it and
was left behind.

**The fix.** `debug_logs` now follows the same `LOCAL_BUILD=true` switch.
`VerboseLogRatchetTest.theFocusedFieldDumpIsOptInLikeTheVerboseFlag` pins the `build.gradle`
line, the default in `res/values/values.xml`, and the place the service reads it.

The emulator smoke test does not depend on that output: its logcat witnesses are
`DictationController` and `MoreToolsPanel` lines, which use their own tags.

## F3. The tools panel kept the old theme

The tools panel (PR #22) is built once, kept by `KeyboardReceiver`, and re-bound each time it
opens. It reads every colour from the `Theme` it was constructed with. On a theme change the
service inflates a new keyboard view, which has a new `Theme`, and rebuilds the emoji and
clipboard panes. Nothing rebuilt this panel, so it went on showing the previous theme's colours
(dark tiles on a light keyboard, or the reverse) until the keyboard process was restarted.

**The fix.** `showToolsPanel` reuses the kept panel only if it was built for the `Theme` the
keyboard is drawn with now (`MoreToolsPanelView.isBuiltFor`, an identity check), and builds a
new one otherwise. Pinned in `MoreToolsPanelTest`, next to the existing pin on `onToolTap`.

## F5. Confirmations that could not be seen

Android 13 and later show an app's toasts only while it holds the notification permission or
has an activity in front. A keyboard has neither, and this app does not declare
`POST_NOTIFICATIONS`, so the toasts it raises from the keyboard are dropped. The repository
already works to that rule: `ImeToastBanDriftTest` bans `Toast` in `KeyboardReceiver.kt` and
`KeyEventHandler.kt` ("Toast is IME-suppressed on Android 13+"). Two more places still used one.

**The split, floating and incognito switches** (`CleverKeysService`) confirmed with a toast.
Split and floating change the keyboard's shape, so you can see that they took effect.
Incognito changes nothing visible; its own comment called the toast "the only confirmation a
toolbar key can give". From a toolbar button or a swipe there was none.

They now use the suggestion strip, like the rest of the keyboard's feedback, and the file is
on the ban list. One limit: while a pane covers the strip (the tools panel included), the
message is not visible. There the tile that lights up is the confirmation, as it was.

**The toolbar's long-press labels** (`KeyboardToolbar`). A long-press on a button named it with
a toast, from a listener that returned `true`. On Android 13+ that showed nothing for two
reasons at once: the toast is dropped, and a long-press that a listener has claimed never
reaches the view's own tooltip, which the same code had already set. From API 26 the long-press
is now left to the tooltip. The toast stays for API 24–25 only, where there is no tooltip and
toasts still work. A pin in `ImeToastBanDriftTest` keeps the listener and its toast inside that
branch.

**Not seen on a device:** that Android draws a tooltip over a keyboard window. The clipboard
pane's icon buttons already rely on the same mechanism. If the labels still do not appear, the
fallback is to show the name in the suggestion strip instead.

Other toasts raised from the keyboard remain and were not changed: "copied" in the clipboard
pane (Android 13+ shows its own clipboard confirmation), a clipboard-service warning, the
custom short-swipe executor's error messages, and the default-keyboard prompt. The
short-swipe errors are the ones worth converting next: a custom action that fails says nothing.

## F6. The floating keyboard's resize knob

`resizeFloating` turned each touch event into a width change on its own, in whole numbers:
`dx * 100 / screenWidth`. One percent of a 1080 px screen is 10.8 px, and a touch event carries
a few pixels, so nearly every event rounded to zero and its pixels were thrown away. At an
ordinary drag speed the knob did nothing. A fast flick moved the width, by less than the finger
had travelled.

**The fix.** `FloatingConfig.resizeStep` carries the pixels that have not yet added up to a
percent from one event to the next, in exact integer arithmetic. The width now depends on how
far the finger moved, not on how the system divided that movement into events. Pixels dragged
past the minimum or maximum are dropped rather than banked, so the keyboard starts widening
again as soon as the finger turns round. The service lays the window out again only when the
width actually changed.

**Tests.** Six new cases in `FloatingConfigTest`: a slow drag, the same distance in different
event sizes, carry and reversal, both ends, bad inputs, and a source pin on the service (which
is where the division was).

## F8. The suggestion bar logged the words you add to your dictionary

`SuggestionBar.showMessage` wrote every temporary message to logcat with an ungated `Log.d`.
The message is often your own word: "Added “…” to dictionary", "Removed “…” from dictionary",
"Swiping now prefers “…”". `SuggestionHandler`, which builds those, is careful to log them
without the word. The bar then printed the whole message, on the installed build.

That line, and the same pattern in `showEmojiSearchStatus`, are now behind
`ENABLE_VERBOSE_LOGGING`. The log ratchet's ceiling drops from 246 to 244 with them, and
`VerboseLogRatchetTest.suggestionBarDoesNotLogTheMessageItShows` pins it.

## D-a. The dictation watchdog could miss by one millisecond

A speech recogniser that never calls back leaves the mic key stuck; the watchdog added on
2026-11-09 ends such a session after 60 seconds. It is a `Handler.postDelayed`, which counts in
`SystemClock.uptimeMillis()`. When it ran, it measured the elapsed time with
`System.currentTimeMillis()`, a different clock.

The watchdog runs once, and its boundary is exact: 60 000 ms expires, 59 999 does not
(`DictationSessionWatchdogTest` pins that on purpose). So the two clocks had to agree to the
millisecond. They do not. Each is cut down to whole milliseconds at its own sub-millisecond
phase, so a timer that fires when uptime reads 60 000 ms can find the wall clock reading
59 999. The check then said "not yet", nothing ran it again, and the stuck session it exists to
end stayed until the keyboard was hidden. A wall clock set backwards during the session did
the same.

How often depends on the phone: the offset between the two clocks is fixed until the time is
next set, and for some offsets it never happens. A script that follows the same steps
(`time.time()` against `time.monotonic()`) read short 2 times in 3,000 in this sandbox, where
wake-ups are slow, and 0 times in 3,000 on one clock.

**The fix.** Both readings are uptime now, so the timer cannot fire before the limit it is
checked against.

**Tests.** A source pin on the two readings, and the arithmetic itself with the two clocks
modelled as one instant read through different phases: never short on the timer's own clock;
short for 450 of 1,000 phase combinations on another, with no wake-up latency.

## F7. A floating keyboard still pushes the app up (reported)

`onComputeInsets` adjusts the insets only for the minimised keyboard. In floating mode the
window is smaller and sits elsewhere, but as the code reads, the app is still told to make room
for it as if it were docked at the bottom, so the app keeps an empty band there. This was read,
not seen.

The likely fix is to report no content inset while floating (`contentTopInsets` and
`visibleTopInsets` set to the window height), which is what a floating keyboard normally does.
That changes how every app lays out while the keyboard floats, and it can only be judged on a
phone, so it is left for the owner. `docs/PHASE3-PART3-FLOATING-DESIGN.md` does not mention
insets.

## D-b. Dictation's language negotiation never happens (reported)

`docs/DICTATION-NO-MATCH-2026-11-04.md` describes asking the recogniser which languages it
supports and then choosing the closest one, so that a tag it has no model for (`en-GH`) becomes
one it can decode. `DictationController.queryLanguagesOnce` is that question. As written it
cannot produce an answer, for two reasons that can be read off the code:

1. **The reply is read from the wrong place.** The question is an ordered broadcast, and the
   reply to an ordered broadcast arrives in the result extras (`getResultExtras(true)`). The
   receiver reads `EXTRA_SUPPORTED_LANGUAGES` and `EXTRA_LANGUAGE_PREFERENCE` from the intent it
   is handed, which is the intent that was sent and carries neither. Both come back null on
   every phone.
2. **The question is not addressed to anyone.** `ACTION_GET_LANGUAGE_DETAILS` is meant to be
   sent to one named receiver (`RecognizerIntent.getVoiceDetailsIntent` builds that intent). It
   is sent without a target, and a broadcast without a target is not delivered to a receiver
   declared in the manifest of an app built for Android 8 or later.

With an empty answer `VoiceLanguagePolicy.pick` returns the wanted tag unchanged, which is what
its unit tests say it should do for an empty list. So the recogniser is always asked for the
keyboard's own tag, and what rescues an unsupported one is the other mechanism in that
document: one silent retry with no language at all (`shouldRetryWithDefault`).

The receiver also calls `unregisterReceiver` on itself, although it was never registered. That
throws every time and is caught: one warning in the log per process.

**Why this is not fixed here.** Making the question work is easy to write and cannot be judged
without a phone. It would change which language tag real dictation is started with, on the
strength of whatever list the phone's recogniser returns. For an English keyboard and a
recogniser whose stated preference is `en-US`, `pick` would start asking for `en-US`, where
today it asks for plain `en` and leaves the variant to the recogniser. Whether that is better
for the person speaking is a question for a test on the owner's phone, not for a code review.
Two ways to do it properly: address the broadcast with `getVoiceDetailsIntent` and read the
result extras, or on Android 13+ use `SpeechRecognizer.checkRecognitionSupport`. Until one of
them is tried on a device, read the 11-04 document's "negotiated" as "passed through"; that
document now carries a note saying so.

## D-c. The first mic press has no watchdog for a moment (reported, low)

`sessionActive` is set when the key is pressed, but the watchdog is armed only once listening
starts. On the first press in a process the language question above is outstanding in between,
so a hang there has no timer. Hiding the keyboard cancels the session, which bounds it.

## Smaller notes (reported)

- **"Select word" looks at 50 characters** on each side of the cursor
  (`Keyboard2View.kt`, the text-menu handler). A longer word is selected in part. Open since the
  10-31 hunt (N3).
- **Switching clipboard tabs** changes `currentTab` at once and loads the new rows a moment
  later. In between, the old tab's rows are on screen and an action on one would use the new
  tab's rules. The window is a few tens of milliseconds, so it would take a deliberate double
  tap. It predates PR #25.
- **A theme change while a pane is open.** A layout bound to another theme can change the theme
  mid-typing. The input views are rebuilt without the pane, but `KeyboardReceiver` still
  believes it is showing, so the next press of that pane's key "closes" a pane that is no longer
  there and appears to do nothing; the press after that opens it. Rare. Read, not reproduced,
  while looking at F3.
- **The "Debug Information" switch** in Advanced settings turns the field dump of F2 on only
  until the keyboard process restarts; the service reads the build flag at start-up, not the
  setting. With F2 fixed that is now the only way to get the dump on a CI build, so it is worth
  knowing that it does not persist.
- **`Pointers.set_fake_pointer_state`** notifies the view twice when it adds a fake pointer
  (`add_fake_pointer` already does). One extra redraw request.

## Checked and found sound

So they are not raised again:

- **Nothing in the service writes a preference per keystroke.** All eight `.edit()` sites in
  `CleverKeysService` are user actions (the five tool switches, the one-handed side, and the
  floating position and width when a drag ends). Part 1 left this open.
- **Word prediction does not run on the UI thread.** `updatePredictionsForCurrentWord` hands
  it to a cancellable task; a keystroke only replaces the pending one.
- **The four tool setters that assign `Config` fields directly are correct.** Only
  `split_enabled` is mirrored into `ConfigSnapshot`, and that one goes through `edit { }`.
- **`predictionCoordinator.initialize()` running twice** is a guarded no-op, locked or unlocked.
- **`en_enhanced.json` is not a stray duplicate** of the binary dictionary. The CTC English
  lexicon reads it on purpose (`CtcEngineAdapter`, "do NOT unify").
- **`PerformanceProfiler`** has no callers, so its trace flag costs nothing.
- **The GIF pane makes no network call.** It composes links from a local pack. No production
  source opens a socket; `ReleasePackagingDriftTest` already pins that.
- **Full-backup import** (the reading loop of `importFullBackup`) bounds every read, rejects
  duplicate entries, checks the manifest before applying anything, and stages media before it
  touches the live tree.

## APK size

About 36 MB per ABI. The assets account for 18 MB of it before compression: 12.0 MB of
dictionaries, 3.0 MB for the swipe model and 2.6 MB for the language model. The rest could not
be measured here, because the sandbox cannot download the APK; it is presumably the ONNX
runtime's native library and unshrunk debug code. A build step that prints the size of each
part would turn that into a number. Nothing was changed.

## Not hunted

The inside of the swipe decoders (CTC and geometric), the settings screens, the emoji grid,
the clipboard database's SQL, and backup and restore beyond the import loop named above.

## How this was verified

**By CI**, which is the compiler here. Each fix was pushed and built before the next where the
runs were allowed to finish:

| Commit | What | Build APK | Emulator smoke |
|---|---|---|---|
| `1938b93` | F6 | 43108921936 | 43108921933 |
| `2c3c9cc` | F1, F2 | 43110733657 | 43110733595 |
| `8376c44` | F3, F5 | 43120585808 | 43120585868 |
| `5c5d051` | D-a | 43122385387 | 43122385341 |
| `3b94fe5` | F8 | 43124145012 | 43124145102 |

Every run in the table succeeded. The smoke test's verdict line and its annotations are the same
on all of them as on `main` before this branch. That includes the three checks it skips on the
emulator (the tools panel, the mic key and the keyboard-clipping measurement), so the smoke test
says nothing about F3, F5 or D-a.

The build job runs a hand-picked subset of the unit tests on each push: 831 tests at the end of
part 1, 843 on `3b94fe5`, none failed. That subset contains the tests for F1, F2, F6 and F8. It
does **not** contain the tests for F3, F5 and D-a (`MoreToolsPanelTest`, `ImeToastBanDriftTest`,
`DictationSessionWatchdogTest`). Every push compiled them, but CI runs them only in the full
suite, which runs on the pull request. That run, and the checks on the final commit, are listed
in the pull request's description.

**Before each push**, in the sandbox:

- *Compilation.* `scripts/ci/offline/typecheck.sh dcdf2df` reports nothing new in the main
  sources. With `--tests` it lists three lines: JUnit's `assertFalse` and `assertEquals`, used
  in two test files that had not used them before. The check cannot see JUnit, so that is
  expected, and CI compiles those files. The tool gained `kotlinx.coroutines` on its classpath
  in this branch (`df1fac5`), so coroutine code such as `applyFilter` is now actually checked.
- *Tests that need no Android classes were run*, against small stand-ins for JUnit and Truth:
  `FloatingConfigTest`, `DictationSessionWatchdogTest`, `MoreToolsPanelTest`,
  `DictationLanguageSourceTest`, and 25 classes that only read the sources (`ImeToastBanDriftTest`,
  `VerboseLogRatchetTest` and `ReleasePackagingDriftTest` among them), to catch a pin broken by
  an edit elsewhere. All passed on the final tree.
- *The fixes were broken on purpose* to see a test fail: the resize step without a carry (3
  tests fail) and with a banked overshoot (1), a `Toast` import put back in the service (1), the
  session stamp returned to the wall clock (1), and the log ceiling lowered by one (1).
  **Not F1 and not F3.** The F1 tests need MockK, so they run only in CI, and the branch was not
  broken there on purpose. They were read against the old code instead: each asserts something
  the old code did not do (no deferred step on the To-do tab, the same rows on screen while a
  search runs, no redraw before the result). The F3 pin is a check on the source text.
- *The coroutine mechanics the F1 tests rely on* (a dispatcher that queues steps, `delay`, and
  `withContext(Dispatchers.Default)` handing back) were run on a small model first.

Those stand-ins are not the real libraries, and the clipboard view tests need MockK and
Objenesis, which the sandbox does not have. For those, the CI run is the only evidence.

**Not verified at all:** anything on a real device. In particular: the clipboard list keeping
its place (F1), the tools panel after a theme change (F3), the strip messages and the toolbar
tooltips (F5), and how the resize knob feels (F6). The edit to the instrumented test
`ClipboardFilterDialogTest` is compiled by no workflow.
