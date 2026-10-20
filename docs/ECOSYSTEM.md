# The JonnyBot / Younglings ecosystem

The one document every thread reads first. The master thread keeps it true; a worker that changes how things connect updates the relevant section in its PR.

Ticket board (the work queue): https://claude.ai/artifact/W1MkBY5EH8beWVZGfnQUER

## 1. The pieces

| Piece | Repository / path | Stack | What it is |
|---|---|---|---|
| **JonnyBot** (Discord bot) | `metorrite/JonnyBot`, `Repositories/JonnyBot` | Java 21, JDA 6.4.1, BotCommands 3.1, PostgreSQL (schema `younglings`), Maven, shaded jar | The bot: commands, listeners, RuneScape tracking, tickets, events, points, moderation. Owns all data. Serves an internal HTTP API on port 8081. |
| **Website** (one Next.js app, four faces) | `metorrite/younglings-website`, `Repositories/younglings-website` (+ worktree `younglings-website-p3`) | Next.js 16 (Turbopack), React 19, Tailwind 4, NextAuth Discord | Chooses its face by host and path (`src/proxy.ts`): |
| ... clan site | `/` and `src/app/(hub)` | | The Younglings clan's public site: home, members, stats, recaps. |
| ... JonnyBot site + dashboard | `/jonnybot`, `/dashboard` | | Marketing/docs/install pages, and the per-server **bot dashboard** (every setting of the bot). |
| ... clans hub | `/hub` (or `clans.<domain>`) | | Public directory of clans, events, leaderboards (redesigned 2026-10-19). |
| ... admin console | `/admin` | | Website behaviour only (what the Younglings site shows, its news-feed channels, site options) plus the owner-only super-admin area. Bot behaviour is never here (see section 3, Surface). |
| **Public / plugin API** | `/api/v1`, `/api/ingest/v1` (host `api.<domain>`) | | Public read API (OpenAPI in `public/openapi`) and the plugin ingest routes. |
| **RS3 plugin** (planned, very soon) | not started | Jagex's coming plugin API | A clan plugin reporting player data into the ingest API. Groundwork exists: ingest API, points ledger, approvals, cross-guild events. See memory note `project_rs3_plugin_api_prep`. |
| Local + hosting | Railway (bot + site), Cloudflare (DNS) | | `clans.`, `api.`, `jonnybot.` subdomains all point at the one site app. |

## 2. How the pieces talk

