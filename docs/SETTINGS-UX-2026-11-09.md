# Settings UI/UX round

2026-11-09. The owner's report was one line — "the settings also has some bugs. The UI/UX needs
improvement too" — and, when asked which half mattered, **layout**: cramped, cluttered, hard to
read.

So this round is a visual/structural pass, not a feature change. Nothing about what a setting
*does* was touched; no preference key, no persistence path, no section composable's controls.

---

## The root cause: one arrangement, three defects

The drawer shows **one section at a time** (phase 4 part 4). But every section composable still
wrapped its controls in `CollapsibleSettingsSection` carrying **its own title** — the identical
string the drawer item was already showing:

```
  [☰]  ⚙️ Mr. Brown's Keyboard Settings          <- top app bar: which SCREEN
       Search settings…
  ┌──────────────────────────────┐
  │ ♿ Accessibility          ⌄  │                <- repeat of the drawer item
  │   Vibrate on key press  [▣] │
  └──────────────────────────────┘
```

Three separate defects came out of that:

1. **The title was printed twice** — drawer, then pane.
2. **The pane never said which section you were in.** With the drawer closed the app bar reads
   "Settings" for all nineteen sections. The redundant pane header was the *only* position
   indicator — the wrong element was doing the right job.
3. **Collapsing the header emptied the screen.** One section per pane means collapsed == "a title
   and nothing else". The only way back was to tap the title again. A control whose reachable
   state is "hide everything" is a dead end, not a choice.

### The fix

- The section title moves to the **top app bar**, as a two-line title: the app name small above,
  the current section large below. That is where "where am I?" belongs, and it is correct for all
  nineteen sections.
- The pane becomes a plain card (`SettingsPane`) with **no title and no chevron**.
- `CollapsibleSettingsSection` is retained as a thin forwarder with its parameters inert, so the
  nineteen call sites did not have to change in the same commit and a future *nested* group (one
  that hides a detail rather than the whole pane) has a documented home.

`SectionHeaderPolicy` holds the rule as pure, tested functions:
`headerText()` (the app bar names the section, totally), `showsOwnTitle()` = false,
`isCollapsibleInPane()` = false.

---

## Also fixed in this pass

- **Search had no empty state.** `showResults` required
  `query.isNotBlank() && results.isNotEmpty()`, so a query matching nothing collapsed the results
  card and rendered **nothing at all** — indistinguishable from "search is broken", and a typo
  produced silence. There is now an explicit `searching` flag (blank vs no-match are different
  states) and a `“No settings match “x””` line.
- **The search field was glued to its neighbours.** `vertical = 8.dp` against the app bar and the
  content card read as one crowded block; now `12.dp`.
- **The drawer opened with a paragraph.** "Configure your keyboard settings for optimal typing
  experience." sat between the title and the first navigation item. It is not navigation; removed
  from the drawer.
- **`SettingsSection` deleted.** A non-collapsible section card with **zero call sites**, carrying
  the same repeated-title problem the collapsible one had. Dead code that would have been the
  obvious thing to copy.
- Card corners 8dp -> 12dp and the pane's inner vertical padding 16dp -> 8dp (the `spacedBy(12.dp)`
  between controls already supplies the rhythm), so a section reads as a card rather than a box.

## Deliberately not done

- **The nineteen `*SectionExpanded` flags are now dead.** The drawer's `onClick` still sets them,
  `collapseAllSections()` still clears them, an existing coverage test still asserts it does, and
  they reach 19 section files, `SettingsActivity` and `SettingsSearch`. Making them inert costs
  nothing; deleting them is a mechanical refactor with real risk and no user-visible payoff, so it
  is left for a dedicated commit rather than smuggled into a layout pass.
- **No new navigation model, no bottom bar, no per-setting icons.** The owner asked for "not
  cramped and cluttered", not a redesign of a working drawer.
- **`selectedSectionKey` still resets to the first item** on process recreation. Worth fixing, but
  it is a behaviour change, and this round is layout.

## Tests

`SectionHeaderPolicyTest` (7) and `SettingsSearchEmptyStateTest` (4), both registered in
`build.gradle`'s `pureTestClasses` array. Compose cannot be driven from the JVM harness here, so
the composable half is pinned by source-reading guards — the same technique the repo's existing
`SettingsSurfaceDriftTest` / `SettingsSearchCoverageTest` use. Each guard message says *why* it
exists, so a future author re-points it instead of deleting it.

All 19 source guards were verified against the tree before the commit.
