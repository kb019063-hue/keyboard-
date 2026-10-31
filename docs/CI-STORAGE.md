# CI storage: artifacts, release assets, and the quota that silently eats builds

Written 2026-10-25 after three days of builds that compiled cleanly, published no APK, and
looked like code failures. Read this before touching `upload-artifact`, the rolling release
step, or the ABI configuration.

## The two stores are not the same thing

| | **Actions artifacts** | **Release assets** |
|---|---|---|
| produced by | `actions/upload-artifact` | `softprops/action-gh-release` (the `dev-builds` tag) |
| lifetime | temporary, expires by `retention-days` | permanent until overwritten |
| who downloads | a developer peeking at one run | **the phone** |
| quota | repo **Actions storage** — a private repo gets **500 MB total** | release storage, separate |
| counts against the quota in this doc | **yes** | no |

The error that means you have hit the artifact quota:

```
Failed to CreateArtifact: Artifact storage quota has been hit. Unable to upload any new
artifacts. Usage is recalculated every 6-12 hours.
```

**Deleting artifacts can never delete the installable APK.** The APK the owner installs lives in
the release. This confusion costs real time: during the 2026-10-23 outage the owner was told the
storage was full and reasonably concluded the latest APKs were not being published. They were —
the release was fine, a *different* upload had failed.

## Why this repo filled 500 MB in three days

ABI splits. `build.gradle` produces three APKs per build (`armeabi-v7a`, `arm64-v8a`,
`x86_64`), roughly 31 MB each. The old step was:

```yaml
path: build/outputs/apk/debug/*.apk
```

Note the `*.` — that glob uploads **all three ABIs as one artifact**, ~94 MB per build. For three
days of pushes that was 41 artifacts, **3,834 MB**, i.e. 7.7x the entire quota, from 41 pushes.

Do this arithmetic before adding any upload step:

```
(APK size × ABI count) × pushes per day × retention days  <  500 MB   (private repo)
```

94 MB × 2 pushes/day × 30 days = **5.6 GB**. Three ABIs are what make the per-build number three
times bigger than anyone expects when they think "it is only a 31 MB APK".

## The trap: why this looked like a code bug

The upload was the **last** step, and the release step came after it. So a quota 403:

1. left `assembleDebug` green — the code was genuinely fine;
2. failed the job on a step that emits no compiler output, so the only visible line was
   `Process completed with exit code 1`;
3. **skipped the release step**, so nothing was published.

The reported symptom is "the build is broken" or "the latest APKs are not falling through",
which points straight at the source. It never was the source. Compounding it: run logs are served
from `results-receiver.actions.githubusercontent.com`, which the dev sandbox cannot reach — only
`api.github.com` annotations get through, and a failed upload produced no annotation at all.

The same failure hit the emulator smoke workflow's *evidence* upload, which turned a **passing**
test run red because the upload step ran last and unconditionally.

## Rules

1. **The rolling release is the delivery path.** Do not upload per-push APK artifacts. They are
   pure duplication of something already published at a permanent URL.
2. **Publish the release before any optional upload**, so no upload failure can suppress the
   deliverable. (This is why the artifact step now follows the release step.)
3. **Any `upload-artifact` step is `continue-on-error: true`.**
4. **Retention ≤ 7 days** for anything, 3 days for multi-megabyte binaries.
5. **An evidence upload must never decide a test's verdict.** Emit the verdict as a
   `::notice`/`::error` annotation *before* uploading anything.
6. **Before merging a change that adds artifacts or more ABIs**: run the arithmetic above.
7. Artifacts that are still useful (smoke evidence) are **small** — keep them, they are not the
   problem. Evidence from a failed run is usually the only way to see what went wrong, so do not
   trade it away to save a few MB.

## Clearing the backlog

Deletion needs the `actions: write` permission. **The Arena integration token does not have it** —
every attempt returns `403 Resource not accessible by integration`, so an agent cannot do this
for the owner and must not claim otherwise.

- **Web UI**: Actions tab → open a run → delete the run (that deletes its artifacts). The
  offending ones are all named `keyboard-debug-*`.
- **Retention setting** (future artifacts only): Settings → Actions → General → *Artifact and log
  retention*. Existing artifacts keep the expiry they were created with.
- **CLI**, with a personal token that has *Actions: write*:

  ```bash
  gh api "repos/kb019063-hue/keyboard-/actions/artifacts?per_page=100" --paginate \
    --jq '.artifacts[] | select(.name | startswith("keyboard-debug-")) | .id' \
  | while read -r id; do gh api -X DELETE \
      "repos/kb019063-hue/keyboard-/actions/artifacts/$id"; done
  ```

Usage is recalculated every 6-12 hours, so the quota frees up on a delay, not instantly.

**What must not be deleted:** the `dev-builds` release, its tag, or its three `.apk` assets. Those
are the product.

## Snapshot at the time of writing

| kind | items | size |
|---|---|---|
| `keyboard-debug-*` (APK bundles) | 41 | **3,834 MB** |
| `smoke-run-*` (evidence) | 38 | 63 MB |
| `build-reports-*` | 2 | ~0 MB |
| **total** | **81** | **3,896 MB** against a 500 MB quota |

98.4% of the problem is the APK bundles, so clearing those alone is sufficient — and no
`keyboard-debug-*` artifact needs to be kept, because every one of them is already on the release.

## Still true on 2026-10-31

Re-measured, and nothing had been cleared: **81 artifacts, 3,896 MB**, none of them expired, all
created 2026-10-21..23. The backlog above is not historical, it is the current state — so the quota
has stayed blown for over a week and every optional upload since has failed silently
(`continue-on-error`, which is also why it looks like nothing is wrong).

What actually broke this time was the *failure-report* upload, triggered by a step that failed on a
`grep` exit code rather than a real error (`docs/BUG-HUNT-2026-10-31.md`, F5). The APK build and the
rolling release were unaffected, exactly as the "trap" section above predicts.

Two changes came out of that run:

- the dispatch-only APK artifact now uses **`retention-days: 1`** instead of 3. It is the one
  upload that is multi-megabyte (94 MB), and it exists only so a manual dispatch yields a
  downloadable artifact — the release is the durable copy. Worst case is one day of dispatches
  (~94 MB each) rather than three.
- the unit-test summary is emitted as **annotations**, never as an artifact, for the same reason:
  whatever fails, the reason must still be readable when the quota is full.
