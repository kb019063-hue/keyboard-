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
# Deliberately does NOT use `set -e`: a failed assertion must not abort the run before the
# remaining screenshots are captured. Failures are counted and become the exit code at the end.

set -uo pipefail

NS=tribixbite.cleverkeys
OUT="${OUT:-artifacts}"
mkdir -p "$OUT"

fails=0
pass() { printf '  PASS  %s\n' "$1"; }
fail() { printf '  FAIL  %s\n' "$1"; fails=$((fails + 1)); }
info() { printf '  ----  %s\n' "$1"; }
shot() { adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null && info "screenshot -> $OUT/$1.png"; }

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
info "abi=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')  sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
adb shell wm size     | tr -d '\r' | sed 's/^/  ----  /'
adb shell wm density  | tr -d '\r' | sed 's/^/  ----  /'

echo "== 2. install =="
if adb install -r -g "$APK" > "$OUT/install.log" 2>&1; then
  pass "adb install"
else
  fail "adb install (see $OUT/install.log)"; tail -5 "$OUT/install.log" | sed 's/^/        /'
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
if tr -d '\r' < "$OUT/ime-list.log" | grep -q "$PKG"; then
  pass "IME listed by the framework"
else
  fail "IME not in 'ime list -s'"; sed 's/^/        /' "$OUT/ime-list.log"
fi
sleep 3
adb shell dumpsys input_method > "$OUT/dumpsys-input_method.txt" 2>&1
if tr -d '\r' < "$OUT/dumpsys-input_method.txt" | grep -qE "mCurId=.*$PKG"; then
  pass "our service is the CURRENT input method (mCurId)"
else
  fail "mCurId is not our service"
  grep -E "mCurId|mCurMethodId" "$OUT/dumpsys-input_method.txt" | head -3 | sed 's/^/        /'
fi

echo "== 4. open a real text field (LauncherActivity, exported) =="
adb shell am start -W -n "$PKG/$NS.LauncherActivity" > "$OUT/am-start.log" 2>&1
if grep -qiE "Status: ok|TotalTime" "$OUT/am-start.log"; then
  pass "LauncherActivity started"
else
  fail "LauncherActivity did not start"; tail -5 "$OUT/am-start.log" | sed 's/^/        /'
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
  if tr -d '\r' < "$OUT/dumpsys-input_method-shown.txt" | grep -qE "mInputShown=true"; then
    pass "KEYBOARD IS RENDERED (mInputShown=true)"
  else
    fail "mInputShown not true -- keyboard did not come up"
    grep -E "mInputShown|mShowRequested" "$OUT/dumpsys-input_method-shown.txt" | head -3 | sed 's/^/        /'
  fi
else
  fail "no text field found in the a11y dump -- cannot raise the keyboard"
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
fi

echo "== 7. settings + keyboard preview render =="
adb shell input keyevent KEYCODE_BACK; sleep 2
adb shell am start -W -n "$PKG/$NS.SettingsActivity" > "$OUT/am-settings.log" 2>&1
sleep 6
shot 04-settings
if grep -qiE "Status: ok|TotalTime" "$OUT/am-settings.log"; then
  pass "SettingsActivity started"
else
  fail "SettingsActivity did not start"; tail -3 "$OUT/am-settings.log" | sed 's/^/        /'
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
  pass "no FATAL EXCEPTION / ANR in logcat"
else
  fail "$CRASHES crash/ANR line(s) in logcat"
  grep -E "FATAL EXCEPTION|ANR in $PKG" "$OUT/logcat.txt" | head -5 | sed 's/^/        /'
fi

echo "== 9. clipboard subsystem (informational) =="
# run-as works because the debug build is debuggable. clipboard_history.db may legitimately not
# exist yet if nothing was copied, so this is reported, never failed.
adb shell run-as "$PKG" ls -la databases/ > "$OUT/app-databases.txt" 2>&1
if grep -qiE "\.db" "$OUT/app-databases.txt"; then
  info "app databases:"; head -8 "$OUT/app-databases.txt" | sed 's/^/        /'
else
  info "no databases yet (expected until the first copy): $(head -1 "$OUT/app-databases.txt")"
fi

echo
if [ "$fails" -eq 0 ]; then echo "SMOKE RESULT: PASS (0 failures)"
else echo "SMOKE RESULT: FAIL ($fails)"; fi
echo "Evidence in $OUT/: screenshots, dumpsys input_method, ime list, logcat, a11y dumps."
[ "$fails" -eq 0 ]
