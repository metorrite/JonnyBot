# Setting up the JonnyBot dev environment on a new machine

**Read first:** [docs/ECOSYSTEM.md](ECOSYSTEM.md), the map of the repos, the ticket board and the rules every thread follows.

Written when moving to a new PC (2026-10-17). It lists what the project needs and where each piece comes from. **No secrets are in this file**: where a secret is needed it says which one and where to find it.

## 1. What to install

| Tool | Why | Notes |
|---|---|---|
| Git + Git Bash | everything | user.name "Drake Forness"; the GitHub account is `metorrite` |
| GitHub CLI (`gh`), logged in | PRs, CI checks, merges | `gh auth login` |
| JDK 21 | the bot (Java 21, JDA 6.4.x, BotCommands 3.1.0) | IntelliJ used `~/.jdks/ms-21.*`; any JDK 21 works |
| Maven 3.9.x | builds and tests | not on PATH on the old machine; IntelliJ's bundled one or a download. First run online (`mvn test`), then `-o` works from the local cache |
| PostgreSQL 16 (port 5432) | the bot's local dev database `jonnybot_dev` (schema `younglings`) | the bot creates its own tables at startup (`CREATE TABLE IF NOT EXISTS ...`) |
| Node.js 22 LTS and npm | the website (Next.js 16, React 19, Tailwind 4) | `npm ci` in `younglings-website` |
| IntelliJ IDEA | run configurations, the IDE MCP server Claude uses to build and run the bot | see section 4 |
| Claude Code (desktop app) | the assistant; its MCP servers `idea` (IntelliJ) and optionally `webstorm` | the IDE must be open with the project for the MCP tools to answer |

## 2. Repositories and folder layout

Clone both repositories side by side (the folder names matter to the scripts and notes):

```
Repositories/
  JonnyBot/              https://github.com/metorrite/JonnyBot.git         the Discord bot (Java) -- main checkout, branch dev-run
  younglings-website/    https://github.com/metorrite/younglings-website.git  the website + dashboard (Next.js) -- main checkout, branch local-test
  jb-*-wt/  yw-*-wt/     git worktrees, one per feature branch (see below)
```

Branch model (both repos): feature branches are made off `dev`, a PR goes into `dev`, and **nothing goes to `master` without Drake saying so** (master auto-deploys to Railway). No stacked PRs. Merge bot PRs before website PRs.

- Main checkout `JonnyBot` stays on a local branch `dev-run` (= whatever should be running in the dev bot: dev, or dev plus the branch being tried). Never commit from it.
- Main checkout `younglings-website` is on `local-test` with uncommitted copies of files, used to run the site locally. It can simply be `git checkout dev` on a fresh machine.
- Feature work: `git worktree add ../jb-<name>-wt -b feat/<name> origin/dev` (and `yw-<name>-wt` for the site).
- Website worktrees have no `node_modules`: make a **junction** to the main checkout's (`mklink /J node_modules ..\younglings-website\node_modules`), build there with `next build --webpack` (Turbopack refuses the junction), and **remove the junction with `rmdir` (not `rm -r`) before `git worktree remove`**, or the real packages are deleted.

## 3. Secrets and configuration (names only)

Keep these in a password manager and re-enter them on the new machine. Never commit them.