- **Website to bot:** server-side only, over `JONNYBOT_INTERNAL_API_URL` (`http://localhost:8081` locally) with the shared secret in header `X-Internal-Secret`. Browsers never see it. Dashboard calls go through `src/lib/jonnybot-admin.ts` (`adminApi.*` for the signed-in admin's server, each call carries who is asking), hub reads through `src/lib/clansHub.ts` (`/internal/directory/*`), plain calls through `src/lib/botCall.ts`.
- **Admin routes on the bot:** `/internal/admin/<feature>`; `TicketAdminApi` checks the asker may use the dashboard in that server, rate-limits writes, audit-logs, then offers the path to every `AdminRouteExtension` bean. A new feature = a new `@BService` implementing `AdminRouteExtension`; no edit to `TicketAdminApi`.
- **Access:** dashboard access is per server (owner, Administrator/Manage Server, or configured admin roles). Bot-owner-only things use `BotOwners` (Discord application owner or `OWNER_IDS`). The beta gate (`canSeeJonnyBot`, `HUB_PUBLIC`) hides the JonnyBot site and the hub from non-staff until switched on.
- **Premium:** a hand-picked server list (`premium_guild`, managed on `/admin/polling-plan`, owner only). Today it gates faster polling and the bot's server avatar/banner/bio. A future premium plan flips the same list.
- **Data stores:** everything lives in the bot's PostgreSQL schema `younglings`. Tables are created by each feature's repository through `SchemaBootstrapper` (idempotent `CREATE ... IF NOT EXISTS`, `ADD COLUMN IF NOT EXISTS`). The website has no database of its own.
- **Dev snapshots (bug reports):** an owner-only zip of the bot's state with a stable id (`snap-yyyyMMdd-HHmmss-xxxx`), taken from the dashboard or `GET/POST /internal/admin/dev/snapshot`. Format, focus and redaction rules: [DEV-SNAPSHOT.md](DEV-SNAPSHOT.md).
- **Multi-server:** the bot runs in many servers. Anything that names a clan or assumes the home server is wrong. Use `BrandingService.displayName(guild)` for names, `GuildSettingsService.getEffective(guildId)` for settings (env defaults apply to the home server only, `GUILD_ID`).

## 3. Rules that apply to every ticket

**Git**
- Feature branches off `origin/dev`, one PR into `dev` per ticket. Never stack branches on unmerged work; if a ticket needs another, it waits (`dependsOn`).
- `master` is a real deploy to Railway. **Never merge, push or open a PR to master** unless the user says so in that thread.
- LF line endings (`.gitattributes`). Commit messages end with the Claude co-author line; PR bodies end with the generated-with line.
- Never `git checkout -- .` / `reset --hard` in a worktree with uncommitted work.

**Surface (where a control goes), Drake 2026-10-19**
- **JonnyBot dashboard (`/dashboard`)** holds everything that changes how the bot behaves: roles, command on/off, channels the bot posts to, tracking, tickets, welcome, events (including the ones the site displays), member and points data.
- **`/admin`** holds only website behaviour: what the Younglings site shows, its news-feed channels, site options. It also holds the owner-only super-admin area (premium servers, beta servers, JonnyBot/hub/dashboard access switches, users allowed into restricted content). It may read bot data, but it never changes bot behaviour.
- Never put role or command toggles on `/admin`, and never put site-feed settings on the dashboard.
- Skipping a surface is allowed only when it has no meaning there (a purely website option needs no Discord control; a member's personal command like `/afk` needs no admin page unless it has server settings). Say why in the PR.
- Bot work still needs its dashboard side in the same ticket (see Bot below and the website-first rule).

**Bot (Java)**
- New listener adapters must be added to `Bot.java` or buttons silently time out.
- **Website first.** The dashboard is the primary way to change how the bot behaves; Discord-only use must keep working. In the same ticket as the bot change: every new setting, setup step or configuration gets a dashboard control; every new slash command appears on the dashboard Commands page (on/off, who, where) and its staff actions get a dashboard equivalent where that makes sense; a new feature gets its own dashboard page and nav entry. Skip a side only when it has no meaning there, and say why in the PR.
- Tests: `mvn -o test` (about 1,200 tests; all must pass). A throwaway JUnit against the local dev DB is the way to check new SQL.
- Dev bot lives in the real clan server (`GUILD_ID`): live tests are real. Do not spam it.

**Website (Next.js)**
- Read `node_modules/next/dist/docs/` before using a Next API (this Next differs from older versions).
- Dashboard UI follows `docs/DASHBOARD-DESIGN.md` (heading levels, `FormField` sizes, `PanelGrid`/`FieldGrid`, `SettingRow`, one global Basic/Advanced slider, `SaveBar`). Never add a per-page Basic/Advanced switch. A saved value never hides.
- A page or building block gets a view in the preview harness (`/dev-preview?v=<view>`), made-up data, no login.
- Checks before a PR: `npx tsc --noEmit`, `npx eslint <paths>`, `next build` (it catches `'use server'` files that export non-async things).
- A bot-linked page shows admin controls to a signed-in admin (`getAdminIfAny` to show, `requireAdmin` to enforce).
- Privacy: public pages never show an individual member's figures or Discord details.

**Everything**
- The website-first rule applies to the RS3 plugin too: anything the plugin reports or lets a member or admin control gets a website view or control (the member's profile for personal consent and data, the dashboard for clan settings).
- Do not invent facts about Discord/JDA limits: look them up or test them.
- Report what you could not verify (signed-in pages, anything against live Discord) in the ticket log and PR.

**Secrets (Drake 2026-10-19, ticket T-109)**
- Never commit, print or paste a real secret (Discord tokens, DB URLs/passwords, `INTERNAL_API_SECRET`, OAuth/Twitch keys, plugin tokens, live DB credentials): not in code, tests, docs, PR text, ticket logs or transcripts.
- Never grep `.env*` files or `.idea/workspace.xml` (the run configurations) wholesale; read a single key if you must, and never echo it.
- Test secrets are built at runtime (`TestSecrets.fake("token")` in the bot's test sources) or are obviously fake (`TEST-SECRET-1`). A literal that looks like a real token fails GitGuardian and the gitleaks scan.
- Both repos run a gitleaks pre-commit hook (`sh tools/install-hooks` once per clone, see `docs/DEV-SETUP.md`) and a CI "Secret Scan" job. Scan output is always redacted (rule id, file, commit). Do not bypass the hook with `--no-verify`; an allow-list entry in `.gitleaks.toml` needs a reason and must be specific.
- Run `git diff --cached --stat` before a push. If a real secret was ever committed: stop, set the ticket to `planning`, log the kind, file and commit (no value) and tell Drake. Rotation and history cleaning are his call.

## 4. Running it locally

`powershell -ExecutionPolicy Bypass -File JonnyBot/tools/dev-start.ps1` stops every old bot, rebuilds the website preview branch `local/wip-preview` (origin/dev plus every open PR), starts exactly one bot (port 8081) and the website (port 3000). Never start the IntelliJ "JonnyBot Dev" configuration as well: it adds a second bot. `HUB_DEMO=true` (git-ignored `.env.local`) fills the hub with fake clans.

## 5. Memory notes worth reading by area

Memory lives in `C:\Users\drake\.claude\projects\C--Users-drake-Documents-Github-Repositories\memory\` (index `MEMORY.md`, loaded automatically in every thread). Start from the index; the ones that matter most: `project_dashboard_design_overhaul`, `project_event_lifecycle_channels`, `project_clan_site`, `project_ticket_system`, `project_multi_server_readiness`, `project_rs3_plugin_api_prep`, `project_remaining_redesign_2026_10_19`, `feedback_*` (how Drake wants work done).

## 6. Ticket workflow

- **Master thread** (strongest model): talks with Drake, knows this whole map, turns requests into tickets on the board, splits large ones, marks unclear ones `planning`, and starts worker threads with `start_session`. It does not write feature code itself.
- **Ticket** = one PR (or one tightly related batch). Fields: project, kind, size, summary, requirements, acceptance, context to read, `dependsOn`, model + effort, branch, PR, log.
- **Statuses:** `planning` (needs a design chat; a design thread talks it through with Drake, then sets it `ready`), `ready`, `blocked` (waiting on `dependsOn`), `in_progress`, `in_review` (PR open), `done` (merged into dev), `cancelled`.
- **Worker thread:** reads its ticket and the context, sets `in_progress`, does the work on one branch, opens one PR into dev, sets `in_review` with the PR link and an honest log of what was not verified. A question that needs a decision stops the work: the ticket goes back to `planning` with the question written down.
- **Git-management thread:** merges `in_review` PRs into dev when CI is green, writes PR comments and the changelog, sets `done`, and promotes tickets whose dependencies are all done from `blocked` to `ready`. Workers merge only their own `auto` tickets (see Risk and merge policy below). Master is never merged by anyone without Drake's explicit go.
- **Risk field:** every ticket has a `risk` object: `level` (low / medium / high), `merge` (`auto` / `after-test` / `drake`), `breaks[]` (likely bugs), `scale` (how bad, how many affected), `interrupts[]` (features or services it can disturb), `tested` (untested / unit tested / run locally / tested on Discord / live / n/a (docs)), `assessedAt` / `assessedBy`. The master thread sets it; a worker raises it if the work turns out riskier and says why in the log.
- **Merge policy:**
  - `auto`: the worker merges its own PR into dev once CI is green and its own checks pass (`gh pr merge <n> --merge`, keep the branch, bot PR before its website PR), then sets `done` and archives. Only for work that cannot delete, ban, re-role, expose or touch live data.
  - `after-test`: `in_review` until the test named in the PR passes (a local run, the dev bot, or a short check by Drake on Discord). Then merged.
  - `drake`: `in_review` until Drake says merge. Used for design, high-risk work (deletes, bans, mass role changes, access and gates, live data) and anything with a choice to make.
  - Either way, never merge to master.
- **Archive:** a done ticket moves from collection `tickets` to `archive` (same id, plus `archivedAt`) so the board stays small. Write the archive copy first, then delete from `tickets`, both pinned with `if_version`.
- **Bugs against a finished ticket:** file a new bug ticket with `relatesTo` set to the old id, using the standard form in `docs/BUG-REPORT.md`. Do not reopen an archived ticket unless the work itself was wrong.

- **Model and effort** per ticket come from the routing table on the board (design/large on the strongest model and high effort, standard features Sonnet/high, small ones Sonnet/medium, mechanical Haiku, git work Sonnet/low). A started session can use the same model or a cheaper one than the thread that starts it, so the master runs on the strongest.
- Every board write pins `if_version` (re-read and redo if it fails).

**Rules learned from the first overnight runs (2026-10-19)**
- **Deletion check before review:** `git diff --diff-filter=D --name-only origin/dev...HEAD` must list nothing you did not mean to delete (a long-running branch once deleted docs another ticket had just merged).
- **Board first:** update the ticket (status, PR, log) *before* pushing or merging, so a usage cut-off leaves a truthful board.
- **Merge only when `gh pr checks` shows no pending checks** and all are green. `gh pr merge` does not wait for CI.
- **Shared or core files are `after-test` even when small:** shared UI components, `nav.ts`, the dashboard shell, `Bot.java` gateway/intents. Raise `risk.merge` and say why in the log. Two tickets touching the same core file are done one after the other, not in parallel.
- **Stack only when the code really depends on it** (does it use the new code?). Say so in the PR when stacking, and never delete the base branch while the top PR is open.
- **Edge case with no rule:** pick the safest option, note it in the log and PR. A real product decision sends the ticket to `planning` with the question written down.
- **Secrets:** never print or commit real secrets; read single keys, never grep `.env` or run configurations wholesale; test secrets are obviously fake or built at runtime.
- **Verification you could not do** (signed-in pages, anything against live Discord) is listed in the ticket log and the PR.
