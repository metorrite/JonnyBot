# Bug reports

What a good bug report holds, and how to get from one to the rows and log lines that explain it.

## What to put in a report

| Field | What to put |
|---|---|
| What's wrong | One line. |
| Earlier work it came from | The PR or issue it relates to, if there is one. |
| Area | The piece it is in (clan site, bot dashboard, hub, bot, plugin, integration, infra). |
| Where | `live` (the live bot or website), `dev` (the dev bot), or `local` (localhost). |
| How bad | Blocks people from using it / wrong result or data / annoying but works / looks wrong. |
| When | When it happened, in UTC (or with the time zone). |
| How often | Every time / sometimes / once so far. |
| Page, command or channel | The URL path, slash command or channel. |
| Who | The kind of account and its role (e.g. `server owner (admin)`, `alt account (member)`). Never a real Discord id in a public report. |
| Steps | One step per line, enough to make it happen again. |
| Expected | What should have happened. |
| Actual | What happened instead. |
| Dev snapshot id | The `snap-…` id from the dev button or `/dev snapshot`. |
| Links | Screenshots, Discord messages, URLs; one per line. |

Steps and the snapshot id matter most: they let whoever picks it up find the exact state. A report without steps is still useful, but it waits until someone can reproduce it.

## Secrets in bug reports

Dev snapshots, logs, screenshots and report text stay redacted: no tokens, DB URLs, `INTERNAL_API_SECRET`, plugin tokens or OAuth keys, and no `.env` or run-configuration contents. If a report needs a config value, name the key, never the value. If you find a real secret in a report, snapshot or log, stop and tell the repository owner (see "Secrets" in [ECOSYSTEM.md](ECOSYSTEM.md)).

## How to triage a bug

1. **Read the report** and anything it links to.
2. **Reproduce first.** Follow the steps on the stated surface. If there is a snapshot id, load that snapshot and reproduce from it, not from memory of the live state. Write down what you saw.
   - Cannot reproduce: say what you tried and what you need, and stop.
3. **Find the root cause.** Trace it in the code and the data. Write the cause in one or two sentences, not only the symptom.
4. **Write a failing test first.** A unit test, or for SQL a throwaway JUnit against the local dev database. Run it and check that it fails for the reported reason.
5. **Fix it.** The smallest change that makes the test pass, on one branch off `origin/dev`, one PR into `dev`. Website and bot sides both, if the surface rule in ECOSYSTEM.md says they both apply.
6. **Check the fix.** Run the test suite, and the reproduction steps again on the dev bot or localhost where you can. Say in the PR what you actually checked. Never merge to `master`.

If the root cause is in a different area, say so in the PR and link the work it depends on rather than fixing it inside this PR.

## From a bug report to the rows that matter

The tools: `tools/snapshot_inspect.py` (read a snapshot zip), `tools/db_query.py` (read-only SQL against the local dev database), the dev bot's internal API through `tools/dev_api.py` (GET only). All are read-only by construction; none needs a secret on the command line.

1. **Get the snapshot.** A snapshot id looks like `snap-20261018-143005-a3f9`. The bot owner has the zip (`jb-snapshot-<id>.zip` from the owner-only Snapshot button, or `<id>.zip` from the dashboard's Snapshots page). Put it outside the repository; never commit one. Snapshots are kept 14 days. No snapshot: reproduce from the steps and stop at step 4 if you cannot.
2. **Read it.** `python tools/snapshot_inspect.py <zip>` prints who took it and when (UTC), the server and focus, bot and site commits, bot uptime and database time, RuneMetrics pacer state, which features have settings, the warnings and errors from the 30 minutes before, and a **timeline** that merges those log lines with the browser's console errors and failed requests (the `+Ns` column is seconds from the snapshot, so the error just before the click stands out). Then:
   - `--table <name>` shows the rows of one focused table (ids are text so 19-digit Discord ids are exact);
   - `--json guild.json` shows the server's effective settings, `--json site/client-log.json` the browser log, `--json site/page.json` the page, role and viewport.
3. **Pick the rows.** The focus decides what the zip holds: `event:<id>` gives the event and every table with an `event_id`, `ticket:<id>` likewise, `member:<id>` every table row for that member in that server. For anything outside the focus, or newer than the snapshot, query the database.
4. **Query the dev database.** `python tools/db_query.py "SELECT ... WHERE event_id = 12"` (`--format json` for machines, `--tables` to list tables). The schema `younglings` is the default, so write `event`, not `younglings.event`. It refuses anything but a single SELECT / WITH / SHOW / EXPLAIN, runs in a read-only transaction and never commits, so it cannot change data. To see a table as it was around the snapshot, filter on its timestamp column using the snapshot's `createdAtUtc` (the manifest) and `databaseTimeUtc` (environment.json: the database's own clock, which is what stored timestamps are compared against). A local dev database is at best an older copy plus local changes, so a row missing from it is not evidence it was missing in the snapshot: trust the zip's rows for the moment of the bug.
5. **Live data is the bot owner's call.** `python tools/db_query.py --live "..."` only prints that the owner must approve and exits; it does not connect. Once the owner has said yes, the same command with `--owner-approved` and `LIVE_DATABASE_URL` set in your environment (the credentials are never in the repository or a log) runs the same read-only checks. Say what you asked and why. Prefer the snapshot, then the dev database.
6. **Write the cause down** (one or two sentences, with the row or log line that proves it), then continue with step 4 of *How to triage a bug* (failing test first).

Tests for the tools: `python -m unittest tools/test_investigation_kit.py -v` (the write-refusal proof; the database cases skip themselves when there is no local dev database).
