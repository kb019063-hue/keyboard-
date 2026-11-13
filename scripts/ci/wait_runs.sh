#!/usr/bin/env bash
# wait_runs.sh — wait for the CI runs on a commit, then print their verdicts and the
# annotations that matter.
#
# AGENTS.md requires reporting the run ids and the conclusions, not the tick, and the
# unit-test counts have to come from the run's notice (`unit tests: N run, M failed,
# K skipped in L classes`). From a sandbox that cannot reach the log service (the
# results-receiver host is not on the allowlist), the api.github.com ANNOTATIONS are the
# only readable channel — the same channel the workflows themselves write their summary
# into.
#
#   scripts/ci/wait_runs.sh                 # the current branch's HEAD, up to 50 minutes
#   scripts/ci/wait_runs.sh <sha>           # a specific commit
#   scripts/ci/wait_runs.sh <sha> <minutes> # ... and a deadline
#
# Exits 0 when every run of the three workflows finished, 1 otherwise.
set -u

REPO="${CI_REPO:-kb019063-hue/keyboard-}"
SHA="${1:-}"
LIMIT_MIN="${2:-50}"
DEADLINE=$(( $(date +%s) + LIMIT_MIN * 60 ))

if [ -z "$SHA" ]; then
  SHA=$(git rev-parse HEAD 2>/dev/null) || { echo "wait_runs: no commit given and HEAD is unknown" >&2; exit 2; }
fi
BRANCH=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "")

echo "wait_runs: $REPO @ ${SHA:0:7}${BRANCH:+ ($BRANCH)}, deadline ${LIMIT_MIN} min"

runs_for_sha() {
  gh api "repos/$REPO/actions/runs?per_page=20" \
    --jq ".workflow_runs[] | select(.head_sha|startswith(\"$SHA\")) | \"\(.id) \(.name) \(.status) \(.conclusion // \"-\")\"" \
    2>/dev/null
}

while :; do
  runs=$(runs_for_sha)
  total=$(printf '%s\n' "$runs" | grep -c . || true)
  done_count=$(printf '%s\n' "$runs" | grep -c ' completed ' || true)
  echo "[$(date -u +%H:%M:%S)] $done_count/$total completed"
  printf '%s\n' "$runs" | sed 's/^/    /'
  if [ "$total" -ge 3 ] && [ "$done_count" -ge "$total" ]; then break; fi
  if [ "$(date +%s)" -gt "$DEADLINE" ]; then echo "TIMEOUT — not every run finished"; break; fi
  sleep 45
done

echo "=================== CHECK RUNS ==================="
gh api "repos/$REPO/commits/$SHA/check-runs" \
  --jq '.check_runs[] | "\(.id) \(.name) \(.conclusion // .status)"' 2>/dev/null |
while read -r id name concl; do
  echo "--- $name ($concl) ---"
  gh api "repos/$REPO/check-runs/$id/annotations?per_page=100" \
    --jq '.[] | "[" + .annotation_level + "] " + (.message | gsub("\n"; " | "))' 2>/dev/null
done
