#!/usr/bin/env bash
# AC6 demo: submits the synthetic PR in samples-rejudge/ three times, as the developer fixes it.
# Each submission is re-judged against the previous review: what was resolved, what is still open,
# what is new, and the updated verdict. Checks every round against samples-rejudge/expected.properties.
# No GitHub needed.
#
#   scripts/rejudge-demo.sh                               # through the CLI
#   scripts/rejudge-demo.sh --api http://localhost:8080   # through POST /api/reviews/rejudge of a running server
#
# Outputs land in target/rejudge-demo/roundN/. Exit code 0 if every round matched, 1 otherwise.
set -euo pipefail
cd "$(dirname "$0")/.."

JAR=target/code-review-bot-v3-3.0.0.jar
SAMPLE=samples-rejudge
OUT=target/rejudge-demo
API=""
if [ "${1:-}" = "--api" ]; then API="${2:-http://localhost:8080}"; fi

if [ ! -f "$JAR" ]; then
  echo "Building $JAR ..."
  mvn -q -B -DskipTests package
fi

prop() { sed -n "s/^$1=//p" "$SAMPLE/expected.properties" | tr -d '\r'; }   # tr: CRLF checkouts on Windows
json_field() { grep -o "\"$2\" *: *\"[^\"]*\"" "$1" | head -1 | sed 's/.*: *"\(.*\)"$/\1/'; }
# "resolved open new": the row under the re-review table's 3-column "|---|---|---|" line,
# in summary.md (a line of its own) or in a JSON response (after an escaped \n)
counts() {
  { grep -A1 -x '|---|---|---|' "$1" | tail -1
    grep -o '|---|---|---|\\n| [0-9]* | [0-9]* | [0-9]* |' "$1" | sed 's/.*\\n//'
  } | grep -o '| [0-9]* | [0-9]* | [0-9]* |' | head -1 | tr -d '|' | xargs
}

title="$(prop title)"
rm -rf "$OUT"
mkdir -p "$OUT"
previous=""
failed=0
rows=()
for round in 1 2 3; do
  out="$OUT/round$round"
  mkdir -p "$out"
  echo
  echo "=================================================================="
  echo " Round $round: submitting $SAMPLE/round$round${previous:+, re-judged against round $((round - 1))}"
  echo "=================================================================="
  if [ -z "$API" ]; then
    # Exit code 1 only means the gate failed, which rounds 1 and 2 are meant to do.
    java -jar "$JAR" review --source "$SAMPLE/round$round" --title "$title" --out "$out" ${previous:+--previous "$previous"} || true
    previous="$out"
    decision="$(json_field "$out/result.json" decision)"
    summary="$out/summary.md"
  else
    java -jar "$JAR" request --source "$SAMPLE/round$round" --title "$title" ${previous:+--previous "$previous"} > "$out/request.json"
    if [ -z "$previous" ]; then printf '{"change":%s}' "$(cat "$out/request.json")" > "$out/request.tmp" && mv "$out/request.tmp" "$out/request.json"; fi
    curl -sS -X POST "$API/api/reviews/rejudge" -H 'Content-Type: application/json' \
         --data-binary @"$out/request.json" -o "$out/response.json" || true
    previous="$out/response.json"
    decision="$(json_field "$out/response.json" decision)"
    summary="$out/response.json"
    echo "decision: $decision   previous: $(json_field "$out/response.json" previousDecision)"
    echo "reason:   $(json_field "$out/response.json" reason)"
  fi

  expected="$(prop "round$round.decision")"
  want="-"; got="-"
  if [ "$round" -gt 1 ]; then
    want="$(prop "round$round.resolved") $(prop "round$round.unresolved") $(prop "round$round.new")"
    got="$(counts "$summary" || true)"
  fi
  if [ "$decision" = "$expected" ] && [ "$got" = "$want" ]; then result="ok"; else result="MISMATCH"; failed=1; fi
  rows+=("$(printf '%-8s %-16s %-16s %-14s %-14s %s' "round$round" "$expected" "${decision:-none}" "$want" "${got:-none}" "$result")")
done

echo
echo "=================================================================="
printf '%-8s %-16s %-16s %-14s %-14s %s\n' "Round" "Expected" "Actual" "Expected" "Actual" "Result"
printf '%-8s %-16s %-16s %-14s %-14s %s\n' "" "verdict" "verdict" "res/open/new" "res/open/new" ""
printf '%s\n' "${rows[@]}"
echo "Full outputs: $OUT/roundN/"
exit "$failed"