**Bot (IntelliJ run configuration "JonnyBot Dev", environment variables):**
`DATABASE_URL` (local Postgres, `postgresql://USER:PASSWORD@127.0.0.1:5432/jonnybot_dev`), `DISCORD_TOKEN` (the *dev* bot's token), `GUILD_ID` (`1495619657674264717`: **this is the real Younglings clan server**; the "JonnyBot Dev" bot is a separate Discord application that sits in it, so anything the dev bot does on Discord happens in the real server, and a bot cannot create a throwaway test server, Discord refuses that for bots now), `INTERNAL_API_SECRET` (shared with the website), `LIVE_ENV=FALSE`, `REMOVE_COMMANDS_ON_SHUTDOWN=TRUE`. The internal API listens on port 8081. "JonnyBot Production" and "Initialize Databse JonnyBot Dev" are the other run configurations. The run configurations live in `.idea/workspace.xml` (the whole `.idea` folder is untracked, not in git), so on a new machine they are made again by hand: in IntelliJ, Run > Edit Configurations > Application, module `JonnyBot`, JDK 21:

| Name | Main class | Environment variables |
|---|---|---|
| JonnyBot Dev | `com.younglings.bot.Main` | `DATABASE_URL`, `DISCORD_TOKEN`, `GUILD_ID`, `INTERNAL_API_SECRET`, `LIVE_ENV`, `REMOVE_COMMANDS_ON_SHUTDOWN` |
| Initialize Databse JonnyBot Dev (optional; the bot initialises its tables itself) | `com.younglings.bot.util.InitializeDatabase` | `DATABASE_URL` |
| JonnyBot Production (not used locally; production is set on Railway) | `com.younglings.bot.Main` | none |
| Icon Image Update and Download (a tool) | `com.younglings.bot.tools.IconDownloader` | none |

Copying the old machine's `.idea/workspace.xml` also works when the repository is at the same path, but it carries the secrets and a lot of other IDE state, so move it the way you would move a password.

**Website (`younglings-website/.env.local`, not in git):**
`DISCORD_CLIENT_ID`, `DISCORD_CLIENT_SECRET`, `NEXTAUTH_SECRET`, `NEXTAUTH_URL` (`http://localhost:3000`), `JONNYBOT_INTERNAL_API_URL` (`http://localhost:8081`), `JONNYBOT_INTERNAL_API_SECRET` (must equal the bot's `INTERNAL_API_SECRET`). Signing in to the dashboard uses Discord OAuth, so the Discord application's redirect URI must include `http://localhost:3000/api/auth/callback/discord`.

**Live database and hosting:** the production Postgres is on Railway; its credentials are in Claude's memory file `reference_railway_live_db.md` and in Railway. Only touch it when asked, read-only first. DNS is on Cloudflare (`reference_infra_dns_hosting.md`).

**RSN link service (`rsnlink`, optional, off unless set up):** its own database `jonnybot_link` on the same local Postgres and its own keys, all `LINK_*` variables (LINK-SYSTEM.md section 6.1). Set it up locally with one command, from this repository:

```
powershell -ExecutionPolicy Bypass -File tools/rsnlink-dev-env.ps1
```

It creates `jonnybot_link` if missing and appends fresh random **local-only** keys to the git-ignored `.env` (`LINK_DATABASE_URL`, `LINK_ENC_KEY`, `LINK_LOOKUP_KEY`, `LINK_ALIAS_KEY`, `LINK_SIGNING_KEY`, `LINK_PUBLIC_BASE_URL`), printing names only. Keys already in `.env` are kept (changing `LINK_ALIAS_KEY` changes every alias; changing `LINK_ENC_KEY` without moving the old one to `LINK_ENC_KEY_OLD` makes stored Discord ids unreadable). To make a key by hand instead: 32 random bytes, base64, e.g. in PowerShell `$b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)`. On start the bot logs `RSN link service is on (schema rsnlink).`, or `... is off` when `LINK_DATABASE_URL` is unset, or `... refused to start:` with the names of the variables that are wrong. Never put these local keys on Railway; production keys are made at go-live (T-120). Tests make their own random keys and use the schema `rsnlink_test`; point them at the link database with `JONNYBOT_TEST_LINK_DATABASE_URL` (same form as `DATABASE_URL`, database `jonnybot_link`).

## 3b. Secret scan (pre-commit hook)

Every commit is scanned for secrets by [gitleaks](https://github.com/gitleaks/gitleaks), locally; nothing is uploaded. CI runs the same scan ("Secret Scan" job) next to GitGuardian.

1. Get the pinned release (8.30.1) from the official releases page, `gitleaks_8.30.1_windows_x64.zip`, verify it against `gitleaks_8.30.1_checksums.txt`, and unzip `gitleaks.exe` into a tools folder **outside the repositories** (for example `%LOCALAPPDATA%\Temp	ools`). Never add the binary to a repository.
2. Put it on `PATH`, or set `GITLEAKS_BIN` to its full path.
3. In each clone (and each worktree's repository, once): `sh tools/install-hooks` from Git Bash. It sets `core.hooksPath` to `tools/hooks`.
4. A commit with a secret in the staged changes is blocked and prints only the rule id, file and line, never the value. Remove the secret (a real one: tell Drake), build fake test values at runtime, or add a specific entry to `.gitleaks.toml`. Do not use `--no-verify`.

Check the whole tree by hand: `gitleaks dir . --redact=100 --no-banner`.

## 4. First run

1. Start Postgres and create the database: `createdb jonnybot_dev` (user `postgres`).
2. Get data into it. Either let the bot create empty tables on first start (it also creates BotCommands' own tables, schema `bc`; if the log ever complains they are missing, load `docs/bc-schema.sql` once as that file says), or restore a copy of live data (how it was done on 2026-10-14 is in Claude's memory `reference_live_to_local_db_sync.md`: dump live read-only, restore locally; **the dev bot must not run against that data in the real server**).
3. IntelliJ: open `JonnyBot`, set the JDK to 21, import the Maven project, create the "JonnyBot Dev" run configuration with the variables above, build, run. Success looks like `Internal API listening on port 8081` and `Finished Loading!` in the log.
4. Website: `cd younglings-website; npm ci; npm run dev` (port 3000). A `.claude/launch.json` entry named `younglings-website` (`npm --prefix <path> run dev`, port 3000) lets Claude start it in its browser pane. Sign in with Discord, open `http://localhost:3000/dashboard/1495619657674264717/event-manager`.
5. Only one bot instance may run per token: starting "JonnyBot Dev" again does **not** stop the old one (the second logs "Address already in use" for port 8081). Stop the old `com.younglings.bot.Main` java process first.

Checking the running bot without the browser: `python tools/dev_api.py GET events/12/space --actor <your discord user id>` calls the same internal admin routes the dashboard uses (it reads the secret from the run configuration and never prints it). A GET only reads; remember the dev bot sits in the real clan server, so POST/PUT/DELETE change real things.

## 4b. Start everything with one command

From this repository, in a terminal:

```
powershell -ExecutionPolicy Bypass -File tools/dev-start.ps1
```

It stops every running bot and the website, rebuilds `local/wip-preview` in the website worktree (origin/dev plus every open website PR merged in; a PR that conflicts is skipped and named), builds and starts exactly one bot from whatever branch is checked out here, starts the website on port 3000, and prints whether both answer and how many bot processes are running (it must be 1). `-NoRefresh` leaves the website branch as it is. The bot's environment comes from the git-ignored `.idea/runConfigurations/JonnyBot_Dev.xml`, so no secret is in the script. Logs: `%TEMP%\jonnybot-dev.log` and `%TEMP%\website-dev.log`.

Do not also start "JonnyBot Dev" from IntelliJ: each launch adds another process, and the older one keeps port 8081 with old code.

## 5. Running the tests

```
mvn -o -q test                                   # whole bot suite (~1135 tests, ~10 minutes; database tests skip without a local database)
mvn -o test -Dtest='EventTeamsTest,EventsAdminApiTest'   # the database-backed event tests (EventsAdminApiTest alone takes ~2.5 minutes)
```

Database-backed tests (`EventSetupTest`, `EventTeamsTest`, `EventsAdminApiTest`, ...) use `LocalDatabase`, which refuses anything but 127.0.0.1/localhost and cleans up the rows it makes (made-up server ids). It finds the database in `.idea/workspace.xml` (the run configuration's `DATABASE_URL`) **or** in the environment variable `JONNYBOT_TEST_DATABASE_URL`; a git worktree has no `.idea`, so export the variable (same value as `DATABASE_URL`) when running tests there. Without it those tests are silently skipped (surefire shows "Tests run: 0" for them).

**Headless startup check (`StartupWiringTest`).** Boots the bot's real BotCommands wiring in a child JVM *without logging in to Discord* (the system property `jonnybot.headless=true` makes `Bot.createJDA` return early, so no gateway, no internal API, no schedulers), then runs every command provider's declaration step. It fails on "No service or factories found", an interface service missing `@InterfacedService`, a missing `SlashParameterResolver`, a bad constructor, a SQL error in a repository's table setup. It uses the local dev database (`JONNYBOT_TEST_DATABASE_URL`) and is skipped when there is none; CI runs it against a throwaway Postgres (`STARTUP_CHECK_DATABASE_URL`, with `docs/bc-schema.sql` loaded first) and fails if the database is missing. Run it alone with `mvn -o test -Dtest=StartupWiringTest`. `ListenerRegistrationTest` is its static companion: every `@BService` `ListenerAdapter` must be a field of `Bot` or implement `BotListener`. **Limit:** it proves the wiring and the command definitions build, not that Discord accepts them or that a listener behaves; the "Finished Loading!" launch of the dev bot stays the check for those.

Website: `npx tsc --noEmit` (ignore the one `LayoutProps` error in a worktree: Next generates that type), `npx eslint <paths>`, `npx next build --webpack`.

### Working in worktrees (rules from the first overnight runs, 2026-10-19)

- **Website worktrees get a real `npm ci`.** Never a `node_modules` junction: `git worktree remove` through one deletes the real packages.
- **Stop dev servers and node processes before `git worktree remove`** (it fails with "Permission denied" while one holds the folder). Check `git worktree list` afterwards.
- **Delete `.next` when switching branches** in a website worktree: stale `.next/types` make `tsc` fail.
- **DB-backed bot tests:** set `JONNYBOT_TEST_DATABASE_URL` to the local dev database (read that single key from the run configuration; never grep the whole file, it prints the password). Without it those tests skip silently ("Tests run: 0").
- **One dev bot only.** Port 8081 and the real server are shared. Claim it on the ticket board (`config/board`, field `devBot` = `{owner, since}`, pinned with `if_version`, stale after 2 hours), launch from your worktree, wait for "Finished Loading!", stop exactly your own process, then clear the claim. If it is held, skip the launch and say so in the ticket.
- **No dev bot for website work:** run a small stub of the internal API (a fake server answering the routes the page calls) to render owner/non-owner views.
- **Tool notes (Windows):** use `python`, not `python3` (the Store stub); `psql` options must come before the URL; edit files with an editor tool, not heredocs or PowerShell `Set-Content` (they mangle backslashes and non-ASCII text); never print the contents of `.env`, run configurations or `.idea/workspace.xml`.

## 6. Files that exist only on the old machine (carry over if wanted)

The short version of what is **not** in GitHub: Claude's memory folder, the IntelliJ run configurations (`.idea`), the website's `.env.local`, Claude's local permission approvals (`.claude/settings.local.json`), the local database, and a few throwaway scratch files. Everything else (all code, both repositories' branches, these two documents) is in GitHub.

- **Claude's memory for this project**: `C:\Users\drake\.claude\projects\C--Users-drake-Documents-Github-Repositories-JonnyBot\memory\` (the plan, decisions, reminders, lessons). The folder name is the repository path with separators turned into dashes, so on a machine where the repo lives at a different path the folder has a different name: copy the *contents* into the matching `projects\<encoded path>\memory\` folder. This is what lets the assistant pick the work up where it was left.
  **This folder is sensitive:** `reference_railway_live_db.md` in it holds the live Railway database credentials. Move the folder the way you would move a password (a USB stick, or a private encrypted store), never through a public place or the repository, or take those credentials out of that one file first and keep them in your password manager.
- `.idea/workspace.xml` (run configurations with the secrets above; see section 3 for recreating them instead), `younglings-website/.env.local`, and `JonnyBot/.claude/settings.local.json` (the permissions Claude was already allowed in this project; optional, it only saves answering the prompts again).
- Git-excluded scratch files: bot `src/test/java/com/younglings/bot/DevDb*SmokeTest.java` and `tools/DevDbSmokeTest.java.txt` (throwaway tests that run repository SQL against the local dev database), `src/test/.../BingoSkinScratchTest.java` (generates the bingo look preview pictures; a copy of the generator is in `tools/BingoSkinGenerator.java.txt`), `tools/DevDbPluginE2eTest.java.txt` (untracked); and `.claude/launch.json`. (The website's `src/app/dev-preview/` harness, which mounts dashboard components over mock data, is now tracked in git and travels with the repository.)
- The local database `jonnybot_dev` (and `jonnybot_dev_liveparity`, a copy of live data, which holds real clan data: treat a dump of it as private). Moving data is optional: `pg_dump -U postgres -Fc jonnybot_dev -f jonnybot_dev.dump` here and `pg_restore -U postgres -d jonnybot_dev --clean --if-exists jonnybot_dev.dump` there. A fresh empty database works too.
- Logins that cannot be moved and are done again: the Claude app, `gh auth login`, git credentials for GitHub, Discord in the built-in browser pane (the dashboard needs a Discord sign-in), and the IDE connection for Claude (IntelliJ: Settings > Tools > MCP Server).
- The conversation transcripts under `~/.claude/projects/...` are optional: they are large (the current project session is about 165 MB) and nothing needs them (the memory folder and these documents hold the decisions); copy the one file you want searchable if you like. Do **not** copy `~/.claude/.credentials.json` or `~/.claude.json`: sign in fresh.

## 7. Moving to a new PC: the order of things

1. **Old PC, now:** check that both pull requests of the current slice are merged into `dev` or still open and pushed (`git status` clean in every checkout, nothing unpushed); copy the memory folder and the secrets (password manager) somewhere private; optionally dump the database; optionally copy the website's `dev-preview` folder.
2. **New PC, tools:** install what section 1 lists, then sign in to GitHub (`gh auth login`) and set `git config --global user.name "Drake Forness"` and your email.
3. **New PC, code:** clone both repositories into the *same folder layout* as section 2 (same absolute path means Claude finds the memory folder under the same name), `git checkout dev`.
4. **New PC, Claude's memory:** put the memory folder at `C:\Users\<you>\.claude\projects\<repository path with every : and \ and / turned into ->\memory\` (for `C:\Users\drake\Documents\Github\Repositories\JonnyBot` that is `C--Users-drake-Documents-Github-Repositories-JonnyBot`). Open the repository in the Claude app; the first thing to ask is where the event lifecycle project stands.
5. **New PC, secrets and run configurations:** section 3. Create the database (section 4), start the bot, start the website, sign in to Discord in the browser pane.
6. **Check it works:** the bot log shows `Finished Loading!`; the dashboard opens; `python tools/dev_api.py GET whoami --actor <your discord user id>` answers `ADMIN`; `mvn -o test -Dtest=EventSpaceTest` passes.

See `docs/EVENT-LIFECYCLE.md` for the event project itself.
