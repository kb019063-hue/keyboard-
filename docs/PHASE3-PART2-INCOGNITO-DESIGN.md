# Phase 3, part 2 — incognito mode

Part 1 gave the keyboard a click; part 2 gives it a private mode. The gap analysis called incognito
"the only genuinely missing command" in CleverKeys against HeliBoard: one switch that suspends
learning, autocorrect, auto-capitalisation and clipboard history. HeliBoard's version is a
`force incognito` preference; here it is both a Settings switch and a toolbar command, because a
private mode you can only reach by leaving the keyboard is a private mode you will not use in the
moment you need it.

## The shape: suspension, not rewrite

The tempting implementation is to flip the four preferences off when incognito turns on and back
when it turns off. That destroys the user's own choices — anyone who had autocorrect off for their
own reasons would come out of incognito with it on — and it stores a derived state with writers,
which is exactly the bug shape this codebase has paid for twice (#154's `vibrate_custom` stomp,
#161's orientation height stomp).

So incognito never touches the four preferences. Each behaviour site reads its preference *folded*
with incognito through a computed property on `Config` — `learningEffective`,
`autocorrectEffective`, `swipeFinalAutocorrectEffective`, `autocapEffective`,
`clipboardHistoryEffective` — each of which is `IncognitoPolicy.allows(pref, incognito)`, i.e.
`pref && !incognito`. The Settings UI keeps reading the raw fields, so what the user sees is always
their own choice and never an incognito-time derivative. Turning incognito off restores exactly what
was there before, with no migration and no remembered state.

`IncognitoPolicy` is a two-line pure object with a four-row JVM test, because the one row worth
pinning is the last: incognito with a feature the user had **off** must stay off. A wrong
implementation (`pref != incognito`) passes the other three rows.

## Folding into the gate that already existed

Learning needed no new gate at all. CleverKeys already threads a master `on_device_learning_enabled`
boolean through every `LearningGate` function — context LM, personalization vocabulary, selection
adaptation, swipe-correction counts, swipe-ML collection — and `LearningGate`'s own contract says
master off means the learned store is *inert, not just frozen*. Folding incognito into that master
at its twelve read sites therefore suspends all learning, and all reading of learned data, through
architecture that already had tests. `PrivacyManager`'s notification sync reads the preferences
directly rather than through `Config`, so it applies the fold itself; missing that one would have
left a notification claiming learning was live while incognito suspended it.

Autocorrect folds at four decision sites (the commit/correct paths and the swipe final-word
autocorrect), auto-capitalisation at two (the shift logic and the cursor-position check), clipboard
history at five (every capture path in `ClipboardHistoryService`). A grep for the raw field names
outside `Config`, the Settings UI and the backup defaults now returns nothing, which is the guard
against a future reader forgetting the incognito half.

## Surface

Settings → Privacy, at the top of the section: the **Incognito Mode** switch, described as
suspending the four behaviours "without changing those settings", which is the whole contract in
one line. And a toolbar command `incognito_toggle` — symbol 🕶️, three UTF-16 units, inside the four
`ShortSwipeMapping` allows — registered in `Category.EVENTS` beside the one-handed commands, listed
in `CustomShortSwipeExecutor`'s recognised names so a swipe mapping cannot commit the literal text,
and dispatched in `Keyboard2View` to `CleverKeysService.toggleIncognito()`. The command writes the
same preference the switch writes, so ConfigurationManager's refresh applies it to an open keyboard
with no IME restart: one code path, not two that could disagree. Because incognito has no visible
geometry change the way one-handed mode does, the command confirms itself with a toast.

## Verification

`IncognitoPolicyTest` pins the truth table. The settings plumbing is checked the usual ways: both
persistence load paths, the reset policy, a literal `saveSetting("incognito_enabled", …)` writer for
`SettingsSurfaceDriftTest`'s F-8 scan, and the search index (173 entries, filed under `privacy`).
`ReleaseClaimCommandCatalogueTest` is satisfied — the catalogue floor is 208 and may grow, and the
mask emoji is not a Private Use Area code point, so it needs no key-font entry. What no test can do
here is watch a word typed in incognito fail to reach the bigram store; that is a device check, and
the cheapest one is: type something distinctive in incognito, turn incognito off, and confirm the
keyboard does not suggest it.
