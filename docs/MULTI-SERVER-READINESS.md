# Multi-server launch readiness

Ticket T-073. Status: **the work that needs no second server** (Drake away, 2026-10-20). Onboarding a real second server and the Discord verification submission stay Drake's (section 6). Supersedes the audit in memory note `project-multi-server-readiness` (2026-10-10, corrected 2026-10-15).

## 1. Where things stand (checked in code on dev, 2026-10-20)

| Area | State |
|---|---|
| Per-server data | 42+ tables carry `guild_id`; settings per server (`guild_settings`); schedulers loop `jda.getGuilds()` |
| New servers inherit nothing from the home server | done (`GuildSettingsService` only uses env defaults for `GUILD_ID`) |
| Join / leave | `GuildLifecycleListener`; data kept 30 days after the bot leaves, then deleted (`GuildRetentionService`, never the home/beta/premium servers) |
| Player identity | global (`player_account`, one row per RSN); `player_link` = registered in this server; snapshots and activity stored once; one poll queue (`PollCoordinator`) |
| Internal API | picks the server by `X-Guild-Id`; home server only the default; actor checked per server (`DashboardAccess`: Manage Server or Administrator in non-home servers) |
| Dashboard | per server, server picker, install link, beta gate (`DASHBOARD_PUBLIC`) |
| Home-only things that are correct | dev command registration to `GUILD_ID`, the bundled clan logo only in the home server (`TeamformingService`), the clan site and `/admin` (single-clan by design) |
| Hard-coded server ids | only the beta-list seed (`BetaDatabaseInitializer`: the friend's server), which is data, not logic |

**Gaps that remain** (each has a ticket or is Drake's):

| # | Gap | Fix |
|---|---|---|
| G1 | The same clan set up in two servers is roster-fetched and polled twice by the daily sync (`ClanSyncScheduler.runForAllGuilds` -> `syncAndPoll` per server) | T-229 |
| G2 | Nobody can see how the shared RuneMetrics budget is spent per server | T-230 |
| G3 | No automated "second server" check: a brand-new server's first minutes (join, defaults, nothing posted, dashboard reachable) are only proven by hand | T-231 |
| G4 | Presence intent: if Discord refuses `GUILD_PRESENCES` at verification, the clan site's online list silently shows nobody | T-232 |
| G5 | JonnyBot's FAQ and privacy page send people to the Younglings Discord for help; the hub's beta page links to "the Younglings home page" | fixed directly (website PRs, section 7) |
| G6 | A JonnyBot support server / contact | Drake (section 6) |
| G7 | Discord bot verification at 75-100 servers | Drake submits; prep in section 4 |
| G8 | Privacy and terms review for other servers' members | text checked in section 5; legal sign-off is Drake's |

## 2. Second-server onboarding checklist (for when Drake has a willing server)

Do it with a small server first (e.g. Eternal Rising's test server or a fresh one Drake owns), on **live**, after the next deploy.

1. Add JonnyBot to the beta list (`/admin` Beta access) so the dashboard opens for them.
2. They invite JonnyBot with the install link; check the bot's join log line and that **nothing** is posted in their server.
3. Their admin signs in at the dashboard, picks the server, runs **Guided setup** (Quick path).
4. Server setup: set the clan name; wait for the first roster sync (or press "Sync now"); check the Roster page fills.
5. `/rs` by one of their members: links once; if that RSN is already linked elsewhere to the same Discord, it is adopted, not re-asked.
6. Activity announcements: pick a channel; wait for the next poll; check one announcement arrives only in their server.
7. Make a tiny event from the dashboard; open signups; join with an alt; cancel and delete it.
8. Check our own server: nothing of theirs shows in our roster, dashboard or clan site.
9. Remove the bot from their server; check the retention notice in the logs; re-add within 30 days and check settings come back.
10. Write every snag on the board as a bug ticket (relatesTo T-073).

## 3. RuneMetrics budget

- One shared queue with tiers (premium clan 60 min, clan 180, linked non-clan 480; `RUNESCAPE_*_POLL_WINDOW_MINUTES`) paced by `RequestPacer` at the rate measured by `tools/RuneMetricsRateTest.java`.
- Capacity rule of thumb: players polled per hour = safe requests per hour; clan players need `roster / 3` per hour at 180 min. Each new 500-member clan adds ~170 requests per hour.
- Defaults: no per-server cap (all clans are small today). If the queue falls behind (T-230 shows it), the first lever is the standard clan window (180 -> 240), not a cap.

## 4. Discord verification prep (Drake submits; required before 100 servers)

Discord asks verified apps for: the owner's identity verification (Drake, through Discord's ID provider), a team or owner account with 2FA, a privacy policy URL and terms URL, and a written reason for each **privileged intent**. Draft answers:

| Intent | Used for (in code) | Draft reason |
|---|---|---|
| Server Members (`GUILD_MEMBERS`) | welcome/goodbye, autoroles, role sync with the clan roster, event roles, member-join completion of signups, retention | "JonnyBot greets new members, gives the roles a server configures (autoroles, self roles, clan member roles that follow the in-game roster) and keeps event roles in step. It needs member join/leave events and the member list to do this." |
| Message Content (`MESSAGE_CONTENT`) | automod and auto-clean filters, highlights (keyword DMs), message logs, custom tags and auto-replies, points keywords | "Server admins switch on moderation filters, keyword highlights, auto-replies and message logs; each reads message text only in servers that turned that feature on, and nothing is stored except moderation/log records the server asked for." |
| Presence (`GUILD_PRESENCES`) | online members on a clan's website (`SiteApi`, `InternalApiServer`) | "A clan's public website shows how many members are online now. If not granted, the website hides that section (T-232)." Recommended: ask, and accept a refusal. |

Links to give: privacy `https://jonnybot.<domain>/privacy`, terms `https://jonnybot.<domain>/terms` (both exist on the JonnyBot site; final domain per `younglings-website/docs/DOMAINS-AND-SITE-BUILDER.md`).

## 5. Privacy and terms check (text, not legal advice)

The JonnyBot privacy page already covers: what is stored per server and per player, global player records, plugin data retention (180 days), deletion 30 days after removal, and asking for deletion. To change before other servers join:

- The contact for requests must be a JonnyBot contact, not the Younglings Discord (G5/G6; the page now reads it from config).
- Add a line that server admins can see what their own server's features store (logs, tickets, cases), and that JonnyBot staff (Drake) can access data only to run and fix the service.
- If the RuneLink and RuneSync plugins go live, link their own notices (RUNESYNC-DESIGN section 12).

## 6. Drake's actions

1. Pick and run the first real second-server onboarding (section 2).
2. Create a JonnyBot support server or contact address, then set `JONNYBOT_SUPPORT_URL` on Railway (the FAQ and privacy page use it).
3. Discord: complete identity verification and submit the bot for verification with the intent reasons in section 4 when nearing 75 servers.
4. Read the privacy/terms pages once more with section 5 in mind.

## 7. Decisions made for Drake

| # | Question | Default |
|---|---|---|
| M1 | Ask for the Presence intent at verification | Yes, and hide the online list if refused (T-232) |
| M2 | Per-server polling cap | None now; widen the clan window first if the queue lags |
| M3 | Support contact | A config value (`JONNYBOT_SUPPORT_URL`); until set, the pages keep today's wording |
| M4 | First onboarding | A small server on live after the next deploy, checklist in section 2 |
| M5 | Same clan in two servers | One roster fetch and one poll per clan per day, fanned out to every server with that clan (T-229) |

Small fixes made directly under this ticket (website): the hub beta page no longer names Younglings; the FAQ and privacy support line read `JONNYBOT_SUPPORT_URL` when set.

## 8. Build tickets

| Ticket | What |
|---|---|
| T-229 | Bot: daily clan sync fetches each clan's roster once and polls each player once, shared by every server with that clan |
| T-230 | Bot + website: RuneMetrics budget view in Super admin (requests per day per server/clan, queue lag) |
| T-231 | Bot: automated second-server smoke test (fake guild join -> defaults -> nothing posted -> dashboard routes answer) |
| T-232 | Bot + website: Presence intent optional (site hides the online list; health page shows which privileged intents are on) |
