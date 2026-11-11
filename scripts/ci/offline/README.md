# Offline harness for the emulator smoke script

`scripts/ci/emulator-smoke.sh` is the gate that proves the keyboard actually works on a device
before it is installed on one. It is also expensive to debug: a run costs about fifteen minutes,
its logs are served from a host outside the dev sandbox's allowlist, its artifact contents come
from blob storage that is outside it too, and the only channels that reliably get through are
annotations (capped, and notices beyond roughly the twelfth are dropped) and the artifact **name**.

So a failed run yields one or two sentences of evidence, and guessing from those has already cost
three runs. This harness removes the guessing for the parts of the script that are pure hunt logic:
it runs the real sections 7d and 7e, unmodified, against a fake `adb` that models an emulator's
settings screen, and checks that sixteen scenarios each produce exactly the verdicts they must.

```
scripts/ci/offline/run.sh          # every scenario, about twenty seconds
scripts/ci/offline/run.sh run30    # one scenario, with its full output on mismatch
```

Run it after any edit to section 7b, 7d or 7e. It needs `python3` and `bash`, and no Android SDK,
Gradle, emulator or network.

## What the fake adb models

Every detail here corresponds to something that broke a real run.

| Modelled behaviour | Why |
|---|---|
| A TopAppBar hamburger (`content-desc="Open sections menu"`), a pinned search bar, and a pane showing ONE section; an open drawer sheet replaces everything in the dump and -- keyboard up -- ends at the IME top and scrolls there | the phase 4 part 4 screen shape; the chrome never scrolls away, and a sheet item under the keyboard is genuinely untappable, so both properties must hold in the fake for the hunts to mean anything |
| Rows outside the viewport are absent from the dump | Compose prunes them, which is what "below the fold" means and why every hunt must scroll |
| Collapsed sections compose no children, and a drawer item tap selects AND expands its section | the reason 7b/7c still confirm the switch after the item tap, and re-tap the pane header when it is missing |
| Search results, and tapping one selects its section, expands it, and scrolls the control to the top | `SettingsSearch.kt`, the deterministic Tier-1 route 7d uses |
| A `SettingsSwitch` row as two nodes: a `TextView` title with `checkable="false"` and an unlabelled `Switch` carrying the state | reported by run 39789396340 from a real device; reading the title node reports `false` forever |
| Text fields bring the IME up -- the settings search bar as well as the launcher's test field | run 39804287846: `am start` from a shell implies NEW_TASK, which brings the app's existing task forward rather than starting the named activity in it, so "navigate to the launcher to find an editor" never navigated anywhere |
| `dumpsys window windows` whose input-method frame narrows only when floating is on | the mechanism part 3 exists to implement |
| BACK: an open drawer sheet closes first; then an IME hides and the activity survives; only then does it pop | run 39783242401 pressed BACK to "dismiss a keyboard" that was never up, and left the next hunt dumping the launcher |
| Swipes apply no fling momentum | a device flings further than it is dragged, so what passes here passes there |
| While the IME is up, the keyboard occludes the bottom band of the screen and the settings rows there are absent | 7e taps the mic key by its `content-desc`, and a settings row rendering in the same band would win the hit test and be tapped instead |
| The toolbar's seven default buttons (`ToolbarConfig.DEFAULT_KEYS_STRING`), mic LAST at index 6, evenly weighted across the top 40dp of the IME frame, at the 420dpi `wm density` reports — matching the script's `TB_N=7` | 7e's coordinate fallback and 7g compute button centres from the frame rect and the density; a formula bug must fail here in thirty seconds, not on the emulator in fifteen minutes |
| Host-side `adb logcat` -- no `shell` prefix | the smoke script calls `adb logcat`, and a fake that only served `adb shell logcat` silently swallowed every replay line, which is why VOICE-DIAG read `log='none'` even in scenarios that had logged a press |
| The more key logs `press` then `shown rows=3 <state>`; `mt_deaf` logs only the press | the 7g ladder: press+shown passes, press without shown is the defect, silence is the harness's miss |
| The hit space carries IME nodes even when the dump hides them (`no_ime_nodes`) | run 40's emulator renders the toolbar -- taps land and logcat proves it -- while its dumps carry no IME nodes; without the split the coordinate routes could never be tested against a dump-blind device |
| The search bar renders whatever the field holds, including text committed through the `InputConnection` | what 7e asserts on: the dictation transcript appearing in the field is the only device-visible evidence that the commit path works |
| `logcat` replays the lines the model recorded, and `pm grant` records the permission | 7e ships the mic key's decision line as `VOICE-DIAG`, because on an image with no speech service that line is the only record of which branch ran |

## Scenarios

`scenarios.py` holds both the state each scenario starts from and the verdicts it must produce: the
7d frame verdict and, since phase 4 part 1, the 7e voice verdict. Four of them exist to prove the
suite cannot lie: `nochecked` and `tapdeaf` must stand down and report instead of asserting a window
measurement whose precondition was never confirmed, and `framepkg` and `noime` must report instead of
asserting a window they cannot identify or reach. `framefull` is the mirror image — floating
confirmed on, window still full width — and is the one scenario that must fail, because that is the
defect the probe exists to catch.

