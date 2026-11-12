# Keyboard latency, second pass: measured (2026-11-12)

The report: the keyboard is slow, lags while typing, and appears late.

The first pass ([`KEYBOARD-LATENCY-2026-11-10.md`](KEYBOARD-LATENCY-2026-11-10.md)) reduced work
that looked wasteful in the code and said so plainly: no measurement, no demonstrated cause. This
pass built the instruments first, measured, and changed only what a number pointed at.

Read the limits section before quoting any figure from here. Nothing in this document was
measured on a phone.

## Summary

| | Before | After | Evidence |
|---|---|---|---|
| Rotating the screen with the keyboard open | the keyboard's process died | survives, same pid | smoke section 10, thirteen runs since the fix |
| Input view re-installed for each focused field | 1 per field, keys re-attached 2x | 0 and 0 | `install=`, `attach=` |
| Keyboard height changes while typing one sentence | 5 | 1 | `resize=` |
| Re-reads of the app's text while typing 21 keys | 21 | 12 | `sync=`: 21 in run 43230232862; 12 in nine of the ten runs since, 10 in the one on a slow runner |
| `onCreate` of the installed (CI) build | 742 ms (debuggable) | 271 ms | same boot, one sample each |
| Sections shown by the settings drawer | the 11 that fit; the list did not scroll | all 19: the last one is reached with one drag | smoke 9b, two runs |
| Apostrophe tables read at set-up | 154 KB of JSON parsed with `org.json` on the main thread | read from a 43 KB binary store: no parse | finding 8 |

What the owner asked about, in order:

- **Appears late.** With the keyboard's process alive, its own share of a showing is about 5 ms
  (`onStartInputView`) plus a few milliseconds of layout; the rest of the 54-209 ms measured is the
  system's window and the emulator's software renderer. (In three of the ten runs that name the
  steps it was 31 to 59 ms, with one step held up each time: the look at the clipboard twice,
  the strip once; see the notes under the table.) With the process dead, everything is set up
  from nothing: about half a second on an ordinary CI runner (73 ms before `onCreate`, 166 ms
  in it, 301 ms from the first request to the first picture), 1.1 s on a slow one, and a phone
  as old as the owner's is likely slower than either. So the question that decides it is how
  often the phone throws the keyboard away, and only the phone can answer it. The Keyboard
  speed card now records that. See "The phone" below. One step of that set-up was the slowest
  in three runs running and was left alone until the phone has given a number (finding 8).
- **Lags while typing.** Handling a key takes under a millisecond here and drawing the keys about
  as long. What was wrong is around the key, not in it: the keyboard's height changed five times
  in one sentence (each one resizes the app behind it too), and after every single letter the
  keyboard went back to the app to re-read the text and searched the dictionary a second time.
  Both are fixed. One thing was measured and left alone: finding a letter's suggestions takes
  about 35 ms on average on the runner and up to 249 ms (finding 7), which on a slow phone is
  long enough to be the lag. Whether any of this is what the owner feels is for the phone to say.
- **Slow in general.** The build the owner installs was debuggable, which makes ART run the same
  code without its optimised form. That is off now. (Before this pass it had also logged
  verbosely on every touch; that was switched off earlier, see `ENABLE_VERBOSE_LOGGING` in
  `build.gradle`.)

## The instruments

- **`LatencyMeter`** (`src/main/kotlin/tribixbite/cleverkeys/LatencyMeter.kt`). Pure Kotlin, a
  handful of clock reads on paths that already run. Durations and counts only; never text, never
  an app's name. Logged under the tag `CKLatency`: one line per process start (`cold ...`) and one
  per showing (`session ...`).
