# Test pitfalls: twelve ways a green suite lies, and how each one bit us

Written 2026-11-01, after the whole unit-test suite was finally made to execute here for the first
time (4,650 tests, 413 classes). It was **141 red**. Only three of those were defects in the app.
The other ~138 were tests that had drifted away from the code they were supposed to be guarding —
and each one had been passing, or failing, for a reason nobody could see from the outside.

This file exists so the next agent (or human) does not spend the same day rediscovering them.
Every entry is: **the message you will see → why → the fix pattern → the test that now pins it**.

Read [`AGENTS.md`](../AGENTS.md) first for the build/CI ground rules, and
[`docs/CI-STORAGE.md`](CI-STORAGE.md) before touching anything that uploads.

---

## 0. Reading a red run in 60 seconds

There is no JDK, Gradle or Android SDK in the dev sandbox, so **CI is the compiler**, and the run
log is not fetchable (`gh run view --log` returns nothing; the log host is unreachable). The
annotations are the only channel that works:

```bash
# job id for a run
gh run view <run-id> --json jobs --jq '.jobs[]|"\(.databaseId) \(.name) \(.conclusion)"'

# what it actually said
gh api repos/{owner}/{repo}/check-runs/<job-id>/annotations --jq '.[]|.message'
```

The report step prints, in this order: `FAILING TESTS` (every one, by class), `FAILING CLASSES`
(counts), `FIRST FAILURE PER CLASS` (**the message is cut at 110 characters**, then the project
stack frames), the skipped classes, `unit tests: <n> run, <n> failed, <n> skipped in <n> classes`,
the slowest classes, and Gradle's `What went wrong` block.

Consequences worth designing for:

- **Put the actionable part of an assertion message first.** Anything after ~110 characters is gone
  from the annotation. `assertWithMessage("$locale/$key is missing")` beats a paragraph.
- **Downloading the XML artifact does not work from the sandbox** (`*.blob.core.windows.net` is
  blocked). Do not plan a diagnosis that needs the XML you just uploaded.
- A run's annotations also include `GRADLE …` lines, which is how a Gradle-level failure (a bad
  property, an unknown task) is told apart from a red test.

---

## 1. A relaxed mock answers `false` to every Boolean getter

**Symptom**

```
TypedBarHighlightHandlerTest#heloHighlightsHelpAndSpaceCommitsHelp:
  bar highlights what space commits value of: highlighted(...) expected: help but was: null
```

**Why.** `Config` exposes **incognito-folded** getters — `autocorrectEffective`, `learningEffective`,
`swipeFinalAutocorrectEffective`, `autocapEffective`, `clipboardHistoryEffective` — and production
reads *those*, never the raw preference behind them. A `mockk(relaxed = true)` `Config` answers a
derived getter with `false` no matter what the raw pref says, so the test ran with autocorrect
switched off and then asserted a correction was highlighted. The same shape made
`AutocapitalisationTest` and `ClipboardCatchUpTest` pass while the feature under test never ran.

**Fix pattern.** Stub the getter production actually reads, wired to the raw pref so a test can
still turn it off:

```kotlin
every { config.autocorrectEffective } answers { config.autocorrect_enabled }
every { config.learningEffective } answers { config.on_device_learning_enabled }
```

**Ask of every new mock-tier test:** grep the production file for `Effective` before stubbing
anything. `SwipeCorrectionOfferTest`, `LearningFunnelBookkeepingTest` and
`TypedBarHighlightHandlerTest` are the reference.

## 2. A platform class is a stub: `SpannableString` has no characters

**Symptom.** The expected text never arrives; `editor.toString()` is `""` although the app committed
`"help "`.

**Why.** Autocorrect commits are wrapped in an `android.text.SpannableString` by
`SuggestionSpanPolicy`/`SuggestionSpanCommit`. Off-device that class is a stub whose bytes cannot be
read back, so `commitText(span, 1)` reaches a fake `InputConnection` as an empty sequence.

