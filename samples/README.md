# Sample PRs

Synthetic pull requests for demos and end-to-end tests. They run without GitHub or production code.

| Sample | What the PR does | Expected verdict |
|---|---|---|
| `01-clean-change-approve` | Adds a clean service, and edits a legacy file that already breaks the rules on lines it doesn't touch | **APPROVE**, no findings |
| `02-warnings-request-changes` | Adds a service with field injection and a 52-line method | **REQUEST_CHANGES**, 2 must-fix |
| `03-errors-block` | Adds a controller that imports a repository, prints to `System.out` and calls `printStackTrace()` | **BLOCK**, 3 must-fix, 1 other finding |

Each sample folder holds:

- `pr.diff`: the PR as `git diff` output. This is what gets submitted for review.
- `head/`: the files after the PR. They give the reviewer each touched file's full content.
- `base/`: the files before the PR, where the PR changes existing files (only `01` has one).
- `expected.properties`: the verdict, must-fix count and findings the bot must produce.

Review one:

```bash
java -jar target/code-review-bot-v3-3.0.0.jar review --diff samples/03-errors-block/pr.diff --source samples/03-errors-block/head
```

Run them all and check every verdict: `scripts/demo.sh`.

After editing a sample's `base/` or `head/`, regenerate its diff with `scripts/make-sample-diff.sh samples/<name>`,
then update `expected.properties`. The sample Java files only have to parse, not compile, and they
aren't part of the Maven build.