- **Smoke probe 6c** (`scripts/ci/emulator-smoke.sh`, `latency_probe`). A warm-up showing, then a
  timed one in which "a sad lad had a salad" is tapped on the real keys, 0.7 s apart: 21 presses,
  16 letters, 5 spaces. The lines reach the check run as `Latency` notices, which is the only
  channel readable from the development sandbox. When the script has to dismiss a "not
  responding" dialog, the first one of a run adds a line there too (`anr first-dialog
  whose=not-ours subject='…' package='…'`), so that a green run with `anr=1` in its verdict
  says whether the dialog was the emulator's launcher or this app.
- **The Keyboard speed card** (Settings, Information & Actions). The same numbers as sentences.
  Tap refreshes, long-press copies. This is how a number comes back from the phone.
- **`ProcessStartLog`**. A line for every time the keyboard is created and one for every time it
  is let go of normally, in a private file. The card's second line is made from it.

Fields of the `session` line, in the order they appear:

| Field | Meaning |
|---|---|
| `start=` | `onStartInputView`, ms |
| `shown=` | from `onStartInputView` to the end of the first draw |
| `presses=`, `touch=avg/max` | key presses, and the time to handle a touch |
| `draw=N:avg/max` | draws of the key view |
| `frames=`, `frame=`, `ui=`, `gpu=`, `slow=` | the platform's per-frame timing (`FrameMetrics`) for the keyboard window; `slow` counts frames over 16.7 ms |
| `avg=` | the average frame by stage: wait, input, layout, draw, sync, gpu |
| `worst=`, `appear=` | the slowest frame, and the slowest frame that showed the window: total first, then the same stages |
| `tm=N:avg/max`, `tl=N:avg/max` | measure and layout passes of the keyboard's own outermost view |
| `strip=N:avg/max` | rebinds of the suggestion strip (a rebind with identical content is skipped and not counted) |
| `sync=N:avg/max` | re-reads of the app's text after a caret report, and how long each held the main thread |
| `predict=N:avg/max` | from a typed letter being handed to the predictor to its suggestions being ready |
| `attach=`, `measure=`, `install=`, `resize=` | work that should not repeat: key view re-attached, re-measured, input view re-installed, height changed while open |

## What was measured

All on the CI emulator: Pixel 7 profile, API 34, x86_64, two cores, 2 GB, software GPU. One sample
per run. Times in ms; `a/b` is average/worst.

| Run | Commit | Cold: create / shown | Warm: start / shown | touch | strip | sync | attach / install / resize |
|---|---|---|---|---|---|---|---|
| 43142217574 | `8c6517c` before the fixes | 202 / 431 | 45.9 / 150 | 7.09/142.76 | 12 | - | 2 / 1 / 5 |
| same run | same, debuggable build | 742 / 585 | | | | | |
| same run | same, non-debuggable again | 271 / 382 | | | | | |
| 43151960834 | `c3bb0b8` band + install fixes | 144 / 460 | 5.8 / 166 | 0.80/7.53 | 33 | - | 0 / 0 / 1 |
| 43154964666 | `7b94c79` | 166 / 301 | 4.8 / 66 | 0.69/9.99 | 33 | - | 0 / 0 / 1 |
| 43230232862 | `46731a0` counter added | 537 / 420 | 12.7 / 156 | 0.85/4.10 | 34 | 21 | 0 / 0 / 1 |
| 43234083019 | `00e258d` re-read change | 105 / 432 | 4.0 / 54 | 0.97/9.17 | 33 | 12 | 0 / 0 / 1 |
| 43239991683 | `ee66dc1` card wording only | 111 / 287 | 5.6 / 101 | 1.09/21.28 | 33 | 12 | 0 / 0 / 1 |
| 43254347006 | `aef226d` CI files only | 224 / 393 | 58.7 / 115 | 1.06/9.06 | 27 | 10 | 0 / 0 / 1 |
| 43259328923 | `5ea41d3` a line on the card, CI files | 224 / 397 | 5.6 / 114 | 0.75/8.30 | 33 | 12 | 0 / 0 / 1 |
| 43266514425 | `d6f1c3b` CI files and documents only | 191 / 369 | 31.1 / 209 | 0.58/3.49 | 33 | 12 | 0 / 0 / 1 |
| 43270968715 | `5dacda5` CI files and documents only | 396 / 361 | 6.1 / 98 | 0.75/6.71 | 33 | 12 | 0 / 0 / 1 |
| 43275071825 | `15f8f8a` CI files and documents only | 124 / 500 | 4.8 / 126 | 0.58/2.83 | 33 | 12 | 0 / 0 / 1 |
| 43278438056 | `d85ea25` CI files and documents only | 139 / 417 | 33.2 / 126 | 0.59/3.16 | 34 | 12 | 0 / 0 / 1 |
| 43282791015 | `e8b633d` CI files and documents only | 101 / 367 | 1.5 / 98 | 0.76/13.11 | 33 | 12 | 0 / 0 / 1 |
| 43285608845 | `eed8f71` the merge into `main` | 466 / 473 | 2.5 / 103 | 0.89/6.89 | 34 | 12 | 0 / 0 / 1 |

Notes on the table:

- The first row's probe typed without pauses and without a warm-up showing; from the second row
  on the probe is the one described above. `strip=12` against `33` is that difference (a process
  that has just started has not loaded its dictionary), not a regression.
- Run 43230232862 landed on a runner about three times slower than the others: every duration in
  it is two to four times the previous run's, including the 187 ms that passed before `onCreate`
  began. It is the reason durations are not compared across runs here. It is also the nearest
  thing in this table to a slow device: set up from nothing took 187 + 537 ms and the first
  picture came 420 ms after that.
- That run also failed, on two focus checks: the emulator's own launcher stopped responding
  and was in front when the script expected this app. Everything after them passed, and the
  same script passed whole on the runs before and after it.
- Runs 43278438056 and 43285608845 (the second is `main` after the merge) failed the same two
  checks the same way, and are the first to say so themselves: `anr first-dialog whose=not-ours
  subject='Quickstep isn't responding'`, three dialogs each (`anr=3`). Their keyboard, typing,
  card and rotation checks passed and their numbers are in the table; the second also started
  slowly (192 ms before `onCreate`, 466 in it). What the two checks ask is which window the
  system says has focus, and in both runs the answer was one and the same launcher window
  from the first check to the last section, through at least six restarts of the launcher, while this
  app was on screen and being typed into. So the answer was stale, and the checks were
  failing a keyboard that worked. They now take a second witness when the first names
  something else: the activity the system holds resumed, together with this app's own nodes
  on screen (`in_front` in the smoke script). A first attempt at this, a longer wait after
  starting the app, was wrong about the cause, did nothing in the run on `main`, and has been
  removed.
