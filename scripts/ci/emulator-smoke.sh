#!/usr/bin/env bash
# Emulator smoke test for the keyboard.
#
# Proves, in order: the APK installs, the IME registers and can be selected, the launcher screen
# really is foreground, no crash occurred, a text field is reachable, the keyboard RENDERS over it
# and is the bound IME, and text is committed. Screenshots and framework dumps go to $OUT.
#
# Runs inside reactivecircus/android-emulator-runner, which provides a booted emulator and `adb`.
#
# ── Two identifiers, never conflated ─────────────────────────────────────────────────────────
#   NS  = source namespace, tribixbite.cleverkeys. Class names live here and never change.
#   PKG = applicationId of the installed build. The debug variant applies
#         `applicationIdSuffix '.debug'`, so PKG is tribixbite.cleverkeys.debug. (The
#         AndroidManifest comment claiming that suffix was removed is stale.)
# Component strings are therefore "$PKG/$NS.<Class>". PKG is detected from the device.
#
# ── Two hard-won constraints on how this script reports ──────────────────────────────────────
# 1. The dev sandbox can read neither CI logs (results-receiver.actions.githubusercontent.com) nor
#    artifacts (*.blob.core.windows.net) -- both outside its allowlist. Check-run annotations via
#    api.github.com are the ONLY channel that gets through.
# 2. GitHub stores roughly 20 annotations per check run and silently drops the rest. An earlier
#    revision emitted ~30 notices; everything from section 6 onward vanished, including the
#    diagnostics that mattered. So `info` prints to stdout WITHOUT annotating, each check emits at
#    most one annotation, and every failure message is dense -- it carries the decisive framework
#    values inline, because failures are preserved where notices are dropped.
#
# ── What "selected" vs "bound" means, and why it matters ─────────────────────────────────────
# `settings get secure default_input_method` is the authoritative record of WHICH IME the user has
# chosen. `dumpsys input_method`'s mCurId is the IME currently BOUND, and binding only happens once
# an editor takes focus. Asserting mCurId before any text field is focused is wrong: it still shows
# whatever was last bound (on a fresh AOSP image, com.android.inputmethod.latin/.LatinIME), even
# though `ime set` succeeded. Run 39591096842 failed on exactly that. So: check the secure setting
# up front, and check mCurId AFTER focusing a field, where it is meaningful.
#
# Deliberately no `set -e`: a failed assertion must not abort before the remaining screenshots are
# captured. Failures are counted and become the exit code.

set -uo pipefail

NS=tribixbite.cleverkeys
OUT="${OUT:-artifacts}"
mkdir -p "$OUT"

fails=0

# --- verdict trackers ---------------------------------------------------------------------------
# Annotation storage has proved both CAPPED (run 39608945187 kept 12 notices in emission order and
# silently dropped everything after section 5) and occasionally LOST outright (run 39610690353
# stored zero annotations and its job conclusion never finalised, though every step succeeded).
# Artifact NAMES, unlike artifact contents, are readable from api.github.com -- so the verdict also
# travels there, via the workflow's "Derive artifact name" step. Written from an EXIT trap so an
# early bail still leaves a summary behind; verified that the trap does not alter the exit status.
TB_VERDICT=notrun
TBUI_VERDICT=notrun
OH_VERDICT=notrun
NF_VERDICT=notrun
KB_VERDICT=notrun
ANR_SEEN=0
VOICE_VERDICT=notrun
# The voice field is spelled `-v=` and appended LAST on purpose: the workflow truncates the summary
# to 90 characters before it becomes the artifact name, and the run-36 summary is already 80. A
# longer spelling would push `anr=` past the cut and silently lose the ANR witness.
write_summary() {
  printf 'fails=%s-toolbar=%s-tbui=%s-oh=%s-nf=%s-keyboard=%s-anr=%s-v=%s\n' \
    "${fails:-?}" "${TB_VERDICT:-?}" "${TBUI_VERDICT:-?}" "${OH_VERDICT:-?}" \
    "${NF_VERDICT:-?}" "${KB_VERDICT:-?}" "${ANR_SEEN:-?}" "${VOICE_VERDICT:-?}" \
    > "$OUT/summary.txt" 2>/dev/null || true
}
trap write_summary EXIT

ann() {  # single-line workflow annotation; flattened and truncated to stay within GitHub's limits
  local lvl="$1" m
  m=$(printf '%s' "$2" | tr '\r\n' '  ' | tr -s ' ' | cut -c1-460)
  printf '::%s::%s\n' "$lvl" "$m"
}
pass() { printf '  PASS  %s\n' "$1"; ann notice "PASS  $1"; }
fail() { printf '  FAIL  %s\n' "$1"; fails=$((fails + 1)); ann error "FAIL  $1"; }
info() { printf '  ----  %s\n' "$1"; }          # stdout only -- NOT annotated (see constraint 2)
shot() { adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null && printf '  ----  shot %s.png\n' "$1"; }
peek() { head -c "${2:-300}" "$1" 2>/dev/null | tr '\r\n' '  ' | tr -s ' '; }

# Framework probes, each a single flattened value suitable for embedding in an annotation.
selime()  { adb shell settings get secure default_input_method 2>/dev/null | tr -d '\r'; }
curime()  { adb shell dumpsys input_method 2>/dev/null | grep -oE 'mCurId=[^ ,]*' | head -1 | tr -d '\r'; }
shown()   { adb shell dumpsys input_method 2>/dev/null | grep -oE 'mInputShown=[a-z]*' | head -1 | tr -d '\r'; }
focus()   { adb shell dumpsys window 2>/dev/null | grep -oE 'mCurrentFocus=Window\{[^}]*\}' | head -1 | tr -d '\r'; }
crashln() { adb logcat -d -b crash -v brief 2>/dev/null | grep -vE '^[[:space:]]*$' | head -4 | tr '\n' ' ' | tr -d '\r'; }
dispsz()  { adb shell wm size 2>/dev/null | tr -d '\r' | grep -oE '[0-9]+x[0-9]+' | tail -1; }

# Which process stopped responding. THE decisive probe for an ANR dialog: it distinguishes "the
# emulator's launcher is slow" (system flakiness, 2 vCPU + software GL) from "our IME hung the
# host app's main thread", which is the one way our own code could legitimately cause it -- an
# IME runs inside every process that shows a text field, and the launcher has a search field.
anrln()   { adb logcat -d -b events -v brief 2>/dev/null | grep -iE 'am_anr|am_kill' | tail -3 | tr '\n' ' ' | tr -d '\r'; }

# The package named in the most recent am_anr event, e.g. com.android.launcher3. am_anr lines look
# like "[0,<pid>,<process>,<flags>,<reason>]", so field 3 after stripping the bracket is the
# process name. Empty when the events buffer holds no am_anr (a dialog can outlive its event).
anr_pkg() { adb logcat -d -b events -v brief 2>/dev/null | grep -iE 'am_anr' | tail -1 \
            | grep -oE '\[[0-9]+,[0-9]+,[A-Za-z0-9_.]+' | head -1 | cut -d, -f3 | tr -d '\r'; }

# The subject line of the ANR dialog on screen, e.g. "Quickstep isn't responding". The dialog names
# the app by its LABEL, which is the one witness that survives when the events buffer holds no
# am_anr -- run 39755499504 wedged exactly that way: am_kill lines only, so an am_anr-based trigger
# never fired and the launcher was left holding "Wait" twenty-one times.
anr_subject() { grep -oE 'text="[^"]*responding[^"]*"' "$1" 2>/dev/null | head -1 | sed 's/^text="//; s/"$//'; }

# Center of the first node in $1 whose text/content-desc matches regex $2; empty if none.
nodecenter() {
  python3 - "$1" "$2" <<'PY' 2>/dev/null
import re, sys
try:
    xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
except Exception:
    sys.exit(0)
pat = re.compile(sys.argv[2], re.I)
for m in re.finditer(r'<node[^>]*>', xml):
    tag = m.group(0)
    # Real dumps emit every attribute, usually with empty values, so joining them can leave a
    # trailing space that would defeat an anchored pattern like ^wait$.
    label = " ".join(re.findall(r'(?:text|content-desc)="([^"]*)"', tag)).strip()
    if not label or not pat.search(label):
        continue
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
    if b:
        x1, y1, x2, y2 = map(int, b.groups())
        if (x2 - x1) > 10 and (y2 - y1) > 10:
            print(f"{(x1 + x2) // 2} {(y1 + y2) // 2}")
            sys.exit(0)
PY
}

# Center of the first node whose CONTENT-DESC alone matches $2. 7e needs this because two different
# things on screen call themselves "Voice Typing": the settings dropdown row added in phase 4 (a
# TextView whose label is its text) and the toolbar's mic key (an ImageButton whose label is its
# content-desc). nodecenter() joins text and content-desc into one label and returns the first hit
# in document order, so with the settings list composed above the keyboard it would tap the row and
# never the key. Matching content-desc alone tells them apart, because only the key carries one.
nodecenter_desc() {
  python3 - "$1" "$2" <<'PY' 2>/dev/null
import re, sys
try:
    xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
except Exception:
    sys.exit(0)
pat = re.compile(sys.argv[2], re.I)
for m in re.finditer(r'<node[^>]*>', xml):
    tag = m.group(0)
    d = re.search(r'content-desc="([^"]*)"', tag)
    if not d or not pat.search(d.group(1).strip()):
        continue
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
    if b:
        x1, y1, x2, y2 = map(int, b.groups())
        if (x2 - x1) > 10 and (y2 - y1) > 10:
            print(f"{(x1 + x2) // 2} {(y1 + y2) // 2}")
            sys.exit(0)
PY
}

