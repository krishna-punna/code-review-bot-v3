#!/usr/bin/env bash
# AC7 demo: a minimal CI pipeline with the rule-review gate as a stage, so you can watch the gate
# pass or stop a change without any CI server.
#
#   ci/simulate-pipeline.sh                                  # this checkout vs origin/main
#   ci/simulate-pipeline.sh --diff samples/03-errors-block/pr.diff --source samples/03-errors-block/head
#   ci/simulate-pipeline.sh --skip-tests ...                 # skip stage 1's test run (faster demo)
#
# Any other options are passed to ci/rule-review-gate.sh. Exit code: that of the first failing stage.
set -uo pipefail
cd "$(dirname "$0")/.."

skip_tests=false
args=()
for arg in "$@"; do
  if [ "$arg" = "--skip-tests" ]; then skip_tests=true; else args+=("$arg"); fi
done

stage() { echo; echo "──── Stage $1 ────────────────────────────────────────────"; }

stage "1/3: build and test"
if $skip_tests; then
  mvn -q -B -DskipTests package || { echo "Stage 1 FAILED: build"; exit 1; }
  echo "built (tests skipped)"
else
  mvn -q -B verify || { echo "Stage 1 FAILED: build or tests"; exit 1; }
  echo "built, all tests passed"
fi

stage "2/3: rule-review gate"
ci/rule-review-gate.sh ${args[@]+"${args[@]}"}
gate=$?
if [ "$gate" -ne 0 ]; then
  echo
  echo "Pipeline FAILED at stage 2: the rule-review gate $([ "$gate" -eq 1 ] && echo "did not pass" || echo "could not run")."
  echo "Stage 3 (merge) skipped. Fix the must-fix items listed above and run the pipeline again."
  exit "$gate"
fi

stage "3/3: merge"
echo "Gate passed: the change may be merged."
echo
echo "Pipeline PASSED."
