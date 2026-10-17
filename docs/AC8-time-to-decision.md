# Time to decision and review clarity (AC8)

AC8 asks the team to compare the governance workflow with its manual review process, on the same or
comparable PRs, and to document an improvement in time-to-decision or review clarity. The bot provides the
measurements and the report. The manual side has to come from your own reviewers or from your repository's
history; nothing in this repository invents it.

| AC8 says | How it is covered |
|---|---|
| Given a baseline manual review process for the same or comparable sample PRs | **Same PRs:** reviewers time themselves on `samples/` and `samples-rejudge/` without the bot and record it in `docs/ac8/manual-baseline.csv`. **Comparable PRs:** `history` reads a repository's merged PRs: time to first human review, rounds of requested changes, time to merge |
| When the governance workflow is used instead | `benchmark` times the bot's verdict on the same samples with the same review code the server runs, and replays the fix-and-rejudge rounds (AC6) |
| A measurable or qualitative improvement in time-to-decision or review clarity can be reported | The report puts both side by side: time to decision, review rounds, and six clarity criteria (explicit decision, file and line, fix stated, severity and category, blocking kept apart from advisory, same input gives the same result) |
| The comparison is documented (quantitative preferred) | `benchmark --baseline … [--history …]` writes `ac8-report.md`. Commit it, or paste it under "Results" below |

## Running it

```bash
mvn -DskipTests package
java -jar target/code-review-bot-v3-3.0.0.jar benchmark                                   # bot side only
java -jar target/code-review-bot-v3-3.0.0.jar benchmark --baseline docs/ac8/manual-baseline.csv
java -jar target/code-review-bot-v3-3.0.0.jar history --repo OWNER/NAME --limit 30         # GITHUB_TOKEN in the environment
java -jar target/code-review-bot-v3-3.0.0.jar benchmark --baseline docs/ac8/manual-baseline.csv --history target/ac8/history.json
```

The output goes to `target/ac8/`: `ac8-report.md` (the comparison), `benchmark.json` (every measurement),
and from `history`, `history.json` and `pr-history.csv` (one row per PR).

## What is measured

| Metric | Bot | Manual baseline |
|---|---|---|
| Time to decision | In-process time from the submitted change to the verdict and findings: median and p90 of 20 runs after 3 warm-up runs. It leaves out network and JVM start-up, which are the same for any tool | Minutes from opening the change to writing down a merge decision (stopwatch) |
| Review rounds to approval | Submissions of the `samples-rejudge` PR until the bot approves it, with what was resolved and what was still open in each round | Rounds a reviewer needs for the same three versions until they would approve |
| Clarity | Counted from each finding: has a file and line, has a fix that differs from the description, has severity and category; the verdict is explicit; the must-fix list holds only blocking findings; 20 runs give identical reports | Counted from the reviewer's notes: issues written down, how many name the file and line, how many say what to change, and whether a merge decision was stated |
| History (comparable PRs) | Seconds from a PR being opened to the first `rule-review` success or failure on its first commit | Hours from a PR being opened to its first human review, number of "changes requested" reviews, hours to merge. Bot reviews (policy footer), `[bot]` accounts, the PR author's own reviews and pending reviews don't count |

## Recording the manual baseline

1. Pick at least two reviewers who haven't seen the bot's output for the samples.
2. For each of `01-clean-change-approve`, `02-warnings-request-changes` and `03-errors-block`: open the
   change (`samples/<name>/pr.diff` with the files in `head/`, or a test PR made with
   `scripts/push-sample-pr.sh`). Start a stopwatch and review it as you normally would against the team
   standards in the README ("Findings model"). Stop when you have written down a merge decision.
3. Count what you wrote down: issues, how many name the file and line, and how many say what to change.
4. For `samples-rejudge`, review `round1/` the same way. The facilitator then hands you `round2/` and
   `round3/`, as if the developer pushed fixes. Count the rounds until you would approve, and the total
   minutes.
5. Add one row per reviewer per sample to `docs/ac8/manual-baseline.csv`. Example (not real data):
   `03-errors-block,reviewer-a,12.5,BLOCK,3,2,1,1`.
6. Run `benchmark --baseline docs/ac8/manual-baseline.csv`, and keep the generated report as the AC8 evidence.

## Results

### Bot side (measured)

Measured on 2026-10-17 with `benchmark --runs 20` (Java 21, Linux, 4 CPUs). Timings vary by machine; the verdicts,
rounds and clarity counts don't.

### 1. Time to decision on the sample PRs

| Sample | Bot verdict | Bot time to decision (median / p90) | Manual time to decision (median) | Manual decisions |
|---|---|---|---|---|
| `01-clean-change-approve` | ✅ APPROVE | 5.15 ms / 6.35 ms | not recorded yet | – |
| `02-warnings-request-changes` | ⚠️ REQUEST_CHANGES | 5.69 ms / 6.25 ms | not recorded yet | – |
| `03-errors-block` | ⛔ BLOCK | 2.15 ms / 2.49 ms | not recorded yet | – |

Same change, same result: **yes**, every one of the 20 runs of each sample gave a byte-for-byte identical report.

> **No manual baseline yet.** Reviewers time themselves on the same samples and fill in `docs/ac8/manual-baseline.csv` (see `docs/AC8-time-to-decision.md`), then rerun `java -jar target/code-review-bot-v3-3.0.0.jar benchmark --baseline docs/ac8/manual-baseline.csv`.

### 2. Review rounds: fixing a blocked change (`samples-rejudge`)

| Submission | Bot verdict | ✅ Resolved | ❌ Still open | 🆕 New | Files re-evaluated | Bot time (median) |
|---|---|---|---|---|---|---|
| 1 | ⛔ BLOCK | – | – | 5 found | 2 of 2 | 3.45 ms |
| 2 | ⚠️ REQUEST_CHANGES | 3 | 2 | 1 | 1 of 2 | 2.28 ms |
| 3 | ✅ APPROVE | 3 | 0 | 0 | 2 of 2 | 2.91 ms |

Approved at submission 3. Each re-review listed exactly what was resolved and what was still open, and re-evaluated only the files that changed.

Manual baseline for the same scenario: not recorded yet (`review_rounds` of the `samples-rejudge` rows).

### 3. Review clarity

| Criterion | Bot (every sample review) | Manual baseline |
|---|---|---|
| States an explicit merge decision | 3 of 3 reviews (100%) | not recorded yet |
| Issues located by file and line | 6 of 6 issues (100%) | not recorded yet |
| Issues with a concrete fix | 6 of 6 issues (100%) | not recorded yet |
| Issues tagged with severity and category | 6 of 6 issues (100%) | not recorded |
| Blocking issues kept apart from advisory ones | yes: must-fix list vs other findings | not recorded |
| Same change gives the same result | yes (20 runs per sample) | not measured |

### Manual side

Not recorded yet. Fill in `docs/ac8/manual-baseline.csv` as described above and/or run `history` on a
repository with human-reviewed PRs, then regenerate the report. The empty "Manual" columns above fill in by
themselves.

### What the measurements already show (qualitative)

- **Faster identification of blocking issues:** on the first submission the bot names every blocking issue
  with its file, line and fix, in milliseconds, and lists them in a separate must-fix list.
- **Fewer, clearer review cycles:** each resubmission is answered with what was resolved, what is still open
  and what is new, re-checking only the files that changed. The developer never has to work out what's left.
- **Consistency:** the same change always gets the same verdict and findings, so a decision doesn't depend on
  who reviews it or when.
