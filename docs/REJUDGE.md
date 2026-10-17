# Fix and re-review (AC6)

When a blocked change is fixed and submitted again, the bot doesn't start from scratch. It compares the
new submission with its previous review, re-evaluates only what changed, reports each earlier finding as
resolved or still open, and issues an updated verdict.

| AC6 says | What the bot does | Where it's tested |
|---|---|---|
| Given a previously blocked change with a must-fix list, when the code is updated and resubmitted | Every review keeps a small **state**: the key of each reviewed file and every finding. A resubmission is compared with it | `RejudgeSamplesTest`, `RejudgeCliTest` |
| The system re-evaluates only the affected / newly changed findings, where feasible | Files whose key is unchanged keep their earlier findings and aren't reviewed again; on GitHub they aren't even fetched. Only changed files go through the rules | `partialFix_reevaluatesOnlyTheChangedFile…`, `pushedFix_isRejudged_onlyTheChangedFileIsFetched…` |
| Resolved findings are marked as resolved, unresolved ones remain flagged | Each earlier finding is listed as ✅ resolved or ❌ still open, and findings that weren't there before as 🆕 new. Still-open findings stay in the must-fix list. On GitHub, the earlier inline comment of a resolved finding gets a "✅ Resolved in …" reply | `RejudgeServiceTest`, `stillOpenAndNewFindings_makeUpTheUpdatedMustFixList`, `PullRequestRejudgeTest` |
| An updated verdict is issued reflecting the current state | The verdict is decided again from the findings open now, and the CI status follows it (`⛔ BLOCK → ⚠️ REQUEST_CHANGES → ✅ APPROVE`) | `verdictFollowsTheFindingsStillOpen`, `everythingFixed_postsTheResolvedReview_andTurnsTheGateGreen` |

## How it works

1. **State.** After a review the bot keeps, for each changed file, a **key**: a hash of exactly what the
   rules saw. For the CLI and API that's the file's path, content and changed lines. For a GitHub PR it's
   the file's patch against the base, which with the base commit determines the content. The state also
   holds every finding and the verdict.
2. **Re-evaluate only what changed.** On the next submission, a file with the same key would produce the
   same findings, so they're carried over without running the rules. Every other file is reviewed again.
3. **Match findings.** Earlier and current findings are paired by **fingerprint**: rule + file + text of
   the offending line, without the line number, so code moving up or down doesn't count as a change.
   Unpaired earlier findings are **resolved**, paired ones are **still open** (at their current
   location), unpaired current ones are **new**.
4. **Decide again.** The usual verdict policy runs on the current findings, giving the updated verdict and
   must-fix list.

Everything is re-evaluated, even unchanged files, when carrying findings over might be wrong:
the rule configuration changed (rules on or off, severities, parameters, excluded paths), the PR's base
moved, or the state came from another version of the bot. The summary says which.

## Command line

`review --out DIR` now also writes `state.json`. Pass that folder, or the file itself, as `--previous` next time:

```bash
java -jar target/code-review-bot-v3-3.0.0.jar review --source samples-rejudge/round1 --out r1    # ⛔ BLOCK
java -jar target/code-review-bot-v3-3.0.0.jar review --source samples-rejudge/round2 --previous r1 --out r2
java -jar target/code-review-bot-v3-3.0.0.jar review --source samples-rejudge/round3 --previous r2 --out r3
```

With `--previous` the summary starts with a **Since the previous review** section, and `--out` adds
`rejudge.json` (resolved / unresolved / new findings, re-evaluated and carried-over files). `result.json`
keeps the same shape as a plain review. `--diff` works with `--previous` as usual.

## REST API

`POST /api/reviews/rejudge` takes the code in the same shape as `POST /api/reviews`, plus the previous
review's state:

```json
{ "change": { "title": "...", "diff": "...", "files": { "path": "content" } },
  "previous": { ...the "state" of the previous response, or null for a first review... } }
```

The response holds `report` (the same shape as `POST /api/reviews`), `rejudge` (null on a first review),
`state` (send it back as `previous` next time) and `summaryMarkdown`. To build a request from files on
disk: `java -jar … request --source DIR --previous PREVIOUS > body.json`. `PREVIOUS` can be a `state.json`,
an `--out` folder, or a saved response of this endpoint. `POST /api/reviews` is unchanged.

## GitHub

Nothing to set up: when a PR the bot reviewed gets a new commit (webhook or poller), it is re-judged.

- The review posted on the new commit starts with **🔁 Since the previous review**: the verdict change,
  the resolved / still open / new counts and lists, and which files were re-evaluated.
- Inline comments are posted only for **new** findings. Still-open findings keep their earlier comment, and
  each resolved one gets a "✅ Resolved in `abc1234`" reply.
- The `rule-review` status reflects the updated verdict, so the gate turns green once everything is fixed.
- If a commit changes nothing the bot reports on, no new review is posted. The status is still set.

The state lives in an HTML comment at the end of the bot's own review, which GitHub doesn't display, so
there's no database and it survives restarts. **Only reviews written by the bot's own account (the token's
user) are trusted.** A state pasted into someone else's review is ignored, and that PR is reviewed in full.

## Demo

```bash
scripts/rejudge-demo.sh                               # all three rounds through the CLI, checked
scripts/rejudge-demo.sh --api http://localhost:8080   # the same through a running server
```

Live on GitHub: open a PR in a test repository with the two files of `samples-rejudge/round1/`. Then commit
the `round2` versions to the same branch, then the `round3` versions, and watch the bot's reviews and the
`rule-review` check after each push.

## Limits

- **Fingerprints follow the line's text.** Editing the offending line itself, for example changing a
  `System.out.println` message, reports the old finding as resolved and a new one. Moving code doesn't.
- **Big states are skipped.** If the state wouldn't fit in GitHub's 65,536-character review limit, it's left
  out and the next commit is reviewed in full.
- **The CLI and API trust the state they're given**, the same way they trust the code they're given. The
  merge gate on GitHub only uses states from the bot's own reviews.