- The last ten rows have `predict=`: `21:34.87/248.72`, `21:31.69/121.19`, `22:75.42/360.66`,
  `21:50.38/247.26`, `22:45.44/231.81`, `21:48.69/224.25`, `22:39.94/214.87`,
  `21:37.44/255.52`, `21:37.93/198.60` and `21:61.31/272.38`. The average runs from 32 to 75 ms
  and the worst case from 121 to 361 ms with no change to the search between them, so both
  follow the runner as much as the code.
- In three rows running (43254347006, 43259328923, 43266514425) the slowest step of `onCreate`
  is the same one: `contractions`, at 79, 85 and 40 ms (the second reads
  `contractions:85,compose:26,inflate:23,predict:20,…` of `create=224`). In the seven samples
  before them it took 13 to 31 ms six times and 76 ms on the slow runner; in the row after
  them, 58 ms, second to `theme:79`. Nothing in those commits touches it, and in 43259328923
  the process itself started quickly (`pre=25`), so "a slow runner" does not cover it. A
  single sample cannot tell that from a regression; the step names printed beside the total
  can, which is what they are for. Finding 8 says what the step is.
- Run 43270968715 started slowly and then ran at ordinary speed. The process took
  356 ms to reach `onCreate` (19 to 91 ms in six other runs, 187 in the first slow one), the
  set-up steps took several times their usual (`theme:79,contractions:58,config:52,
  predict:49,prefs:48`), and the smoke script dismissed a "not responding" dialog once
  (`anr=1` in its verdict; every check passed). Whose dialog it was is not known: the
  annotations did not say. On every earlier occasion that was identified it was the
  emulator's launcher, and the script now reports it (see "The instruments"). The warm part
  is ordinary: `start=6.13`, `predict=21:48.69/224.25`. With 43230232862 this is the nearest
  thing here to a slow device: 356 + 396 ms to set up from nothing, and the first picture
  361 ms after it was asked for.
