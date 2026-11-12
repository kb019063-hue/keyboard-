#!/usr/bin/env bash
# Says what a Gradle test run is doing when it has run far longer than it should.
#
#   test-wedge-watchdog.sh <out-dir> <label> <minutes> [<minutes> ...]
#
# Start it in the background next to `./gradlew ... test...` and kill it when Gradle returns.
# At each mark (minutes since it was started) it looks for the test worker JVM and writes one
# report file into <out-dir>. Nothing is printed: a later step turns the files into annotations,
# because annotations are the only thing about a run that can be read from the development
# sandbox (AGENTS.md, "Before you touch CI"), and a line printed from the background could be
# cut in two by Gradle's own output.
#
# Why this exists. Twice a whole-suite run has sat inside `testDebugUnitTest` until its bound
# and produced nothing: 2026-11-01 (90 minutes) and 2026-11-12 (run 43242985447: 45 minutes, on
# a commit whose code had passed the same suite in under nine an hour earlier). Gradle writes its
# JUnit XML when the task ends, so a task that is cut off leaves no XML at all -- not "everything
# but the class that stalled", as build.gradle once hoped. Both times there was nothing to read:
# not which class, not whether it was blocked, spinning, or out of memory. A thread dump answers
# all three.
#
# A report holds, for each test worker JVM: how long it has run and how busy it is; disk, memory
# and the size of the log (a test that prints in a loop fills the disk); the collector's
# occupancy (a heap that is full looks like a stall); any deadlock the JVM itself found; the
# stack of the thread that runs the tests, which names the test class and method; and the other
# threads that are inside this project's code.
#
# It needs a JDK on PATH (`jstack`, `jstat`), which `actions/setup-java` provides, and it must
# never be the reason a job fails: every command is allowed to fail and the script always exits 0.

set -u

out_dir="${1:?out-dir}"; label="${2:?label}"; shift 2
[ "$#" -gt 0 ] || { echo "usage: $0 <out-dir> <label> <minutes>..." >&2; exit 0; }
mkdir -p "$out_dir" 2>/dev/null || exit 0

# Killed while asleep: take the sleep down too, so nothing is left holding the step open.
nap_pid=""
trap '[ -n "$nap_pid" ] && kill "$nap_pid" 2>/dev/null; exit 0' TERM INT

started=$(date +%s)
# Tests replace these: the seconds in a "minute", and how long one tool may take.
minute="${WATCHDOG_MINUTE_SECONDS:-60}"
tool_timeout="${WATCHDOG_TOOL_TIMEOUT:-90}"

# The blocks of a thread dump are separated by blank lines; a thread's block starts with its
# quoted name. "main" selects the blocks whose first line matches <regex>; "others" selects the
# blocks that are inside this project's code and are not one of those.
thread_block() {   # <dump file> main|others <regex> <max blocks> <max lines each>
  awk -v which="$2" -v re="$3" -v max="$4" -v lines="$5" 'BEGIN { RS = ""; FS = "\n" }
    (which == "main" && $1 ~ re) || (which == "others" && $1 !~ re && $0 ~ /tribixbite\./) {
      if (++seen > max) exit
      n = (NF < lines) ? NF : lines
      for (i = 1; i <= n; i++) print $i
      if (NF > lines) print "\t... " (NF - lines) " more frames"
      print ""
    }' "$1" 2>/dev/null
}

dump_threads() {   # <pid> <file>; leaves the dump in the file, or the reason there is none
  timeout "$tool_timeout" jstack -l "$1" > "$2" 2> "$2.err" && [ -s "$2" ] && return 0
  timeout "$tool_timeout" jcmd "$1" Thread.print -l > "$2" 2>> "$2.err" && [ -s "$2" ] && return 0
  return 1
}

report_jvm() {     # <pid> <what it is> <report file> <regex: first line of its main threads>
  local pid="$1" what="$2" file="$3" main="$4" dump
  dump="$(mktemp)"
  {
    echo "$what (pid $pid): $(ps -o etimes=,pcpu=,rss=,nlwp= -p "$pid" 2>/dev/null \
      | awk '{ printf "up %d s, cpu %s%%, resident %d MB, %s threads", $1, $2, $3 / 1024, $4 }')"
    echo "gc (percent full: survivors, eden, old, metaspace; then collections and seconds):"
    timeout "$tool_timeout" jstat -gcutil "$pid" 2>&1 | head -2 | sed 's/^/  /'
    if dump_threads "$pid" "$dump"; then
      if grep -q "Java-level deadlock" "$dump"; then
        echo "DEADLOCK, as reported by the JVM:"
        sed -n '/Java-level deadlock/,$p' "$dump" | head -60
      fi
      echo "thread states: $(grep -o 'java.lang.Thread.State: [A-Z_]*' "$dump" | awk '{ print $2 }' \
        | sort | uniq -c | awk '{ printf "%s%s %s", sep, $2, $1; sep = ", " }')"
      echo "== the thread that runs the work =="
      thread_block "$dump" main "$main" 3 70
      echo "== other threads inside this project's code =="
      thread_block "$dump" others "$main" 8 25
    else
      echo "no thread dump: $(head -3 "$dump.err" 2>/dev/null | tr '\n' ' ')"
    fi
  } >> "$file" 2>&1
  rm -f "$dump" "$dump.err"
}

for mark in "$@"; do
  wait_s=$(( started + mark * minute - $(date +%s) ))
  if [ "$wait_s" -gt 0 ]; then
    sleep "$wait_s" & nap_pid=$!
    wait "$nap_pid" 2>/dev/null
    nap_pid=""
  fi

  file="$out_dir/$label-${mark}min.txt"
  {
    echo "$label: still running after $mark minutes."
    echo "disk: $(df -h . 2>/dev/null | awk 'NR == 2 { print $4 " free of " $2 }');" \
         "memory: $(free -m 2>/dev/null | awk 'NR == 2 { print $7 " MB available of " $2 }');" \
         "log: $(cat ./*-output.log 2>/dev/null | wc -c) bytes"
  } > "$file" 2>&1

  workers=$(pgrep -f 'Gradle Test Executor' 2>/dev/null || true)
  if [ -n "$workers" ]; then
    for pid in $workers; do
      # "Test worker", or "Test worker @coroutine#7" under kotlinx-coroutines-test.
      report_jvm "$pid" "test worker" "$file" '^"Test worker'
    done
  else
    # No test JVM: the time is going somewhere else (resolving, compiling, starting the worker).
    {
      echo "No test worker JVM is running. Java processes:"
      ps -eo pid=,etimes=,pcpu=,rss=,args= 2>/dev/null | grep -i '[j]ava' | cut -c1-160 | head -6
    } >> "$file" 2>&1
    for pid in $(pgrep -f 'GradleDaemon' 2>/dev/null | head -1); do
      report_jvm "$pid" "Gradle daemon" "$file" '^"(Daemon worker|Execution worker)'
    done
  fi
done
exit 0
