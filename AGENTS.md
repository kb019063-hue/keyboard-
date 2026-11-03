# AGENTS.md

Conventions for anyone — human or AI — changing this repository. Read this first; each item
below is here because it already cost someone a day.

## Before you touch CI

**Read [`docs/CI-STORAGE.md`](docs/CI-STORAGE.md).** The short version:

- **Actions artifacts and release assets are different stores.** The APK the owner installs is a
  *release asset* on the `dev-builds` tag. Artifacts are temporary per-run files. Deleting
  artifacts cannot break the app, and a full artifact quota cannot stop the release — but a
  failed upload step *scheduled before* the release step will, and did.
- **ABI splits triple every per-build byte.** Three APKs at ~31 MB are ~94 MB per build, and a
  private repo gets **500 MB** of artifact storage in total. A glob of
  `build/outputs/apk/debug/*.apk` uploads all three as one artifact. Always run the arithmetic in
  that doc before adding an upload.
- **Never upload per-push APK artifacts.** They duplicate the release.
- **Every `upload-artifact` is `continue-on-error: true`**, retention ≤ 7 days.
- **Report a test's verdict as an annotation before uploading evidence**, so a storage failure
  cannot change the verdict. Run logs are unreadable from the dev sandbox
  (`results-receiver.actions.githubusercontent.com` is unreachable); `api.github.com` annotations
  are the only channel that works.
- Deleting artifacts needs `actions: write`, which the Arena integration token lacks (403). Do not
  promise the owner that you have cleared it.

## Build and test

- **The dev sandbox cannot build.** No Android SDK, no Gradle distribution, no Maven Central.
  GitHub Actions is the compiler: push and read the run. Never claim a Kotlin change is correct
  because it "looks right"; say it is unverified until a green build says otherwise.
- **CI runs the tests too.** `build-apk.yml` runs a ~65-class / ~770-test subset on every push;
  `.github/workflows/full-test-suite.yml` runs **all 413 classes / 4,650 tests** nightly, on demand,
  and whenever that file or `build.gradle` changes. Both are green as of run 41374157846
  (`4,650 run, 0 failed, 204 skipped`), so **a red run now means a real problem in this
  repository** — do not explain one away as "upstream drift". The 204 skips are 22 classes that
  assert on artefacts this fork does not vendor; they report SKIPPED through `UpstreamArtefacts`,
  not failed and not passed.
- **When a test is red, read [`docs/TEST-PITFALLS.md`](docs/TEST-PITFALLS.md) before "fixing" it.**
  That file is the catalogue of the twelve ways tests in this repo have lied — entire classes
  passing with the feature switched off, assertions on a device path a stub cannot produce, source
  scans that cannot see a wrapped call. Most reds here have been the test, not the app; a few were
  the app, and telling them apart is the whole job.
- Python 3 is required at build time (`build.gradle` execs six stdlib-only generators). No pip.

## The installed build is the DEBUG variant

`build.gradle` gives debug `applicationIdSuffix ".debug"`, `minifyEnabled false`,
`debuggable true`, and a stable committed keystore so builds install as upgrades. That is the APK
the owner actually uses, so **debug-only costs are user-visible costs** — verbose logging used to
be forced on here and made typing feel slow. Keep `ENABLE_VERBOSE_LOGGING` opt-in
(`LOCAL_BUILD=true`), and gate anything per-frame or per-touch behind it.

A minified release APK is faster but has a different `applicationId`, so it cannot install over
the debug app — it arrives as a fresh app and loses settings, learned words and clipboard history
unless they are migrated through backup/restore.

## Layout files

- `loc <key>` in a layout is a **placement marker, not a printed label** ("Symbols prefixed with
  'loc ' are not visible on the keyboard"). `KeyboardData` records it as `Key.F_LOC`; the renderer
  must not draw it. Do not add `loc` corners expecting them to appear.
- Every shipped layout is validated by `tools/check_layout.py` during `preBuild` (so
  `assembleDebug` sees it). Duplicate key values are a hard build failure because
  `KeyboardData.getKeys()` keeps only one position per value; the checker's other findings
  stay advisory. Run it on a layout you edit.
- A `SystemLayout` preference entry resolves to the current subtype's `default_layout` in
  `res/xml/method.xml`. English subtypes, including `en-GB` and `en-GH`, name
  `latn_qwerty_us_gboard_style`. When no subtype resolves, `KeyboardComponentGraph` falls back
  to that same layout — not the dense `latn_qwerty_us`. `tools/check_layout.py` fails the build
  if that chain drifts. Changing the default means changing `method.xml` and that fallback
  together, not `LayoutsPreference.DEFAULT`.

## What the owner has already decided

Read [`docs/OWNER-DECISIONS.md`](docs/OWNER-DECISIONS.md) before "improving" the look. Short
version, do not revert:

- The target layout is the Gboard screenshot, not the dense October 23 CleverKeys photo.
  English defaults and the no-subtype fallback are `latn_qwerty_us_gboard_style`.
- The owner rejected the purple default. `Defaults.THEME` is `harbor`. The wardrobe is
  Harbor, Daybreak, Signal, Ember, Kiln. Purple remains selectable; it is not the default.
- The toolbar is seven buttons, mic last. Icon buttons are dead if the inner `ImageView` is
  clickable.
- The installable app is the debug APK on the rolling `dev-builds` release:
  `keyboard-arm64-v8a.apk` for a phone.

## Documentation

Design decisions live in `docs/`, numbered by phase (`PHASE2-*`, `PHASE3-*`, `PHASE4-*`). When
you ship something a doc describes, update the doc in the same commit; when you discover a
behaviour that a doc or code comment states wrongly, fix the statement rather than leaving a
comment that lies about what the code does.
