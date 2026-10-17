#!/usr/bin/env bash
# Regenerates samples/<name>/pr.diff from the sample's base/ (before) and head/ (after) folders,
# using a throwaway git repo, so the diff is real `git diff` output. Run it after editing a sample.
#
#   scripts/make-sample-diff.sh samples/03-errors-block
set -euo pipefail

sample="${1:?usage: $0 samples/<name>}"
sample="$(cd "$sample" && pwd)"
[ -d "$sample/head" ] || { echo "no head/ folder in $sample" >&2; exit 2; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

git -C "$work" init -q
git -C "$work" config user.email "samples@example.invalid"
git -C "$work" config user.name "samples"
if [ -d "$sample/base" ]; then cp -R "$sample/base/." "$work/"; fi
git -C "$work" add -A
git -C "$work" commit -q --allow-empty -m base

cp -R "$sample/head/." "$work/"
git -C "$work" add -A
git -C "$work" diff --cached --no-color --no-ext-diff > "$sample/pr.diff"
echo "wrote $sample/pr.diff"
