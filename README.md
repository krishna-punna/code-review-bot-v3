# code-review-bot-v3

A deterministic, rule-based pull request reviewer. There's no AI involved: the same code always produces the same findings.

## How it works

```
GitHub PR event ─► POST /webhook (HMAC check) ─► PullRequestReviewer ─┐  list changed files + patches,
  (PullRequestPoller is a second, scheduled caller of /webhook)       │  fetch each file at head SHA
                                                                      ├─► ChangeSet ─► ReviewService
POST /api/reviews ─┐                                                  │                 ├─ RuleEngine: enabled rules, changed lines only
CLI `review` ──────┴─► ReviewRequest: unified diff + file contents ───┘                 └─ VerdictEngine: one verdict + must-fix list

On GitHub:     ONE review with inline comments, and commit status "rule-review": success / failure
API and CLI:   JSON report + Markdown summary; the CLI exits 0 if the gate passed, 1 if it failed
```

Every way in runs the same `ReviewService`, so a sample diff gets the verdict it would get on a real PR.
See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the diagrams.

Every review ends in exactly one verdict: **APPROVE**, **REQUEST_CHANGES** or **BLOCK**. The `rule-review` status turns red on BLOCK or REQUEST_CHANGES (configurable). Make `rule-review` a required check in branch protection to block merges.

## Review a change without GitHub

Needs Java 17+ and Maven. `samples/` holds synthetic PRs, one per verdict ([samples/README.md](samples/README.md)).

```bash
mvn -DskipTests package
scripts/demo.sh                     # reviews every sample and checks its expected verdict
```

Review any diff with the CLI. `--source` is where the new version of the files is, because a diff
alone doesn't hold the full files the rules need. Files the diff adds can be rebuilt from it, so `--source` is optional for those.

```bash
git diff main...my-branch > my.diff          # with my-branch checked out
java -jar target/code-review-bot-v3-3.0.0.jar review --diff my.diff --source . --out review-out
```

It prints the summary, writes `review-out/result.json` and `summary.md`, and exits 0 if the gate passed,
1 if it failed, or 2 for bad input. Leave out `--diff` to review every file under `--source` in full.

Or post the change to a running server:

```bash
java -jar target/code-review-bot-v3-3.0.0.jar request --diff my.diff --source . > req.json
curl -X POST localhost:8080/api/reviews -H 'Content-Type: application/json' --data-binary @req.json
```

The request body is `{"title": "...", "diff": "<git diff output>", "files": {"path": "full new content"}}`.
The endpoint only reads what is posted and never calls GitHub. It has no authentication, so don't expose it publicly.

A walkthrough for demos, including a live GitHub PR, is in [docs/DEMO.md](docs/DEMO.md).

## Fix and re-review (AC6)

Resubmit a fixed change and the bot re-judges it against its previous review: only files that changed
are reviewed again, each earlier finding is reported ✅ resolved or ❌ still open (plus 🆕 new ones), and
the verdict is updated. On GitHub this happens by itself on every new commit to a PR the bot reviewed.
Elsewhere, pass the previous review along:

```bash
java -jar target/code-review-bot-v3-3.0.0.jar review --source samples-rejudge/round1 --out r1
java -jar target/code-review-bot-v3-3.0.0.jar review --source samples-rejudge/round2 --previous r1 --out r2
scripts/rejudge-demo.sh            # all rounds of the sample, checked
```

The REST API has `POST /api/reviews/rejudge` for the same. Details: [docs/REJUDGE.md](docs/REJUDGE.md).

## CI gate (AC7)

Every evaluated change gets a pass/fail gate and a summary with the verdict, the finding counts per severity,
and a link to the details. On GitHub, the `rule-review` status links to the bot's summary review, which every
evaluated commit gets, clean ones included (`review.ci.summary-on-approve`). In any other CI, run the gate as a
stage; it exits 1 when the gate fails:

```bash
ci/rule-review-gate.sh                         # this checkout vs origin/main; writes target/rule-review/
ci/simulate-pipeline.sh --skip-tests --diff samples/03-errors-block/pr.diff --source samples/03-errors-block/head
```

Details, including how to make `rule-review` a required check: [docs/CI-GATE.md](docs/CI-GATE.md).

## Automatic polling (no webhook setup needed)

`PullRequestPoller` runs on a schedule. It lists the open PRs in each configured repo and sends every PR that needs a review to the same `/webhook` endpoint, signed with your secret. The webhook code is unchanged; the poller is just a second caller.

The poller is off by default. It still needs `GITHUB_WEBHOOK_SECRET` (any value) so it can sign its requests, even if no GitHub webhook is set up.

A PR is sent only if all of these are true:
- It is open and not a draft.
- The poller hasn't already sent its current head commit.
- Its head commit has no `success` or `failure` `rule-review` status yet. This means PRs already reviewed by the real webhook, or before a restart, are skipped. A review that crashed leaves an `error` status, and that commit is sent again after a restart.

A new push changes the head commit, so the PR is picked up again on the next run.

```yaml
review:
  poller:
    enabled: true
    repos: "owner/repo1,owner/repo2"
    cron: "0 0/5 * * * *"      # default: every 5 minutes
```

Or set them through environment variables: `POLLER_ENABLED`, `POLLER_REPOS`, `POLLER_CRON`.

Cron examples: every minute is `0 * * * * *`, every 15 minutes is `0 0/15 * * * *`, and weekdays 9am to 7pm every 10 minutes is `0 0/10 9-19 * * MON-FRI`.

## Project layout