`nosearch` and `panescan` pin the fallback tiers down: with search broken, `nosearch` must reach
every control through the drawer (Tier 2b), while `panescan` starts with the right section already
selected and must answer from the pane scan (Tier 2a) without the drawer ever opening.

`kbclip` is 7f's mirror image: the measure pass over-reserves the navigation inset, the keyboard
view ends up 132px taller than its window, and the last key row is clipped at the screen edge — the
defect the owner's Huawei screenshot showed. It must fail. The fake therefore models the keyboard
view's own bounds (matched on its class name, never on the package, which the settings activity
shares) alongside the window frame 7d already models, and `hit()` returns the deepest node at a
point so a tap on a toolbar key lands on the key and not on the view containing it.

`noimenodes` is the shape runs 39 and 40's real emulator produced: dumpsys says the IME is shown,
yet the accessibility dump carries none of its nodes — only the settings screen behind it. 7f must
stand down and say so (a `KBCLIP skipped` warning), never pass over a view bottom of 0 that only
means "no node found". 7e must survive it anyway: with no node to aim at it falls back to the
COMPUTED mic position — frame rect plus density, the LAST of seven evenly weighted buttons
(13/14 of the frame width, `voice_typing` is 7th of 7) — and
the fake, whose hit space sees the toolbar even while its dump hides it, takes the tap, so the
transcript still lands and `voice=ok`. That fallback is the only route a dump-blind emulator has,
and its verdict ladder stays honest: a computed point that logs no dictation press is the harness's
miss (`key`, a warning), never the product's defect; only a witnessed tap — node route, or
coordinate route with the controller's press in logcat — may accuse (`commit`, a failure).

7g rides the same coordinates: the more key is button zero, the sheet is a dialog over the IME
window that no dump will ever show, so logcat is the witness — `press` when the command dispatches,
`shown rows=3 <state>` when the tools panel is up (3 grid rows of 4 columns holding the nine
tools, five switches then four launchers — the panel replaced the dialog on 2026-11-09). `mtdeaf` is its mirror image: the press logged and
the sheet never showed, the one genuine defect 7g exists to catch, so it must fail. Every other
scenario's toolbar still carries the more key at position zero, so 7g passes everywhere else.
A per-scenario run can now emit seven warnings — NFFRAME-DIAG, VOICE skipped, VOICE-DIAG,
DUMPDIAG, KBCLIP-DIAG/skipped, MTSHEET skipped, MTSHEET-DIAG — so the harness reads eight.

`voicedeaf` is 7e's mirror image and exists for the same reason: the mic key tapped with the
self-test switch confirmed on, and no transcript reaching the field. That is the defect 7e exists to
catch — a key that logs a decision and commits nothing — so it must fail, and a harness that cannot
produce that failure proves nothing about dictation. `voicenokey` is the stand-down: no toolbar node
carries the mic key's `content-desc`, so 7e reports and does not accuse. Both ride on scenarios where
7d still passes, which is what keeps a voice regression from being blamed on the window probe.

`framemodern` earns its place by having already caught a bug: it models the Android 12+ `dumpsys`
dialect, where a window's rectangle is a lowercase `frame=` under a `Frames:` block and the window is
identifiable only by `ty=INPUT_METHOD` inside it. The first version of the parser looked for
`inputmethod` as one word, so the underscored type token missed and the measurement was quietly
downgraded to "cannot identify this window" — a green run that proved nothing. Matching the type
token exactly (rather than any mention of the input method, which a window can carry as a *target*)
is what makes it assert again.

The runner also re-runs the **pre-fix** shape of section 7b's "Add" hunt (`addloop-old.txt`) and
requires it to fail. A shape test that passes both the fixed and the broken loop proves nothing;
run 39789396340 lost a check to that loop scrolling a fourth time and then falling out without
testing the dump it had just taken.

## Limits

This tests hunt logic: what to look for, in which order, how far to scroll, when to tap, and when to
refuse to assert. It cannot test anything about rendering, timing, the real accessibility tree, or
the window manager — those are what the emulator run is for, and the two are complementary rather
than interchangeable. A green harness means the script will look in the right places and report
honestly; it says nothing about whether the keyboard itself works.

# `typecheck.sh` — a compiler check without a build

A second, unrelated tool that lives here for the same reason the harness does: it runs in the dev
sandbox, and CI never invokes it.

## Why it exists

The dev sandbox cannot run Gradle (no Android SDK, no Maven Central), so until 2026-11-11 a Kotlin
change could not be checked at all before it was pushed. On that day a change with unresolved
names in three files was merged to `main` before its build had reported, and `main` did not
compile until it was reverted (`docs/BUG-HUNT-2026-11-11.md`).

The sandbox *can* reach PyPI and `api.github.com`, and three things from there are enough to run
the Kotlin compiler's front end: a JRE (the `jdk4py` wheel), the compiler (embedded in the
`kotlin-jupyter-kernel` wheel) and `android.jar` for API 34 (a git blob in
`Sable/android-platforms`). `typecheck.sh` fetches them once into `~/.cache/tc-typecheck`
(about 320 MB, outside the repository).

