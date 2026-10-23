#!/usr/bin/env bash
# Run section 7d of scripts/ci/emulator-smoke.sh against a fake adb, across every scenario in
# scenarios.py, and check each one produces exactly the verdict it must. Also re-runs the pre-fix
# shape of section 7b's "Add" hunt to prove the shape test can still detect that bug.
#
#   scripts/ci/offline/run.sh            # all scenarios
#   scripts/ci/offline/run.sh run30      # one scenario, with its full output
#
# No emulator, no Gradle, no network: about twenty seconds. Run it after ANY edit to 7b/7d, because
# a CI cycle costs fifteen minutes and its logs cannot be read from the dev sandbox.

set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)
SMOKE="$ROOT/scripts/ci/emulator-smoke.sh"
WORK=${NFOFF_DIR:-/tmp/nfoff}
export NFOFF_DIR="$WORK"

[ -f "$SMOKE" ] || { echo "cannot find $SMOKE" >&2; exit 2; }
rm -rf "$WORK"; mkdir -p "$WORK/bin" "$WORK/out"

# --- the fake adb, and a sleep that does not waste the twenty seconds this suite should take ------
cat > "$WORK/bin/adb" <<EOF
#!/bin/sh
exec python3 "$HERE/fakeadb.py" "\$@"
EOF
printf '#!/bin/sh\nexit 0\n' > "$WORK/bin/sleep"
chmod +x "$WORK/bin/adb" "$WORK/bin/sleep"

# --- build the harness out of the REAL script: its preamble (helpers, verdict trackers, annotation
# --- writers), the three dump helpers 7b defines, and section 7d verbatim ------------------------
python3 - "$SMOKE" "$WORK/harness.sh" <<'PY'
import sys
src = open(sys.argv[1]).read().split("\n")

def find(pred, start=0):
    for i in range(start, len(src)):
        if pred(src[i]):
            return i
    raise SystemExit("marker not found")

pre_end = find(lambda l: l.startswith("# --- locate the x86_64 split"))
h_start = find(lambda l: l.startswith("scrollup() {"))
h_end = find(lambda l: l.startswith("texts() {"), h_start)
nf_start = find(lambda l: l.startswith('echo "== 7d.'))
nf_end = find(lambda l: l.startswith("# ThemeSettingsActivity hosts KeyboardPreviewHost"), nf_start)

glue = ["", 'PKG=tribixbite.cleverkeys.debug', 'SW=1080', 'SH=2400',
        'OUT="%s/out"' % __import__("os").environ["NFOFF_DIR"], 'mkdir -p "$OUT"', ""]
tail = ["", 'write_summary', 'echo "SUMMARY $(cat "$OUT/summary.txt")"',
        'echo "HARNESS fails=$fails NF_VERDICT=$NF_VERDICT VOICE_VERDICT=$VOICE_VERDICT"']
out = src[:pre_end] + src[h_start:h_end + 1] + glue + src[nf_start:nf_end - 1] + tail
open(sys.argv[2], "w").write("\n".join(out) + "\n")
PY
[ -f "$WORK/harness.sh" ] || { echo "harness build failed" >&2; exit 2; }
bash -n "$WORK/harness.sh" || { echo "harness does not parse" >&2; exit 2; }

# --- one scenario --------------------------------------------------------------------------------
verdict_of() {  # $1 = captured output
  local out="$1" fails nf frame voice warn
  fails=$(printf '%s' "$out" | sed -n 's/^SUMMARY fails=\([0-9]*\).*/\1/p' | head -1)
  nf=$(printf '%s' "$out" | sed -n 's/.*-nf=\([a-z]*\)-.*/\1/p' | head -1)
  voice=$(printf '%s' "$out" | sed -n 's/.*VOICE_VERDICT=\([a-z]*\).*/\1/p' | head -1)
  frame=none
  printf '%s' "$out" | grep -q "PASS  floating narrowed the input-method WINDOW" && frame=pass
  printf '%s' "$out" | grep -q "FAIL  floating left the input-method window" && frame=fail
  # Four warnings, not two: 7e added VOICE-DIAG plus its own skip warnings, and a scenario's
  # required substring can be the third one emitted.
  warn=$(printf '%s' "$out" | grep '^::warning::' | head -4 | tr '\n' ' ')
  printf '%s|%s|%s|%s|%s' "${fails:-?}" "${nf:-?}" "$frame" "${voice:-?}" "$warn"
}

