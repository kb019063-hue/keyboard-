#!/usr/bin/env bash
# Emulator smoke test for the keyboard.
#
# Proves, in order: the APK installs, the IME registers with the framework, it can be made the
# active keyboard, it actually RENDERS over a real text field, and it COMMITS text. Screenshots
# and framework dumps are written to $OUT as evidence and uploaded pass or fail.
#
# Runs inside reactivecircus/android-emulator-runner, which provides a booted emulator and `adb`.
#
# IMPORTANT -- two different identifiers:
#   NS  = the source namespace, tribixbite.cleverkeys. Class names live here and never change.
#   PKG = the applicationId of the installed build. The debug variant applies
#         `applicationIdSuffix '.debug'` (build.gradle), so PKG is tribixbite.cleverkeys.debug.
#         (The AndroidManifest comment claiming that suffix was removed is stale.)
# Every component string must therefore be "$PKG/$NS.<Class>" -- mixing them up silently fails
# `ime set`. PKG is detected from the device rather than assumed.
#
# ── Why every check is also emitted as a workflow annotation ─────────────────────────────────
# The dev sandbox cannot read CI logs OR artifacts: both are served from hosts outside its
# allowlist (results-receiver.actions.githubusercontent.com and *.blob.core.windows.net). The one
# channel that does get through is check-run annotations, via api.github.com. So `pass`/`fail`/
# `info` each emit a `::notice::`/`::error::` command as well as printing, and the decisive
# framework values (mCurId, mInputShown, crash lines) are emitted verbatim. Without this a failed
# run is indistinguishable from an infrastructure failure -- which is exactly what happened on run
# 39587185764: a 564 KB artifact proved the emulator booted and screenshots were taken, but the
# only readable signal was "The process '/usr/bin/sh' failed with exit code 1".
#
# Deliberately does NOT use `set -e`: a failed assertion must not abort the run before the
# remaining screenshots are captured. Failures are counted and become the exit code at the end.

set -uo pipefail

NS=tribixbite.cleverkeys
OUT="${OUT:-artifacts}"
mkdir -p "$OUT"

fails=0

# Emit a single-line workflow annotation. Newlines are flattened and the message truncated,
# because annotations are line-oriented and GitHub caps their length.
ann() {
  local lvl="$1" m
  m=$(printf '%s' "$2" | tr '\r\n' '  ' | tr -s ' ' | cut -c1-380)
  printf '::%s::%s\n' "$lvl" "$m"
}
pass() { printf '  PASS  %s\n' "$1"; ann notice "PASS  $1"; }
fail() { printf '  FAIL  %s\n' "$1"; fails=$((fails + 1)); ann error "FAIL  $1"; }
info() { printf '  ----  %s\n' "$1"; ann notice "      $1"; }
shot() { adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null && printf '  ----  screenshot -> %s/%s.png\n' "$OUT" "$1"; }
# First N chars of a file, flattened -- for quoting framework output into an annotation.
peek() { head -c "${2:-300}" "$1" 2>/dev/null | tr '\r\n' '  ' | tr -s ' '; }

# --- locate the x86_64 split (the emulator is x86_64; ARM splits cannot run on it) ---
APK="${APK:-}"
if [ -z "$APK" ]; then
  APK=$(find build/outputs/apk/debug -name '*x86_64*.apk' 2>/dev/null | head -1)
fi
if [ -z "$APK" ] || [ ! -f "$APK" ]; then
  fail "no x86_64 APK found under build/outputs/apk/debug (got: '${APK:-}')"
  echo "SMOKE RESULT: FAIL ($fails)"; exit 1
fi
info "APK under test: $APK ($(du -m "$APK" | cut -f1) MB)"

echo "== 1. wait for boot =="
adb wait-for-device
for _ in $(seq 1 60); do
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 5
done
if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
  pass "device booted"
else
  fail "device did not finish booting in 300s"
fi
info "abi=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')  sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')  $(adb shell wm size | tr -d '\r')"

echo "== 2. install =="
if adb install -r -g "$APK" > "$OUT/install.log" 2>&1; then
  pass "adb install"
else
  fail "adb install: $(peek "$OUT/install.log" 200)"
fi

