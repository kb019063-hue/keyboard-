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
- **CI runs `assembleDebug` only, not `test`.** Upstream drift tests assert on files this fork
  does not vendor and would fail for reasons unrelated to correctness.
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
- Every shipped layout is validated by `tools/check_layout.py` in the build. Run it on a layout
  you edit.
- A `SystemLayout` preference entry resolves to the current subtype's `default_layout` in
  `res/xml/method.xml` — changing a user's default layout means changing that file, not
  `LayoutsPreference.DEFAULT`.

## Documentation

Design decisions live in `docs/`, numbered by phase (`PHASE2-*`, `PHASE3-*`, `PHASE4-*`). When
you ship something a doc describes, update the doc in the same commit; when you discover a
behaviour that a doc or code comment states wrongly, fix the statement rather than leaving a
comment that lies about what the code does.
