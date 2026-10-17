#!/usr/bin/env bash
# Regenerates samples/<name>/pr.diff from the sample's base/ (before) and head/ (after) folders,
# using a throwaway git repo, so the diff is real `git diff` output. Run it after editing a sample.
# It only compares the files already in base/ and head/; it doesn't create them.
#
#   scripts/make-sample-diff.sh samples/03-errors-block
set -euo pipefail

sample="${1:?usage: $0 samples/<name>}"
sample="$(cd "$sample" && pwd)"
[ -d "$sample/head" ] || { echo "error: no head/ folder in $sample" >&2; exit 2; }

# Stop with a reason instead of writing an empty pr.diff.
if [ -z "$(find "$sample/head" -type f | head -1)" ]; then
  echo "error: $sample/head has no files. Put the files as they are after the change there first." >&2
  exit 2
fi
empty="$(find "$sample/head" -type f -empty)"
if [ -n "$empty" ]; then
  echo "error: these files are empty (0 bytes). Paste their content and save them first:" >&2
  echo "$empty" | sed 's/^/  /' >&2
  exit 2
fi

work="$(mktemp -d)"
out="$(mktemp)"
trap 'rm -rf "$work" "$out"' EXIT

git -C "$work" init -q
git -C "$work" config user.email "samples@example.invalid"
git -C "$work" config user.name "samples"
if [ -d "$sample/base" ]; then cp -R "$sample/base/." "$work/"; fi
git -C "$work" add -A
git -C "$work" commit -q --allow-empty -m base

cp -R "$sample/head/." "$work/"
git -C "$work" add -A
git -C "$work" diff --cached --no-color --no-ext-diff > "$out"
if [ ! -s "$out" ]; then
  echo "error: base/ and head/ are identical, so there is no change to write to pr.diff." >&2
  echo "       Make sure head/ holds the changed version of the files, and that you saved it." >&2
  exit 2
fi

cp "$out" "$sample/pr.diff"
echo "wrote $sample/pr.diff:"
git -C "$work" diff --cached --stat --no-color | sed 's/^/  /'
