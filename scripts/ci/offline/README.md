# Offline harness for the emulator smoke script

`scripts/ci/emulator-smoke.sh` is the gate that proves the keyboard actually works on a device
before it is installed on one. It is also expensive to debug: a run costs about fifteen minutes,
its logs are served from a host outside the dev sandbox's allowlist, its artifact contents come
from blob storage that is outside it too, and the only channels that reliably get through are
annotations (capped, and notices beyond roughly the twelfth are dropped) and the artifact **name**.

So a failed run yields one or two sentences of evidence, and guessing from those has already cost
three runs. This harness removes the guessing for the parts of the script that are pure hunt logic:
it runs the real section 7d, unmodified, against a fake `adb` that models an emulator's settings
screen, and checks that nine scenarios each produce exactly the verdict they must.

```
scripts/ci/offline/run.sh          # every scenario, about twenty seconds
scripts/ci/offline/run.sh run30    # one scenario, with its full output on mismatch
```

Run it after any edit to section 7b or 7d. It needs `python3` and `bash`, and no Android SDK,
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

## Scenarios

`scenarios.py` holds both the state each scenario starts from and the verdict it must produce. Four
of them exist to prove the suite cannot lie: `nochecked` and `tapdeaf` must stand down and report
instead of asserting a window measurement whose precondition was never confirmed, and `framepkg`
and `noime` must report instead of asserting a window they cannot identify or reach. `framefull` is
the mirror image — floating confirmed on, window still full width — and is the one scenario that
must fail, because that is the defect the probe exists to catch.

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