- Run 43254347006 is another slow runner (frames averaged 213 ms against
  143 to 187), with no change to the app, and it shows two limits of the probe:
  - **Counts move too when the runner is slow.** `sync=10` and `strip=27` against 12 and 33.
    The likely reason, not confirmed: the emulator stalled, taps arrived in a bunch, and two
    re-reads due within 100 ms of each other become one. So counts are comparable between runs
    of similar speed only. `predict=22` is the full count for this sentence, sixteen letters
    plus the second search that follows each of the six first letters (finding 5); 21 in the
    two runs before means one search was overtaken by the next key.
  - **One step of an opening follows the system's speed, not the keyboard's.** The warm
    `start=58.73` was almost all `clip:54.2`, the look at the system clipboard that every
    showing begins with (`ClipboardHistoryService.on_keyboard_shown`). In the four runs on
    ordinary runners that step took 0.6 to 0.9 ms; on the other slow runner (43230232862) it
    took 5.2 of that showing's 12.7 ms, and in the run after this one (43259328923) 4.7 of
    5.6 ms. In 43275071825 it was 3.6 of 4.8 ms, and in that run's first showing after the
    process started it was the largest step of all (`first=clip:33.7`; 3 to 12 ms in the
    other runs' first showings). In 43278438056, the run in which the launcher stopped
    responding three times, it was 31.5 of 33.2 ms. It is a call into the system on the main
    thread, before the first picture, so it takes as long as the system takes to answer, and
    a system that is struggling answers slowly. Nothing was changed: these are single
    samples, and moving the look behind the first picture changes when a copy reaches the
    clipboard history. It is the first thing to change if the phone agrees: the card names
    the steps of the slowest opening, and if it names `clip`, that move is the fix.
  - **And one step can be held up from outside.** In run 43266514425 the warm `start=31.12` was
    almost all `strip:29.2`. On a showing after the first, the keyboard's code in that step
    is a few assignments and the comparison of one short list (`PredictionViewSetup`, the
    branch for a strip that exists already): nothing that leaves the process, and 2.3 ms or
    less in the other runs. So for most of those 29 ms the main thread was held up by
    something other than this code. What it was has not been determined; the emulator has
    two cores, and the renderer and the keyboard's own background threads share them. With
    the two slow looks at the clipboard that makes three of the ten runs that name the
    steps in which a warm opening spent 29 to 54 ms in one step. The card's "at worst … the
    slowest spent" is the line that will show whether openings on the phone have such a tail.

The whole of two runs. 43154964666, before the re-read change:

```
cold pre=73 create=166 start=83 shown=301
  steps=listeners:39,predict:26,theme:24,prefs:17,contractions:17,inflate:15,config:11,clipboard:9
  first=strip:30.2,view:16.8,layout:12.6,receiver:10.8,editor:6.8,clip:5.7
session start=4.83 shown=66.42 presses=21 touch=0.69/9.99 draw=23:0.53/0.81
  frames=67 frame=142.97/368.27 ui=49.08/216.58 gpu=47.97/130.77 slow=63
  avg=33.2:2.0:13.1:0.7:1.6:46.4 worst=368:138:0:77:0:10:59 appear=253:96:3:51:1:0:91
  tm=39:0.22/2.01 tl=36:0.06/1.09 strip=33:0.29/2.20 attach=0 measure=7 install=0 resize=1
```

43234083019, after it:

```
cold pre=28 create=105 start=81 shown=432
  steps=predict:24,prefs:20,theme:18,contractions:13,clipboard:10,config:8,inflate:4,compose:1
  first=view:44.3,receiver:15.6,strip:8.4,layout:7.0,clip:3.8,editor:2.0
session start=3.95 shown=54.26 presses=21 touch=0.97/9.17 draw=23:1.03/3.70
  frames=66 frame=174.13/502.41 ui=62.42/427.53 gpu=49.79/141.04 slow=66
  avg=43.4:4.0:14.2:0.7:1.2:48.6 worst=502:115:0:306:4:0:24 appear=212:113:1:41:3:0:52
  tm=39:0.30/5.38 tl=36:0.04/0.69 strip=33:0.65/7.08 sync=12:1.01/4.87
  predict=21:34.87/248.72 attach=0 measure=7 install=0 resize=1
card title=yes build-line=yes presses-line=yes starts-line=yes
drawer kb=true>false first-screen=11/19 lowest=Accessibility drags=1 last-screen=11:Help-and-FAQ item=yes
rotate verdict=ok pid=2440>2440>2440 shown=true>false>true new-crash=none
```

Twelve re-reads for that sentence is the number worked out beforehand: six first letters, five
spaces, and the one check that runs when typing stops. The run after it (43239991683, a change
to the card's wording only) counted twelve again, with `strip=33 attach=0 install=0 resize=1`.

## What was found, and what was done about each

### 1. Rotating the screen killed the keyboard (fixed, `85892eb`)

A configuration change makes `InputMethodService` build a new input frame. The keyboard's view
still had its old parent, adding it again threw out of `onStartInputView`, and the process died.
The user then waited for a cold start. `InputViewInstall` decides between keep, install and move.
Smoke section 10 rotates there and back and checks the process id: `pid=2440>2440>2440`.

### 2. The input view was re-installed for every field (fixed, same commit)

`setInputView` pulled the keyboard out of its window and put it back each time a text field took
focus: `install=1 attach=2` per showing. Now `install=0 attach=0`. A side effect that is a fix in
its own right: a touch that arrives while an app restarts input is no longer lost.

### 3. The keyboard changed height five times in a sentence (fixed, `1671a46`)

The suggestion band collapsed whenever the strip was empty, which is at every space. Each change
of height is a resize of the keyboard's window and of the app behind it. Now the band stays open
once suggestions have shown in a field that has text, and closes when the field is emptied or
another field takes focus: `resize=5` became `resize=1`.

### 4. The installed build was debuggable (fixed, `8c6517c`)

On the same boot, same commit, one sample each: `onCreate` 742 ms debuggable, 271 ms not. See
`AGENTS.md`, "The installed build is the DEBUG variant".

### 5. The app's text was re-read after every letter (fixed, `00e258d`)

Every caret report is followed 100 ms later by `synchronizeWithCursor`: two reads of the app's
text on the keyboard's main thread, then a second prediction and a second correction sweep for
the word. Typing a letter moves the caret, so this ran after every key: `sync=21` for 21 keys.

On the emulator each of those took 1 to 2 ms, but the emulator's test field lives in the
keyboard's own process and answers at once. On a phone the read waits for the app, and 100 ms
after a key the app is often still busy with the letter it was just given. That wait is on the
thread that handles the next key press.

The second search had a cost of its own that the emulator hides. It was started by cancelling
whatever search was running (`PredictionTaskRunner.cancelAndSubmit`). Here a search is done in
35 ms, long before the 100 ms are up. On a processor where it takes longer than 100 ms, the
re-read cancelled the first search while it was still working and began again, so every
letter's suggestions came later than if nothing had been done. That is read from the code and
from the 35 ms; it has not been observed.

Now a letter added to the end of a word does not cause a prompt re-read. `TypedLetterEcho`
recognises the caret report that merely answers such a letter, and `CursorSyncPace` lets the
re-read wait for a pause of 1.5 s; when it then finds the word the keyboard already holds, it
stops there. The first letter of a word, a letter typed inside a word, space, punctuation,
Backspace, Enter, a suggestion, a swipe, any caret move and any restart of input keep the prompt
re-read. The safeguards are listed in the commit message and pinned by `TypedLetterEchoTest`.

An earlier reading of `strip=33` was that the strip was rebound twice per letter. That was
wrong: a rebind with identical content is skipped in `SuggestionBar.setSuggestionsWithScores`
and is not counted, so the second search cost a search and no rebind. The count agrees: `strip=`
stayed at 33 when the re-reads went from 21 to 12.

What remains of it: the first letter of every word, every Backspace and every caret move still
re-read and search again. For the first letter of a new word the search is cheap (one letter is
below `autocorrect_min_word_length`, so there is no correction sweep). They were left as they
are because that re-read is where real work is done.

### 6. The end of the settings drawer could not be reached (fixed, `8dbb88c`)

Found on the way to the card. The drawer lists nineteen sections in a plain column, and a
Material drawer sheet does not scroll by itself: what does not fit gets no height. Smoke 9b now
reports what the open drawer shows, with the keyboard put away first (`kb=true>false`):
`first-screen=11/19 lowest=Accessibility`, and after one drag `last-screen=11:Help-and-FAQ`, the
nineteenth. So on that screen eleven fit at a time, and before the list scrolled the eight from
Clipboard down, Information & Actions among them, could be reached through search only. That
the old column gave them no height is read from the library's layout rule, not seen; that the
list scrolls now, and that eleven fit, is seen.

### 7. A letter's suggestions take 35 ms on the runner (measured, not changed)

`predict=21:34.87/248.72`: from a typed letter being handed to the predictor to its suggestions
being ready took 35 ms on average and 249 ms at worst, on a runner that was fast that day (the
next run: 32 ms and 121 ms). This is the dictionary search plus the correction sweep that runs
for every word-in-progress that is not itself a word (`WordPredictor.autoCorrect`, so that the
strip can show what space would commit). It runs on a worker thread and does not hold up a key
press.

It is the largest piece of work per key by a wide margin: handling the touch is 1 ms. On a
processor several times slower it is plausibly 150 ms or more per letter, which is a strip that
visibly trails the typing, and a core kept busy the whole time. Nothing was changed, for two
reasons. The sweep's rules and thresholds were tuned with on-demand evaluations (the `*Eval.kt`
classes in `src/test/kotlin/tribixbite/cleverkeys/autocorrect/`), whose corpora are not in this
repository, so a change to what it searches cannot be re-scored here. And there is no number
from the phone yet. The card has the line now.

One more consequence, also read and not seen: when space is pressed on a misspelt word before
the worker has finished that word, the sweep runs on the main thread instead
(`AutocorrectMemo.get` computes on a miss). A word that is in the dictionary returns at once
and is not affected.

### 8. Set-up read its apostrophe tables on the main thread (fixed, 2026-11-12)

In three runs running the slowest step of `onCreate` was `contractions`: 79 and 85 ms of 224,
then 40 of 191; in the next it was second, 58 of 396. Before those it took 13 to 31 ms of 105
to 271 on ordinary runners. In the debuggable build it was 395 ms of 742, more than half (run
43142217574, where the two builds were timed on one boot).

The step loads the tables that put the apostrophe into `dont` and offer `we'll` beside `well`
(`ContractionManager.loadTypingMappings`, called from `KeyboardComponentGraph`). For English
that is a few small files and one large one, `contraction_pairings.json`: 154 KB, 1,748 entries,
parsed with `org.json`. All of it runs on the main thread before the keyboard can be shown.
Which file the time goes to was not measured; the large one is the obvious candidate, and a
parser that walks text character by character is the kind of code a debuggable build runs
slowest, which fits the 395 ms.

**What changed.** The file was not moved off the main thread; it was replaced by a compact
form of it. `contractions.bin` — the store this repository already ships for the *other*
contraction file — now carries the pairing STRUCTURE (base -> variants, with the pairing
frequencies) instead of a flat set of forms with no base at all. The JSON stays the source of
truth; `scripts/generate_binary_contractions.py` projects it into the store at build time, and
the app reads 43 KB of bytes instead of parsing 154 KB of JSON.

The point is not the bytes: it is that the parse is gone. `org.json` built a tree of 1,748
arrays and 1,791 objects on the main thread, and then the loader walked that tree; the store
is read once, straight into the maps it is going to be used as.

**Why the data is still the data.** The suggestion bar's order is decided by the order of the
variants under a base, so "the same table" has to mean the same table *in the same order*.
`ContractionBinaryPairingsTest` reads the shipped store and the shipped JSON and requires
them to agree on the bases, the variants, each pairing frequency — including "no frequency",
which the JSON expresses by omitting the field and the store by a sentinel — and the order of
the variants under every base. It also requires the set of known contraction forms to be the
same, because that set is what the loader's DERIVATION step runs on: a contraction with an
apostrophe implies a base, and a different set would silently change which bases the keyboard
offers a variant for. The store is V1-compatible: an old store carries no structure, and the
loader then reads the JSON, exactly as before.

The order of the BASES is the one thing the test does not take from the production parser,
and the reason is worth stating because it is a property of the JSON library, not of the
store: `org.json` keeps an object's keys in a `HashMap` on the JVM and in a `LinkedHashMap`
on Android. The store therefore keeps the bases in the order the JSON FILE lists them, which
on Android is exactly the order the old parser produced, and the test asserts that order
directly against the file. Under the JVM the two readings differ — the `HashMap` order over
these 1,748 keys begins `hall, clinton, owners, …` while the file begins `a, aaron,
abbott, …` — and nothing observes it: every consumer looks a base up by name
(`getPairedContractions(base)`, `getPairedVariantFrequency(base, variant)`,
`getPairedFrequencyBases()`, which is a `Set`). The order the app does see, the order of the
variants under a base, is compared exactly, against both readings of the JSON.

**What is not claimed.** The phone has not said anything yet, so nothing is claimed about
what this is worth on it. What can be said is that 154 KB of JSON parsing no longer happens
on the main thread before the first picture, at every cold start. The other half of this
finding — loading the tables *beside* the first picture rather than before it — is still not
done, and still has the reason given below: it means making them safe to read while they
load, in a class that the suggestion code reads in several places and that reloads on every
change of language.

The emulator does not show a win, and the reason is worth recording rather than leaving out:
the `contractions` step measured 38 ms on `main` (run 43293833264, `create=130`), 50 ms on
the first V2 commit (run 43312641372, `create=192`) and 31 ms on the second (run
43316308878, `create=403`). The step is the same size before and after within the noise,
because the runner's set-up total moved from 130 ms to 403 ms across runs of the SAME code
path, and two cores of shared CI CPU will not resolve a 40 ms difference. The claim here is
therefore the structural one, which the code proves: the parse is not on the path any more.
The measurement that would settle the number is the one the phone has not given.

It is paid only when the keyboard is set up from nothing, so it matters exactly as much as the
phone kills the keyboard, and the card counts that. If the card's "Slowest set-up steps"
names `contractions` on the phone, the remaining half is the place: the same tables, loaded
beside the first picture instead of before it.

## What the emulator cannot show

- **How long the app takes to answer.** The test field is in the keyboard's process. `sync=` and
  the time inside it mean something only on a phone, in a real app.
- **Rendering.** The emulator draws in software: `gpu=` near 50 ms a frame is the emulator, and so
  is most of `shown=`.
- **The phone's CPU.** The runner's cores are several times faster than an old phone's, and vary
  threefold from run to run.
- **What the phone does to background apps.** See below.
- **Anything on Android below 12 or on EMUI.** The emulator is API 34.

## The phone

The repository records that the owner's phone is a Huawei (`PHASE4-POLISH-DESIGN.md`). Its
screenshots are 1080x2280, a panel size of around 2018, and one of them, from 2026-11-05, has
what looks like the power-saving leaf in the battery icon. The Android version is not recorded
anywhere. What follows are inferences until the phone confirms them:

- Features that need Android 12 probably do not apply. In particular the 24dp blur behind the
  default theme (`blurRadius`, applied through `Window.setBackgroundBlurRadius`) is API 31 and
  up, so it costs nothing on a phone older than that. It was looked at as a suspect and set aside
  for that reason; it has not been measured on any device.
- Huawei's EMUI is known for stopping background apps to save power, more so in power-saving
  mode. A keyboard that has been stopped must be set up from nothing the next time a text field
  is tapped, and on a phone of that age that is plausibly seconds rather than the emulator's
  half second.
- A build that has just been installed has not been compiled ahead of time. Android does that in
  the background while the phone is idle and charging, so the same build is quicker the next
  day than in its first hour. This is how Android works in general, not something measured
  here; it matters because every new build starts from there again.

### Reading the card

Settings, Information & Actions, "Keyboard speed". Long-press copies it. The numbers count from
the last time the keyboard's process started, except the first two lines. The lines:

- **Build: version, commit, "not debuggable".** Which build the numbers belong to. "debuggable"
  here means a build that runs slower by design (finding 4).
- **Set up from nothing: N times in the last 24 hours (M in the last hour), K after being killed;
  the latest ... ago.** Every creation of the keyboard is counted. "After being killed" counts
  the ones that followed another creation with no ordinary shutdown between them: the phone
  killed it, or it crashed, was updated, or the phone restarted. Switching to another keyboard
  and back is an ordinary shutdown and is not counted as a kill.
  - Many, most after a kill: the phone is throwing the keyboard away. The remedy is in the
    phone's settings (below), not in this code.
  - Many, none after a kill: something keeps switching keyboards. One candidate is EMUI's
    "Secure input", a setting that puts Huawei's own keyboard up for password fields. That is
    general knowledge of EMUI, not something checked on this phone.
  - A handful: being set up from nothing is not why it appears late.
- **Starting: A ms before set-up, set-up B ms, first picture C ms after being asked.** What the
  latest set-up cost on this phone, followed by a line naming its slowest steps. When the line
  begins "Starting again in a process that was still running" or "Starting in a process that
  was already running", the keyboard was set up inside a process that existed already (another
  keyboard had been used in between, or the settings screen was opened first). There is then no
  "before set-up" figure, and the set-up is quicker than one from a dead process, so it is not
  the number to judge a late appearance by.
  The steps that "Slowest set-up steps" can name, in the order they run:
  `compose` the table of key combinations;
  `prefs` opening the stored settings;
  `theme` the key handler and the theme;
  `config` reading every setting;
  `listeners` the emoji index, installed language packs and the receivers for changes;
  `inflate` building the key view;
  `clipboard` starting the clipboard history;
  `contractions` the apostrophe tables (finding 8);
  `clipmgr` the clipboard manager;
  `predict` the predictor, which starts loading the dictionary;
  `coordinators` the parts that handle input and suggestions;
  `graph` handing those parts to the service;
  `swipe` wiring swipe typing;
  `propagate` the last wiring, and logging for debugging.
