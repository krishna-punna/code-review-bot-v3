# code-review-bot-v3

A deterministic, rule-based pull request reviewer. There's no AI involved: the same code always produces the same findings.

## How it works

```
GitHub PR event ──► POST /webhook ──► PullRequestReviewer
                     (HMAC check)          │
                                           ├─ list changed files + patches
                                           ├─ DiffParser: added lines → diff positions
                                           ├─ fetch file at head SHA → FileContext (lazy AST)
                                           ├─ RuleEngine: run enabled rules, keep changed lines only
                                           ├─ post ONE review with inline comments
                                           └─ commit status "rule-review": success / failure
```

Every review ends in exactly one verdict: **APPROVE**, **REQUEST_CHANGES** or **BLOCK**. The `rule-review` status turns red on BLOCK or REQUEST_CHANGES (configurable). Make `rule-review` a required check in branch protection to block merges.

## Automatic polling (no webhook setup needed)

`PullRequestPoller` runs on a schedule. It lists the open PRs in each configured repo and sends every PR that needs a review to the same `/webhook` endpoint, signed with your secret. The webhook code is unchanged; the poller is just a second caller.

A PR is sent only if all of these are true:
- It is open and not a draft.
- The poller hasn't already sent its current head commit.
- Its head commit has no finished `rule-review` status yet. This means PRs already reviewed by the real webhook, or before a restart, are skipped.

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
| `rule` | `Rule` interface, `Violation`, `Severity`, `FileContext`, `RuleEngine` |
| `rule.impl` | Sample rules: `no-system-out`, `no-field-injection`, `max-method-length` |
| `github` | `WebhookController`, `PullRequestReviewer`, `DiffParser` |
| `config` | `ReviewProperties`, `PollerProperties` (yml binding), `GitHubConfig` |
| `poller` | `PullRequestPoller`: scheduled job that finds PRs and calls `/webhook` |

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

```bash
export GITHUB_TOKEN=...            # fine-grained PAT: Pull requests RW, Contents R, Commit statuses RW
export GITHUB_WEBHOOK_SECRET=...   # same value as in the repo's webhook settings
mvn test
mvn spring-boot:run
```

In the repo, go to **Settings → Webhooks** and add `https://<host>/webhook` with content type `application/json`, the same secret, and the "Pull requests" event. For local testing, expose port 8080 with a tunnel such as ngrok or smee.io.

## Known limits and next steps

- **Line-anchored rules**: `max-method-length` reports on the method's first line, so it only fires if the PR touches the signature. To catch edits deeper in a method, give `Violation` a line range and match on any changed line in it.
- **Re-pushes**: every push posts a new review. To avoid noise, store a fingerprint of `ruleId + path + line text` and skip findings already posted.
- **Scale**: the bot fetches files one at a time. For big PRs, cache by SHA or shallow-clone the head commit instead.
- **Auth**: a PAT is fine to start. A GitHub App gives per-org install, higher rate limits, and Check Runs with richer annotations.