**Fix pattern.** Switch the decoration off for tests that need readable text — it has its own tests
(`SuggestionSpanPolicyTest`, `SuggestionSpanCommitTest`):

```kotlin
mockkObject(SuggestionSpanPolicy)
every { SuggestionSpanPolicy.plan(any(), any(), any()) } returns null   // plain-text commit
```

`testOptions.returnDefaultValues = true` is why the stub no longer throws; before that, a stub call
was itself a signal that something ran. **Do not** read a `Stub!` exception as proof a code path
executed — `WindowLayoutUtilsTest` did, and nine tests were asserting on nothing.

## 3. Direct-boot storage hands a relaxed mock a brand-new Context

**Symptom**

```
PointersShortSwipeCustomOverrideTest#customMapping_resolvesAfterColdInitFromDisk_swipeTypingDisabled:
  a cold-loaded custom mapping must resolve with swipe typing disabled
```

**Why.** `ShortSwipeCustomizationManager`, `PrivacyManager`, `CustomThemeManager`,
`SwipePerformanceStats` and friends resolve their files through
`context.createDeviceProtectedStorageContext()` (direct-boot storage). A relaxed `Context` answers
that with a **new child mock** whose `filesDir` has no path: the writer persisted to one phantom path
and the cold loader read another. The data was in the test's temp dir, invisible to both.

**Fix pattern.**

```kotlin
every { context.filesDir } returns tmpDir
every { context.createDeviceProtectedStorageContext() } returns context
```

Grep production for `createDeviceProtectedStorageContext()` before writing any test that touches
files. (An earlier variant of this bug cost 21 `PrivacyManagerTest` cases.)

## 4. `@Before` cannot guard a `@BeforeClass` or a field initialiser

**Symptom.** A class that should skip reports a missing file as a failure:
`expected scripts/dictionaries/langpack-ru.zip`.

**Why.** JUnit runs `@BeforeClass`, then the instance is constructed (field initialisers), and only
then `@Before`. A guard placed in `@Before` therefore runs *after* the fixture that needs the
artefact has already thrown.

**Fix pattern.** Guard at the top of the thing that loads, using the shared helper:

```kotlin
@BeforeClass @JvmStatic fun guard() {
    UpstreamArtefacts.require(LANGPACK_DIR)   // Assumption → SKIPPED, not failed
}
```

`UpstreamArtefacts` exists because this fork does not vendor `docs/RELEASE_RECORD.md`, `fastlane/`,
`docs/i18n/glossary.json`, `.github/scripts/emulator-ci.sh` or `scripts/dictionaries/*.zip`.
**22 classes legitimately skip** — a skipped class is not a pass, and never "fix" one by deleting the
guard.

## 5. `getString()` is the symbol a key DRAWS, not the key's name

**Symptom**

```
StyleLayoutsTest#gboardStyleHasADigitRowAndBareLetters:
  missing (4) : ?123:switch_numeric, switch_emoji, space, enter
  unexpected (4): ?123, , , 
```

**Why.** `KeyValue.getString()` returns `_payload.toString()` — the glyph rendered on the key. For
`space`, `enter` and `switch_emoji` the payload is empty because they draw an icon, so a text
comparison cannot tell those three apart from each other or from a typo.

**Fix pattern.** Compare `KeyValue` identity:

```kotlin
assertThat(row.map { it.getKeyValue(0) })
    .containsExactly(key("?123:switch_numeric"), key(","), key("switch_emoji"),
                     key("space"), key("."), key("enter")).inOrder()
```

