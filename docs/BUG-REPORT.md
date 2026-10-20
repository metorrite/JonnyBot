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