The kernel wheel turned out to carry one more useful thing: its fat jar bundles
`kotlinx-coroutines-core` under its real package name. Since 2026-11-11 (part 2 of that hunt)
`typecheck.sh` copies those classes into their own jar and puts it on the compile classpath, so
`launch`, `withContext`, `Job` and what happens inside their lambdas are checked like any other
code. It is 1.10.2, not the project's 1.8.1 — see Limits.

```
scripts/ci/offline/typecheck.sh                    # working tree vs origin/main
scripts/ci/offline/typecheck.sh --tests            # ...including src/test/kotlin
scripts/ci/offline/typecheck.sh <base>             # working tree vs <base>
scripts/ci/offline/typecheck.sh <base> <commit>    # <commit> vs <base>
```

Run it before every push that touches Kotlin. It needs `python3`, `git`, `pip` and either `gh`
or `curl`.

## How it works, and why thousands of errors are normal

With only the Android SDK, the Kotlin standard library and kotlinx.coroutines on the classpath,
every reference to AndroidX, Compose, Gson, RE2/J, `R` or `BuildConfig` is unresolved. A tree that
builds perfectly in CI therefore reports about **14,700** errors here (14,703 for `main` at
`dcdf2df`; 242 of its 429 files report none at all, the other 187 touch a library the tool cannot
see). Before coroutines were on the classpath the same tree reported 15,060.

So the count means nothing and the **difference** means everything. The same front end runs over
a base commit and over your tree, and only what your tree has and the base does not is shown, in
three classes:

| Class | Meaning | What to do |
|---|---|---|
| **NEW TO THE TREE** | no file in the base reports this message | it is a real error; fix it |
| **NEW TO THE FILE** | the base reports the same message, but only in other files | read each: either the file now uses a library the tool cannot see, or it is real |
| *repeats* (counted, not listed) | the same file already reported this exact message in the base | more uses of a library the tool cannot see |

Diagnostics are compared as counts per (file, message), not by line number, so an edit that only
moves code does not look like a new error. The base's result is cached by the hash of its `src/`
tree plus a classpath tag (`CLASSPATH_TAG` in the script: bump it whenever the classpath or the
compiler flags change, or a cached base would be compared with a tree compiled differently).
Exit status: `0` nothing new to its file, `1` something to read, `2` the check did not run.

Measured in the 4 GB sandbox: about 50 seconds per tree for `src/main/kotlin`, about 75 with
`--tests`.

## What it has been checked against

- **The change that broke `main`.** `typecheck.sh 0e2dce4 51c9107` reports eleven errors that are
  new to the tree — `_resources` and `foldableUnfolded` at `CleverKeysService.kt:1171` and `:1265`
  and at `KeyboardReceiver.kt:315`, the private `_config` there, the missing `IOException`
  import — and three that are new to their file. That includes the two lines CI's eight-item
  annotation list never reached (`CleverKeysService.kt:1265`, `KeyboardReceiver.kt:315`). Under
  two minutes, both trees compiled from scratch.
- **The change that added it.** A single-file type-check of the new `ConfigurationManager.kt`
  passed. The whole-tree run did not: the file declared a `private class MainLooperScheduler`,
  and `clipboard/ClipboardManager.kt` already has one in the same package, which is a
  redeclaration. It would have been a red build.
- **Itself.** With a heap too small to finish, it exits `2` and says the compiler did not finish;
  a tree with fewer than 1,000 diagnostics is treated the same way, because for this codebase
  that can only mean the front end stopped early.

## Limits

A clean result is evidence, not a verdict.

- **Code behind a missing library is not checked.** A call on a receiver whose type is unresolved
  produces no diagnostic, whatever is wrong with it. Compose UI and anything built on an AndroidX
  type are largely invisible. A class that does not exist *inside* such a library (PR #26's
  `com.google.gson.stream.JsonParser`) looks like any other use of it.
- **Coroutines are checked against 1.10.2, the project ships 1.8.1.** The jar is whatever the
  kernel wheel bundles. An API added after 1.8.1 passes here and fails in CI; nothing the app
  uses today is that new, but a clean result does not prove the next call is not.
  `kotlinx-coroutines-test` and `-android` are not in the kernel jar, so `Dispatchers.setMain`
  and friends in tests are still unresolved names.
- **Adding tests is noisy.** JUnit, Truth and MockK are missing too, so a test file that starts
  using one of them lists those names as NEW TO THE FILE. Read them; they should all be library
  names.
- **`--tests` compiles main and test as one module.** Gradle compiles them separately.
- **The compiler is not the project's.** It is a 2.3 development build held to language level
  2.0; the project builds with Kotlin 2.0.0. They can disagree at the margins.
- **Nothing else runs.** No resources, manifest, lint, generated sources
  (`build/generated/**`), `tools/check_layout.py`, packaging or tests.

Push, then read the run. That is still the only thing that says the build is green.
