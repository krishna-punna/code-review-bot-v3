# CI gate visibility (AC7)

Every evaluated change produces a pass/fail gate, plus a summary showing the verdict, the finding
counts per severity, and where the details are. It works in two places: on GitHub, where the gate is the
`rule-review` commit status, and in any CI pipeline, where the gate is a script stage. There are no
GitHub Actions workflows.

| AC7 says | On GitHub | In a pipeline (`ci/rule-review-gate.sh`) |
|---|---|---|
| Given a PR/change has been evaluated, when the CI pipeline (real or simulated) runs | The webhook or poller reviews each new commit of a PR | The stage reviews the checkout's changes against the base, or a given diff |
| A pass/fail gate status is produced | The `rule-review` status: ✅ success or ❌ failure (`review.verdict.fail-gate-on`) | Exit code 0 (pass) or 1 (fail), and `RULE_REVIEW_GATE=PASS/FAIL` in `gate.env` |
| A summary comment/output shows the verdict and finding counts by severity | A summary review on every evaluated commit, clean ones included. It starts with `CI gate rule-review: ❌ FAIL · ⛔ BLOCK · BLOCK 0 · ERROR 3 · WARNING 1` | The same line, as a block at the end of the stage's log |
| …and a link/reference to details | The status links to that summary review; the summary links to the inline comments ("details per line") | `Details:` points to `summary.md` (full report) and `result.json` |

## On GitHub

Nothing to set up beyond the bot itself (see "Run" in the README):

- Each review the bot posts starts with the **gate line**: pass/fail, the verdict, and the count for every
  severity, zeros included. Then come the must-fix list, the counts table, and on a re-review (AC6) what
  was resolved.
- **Clean commits get a summary too**, so the check always has something to link to. To post nothing on
  clean commits (only the green status), set `review.ci.summary-on-approve: false`.
- The `rule-review` **status links to the summary review**. Its description repeats the verdict and counts,
  for example `BLOCK · 3 must-fix · 3 ERROR, 1 WARNING`. When a re-review finds nothing new and posts nothing,
  the status links to the pull request.
- To **block merging**, open the repository's **Settings > Branches** (or **Rules**), add a rule for the
  main branch, turn on **Require status checks to pass**, and choose `rule-review`.

## In any CI pipeline

`ci/rule-review-gate.sh` is one pipeline stage. Run it on the checked-out PR branch, with the base branch
fetched:

```bash
ci/rule-review-gate.sh                       # this checkout vs origin/main
ci/rule-review-gate.sh --base main           # vs another base
ci/rule-review-gate.sh --diff pr.diff --source checkout/   # a diff you already have
ci/rule-review-gate.sh --previous last-run/  # re-judge against the previous run's output (AC6)
```

The end of the stage's log:

```
======================== rule-review gate ========================
Change:    samples/03-errors-block/pr.diff (1 changed file(s) reviewed)
Gate:      FAIL ❌
Verdict:   BLOCK
Findings:  BLOCK 0 · ERROR 3 · WARNING 1   (must fix: 3)
Details:   target/rule-review/summary.md   full report: must-fix list, each finding with file:line and fix
           target/rule-review/result.json  machine-readable; target/rule-review/gate.env for CI variables
==================================================================
```

| Output | What it's for |
|---|---|
| Exit code | `0` passed, `1` failed (the stage fails), `2` the change couldn't be reviewed |
| `summary.md` | The full report, the same as the bot posts on GitHub. Keep it as a build artifact |
| `result.json` | Everything as JSON (verdict, gate, counts, must-fix list, findings) |
| `gate.env` | `RULE_REVIEW_GATE`, `RULE_REVIEW_DECISION`, `RULE_REVIEW_BLOCK` / `ERROR` / `WARNING`, `RULE_REVIEW_MUST_FIX`, `RULE_REVIEW_DETAILS`, for CI tools that read `KEY=value` files |
| `state.json` | Pass the folder to `--previous` on the next run to re-judge (AC6) |

To use it in a CI tool: check out the PR branch, fetch the base, build the jar (`mvn -DskipTests package`),
run the script, fail the stage on a non-zero exit, and archive the output folder. The script uses only bash,
git and Java (Git Bash on Windows).

## Simulated pipeline (demo)

`ci/simulate-pipeline.sh` runs a three-stage pipeline locally: build and test, the gate, then merge. Stage 3
only runs if the gate passes:

```bash
ci/simulate-pipeline.sh --skip-tests --diff samples/03-errors-block/pr.diff --source samples/03-errors-block/head
#   -> Stage 2 gate FAIL ❌ (BLOCK · ERROR 3 · WARNING 1) -> "Pipeline FAILED at stage 2", exit 1
ci/simulate-pipeline.sh --skip-tests --diff samples/01-clean-change-approve/pr.diff --source samples/01-clean-change-approve/head
#   -> Stage 2 gate PASS ✅ -> Stage 3 merge -> "Pipeline PASSED", exit 0
ci/simulate-pipeline.sh                      # the real thing: full test run, then gate this branch vs origin/main
```
