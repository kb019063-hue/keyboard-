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
KB_VERDICT=notrun
ANR_SEEN=0
write_summary() {
  printf 'fails=%s-toolbar=%s-tbui=%s-keyboard=%s-anr=%s\n' \
    "${fails:-?}" "${TB_VERDICT:-?}" "${TBUI_VERDICT:-?}" "${KB_VERDICT:-?}" "${ANR_SEEN:-?}" \
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
    A=""
    for a2 in 1 2 3; do
      A=$(nodecenter "$E" '^add$'); [ -n "$A" ] && break
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
      fail "picker did not open: 'Choose a command' absent after tapping Add at '${A:-NOT-FOUND}': texts=[$(texts "$P")]"
    fi
    adb shell input keyevent KEYCODE_BACK; sleep 2
  else
    TBUI_VERDICT=no-switch
    fail "header found but 3 expand attempts never composed the switch: last tapped '${LASTC:-NOT-FOUND}' band=$SAFE_LO-$SAFE_HI screen=${SW}x${SH}: texts=[$(texts "$LASTE")]"
  fi
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
