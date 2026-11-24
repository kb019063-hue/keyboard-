# Bug hunt, part 16 — `customization/`: the load path, and a guard that can never be false

Date: 2026-11-15
Branch: `arena/cbf26556-keyboard`
Base: `9f586e9` (part 15, the shape channel's frame)
Scope: `customization/` — `ShortSwipeMapping` (init invariants + the five factories),
`ShortSwipeCustomizations` (storage + `convert`), `ShortSwipeCustomizationManager` (load, save,
import, the per-key render index), `ShortSwipeAssignment`, `CommandRouting`, `XmlAttributeMapper`,
`XmlLayoutExporter`, `DynamicTemplate`, `SwipeDirection`, `IntentDefinition`, and the read surfaces
of `CustomShortSwipeExecutor` / `CommandRegistry` / `KeyMagnifierView` / `KeyboardPreviewHost` /
`CommandPaletteDialog`.

Two of the findings (C1, C6) are load/import-path defects of the same shape as G-2 ("an import that
delivers nothing must not destroy everything"). C3 is a guard that is *always true*, which turned a fallback
into dead code and let the export path write layouts that type command names into
the user's document. The rest is what the pass checked and found sound, plus one contradiction this
pass deliberately did **not** resolve.

---

## Summary

| # | Finding | Severity | State |
|---|---------|----------|-------|
| C1 | `toMappingList()` is all-or-nothing: one malformed stored entry discards **every** mapping | **high** (silent total data loss from the load path) | **fixed** + 3 tests |
| C2 | `timestamp()` truncates before it validates, so a cut inside a quoted literal yields an invalid pattern | medium (hand-edited/imported only) | **fixed** + 2 tests |
| C3 | `toXmlValue`'s COMMAND guard tests `getKeyByName(name) != null`, which is **always true**; the legacy remap was dead code and legacy command names exported as text that types the command | **high** (exported layouts corrupt the user's document) | **fixed** + 5 tests |
| C4 | `WORD_LEFT`/`WORD_RIGHT` export as `cursor_left`/`cursor_right`, downgrading a word move to a character move | medium (silent semantic loss on export) | **fixed** + 1 test |
| C5 | An exported attribute value is parsed **bare** by `parse_key_attr`, but the mapper emits keydefs, so TEXT arrives with its quotes and INTENT/TIMESTAMP arrive as unexecuted text | **high** (whole export path is lossy) | **reported** — test pins current behaviour |
| C6 | An import is all-or-nothing one level coarser than C1: one syntactically bad entry throws in `gson.fromJson` and the whole payload yields 0, good entries included | medium (import-only) | **reported** — product decision |
| C7 | Two catalogue commands (`\t`, `\n`) have no implementation, no `KeyValue`, and no export — a palette entry that does nothing | low | **reported** |

Production files changed: `ShortSwipeMapping.kt`, `ShortSwipeCustomizationManager.kt`,
`XmlAttributeMapper.kt`, `XmlLayoutExporter.kt`.
Tests: `ShortSwipeIntentTest` +12 (68 total in the class); 12 call sites updated across four test files
for the new nullable return type. Suite: **4903 run / 0 failed / 134 skipped / 440 classes** (`0d82f9c`).

---

## C1 — the load path could discard every mapping because of one

`ShortSwipeCustomizations.toMappingList()` walked the stored map and constructed a
`ShortSwipeMapping` per entry. `ShortSwipeMapping.init` is strict — `displayText` ≤ 4,
`actionValue` ≤ 4096, a TIMESTAMP `actionValue` must be a parseable `SimpleDateFormat` pattern,
a TEMPLATE `actionValue` must be a valid template:

```kotlin
require(displayText.length <= MAX_DISPLAY_LENGTH) { … }
require(actionValue.length <= MAX_ACTION_LENGTH) { … }
if (actionType == ActionType.TIMESTAMP) { require(isValidTimestampPattern(actionValue)) { … } }
```

Those invariants are **stronger than anything the app's editor can produce** — the palette applies
`take(4)`, the text field caps at `MAX_ACTION_LENGTH`, the timestamp editor validates before
confirming. But `toMappingList` is a *load* path: its `mappings` come straight from
`gson.fromJson` of `short_swipe_customizations.json`, or of an imported backup. The invariants were
being applied to untrusted input.

So one hand-edited or third-party entry — a five-character label, a stray quote in a timestamp
pattern — threw `IllegalArgumentException` out of `toMappingList`, and `loadMappings`'s
`catch (e: Exception)` logged "Failed to load custom mappings" and left `mappingCache` empty. The
user lost **all** of their mappings to one bad entry, with no way to tell which.

This is G-2's shape exactly — "an import that delivers nothing must not destroy everything" — one
grain finer: per entry instead of per file.

Fixed by making the conversion per-entry tolerant:

```kotlin
fun convert(): Converted {
    var skipped = 0
    for ((keyCode, directions) in mappings) {
        for ((directionName, mapping) in directions) {
            val direction = SwipeDirection.entries.find { it.name == directionName } ?: continue
            val converted = try { … } catch (_: IllegalArgumentException) { skipped++; continue }
            out.add(converted)
        }
    }
    return Converted(out, skipped)
}
fun toMappingList(): List<ShortSwipeMapping> = convert().mappings
```

Two design points that matter as much as the fix:

- **The count is returned, not stored.** A `@Volatile var lastSkippedCount` on the data class was
  the obvious alternative and is the wrong shape: mutable state on a value type, shared by every
  holder, correct only for whichever thread called last. `Converted(mappings, skipped)` keeps the
  value with the call that produced it.
- **Nothing is deleted.** The skipped entries stay in the file (`loadMappings` does not rewrite it),
  so fixing the entry — or a future release that accepts it — restores the mapping. Both call sites
  now log `"skipped N malformed entr{y|ies} (left in the file)"` / `"from the payload"`.

`importFromJson` goes through the same `convert()`, so an import keeps its good entries too.

**Read the sweep.** The `catch` catches only `IllegalArgumentException`, deliberately: `init`'s
`require` is the only thing in `convert()` that throws it, so the catch cannot be silently
swallowing a different bug class.

---

## C2 — `timestamp()` validated the string after truncating it

`ShortSwipeMapping.timestamp()` did `actionValue = pattern.take(MAX_ACTION_LENGTH)`. Truncation
looks safe because `init` validates whatever is stored — but the cut itself can *create* an invalid
pattern: `SimpleDateFormat` rejects an odd number of `'`, so a pattern whose 4096th character falls
inside a quoted run truncates to an unterminated quote and `init` throws.

Demonstrated before fixing, with a valid 4138-character pattern padded so the cut lands inside a
quoted run: quote count after `take(4096)` is odd. The factory then throws at construction, and
because construction happens on the load path, C1 turned one such entry into total loss. Fixed
together, therefore:

```kotlin
fun untruncatedTimestampPattern(pattern: String): String {
    if (pattern.length <= MAX_ACTION_LENGTH) return pattern
    val cut = pattern.substring(0, MAX_ACTION_LENGTH)
    val lastQuote = cut.lastIndexOf('\'')
    return if (lastQuote >= 0 && cut.count { it == '\'' } % 2 == 1) cut.substring(0, lastQuote) else cut
}
```

Below the cap it is a no-op, so no existing mapping moves. Above it, the unfinished tail is dropped
rather than throwing — the cap exists to bound storage, not to express intent. If the pattern is one
enormous literal, the trim leaves the empty string, `init`'s `isNotBlank` rejects it with its own
message, and the per-entry `catch` from C1 handles it. All four cases are pinned by test.

---

## C3 — a guard that is always true, and the dead code behind it

`XmlAttributeMapper.toXmlValue` guarded the COMMAND case like this:

```kotlin
if (KeyValue.getKeyByName(commandName) != null) commandName
else mapLegacyCommandToKeyword(mapping.getCommand()) ?: commandName
```

`KeyValue.getKeyByName` is **total**:

```kotlin
fun getKeyByName(name: String): KeyValue {
    val k = getSpecialKeyByName(name)
    if (k != null) return k
    return try { KeyValueParser.parse(name) }
    catch (_e: KeyValueParser.ParseError) { makeStringKey(name) }   // ← never null
}
```

An unrecognised name comes back as a String key *spelling that name*. The condition is therefore
true for every input, the `else` branch is unreachable, and `mapLegacyCommandToKeyword` — all 21
entries of it — has never run. (Kotlin's `KeyValue` is a platform type here, so the compiler did not
warn; `!= null` on a non-null type would have been flagged.)

Two consequences, and the second is the serious one:

1. Legacy `AvailableCommand` mappings stored as `COPY`, `SELECT_ALL`, `WORD_LEFT` before the
   catalogue existed never got their remap.
2. Every such value exported **verbatim**. A String key in a layout's subkey slot *types* its text.
   So exporting a legacy command mapping wrote a layout whose short swipe inserts the literal string
   `COPY` into the user's document.

This is the same defect class as the toolbar's `more_tools` (2026-11-04), reached from the export
path instead of the toolbar. `CommandRouting.isOwnNameFallback(commandName, kv)` is the test that
actually distinguishes a real key from the fallback — it exists precisely because `getKeyByName` is
total — and it now decides:

```kotlin
private fun commandAttributeValue(mapping: ShortSwipeMapping): String? {
    val commandName = mapping.actionValue
    val resolved = KeyValue.getKeyByName(commandName)
    if (!CommandRouting.isOwnNameFallback(commandName, resolved)) return commandName
    return mapLegacyCommandToKeyword(mapping.getCommand())
}
```

Measured against the source, not assumed:

- **21/21** legacy remap targets are real keys, so the remap can never itself produce a String key.
- **13 of 247** registry commands have no `KeyValue` at all: the five `paste_pinned_N`,
  `showTextMenu`, `primaryLangToggle`, `secondaryLangToggle`, and the five mode toggles
  (`one_handed_toggle`, `one_handed_side`, `split_toggle`, `incognito_toggle`, `floating_toggle`).
  For those, `commandAttributeValue` returns `null` and `XmlLayoutExporter` **omits the attribute**
  (`?: continue`) rather than writing a value that types text.
- Those 13 are exactly the commands `Keyboard2View` handles itself (`customCommandHandled`) or the
  executor's `paste_pinned_*` block — keyboard-level actions that no layout XML can carry. Returning
  `null` is the honest answer, and it matches the executor's own routing rather than inventing a
  new classification. `removed` is *not* in the set: it is a real `KeyValue` (Kind.Placeholder).

`toXmlValue` now returns `String?`, so the compiler forces every caller to decide. Four existing
call sites were updated (`ShortSwipeIntentTest` ×6, `KeyValueParserTest` ×3, `ShortSwipeMappingTest`
×2, `DynamicTemplateTest` ×1).

The file-level KDoc said "command names … can be used directly as XML attribute values" — that
sentence *is* the bug, stated as documentation, so it was rewritten.

---

## C4 — word movement exported as character movement

`mapLegacyCommandToKeyword` had:

```kotlin
AvailableCommand.WORD_LEFT  -> "cursor_left"   // "Slider handles word movement via repeat"
AvailableCommand.WORD_RIGHT -> "cursor_right"
```

That comment describes a design that no longer exists: `WORD_LEFT`/`WORD_RIGHT` have been real
editing keys (`word_left`, `word_right`) since 2026-10-17. Any legacy word-move mapping exported as
a *one-character* cursor move. Now mapped to `word_left`/`word_right`, with the resolved
`KeyValue.Editing` value asserted in the test rather than just the string.

---

## C5 — reported: the export writes keydefs into a field read as literals

**Not fixed. This is the one finding in this pass where the right fix is a format decision, not a
patch, and I am flagging that I got it wrong first and CI caught me.**

`XmlAttributeMapper.toXmlValue` produces *keydefs* — the right-hand side of a `symbol:keydef`
string. Its own tests parse them that way and pass, e.g.
`KeyValueParser.parse("date:" + toXmlValue(timestampMapping))` yields `Kind.Timestamp` with the
right pattern. On that reading the mapper is correct, and `ShortSwipeMappingTest`/`KeyValueParserTest`
pin it.

But `XmlLayoutExporter` does not write a `symbol:keydef` string into the attribute; it writes the
keydef alone. And a layout attribute value goes to `KeyValue.getKeyByName` **bare**:

```kotlin
// KeyboardData.parse_key_attr
ks[index] = KeyValue.getKeyByName(key_val_actual)
```

With no colon, `KeyValueParser.parse` returns `makeStringKey(input)`. Measured consequences on the
layout path:

| stored mapping | exported attribute | parses as (layout path) |
|---|---|---|
| TEXT `Hello` | `'Hello'` | String key — text is **`'Hello'`**, quotes included |
| TEXT `It's` | `'It\'s'` | String key — text is `'It\'s'` |
| INTENT `{…}` | `intent:'{…}'` | String key — the JSON is **typed as text**, never executed |
| TIMESTAMP `yyyy-MM-dd` | `timestamp:'yyyy-MM-dd'` | String key — **typed as text**, never formatted |
| COMMAND `copy` | `copy` | Editing key — correct |

So the export path is lossy for four of the five action types, and the two that are meant to *do*
something (intent, timestamp) instead silently insert their own source into the document.

I first "fixed" this by escaping backslashes in TEXT. CI rejected it, correctly: escaping is only
correct on the prefixed reading, so it made TEXT neither reading's right answer. I reverted it and
pinned the current behaviour in a test named `the map does not cover TEXT and the three controls -
the reported contradiction` — *current* behaviour, explicitly not *correct* behaviour.

Why not just fix it: the two candidate fixes are mutually exclusive and both are format changes.
Either the exporter emits a **bare literal** (which TEXT can carry, but which cannot express intent
or timestamp at all), or it emits a **prefixed keydef** — `1:intent:'…'` or similar — which requires
`parse_key_attr` to route non-command values through the parser instead of `getKeyByName`. The
second is almost certainly the intended design (the mapper already builds keydefs, and UK-format
layouts are `symbol:keydef` strings), but it changes how every exported layout is read and needs a
device check on a real re-import. Recording the measurement and the options is worth more than a
guess that CI cannot validate.

**Suggested sequence:** confirm on-device that a hand-written layout key with `n="1:timestamp:'yyyy'"`
yields a working timestamp key; if it does, change `XmlLayoutExporter` to emit `<slot>1:<keydef>` for
TEXT/INTENT/TEMPLATE/TIMESTAMP and leave COMMAND bare; add a round-trip test that goes
exporter → `KeyboardData.load_string_exn` → inspect `KeyValue.Kind`, which is the test that should
have existed from the start.

---

## C6 — reported: an import is still all-or-nothing at the parse level

C1 fixed *per-entry* loss. Import has a coarser version of the same problem that C1 cannot reach,
because it happens before any entry exists: if the payload has **one** syntactically bad entry,
`gson.fromJson` throws `JsonSyntaxException` and `importFromJson`'s `catch (e: Exception)` returns 0
with "Failed to import mappings from JSON". The good entries are never seen.

G-2 at least guarantees this does not *destroy* the existing set — that still holds, and it is the
important half. The gap is that a 40-entry backup with one trailing comma imports nothing at all, and
the user gets a generic failure.

Also unmeasured but visible in the code: `importFromJson` never compares the payload's `version`
against `CURRENT_VERSION = 2`. A future version can be imported silently.

Not fixed because the choices are real product decisions: lenient per-entry parsing (parse the
payload as a `JsonObject` and convert entries individually) would recover the good entries but
changes what "the import succeeded" means, and a version gate needs a migration story that does not
exist yet.

---

## C7 — reported: two catalogue commands have no implementation at all

Measuring the catalogue for C3 turned up two entries that are not just unexportable — they are
inert everywhere.

`CommandRegistry` defines a "Tab Char" and a "Newline Char" command in `Category.SPACES`:

```kotlin
Command("\\t", R.string.cmd_backslash_t, R.string.cmd_backslash_t_desc, Category.SPACES, …)
Command("\\n", R.string.cmd_backslash_n, R.string.cmd_backslash_n_desc, Category.SPACES, …)
```

They are the **only 2 of 247** catalogue names that contain a character `KeyValueParser` treats
specially (the rest are plain identifiers). Neither name exists in `KeyValue`'s special-key table,
neither appears in `CustomShortSwipeExecutor`'s `when`, and neither is in `Keyboard2View`'s
`customCommandHandled` block. So for a mapping to either one:

- the executor's `else` branch calls `getKeyByName("\\t")` → a String key spelling `\\t` →
  `isOwnNameFallback` is true → returns false, deferring;
- `Keyboard2View` finds no custom handler, and the `KeyValue` is the same String fallback, so it is
  a no-op rather than corruption (the 2026-11-04 `more_tools` guard doing its job);
- the export path returns **null** (C3's rule), so the mapping silently disappears from an exported
  layout.

The user-visible result is a command in the palette labelled "Tab Char" that does nothing, in the
one category where the alternative implementations already exist (`tab` and `enter` are real keys,
and `KeyValue.getKeyByName("tab")` is an Event key). Reported rather than fixed because "what should
`\\t` do" is a product question — insert a literal `\t` two-character sequence, or send a Tab key
event (which `tab` and `enter` already cover)? — and the answer decides whether the entry is
re-pointed, renamed, or deleted.

The wider C3 lesson stands and is worth stating separately: **the export writes a string into a
grammar it does not control.** That is fine for the 245 identifier-shaped names and is exactly what
broke for these 2. Fixing it means emitting an escaped or `symbol:keydef` form — the same change of
shape as C5 — so it is deferred with C5 rather than half-migrating the format.

---

## Checked and found sound

- **`CommandRegistry.getDisplayInfo`** applies `.take(4)` to every label, so
  `upgradeLegacyDefaultLabel` cannot violate `MAX_DISPLAY_LENGTH` however the catalogue grows.
  `LEGACY_DEFAULT_LABELS` has exactly one entry (`copy_private`) and the upgrade path is
  idempotent — a second load does not re-upgrade.
- **The `removed` placeholder** (`REMOVED_COMMAND = "removed"`) is used consistently by
  `ShortSwipeAssignment.remove`, `isRemoval`, the subkey popover, and the export path. It is a real
  `KeyValue` (Kind.Placeholder, empty payload), so it exports as itself and types nothing.
- **`buildByKeyIndex`** and the `@Volatile` reference-swap publication. The documented "raw keyCode,
  not re-lowercased" semantics is genuinely load-bearing (an uppercase stored keyCode is
  unreachable and stays unreachable), and `ShortSwipeByKeyIndexTest` compares against the original
  filter/`associateBy` expression as an oracle. Readers are lock-free, writers hold `fileMutex`, and
  the inner maps are frozen snapshots.
- **`ShortSwipeAssignment.apply/remove/restoreDefault`**: one implementation behind both edit
  screens, so the settings screen and the popover cannot drift. `remove` correctly distinguishes
  "hide a layout default" (`removal` mapping) from "delete a custom mapping".
- **`DynamicTemplate`**: non-recursive by construction, tokens parsed before any provider is read,
  providers memoised per expansion (pinned: two `{clipboard}` tokens read once), bounded by
  `MAX_EXPANDED_LENGTH` with `addExact` on the UTF-16 offset, and `execute` re-checks `matches`/
  `stillCurrent` at every step rather than retrying. The password-field guard is checked first.
- **`CommandRouting`**: the `KEY_PIPELINE_KINDS` set and `isOwnNameFallback` are the two guards that
  keep commands out of `commitText`. `typedTextValue` correctly refuses control characters and
  splits BMP/supplementary code points, so an emoji mapping becomes a String key.
- **`SwipeDirection`**: `fromAngle`'s 22.5° shift is right for 45°-wide sectors, `fromDelta` negates
  `dy` for screen coordinates, and `subLabelIndex` matches `KeyboardData.Key.keys` (1=NW … 8=S)
  including 0 for the main key.
- **`CustomShortSwipeExecutor`**: the editing-command guard defers to `KeyEventHandler` before the
  registry is consulted, `executeCommandByName` checks `CommandRouting.editingValue` first, and the
  `else` branch refuses to `commitText` a `isOwnNameFallback` key. `sendKeyEvent` returns
  `down && up` rather than ignoring a failed up-event.
- **`KeyMagnifierView`**: `keyRect`, the eight zone `RectF`s are rebuilt into a reused map, paints
  are fields, and `mainLabelWidth` bounds the W/E slots — no per-frame allocation on the draw path.
  `ActionType` and `availableCommand` names remain the persisted identifiers (renaming one is the
  documented forbidden change).
- **Storage I/O**: every mutator takes `fileMutex` around cache mutation *and* the write; the
  read-modify-write races the class documents (a save during init-time load) are actually closed.

---

## What this pass did not do

- **Did not run the app.** No emulator/Gradle in the sandbox; CI is the only compile signal. The
  `XmlLayoutExporter` attribute question in C5 is precisely the kind of thing a device check settles
  and a unit test cannot.
- **Did not audit the Android-Context halves of the executor** (`executeIntent`'s
  `resolveActivity`/`startService`, `executePastePinned`, toast strings) — they need a `Context` and
  belong to a pass that can run instrumented tests.
- **Did not audit `CommandPaletteDialog` or `IntentEditorDialog` beyond their validation and
  search-ranking shapes.** Both are ~1200/350-line Compose surfaces; the filter/rank path
  (`searchRanked`, `calculateScore`, the localized+English dual `ResultText`) was read and looks
  correct — the English fallback is deduplicated — but Compose recomposition behaviour was not
  measured.
- **Did not re-open any closed subsystem.** Waves 4–18 stay closed; the `swipe/geometric/` findings
  in parts 11–15 remain as reported there.
- **Did not change `CURRENT_VERSION` or add a migration.** C6's version gate is the obvious first
  step of a migration and needs that story first.

---

## Verification

- `0d82f9c` — CI: **4903 run / 0 failed / 134 skipped / 440 classes**; Build APK success; Emulator
  smoke success (rotate verdict ok, no new crashes).
- Delta: **+12 tests** (baseline `9f586e9` was 4891), same 440 classes — the new tests live in the
  existing registered `ShortSwipeIntentTest` (no `UpstreamArtefacts` gate, so nothing skips).
- Every number in this report was recomputed from the source by script rather than carried over:
  the 21 remap targets, the 13 no-`KeyValue` commands, the 2/247 special-character names, and the
  four of five lossy action types. One did not survive that check — a first draft of C7 asserted
  "143 of 247 names contain a special character" from impression rather than measurement; the real
  figure is 2, and chasing the discrepancy is what surfaced C7. Corrected above rather than quietly
  dropped, since this is the same error class part 15 caught twice.

Two CI cycles were spent learning what C5 is, and both failures are described above rather than
quietly removed: the first said "escape backslashes" (wrong reading), the second said "parse the
exported value with a `symbol:` prefix" (wrong oracle — `parse_key_attr` calls `getKeyByName`, not
`KeyValueParser.parse`). The reverted change and its tests are gone; the knowledge is in C5.
