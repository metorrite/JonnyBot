# The event lifecycle project

What was asked for, what has been decided, what is built, and what is next. Written 2026-10-17 so the work can be picked up on another machine. (Claude's own notes for the project hold the same plus working lessons: `memory/project_event_lifecycle_channels.md`, `project_event_reminders.md`.)

## The goal

Drake runs RuneScape clan events (bingo, scavenger hunts, XP competitions, boss nights) through JonnyBot and the clan dashboard. The event engine (create, objectives, teams, players, proof, standings, buy-ins, prizes) already existed. This project turns it into a full lifecycle:

**build -> take signups -> close signups and confirm the roster -> (verify players) -> build teams -> make Discord roles and channels -> run, with players submitting proof -> finish -> wrap up (export, archive, delete).**

## Requirements (Drake's words, condensed) and decisions

- R1-R4 Bingo looks: keep both the template pictures ("classic") and the RuneScape-themed ones; shapes (path, pyramid, convergence = "The Boss Convergence", world tree) plus the normal grid, now with backdrops; options to customise each.
- R5-R9 Every event gets channel, player/team and board management that coincide, and a player-facing area. The bot makes **event roles** for signed-up players (website and Discord signups stay in sync); marking signups closed pauses the signup and syncs the roster **one last time**; roles then drive a category and channels; a **"Set up event channels"** button only works once signups are over, the roster is confirmed and (team events) the teams are final.
- R10-R11 Team events: an admin builds teams by hand by skill (some bigger and weaker, some smaller and stronger), in a friendly web interface **and** Discord commands, then finalises them.
- R12-R17 Channels: every channel name from a pattern setting (`{event} General Channel` -> `christmas-bingo-general-channel`); per channel the bot **creates it or the admin picks an existing one**, independently; picked channels are validated; the next phase is blocked until everything the engine needs exists. Per event: its own category, a staff+organiser channel, an announcement channel (participants read, organisers post), a general channel, optional board/standings channels, private team channels, optional per-player channels for solo events. Discord allows 50 channels per category, so extra categories are made automatically (decided, not optional).
- R18-R19 `/submit`: the event is worked out from the channel it is used in; an option makes `/submit` the only way to submit; each submission goes to the player's/team's channel and (optionally) a staff-only channel. Decided shape: `/submit event` and `/submit event-verify` (subcommands).
- R20/R22 Verification before the event (so nobody hoards): pending players sit on the roster but are excluded from teams unless an organiser lets them through; the gate (at signup / before teams / before start) is movable.
- R23 Setup streams in the web interface: Quick, Standard, Guided; saved templates; drafts.
- R24 Wrap-up options (undecided in detail): export everything to a zip of JSON files, delete all, archive into archive channels, compress/transcribe (e.g. every solo channel into one), de-duplicate bot messages, and a role-retention schedule independent of the channel schedule (e.g. keep general and announcements viewable a week).
- D10 Participants must join the host Discord to take part (so roles and channels only concern members).
- Parked: an events subdomain hub for open community events (`events.rsyounglings.com`).

## Slices and status

| # | Slice | Bot | Website | State |
|---|---|---|---|---|
| 1 | Bingo looks, grid backdrops, options | #186 | #75 | merged into dev |
| 2 | Lifecycle foundation: close signups, roster, verification, readiness checklist | #187 | #76 | merged into dev |
| 3 | Setup streams (standard/quick/guided), drafts, saved templates, options registry | #188 | #77 | merged into dev |
| 4 | Team builder: ratings, strength, suggestion, finalise, `/events team` | #189 | #78 | merged into dev |
| 5 | Roles and channels (see below) | #191 | #79 | built and tested, PRs open (not merged); never run against Discord for real |
| 6 | `/submit event` and `/submit event-verify`, channel inference, receipts, require flag | | | not started |
| 7 | Player-facing event area on the website (my events, team, board, standings, submissions, links to channels) | | | not started |
| 8 | Wrap-up: export zip, delete, archive, compress, de-duplicate, role retention; docs/guide text | | | not started |

Nothing from these slices is on `master`: it needs Drake's explicit go-ahead (it deploys). After slice 8 comes refinement.

## How the engine fits together (bot, `com.younglings.bot.events`)

- `EventService` applies the rules; `EventRepository` owns the tables (`SchemaBootstrapper` creates them at startup, `ALTER ... IF NOT EXISTS` for new columns). Standings are never stored: `EventScoring` works them out from the append-only progress log.
- Stages: DRAFT, SIGNUPS, RUNNING, SCORING, DONE, CANCELLED (`EventRules`).
- `EventPrep` (pure): the "before it starts" checklist: build, signups, roster, verification, teams. Mode OFF/STANDARD/FULL decides whether steps are required. `EventRoster` (pure): verification states and the gate. `TeamBuilder` (pure): ratings, suggestion, "what stops the teams being final". `TeamRules`: who may join and share a team.
- Templates (`EventTemplate`: custom, bingo, scavenger-hunt, competition, boss-night). `EventOptions` is the **single registry of every creation option** (path, label, step, type, standard value, required, show-when, quick); `EventSetup` creates events from answers, keeps drafts (`event_draft`) and saved templates (`event_blueprint`); the website renders its three setup streams from the registry's schema, so a new option appears everywhere.
- `EventChanges` is a tiny bus (PROGRESS, STAGE, SETUP, CLAIM_DECIDED, ROSTER, TEAMS) that Discord-facing parts subscribe to (`EventBoardPanels` redraws the live bingo board).
- Website routes live in `EventsAdminApi` (+ `EventSetupApi`), reached through the dashboard's allow-listed server action `eventApiAction` (`src/app/dashboard/[guildId]/event-manager/actions.ts`). Discord commands: `/events ...` (`EventsCommand`), `/events team ...` (`EventsTeamCommand`).
- Website: `EventManager.tsx` (the event's tabs), `setup/` (the three streams, wizard, live summary), `teams/` (the team builder), `src/lib/eventOptions.ts` and `eventTeams.ts` (pure helpers mirroring the bot).

## Slice 5: roles and channels (built, PRs open)

What it does, per event that switches it on (`config.channels.enabled`, off by default), once signups are closed, the roster is confirmed and (team events) the teams are final:

- **Roles** the bot makes (recorded so it only ever touches its own): participant, organiser, one per team. Membership follows the effective roster (players who count at the current verification moment, so unverified players never get roles), teams and organisers, kept in step when they change.
- **Channels** (slots): category (plus overflow categories at 50 channels each), staff, announcements, general, board, submissions (staff queue), a private channel per team, optionally one per player for solo events. Names from patterns; each slot is created by the bot or an existing channel the admin picked (validated).
- **Access:** @everyone cannot see any of it; staff roles and organisers see and post everywhere; participants read announcements and the board, post in general; a team's role sees only its channel; a player sees only theirs.
- **Preview, then apply:** a pre-flight (bot permissions, role hierarchy, headroom under Discord's limits of 250 roles, 500 channels, 50 channels per category, 100 permission overwrites per channel) and a dry-run list of everything that will be created or changed, then apply; applying is idempotent and resumable (each created id is saved as it goes; pressing it again only creates what is missing and repairs drift). A way to remove everything the bot made for an event.
- Opt-in per event (`config.channels.enabled`), so existing events are unaffected; when on, "Roles and channels" becomes a required step of the checklist in an event that follows the setup steps.

### Where it lives

Bot (`com.younglings.bot.events`): `EventNaming` (names from patterns, Discord's style), `EventSpace` (pure: the settings `config.channels`, who sees what per kind of channel, the readiness rule, the `Plan` of what to create/keep/remove with Discord's limits, who should hold each role), `EventSpaceRepository` (tables `event_role`, `event_channel`, `event_role_member`; the applied/complete/note state is on `event_setup`), `EventSpaces` (reads the roster, teams, staff roles, server room and permissions, and previews), `EventSpaceOps` (does it on Discord: background setup and removal jobs, role holders kept in step on roster/team/stage changes, member joins and a 15-minute sweep). Dashboard routes: `internal/EventSpaceApi` (`GET events/{id}/space`, `PUT .../space/settings`, `POST .../space/preview|apply|remove|sync`). Tests: `EventNamingTest`, `EventSpaceTest`, `EventSpaceRepositoryTest`, `EventSpaceApiTest`, `EventSpaceOpsTest` (a pretend Discord server), and the checklist step in `EventPrepTest`.

Website: `components/dashboard/space/ChannelsTab.tsx` (the Channels tab), `lib/eventSpace.ts` (pure helpers), types in `lib/eventTypes.ts`, allow-list entries in `event-manager/actions.ts`, a button on the checklist step in `EventManager.tsx`.

### Choices I made that Drake may want to change (unconfirmed)

- Default names are short (`{event} Staff`, `{event} Announcements`, `{event} General`, `{event} Board`, `{event} Review`, `{event} {team}`, `{event} {player}`; roles `{event} Participant`, `{event} Organiser`, `{event} {team}`); his example `{event} General Channel` is just a pattern he can type. The patterns are per event; a server-wide default pattern setting was not built.
- Staff = the Admin group in the server's settings (plus anyone with the Administrator permission, who sees everything anyway). Organisers of an event get their own role. Players of a team event who aren't on a team yet still get the participant role.
- Team roles are only made when team channels are on (roles are scarce: 250 per server). Per-player channels (solo events) are off by default.
- A channel an organiser picks is used as it is: JonnyBot never changes who can see it. The board and proof channels JonnyBot makes become the event's `boardChannelId` / `reviewChannelId`.
- Setting up again only makes what is missing; it does not repair permissions or names of channels that already exist (a possible refinement), and removing a team leaves its role and channel until "Bring them up to date" with the remove box ticked.
- Unverified players (past the gate) and players on the waitlist get no roles. A player who isn't in the Discord server yet is skipped and gets the role when they join (D10).
- No welcome message is posted in the new channels yet.

### What is not proven (never run against Discord for real)

Creating the category, roles and channels on a real server, the exact permission behaviour of the overwrites in Discord's client, role grants to real members, and how long Discord's channel-creation rate limit makes a big event take. A preview was checked read-only against the real server (staff roles, room, picked channels). **How to try it** (carefully, the dev bot lives in the real clan server, so everything made is real but hidden from members): make a small test event with yourself as the only player, switch on roles and channels on its Channels tab, close signups and confirm the roster, press Set up, look at the server's channel list as a member without the roles (nothing should be visible) and as yourself, then Remove them.

## Decisions I made that Drake may want to change

Setup defaults (one `standard` value per option in `EventOptions`/the templates' `options()`): starts a day after creation, runs a week, solo, clan only, no limit, mix freely, first come first served, no buy-in, no prizes, setup steps ON from the dashboard (OFF for Discord quick-create), verification off (gate before teams), bingo 5x5 RuneScape look boss-drop pool 50 per line 250 blackout, hunt 8 items 25 pts unordered 100 bonus, competition overall 1 point per 10,000 XP, boss night 10 kills 10 pts.
Verification: a pending player counts until the gate's moment; the deadline closes signups for every event that has one; confirming the roster survives late adds, reopening signups resets it.
Teams: ratings 1-5 (3 = unknown); the suggestion levels totals (strongest first onto the weakest team); strength = total XP of the linked RuneScape name; teams can still be changed after they are final and reopened until the event starts.

## What is not proven yet

Discord-side code has needed fixes the first time it ran for real every time. Not yet run in Discord: closing signups and the pause, the join messages with verification, `/events create` from the standard answers, `/events team ...`, board posting with the new looks. Test on the dev bot with Drake's account and his alt. The dev bot runs in the real clan server (`1495619657674264717`), so tests there are live: keep them small and clean up after. Lessons that bit before: a new enum slash option needs a resolver in `EventsResolvers` or the bot will not start; a new `ListenerAdapter` must be registered in `Bot.java`; starting the dev bot twice leaves two bots on one token.

## Open questions for Drake

- Does he like the standard defaults above (especially RuneScape as the default look and setup steps on by default)?
- Bingo customisation beyond the look: variable shape sizes (pyramid base, path length) need shapes drawn in code; fog of war, a title override, names under tiles, a free centre space?
- His `path.png` has two empty glyph boxes beside START and TREASURE CHEST (offered to paint them out).
- Wrap-up details (slice 8): what exactly to export, archive and delete, and the role-retention schedule.
