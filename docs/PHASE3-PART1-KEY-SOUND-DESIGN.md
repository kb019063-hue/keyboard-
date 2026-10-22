# Phase 3, part 1 — key-press sound

Phase 3 is the four remaining HeliBoard gaps, shipped easiest-first in four parts:
**key-press sound/haptics** (this document), incognito toggle, floating keyboard, split keyboard.
The gap analysis (`HELIBOARD-GAP-ANALYSIS.md`) found CleverKeys already had a rich haptic stack —
a master `haptic_enabled` toggle, five per-event toggles, a custom-duration path — and **no sound
at all**. HeliBoard's `sound_on` preference had no counterpart here. So part 1 is the audio half of
key-press feedback, built to mirror the haptic half rather than invent a second architecture.

## Decisions

**SoundPool, not `AudioManager.playSoundEffect`.** The system call honours the global "touch
sounds" setting, so a user who enabled key sound in this app would hear nothing if they had touch
sounds off system-wide — a setting that looks broken. SoundPool plays our own sample at our own
volume, independent of that global. This is the same reasoning the haptic path already follows:
`VibratorCompat` passes `FLAG_IGNORE_GLOBAL_SETTING` so the app's own toggle is authoritative.

**The sample is synthesised, not recorded.** `scripts/generate_key_sound.py` writes
`res/raw/key_click.wav`: a 45 ms burst of decaying broadband noise (the plastic tick) blended with
a 1.9 kHz body tone (the thock), peak-normalised to 0.7, 22 050 Hz mono 16-bit, about 2 KB. A fixed
seed makes the asset byte-reproducible, the same arrangement as the launcher icon, the dictionaries
and the settings search index. There is no binary blob in the tree whose origin nobody can explain.

**Off by default.** A keyboard that clicks on every tap is intrusive until asked for; Gboard and
HeliBoard both ship sound off. `sound_enabled` defaults false, so the keyboard after this change is
exactly as quiet as before it. `sound_volume` is a 0–100 percentage of the sample, default 70,
exposed as a slider that appears only when the toggle is on.

**One seam, shared with haptics.** `Keyboard2View.vibrate()` already funnels every key-press haptic
through `VibratorCompat.vibrate(this, _config, event)`. The click is played in the same function,
gated on `event == HapticEvent.KEY_PRESS`, so a key that can vibrate is a key that can click and the
two can never drift apart. `KeyClickSound.play` returns immediately (SoundPool mixes in its own
thread), so it adds no latency to the keystroke, and is a no-op when the sample has not finished
loading — dropping the first click after enabling is inaudible; blocking a keystroke on a
SoundPool load would not be.

**Lifecycle.** The pool is created lazily on first enabled play (a keyboard that never enables
sound never pays for one native SoundPool) and released in `Keyboard2View.onDetachedFromWindow`,
so IME switches and rotation cannot leak it.

**Config follows the haptic pattern.** `Defaults.SOUND_ENABLED` / `Defaults.SOUND_VOLUME`, two
`@JvmField` vars on `Config`, read in `Config.refresh()` so a Settings change reaches the next
keystroke through the same live-refresh chain as the haptic flags. The Settings switch also writes
`Config.globalConfig()` immediately, exactly as the vibration switch does.

## Surface

Settings → Accessibility, directly under the haptic controls: a **Key Sound** header, the
**Sound on Key Press** switch, and a **Key Sound Volume** slider (0–100 %, step 5) shown only while
the switch is on. Sound is deliberately independent of the vibration toggle — a user may want the
click without the buzz.

Strings are English-only at first, matching how the phase 1 and phase 2 strings shipped; they are
not in `TranslationCoverageDriftTest`'s required set, so the 21 locales falling back to English is
the intended, tested-for state. The settings search index picks both controls up automatically
(169 → 172 entries) and files them under the `accessibility` section.

## Verification

There is no JVM unit test for an audio path — SoundPool is native and silent under Robolectric. The
verifiable surface is: the generated WAV's header (channels/width/rate/duration), `Config`'s
defaults and clamping, the settings plumbing (both persistence load paths, the reset policy, and a
literal `saveSetting` writer per new key so `SettingsSurfaceDriftTest`'s F-8 check passes), and the
search index. On device, the sound is a human-ears check: enable it in Settings → Accessibility and
type. The emulator smoke run cannot hear it, and pretending otherwise would be worse than saying so.
