# Which build is on the phone? — 2026-11-28

The owner said it before any of us noticed:

> "since the app got made or maybe a little after that, the version has always been the same,
> whether a new or not (something like 2000002 version). I thought one of you AIs might find out
> but no, I have to tell you first"

They were right. Every dev build shipped as `versionName 2.0.0` / `versionCode 200002`, so:

- the phone could not tell them whether the download had installed,
- a screenshot could not tell us which APK it came from,

and two rounds of "it still isn't fixed" were spent on APKs that were older than the fix. That is
a tooling failure, not a UI one.

## What a build says about itself now

| Where | What it shows | Example |
| --- | --- | --- |
| Android **App info** → Version | version name, with the commit for dev builds | `2.0.0-dev.a5bdfdd` |
| Settings → **Version Information** | the visible build line (tap-hold copies it) | `Build: 2.0.0 · a5bdfdd · 2026-11-28` |
| Settings → keyboard **timings** card | version, commit, debuggable | `2.0.0, a5bdfdd, debuggable` |
| The `dev-builds` release page | the same commit, so a download can be matched to a phone | — |

`version_info.txt` (generated at build time into `res/raw`) now carries `commit=` and `date=`
beside `version=`. The settings card puts all three on one line; the timings card was already
reading `version` and `commit` and simply never received one.

## How the stamp gets in

1. `.github/workflows/build-apk.yml`, step **Stamp the build**, runs *before* `assembleDebug` and
   exports `CLEVERKEYS_DEV_BUILD_ID=$(git rev-parse --short HEAD)`.
2. `build.gradle` reads it at configuration time:
   - `versionName = 2.0.0-dev.<sha>` when the variable is set,
   - `version_info.txt` gets `commit=<sha>` and `date=<commit date>`.
3. Nothing else changes: same `versionCode` (200002), same debug keystore, so it is still an
   in-place upgrade.

## The rules it must not break

- **Release builds stay plain.** No `CLEVERKEYS_DEV_BUILD_ID` (F-Droid, a local build, a source
  tarball) → `versionName` is exactly `2.0.0`, which is what the F-Droid recipe and the
  reproducibility guard expect.
- **Reproducibility.** The stamp is the commit hash and the commit's *own* date — both fixed for a
  given tree — never a wall-clock build time. Two builds of one commit are still byte-identical.
  `BuildStampDriftTest` pins this (`no wall-clock timestamp in the version file`).
- **No git is not an error.** Without a checkout the two fields are omitted and the file reads
  exactly as it did before.

## Pinned by

`BuildStampDriftTest` (in the per-push subset in `build-apk.yml`): the workflow stamps before it
builds, the version name carries the stamp, a release build stays plain, the version file carries
the commit and date, and the settings card shows what the file carries.
