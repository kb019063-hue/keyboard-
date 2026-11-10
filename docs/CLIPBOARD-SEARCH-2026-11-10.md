# Clipboard search audit and changes

Gboard is the keyboard's visual reference, but published descriptions of its clipboard describe a
short recent/pinned list without search ([Relic](https://relic.space/gboard-clipboard),
[Clipboard History Pro](https://clipboardextension.com/clipboard-history-android)). They are
third-party descriptions, not a specification of every Gboard release. We retain this fork's
search, extended filters, and owner-selected indefinite retention rather than removing them or
copying Gboard's expiry policy.

## Search behavior

- Plain search is case-insensitive substring matching. It does not allocate a lowercased copy of
  every entry.
- The `.*` button enables **RE2 regex**, not glob shorthand. `a*` and `?` now have their normal
  regex meanings; use `.*` for the old glob `*`, and `.` for the old glob `?`. Query text is not
  lowercased, so `\D`, `\S`, `\W`, Unicode classes and quoted escapes retain their meaning.
- RE2/J provides linear-time matching, but does not support some Java regex features, notably
  lookaround and backreferences. Unsupported, invalid or overly large patterns show the existing
  red error tint and no matches. Long-press the regex button for help (API 26+).
- Pattern length is limited to 256 characters and the product of counted repetition bounds to
  1,000. The latter is deliberately conservative, including independent repetitions, because
  RE2/J expands nested counted repeats during compilation. Escaped or character-class braces
  are not counted. See [RE2/J issue 216](https://github.com/google/re2j/issues/216).
- Filtering is debounced for a changing non-empty query and executed off the UI thread. Each
  job works on a snapshot and is cancelled on a new filter, data load or view detach. While it
  runs, the old rows are hidden and bulk selection is disabled; only the newest result publishes.
  The unfiltered case uses the existing list directly. The test-only unattached view path remains
  synchronous because no view coroutine scope exists.

## Verification needed

The unit tests cover syntax, escaping, the repetition budget and a formerly catastrophic pattern.
The instrumented clipboard filter test exercises rapid replacement, regex errors and selection
readiness. The sandbox lacks the Android toolchain; build and test on CI and exercise long-press,
search, tab changes, selection and undo on a device before treating these changes as verified.
The dependency lock entry was updated for RE2/J; regenerate/confirm it with the Gradle commands in
`build.gradle` when a complete toolchain is available.
