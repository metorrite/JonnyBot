# Bug reports

The standard bug report. The board's **Report a bug** form (artifact `W1MkBY5EH8beWVZGfnQUER`) asks for exactly these fields, and it writes them into the new bug ticket's `bugReport` object. A bug against a finished ticket is a new ticket with `relatesTo` set to the old id (see [ECOSYSTEM.md](ECOSYSTEM.md), section 6).

## The standard form

| Field | Stored as | What to put |
|---|---|---|
| What's wrong | ticket `title` | One line. |
| Ticket it came from | `relatesTo` | Ticket id (e.g. `T-054`), if there is one. |
| Area | `project` | The piece it is in (clan site, JonnyBot, hub, bot, plugin, integration, infra). |
| Where | `bugReport.env` | `live` (the real server / rsyounglings.com), `dev` (the dev bot), or `local` (localhost). |
| How bad | `bugReport.severity` | Blocks people from using it / wrong result or data / annoying but works / looks wrong. |
| When | `bugReport.when` | When it happened, with the timezone, converted to UTC (the form does this). |
| How often | `bugReport.frequency` | Every time / sometimes / once so far. |
| Page, command or channel | `bugReport.place` | The URL path, slash command or channel. |
| Who | `bugReport.who` | The account and its role (e.g. `Drake (admin)`, `alt account (member)`). |
| Steps | `bugReport.steps[]` | One step per line, enough to make it happen again. |
| Expected | `bugReport.expected` | What should have happened. |
| Actual | `bugReport.actual` | What happened instead. |
| Dev snapshot id | `bugReport.snapshot` | The `snap_…` id from the dev button or `/dev snapshot`. |
| Links | `bugReport.links[]` | Screenshots, Discord messages, URLs; one per line. |

Steps and the snapshot id matter most: they let a worker find the exact state. A report without steps is still filed, but its ticket goes to `planning` until someone can reproduce it.

## Secrets in bug reports

Dev snapshots, logs, screenshots and ticket text stay redacted: no tokens, DB URLs, `INTERNAL_API_SECRET`, plugin tokens or OAuth keys, and no `.env` or run-configuration contents. If a report needs a config value, name the key, never the value. If you find a real secret in a report, snapshot or log, stop and tell Drake (see the Secrets rule in `docs/ECOSYSTEM.md`).

## Risk for a bug

The form sets a starting risk (`level`, `merge`):

- `high` level, or `drake` merge when the original ticket was high risk: Drake decides the merge.
- Live and blocker bugs start at `medium` with `after-test`.
- Everything else starts at `low` with `auto`.

The worker raises the risk if the fix turns out bigger than the report says.

## How a worker triages a bug

1. **Read the report.** Read the ticket and any `relatesTo` ticket it points to (open or in the archive), plus the links.
2. **Reproduce first.** Follow the steps on the stated surface. If there is a snapshot id, load that snapshot and reproduce from it, not from memory of the live state. Write what you saw in the log.
   - Cannot reproduce: say what you tried, set the ticket to `planning` with the question, and stop.
3. **Find the root cause.** Trace it in the code and the data. Write the cause in one or two sentences in the log, not only the symptom.
4. **Write a failing test first.** A unit test, or for SQL a throwaway JUnit against the local dev database (see the dev DB smoke test note). Run it and check that it fails for the reported reason.
5. **Fix it.** Smallest change that makes the test pass, on one branch off `origin/dev`, one PR into dev. Website and bot sides both, if the surface rule says they both apply.
6. **Check the fix.** Run the test suite for the touched repo, and the reproduction steps again on the dev bot or localhost where you can. Record in `risk.tested` what you actually did.
7. **Merge or hand over** by the merge policy on the ticket (`auto`, `after-test` or `drake`). Never merge to master.

If the root cause is in a different ticket's area, say so in the log and link it with `dependsOn` rather than fixing it inside this PR.

## From a bug report to the rows that matter

The tools: `tools/snapshot_inspect.py` (read a snapshot zip), `tools/db_query.py` (read-only SQL against the local dev database), the dev bot's internal API through `tools/dev_api.py` (GET only). All are read-only by construction; none needs a secret in the command line.

1. **Get the snapshot.** The report's `bugReport.snapshot` is an id like `snap-20261018-143005-a3f9`. Ask Drake for the zip (`jb-snapshot-<id>.zip` from the owner-only Snapshot button, or `<id>.zip` from the dashboard's Snapshots page). Put it outside the repository; never commit one. Snapshots are kept 14 days. No snapshot: reproduce from the steps and stop at step 4 if you cannot.
2. **Read it.** `python tools/snapshot_inspect.py <zip>` prints who took it and when (UTC), the server and focus, bot and site commits, bot uptime and database time, RuneMetrics pacer state, which features have settings, the warnings and errors from the 30 minutes before, and a **timeline** that merges those log lines with the browser's console errors and failed requests (the `+Ns` column is seconds from the snapshot, so the error just before the click stands out). Then:
   - `--table <name>` shows the rows of one focused table (ids are text so 19-digit Discord ids are exact);
   - `--json guild.json` shows the server's effective settings, `--json site/client-log.json` the browser log, `--json site/page.json` the page, role and viewport.
3. **Pick the rows.** The focus decides what the zip holds: `event:<id>` gives the event and every table with an `event_id`, `ticket:<id>` likewise, `member:<id>` every table row for that member in that server. For anything outside the focus, or newer than the snapshot, query the database.
4. **Query the dev database.** `python tools/db_query.py "SELECT ... WHERE event_id = 12"` (`--format json` for machines, `--tables` to list tables). The schema `younglings` is the default, so write `event`, not `younglings.event`. It refuses anything but a single SELECT / WITH / SHOW / EXPLAIN, runs in a read-only transaction and never commits, so it cannot change data. To see a table as it was around the snapshot, filter on its timestamp column using the snapshot's `createdAtUtc` (the manifest) and `databaseTimeUtc` (environment.json: the database's own clock, which is what stored timestamps are compared against). The dev database is a copy of live from 2026-10-14 plus whatever Drake has done since, so a row missing from it is not evidence it was missing in the snapshot: trust the zip's rows for the moment of the bug.
5. **Live data is Drake's call.** `python tools/db_query.py --live "..."` only prints that Drake must approve and exits; it does not connect. After Drake says yes in your thread, the same command with `--drake-approved` and `LIVE_DATABASE_URL` set in your environment (credentials: `reference_railway_live_db` memory note, never in the repository or the log) runs the same read-only checks. Say in the ticket log what you asked and why. Prefer the snapshot, then the dev database.
6. **Write the cause down** in the ticket log (one or two sentences, with the row or log line that proves it), then continue with step 4 of *How a worker triages a bug* (failing test first).

Tests for the tools: `python -m unittest tools/test_investigation_kit.py -v` (the write-refusal proof; the database cases skip themselves when there is no local dev database).