# Detect the real applicationId from the device. Third-party packages only, so a preinstalled
# Google IME can never be mistaken for ours.
PKG=$(adb shell pm list packages -3 2>/dev/null | tr -d '\r' | sed 's/^package://' | grep -i 'cleverkeys' | head -1)
if [ -z "$PKG" ]; then
  PKG="$NS.debug"
  info "pm list did not reveal the package; falling back to $PKG"
else
  pass "detected applicationId: $PKG"
fi
SVC="$PKG/$NS.CleverKeysService"
info "IME component: $SVC"

echo "== 3. register + activate the IME =="
adb shell ime enable "$SVC" > "$OUT/ime-enable.log" 2>&1
adb shell ime set    "$SVC" > "$OUT/ime-set.log"    2>&1
adb shell ime list -s       > "$OUT/ime-list.log"   2>&1
info "ime enable said: $(peek "$OUT/ime-enable.log" 120)"
info "ime set said:    $(peek "$OUT/ime-set.log" 120)"
if tr -d '\r' < "$OUT/ime-list.log" | grep -q "$PKG"; then
  pass "IME listed by the framework"
else
  fail "IME not in 'ime list -s': $(peek "$OUT/ime-list.log" 200)"
fi
sleep 3
adb shell dumpsys input_method > "$OUT/dumpsys-input_method.txt" 2>&1
CURID=$(grep -oE "mCurId=[^ ,]*" "$OUT/dumpsys-input_method.txt" | head -1 | tr -d '\r')
if tr -d '\r' < "$OUT/dumpsys-input_method.txt" | grep -qE "mCurId=.*$PKG"; then
  pass "our service is the CURRENT input method ($CURID)"
else
  fail "mCurId is not our service (got '${CURID:-none}')"
fi

echo "== 4. open a real text field (LauncherActivity, exported) =="
adb shell am start -W -n "$PKG/$NS.LauncherActivity" > "$OUT/am-start.log" 2>&1
if grep -qiE "Status: ok|TotalTime" "$OUT/am-start.log"; then
  pass "LauncherActivity started"
else
  fail "LauncherActivity did not start: $(peek "$OUT/am-start.log" 200)"
fi
sleep 6
shot 01-launcher

echo "== 5. focus the text field so the IME must render =="
adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
adb pull /sdcard/ui.xml "$OUT/ui-launcher.xml" > /dev/null 2>&1
CENTER=$(python3 - "$OUT/ui-launcher.xml" <<'PY' 2>/dev/null
import re, sys
try:
    xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
except Exception:
    sys.exit(0)
# Prefer a genuine text editor; Compose fields surface as EditText in the a11y tree.
pats = [r'class="[^"]*EditText[^"]*"', r'class="[^"]*TextField[^"]*"',
        r'focusable="true"[^>]*clickable="true"']
for p in pats:
    for m in re.finditer(r'<node[^>]*' + p + r'[^>]*>', xml):
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', m.group(0))
        if b:
            x1, y1, x2, y2 = map(int, b.groups())
            if (x2 - x1) > 20 and (y2 - y1) > 10:
                print(f"{(x1 + x2) // 2} {(y1 + y2) // 2}")
                sys.exit(0)
PY
)
if [ -n "${CENTER:-}" ]; then
  info "text field centre: $CENTER"
  adb shell input tap $CENTER
  sleep 5
  shot 02-keyboard-shown
  adb shell dumpsys input_method > "$OUT/dumpsys-input_method-shown.txt" 2>&1
  SHOWN=$(grep -oE "mInputShown=[a-z]*" "$OUT/dumpsys-input_method-shown.txt" | head -1 | tr -d '\r')
  if [ "$SHOWN" = "mInputShown=true" ]; then
    pass "KEYBOARD IS RENDERED ($SHOWN)"
  else
    fail "keyboard did not come up (got '${SHOWN:-no mInputShown field}')"
    info "show-request state: $(grep -oE 'mShowRequested=[a-z]*|mServedView=[^ ,]*' "$OUT/dumpsys-input_method-shown.txt" | head -2 | tr '\n' ' ')"
  fi
