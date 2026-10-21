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
# Annotation budget matters: GitHub stores ~20 annotations per check run and drops the rest. This
# emits 5, the workflow diagnostics step emits 1-2, and the smoke script emits ~9.
#
# Always exits 0 -- this is an observer, and must never be the reason the job fails.

set -uo pipefail

ann() { printf '::%s::%s\n' "$1" "$(printf '%s' "$2" | tr '\r\n' '  ' | tr -s ' ' | cut -c1-440)"; }

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
EMU="$SDK/emulator/emulator"

# 1. The decisive one: does the emulator itself believe it can be hardware-accelerated?
if [ -x "$EMU" ]; then
  ann notice "pre-launch: accel-check -> $("$EMU" -accel-check 2>&1 | head -3)"
else
  ann error "pre-launch: emulator binary MISSING at $EMU -- SDK provisioning did not complete"
fi

# 2. Was the AVD created, and is the system image present? Distinguishes AVD-creation failure from
#    a launch/boot failure.
ann notice "pre-launch: avd -> $(ls -1 "$HOME/.android/avd" 2>&1 | head -5 | tr '\n' ' ')"
ann notice "pre-launch: system-images -> $(find "$SDK/system-images" -maxdepth 3 -mindepth 3 -type d 2>/dev/null | head -3 | tr '\n' ' ')"

# 3. Host + KVM at the moment of launch (the workflow's earlier check can go stale).
ann notice "pre-launch: host -> $(nproc)vCPU $(free -h | awk '/^Mem:/{print $2}')RAM virt=$(grep -oE 'vmx|svm' /proc/cpuinfo | sort -u | tr '\n' ' ') /dev/kvm=$(stat -c '%A %U:%G' /dev/kvm 2>&1)"

# 4. Confirm the artifact the smoke script will look for is really there, so a missing-APK failure
#    is not mistaken for an emulator failure.
FOUND_APK=$(find build/outputs/apk/debug -name '*x86_64*.apk' 2>/dev/null | head -1)
if [ -n "$FOUND_APK" ]; then
  ann notice "pre-launch: apk -> found $FOUND_APK ($(du -m "$FOUND_APK" | cut -f1) MB)"
else
  ann error "pre-launch: apk -> NO x86_64 APK under build/outputs/apk/debug; the smoke script cannot proceed"
fi

exit 0
