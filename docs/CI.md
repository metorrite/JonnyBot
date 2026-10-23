# CI (`.github/workflows/build-test.yml`)

`ci` is the one check to require: green when every job passed or was skipped, red when any failed or was cancelled. Secret scanning
(`secret-scan.yml`, gitleaks) is its own workflow and always runs.

| Run                                    | `build` (Maven verify with the startup check and the real-SQL tests) |
| -------------------------------------- | -------------------------------------------------------------------- |
| Pull request into `dev`                | Runs.                                                                |
| Push to `dev` (every merge)            | Runs.                                                                |
| Pull request into `master` (a release) | Skipped when dev already proved the same tree (below), else runs.    |
| Push to `master` (after the merge)     | Skipped when dev already proved the same tree, else runs.            |

## The release skip

The `decide` job runs `.github/scripts/release_skip.py`. On a release it skips `build` only when the commit's tree
(`git rev-parse HEAD^{tree}`; for a pull request, the test merge commit GitHub builds) is **identical** to the tree of a `dev` commit
whose own push run of this workflow concluded **success** with `build` passing. Same tree means the same files, this workflow
included, so that run already tested exactly this code.

Everything else builds: a hotfix straight to master, a dev that was never green, a tree that differs (master has a commit dev lacks),
or any GitHub API error (fail closed). If a dev run with the same tree is still running, it waits for it (up to 20 minutes).

The `ci` job prints why, e.g. `skip heavy: tree 1a2b3c4d5e6f matches dev commit 0f9e8d7c6b5a, CI run <url> green`; the run summary
has the same line. The decision tests (`python3 -m unittest discover -s .github/scripts -p "test_*.py"`) run in `decide`. The same
script is used by runeclans and runeclans-hub.

A release is then: merge to dev, wait for the dev push run to go green, open the dev→master PR; its CI takes well under a minute.
