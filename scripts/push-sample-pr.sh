#!/usr/bin/env bash
# Live GitHub demo: turns a sample into a real pull request on a sandbox repo the bot watches.
#
#   scripts/push-sample-pr.sh samples/03-errors-block ~/src/my-sandbox-repo
#
# The sandbox clone's default branch first gets the sample's base/ files (if it has any), then a
# new branch sample/<name> gets its head/ files and is pushed. Open the PR from the printed link;
# the bot reviews it on the webhook event, or on the next poll.
# Use a throwaway repo only: this commits and pushes to it.
set -euo pipefail

usage="usage: $0 samples/<name> <path-to-sandbox-clone>"
sample="$(cd "${1:?$usage}" && pwd)"
clone="$(cd "${2:?$usage}" && pwd)"
name="$(basename "$sample")"
title="$(sed -n 's/^title=//p' "$sample/expected.properties" | tr -d '\r')"
branch="sample/$name"

if ! git -C "$clone" diff --quiet || ! git -C "$clone" diff --cached --quiet; then
  echo "$clone has uncommitted changes; commit or stash them first" >&2
  exit 2
fi
default="$(git -C "$clone" symbolic-ref --short refs/remotes/origin/HEAD 2>/dev/null | sed 's#^origin/##' || true)"
default="${default:-main}"

git -C "$clone" checkout -q "$default"
git -C "$clone" pull -q --ff-only
if [ -d "$sample/base" ]; then
  cp -R "$sample/base/." "$clone/"
  git -C "$clone" add -A
  if ! git -C "$clone" diff --cached --quiet; then
    git -C "$clone" commit -q -m "Base files for sample $name"
    git -C "$clone" push -q origin "$default"
  fi
fi

git -C "$clone" checkout -q -B "$branch"
cp -R "$sample/head/." "$clone/"
git -C "$clone" add -A
git -C "$clone" commit -q -m "$title"
git -C "$clone" push -q -f -u origin "$branch"

url="$(git -C "$clone" remote get-url origin)"
echo "Pushed $branch. Open a PR from it into $default, titled \"$title\":"
if [[ "$url" =~ github\.com[:/](.+)$ ]]; then
  echo "  https://github.com/${BASH_REMATCH[1]%.git}/compare/$default...$branch?expand=1"
fi
