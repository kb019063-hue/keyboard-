# Updated comparison: visible regressions

The owner's 2026-11-05 screenshots show the same actions in CleverKeys (light) and
Gboard (dark with a personal image). They are not the same theme, so the wallpaper and
light/dark palette cannot be treated as evidence of a broken theme.

* In the messaging screenshot the CleverKeys toolbar's dark icons sit directly on the dark
  app. The toolbar passed an alpha byte to `setBackgroundColor` as though it were ARGB,
  making its background effectively transparent. It now uses the same background policy as
  the suggestion strip, including the keyboard field for the glass theme.
* After dismissing a content pane with an empty suggestion strip, the strip's scroll view
  was restored at height zero. When predictions arrived the parent grew, but the child
  remained zero-height. It now follows the parent; the geometry test covers this transition.
* Opening emoji after typing “Are” searched for that word automatically. The comparison
  shows Gboard opening on recent emoji instead. CleverKeys now opens with an empty query and
  shows the first emoji category if there is no history. Search remains available by typing.

These fixes do not yet reproduce Gboard's full emoji browser or its in-keyboard tools grid:
CleverKeys still shows its search panel above the letters and uses a tools dialog. Nor do
these screenshots establish that prediction results or key geometry match on the owner's
installed version. Verify on the phone with the current debug build, including opening and
closing emoji, typing afterwards, and using a light keyboard over a dark app.

## Typing-height audit (2026-11-08)

The suggestion band used to collapse whenever the suggestion list became empty and expand when
new suggestions arrived. This can happen between the current-word and next-word predictions when
space is pressed; changing the IME window height can move the editor and disturb typing. The
toolbar and suggestions now share a single fixed-height header. The one-time
`stable_suggestion_strip_v1` migration also reserves an empty strip by default when the toolbar
is disabled, including on installs with a stored old `false` value. The **Always Show Suggestion
Strip** setting can still opt out when the toolbar is disabled, which may bring movement back.

An occupancy/geometry test covers suggestions appearing, clearing, and next-word suggestions
appearing; a migration test covers upgrades and later opt-outs. Android build and device checks are
still required.