- **Opening: N times, X ms on average, Y ms at worst.** Every showing, from the keyboard being
  asked for to its first picture.
- **The keyboard's own work in an opening: X ms on average, Y ms at worst; the slowest spent
  ...** The part of an opening that is this keyboard's code; the rest is the system making the
  window and drawing the first frame. On the emulator it is usually 2 to 6 ms of a 54 to 209 ms
  opening.
  If it is a large part on the phone, the steps named after "the slowest spent" say where to
  look:
  `clip` one look at the system clipboard for a copy the keyboard missed;
  `view` checking the theme and attaching the keys;
  `receiver` the layout manager and the key-event receiver;
  `layout` choosing the layout for this field and giving it to the keys;
  `editor` the per-field resets;
  `strip` the suggestion strip and the panes around it;
  `install` the strip's height and handing the view to the system;
  `field` password mode and the learning switch for this field.
- **Key presses: N, handled in X ms on average, Y ms at worst.**
- **Frames: ...** and the slowest frame by stage.
- **Reading the app's text after the caret moved: N times, X ms on average, Y ms at worst.** The
  one to look at for lag while typing. Tens of milliseconds here means the app answers slowly.
- **From a letter to its suggestions: N times, X ms on average, Y ms at worst.** The dictionary
  search. Hundreds of milliseconds here means the strip, not the keys, is what lags.