| Package | What's in it |
|---|---|
| `review` | `ReviewService` (the workflow every entry point uses), `ChangeSet`, `ReviewRequest`, `UnifiedDiff`, `DiffParser`, `ReviewReport` |
| `rule` | `Rule` interface, `Finding`, `Severity`, `Category`, `FileContext`, `RuleEngine` |
| `rule.impl` | Sample rules: `no-system-out`, `no-field-injection`, `max-method-length`, `layer-dependency` |
| `verdict` | `VerdictEngine`, `Verdict`, `VerdictFormatter`, `VerdictProperties` |
| `github` | `WebhookController`, `PullRequestReviewer` |
| `poller` | `PullRequestPoller`: scheduled job that finds PRs and calls `/webhook` |
| `api` | `ReviewApiController`: `POST /api/reviews` |
| `cli` | `ReviewCli`: the `review` and `request` commands |
| `config` | `ReviewProperties`, `PollerProperties` (yml binding), `GitHubConfig` |
| `ci` | `CiProperties` (`review.ci`), `GateSummary`: the gate line at the top of each review (AC7) |
| `rejudge` | `RejudgeService` (fix-and-rejudge, AC6), `ReviewState`, `Rejudge`, `RejudgeFormatter`; `POST /api/reviews/rejudge` is `api.RejudgeApiController` |

Outside `src/`: `samples/` (synthetic PRs), `samples-rejudge/` (one PR fixed over three rounds, AC6), `scripts/` (demo and sample helpers), `ci/` (the CI gate stage and a simulated pipeline, AC7), `docs/` (architecture, demo walkthrough, fix-and-rejudge).

## Findings model

Every finding carries:

| Field | Example |
|---|---|
| `severity` | `WARNING`, `ERROR` or `BLOCK` (least to most severe) |
| `category` | `QUALITY`, `SECURITY` or `ARCHITECTURE` |
| `ruleId` | `layer-dependency` |
| `path`, `line` | `src/main/java/com/acme/web/OrderController.java:4` |
| `description` | what is wrong |
| `remediation` | what to change to fix it (required, can't be blank) |
| `fingerprint` | hash of rule + path + line text; survives code moving up or down |

| Rule | Category | Default severity |
|---|---|---|
| `no-system-out` | Quality | ERROR |
| `max-method-length` | Quality | WARNING |
| `no-field-injection` | Architecture | WARNING |
| `layer-dependency` | Architecture | ERROR |

## Merge verdict

`VerdictEngine` turns the findings into one verdict. It's a pure function: the same findings, in any order, always give the same result.

| Check (in order) | Verdict |
|---|---|
| Any finding at a `block-on` severity (default `BLOCK`, `ERROR`) | **BLOCK** |
| Otherwise, any finding at a `request-changes-on` severity (default `WARNING`) | **REQUEST_CHANGES** |
| Otherwise | **APPROVE** |

- **Traceable:** the verdict lists the findings that drove it (rule and location) plus the policy that was applied.
- **Must-fix list:** contains only the driving findings, most severe first. Each item states the action, the reason, the location and the rule. For a BLOCK verdict, warnings appear under "Other findings", not in the must-fix list.
- **Configurable** in `review.verdict` (`block-on`, `request-changes-on`, `fail-gate-on`). A severity in neither list is advisory. Overlapping lists stop the app at startup.

The bot posts the verdict as a COMMENT review. GitHub doesn't let a token APPROVE or REQUEST_CHANGES on its owner's own PRs, so the CI status is what enforces the verdict.

## Adding a rule

Create a `@Component` that implements `Rule`. It's picked up automatically.

```java
@Component
public class NoTodoRule implements Rule {
    public String id() { return "no-todo"; }
    public Category category() { return Category.QUALITY; }
    public Severity defaultSeverity() { return Severity.WARNING; }
    public String remediation() { return "Do the work now, or replace the TODO with a ticket reference (e.g. `// JIRA-123`)."; }
    public boolean appliesTo(FileContext f) { return true; }   // any file type

    public List<Finding> check(FileContext f, Severity sev) {
        List<Finding> out = new ArrayList<>();
        for (int i = 0; i < f.lines().size(); i++)
            if (f.lines().get(i).contains("TODO"))
                out.add(finding(f, i + 1, sev, "Unresolved TODO left in the code."));
        return out;
    }
}
```

Then you can tune it in `application.yml` under `review.rules.no-todo` (`enabled`, `severity`, `params`). This doesn't require a code change.

Rules report findings on any line. The engine drops findings on lines the PR didn't add, so legacy code doesn't flood new PRs.

## Run

Needs Java 17+ and Maven.

```bash
export GITHUB_TOKEN=...            # fine-grained PAT: Pull requests RW, Contents R, Commit statuses RW
export GITHUB_WEBHOOK_SECRET=...   # required; same value as in the repo's webhook settings
mvn verify                         # unit and end-to-end tests, then the jar
mvn spring-boot:run
```

In the repo, go to **Settings → Webhooks** and add `https://<host>/webhook` with content type `application/json`, the same secret, and the "Pull requests" event. For local testing, expose port 8080 with a tunnel such as ngrok or smee.io.

`/webhook` rejects every request (401) while no secret is set. Keep the token and secret in the environment, never in `application.yml`.

## Known limits and next steps

- **Line-anchored rules**: `max-method-length` reports on the method's first line, so it only fires if the PR touches the signature. To catch edits deeper in a method, give `Finding` a line range and match on any changed line in it.
- **Re-pushes**: handled by fix-and-rejudge (AC6). A new commit gets a review only if something changed since the previous one, and inline comments only for new findings.
- **Scale**: the bot fetches files one at a time. For big PRs, cache by SHA or shallow-clone the head commit instead.
- **Auth**: a PAT is fine to start. A GitHub App gives per-org install, higher rate limits, and Check Runs with richer annotations.
