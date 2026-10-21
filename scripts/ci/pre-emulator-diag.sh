#!/usr/bin/env bash
# Pre-launch diagnostics, run by android-emulator-runner AFTER it provisions the SDK but BEFORE it
# starts the emulator.
#
# This is the only point in the pipeline where the emulator binary actually exists. The workflow's
# own diagnostics step necessarily runs earlier, so on every run so far its `emulator -accel-check`
# reported "/usr/local/lib/android/sdk/emulator/emulator: No such file or directory" -- the single
# most useful probe, silently useless each time.
#
# Run 39593756330 failed inside the emulator step having produced a 1,272-byte artifact (diagnostics
# only, no screenshots), which proves scripts/ci/emulator-smoke.sh never ran: the failure was in
# SDK provisioning, AVD creation or emulator launch. The action surfaces that only as a generic
# "The process '/usr/bin/sh' failed with exit code 1", and CI logs are unreadable from the dev
# sandbox, so this script exists to pin down which of the three it was.
#
# Annotation budget matters, and it is tighter than it looks. Run 39608945187 stored 13
# annotations -- 12 notices in emission order, stopping exactly at section 5's PASS -- and every
# notice the script emitted after that point was silently dropped, including the toolbar verdict
# this whole exercise exists to produce. So the healthy path here prints to stdout and does NOT
# annotate: these probes have already served their purpose (accel-check answered "KVM installed and
# usable" on two consecutive runs), and they are only decisive when something is wrong. Only the
# two genuinely broken states still annotate, as errors, which survive where notices do not.
#
# Always exits 0 -- this is an observer, and must never be the reason the job fails.

set -uo pipefail

ann() { printf '::%s::%s\n' "$1" "$(printf '%s' "$2" | tr '\r\n' '  ' | tr -s ' ' | cut -c1-440)"; }
# stdout only -- see the annotation-budget note above.
say() { printf '  ---- pre-launch: %s\n' "$1"; }

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
EMU="$SDK/emulator/emulator"

# 1. The decisive one: does the emulator itself believe it can be hardware-accelerated?
if [ -x "$EMU" ]; then
  say "accel-check -> $("$EMU" -accel-check 2>&1 | head -3)"
else
  ann error "pre-launch: emulator binary MISSING at $EMU -- SDK provisioning did not complete"
fi

# 2. Was the AVD created, and is the system image present? Distinguishes AVD-creation failure from
#    a launch/boot failure.
say "avd -> $(ls -1 "$HOME/.android/avd" 2>&1 | head -5 | tr '\n' ' ')"
say "system-images -> $(find "$SDK/system-images" -maxdepth 3 -mindepth 3 -type d 2>/dev/null | head -3 | tr '\n' ' ')"

# 3. Host + KVM at the moment of launch (the workflow's earlier check can go stale).
say "host -> $(nproc)vCPU $(free -h | awk '/^Mem:/{print $2}')RAM virt=$(grep -oE 'vmx|svm' /proc/cpuinfo | sort -u | tr '\n' ' ') /dev/kvm=$(stat -c '%A %U:%G' /dev/kvm 2>&1)"

# 4. Confirm the artifact the smoke script will look for is really there, so a missing-APK failure
#    is not mistaken for an emulator failure.
FOUND_APK=$(find build/outputs/apk/debug -name '*x86_64*.apk' 2>/dev/null | head -1)
if [ -n "$FOUND_APK" ]; then
  say "apk -> found $FOUND_APK ($(du -m "$FOUND_APK" | cut -f1) MB)"
else
  ann error "pre-launch: NO x86_64 APK under build/outputs/apk/debug; the smoke script cannot proceed"
fi

exit 0