- **While open: height changed N times, keys re-attached N times, view re-installed N times.**
  Work that should not repeat. The last two should be 0, and the first about one per field
  typed in.

### Phone settings to check on EMUI

Names differ between EMUI versions. These are the places:

1. Settings, Battery, App launch: find the keyboard, turn off "Manage automatically", and allow
   Auto-launch, Secondary launch and Run in background.
2. Settings, Apps, special access, Ignore battery optimisation: allow it for the keyboard.
3. Settings, Battery: power-saving mode off while judging speed. It limits background work and
   on some models the processor.

None of this has been tried on the owner's phone. The card's "Set up from nothing" line says
whether it is needed.

## Limits

- Every figure is one sample on a shared CI runner. A row three times slower than its neighbour
  is in the table above. Counts (`sync`, `strip`, `resize`, `install`, `attach`) are comparable
  between runs; durations are not.
- The probe types slowly and evenly. It does not type fast, swipe, correct, or use an app that
  restarts input.
- The typing change is verified by its unit tests, by the count on the emulator, and by the
  existing typing tests still passing. It has not been used by a person.
- The probe changed once between runs (a warm-up showing and pauses between taps were added
  after the first run), as noted under the table.

## Decisions taken without asking

All reversible. They are also in [`OWNER-DECISIONS.md`](OWNER-DECISIONS.md).

