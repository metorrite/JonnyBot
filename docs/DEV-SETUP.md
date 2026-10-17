# Setting up the JonnyBot dev environment on a new machine

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
`DATABASE_URL` (local Postgres, `postgresql://USER:PASSWORD@127.0.0.1:5432/jonnybot_dev`), `DISCORD_TOKEN` (the *dev* bot's token), `GUILD_ID` (the dev server, `1495619657674264717`), `INTERNAL_API_SECRET` (shared with the website), `LIVE_ENV=FALSE`, `REMOVE_COMMANDS_ON_SHUTDOWN=TRUE`. The internal API listens on port 8081. "JonnyBot Production" and "Initialize Databse JonnyBot Dev" are the other run configurations. The run configurations live in `.idea/workspace.xml` (not in git).

**Website (`younglings-website/.env.local`, not in git):**
`DISCORD_CLIENT_ID`, `DISCORD_CLIENT_SECRET`, `NEXTAUTH_SECRET`, `NEXTAUTH_URL` (`http://localhost:3000`), `JONNYBOT_INTERNAL_API_URL` (`http://localhost:8081`), `JONNYBOT_INTERNAL_API_SECRET` (must equal the bot's `INTERNAL_API_SECRET`). Signing in to the dashboard uses Discord OAuth, so the Discord application's redirect URI must include `http://localhost:3000/api/auth/callback/discord`.

**Live database and hosting:** the production Postgres is on Railway; its credentials are in Claude's memory file `reference_railway_live_db.md` and in Railway. Only touch it when asked, read-only first. DNS is on Cloudflare (`reference_infra_dns_hosting.md`).

## 4. First run

1. Start Postgres and create the database: `createdb jonnybot_dev` (user `postgres`).
2. Get data into it. Either let the bot create empty tables on first start, or restore a copy of live data (how it was done on 2026-10-14 is in Claude's memory `reference_live_to_local_db_sync.md`: dump live read-only, restore locally; **the dev bot must not run against that data in the real server**).
3. IntelliJ: open `JonnyBot`, set the JDK to 21, import the Maven project, create the "JonnyBot Dev" run configuration with the variables above, build, run. Success looks like `Internal API listening on port 8081` and `Finished Loading!` in the log.
4. Website: `cd younglings-website; npm ci; npm run dev` (port 3000). A `.claude/launch.json` entry named `younglings-website` (`npm --prefix <path> run dev`, port 3000) lets Claude start it in its browser pane. Sign in with Discord, open `http://localhost:3000/dashboard/1495619657674264717/event-manager`.
5. Only one bot instance may run per token: starting "JonnyBot Dev" again does **not** stop the old one (the second logs "Address already in use" for port 8081). Stop the old `com.younglings.bot.Main` java process first.

## 5. Running the tests

```
mvn -o -q test                                   # whole bot suite (~1135 tests, ~10 minutes; database tests skip without a local database)
mvn -o test -Dtest='EventTeamsTest,EventsAdminApiTest'   # the database-backed event tests (EventsAdminApiTest alone takes ~2.5 minutes)
```

Database-backed tests (`EventSetupTest`, `EventTeamsTest`, `EventsAdminApiTest`, ...) use `LocalDatabase`, which refuses anything but 127.0.0.1/localhost and cleans up the rows it makes (made-up server ids). It finds the database in `.idea/workspace.xml` (the run configuration's `DATABASE_URL`) **or** in the environment variable `JONNYBOT_TEST_DATABASE_URL`; a git worktree has no `.idea`, so export the variable (same value as `DATABASE_URL`) when running tests there. Without it those tests are silently skipped (surefire shows "Tests run: 0" for them).

Website: `npx tsc --noEmit` (ignore the one `LayoutProps` error in a worktree: Next generates that type), `npx eslint <paths>`, `npx next build --webpack`.

## 6. Files that exist only on the old machine (carry over if wanted)

- **Claude's memory for this project**: `C:\Users\drake\.claude\projects\C--Users-drake-Documents-Github-Repositories-JonnyBot\memory\` (the plan, decisions, reminders, lessons). The folder name is the repository path with separators turned into dashes, so on a machine where the repo lives at a different path the folder has a different name: copy the *contents* into the matching `projects\<encoded path>\memory\` folder. This is what lets the assistant pick the work up where it was left.
- `.idea/workspace.xml` (run configurations with the secrets above) and `younglings-website/.env.local`.
- Git-excluded scratch files: bot `src/test/.../BingoSkinScratchTest.java` (generates the bingo look preview pictures; a copy of the generator is in `tools/BingoSkinGenerator.java.txt`), `tools/DevDbPluginE2eTest.java.txt` (untracked); website `src/app/dev-preview/` (a throwaway page that mounts dashboard components over mock data: `?v=teams`, `?v=wizard`, `?v=events`; `.git/info/exclude` lists it) and `.claude/launch.json`.
- The conversation transcripts under `~/.claude/projects/...` are optional.

See `docs/EVENT-LIFECYCLE.md` for the event project itself.