# An "Application Not Responding" dialog sits above every window, so ONE transient ANR fails
# every downstream UI assertion for a reason unrelated to what they test -- run 39606860203 lost
# three checks that way. Dismiss it with "Wait" (keeps the app alive, unlike "Close app") and
# let the real assertions run. Returns 0 if a dialog was dismissed, 1 if none was present.
# The dialog does NOT always take mCurrentFocus. Run 39684027086 had "Quickstep isn't responding"
# with Wait/Close app on screen while focus still reported the launcher window, so the focus-only
# gate above let one dialog sink four unrelated assertions. The accessibility dump is the reliable
# witness, so take one here and accept either signal: focus, or an "…responding" line alongside a
# Wait button (both required, so an ordinary screen mentioning the word cannot trigger a dismissal).
dismiss_anr() {
  local f="$OUT/ui-anr.xml" c hit=""
  adb shell uiautomator dump /sdcard/ui-anr.xml > /dev/null 2>&1
  adb pull /sdcard/ui-anr.xml "$f" > /dev/null 2>&1
  case "$(focus)" in *"Application Not Responding"*) hit=focus ;; esac
  if [ -z "$hit" ] && grep -qiE "responding" "$f" 2>/dev/null; then
    [ -n "$(nodecenter "$f" 'wait')" ] && hit=dump
  fi
  [ -n "$hit" ] || return 1
  ANR_SEEN=$((ANR_SEEN + 1))
  info "ANR dialog on screen (detected via $hit) -- anr events: $(anrln)"

  # Whose ANR is it? "Wait" is the right answer for OUR app (it keeps the process alive so the
  # real assertions can run) and the WRONG answer for a wedged system process: run 39753071748
  # had Quickstep -- the emulator's own launcher -- ANRing, and tapping Wait left it wedged, so
  # it re-ANRed 21 times and every assertion downstream failed against a launcher that never
  # came back. For any package that is not ours, close it instead: the launcher restarts by
  # itself, the device recovers, and the checks that follow measure our keyboard again.
  # Two independent witnesses say "this ANR is not ours". (1) The events buffer names a foreign
  # process in its latest am_anr. (2) The dialog's subject line does not carry our brand: the
  # label is "Mr. Brown's Keyboard" in every locale -- the brand was deliberately never
  # translated -- so a subject without "Brown" is somebody else's dialog. Witness 2 matters
  # because the events buffer can hold no am_anr at all (run 39755499504), and witness 1 matters
  # because it yields the package name needed to force-stop.
  local apkg subj foreign=""
  apkg=$(anr_pkg); subj=$(anr_subject "$f")
  if [ -n "${PKG:-}" ]; then
    if [ -n "${apkg:-}" ] && [ "${apkg#"$PKG"}" = "$apkg" ]; then
      foreign="$apkg"
    elif [ -n "$subj" ] && [ "${subj#*Brown}" = "$subj" ]; then
      foreign="$subj"
    fi
  fi
  if [ -n "$foreign" ]; then
    info "ANR belongs to $foreign, not $PKG -- closing it so the device recovers"
    c=$(nodecenter "$f" '^close app$'); [ -n "${c:-}" ] || c=$(nodecenter "$f" 'close app')
    if [ -n "${c:-}" ]; then
      info "tapping 'Close app' at $c"
      adb shell input tap $c
    else
      info "no 'Close app' button found; sending BACK"
      adb shell input keyevent KEYCODE_BACK
    fi
    # force-stop only when a package name is actually known; a label alone cannot be stopped,
    # and the Close-app tap has already done the recovery in that case.
    if [ -n "${apkg:-}" ] && [ "${apkg#"$PKG"}" = "$apkg" ]; then
      adb shell am force-stop "$apkg" > /dev/null 2>&1
    fi
    sleep 4
    return 0
  fi

  c=$(nodecenter "$f" '^wait$')
  [ -n "${c:-}" ] || c=$(nodecenter "$f" 'wait')
  if [ -n "${c:-}" ]; then
    info "tapping 'Wait' at $c"
    adb shell input tap $c
  else
    info "no 'Wait' button found; sending BACK"
    adb shell input keyevent KEYCODE_BACK
  fi
  sleep 4
  return 0
}

# --- locate the x86_64 split (the emulator is x86_64; ARM splits cannot run on it) ---
APK="${APK:-}"
[ -z "$APK" ] && APK=$(find build/outputs/apk/debug -name '*x86_64*.apk' 2>/dev/null | head -1)
if [ -z "$APK" ] || [ ! -f "$APK" ]; then
  fail "no x86_64 APK found under build/outputs/apk/debug (got '${APK:-}')"; exit 1
fi
info "APK: $APK ($(du -m "$APK" | cut -f1) MB)"

echo "== 1. boot =="
adb wait-for-device
for _ in $(seq 1 60); do
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 5
done
BOOTED=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
if [ "$BOOTED" = "1" ]; then
  pass "booted: abi=$(adb shell getprop ro.product.cpu.abi | tr -d '\r') sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r') screen=$(dispsz)"
else
  fail "device did not finish booting in 300s (sys.boot_completed='$BOOTED')"
fi
# Clear both buffers so the crash checks below see only OUR app, not boot noise.
adb logcat -c > /dev/null 2>&1
adb logcat -c -b crash > /dev/null 2>&1
adb logcat -c -b events > /dev/null 2>&1

echo "== 2. install =="
if adb install -r -g "$APK" > "$OUT/install.log" 2>&1; then
  # Detect the real applicationId. Third-party packages only, so a preinstalled Google IME can
  # never be mistaken for ours.
  PKG=$(adb shell pm list packages -3 2>/dev/null | tr -d '\r' | sed 's/^package://' | grep -i cleverkeys | head -1)
  [ -z "$PKG" ] && PKG="$NS.debug"
  pass "installed, applicationId=$PKG"
else
  fail "adb install: $(peek "$OUT/install.log" 200)"
  PKG="$NS.debug"
fi
SVC="$PKG/$NS.CleverKeysService"
info "IME component: $SVC"

echo "== 3. enable + select the IME =="
adb shell ime enable "$SVC" > "$OUT/ime-enable.log" 2>&1
adb shell ime list -s > "$OUT/ime-list.log" 2>&1
SEL=""
for try in 1 2 3; do
  adb shell ime set "$SVC" > "$OUT/ime-set.log" 2>&1
  sleep 4
  SEL=$(selime)
  case "$SEL" in *"$PKG"*) break ;; esac
  # Fallback: shell holds WRITE_SECURE_SETTINGS, so write the setting directly.
  adb shell settings put secure default_input_method "$SVC" > /dev/null 2>&1
  sleep 2
  SEL=$(selime)
  case "$SEL" in *"$PKG"*) break ;; esac
  info "attempt $try: secure setting still '$SEL'"
done
if tr -d '\r' < "$OUT/ime-list.log" | grep -q "$PKG"; then LISTED=yes; else LISTED=NO; fi
case "$SEL" in
  *"$PKG"*) pass "IME selected: listed=$LISTED secure=$SEL" ;;
  *)        fail "IME not selected after 3 attempts: listed=$LISTED secure='$SEL' enable='$(peek "$OUT/ime-enable.log" 90)' set='$(peek "$OUT/ime-set.log" 90)'" ;;
esac
# mCurId is only meaningful once an editor is focused -- reported, not asserted (see header).
info "mCurId before any focus (expected to still be the stock IME): $(curime)"

echo "== 4. launcher screen is really foreground, and did not crash =="
adb shell am start -W -n "$PKG/$NS.LauncherActivity" > "$OUT/am-start.log" 2>&1
sleep 6
shot 01-launcher
dismiss_anr || true
FOCUS=$(focus)
# Dismissing the dialog is not enough on its own: an ANR covering the launch swallows it, so the
# activity has to be started again. This is the retry run 39684027086 never got.
if ! printf '%s' "$FOCUS" | grep -q "LauncherActivity" && dismiss_anr; then
  info "ANR dialog dismissed -- relaunching LauncherActivity"
  adb shell am start -W -n "$PKG/$NS.LauncherActivity" > "$OUT/am-start-retry.log" 2>&1
  sleep 6
  shot 01b-launcher-retry
  FOCUS=$(focus)
fi
CRASH=$(crashln)
if [ -n "$CRASH" ]; then
  fail "CRASH after launching LauncherActivity: $CRASH"
elif printf '%s' "$FOCUS" | grep -q "LauncherActivity"; then
  pass "LauncherActivity foreground: $FOCUS"
else
  fail "LauncherActivity not foreground: focus='$FOCUS' anr='$(anrln)' am='$(peek "$OUT/am-start.log" 120)'"
fi