- The CI-built APK is not debuggable. Same applicationId, keystore and versionCode: it installs
  over the old app and keeps its data.
- The suggestion band stays open while a field with text is being typed in.
- After a letter that extends a word, the re-read of the app's text waits for a pause.
- The keyboard writes down when it is created and when it is let go of (times only, 120 lines, a
  private file that is not backed up).
- The settings drawer scrolls.

## What the owner was asked to check

Asked on 2026-11-12, with the build of that day. No answer is recorded here yet; when one comes,
write it under each item.

1. Install the new build over the old one. It should keep its settings and learned words.
2. Type a few sentences in the apps used most. Does the keyboard come up sooner? Does typing
   still lag? Do the suggestions keep up with the letters?
3. The band of suggestions now stays open while a field is typed in. Is that right, or should it
   close at every space as it did before?
4. Turn the phone on its side and back with the keyboard open. The keyboard should stay.
5. In the keyboard's settings, open the side list of sections and drag it up: it should scroll
   to the last one, "Help & FAQ".
6. After a day of ordinary use: Settings, Information & Actions, long-press "Keyboard speed",
   and paste the copied text into the chat, together with the phone's Android version.

The checks still open from the day before are in
[`BUG-HUNT-2026-11-11-PART-2.md`](BUG-HUNT-2026-11-11-PART-2.md), under "Not verified".

## If the phone still feels slow

Send the card's text. What it will decide:

| The card says | Then |
|---|---|
| Many starts after a kill | phone settings above; if they do not help, shorten `onCreate` (the `steps=` list says where) |
| Few starts, "Opening" slow | the showing itself: look at the slowest frame that showed the keyboard, stage by stage |
| "Reading the app's text" slow | the remaining prompt re-reads (first letter, space) and `rememberVerifiedWord`, which still reads the editor three times per word |
| "From a letter to its suggestions" slow | the dictionary search and the correction sweep on the worker thread |
| "Key presses" or "Frames" slow | the key view's own drawing |
| All fast | the delay is outside what the keyboard can see; compare with Gboard on the same phone at the same moment |
