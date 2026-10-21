# keyboard-

A personal, **GPL-3.0** Android keyboard (IME). 100% offline — the manifest declares no
`INTERNET` permission, so nothing can phone home by construction.

Built on top of [CleverKeys](https://github.com/tribixbite/CleverKeys), with a planned set of
feature ports from [HeliBoard](https://github.com/HeliBorg/HeliBoard). See
[`docs/PROVENANCE.md`](docs/PROVENANCE.md) for the full attribution and license position.

## Why this exists

Gboard's clipboard keeps ~5 snippets and drops the oldest. This keyboard keeps them
indefinitely. Out of the box, on the inherited defaults in `Config.kt`:

| Setting | Default | Meaning |
|---|---|---|
| `CLIPBOARD_HISTORY_LIMIT` | `0` | **Unlimited** entry count — `applySizeLimit()` skips pruning entirely |
| `CLIPBOARD_HISTORY_DURATION` | `-1` | **Never expire** — `cleanupExpiredEntries()` is skipped; `rescueExpiredEntries()` runs instead |
| `CLIPBOARD_MAX_ITEM_SIZE_KB` | `512` | Per-item cap, sized to stay under Android's ~1 MB Binder IPC limit |
| `CLIPBOARD_LIMIT_TYPE` | `count` | Limit by count (alternative: `size`, capped by `CLIPBOARD_SIZE_LIMIT_MB = 5`) |
| `CLIPBOARD_PINNED_ENABLED` / `CLIPBOARD_TODO_ENABLED` | `true` | Pinned and todo tabs visible |

Plus tags, regex search, inline editing, media capture, and export/import — all stored locally.

Two defaults deliberately **withhold** entries, and are worth knowing about if you paste
credentials a lot: `CLIPBOARD_EXCLUDE_PASSWORD_MANAGERS = true` and
`CLIPBOARD_RESPECT_SENSITIVE_FLAG = true` (honours the Android 13+ `IS_SENSITIVE` flag).
Both are toggleable in Settings.

## Build

There is no local build path in this project's dev sandbox — the Android SDK, Gradle
distribution, and Maven Central are all unreachable from it. **GitHub Actions is the build
system.** `.github/workflows/build-apk.yml` runs on every push and produces per-ABI debug APKs.

The build is a single-module Gradle project rooted at the repo:

```
./gradlew assembleDebug     # -> build/outputs/apk/debug/*.apk
```

Toolchain (pinned by `gradle/wrapper/gradle-wrapper.properties` and `build.gradle`):
Gradle 8.10.2 · AGP 8.8.2 · Kotlin 2.0.0 · compileSdk 34 · minSdk 24 · Java 11 target · JDK 17 to run Gradle.

Two build-time details that are easy to trip over:

- **Python 3 is required at build time.** `build.gradle` shells out to `scripts/generate_binary_dict.py`,
  `generate_binary_contractions.py`, `generate_settings_search_index.py`, `generate_compose_bin.py`,
  `tools/check_layout.py` and `normalize_apk.py`. All are **stdlib-only** — no pip installs needed.
  Their outputs feed back into `kotlin.srcDirs` and `res.srcDirs` under `build/generated/`.
- **Dependency locking is ACTIVE** for the debug/release compile and runtime classpaths
  (`gradle.lockfile`). Any dependency change must be regenerated with
  `./gradlew assembleDebug --write-locks` or resolution fails outright.

## Install

1. Open the repo's **Actions** tab → latest *Build APK* run → download the artifact, **or**
   grab it from the rolling `dev-builds` release (one permanent URL per ABI).
2. Pick your ABI — **`arm64-v8a`** for essentially any phone from 2017 onward;
   `armeabi-v7a` for older 32-bit devices; `x86_64` for emulators.
3. Allow "install unknown apps" for your browser, install the APK.
4. Android Settings → System → Languages & input → On-screen keyboard → enable it.
5. Open the keyboard's launcher activity and follow the enable prompt.

Debug builds are signed with the committed `debug.keystore`, so **successive builds install as
upgrades** over each other — no uninstall needed. That keystore is a debug key, not a release
secret; it is inherited from upstream, where it is likewise public.

## Tests

CI runs `assembleDebug` only — **not** `test`. Upstream carries a set of "drift tests" that
assert on repository files this fork intentionally does not vendor, e.g.
`ReleaseRecordDriftTest` reads `docs/RELEASE_RECORD.md` and `CuratedInstrumentationListTest`
reads `.github/scripts/emulator-ci.sh`. Running them here would fail for reasons unrelated to
code correctness. Restore those paths before re-enabling the test gate.

The instrumented-test langpacks are likewise absent: `copyScriptLatencyPacks` copies six
`langpack-*.zip` files out of `scripts/dictionaries/`, which is not vendored. That task is wired
only to `*AndroidTest*` targets, so `assembleDebug` is unaffected.

## Roadmap

Port from HeliBoard, in rough priority order:

1. **Theme depth** — dynamic colour (Android 12+), follow-system day/night, background images.
2. **Form factors** — one-handed mode, split keyboard, floating keyboard.
3. **D-Pad / arrow key row** and toolbar-key polish.
4. **Backup & restore** of settings plus learned-word data.
5. **Broader layout/language coverage.**

Every port is a *down-port*: HeliBoard is on AGP 9.1.1 / Kotlin 2.4.0 / compileSdk 37 / Java 17 /
minSdk 21, this tree is AGP 8.8.2 / Kotlin 2.0.0 / compileSdk 34 / Java 11 / minSdk 24. Only one
toolchain can win a single APK build, so HeliBoard code gets adapted to this one — never merged
wholesale. Note also that HeliBoard's glide typing depends on a **closed-source** `swypelibs`
binary that is not redistributable; this fork keeps CleverKeys' open ONNX gesture engine instead.

## License

GPL-3.0-only. Derivative of CleverKeys (GPL-3.0), which derives from Unexpected Keyboard
(GPL-3.0). Any HeliBoard code ported in is GPL-3.0 with Apache-2.0-licensed AOSP components and
a CC-BY-SA-4.0 icon. Combining GPL-3.0 code from both upstreams is permitted provided the result
stays GPL-3.0 and all copyright notices are preserved — see `LICENSE`, `NOTICE`, and
[`docs/PROVENANCE.md`](docs/PROVENANCE.md).
