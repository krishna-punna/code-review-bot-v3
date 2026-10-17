# Fix-and-rejudge sample (AC6)

One synthetic pull request, submitted three times as the developer works through the must-fix list.
The PR adds two files, `web/OrderController.java` and `service/ReportService.java`; each `roundN/`
folder holds both files as they are at that point.

| Round | What the developer did | Expected result |
|---|---|---|
| `round1` | First submission. The controller imports a repository, prints to `System.out`, calls `printStackTrace()` and uses field injection; the report service uses field injection | **BLOCK**: 3 errors must be fixed, 2 warnings |
| `round2` | Fixed the three errors in the controller, but kept one field injection and added a new one. Didn't touch the report service | **REQUEST_CHANGES**: 3 resolved, 2 still open, 1 new. Only the controller is re-evaluated; the report service's finding is carried over |
| `round3` | Switched both classes to constructor injection | **APPROVE**: the last 3 resolved |

Run all three rounds and check them: `scripts/rejudge-demo.sh`. By hand:

```bash
java -jar target/code-review-bot-v3-3.0.0.jar review --source samples-rejudge/round1 --out r1
java -jar target/code-review-bot-v3-3.0.0.jar review --source samples-rejudge/round2 --previous r1 --out r2
java -jar target/code-review-bot-v3-3.0.0.jar review --source samples-rejudge/round3 --previous r2 --out r3
```

The rounds are reviewed as whole files (`--source` without `--diff`), as for a PR that adds them.
They live outside `samples/` because they don't have the one-diff layout the AC1 samples share.
