# AC1 demo walkthrough

A script for showing, live or in a recording, that a sample PR goes from code change to analysis
to verdict with no manual step. Parts 1 and 2 need only Java 17+ and Maven. Part 3 adds a real GitHub PR.

| AC1 says | Where the demo shows it |
|---|---|
| A sample PR diff or changed-file set is submitted to the system | Part 1 (CLI) and Part 2 (REST API) submit `samples/*/pr.diff`; Part 3 opens a real PR |
| The governance workflow runs | The same `ReviewService` runs every time: rules on the changed lines, then the verdict |
| It produces APPROVE, REQUEST_CHANGES or BLOCK with supporting findings | One sample per verdict, each with its findings, locations and fixes |
| Without manual intervention | One command per sample (Parts 1, 2); on GitHub the webhook or poller triggers the review |
| The full flow is demonstrable | This walkthrough; `scripts/demo.sh` checks every verdict automatically |

## 0. Build and test (1 minute)

```bash
mvn verify
```

Point out the end-to-end tests: `SamplesEndToEndTest` posts every sample to a running server, and
`ReviewCliTest` runs the CLI. Both check the verdicts listed in each sample's `expected.properties`.

## 1. Sample PRs through the CLI (3 minutes)

```bash
scripts/demo.sh
```

For each sample, show the diff first (`cat samples/<name>/pr.diff`), then the bot's output:

1. **`01-clean-change-approve` → APPROVE.** The PR edits `LegacyReportJob`, which already prints to
   `System.out`. Those lines aren't part of the PR, so they aren't flagged. Only the new code is judged.
2. **`02-warnings-request-changes` → REQUEST_CHANGES.** Two warnings (field injection, a 52-line
   method). Both are in the must-fix list, and each item says what to change.
3. **`03-errors-block` → BLOCK.** Three errors (controller imports a repository, `System.out`,
   `printStackTrace()`) make up the must-fix list. The field-injection warning is listed under
   "Other findings" because the errors decided the verdict.

The table at the end compares each verdict with the expected one. Full outputs are in
`target/demo/<sample>/summary.md` and `result.json`.

To review a change of your own: `git diff main...my-branch > my.diff` while `my-branch` is checked out, then
`java -jar target/code-review-bot-v3-3.0.0.jar review --diff my.diff --source .`

## 2. The same samples through the running server (2 minutes)

```bash
GITHUB_WEBHOOK_SECRET=demo-secret mvn spring-boot:run      # terminal 1
scripts/demo.sh --api http://localhost:8080                # terminal 2
```

Each sample is posted to `POST /api/reviews`. Show one call by hand:

```bash
java -jar target/code-review-bot-v3-3.0.0.jar request --diff samples/03-errors-block/pr.diff \
  --source samples/03-errors-block/head --title "Expose order lookup endpoint" > /tmp/req.json
curl -s -X POST localhost:8080/api/reviews -H 'Content-Type: application/json' --data-binary @/tmp/req.json
```

The response holds `decision`, `gate`, `reason`, the `mustFix` list, every finding with
`location`, `description` and `remediation`, and the same Markdown summary the bot posts on GitHub.

## 3. A real pull request on GitHub (5 minutes)

You need a throwaway repo, a fine-grained token for it (Pull requests RW, Contents R, Commit statuses RW)
and a clone of it.

```bash
export GITHUB_TOKEN=...                 # never put it in a file in this repo
export GITHUB_WEBHOOK_SECRET=demo-secret
export POLLER_ENABLED=true POLLER_REPOS=<owner>/<sandbox> POLLER_CRON="0 * * * * *"   # check every minute
mvn spring-boot:run
```

The poller means no webhook or tunnel is needed. To use the webhook instead, see "Run" in the README.

```bash
scripts/push-sample-pr.sh samples/03-errors-block ~/src/<sandbox>
```

Open the PR from the printed link. Within a minute the bot sets the `rule-review` status to
pending and then failure. It posts one review with the verdict and must-fix list, plus inline comments
on lines 4, 15, 20 and 24 of `OrderController.java`. Push another commit to the branch and the bot reviews
the new head commit automatically.