run_one() {  # $1 = scenario
  local sc="$1" want got out rc=0
  rm -rf "$WORK/out"; mkdir -p "$WORK/out"
  python3 "$HERE/scenarios.py" write "$sc" > /dev/null || return 2
  want=$(python3 "$HERE/scenarios.py" expect "$sc")
  out=$(PATH="$WORK/bin:$PATH" bash "$WORK/harness.sh" 2>&1)
  got=$(verdict_of "$out")
  local wf wn wframe wvoice wwarn gf gn gframe gvoice gwarn
  IFS=' ' read -r wf wn wframe wvoice wwarn <<< "$want"
  IFS='|' read -r gf gn gframe gvoice gwarn <<< "$got"
  [ "$gf" = "$wf" ] || { echo "    fails: want $wf got $gf"; rc=1; }
  [ "$gn" = "$wn" ] || { echo "    nf verdict: want $wn got $gn"; rc=1; }
  [ "$gframe" = "$wframe" ] || { echo "    frame assertion: want $wframe got $gframe"; rc=1; }
  [ "$gvoice" = "$wvoice" ] || { echo "    voice verdict: want $wvoice got $gvoice"; rc=1; }
  if [ -n "$wwarn" ]; then
    printf '%s' "$gwarn" | grep -q "$wwarn" || { echo "    warning: wanted substring '$wwarn', got '$gwarn'"; rc=1; }
  fi
  if [ "$rc" -eq 0 ]; then
    printf '  ok    %-12s fails=%s nf=%s frame=%s voice=%s\n' "$sc" "$gf" "$gn" "$gframe" "$gvoice"
  else
    printf '  FAIL  %-12s fails=%s nf=%s frame=%s voice=%s\n' "$sc" "$gf" "$gn" "$gframe" "$gvoice"
    printf '%s\n' "$out" | grep -E '^  (PASS|FAIL)|^::(warning|error)|^SUMMARY' | sed 's/^/      /' | cut -c1-200
  fi
  return $rc
}

# --- section 7b's Add hunt: every dump the loop takes must be tested -----------------------------
addloop_test() {
  local loop old rc=0
  loop=$(sed -n '/^    A=""$/,/^    done$/p' "$SMOKE")
  [ -n "$loop" ] || { echo "  FAIL  could not extract 7b's Add hunt from $SMOKE"; return 1; }
  old=$(cat "$HERE/addloop-old.txt")
  probe() {  # $1 = loop text; succeeds when the hunt finds an Add that only appears after 3 scrolls
    (
      OUT="$WORK/out"; E="$OUT/expanded.xml"
      nodecenter() { case "$1" in *ui-tb-add-3.xml) printf '540 900' ;; *) printf '' ;; esac; }
      scrollup() { :; }
      dumpui() { : > "$1"; }
      eval "$1"
      [ -n "$A" ]
    )
  }
  if probe "$loop"; then
    echo "  ok    7b-add-hunt  finds an Add that only appears on the fourth dump"
  else
    echo "  FAIL  7b-add-hunt  still misses the last dump it takes (run 39789396340's off-by-one)"
    rc=1
  fi
  if probe "$old"; then
    echo "  FAIL  7b-add-hunt  the shape test no longer detects the pre-fix loop, so it proves nothing"
    rc=1
  else
    echo "  ok    7b-add-hunt  the pre-fix loop shape still fails it (the test can detect the bug)"
  fi
  return $rc
}

echo "offline harness: $SMOKE"
echo "  work dir $WORK"
bad=0
addloop_test || bad=1
if [ $# -gt 0 ]; then
  for sc in "$@"; do
    run_one "$sc" || bad=1
  done
else
  for sc in $(python3 "$HERE/scenarios.py" names); do
    run_one "$sc" || bad=1
  done
fi
echo
if [ "$bad" -eq 0 ]; then
  echo "OFFLINE RESULT: PASS (every scenario produced the verdict it must)"
else
  echo "OFFLINE RESULT: FAIL"
fi
exit "$bad"
