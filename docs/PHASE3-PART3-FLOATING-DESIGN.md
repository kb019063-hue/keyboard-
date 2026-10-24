# Phase 3, part 3 — floating keyboard

Part 3 is the first of the four gaps that needs the platform rather than just the codebase: a
floating keyboard is not a smaller view, it is a differently-placed *window*. HeliBoard ships this
as nine files; CleverKeys had none.

## The mechanism: the IME window's own layout params

The existing minimizer (gh #175) only swaps which view `setInputView` shows — the input method
window itself stays full-width and bottom-anchored, so a bar or a button can be small but a
*keyboard* cannot float that way. What moves a window is its `WindowManager.LayoutParams`: width,
gravity and the x/y offsets. `WindowLayoutUtils.applyFloatingWindowLayoutParams` now owns exactly
those four values — width from a percentage of the screen, gravity `TOP|START` with explicit
offsets, height left as the `WRAP_CONTENT` the existing code sets so the window hugs the keyboard —
and restores the platform default (MATCH_PARENT, BOTTOM, zero offsets) when floating is off.

Gravity `TOP|START` with explicit offsets is the unambiguous half of the window-manager contract:
under BOTTOM gravity the sign of y flips meaning, and a window the user drags must map one-to-one
onto stored numbers. Attributes are reassigned only when something actually changed, because a drag
gesture calls this on every move event and each assignment asks the window manager for a relayout.

It is a separate function, not a fourth parameter on `updateSoftInputWindowLayoutParams`, for two
reasons: that signature is pinned by `WindowLayoutUtilsTest`'s mockk verifications, and the two
concerns genuinely differ — the existing function sets height, input-area gravity and the
transparent backgrounds that hide OEM white bars, and must keep doing exactly that whether or not
the keyboard floats. The fold happens in the service's private `updateSoftInputWindowLayoutParams()`,
the one choke point both of its call sites pass through — the same reasoning that put the incognito
fold in `Config`'s computed reads rather than in its callers.

Floating applies only to a visible, non-minimized keyboard: a minimized bar or button is already
small, and shrinking the window around it would shrink the one thing left to tap.

## Position as fractions of travel

`FloatingConfig` (pure Kotlin, JVM-tested, no `android.*` import, the same arrangement as
`OneHandedConfig`) stores position as fractions, not pixels: a persisted pixel offset from a
portrait session would strand the window off a landscape screen. The x fraction is a fraction of the
window's available horizontal *travel* (screen width minus window width), so 0 is flush left, 1
flush right and 0.5 centred at any width, and narrowing the window can never push it past an edge.
The y fraction places the window's top edge, capped at 0.9 of screen height so the keyboard can
never be dragged until only its grip shows. Width maths is integer, like phase 2's gap width,
because float truncation surprised us there.

## The wrapper: grip and knob outside the scaling

`FloatingRoot` wraps the one-handed root from the *outside*, so the grip (a 26dp bar across the top)
and the resize knob (a 26dp square at the bottom-right corner) are never scaled with the keys — and
while floating is on, the service suppresses one-handed scaling altogether, because two independent
shrinks stacking would leave the keyboard smaller than either setting says. The handles report
movement, not geometry: pixel deltas go to the service, which owns the fractions and the relayout,
so `FloatingConfig` stays the only place the maths lives. Position and width persist on gesture
*end*, not per move event.

While floating is off the wrapper is invisible in every sense that matters — handles GONE, content
holder wrapping the keyboard exactly — which is what lets the service keep it installed for the
whole session and merely flip `setState` on toggle, instead of re-wrapping the input view
mid-keystroke. `host()` is idempotent for the same reason `OneHandedRoot.host` is: theme changes,
pane swaps and minimize/expand all reach `setInputView` again, and nesting a holder in a holder
would shrink the keyboard a little more on every pass.

Resize is width-only, honestly: the window height wraps the keyboard, so there is no independent
height to change without fighting the platform's own keyboard sizing. The knob drags horizontally
and ignores vertical movement.

## Surface and verification

Settings → Appearance: a **Floating Keyboard** switch with a width slider (50–100%, step 5) shown
only while on; and a toolbar command `floating_toggle` (a window glyph, two UTF-16 units, not a
Private Use Area code point) so it can be toggled without leaving the keyboard. Both write the same
preference; `onSharedPreferenceChanged` reaches `applyFloatingToOpenKeyboard`, so an open keyboard
re-wraps and relayouts with no IME restart — the contract one-handed mode keeps.

`FloatingConfigTest` pins the clamps, the integer width conversion, travel-relative centring and the
y cap. The settings plumbing is checked as usual: both persistence load paths, the reset policy,
const-named writers at every write site (the service's `putFloat(FloatingConfig.PREF_X_FRACTION, …)`
satisfies `SettingsSurfaceDriftTest`'s F-8 scan, which resolves const names), and the search index
(173 → 175 entries, filed under `appearance`).

What no emulator run can prove is the one assumption the whole feature rests on: that the window
manager honours gravity and offsets on a `TYPE_INPUT_METHOD` window on the user's launcher, and that
dragging feels like dragging rather than like a series of relayouts. That is the same class of
device-only assumption as phase 2's inverse touch matrix, and it is the first thing to check on the
phone: turn floating on, drag the keyboard to a corner, narrow it, and type.
