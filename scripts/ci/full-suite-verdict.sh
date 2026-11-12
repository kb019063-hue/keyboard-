#!/usr/bin/env bash
# The verdict of the whole unit-test suite (.github/workflows/full-test-suite.yml).
#
# The two test steps there never fail by themselves, so that the report step always runs and
# says WHY. That makes this script the only thing standing between a broken suite and a green
# check, and it has been wrong twice:
#
#   * Until 2026-11-10 it did not exist: a test class that did not compile produced no results
#     and the check was green.
#   * Until 2026-11-12 it looked only for "no results at all". A failing test was left to a step
#     that asked whether the test step had failed -- which it cannot, by design. So the check
#     was green with failing tests: runs 41766203725, 41774492055, 41828565272, 41840073362,
#     42558434610 and 42559739283 each report failed tests in their annotations and concluded
#     `success`.
#
# Run from the repository root after the report step. Reads what the steps before it left:
#
#   build/unit-test-gradle-exit.txt     gradle_exit=N of the whole-suite invocation
#   build/heavy-test-gradle-exit.txt    gradle_exit=N of the memory-hungry classes' invocation
#   build/full-suite-xml/, build/test-results/heavy/    their JUnit XML
#   build/unit-test-failed.txt          failures + errors, counted from that XML by the report
#   unit-test-output.log, heavy-test-output.log
#
# Green needs all of: both invocations exited 0, there is XML, and the count is 0. Anything
# missing is a failure, never a pass: "nothing was verified" must not look like "all passed".

set -u

recorded_exit() {   # <file>: the recorded exit code, or "missing"
  local v=""
  [ -f "$1" ] && v=$(sed -n 's/^gradle_exit=//p' "$1" | tail -1)
  echo "${v:-missing}"
}

code=$(recorded_exit build/unit-test-gradle-exit.txt)
heavy=$(recorded_exit build/heavy-test-gradle-exit.txt)
xml=$(find build/full-suite-xml build/test-results/heavy -name '*.xml' 2>/dev/null | wc -l | tr -d ' ')
failed=$(tr -d ' \n' < build/unit-test-failed.txt 2>/dev/null || true)
timed_out=no
grep -qs "Timeout has been exceeded" unit-test-output.log heavy-test-output.log && timed_out=yes

echo "whole suite exit=$code, memory-hungry exit=$heavy, junit xml files=$xml," \
     "failed tests=${failed:-not counted}, ran into the time bound=$timed_out"

verdict=0
fail() { echo "::error::$1"; verdict=1; }

if [ "$timed_out" = "yes" ]; then
  # Gradle writes the JUnit XML when the task ends, so a task that is cut off usually leaves none.
  fail "a test run was still going at its time bound (build.gradle) and was stopped: not every test ran. The 'Long test run' annotations say what it was doing."
elif [ "$xml" = "0" ]; then
  if [ "$code" = "missing" ]; then
    fail "the unit-test step left no exit code and no results: it did not run. Nothing was verified."
  elif [ "$code" != "0" ]; then
    fail "the unit-test task produced NO results - the test sources did not compile. Nothing was verified."
  else
    fail "the unit-test task produced no JUnit XML at all. Nothing was verified."
  fi
else
  if [ "$code" != "0" ]; then
    fail "the whole-suite run did not succeed (Gradle exit $code): see the failing tests in the annotations above."
  fi
  if [ "$heavy" != "0" ]; then
    fail "the memory-hungry classes' run did not succeed (Gradle exit $heavy)."
  fi
  if [ -z "$failed" ]; then
    fail "the report step left no count of failed tests, so the results were not read."
  elif [ "$failed" != "0" ]; then
    fail "$failed unit test(s) failed."
  fi
fi

exit "$verdict"
