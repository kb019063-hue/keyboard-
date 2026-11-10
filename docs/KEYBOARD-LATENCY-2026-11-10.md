# Keyboard latency: first investigation

The report is that this keyboard feels slow both to appear and while typing. No device trace or
before/after timings are available, so these are targeted reductions in work, **not a demonstrated
root cause or a measured fix**.

- Cold service startup synchronously initializes the compose table before the keyboard appears.
  Its generated loader used `DataInputStream` directly over the Android asset stream, performing
  byte-at-a-time reads for an 8,680-entry / 52,084-byte table. It now buffers reads. The generator
  and generated Kotlin are both changed; the real-table regression test checks decoded content
  and counts underlying reads. This preserves synchronous readiness of shift and compose keys.
- The delayed autocapitalisation callback can request the same shift state repeatedly while typing.
  `Keyboard2View.set_shift_state` previously invalidated the whole keyboard even when no modifier
  changed. A pointer change already sends `onPointerFlagsChanged` and invalidates; the method
  still refreshes its modifier snapshot and invalidates if that snapshot differs. Tests cover
  no-op and changed snapshots. This does not remove the touch-driven redraw or the caps update.
- On a field start, `clearSpecialLayout` already resolves and returns the text layout, which the
  service then resolved again. The returned layout is now reused. The modifier has a cache, so
  the second call was generally a cache hit; this is a small cleanup, not a claim of major savings.

Neither compile nor device testing was available in the sandbox. Verify with an installable CI
build: compare cold and warm show-to-first-frame and key-to-visible-text on the same device and
editor, and test shift, caps lock, password/numeric fields, layout changes, theme preview and
swipe. Android system tracing / Perfetto can separate a busy IME main thread, input dispatch,
editor-side delays, GC and rendering. Trace data and screenshots may include sensitive app context;
never capture or share actual private text.