echo "== 5. reach a text field, then prove the keyboard renders and is bound =="
# LauncherActivity stacks three SetupCards ABOVE its OutlinedTextField
# (activities/LauncherActivity.kt ~470-505), so on a short viewport the field is below the fold and
# never reaches the a11y tree. Run 39588742284 failed on exactly that at 320x640. Scroll and
# re-dump rather than assuming first-load visibility.
SCREEN=$(dispsz); SW=${SCREEN%%x*}; SH=${SCREEN##*x}
case "$SW$SH" in (*[!0-9]*|"") SW=1080; SH=2400 ;; esac
info "display ${SW}x${SH}"

CENTER=""; ATTEMPTS=0
for attempt in 1 2 3 4; do
  ATTEMPTS=$attempt
  dismiss_anr || true
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  adb pull /sdcard/ui.xml "$OUT/ui-launcher-$attempt.xml" > /dev/null 2>&1
  cp -f "$OUT/ui-launcher-$attempt.xml" "$OUT/ui-launcher.xml" 2>/dev/null || true
  CENTER=$(python3 - "$OUT/ui-launcher-$attempt.xml" <<'PY' 2>/dev/null
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
  [ -n "${CENTER:-}" ] && { info "field found on attempt $attempt at $CENTER"; break; }
  info "attempt $attempt: no field, scrolling"
  adb shell input swipe $((SW / 2)) $((SH * 3 / 4)) $((SW / 2)) $((SH / 4)) 300
  sleep 3
done

if [ -z "${CENTER:-}" ]; then
  # One dense error: what was on screen, and whether anything crashed. Classes and visible strings
  # distinguish "wrong screen" from "field exists but Compose hid it from the a11y tree".
  fail "no text field after $ATTEMPTS dump/scroll attempts: screen=${SW}x${SH} focus='$FOCUS' crash='${CRASH:-none}' anr='$(anrln)' classes=[$(grep -ohE 'class="[^"]*"' "$OUT"/ui-launcher-*.xml 2>/dev/null | sort -u | head -6 | tr '\n' ' ')] text=[$(grep -ohE 'text="[^"]{1,30}"' "$OUT"/ui-launcher-*.xml 2>/dev/null | sort -u | head -6 | tr '\n' ' ')]"
else
  adb shell input tap $CENTER
  sleep 6
  shot 02-keyboard-shown
  adb shell dumpsys input_method > "$OUT/dumpsys-input_method-shown.txt" 2>&1
  SHOWN=$(grep -oE 'mInputShown=[a-z]*' "$OUT/dumpsys-input_method-shown.txt" | head -1 | tr -d '\r')
  dismiss_anr || true
  CUR=$(curime); FOCUS2=$(focus); CRASH2=$(crashln)
  # Now mCurId IS meaningful: an editor has focus, so the IME has been bound.
  if [ "$SHOWN" = "mInputShown=true" ] && printf '%s' "$CUR" | grep -q "$PKG"; then
    KB_VERDICT=bound
    pass "KEYBOARD RENDERED AND BOUND: $SHOWN $CUR"
  else
    KB_VERDICT=notup
    fail "keyboard not up: shown='${SHOWN:-none}' ${CUR:-mCurId=none} focus='$FOCUS2' crash='${CRASH2:-none}' anr='$(anrln)' tap=$CENTER"
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
    # `input text` injects key events and some Compose fields report contents oddly in the a11y
    # tree, so this is reported rather than failed -- the screenshot is the arbiter.
    info "typed text unconfirmed in a11y dump: $(grep -ohE 'text="[^"]{0,50}"' "$OUT/ui-typed.xml" 2>/dev/null | tail -3 | tr '\n' ' ')"
  fi

  echo "== 6b. toolbar row rendered (HeliBoard gap analysis phase 1) =="
  # uiautomator frequently dumps only the focused APPLICATION window and omits the IME window, so
  # "toolbar absent" is a real failure only when the dump proves the IME WAS captured. That proof
  # is the Keyboard2View class name -- grepping the package name would prove nothing, because the
  # app under test is that same package and appears in the dump either way.
  adb shell uiautomator dump /sdcard/ui-toolbar.xml > /dev/null 2>&1
  adb pull /sdcard/ui-toolbar.xml "$OUT/ui-toolbar.xml" > /dev/null 2>&1
  TB=$(grep -c "keyboard_toolbar" "$OUT/ui-toolbar.xml" 2>/dev/null) || TB=0
  IME=$(grep -c "Keyboard2View" "$OUT/ui-toolbar.xml" 2>/dev/null) || IME=0
  LABELS=$(grep -ohE 'content-desc="[^"]{1,30}"' "$OUT/ui-toolbar.xml" 2>/dev/null | grep -icE 'copy|paste|undo|redo|settings|voice|clipboard|select') || LABELS=0
  if [ "${TB:-0}" -gt 0 ]; then
    TB_VERDICT=rendered-${TB}
    pass "toolbar rendered: resource-id keyboard_toolbar in the a11y tree, $LABELS command labels"
  elif [ "${IME:-0}" -gt 0 ]; then
    TB_VERDICT=missing
    fail "toolbar MISSING although the IME window WAS in the dump: keyboard_toolbar=$TB Keyboard2View=$IME labels=$LABELS ids=$(grep -ohE 'resource-id="[^"]{0,60}"' "$OUT/ui-toolbar.xml" 2>/dev/null | sort -u | head -6 | tr '\n' ' ')"
  else
    TB_VERDICT=unverifiable
    info "toolbar unverifiable by dump: uiautomator captured no IME window (Keyboard2View absent), so absence proves nothing -- screenshot 03-typed-text is the arbiter"
  fi
fi

echo "== 7. settings + keyboard preview render =="
adb shell input keyevent KEYCODE_BACK; sleep 2
adb shell am start -W -n "$PKG/$NS.SettingsActivity" > "$OUT/am-settings.log" 2>&1
sleep 6
shot 04-settings
dismiss_anr || true
FOCUS3=$(focus)
if printf '%s' "$FOCUS3" | grep -q "SettingsActivity"; then
  pass "SettingsActivity foreground"
else
  fail "SettingsActivity not foreground: focus='$FOCUS3' anr='$(anrln)' am='$(peek "$OUT/am-settings.log" 120)'"
fi
# 7b. The toolbar settings screen (gap analysis phase 1). Unlike 6b above this is ASSERTABLE, not
# merely reported: SettingsActivity is an ordinary app window, so uiautomator captures it. Three
# escalating checks, each failing with its own layer named -- the section header renders, expanding
# it composes the switch (so toolbarSectionExpanded and both state vars are genuinely wired), and
# the picker opens over CommandRegistry (so ALL_COMMANDS renders rather than an empty dialog).
echo "== 7b. toolbar settings screen (phase 1 UI) =="
scrollup() { adb shell input swipe $((SW / 2)) $((SH * 3 / 4)) $((SW / 2)) $((SH / 4)) 300; sleep 2; }
dumpui() {  # $1 = local filename; fresh uiautomator dump each call
  adb shell uiautomator dump /sdcard/ui-tb.xml > /dev/null 2>&1
  adb pull /sdcard/ui-tb.xml "$1" > /dev/null 2>&1
}
texts() { grep -ohE 'text="[^"]{1,28}"' "$@" 2>/dev/null | sort -u | head -8 | tr '\n' ' '; }

HDR=""
for attempt in 1 2 3 4 5 6; do
  F="$OUT/ui-tb-hdr-$attempt.xml"; dumpui "$F"
  # 'text="…Toolbar' rather than a bare 'Toolbar': the only other string containing the word is
  # cmd_show_text_menu_desc, a command DESCRIPTION that appears solely inside the picker dialog.
  # Note the dump escapes the header's emoji as a numeric entity (&#129520;), which this pattern
  # tolerates by matching the word rather than the glyph.
  # An ANR dialog over the settings screen would otherwise burn all six attempts looking like a
  # missing header, which is what run 39684027086 reported.
  if grep -qiE "responding" "$F" 2>/dev/null; then
    info "attempt $attempt: ANR dialog over the settings screen -- dismissing"
    dismiss_anr || true
    continue
  fi
  if grep -qE 'text="[^"]*Toolbar' "$F" 2>/dev/null; then HDR="$F"; break; fi
  info "attempt $attempt: no toolbar header on screen, scrolling"
  scrollup
done

if [ -z "$HDR" ]; then
  TBUI_VERDICT=no-header
  fail "toolbar section header never appeared after 6 dump/scroll attempts: texts=[$(texts "$OUT"/ui-tb-hdr-*.xml)]"
else
  pass "toolbar section header present in the settings list"
  # Tap the header only once it sits in a safe vertical band. Run 39681883670 found the header at
  # the bottom of the list and tapped y=2195 of a 2400px screen -- inside the gesture nav bar, so
  # the section never expanded and the check failed for a reason that has nothing to do with the
  # code under test. Nudge the list until the centre is comfortably on screen, then tap.
  SAFE_LO=$((SH * 20 / 100)); SAFE_HI=$((SH * 75 / 100))
  EXPANDED=""; SRC="$HDR"; LASTC=""; LASTE="$HDR"
  for try in 1 2 3; do
    for nudge in 1 2 3; do
      C=$(nodecenter "$SRC" 'Toolbar'); [ -z "$C" ] && break
      CY=${C#* }
      [ "$CY" -ge "$SAFE_LO" ] && [ "$CY" -le "$SAFE_HI" ] && break
      # Too low: content moves up. Too high: content moves down. Quarter-screen steps so the
      # header cannot be scrolled clean past the viewport in one go.
      if [ "$CY" -gt "$SAFE_HI" ]; then
        adb shell input swipe $((SW / 2)) $((SH * 60 / 100)) $((SW / 2)) $((SH * 40 / 100)) 300
      else
        adb shell input swipe $((SW / 2)) $((SH * 40 / 100)) $((SW / 2)) $((SH * 60 / 100)) 300
      fi
      sleep 2
      N="$OUT/ui-tb-nudge-$try-$nudge.xml"; dumpui "$N"
      # Only adopt the new dump if the header is still in it, so an overshoot cannot lose the target.
      grep -qE 'text="[^"]*Toolbar' "$N" 2>/dev/null && SRC="$N"
    done
    C=$(nodecenter "$SRC" 'Toolbar'); LASTC="$C"
    [ -n "$C" ] && { adb shell input tap $C; sleep 3; }
    E="$OUT/ui-tb-expanded-$try.xml"; dumpui "$E"; LASTE="$E"
    # A single tap on a collapsed header can only expand it, so re-tapping is safe -- but only
    # after re-reading the screen, since the list may have shifted under the tap.
    if grep -qE 'text="Show toolbar"' "$E" 2>/dev/null; then EXPANDED="$E"; break; fi
    info "attempt $try: tapped '${C:-none}' (band $SAFE_LO-$SAFE_HI), section still collapsed"
  done

  if [ -n "$EXPANDED" ]; then
    E="$EXPANDED"; TBUI_VERDICT=switch
    pass "expanding the section composed the 'Show toolbar' switch"
    # The Add button can sit below the fold once the button list is expanded, so hunt for it
    # rather than failing on the first miss.
    # Test-then-scroll, so EVERY dump the hunt takes is actually tested. Run 39789396340 lost this
    # check to an off-by-one: the old loop scrolled after its third test and then fell out, leaving
    # ui-tb-add-3.xml unexamined -- and the very next dump, taken from the same scroll position,
    # showed text="Add" sitting right there. Three scrolls, four tests.
    A=""
    for a2 in 1 2 3 4; do
      A=$(nodecenter "$E" '^add$')
      [ -n "$A" ] && break
      [ "$a2" -eq 4 ] && break
      scrollup; E="$OUT/ui-tb-add-$a2.xml"; dumpui "$E"
    done
    [ -n "$A" ] && { adb shell input tap $A; sleep 3; }
    P="$OUT/ui-tb-picker.xml"; dumpui "$P"; shot 06-toolbar-picker
    if grep -qE 'text="Choose a command"' "$P" 2>/dev/null; then
      # Anchored: each text node must EQUAL a catalogue label. The switch description also says
      # "undo, copy, paste", but as part of a longer sentence, so it cannot match these.
      # `grep -o | sort -u | wc -l`, NOT `grep -c`: a real uiautomator dump is ONE line, so -c
      # would report 1 no matter how many catalogue labels rendered.
      N=$(grep -oE 'text="(Undo|Redo|Copy|Paste|Cursor Left|Voice Typing|Select Word)"' "$P" 2>/dev/null | sort -u | wc -l)
      if [ "${N:-0}" -eq 0 ]; then
        # The dialog opened but rendered no catalogue entries at all -- ALL_COMMANDS empty or the
        # LazyColumn composed nothing. Registry order puts copy/paste first, so a working picker
        # always shows at least two of these labels without scrolling.
        TBUI_VERDICT=picker-empty
        fail "picker opened but rendered NO command labels: texts=[$(texts "$P")]"
      else
        TBUI_VERDICT=picker-$N
        pass "command picker opened over CommandRegistry ($N catalogue labels on screen)"
      fi
    else
      TBUI_VERDICT=no-picker
      # add-nodes counts the literal text="Add" in the last dump tested and in the picker dump, so a
      # NOT-FOUND tap says at once whether the button was absent or merely unmatched by ^add$.
      fail "picker did not open: 'Choose a command' absent after tapping Add at '${A:-NOT-FOUND}': add-nodes=$(grep -o 'text="Add"' "$E" 2>/dev/null | wc -l | tr -d ' ')/$(grep -o 'text="Add"' "$P" 2>/dev/null | wc -l | tr -d ' ') last-dump=$(basename "$E") texts=[$(texts "$P")]"
    fi
    adb shell input keyevent KEYCODE_BACK; sleep 2
  else
    TBUI_VERDICT=no-switch
    fail "header found but 3 expand attempts never composed the switch: last tapped '${LASTC:-NOT-FOUND}' band=$SAFE_LO-$SAFE_HI screen=${SW}x${SH}: texts=[$(texts "$LASTE")]"
  fi
fi

# 7c. One-handed mode settings (gap analysis phase 2). Assertable for the same reason 7b is:
# SettingsActivity is an ordinary app window, so uiautomator captures it. The shrunken keyboard
# itself is NOT assertable from a dump -- a scaleX transform exposes no text and no resource-id to
# match, and IME windows are not captured at all -- so the pixel question goes to the screenshots
# and this walks the settings surface end to end instead: the header renders, expanding it composes
# the switch, the side/width controls stay hidden until it is on, turning it on reveals them, and
# the value reaches device-protected storage.
echo "== 7c. one-handed mode settings (phase 2 UI) =="
# The header reads "One-Handed Mode" (capital H) and the switch inside it "One-handed mode"
# (lowercase h), so CASE-SENSITIVE greps tell them apart -- none of these use -i. nodecenter does
# match case-insensitively, which is harmless: while the section is collapsed the switch is not
# composed, so the header is the only node carrying either spelling.
adb shell am start -W -n "$PKG/$NS.SettingsActivity" > "$OUT/am-settings-oh.log" 2>&1
sleep 5

# Own safe band rather than reusing 7b's: those variables are set inside a branch that a header
# miss would skip, and a tap near the screen edge hits the gesture nav bar (run 39681883670).
OH_LO=$((SH * 20 / 100)); OH_HI=$((SH * 75 / 100))
OH_HDR=""
for attempt in 1 2 3 4 5 6 7 8; do
  F="$OUT/ui-oh-hdr-$attempt.xml"; dumpui "$F"
  if grep -qiE "responding" "$F" 2>/dev/null; then
    info "attempt $attempt: ANR dialog over the settings screen -- dismissing"
    dismiss_anr || true
    continue
  fi
  if grep -qE 'text="[^"]*One-Handed Mode' "$F" 2>/dev/null; then OH_HDR="$F"; break; fi
  info "attempt $attempt: no one-handed header on screen, scrolling"
  scrollup
done

if [ -z "$OH_HDR" ]; then
  OH_VERDICT=no-header
  fail "one-handed section header never appeared after 8 dump/scroll attempts: texts=[$(texts "$OUT"/ui-oh-hdr-*.xml)]"
else
  pass "one-handed section header present in the settings list"
  SRC="$OH_HDR"; OH_EXP=""; OH_LASTC=""
  for try in 1 2 3; do
    for nudge in 1 2 3; do
      C=$(nodecenter "$SRC" 'One-Handed Mode'); [ -z "$C" ] && break
      CY=${C#* }
      [ "$CY" -ge "$OH_LO" ] && [ "$CY" -le "$OH_HI" ] && break
      if [ "$CY" -gt "$OH_HI" ]; then
        adb shell input swipe $((SW / 2)) $((SH * 60 / 100)) $((SW / 2)) $((SH * 40 / 100)) 300
      else
        adb shell input swipe $((SW / 2)) $((SH * 40 / 100)) $((SW / 2)) $((SH * 60 / 100)) 300
      fi
      sleep 2
      N="$OUT/ui-oh-nudge-$try-$nudge.xml"; dumpui "$N"
      # Only adopt the new dump if the header survived the scroll, so an overshoot cannot lose it.
      grep -qE 'text="[^"]*One-Handed Mode' "$N" 2>/dev/null && SRC="$N"
    done
    C=$(nodecenter "$SRC" 'One-Handed Mode'); OH_LASTC="$C"
    [ -n "$C" ] && { adb shell input tap $C; sleep 3; }
    E="$OUT/ui-oh-expanded-$try.xml"; dumpui "$E"
    if grep -qE 'text="One-handed mode"' "$E" 2>/dev/null; then OH_EXP="$E"; break; fi
    info "attempt $try: tapped '${C:-none}' (band $OH_LO-$OH_HI), section still collapsed"
  done

  if [ -z "$OH_EXP" ]; then
    OH_VERDICT=no-switch
    fail "one-handed header found but 3 expand attempts never composed the switch: last tapped '${OH_LASTC:-NOT-FOUND}' band=$OH_LO-$OH_HI screen=${SW}x${SH}: texts=[$(texts "$OUT"/ui-oh-expanded-*.xml)]"
  else
    OH_VERDICT=switch
    pass "expanding the section composed the 'One-handed mode' switch"
    shot 07-one-handed-collapsed
    E="$OH_EXP"

    # Side and width are gated behind the switch, so on a fresh install (default off) they must not
    # be on screen. Only a necessary condition, and deliberately not a stronger claim: a control
    # below the fold is missing from a dump without being missing from the UI, which is the whole
    # subject of the hunt further down. What makes the pair meaningful is that the same control
    # DOES appear once the switch is on.
    if grep -qE 'text="Keyboard width"' "$E" 2>/dev/null; then
      info "'Keyboard width' visible before any toggle -- a previous run left the pref enabled"
      OH_VERDICT=pre-on
    else
      pass "side/width controls not on screen while one-handed mode is off"
    fi

    # SettingsSwitch makes the whole Row clickable, so the title's centre is a valid tap target.
    #
    # ANCHORED on purpose. nodecenter matches case-INSENSITively and returns the FIRST hit in
    # document order, and the section header ("One-Handed Mode", emoji escaped to a numeric
    # entity) sits above the switch row ("One-handed mode") -- so an unanchored pattern resolves
    # to the header, and tapping it COLLAPSES the section instead of flipping the switch. The
    # offline harness caught exactly that: both taps landed on the header and the width control
    # never appeared. ^...$ can only match the switch title, whose label has no emoji prefix.
    C=$(nodecenter "$E" '^One-handed mode$')
    if [ -z "$C" ]; then
      OH_VERDICT=no-toggle
      fail "could not locate the 'One-handed mode' row to tap: texts=[$(texts "$E")]"
    else
      CY=${C#* }
      if [ "$CY" -lt "$OH_LO" ] || [ "$CY" -gt "$OH_HI" ]; then
        if [ "$CY" -gt "$OH_HI" ]; then
          adb shell input swipe $((SW / 2)) $((SH * 60 / 100)) $((SW / 2)) $((SH * 40 / 100)) 300
        else
          adb shell input swipe $((SW / 2)) $((SH * 40 / 100)) $((SW / 2)) $((SH * 60 / 100)) 300
        fi
        sleep 2
        N="$OUT/ui-oh-switch-nudge.xml"; dumpui "$N"
        C2=$(nodecenter "$N" '^One-handed mode$'); [ -n "$C2" ] && C="$C2"
      fi
      adb shell input tap $C; sleep 3
      # HUNT for the width control instead of failing on the first miss. Run 39727796466 reported
      # oh=no-width with the toolbar section's own rows on screen ("10 / 12", "Add", "Copy",
      # "Cursor Left", ...): 7b leaves that section EXPANDED, and its ten button rows sit directly
      # above this one, so the switch landed at y=1726 of 2400 and the controls it reveals composed
      # below the fold. The section was still expanded in that dump ("One-handed mode" present), so
      # the tap had hit the row -- the assertion was simply looking one screenful too high. Same
      # trap 7b's Add-button hunt exists for.
      T=""; W=""
      for w in 1 2 3 4; do
        T="$OUT/ui-oh-toggled-$w.xml"; dumpui "$T"
        if grep -qE 'text="Keyboard width"' "$T" 2>/dev/null; then W="$T"; break; fi
        info "attempt $w: 'Keyboard width' not on screen yet, scrolling"
        scrollup
      done
      shot 08-one-handed-toggled
      if [ -n "$W" ]; then
        OH_VERDICT=toggled
        pass "toggling the switch revealed the width control (state var + recomposition wired)"
        # 'Side' is a short common word, so a miss is reported rather than failed.
        if grep -qE 'text="Side"' "$W" 2>/dev/null; then
          pass "the side dropdown composed alongside it"
        else
          info "the side dropdown title was not in the same dump (it may sit above the fold now)"
        fi
      else
        OH_VERDICT=no-width
        fail "tapping the switch at '$C' never revealed 'Keyboard width' after 4 dump/scroll attempts: texts=[$(texts "$OUT"/ui-oh-toggled-*.xml)]"
      fi

      # Best-effort persistence check. DirectBootAwarePreferences writes
      # "${packageName}_preferences" into DEVICE-PROTECTED storage (/data/user_de/0/...), which
      # run-as does not always reach. Reported, never asserted: a harness limitation must not sink
      # a run whose UI assertions all passed.
      PX="$OUT/oh-prefs.xml"
      adb shell "run-as $PKG cat /data/user_de/0/$PKG/shared_prefs/${PKG}_preferences.xml" > "$PX" 2>&1
      grep -q "one_handed" "$PX" 2>/dev/null || \
        adb shell "run-as $PKG cat shared_prefs/${PKG}_preferences.xml" > "$PX" 2>&1
      if grep -q "one_handed_enabled" "$PX" 2>/dev/null; then
        V=$(grep -oE 'name="one_handed_enabled" value="[a-z]+"' "$PX" | head -1)
        S2=$(grep -oE 'name="one_handed_side" value="[a-z]+"' "$PX" | head -1)
        pass "preference reached device-protected storage: ${V:-present} ${S2}"
      else
        info "protected prefs unreadable via run-as (harness limitation): $(peek "$PX" 140)"
      fi
    fi
  fi
fi

echo "== 7d. phase-3 feature switches (incognito, floating, split) =="
# Composition evidence for the three phase-3 controls, reached through the settings SEARCH BAR
# instead of by scrolling. Runs 39781367258 and 39783242401 are why: 7b and 7c leave the Toolbar
# and Appearance sections expanded, so the list is several screens long by the time 7d starts and
# every fixed scroll budget is a coin flip -- run 29 failed because its hunt started deep and
# scrollup only reveals LOWER items, run 30 failed because it started at the top and ran out of
# budget before Privacy. Search is deterministic: the bar sits at a known place at the top of the
# screen, result rows are titled with the setting name, and tapping one runs executeSearchAction ->
# collapseAllSections + expandSection + scrollToSetting, which polls up to 1.5 s for the control to
# register its position and then puts it at the top of the viewport (SettingsSearch.kt:42-67). It
# also proves the generated search index -- regenerated for parts 2-4, now 176 entries -- really
# carries the new controls, which nothing else in this suite checks. The scroll route stays as a
# fallback, so a search regression cannot mask a settings regression, and NF_ROUTE/NF_SEARCH say
# which tier answered.
# Beyond composition, floating gets the one check only a device can give: that enabling it narrows
# the input-method WINDOW itself (WindowLayoutUtils.applyFloatingWindowLayoutParams), not merely a
# view inside it. Tap outcomes stay evidence, never assertions -- whether a Compose switch flipped
# is not something a dump can prove, and a harness limitation must not sink a run whose real checks
# passed. Floating and incognito are deliberately left ENABLED afterwards: the rest of the run then
# exercises the keyboard in that state, and section 8's crash buffer is the assertion that catches
# a bad interaction with the one-handed mode 7c left on.
NF_VERDICT=ok
NF_SRC=""
NF_ROUTE=none
NF_SEARCH=no

# Twelve downward swipes: reach the top of the settings list from anywhere in it. Needed by both
# tiers -- the search bar is inside the scrolling Column (SettingsScreen.kt:106-139), so a list
# left scrolled deep by an earlier section hides it exactly like it hides a section header. Twelve
# rather than eight because the offline harness models NO fling momentum (a 960px drag moves the
# list 960px, where a device flings further), and eight only just reached the top from the bottom
# of a list with two sections expanded.
nf_totop() {
  local n
  for n in 1 2 3 4 5 6 7 8 9 10 11 12; do
    adb shell input swipe $((SW / 2)) $((SH * 30 / 100)) $((SW / 2)) $((SH * 70 / 100)) 250
    sleep 1
  done
}

# Dump, test, scroll down, repeat. Twelve big steps (85% -> 15% of the screen) because with two
# sections left expanded by 7b/7c the list runs to several screens. Winner lands in NF_SRC.
nf_scan() {  # $1 = dump-name prefix, $2 = text regex
  local pre="$1" re="$2" f n
  for n in 1 2 3 4 5 6 7 8 9 10 11 12; do
    f="$OUT/ui-$pre-scan-$n.xml"; dumpui "$f"
    if grep -qiE "responding" "$f" 2>/dev/null; then
      info "scan $n: ANR dialog over the settings screen -- dismissing"
      dismiss_anr || true
      continue
    fi
    if grep -qE "text=\"[^\"]*$re" "$f" 2>/dev/null; then NF_SRC="$f"; return 0; fi
    adb shell input swipe $((SW / 2)) $((SH * 85 / 100)) $((SW / 2)) $((SH * 15 / 100)) 200
    sleep 2
  done
  return 1
}

# Centre of the TOPMOST text field in a dump. In SettingsActivity that is the search bar; in
# LauncherActivity it is the "test your new keyboard here" field that brings the keyboard up.
nf_topfield() {  # $1 = dump
  python3 - "$1" <<'PY' 2>/dev/null
import re, sys
try:
    xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
except Exception:
    sys.exit(0)
best = None
for m in re.finditer(r'<node[^>]*>', xml):
    tag = m.group(0)
    if not re.search(r'class="[^"]*(?:EditText|TextField)[^"]*"', tag):
        continue
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
    if not b:
        continue
    x1, y1, x2, y2 = map(int, b.groups())
    if (x2 - x1) < 20 or (y2 - y1) < 10:
        continue
    if best is None or y1 < best[0]:
        best = (y1, (x1 + x2) // 2, (y1 + y2) // 2)
if best:
    print("%d %d" % (best[1], best[2]))
PY
}

# Tap target for a settings row. SettingsSwitch makes the whole Row clickable (7c relies on the same
# fact), but an unanchored pattern can resolve to the WRONG node: "Floating Keyboard" is also a
# prefix of the "Floating Keyboard Width" slider, and 7c was bitten by a section header that differs
# from its switch only by case. So try the exact label first and only then fall back to a substring.
nf_tapcenter() {  # $1 = dump, $2 = exact row title
  local c
  c=$(nodecenter "$1" "^$2\$")
  [ -n "$c" ] || c=$(nodecenter "$1" "$2")
  printf '%s' "$c"
}

# State of the switch whose label matches $2, as "<checked> <diagnostics>". Compose merges a
# SettingsSwitch row into one semantics node carrying both the title and the toggle state, so this
# reads THE switch rather than "some switch on screen is checked". The diagnostics ride along
# because whether real dumps expose `checked` on that node is not something this sandbox can verify:
# if they do not, 7d must degrade to reporting that, never to asserting a state change it cannot
# confirm. Prints "none no-match" when no node carries the label at all.
nf_switchstate() {  # $1 = dump, $2 = row title
  python3 - "$1" "$2" <<'PY' 2>/dev/null
import re, sys
try:
    xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
except Exception:
    print("none unreadable-dump"); sys.exit(0)
title = sys.argv[2].lower()

def attr(tag, name):
    m = re.search(name + r'="([^"]*)"', tag)
    return m.group(1) if m else "?"

nodes = []
for m in re.finditer(r'<node[^>]*>', xml):
    tag = m.group(0)
    label = " ".join(re.findall(r'(?:text|content-desc)="([^"]*)"', tag)).strip()
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
    nodes.append((tag, label, tuple(map(int, b.groups())) if b else None))

# A SettingsSwitch row is TWO nodes in a real dump, which run 39789396340 established the hard way:
# the title is an android.widget.TextView with checkable="false" checked="false", and the toggle
# beside it carries the state with no label at all. Reading the title node therefore always reports
# "false" and would have silently disabled the window-frame assertion for good. So find the title,
# then take the checkable node sharing its row band.
pick = None
for tag, label, b in nodes:
    if label and label.lower() == title:
        pick = (tag, label, b); break
if pick is None:
    for tag, label, b in nodes:
        if label and title in label.lower():
            pick = (tag, label, b); break
if pick is None:
    print("none no-match"); sys.exit(0)
tag, label, bounds = pick
if bounds:
    x1, y1, x2, y2 = bounds
    band = max(24, y2 - y1)
    cy = (y1 + y2) // 2
    mates = sorted(
        [(abs((bb[1] + bb[3]) // 2 - cy), t, l, bb) for t, l, bb in nodes
         if bb and t is not tag and attr(t, "checkable") == "true"
         and y1 - band <= (bb[1] + bb[3]) // 2 <= y2 + band])
    if mates:
        # nearest to the title's own centre, not merely first in document order: a neighbouring row's
        # toggle can fall inside a generous band and would report the wrong switch's state.
        _d, t, l, bb = mates[0]
        print("%s checkable=true class=%s label='%s' row-mates=%d title-class=%s"
              % (attr(t, "checked"), attr(t, "class"), l[:14], len(mates), attr(tag, "class")))
        sys.exit(0)
print("%s checkable=%s class=%s label=%s (title node itself, no toggle beside it)"
      % (attr(tag, "checked"), attr(tag, "checkable"), attr(tag, "class"), label[:20]))
PY
}

# Nodes reporting checked="true" anywhere in a dump. The second, independent way to confirm a tap
# landed, for dumps where the toggle cannot be tied to its title row: whatever the count was before
# the tap, one more afterwards means something on that row turned on.
nf_checkedcount() { grep -o 'checked="true"' "$1" 2>/dev/null | wc -l | tr -d ' '; }

# Failure evidence for a 7d hunt. Plain `texts` (sorted-unique head -8 across EVERY dump of the
# hunt) proved worthless in run 39783242401: numeric emoji entities sort before letters, so all
# eight slots went to section icons and the annotation never said what the hunt had looked at.
# Report the NEWEST dump alone, plus every section header the hunt ever saw.
nf_evidence() {  # $1 = dump-name prefix
  local last n
  last=$(ls -1t "$OUT"/ui-"$1"-*.xml 2>/dev/null | head -1)
  n=$(ls -1 "$OUT"/ui-"$1"-*.xml 2>/dev/null | wc -l | tr -d ' ')
  printf 'dumps=%s newest=%s texts=[%s] sections=[%s]' \
    "${n:-0}" "$(basename "${last:-none}")" \
    "$(grep -ohE 'text="[^"]{1,26}"' "${last:-/dev/null}" 2>/dev/null | sort -u | head -6 | tr '\n' ' ')" \
    "$(grep -ohE 'text="[^"]*(Privacy|Appearance)[^"]{0,10}"' "$OUT"/ui-"$1"-*.xml 2>/dev/null | sort -u | head -3 | tr '\n' ' ')"
}

# Reach the control titled $3 and leave the dump that shows it in NF_SRC.
#   Tier 1 (search): fresh SettingsActivity, climb to the top, tap the search bar, type $2, the
#     result row titled $3 must compose, tap it -> all sections collapse, the right one expands and
#     the control scrolls to the top of the viewport.
#   Tier 2 (scroll): only if tier 1 never produced the result row -- climb, scan down for the
#     section header $4, centre it in the tap-safe band, tap to expand, then scan for the control.
nf_reach() {  # $1 = prefix, $2 = search query, $3 = control title, $4 = section header regex
  local pre="$1" q="$2" title="$3" hdr="$4"
  local f c n cy src
  local lo=$((SH * 20 / 100)) hi=$((SH * 75 / 100))
  NF_SRC=""; NF_ROUTE=none; NF_SEARCH=no
  adb shell am start -W -n "$PKG/$NS.SettingsActivity" > "$OUT/am-settings-$pre.log" 2>&1
  sleep 5
  nf_totop
  f="$OUT/ui-$pre-land.xml"; dumpui "$f"
  c=$(nf_topfield "$f")
  if [ -n "$c" ]; then
    adb shell input tap $c; sleep 2
    adb shell input text "$q" > /dev/null 2>&1
    sleep 3
    f="$OUT/ui-$pre-search.xml"; dumpui "$f"
    if grep -qE "text=\"[^\"]*$title" "$f" 2>/dev/null; then
      NF_SEARCH=yes
      c=$(nodecenter "$f" "$title")
      if [ -n "$c" ]; then
        # 7 s: executeSearchAction polls up to 1.5 s for the control's position, scrolls, then
        # highlights for 2 s. Dumping mid-scroll would read a frame that is about to change.
        adb shell input tap $c; sleep 7
        f="$OUT/ui-$pre-after.xml"; dumpui "$f"
        if grep -qE "text=\"[^\"]*$title" "$f" 2>/dev/null; then NF_SRC="$f"; NF_ROUTE=search; return 0; fi
        if nf_scan "$pre" "$title"; then NF_ROUTE=search; return 0; fi
      fi
    fi
  fi
  # Tier 2: the scroll route, attempted TWICE. Tapping a header is a toggle, and an earlier section
  # of this suite may have left the target expanded -- the offline harness's nosearch scenario fails
  # on exactly that, collapsing Appearance and then scanning twelve screens for a control that is no
  # longer composed. So: climb, find the header, centre it in the tap-safe band, tap, scan; if the
  # control never composed, climb back and tap the header a second time, which reopens the section.
  local round
  for round in 1 2; do
    nf_totop
    nf_scan "$pre-hdr$round" "$hdr" || return 1
    src="$NF_SRC"
    for n in 1 2 3; do
      c=$(nodecenter "$src" "$hdr"); [ -z "$c" ] && break
      cy=${c#* }
      [ "$cy" -ge "$lo" ] && [ "$cy" -le "$hi" ] && break
      if [ "$cy" -gt "$hi" ]; then
        adb shell input swipe $((SW / 2)) $((SH * 60 / 100)) $((SW / 2)) $((SH * 40 / 100)) 300
      else
        adb shell input swipe $((SW / 2)) $((SH * 40 / 100)) $((SW / 2)) $((SH * 60 / 100)) 300
      fi
      sleep 2
      f="$OUT/ui-$pre-hnudge-$round-$n.xml"; dumpui "$f"
      grep -qE "text=\"[^\"]*$hdr" "$f" 2>/dev/null && src="$f"
    done
    c=$(nodecenter "$src" "$hdr")
    if [ -n "$c" ]; then adb shell input tap $c; sleep 4; fi
    if nf_scan "$pre-exp$round" "$title"; then NF_ROUTE=scroll; return 0; fi
  done
  return 1
}

# The device-only half of part 3. Composition proves the switch exists; only a frame proves that
# turning it on resizes the input-method WINDOW, which is the whole mechanism (width 80 percent of
# the screen by default, gravity TOP|START at an explicit x/y). Asserted only when the evidence is
# unambiguous -- the keyboard must be up AND the window must be identified as the input method by
# its own header line -- because a mis-parsed window would be a harness lie, not a product defect.
# Anything less confident goes out as a warning annotation, which is also the first test of whether
# warnings survive annotation storage at all (notices are capped at about 12, errors survive).
nf_frame_probe() {
  local f c shown frameout w W1 W2 CONF WHDR _r
  # Bring the keyboard up over the activity we are ALREADY in, by focusing the settings search bar
  # (SettingsScreen.kt:139 -- an OutlinedTextField, so focusing it shows the IME like any editor).
  # Run 39804287846 is why this replaced a trip to LauncherActivity: `am start` from a shell implies
  # FLAG_ACTIVITY_NEW_TASK, which brings the app's existing task forward instead of starting the
  # named activity inside it, so focus stayed on SettingsActivity and three hunts looked for a
  # launcher text field on a settings screen. The warning said so in as many words -- focus= and a
  # ScrollView among the classes. The IME window is ours whichever app hosts the editor, so nothing
  # about the measurement is weaker for staying put, and one navigation is one less thing to flake.
  nf_totop
  c=""
  for f in 1 2 3; do
    dumpui "$OUT/ui-nfframe-$f.xml"
    c=$(nf_topfield "$OUT/ui-nfframe-$f.xml")
    [ -n "$c" ] && break
    adb shell input swipe $((SW / 2)) $((SH * 3 / 4)) $((SW / 2)) $((SH / 4)) 300
    sleep 3
  done
  if [ -z "$c" ]; then
    ann warning "NFFRAME no text field to focus after 3 attempts, floating window unmeasured: focus='$(focus)' classes=[$(grep -ohE 'class="[^"]*"' "$OUT"/ui-nfframe-*.xml 2>/dev/null | sort -u | head -4 | tr '\n' ' ')]"
    return 0
  fi
  adb shell input tap $c; sleep 7
  shot 09-floating-keyboard
  adb shell dumpsys input_method > "$OUT/dumpsys-input_method-floating.txt" 2>&1
  shown=$(grep -oE 'mInputShown=[a-z]*' "$OUT/dumpsys-input_method-floating.txt" 2>/dev/null | head -1 | tr -d '\r')
  FR="$OUT/window-frames.txt"
  adb shell dumpsys window windows > "$FR" 2>&1
  # Fall back to the undifferentiated dump if that spelling produced no window list at all: which
  # sub-commands a given Android build accepts is not something this sandbox can check, and run
  # 39877368710 came back with conf=0 and an empty frame, so the shape of this output is a guess
  # until the diagnostics below say otherwise.
  FSRC=windows
  grep -q "Window #" "$FR" 2>/dev/null || { adb shell dumpsys window > "$FR" 2>&1; FSRC=full; }
  # Line 1: "<x1> <y1> <x2> <y2> <confidence> <header>". Confidence 3 = the window's own header
  # names the input method, 2 = the block does (a window's mAttrs carry ty=INPUT_METHOD), 1 = the
  # header names our package. Losing candidates follow as "ALT <header>", and line "DIAG ..." always
  # reports the raw shape of the dump, because the format is the one thing still unverified here.
  python3 - "$FR" "$PKG" "$FSRC" > "$OUT/nfframe.txt" 2>/dev/null <<'PY'
import re, sys
try:
    txt = open(sys.argv[1], encoding="utf-8", errors="replace").read()
except Exception:
    print("0 0 0 0 0 unreadable")
    print("DIAG unreadable")
    sys.exit(0)
pkg = (sys.argv[2] if len(sys.argv) > 2 else "").lower()
src = sys.argv[3] if len(sys.argv) > 3 else "?"
# Android 12 and later print a window's rectangle as a "Frames:" block with a lowercase frame=, not
# the mFrame= of older releases. Match either, and do not care which.
FRAME = re.compile(r'\bm?[Ff]rame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]')
blocks = [b for b in re.split(r'(?m)^(?=\s*Window #)', txt) if b.strip().startswith("Window #")]
best, alts, withframe = None, [], 0
for block in blocks:
    head = block.split("\n", 1)[0].strip()
    m = FRAME.search(block)
    if not m:
        continue
    withframe += 1
    hl, bl = head.lower(), block[:1500].lower()
    if "inputmethod" in hl or "input_method" in hl:
        conf = 3
    elif "ty=input_method" in bl:
        # the window TYPE, not merely a mention: a block can reference the input method as a target
        # or a layer without being the input method's own window.
        conf = 2
    elif pkg and pkg in hl:
        conf = 1
    else:
        continue
    x1, y1, x2, y2 = map(int, m.groups())
    if best is None or conf > best[0]:
        if best is not None:
            alts.append(best[5])
        best = (conf, x1, y1, x2, y2, head[:56])
    else:
        alts.append(head[:56])
keys = ",".join(k for k in ("mFrame=", "frame=", "Frames:", "ty=INPUT_METHOD") if k.lower() in txt.lower())
print("%d %d %d %d %d %s" % (best[1], best[2], best[3], best[4], best[0], best[5]) if best
      else "0 0 0 0 0 none")
for a in alts[:2]:
    print("ALT %s" % a)
print("DIAG src=%s blocks=%d withframe=%d keys=%s bytes=%d" % (src, len(blocks), withframe,
                                                              keys or "none", len(txt)))
PY
  frameout=$(head -1 "$OUT/nfframe.txt" 2>/dev/null)
  DIAG=$(sed -n 's/^DIAG //p' "$OUT/nfframe.txt" 2>/dev/null | head -1)
  W1=0; T1=0; W2=0; T2=0; CONF=0; WHDR=none
  if [ -n "$frameout" ]; then
    # the header is last and may contain spaces, so shift the five numeric fields off the front
    set -- $frameout
    W1=${1:-0}; T1=${2:-0}; W2=${3:-0}; T2=${4:-0}; CONF=${5:-0}
    [ "$#" -ge 6 ] && { shift 5; WHDR="$*"; }
  fi
  case "$CONF" in (*[!0-9]*|"") CONF=0 ;; esac
  # Always annotated: notices are capped and get dropped past about the twelfth, so a PASS from here
  # would never be readable, and the shape of this dump is the last unverified assumption in 7d.
  ann warning "NFFRAME-DIAG ${DIAG:-none} conf=$CONF frame=[$W1,$T1][$W2,$T2] screen=${SW}x${SH} shown=${shown:-none} header='$WHDR' alt=[$(grep '^ALT' "$OUT/nfframe.txt" 2>/dev/null | cut -c5-56 | tr '\n' '|')] focus='$(focus)'"
  if [ "$CONF" -ge 2 ] && [ "$shown" = "mInputShown=true" ]; then
    w=$((W2 - W1))
    if [ "$w" -gt 0 ] && [ "$w" -lt "$SW" ]; then
      pass "floating narrowed the input-method WINDOW to ${w}px of ${SW}px at y=${T1}: $WHDR"
    else
      NF_VERDICT=$([ "$NF_VERDICT" = "ok" ] && echo frm || echo "$NF_VERDICT")
      fail "floating left the input-method window ${w}px wide on a ${SW}px screen: shown=${shown:-none} frame=[$W1,$T1][$W2,$T2] header='$WHDR' $DIAG"
    fi
  else
    info "floating window not assertable from this dump (see the NFFRAME-DIAG annotation)"
  fi
  # Leave as we arrived. With an IME shown, BACK is consumed by the input method and hides it rather
  # than popping the activity, so the split check that follows finds the settings screen still there.
  adb shell input keyevent KEYCODE_BACK > /dev/null 2>&1
  sleep 3
}

# --- incognito, under Privacy ---
if nf_reach nfinc incognito 'Incognito Mode' 'Privacy'; then
  HS=$(grep -qE 'text="[^"]*Privacy' "$NF_SRC" 2>/dev/null && echo yes || echo no)
  pass "incognito switch composed under Privacy (route=$NF_ROUTE search-result=$NF_SEARCH header-in-same-dump=$HS)"
  C=$(nf_tapcenter "$NF_SRC" 'Incognito Mode')
  if [ -n "$C" ]; then
    adb shell input tap $C; sleep 3
    D="$OUT/ui-nfinc-on.xml"; dumpui "$D"
    info "incognito switch state after tapping at $C: $(nf_switchstate "$D" 'Incognito Mode') (evidence, not asserted)"
    shot 08-incognito-on
  fi
else
  NF_VERDICT=inc
  fail "incognito switch never composed (route=$NF_ROUTE search-result=$NF_SEARCH): $(nf_evidence nfinc)"
fi

# --- floating, under Appearance, then the window-frame probe ---
if nf_reach nfflt floating 'Floating Keyboard' 'Appearance'; then
  HS=$(grep -qE 'text="[^"]*Appearance' "$NF_SRC" 2>/dev/null && echo yes || echo no)
  pass "floating keyboard switch composed under Appearance (route=$NF_ROUTE search-result=$NF_SEARCH header-in-same-dump=$HS)"
  PRE=$(nf_checkedcount "$NF_SRC")
  C=$(nf_tapcenter "$NF_SRC" 'Floating Keyboard')
  if [ -n "$C" ]; then adb shell input tap $C; sleep 4; fi
  D="$OUT/ui-nfflt-on.xml"; dumpui "$D"
  shot 08b-floating-switch
  POST=$(nf_checkedcount "$D")
  SWST=$(nf_switchstate "$D" 'Floating Keyboard')
  info "floating switch: tap=${C:-none} state='$SWST' checked-nodes ${PRE:-0}->${POST:-0}"
  # The frame probe asserts a CONSEQUENCE of the switch being on, so it only runs once the switch is
  # confirmed on -- either by the toggle beside the title row, or by the checked count rising across
  # the tap. Tapping a Compose row and hoping is how a harness turns into a liar: if the tap missed,
  # the window is legitimately full width and the failure would blame the product.
  if [ "${SWST%% *}" = "true" ] || [ "${POST:-0}" -gt "${PRE:-0}" ]; then
    nf_frame_probe
  else
    ann warning "NFFRAME skipped, floating never confirmed on: state='$SWST' checked-nodes=${PRE:-0}->${POST:-0} tap='${C:-none}' focus='$(focus)'"
  fi
else
  NF_VERDICT=$([ "$NF_VERDICT" = "ok" ] && echo flt || echo "$NF_VERDICT")
  fail "floating keyboard switch never composed (route=$NF_ROUTE search-result=$NF_SEARCH): $(nf_evidence nfflt)"
fi

# --- split, under Appearance. Reached by its own search: the floating probe left us in
# LauncherActivity with a keyboard up, and "split" also matches the Split Gap slider, so the
# assertion is on the full title, which only the switch row carries. ---
if nf_reach nfspl split 'Split Keyboard' 'Appearance'; then
  HS=$(grep -qE 'text="[^"]*Appearance' "$NF_SRC" 2>/dev/null && echo yes || echo no)
  pass "split keyboard switch composed under Appearance (route=$NF_ROUTE search-result=$NF_SEARCH header-in-same-dump=$HS)"
  C=$(nf_tapcenter "$NF_SRC" 'Split Keyboard')
  if [ -n "$C" ]; then
    adb shell input tap $C; sleep 3
    D="$OUT/ui-nfspl-on.xml"; dumpui "$D"
    info "split switch state after tapping at $C: $(nf_switchstate "$D" 'Split Keyboard') (evidence, not asserted)"
    shot 10-split-on
  fi
else
  NF_VERDICT=$([ "$NF_VERDICT" = "ok" ] && echo spl || echo "$NF_VERDICT")
  fail "split keyboard switch never composed (route=$NF_ROUTE search-result=$NF_SEARCH): $(nf_evidence nfspl)"
fi

# --- 7e: dictation, phase 4 part 1 ---------------------------------------------------------------
# The mic key's old behaviour was to hand the field to a voice IME, which on the owner's Huawei
# produced a system toast and typed nothing. A real recognition session cannot run here: the AOSP
# image has no speech service and no microphone, so `isRecognitionAvailable` is false and the policy
# falls back. What CAN be proven is the whole delivery path -- preference read, DictationPolicy
# decision, DictationController commit, InputConnection into a live editor -- by riding the
# self-test switch, which substitutes a fixed transcript for audio. If that transcript lands in the
# field the keyboard is bound to, everything except the recogniser itself is proven on a real device.
#
# The assertion is gated on the switch being CONFIRMED on, exactly like the frame probe in 7d: if
# the tap missed, the key legitimately does the legacy thing and a failure would blame the product
# for the harness's miss. VOICE-DIAG is emitted whether or not anything was asserted, because
# warnings survive annotation storage where notices are dropped at about twelve.
MIC=""; GRANT="none"
if nf_reach nfvoi dictation 'Dictation Self-Test' 'Input'; then
  pass "dictation self-test switch composed under Input Behavior (route=$NF_ROUTE search-result=$NF_SEARCH)"
  PRE=$(nf_checkedcount "$NF_SRC")
  C=$(nf_tapcenter "$NF_SRC" 'Dictation Self-Test')
  if [ -n "$C" ]; then adb shell input tap $C; sleep 3; fi
  D="$OUT/ui-nfvoi-on.xml"; dumpui "$D"
  POST=$(nf_checkedcount "$D")
  SWST=$(nf_switchstate "$D" 'Dictation Self-Test')
  info "self-test switch: tap=${C:-none} state='$SWST' checked-nodes ${PRE:-0}->${POST:-0}"
  shot 11-voice-switch
  if [ "${SWST%% *}" = "true" ] || [ "${POST:-0}" -gt "${PRE:-0}" ]; then
    # Granted up front so the decision line the key logs reports permissions the way a real phone
    # does after the user allows it. The self-test branch does not consult the permission, but the
    # log line reports it, and that line is the only plumbing evidence CI can read.
    GRANT=$(adb shell pm grant "$PKG" android.permission.RECORD_AUDIO 2>&1 | tr -d '\r' | head -1)
    [ -z "$GRANT" ] && GRANT=granted
    # nf_reach left focus on the switch row, so the keyboard is down. Bring the settings screen
    # forward, climb to the top, and focus the search bar -- the same editor 7d measures with.
    adb shell am start -W -n "$PKG/$NS.SettingsActivity" > "$OUT/am-settings-nfvoi.log" 2>&1
    sleep 4
    nf_totop
    F="$OUT/ui-nfvoi-field.xml"; dumpui "$F"
    FC=$(nf_topfield "$F")
    if [ -n "$FC" ]; then adb shell input tap $FC; sleep 3; fi
    F="$OUT/ui-nfvoi-kb.xml"; dumpui "$F"
    MIC=$(nodecenter_desc "$F" '^Voice Typing$')
    info "self-test: field-tap='${FC:-none}' mic-node='${MIC:-none}' shown=$(shown)"
    if [ -n "$MIC" ]; then
      adb shell input tap $MIC; sleep 3
      F="$OUT/ui-nfvoi-after.xml"; dumpui "$F"
      shot 11-voice-selftest
      if grep -q 'voice dictation self test' "$F" 2>/dev/null; then
        VOICE_VERDICT=ok
        pass "the mic key committed dictation's transcript into the focused field"
      else
        VOICE_VERDICT=commit
        fail "mic key tapped at $MIC with self-test confirmed on, but no transcript reached the field: $(nf_evidence nfvoi)"
      fi
    else
      VOICE_VERDICT=key
      ann warning "VOICE skipped, no toolbar node carries content-desc 'Voice Typing' (shown=$(shown) focus='$(focus)')"
    fi
  else
    VOICE_VERDICT=sw
    ann warning "VOICE skipped, self-test never confirmed on: state='$SWST' checked-nodes=${PRE:-0}->${POST:-0} tap='${C:-none}'"
  fi
else
  VOICE_VERDICT=flt
  fail "dictation self-test switch never composed (route=$NF_ROUTE search-result=$NF_SEARCH): $(nf_evidence nfvoi)"
fi
VOICEDIAG=$(adb logcat -d -v brief 2>/dev/null | grep -E 'DictationController|VoicePermissionActivity' \
            | tail -3 | tr '\n' '|' | tr -d '\r' | cut -c1-300)
ann warning "VOICE-DIAG verdict=$VOICE_VERDICT mic='${MIC:-none}' grant='$GRANT' shown=$(shown) log='${VOICEDIAG:-none}'"

# Run 39 returned mic='none' and view=0 while dumpsys said the IME was shown: either the dump
# truly carries no IME-window nodes on this emulator, or the keyboard is composed but its nodes
# look different than assumed. Warnings are the one CI channel that survives, so the dump gets to
# describe itself here; the next run's DUMPDIAG line decides whether 7e/7f tap nodes or pixels.
DUMPDIAG=$(python3 - "$OUT/ui-nfvoi-kb.xml" <<'PYD' 2>/dev/null
import re, sys
try:
    xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
except Exception:
    print("dump=unreadable"); sys.exit(0)
nodes = re.findall(r"<node[^>]*>", xml)
pkg = [t for t in nodes if 'package="tribixbite.cleverkeys' in t]
desc = [t for t in nodes if 'content-desc="Voice Typing"' in t]
kbv = [t for t in nodes if "Keyboard2View" in t]
cls = sorted({(m.group(1) if (m := re.search(r'class="([^"]*)"', t)) else "?") for t in pkg})
tail = ",".join(c.split(".")[-1] for c in cls[:6])
print("nodes=%d pkg=%d descVoice=%d kbview=%d classes=%s" % (len(nodes), len(pkg), len(desc), len(kbv), tail or "-"))
PYD
)
ann warning "DUMPDIAG ${DUMPDIAG:-dump=missing} (the ui-nfvoi-kb dump 7e tapped the mic from)"

# --- 7f: does the keyboard fit inside its window (phase 4 part 2) -------------------------------
# The owner's phone clipped the bottom key row at the screen edge. The mechanism: onMeasure adds
# the navigation-bar inset to the view's height, and on a gesture-nav phone the pre-insets fallback
# reserved the FULL 3-button bar (it computed isGestureNav and then never read it), so the view
# grew ~48dp taller than its window and the window manager clipped what did not fit. Two numbers
# settle it on any device: the bottom edge of the keyboard view's own bounds in the accessibility
# dump, and the bottom edge of the input-method window's frame in `dumpsys window`. A view that
# ends below its window is being clipped and that is a failure; anything else passes with the
# reserved strip reported, because space left for a nav bar below the window is device-specific.
ime_frame_bottom() {  # bottom edge of the input-method window's frame, dialect-tolerant (see 7d)
  adb shell dumpsys window windows 2>/dev/null | python3 -c '
import re, sys
blocks = re.split(r"(?=Window #)", sys.stdin.read())
for b in blocks:
    head = b.split("\n", 1)[0]
    if "ty=INPUT_METHOD" in b or "InputMethod" in head:
        m = re.search(r"\bm?[Ff]rame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b)
        if m:
            print(m.group(4)); sys.exit(0)
'
}

kbclip_view_bottom() {  # $1 = dump; lowest bottom edge of the keyboard view itself
  # Matched on the class name, not the package: the settings activity is the SAME package, so a
  # package match would take the tallest settings row and accuse the keyboard of nothing.
  python3 - "$1" <<'PY' 2>/dev/null
import re, sys
try:
    xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
except Exception:
    sys.exit(0)
best = 0
for m in re.finditer(r'<node[^>]*>', xml):
    tag = m.group(0)
    if "Keyboard2View" not in tag:
        continue
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
    if b:
        best = max(best, int(b.group(4)))
print(best)
PY
}

if [ "$(shown)" = "mInputShown=true" ]; then
  F="$OUT/ui-kbclip.xml"; dumpui "$F"
  VB=$(kbclip_view_bottom "$F")
  FB=$(ime_frame_bottom)
  SB=$(dispsz | cut -dx -f2)
  if [ -z "$VB" ] || [ "$VB" = "0" ] || [ -z "$FB" ] || [ "$FB" = "0" ]; then
    # Run 39 proved this branch necessary: the dump carried no keyboard-view node at all
    # (view=0 -- the helper prints 0 when the dump carries no Keyboard2View node), and the old
    # else printed a confident "fits its window" over nothing. A check that cannot see the view
    # reports that it cannot see it, and nothing more.
    info "7f stood down: view bottom '${VB:-none}', frame bottom '${FB:-none}' (shown=$(shown))"
    ann warning "KBCLIP skipped, no measurable pair: view='${VB:-none}' frame='${FB:-none}' screen='${SB:-?}'"
  elif [ "$VB" -gt "$((FB + 4))" ]; then
    ann warning "KBCLIP-DIAG view=$VB frame=$FB screen=${SB:-?} shown=$(shown)"
    fail "keyboard view bottom $VB overflows its window frame bottom $FB (screen ${SB:-?}): the last row is clipped"
  else
    pass "keyboard fits its window (view bottom $VB, window bottom $FB, screen ${SB:-?}, reserved below window $(( ${SB:-0} - ${FB:-0} ))px)"
    ann warning "KBCLIP-DIAG view=$VB frame=$FB screen=${SB:-?} shown=$(shown)"
  fi
else
  info "7f skipped: no keyboard up to measure (shown=$(shown))"
fi

# ThemeSettingsActivity hosts KeyboardPreviewHost -> a real Keyboard2View rendered with the active
# theme and layout: a direct visual check of the keyboard needing no IME connection at all.
adb shell am start -W -n "$PKG/$NS.ThemeSettingsActivity" > "$OUT/am-theme.log" 2>&1
sleep 6
shot 05-theme-preview
if printf '%s' "$(focus)" | grep -q "ThemeSettingsActivity"; then
  pass "ThemeSettingsActivity (KeyboardPreviewHost) foreground"
else
  info "ThemeSettingsActivity not foreground (non-exported; shell may be refused)"
fi

echo "== 8. crashes across the whole run =="
adb logcat -d -v brief > "$OUT/logcat.txt" 2>&1
FINALCRASH=$(crashln)
if [ -z "$FINALCRASH" ]; then
  pass "crash buffer empty ($(wc -l < "$OUT/logcat.txt") logcat lines captured)"
else
  fail "crash buffer NOT empty: $FINALCRASH"
fi

echo "== 9. clipboard subsystem (informational) =="
# run-as works because the debug build is debuggable. clipboard_history.db may legitimately not
# exist until the first copy, so this is reported, never failed.
adb shell run-as "$PKG" ls -la databases/ > "$OUT/app-databases.txt" 2>&1
info "databases: $(grep -oE '[a-z_]+\.db' "$OUT/app-databases.txt" 2>/dev/null | tr '\n' ' ')$(peek "$OUT/app-databases.txt" 80)"

write_summary
echo "SUMMARY $(cat "$OUT/summary.txt" 2>/dev/null)"

echo
if [ "$fails" -eq 0 ]; then
  echo "SMOKE RESULT: PASS (0 failures)"; ann notice "SMOKE RESULT: PASS (0 failures)"
else
  echo "SMOKE RESULT: FAIL ($fails)"; ann error "SMOKE RESULT: FAIL ($fails failures) -- each FAIL annotation above carries its own framework evidence"
fi
echo "Evidence in $OUT/: screenshots, dumpsys, ime logs, logcat, a11y dumps."
[ "$fails" -eq 0 ]
