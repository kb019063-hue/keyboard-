# Offline harness for the emulator smoke script

`scripts/ci/emulator-smoke.sh` is the gate that proves the keyboard actually works on a device
before it is installed on one. It is also expensive to debug: a run costs about fifteen minutes,
its logs are served from a host outside the dev sandbox's allowlist, its artifact contents come
from blob storage that is outside it too, and the only channels that reliably get through are
annotations (capped, and notices beyond roughly the twelfth are dropped) and the artifact **name**.

So a failed run yields one or two sentences of evidence, and guessing from those has already cost
three runs. This harness removes the guessing for the parts of the script that are pure hunt logic:
it runs the real sections 7d and 7e, unmodified, against a fake `adb` that models an emulator's
settings screen, and checks that fourteen scenarios each produce exactly the verdicts they must.

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
| One `verticalScroll` Column, so the search bar scrolls away with the content | `SettingsScreen.kt:106-139`; a hunt that assumes the bar is always on screen finds nothing |
| Rows outside the viewport are absent from the dump | Compose prunes them, which is what "below the fold" means and why every hunt must scroll |
| Collapsed sections compose no children | the reason each check is two-stage: expand, then look |
| Search results, and tapping one collapses all sections, expands one, scrolls the control to the top | `SettingsSearch.kt:42-67`, the deterministic route 7d now uses |
| A `SettingsSwitch` row as two nodes: a `TextView` title with `checkable="false"` and an unlabelled `Switch` carrying the state | reported by run 39789396340 from a real device; reading the title node reports `false` forever |
| Text fields bring the IME up -- the settings search bar as well as the launcher's test field | run 39804287846: `am start` from a shell implies NEW_TASK, which brings the app's existing task forward rather than starting the named activity in it, so "navigate to the launcher to find an editor" never navigated anywhere |
| `dumpsys window windows` whose input-method frame narrows only when floating is on | the mechanism part 3 exists to implement |
| BACK with an IME shown hides the keyboard and keeps the activity; only then does it pop | run 39783242401 pressed BACK to "dismiss a keyboard" that was never up, and left the next hunt dumping the launcher |
| Swipes apply no fling momentum | a device flings further than it is dragged, so what passes here passes there |
| While the IME is up, its toolbar band covers the bottom of the screen and the settings rows there are absent | 7e taps the mic key by its `content-desc`, and a settings row rendering in the same band would win the hit test and be tapped instead |
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

`kbclip` is 7f's mirror image: the measure pass over-reserves the navigation inset, the keyboard
view ends up 132px taller than its window, and the last key row is clipped at the screen edge — the
defect the owner's Huawei screenshot showed. It must fail. The fake therefore models the keyboard
view's own bounds (matched on its class name, never on the package, which the settings activity
shares) alongside the window frame 7d already models, and `hit()` returns the deepest node at a
point so a tap on a toolbar key lands on the key and not on the view containing it.

`noimenodes` is the shape run 39's real emulator produced: dumpsys says the IME is shown, yet the
accessibility dump carries none of its nodes — only the settings screen behind it. Both 7e and 7f
must stand down and say so (`voice=key`, a `KBCLIP skipped` warning), never pass over a view bottom
of 0 that only means "no node found". A per-scenario run can now emit five warnings — NFFRAME-DIAG,
VOICE skipped, VOICE-DIAG, DUMPDIAG, KBCLIP-DIAG — so the harness reads six.

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
