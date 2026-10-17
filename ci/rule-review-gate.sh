#!/usr/bin/env bash
# AC7: the rule-review CI gate as a pipeline stage, for any CI tool (or your own machine).
# Reviews a change, prints the gate summary (pass/fail, verdict, finding counts per severity,
# where the details are), writes the reports, and exits non-zero when the gate fails, which
# fails the stage.
#
#   ci/rule-review-gate.sh                               # this checkout vs origin/main, like its PR
#   ci/rule-review-gate.sh --base main                   # ... vs another base
#   ci/rule-review-gate.sh --diff pr.diff --source DIR   # a given diff, with the files after it
#   options: --out DIR (default target/rule-review)  --title TEXT  --previous DIR (re-judge, AC6)
#
# Writes to the --out folder: summary.md (full report), result.json (machine-readable), state.json,
# and gate.env (KEY=value lines a CI tool can load). Exit code: 0 passed, 1 failed, 2 bad input.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
JAR="${RULE_REVIEW_JAR:-$root/target/code-review-bot-v3-3.0.0.jar}"
base="origin/main"; diff=""; source=""; out="target/rule-review"; title=""; previous=""
while [ $# -gt 0 ]; do
  case "$1" in
    --base)     base="$2"; shift 2 ;;
    --diff)     diff="$2"; shift 2 ;;
    --source)   source="$2"; shift 2 ;;
    --out)      out="$2"; shift 2 ;;
    --title)    title="$2"; shift 2 ;;
    --previous) previous="$2"; shift 2 ;;
    -h|--help)  sed -n '2,13p' "$0"; exit 0 ;;
    *)          echo "error: unknown option $1 (see --help)" >&2; exit 2 ;;
  esac
done

if [ ! -f "$JAR" ]; then
  echo "Building $JAR ..."
  (cd "$root" && mvn -q -B -DskipTests package)
fi
mkdir -p "$out"

if [ -z "$diff" ]; then
  # What a PR from this checkout would show: everything since it branched off the base, including
  # uncommitted edits, so the diff matches the files on disk.
  if ! git rev-parse --verify -q "$base" > /dev/null; then
    echo "error: unknown base '$base'. Fetch it first, e.g. git fetch origin main" >&2
    exit 2
  fi
  diff="$out/change.diff"
  git diff --no-color --no-ext-diff "$(git merge-base "$base" HEAD)" > "$diff"
  source="."
  label="changes since $base"
  title="${title:-$(git rev-parse --abbrev-ref HEAD) vs $base}"
  if [ ! -s "$diff" ]; then
    echo "rule-review gate: PASS (no changes since $base)"
    printf 'RULE_REVIEW_GATE=PASS\nRULE_REVIEW_DECISION=APPROVE\n' > "$out/gate.env"
    exit 0
  fi
else
  label="$diff"
fi

set +e
java -jar "$JAR" review --diff "$diff" ${source:+--source "$source"} ${title:+--title "$title"} \
     ${previous:+--previous "$previous"} --out "$out" > "$out/review.log" 2>&1
code=$?
set -e
if [ "$code" -eq 2 ] || [ ! -f "$out/result.json" ]; then
  grep -v '^Picked up JAVA_TOOL_OPTIONS' "$out/review.log" >&2 || true
  echo "rule-review gate: ERROR (the change could not be reviewed, see above)" >&2
  exit 2
fi

field() { grep -o "\"$1\" *: *\"[^\"]*\"" "$out/result.json" | head -1 | sed 's/.*: *"\(.*\)"$/\1/'; }
count() { grep -o "\"$1\" *: *[0-9]*" "$out/result.json" | head -1 | grep -o '[0-9]*$'; }
gate="$(field gate)"
decision="$(field decision)"
must_fix="$(grep -c '"number" *:' "$out/result.json" || true)"

cat > "$out/gate.env" <<EOF
RULE_REVIEW_GATE=$gate
RULE_REVIEW_DECISION=$decision
RULE_REVIEW_BLOCK=$(count BLOCK)
RULE_REVIEW_ERROR=$(count ERROR)
RULE_REVIEW_WARNING=$(count WARNING)
RULE_REVIEW_MUST_FIX=$must_fix
RULE_REVIEW_DETAILS=$out/summary.md
EOF

cat "$out/summary.md"
echo
echo "======================== rule-review gate ========================"
echo "Change:    $label ($(count filesReviewed) changed file(s) reviewed)"
echo "Gate:      $([ "$gate" = PASS ] && echo "PASS ✅" || echo "FAIL ❌")"
echo "Verdict:   $decision"
echo "Findings:  BLOCK $(count BLOCK) · ERROR $(count ERROR) · WARNING $(count WARNING)   (must fix: $must_fix)"
echo "Details:   $out/summary.md   full report: must-fix list, each finding with file:line and fix"
echo "           $out/result.json  machine-readable; $out/gate.env for CI variables"
echo "=================================================================="
exit "$code"