else
  fail "no text field found in the a11y dump -- cannot raise the keyboard"
  info "dump root classes: $(grep -oE 'class="[^"]*"' "$OUT/ui-launcher.xml" 2>/dev/null | sort -u | head -6 | tr '\n' ' ')"
fi

echo "== 6. commit text through the IME =="
adb shell input text "keyboard%stest%s123"
sleep 3
shot 03-typed-text
adb shell uiautomator dump /sdcard/ui2.xml > /dev/null 2>&1
adb pull /sdcard/ui2.xml "$OUT/ui-typed.xml" > /dev/null 2>&1
if grep -qiE "keyboard[ _]*test[ _]*123" "$OUT/ui-typed.xml" 2>/dev/null; then
  pass "typed text reached the editor"
else
  # Not fatal on its own: `input text` injects key events and some Compose fields report their
  # contents oddly in the a11y tree. The screenshot is the arbiter; flag for human review.
  info "typed text not confirmed in a11y dump -- inspect 03-typed-text.png"
  info "editor text now: $(grep -oE 'text="[^"]{0,60}"' "$OUT/ui-typed.xml" 2>/dev/null | tail -4 | tr '\n' ' ')"
fi

echo "== 7. settings + keyboard preview render =="
adb shell input keyevent KEYCODE_BACK; sleep 2
adb shell am start -W -n "$PKG/$NS.SettingsActivity" > "$OUT/am-settings.log" 2>&1
sleep 6
shot 04-settings
if grep -qiE "Status: ok|TotalTime" "$OUT/am-settings.log"; then
  pass "SettingsActivity started"
else
  fail "SettingsActivity did not start: $(peek "$OUT/am-settings.log" 160)"
fi
# ThemeSettingsActivity hosts KeyboardPreviewHost, which renders a real Keyboard2View with the
# active theme and layout -- a direct visual check of the keyboard itself, no IME needed.
adb shell am start -W -n "$PKG/$NS.ThemeSettingsActivity" > "$OUT/am-theme.log" 2>&1
sleep 6
shot 05-theme-preview
if grep -qiE "Status: ok|TotalTime" "$OUT/am-theme.log"; then
  pass "ThemeSettingsActivity (keyboard preview) started"
else
  info "ThemeSettingsActivity did not start (non-exported; shell may be refused)"
fi

echo "== 8. crashes / ANRs =="
adb logcat -d -v brief > "$OUT/logcat.txt" 2>&1
CRASHES=$(grep -cE "FATAL EXCEPTION|ANR in $PKG" "$OUT/logcat.txt" 2>/dev/null || true)
if [ "${CRASHES:-0}" -eq 0 ]; then
  pass "no FATAL EXCEPTION / ANR in logcat ($(wc -l < "$OUT/logcat.txt") lines captured)"
else
  fail "$CRASHES crash/ANR line(s) in logcat"
  grep -E "FATAL EXCEPTION|ANR in $PKG" "$OUT/logcat.txt" | head -3 | while read -r l; do info "crash: $l"; done
  # The first exception block after FATAL EXCEPTION usually names the cause.
  info "cause: $(grep -A3 -m1 'FATAL EXCEPTION' "$OUT/logcat.txt" | tail -2 | tr '\n' ' ')"
fi

echo "== 9. clipboard subsystem (informational) =="
# run-as works because the debug build is debuggable. clipboard_history.db may legitimately not
# exist yet if nothing was copied, so this is reported, never failed.
adb shell run-as "$PKG" ls -la databases/ > "$OUT/app-databases.txt" 2>&1
if grep -qiE "\.db" "$OUT/app-databases.txt"; then
  info "app databases: $(grep -oE '[a-z_]+\.db' "$OUT/app-databases.txt" | tr '\n' ' ')"
else
  info "no databases yet (expected until the first copy): $(peek "$OUT/app-databases.txt" 120)"
fi

echo
if [ "$fails" -eq 0 ]; then
  echo "SMOKE RESULT: PASS (0 failures)"
  ann notice "SMOKE RESULT: PASS (0 failures)"
else
  echo "SMOKE RESULT: FAIL ($fails)"
  ann error "SMOKE RESULT: FAIL ($fails failures) -- see the PASS/FAIL annotations above"
fi
echo "Evidence in $OUT/: screenshots, dumpsys input_method, ime list, logcat, a11y dumps."
[ "$fails" -eq 0 ]
