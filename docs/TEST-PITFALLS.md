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
2. **`bash -e` + `set -o pipefail` aborts the step at the first failing test.** Both Gradle lines end
   with `|| true`; the verdict is decided by the report step, which reads the XML.
3. **`isFailOnNoMatchingTests` is not a property of `DefaultTestFilter` in this Gradle version.**
   Adding it fails the build in ~60 s naming the unknown property. The `filter { }` block stays
   exactly `heavyEngineTests.each { … }`.
4. **The workflow's `paths:` must include `build.gradle`**, because heap, filters and the test
   timeout live there and change every verdict below them. A workflow file edited without a matching
   push runs nothing.
5. **Never widen the test heap globally.** 3 GB for the whole suite turns a 12-minute run into a
   70-minute GC crawl; the hungry engine classes get their own invocation via `-PonlyHeavy` /
   `-PtestMaxHeap` / `-PjunitXmlDir`.
6. **The job timeout destroys the report.** It was 180 minutes; a stalled class meant the report step
   never ran. It is 75 minutes with a 45-minute test-task cap, and the XML is uploaded.
7. **`gh pr edit --body` fails** on the deprecated Projects GraphQL field — use
   `gh api -X PATCH repos/{owner}/{repo}/pulls/<n> --input <json>`.

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
