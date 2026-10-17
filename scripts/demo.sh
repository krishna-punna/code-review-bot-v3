#!/usr/bin/env bash
# AC1 demo: submits every synthetic PR in samples/ to the review workflow, prints what the bot
# decided, and checks each verdict against the sample's expected.properties. No GitHub needed.
#
#   scripts/demo.sh                               # through the CLI, no server
#   scripts/demo.sh --api http://localhost:8080   # through POST /api/reviews of a running server
#
# Outputs land in target/demo/<sample>/ (result.json, summary.md). Exit code 0 if every sample
# got its expected verdict, 1 otherwise.
set -euo pipefail
cd "$(dirname "$0")/.."

JAR=target/code-review-bot-v3-3.0.0.jar
API=""
if [ "${1:-}" = "--api" ]; then API="${2:-http://localhost:8080}"; fi

if [ ! -f "$JAR" ]; then
  echo "Building $JAR ..."
  mvn -q -B -DskipTests package
fi

prop() { sed -n "s/^$2=//p" "$1/expected.properties"; }
json_field() { grep -o "\"$2\" *: *\"[^\"]*\"" "$1" | head -1 | sed 's/.*: *"\(.*\)"$/\1/'; }

results=()
failed=0
for dir in samples/*/; do
  dir="${dir%/}"
  name="$(basename "$dir")"
  title="$(prop "$dir" title)"
  expected="$(prop "$dir" decision)"
  out="target/demo/$name"
  mkdir -p "$out"
  rm -f "$out/result.json" "$out/summary.md"

  echo
  echo "=================================================================="
  echo " $name: \"$title\""
  echo " submitting $dir/pr.diff ($(grep -c '^diff --git' "$dir/pr.diff") file(s) changed)"
  echo "=================================================================="
  if [ -z "$API" ]; then
    # Exit code 1 only means the gate failed, which some samples are meant to do.
    java -jar "$JAR" review --diff "$dir/pr.diff" --source "$dir/head" --title "$title" --out "$out" || true
  else
    java -jar "$JAR" request --diff "$dir/pr.diff" --source "$dir/head" --title "$title" > "$out/request.json"
    curl -sS -X POST "$API/api/reviews" -H 'Content-Type: application/json' \
         --data-binary @"$out/request.json" -o "$out/result.json" || true
    echo "decision: $(json_field "$out/result.json" decision)   gate: $(json_field "$out/result.json" gate)"
    echo "reason:   $(json_field "$out/result.json" reason)"
  fi

  actual="$( [ -f "$out/result.json" ] && json_field "$out/result.json" decision || true)"
  if [ "$actual" = "$expected" ]; then verdict="ok"; else verdict="MISMATCH"; failed=1; fi
  results+=("$(printf '%-30s %-16s %-16s %s' "$name" "$expected" "${actual:-none}" "$verdict")")
done

echo
echo "=================================================================="
printf '%-30s %-16s %-16s %s\n' "Sample" "Expected" "Actual" "Result"
printf '%s\n' "${results[@]}"
echo "Full outputs: target/demo/<sample>/result.json and summary.md"
exit "$failed"
