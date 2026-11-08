# Gboard header and typing-height research — 2026-11-08

## What the owner's screenshots show

These are observations of the owner's particular Gboard and configuration, not a claim about
all versions or about Google's private implementation.

- `144300` (empty field): one header row contains the four-square menu, commands, and mic.
- `144309` (typed “Are”): the **same row** has the menu at left, three word suggestions in
  the middle, and mic at right. The middle toolbar shortcuts are not shown. The row and number
  keys appear at the same screen coordinates in both images.
- `144316`: tapping the menu opens tools within the keyboard, with a close button in the header.
  `144341` shows an emoji browser instead of the normal letter keys, with recent emoji visible.
- In CleverKeys `144154` and `144217`, the number row appears at the same vertical position,
  but the toolbar and app content are higher in the latter, with an empty band below the toolbar.
  Still images alone do not establish precisely what moves during a space press or prove the
  cause. A device test across keystrokes and IME insets is needed.

An earlier service comment saying the owner's Gboard keeps every toolbar icon while suggestions
are shown is inconsistent with `144309`; the screenshot is the better evidence for this setup.

## What public documentation says

- [Google's Gboard help](https://support.google.com/gboard/answer/7068415?hl=en-DO)
  describes word suggestions at the top of the keyboard and options for next-word suggestions.
- [Android Police's toolbar description](https://www.androidpolice.com/gboard-quick-access-toolbar-revamp/)
  describes the four-square tools menu and toolbar customization. Exact shortcuts and placement
  can vary with version and user settings.
- Android's [InputMethodService reference](https://developer.android.com/reference/android/inputmethodservice/InputMethodService)
  distinguishes the IME input view and optional candidates view; an app may be moved or resized
  to accommodate the IME. It explicitly says transient *system candidates views* don't resize
  the app, though they may cause panning. This does **not** establish that Gboard uses that API.
- Android's [IME inset documentation](https://developer.android.com/develop/ui/views/layout/sw-keyboard)
  describes observing IME height with `WindowInsets`. This can help distinguish a changing IME
  height from app scrolling/panning on a real device.
- [Gboard is proprietary](https://stackoverflow.com/questions/64316829/is-the-source-code-of-android-tvs-gboard-available);
  its internal layout and prediction machinery cannot be inferred from the screenshots or the
  public Android framework docs.

## Implications for CleverKeys

The typing header now shares one fixed-height row: the full toolbar is shown when the strip
is empty; suggestions, messages, password controls, or autofill replace its middle while the
first and last configured toolbar commands remain at the edges. The edge widths are measured so
custom command labels do not overlap suggestions. When the toolbar is disabled, the existing
always-visible setting determines whether an empty strip reserves space. A content pane remains
below the toolbar and is not mistaken for the typing strip.

This remains unverified on a real device. The existing more-tools dialog is not Gboard's full
tools grid, so middle toolbar actions are not all reachable from that dialog while suggestions
are showing. That is a separate, still-open parity gap.
Content panes need their own deliberate layout: the Gboard tools and emoji screenshots replace
keyboard content rather than adding another full-height panel above the letters. That is a
separate change from stabilizing the typing header.

Before calling the movement fixed, test the *actual IME* on device in the same editor: record
screen Y coordinates of the number row, toolbar, editor and IME top/insets during typing,
space, next-word results, deletion, suggestions on/off, and return from a content pane. Check
portrait/landscape, dark/light apps, accessibility and navigation insets. Compare with Gboard on
the same phone. Current JVM/view tests cannot substitute for that end-to-end evidence.
