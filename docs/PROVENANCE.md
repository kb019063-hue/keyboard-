# Provenance, attribution and license position

This file records where every part of this tree came from, what was removed and why, and the
GPL-3.0 obligations that follow. Keep it updated — it is the compliance record for this fork.

## Base

| | |
|---|---|
| Upstream | [`tribixbite/CleverKeys`](https://github.com/tribixbite/CleverKeys) |
| Commit | `b2e2577849099925bdcf9b864700b018ac135ae4` |
| Commit date | 2026-10-21T03:40:08-04:00 |
| Branch | `main` |
| License | GPL-3.0 |
| Vendored on | 2026-10-21 |

CleverKeys is itself a derivative of
[Unexpected Keyboard](https://github.com/Julow/Unexpected-Keyboard) (GPL-3.0). Its `NOTICE` file
carries the full attribution chain and is preserved verbatim at the repository root, together
with `LICENSE`.

The base was vendored as a **snapshot, without upstream git history**. That was a deliberate
trade: it keeps this repository small and its diffs readable, at the cost of `git blame`
crossing the fork boundary. To trace a line back through upstream history, clone CleverKeys and
check out `b2e2577`.

## No local modifications yet

As of the vendoring commit, the Kotlin sources, resources, assets and build scripts are
**byte-identical to upstream `b2e2577`**. The only additions are this file, `README.md`, and
`.github/workflows/build-apk.yml`. The `applicationId` is still upstream's
`tribixbite.cleverkeys` and the app label is still `CleverKeys`; rebranding is a separate,
later commit so that a build failure is always attributable to exactly one change.

## What was trimmed, and why

Upstream HEAD is 147 MB across 2,338 files. The build-essential subset is 46 MB across 1,620
files. Nothing required by `./gradlew assembleDebug` was removed.

### Removed

| Path | Size | Reason |
|---|---|---|
| `web_demo/` | 38.9 MB | Browser WASM demo of the swipe model (`ort-wasm-simd.wasm`, duplicate `.onnx` files). Not part of the APK. |
| `scripts/dictionaries/` | 33 MB | Langpack `.zip`/`.bin` data. Read only by the `copyScriptLatencyPacks` task, which `build.gradle` wires exclusively to `merge*AndroidTestAssets`, `generate*AndroidTestLintModel` and `lintAnalyze*AndroidTest`. |
| `docs/` | 19.6 MB | Specs, evals and history. Referenced by `build.gradle` only inside comments, and by *drift unit tests* — never by `assembleDebug`. |
| `art/` | 6.9 MB | Source logo art consumed by `scripts/generate_icons.sh`. That script is a CI-only step, not a Gradle task, and the generated `res/mipmap-*` icons are already committed — so this fork does not run it and does not need ImageMagick. |
| `fastlane/` | 2.6 MB | F-Droid store listing metadata. |
| `site/`, `screenshots/`, `memory/`, `metadata/`, `model/`, `.claude/`, `swype-model-training/` | ~1.9 MB | Docs site, screenshots, agent working notes, ML training scaffolding. |
| `CHANGELOG.md`, `README.md`, `CLAUDE.md`, `AGENTS.md`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `SECURITY.md`, `MAINTAINERS.md`, `ROADMAP.md`, `RELEASE_NOTES.md`, `CITATION.cff`, `.trivyignore` | ~0.2 MB | Upstream community and agent-workflow docs. Superseded here by this fork's own `README.md`. |

### Kept (build-essential)

| Path | Size | Required by |
|---|---|---|
| `src/` | 36 MB | `sourceSets.main` — Kotlin sources plus bundled assets, including `src/main/assets/models/ctc_swipe_encoder.onnx` (3.05 MB) and the dictionary data |
| `res/` | 6 MB | `res.srcDirs = ['res', 'build/generated/layouts/res', 'build/generated/version/res']`; includes the committed `mipmap-*` launcher icons |
| `scripts/` (root files) | 3 MB | Invoked by `build.gradle` at build time — see below |
| `srcs/` | 2 MB | `srcs/compose/compose` is an input to `generate_compose_bin.py` (`build.gradle:1709`) |
| `tools/` | 1 MB | `tools/check_layout.py` is exec'd from `build.gradle:1610` |
| `assets/` | 0.02 MB | Root `assets` srcDir; contains `special_font.ttf` |
| `gradle/`, `gradlew`, `gradlew.bat`, `build.gradle`, `settings.gradle`, `gradle.properties`, `gradle.lockfile` | | Build system. Dependency locking is **active** for the debug/release compile and runtime classpaths |
| `AndroidManifest.xml`, `proguard-rules.pro`, `proguard-soak-testkeep.pro`, `lint-baseline.xml` | | `manifest.srcFile`, `proguardFiles` (`build.gradle:455`) |
| `debug.keystore` | 2.6 KB | Stable debug signature — see the note below |
| `LICENSE`, `NOTICE` | 50 KB | GPL-3.0 §5 notice preservation |
| `.gitignore`, `.gitattributes`, `.editorconfig` | | Repo hygiene |

### Build-time Python

`build.gradle` execs Python 3 during the build. All of these are **stdlib-only** (`json`, `re`,
`struct`, `sys`, `unicodedata`, `pathlib`, `typing`, `collections`, `os`, `argparse`, `shutil`,
`subprocess`, `tempfile`, `zipfile`, `array`), so CI needs no pip step:

- `scripts/generate_binary_dict.py` → `build.gradle:1633`
- `scripts/generate_binary_contractions.py` → `build.gradle:1646`
- `scripts/generate_settings_search_index.py` → `build.gradle:1684` (generates into `build/generated/search/kotlin`, which is a declared `kotlin.srcDir`)
- `scripts/generate_compose_bin.py` → `build.gradle:1699,1709`
- `scripts/normalize_apk.py` → `build.gradle:1750`
- `tools/check_layout.py` → `build.gradle:1610` (`ignoreExitValue = true`)

## Two `.gitignore` traps

Upstream's `.gitignore` excludes files that upstream nonetheless **tracks** (grandfathered, per
the comment at `.gitignore:114`). In a fresh clone only tracked files exist, so this fork had to
force-add them; a plain `git add` would have silently dropped both.

1. `debug.keystore` — matched by `*.keystore` (`.gitignore:35`). If absent, `build.gradle:1714`
   runs `keytool -genkeypair` to create a throwaway keystore. That produces a **different
   signature on every CI run**, so each new APK would require uninstalling the previous one.
   Committing the stable key makes successive builds install as upgrades. This is a debug key
   inherited from upstream, where it is public; it is not a release secret.
2. `src/main/assets/dictionaries/en_enhanced.bin` — explicitly ignored at `.gitignore:40`
   (regenerable from the committed `.json` by `generate_binary_dict.py`) but tracked upstream.
   Vendored as upstream's exact bytes so the build's inputs are known-good rather than dependent
   on generator wiring. Same reasoning for `contractions.bin` (`.gitignore:41`).

## Known consequences of the trim

- **`./gradlew test` will fail.** Drift tests assert on non-vendored repository files:
  `ReleaseRecordDriftTest` reads `docs/RELEASE_RECORD.md`, `CuratedInstrumentationListTest` reads
  `.github/scripts/emulator-ci.sh`, and translation-coverage tests read `fastlane/`. These
  failures describe the trimmed tree, not broken code. CI therefore runs `assembleDebug` only.
  Restore the relevant paths before re-enabling a test gate.
- **Instrumented tests cannot stage langpacks.** `copyScriptLatencyPacks` needs six
  `langpack-*.zip` files from `scripts/dictionaries/`. Affects `*AndroidTest*` targets only.
- **No icon regeneration.** `res/mipmap-*` is used as committed. To rebrand the icon, replace
  those PNGs directly (or restore `art/` + `scripts/generate_icons.sh` and ImageMagick).

## License position

Both upstreams are **GPL-3.0**, so this combined derivative must be GPL-3.0 and must preserve all
copyright notices. That is satisfied by keeping `LICENSE` and `NOTICE` verbatim at the root and
by this attribution file.

- CleverKeys — GPL-3.0, © 2024-2026 tribixbite. Derivative of Unexpected Keyboard (GPL-3.0).
- HeliBoard — GPL-3.0, © Helium314 and contributors. Because it derives from the AOSP keyboard,
  upstream additionally ships `LICENSE-Apache-2.0` (for the AOSP components) and
  `LICENSE-CC-BY-SA-4.0` (for its icon). **When HeliBoard code is first ported in, both license
  files must be added to this repository and the ported files must carry their original
  headers.**
- HeliBoard's glide typing depends on a proprietary `swypelibs` native library that users extract
  from GApps. It is **not redistributable and will not be vendored here.** This fork keeps
  CleverKeys' open ONNX gesture engine (`ctc_swipe_encoder.onnx`, 3.05 MB), whose training code
  is public.

This repository is currently **private**. GPL-3.0 obliges you to offer the corresponding source to
anyone you distribute a binary to. Building and installing APKs on your own device is not
distribution and creates no obligation; handing APKs to other people does. If that happens, make
the repository public or provide the source alongside the binary.

## Upstream tracking

Upstream is active (pushed 2026-10-21, 485 stars, 71 open issues). To diff against a newer
upstream release:

```sh
git clone --depth 1 https://github.com/tribixbite/CleverKeys.git /tmp/ck
diff -ru --exclude=.git /tmp/ck/src src
```