Use `getString()` only when the *label* is what you are testing (e.g. "the numbers key reads
`?123`").

## 6. Assertions that encode one device's filesystem

**Symptom.** `expected to start with: /data/ but was : /tmp/dlm-test/swipe_log.txt`

**Why.** The test stubbed `filesDir` to a temp dir *because* a relaxed mock's `File` has a null path —
and then asserted the real device prefix anyway.

**Fix pattern.** Assert the invariant, not the device:

```kotlin
assertThat(path).startsWith(mockContext.filesDir.absolutePath)   // under filesDir…
assertThat(path).endsWith("swipe_log.txt")                       // …and named this
```

## 7. Arithmetic that never reaches the case it names

**Symptom**

```
threshold 70% puts the dead zone past maxDistance
  expected to be greater than: 100.0 but was : 98.0
```

**Why.** The dead zone is `keyHeight × threshold`, and `keyHeight` is `0.7 × diagonal`, so at 70 %
the dead zone is `0.49 × diagonal` — still *inside* the `0.5 ×` half-diagonal. The bug the test
describes only begins at 71.4 %, so the sweep started below the case under test.

**Fix pattern.** Compute the boundary in the test and start there, and say so in a comment:

```kotlin
// 0.7 × diagonal vs a 0.5 × maxDistance: past maxDistance only from 71.4 % up.
for (threshold in 72..80) { … }
```

## 8. Pinning a file's layout instead of its wiring

**Symptom**

```
the panel must actually be composed into the settings screen
  value of: containsMatchIn(...) expected to be true
```

**Why.** The pin required `TestKeyboardSection()` alone on a line. Production dispatches it as
`"testKeyboard" -> TestKeyboardSection()`. The panel was composed the whole time; the regex pinned
formatting.

**Fix pattern.** Match the call, allowing the dispatch prefix, and assert the *behaviour* you care
about separately:

```kotlin
Regex("""(?m)^\s*(?:"[^"]+"\s*->\s*)?TestKeyboardSection\(\)\s*$""")
```

Rule of thumb for every source scan: **it may pin a symbol, never a line break or an argument order
that `ktlint`/`Editor > Format` could move.** Where a scan is unavoidable, tolerate whitespace
(`\s*`) everywhere — that alone was the cause of two failures here (see 9 and 10).

## 9. Two definitions of one default

**Symptom**

```
SettingsActivity.toolbarKeys loads from Defaults.TOOLBAR_KEYS but starts as
  ToolbarConfig.DEFAULT_KEYS — start it from that constant (audit M5)
```

**Why.** The screen's first frame and its first reload read the same value from two different
sources. Same list that day; two sources forever after.

**Fix pattern.** Initialise from exactly what the loader reads:
`mutableStateOf(ToolbarConfig.parse(Defaults.TOOLBAR_KEYS))`. `DefaultsSingleDefinitionDriftTest` is
the ratchet; when it names a field, fix the field, not the test.

## 10. Source scans that cannot see a wrapped call

**Symptom**

```
Writerless SETTINGS_DEFAULTS keys drifted from the documented set — a new key with a
  runtime reader but no control is the F-8 class; surface it or reclassify it
```

**Why.** The scan looked for `saveSetting("key"` and `saveSetting(Obj.CONST,` on **one line**.
`InputBehaviorSection` writes the voice-typing dropdown as

```kotlin
saveSetting(
    VoiceTypingConfig.PREF_MODE,
    VoiceTypingConfig.rawOf(…)
)
```

so `voice_typing_mode` was reported as having no control while its dropdown had been writing it
since phase 4 part 1.

**Fix pattern.** Tolerate whitespace/newlines in the pattern
(`saveSetting\(\s*(?:\w+\.)?PREF_MODE\s*[,)]`), and when a pin *does* fail, **prove the write exists
before believing the pin** — a false positive here is indistinguishable from a real gap.

## 11. A pin that outlives the code it pinned

**Symptom.** `HapticsBehaviorDriftTest#systemVibrationSwitch_writesInverseOfVibrateCustom_toPrefAndLiveConfig`

**Why.** The assertion matched the literal `Config.globalConfig().vibrate_custom = !useSystem`. The
write moved to `config.edit { vibrate_custom = !useSystem }` — a *better* form, and the only one that
republishes the `ConfigSnapshot` — so the pin went red on an improvement.

**Fix pattern.** When production changes shape deliberately, move the pin **and write down why next
to it**. When the pin is right and the code is wrong, fix the code (see the entry below).

## 12. Tests that were never registered, and static state that leaks between classes

**Symptom.** `expected to be empty but was: [tribixbite.cleverkeys.ConfigurationBurstCoalescingTest, …]`

**Why.** On ARM64 Termux `testDebugUnitTest` is disabled, and tests run through two `JavaExec` tasks
that take an **explicit class list** (`pureTestClasses`, `mockTestClasses` in `build.gradle`). Eighteen
`*Test` classes written for an audit were never added, so on a device they could not run at all.

**Fix pattern.** Register every new class in one of the two lists (pure = no `android.jar`, mock =
needs the stubs; when unsure choose **mock**, it is the superset). `TestRunnerListDriftTest` fails if
you forget, if you leave a stale entry, or if a class is in both.

Two related traps:

- **A class can pass in the per-push subset and fail in the whole suite.** MockK static mocks
  (`mockkStatic(Log::class)`, `mockkObject(Config.Companion)`) are process-global; only trust a
  verdict from the invocation that actually ran the class alongside everything else.
- **A `--tests` filter is not registration.** Adding a class to `build-apk.yml`'s subset does not add
  it to the Termux runners, and vice versa.

---

## What a JVM test here does and does not have

| Available | Not available |
|---|---|
| `android.jar` **stubs** on the mock tier: every method returns its default (`0`, `false`, `null`, no-op) — it never throws | Real Android behaviour. `Color.alpha`, `SpannableString`, `TextUtils`, `Xml.newPullParser`, `Log` all lie or return nothing |
| Objenesis to build an `Android`-class instance without a constructor | Constructors of anything taking `Context`/`Resources` — use `objenesis.newInstance(...)` + field injection (`setField` helper in `TypedBarHighlightHandlerTest`) |
| `sun.misc.Unsafe.allocateInstance` for `Pointers`/view-shaped classes | Calling `init` on those objects; inject every collaborator |
| MockK (inline, so final classes are mockable) | Sharing a mock across classes; static mocks leak until `unmockkAll()` |
| Real file IO in a temp dir under `build/tmp` | `/tmp` on Termux, and anything under `src/` (never write into the repo from a test) |

Facts that keep costing time:

- `mockkStatic(Log::class)` + stubs for `Log.d/i/w/e` are needed in any test whose production path
  logs — debug builds compile `ENABLE_VERBOSE_LOGGING` on.
- `Color` helpers: read channels with bit operations, not `android.graphics.Color` (see
  `KeyFillAlpha.of`), or the value is 0 in every test.
- CWD is the project root, so `File("res/values-de")` and `File("build.gradle")` work.
- Ratchet/count pins (consumer counts, unguarded-log counts, string counts) must be updated in the
  **same commit** that changes the count, with the history comment extended. Lowering a ceiling is
  always welcome; raising one is an owner decision.

---

## CI harness traps (each one hid the next)

1. **Two `./gradlew testDebugUnitTest` invocations are the same Gradle task.** The second deletes the
   first's JUnit XML, so a run that executed 500 tests can report "no JUnit XML found". The first
   run's results are copied to `build/full-suite-xml/` between the two — do not remove that copy.
2. **`bash -e` + `set -o pipefail` aborts the step at the first failing test.** So neither Gradle
   line is allowed to fail its step: each records its exit code in a file instead, the report step
   reads the XML, and the last step (`scripts/ci/full-suite-verdict.sh`) decides. See trap 8 for
   what happened while nothing decided.
3. **`isFailOnNoMatchingTests` is not a property of `DefaultTestFilter` in this Gradle version.**
   Adding it fails the build in ~60 s naming the unknown property. The `filter { }` block stays
   exactly `heavyEngineTests.each { … }`.
4. **The workflow's `paths:` must include `build.gradle`**, because heap, filters and the test
   timeout live there and change every verdict below them. A workflow file edited without a matching
   push runs nothing.
5. **The test heap is a measured number: read the `Test JVM` line before changing it.** Two
   mistakes have been made here, in opposite directions.
   - *Too much, for everything.* 3 GB for the whole suite, engine classes included, turned a
     12-minute run into one that was abandoned after 70 minutes (2026-10-31). Nobody knows why:
     there was no collection log then. The hungry engine classes have had their own invocation
     since (`-PonlyHeavy` / `-PtestMaxHeap=3g` / `-PjunitXmlDir`). Alone, their log shows no
     more than 266M left after a collection at any quarter of the run, so the 3 GB they have is
     a guess that was never revisited either.
   - *Too little, unnoticed.* Everything else ran on Gradle's default, 512 MB, which no file
     here mentioned (one comment called it 2 GB). The first collection log (run 43259344146,
     2026-11-12) showed the suite at the edge of it; trap 9 has the line. The whole-suite
     invocation now passes `-PtestMaxHeap=1g`. In its first two runs there the suite ended
     with about 550M in use and stood at 762M at its fullest: more than the whole of the heap
     it had before.

   The line turns into a warning that says `NEARLY OUT OF ROOM` when, at the JVM's fullest,
   85% of the limit was still in use after a collection. Raising the number is the quick
   answer. Finding what the tests keep is the real one, and it has not been attempted: see
   the end of trap 9.
6. **The job timeout destroys the report.** It was 180 minutes; a stalled class meant the report step
   never ran. It is 75 minutes with a 45-minute test-task cap, and the XML is uploaded. See trap 9
   for what a run that hits the cap leaves behind (no XML).
7. **`gh pr edit --body` fails** on the deprecated Projects GraphQL field — use
   `gh api -X PATCH repos/{owner}/{repo}/pulls/<n> --input <json>`.
8. **The check was green with failing tests until 2026-11-12.** The step named "Fail the job when
   tests failed" asked whether the test step had failed, and the test step cannot fail (trap 2).
   The report printed `FAILING TESTS` and the job concluded `success`: runs 41766203725,
   41774492055, 41828565272, 41840073362, 42558434610 and 42559739283, all on pull requests, each
   report one or two failed tests in their annotations under a green tick. Every run on `main`
   among the 55 looked at reports `0 failed`, so nothing red is known to have been merged because
   of it, but only because whoever merged read the notice. The gate is now
   `scripts/ci/full-suite-verdict.sh`, which was run against eleven made-up outcomes first: green needs
   both Gradle exit codes to be 0, XML to exist, and the report's count of failures to be 0.
   **Still quote the notice (`unit tests: N run, 0 failed`) and the run id, not the colour.**
9. **A run that hits the 45-minute cap leaves no XML at all**, not "everything but the class that
   stalled": run 43242985447 (2026-11-12) ended with `Timeout has been exceeded` and
   `no JUnit XML found`, on a commit whose code had passed the same suite in nine minutes an hour
   before. It could not be diagnosed, and neither could the one of 2026-11-01. Two instruments
   exist since then, and both arrive as annotations:
   - **`Test JVM`**, on every run: one line per Gradle invocation from the test JVM's own
     collection log (`-PtestGcLog`, `scripts/ci/test-gc-summary.py`). It says how long the JVM
     ran, how much of that the collector took, and how much heap was left after a collection at
     each quarter of the run and at its fullest, against the JVM's limit. The whole suite
     shares ONE JVM and static state is never released, so a run can be slow because it is out
     of room; this line is how that is told from a stuck test, and it shows the room shrinking
     before a run fails. It is a notice, and a warning that says `NEARLY OUT OF ROOM` from 85%
     of the limit. It counts apart the full collections that the code asked for with
     `System.gc()`, which say nothing about room: `StaticLmAssetDriftTest` makes 36 in every
     run.
   - **`Long test run`**, only when a run is still going at 15 and 30 minutes
     (`scripts/ci/test-wedge-watchdog.sh`): the test JVM's thread dump (the "Test worker" stack
     names the class and method), its collector occupancy, disk and memory, and what Gradle
     itself is waiting for. Errors when the run then failed, warnings when it was only slow.
   The report also prints `test time: N s inside M classes; slowest: …`, so two runs can be
   compared class by class. Read these before guessing.

   **What the first `Test JVM` line said** (run 43259344146, the same code as the run that
   stalled plus CI files, 9.1 minutes, green):

   ```
   whole suite: test JVM ran 340 s (G1); 1416 young and 61 full collections, 90 concurrent
   cycles; paused 27.3 s in all (8.0%), longest 485 ms; concurrent work 94.5 s; heap left after
   a collection: 100M, 162M, 283M, 475M at each quarter of the run, of a possible 512M;
   machine: 2 processors, 7932M
   memory-hungry classes: test JVM ran 50 s (G1); 78 young and 2 full collections, 2 concurrent
   cycles; paused 1.2 s in all (2.5%), longest 125 ms; concurrent work 0.7 s; heap left after a
   collection: 266M, 266M, 61M, 68M at each quarter of the run, of a possible 3072M
   ```

   The suite ended that run with 475M of its 512M still in use after a collection, and what
   it keeps grows all the way through: static state is never released, as trap 12 says. So the
   suite had been outgrowing its heap one test class at a time (413 classes on 2026-11-01, 424
   now), and nothing reported it.

   One number in that line was misread at first. Of the 61 full collections, 36 are not a
   sign of anything: `StaticLmAssetDriftTest` calls `System.gc()` three times before and
   three times after loading each of six language models, to print a heap delta. That count
   is exact and the line could not tell the two kinds apart; it does now ("the full ones: N
   for want of room, M asked for by the code"). The other 25 would be the collector's last
   resort, but that is a subtraction: the log itself was never seen.

   What a JVM does in that state was tried in the sandbox with a stand-in program under the
   same collector (steady work that makes garbage, while the heap it never releases climbs to
   475M over 40 seconds). At a 512 MB limit its work rate had halved by the time nine tenths
   of the limit was in use and fell to a ninth at 96%, through 1,409 full collections and **no
   error**; at 1 GB it kept full speed with none. Allowed three megabytes more, the 512 MB one
   stopped with `OutOfMemoryError`. So just below failing there is a narrow band in which a
   run only gets slower, and a suite at the edge of that band can finish in nine minutes one
   hour and not in forty-five the next. That is the likeliest account of run 43242985447 and
   of 2026-11-01. It is not proved for either: neither left a log, and the stand-in is not the
   suite.

   What was done: the whole-suite invocation runs with `-PtestMaxHeap=1g` (trap 5), and the
   line becomes a warning before the room runs out again. **The first run at 1 GB**
   (43266527543, 4,797 run, 0 failed):

   ```
   whole suite: test JVM ran 395 s (G1); 219 young and 36 full collections, 37 concurrent
   cycles; paused 15.4 s in all (3.9%), longest 301 ms; concurrent work 50.0 s; heap left after
   a collection: 86M, 156M, 295M, 554M at each quarter of the run, of a possible 1024M;
   machine: 2 processors, 7932M
   ```

   What it shows, and what it does not:
   - The collector did about half the work: 15 s of pauses against 27, 50 s beside the tests
     against 94, 219 young collections against 1,416. The 36 full collections left are the
     ones the one test asks for, so by the same subtraction none was for want of room.
   - The run was **not faster**: the JVM ran 395 s against 340, and the step took 11.4
     minutes against 9.1. The time inside the test classes rose with it (382 s against 327 s),
     and so did the rest of the step, which is mostly compiling (4.8 minutes against 3.4), so
     this was a slower machine; the four runs of the step that finished on this branch took
     8.6, 11.0, 9.1 and 11.4 minutes, the first three at 512 MB. One run each cannot show
     what the heap is worth in minutes. What the change is for is the run that does not
     finish, and that cannot be shown by one run that does.
   - With twice the room the suite read 554M at its end, not 475M. The figure is what a
     collection left behind, so it includes garbage the collection did not look at, and a
     collector with room to spare leaves more of it lying; a JVM against its limit also drops
     what it may (soft references). Which of the two the 79M is was not determined, so
     compare runs at the same limit only.
   - What the tests keep still nearly doubles from each quarter of the run to the next.

   **The second run at 1 GB** (43270987868, 4,797 run, 0 failed, 8.6 minutes) was the first
   to print the two additions to the line:

   ```
   whole suite: test JVM ran 308 s (G1); 231 young and 36 full collections, 56 concurrent
   cycles; the full ones: 0 for want of room (-0.0 s), 36 asked for by the code (6.1 s);
   paused 12.1 s in all (3.9%), longest 238 ms; concurrent work 61.4 s; heap left after a
   collection: 96M, 156M, 297M, 551M at each quarter of the run and 762M at its fullest, of a
   possible 1024M; machine: 2 processors, 7932M
   ```

   - The subtraction is now a reading: no full collection for want of room, and the 36 that
     the one test asks for are half of all the time the tests were stopped (6.1 of 12.1 s).
   - **762M at its fullest.** At some moment in the run the suite had three quarters of a
     gigabyte in use after collections: 200M more than at its end, and half as much again as
     the whole of the 512 MB it used to run in. This is the best evidence there is for the
     account above. At 512 MB that moment could only be got through by the collector's last
     resort, and how long that took would have depended on what else was live just then.
     Which test class it is, nobody knows yet.
   - The warning is at 85%, 870M. The suite's fullest moment is about a hundred megabytes
     short of it, so expect the warning before long, and treat it as the prompt to look at
     what the tests keep and not only to raise the number again.
   - Two details of that line were corrected afterwards, and later lines differ in them. The
     `-0.0` is a rounding slip. And "at its fullest" is the most that five collections in a
     row all left behind, but it counted the two pauses inside a concurrent cycle as
     collections, so the five could be as little as one real collection; it now counts young
     and full collections only, and may read lower for it.

   What was not done: **nobody has looked at what the tests keep.** Half a gigabyte is a
   great deal for 424 classes of unit tests. Bringing it down would shorten every run (in
   the first one the collector stopped the tests for 27 s and worked beside them for another
   94 s, on two processors) and would let the limit come back down. The way in: `jcmd <pid>
   GC.class_histogram` on the test worker while it runs says which classes of object fill the
   heap (the watchdog script already finds the worker's pid); and the JUnit XML's `timestamp`
   per class, set against a collection log that carries wall-clock time (add `utctime` to the
   decorations in `build.gradle`), says during which test classes the kept heap rose.

---

## Checklist before pushing a test

- [ ] Does production read a folded getter (`*Effective`) on this path? Stub **that**.
- [ ] Does the path commit text? Stub `SuggestionSpanPolicy.plan` → `null`, or assert on spans.
- [ ] Does it touch files? Stub `filesDir` **and** `createDeviceProtectedStorageContext()`.
- [ ] Does it load an upstream artefact? `UpstreamArtefacts.require` at the top of `@BeforeClass`.
- [ ] Are you identifying a key by text? Use `KeyValue` identity unless the label is the point.
- [ ] Are you asserting a path? Assert the invariant under the stubbed dir, not `/data/`.
- [ ] Is it a source scan? Tolerate whitespace, and pin a symbol rather than a line's shape.
- [ ] Is the class registered in `pureTestClasses`/`mockTestClasses`?
- [ ] If it changes a count some ratchet pins, did you update the constant and its history comment?
- [ ] Did the **whole suite** run, or only the subset? They disagree.
