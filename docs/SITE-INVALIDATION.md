# Bot to site push invalidation

The website caches what it reads from the bot (`next: { revalidate: N }`, 10 s to 5 min). Those timers still apply, but when the bot changes data that a public page shows, it now also tells the site, and the page refreshes on its next visit instead of waiting out the timer.

## The flow

1. A repository write that changes public data calls `SiteInvalidator.signal(Area...)` (static, a no-op when no website is configured or in tests).
2. `SiteInvalidator` merges signals for one second (a roster sync writing 200 members is one call), then sends `POST <SITE_URL>/api/revalidate` on a background thread with header `X-Internal-Secret` (the same shared secret as every other bot-to-site call) and body `{"tags":["site:polls","hub:events"]}`. It never blocks the write; a failure is only logged (at most once a minute) and the timers are the fallback.
3. The site (`src/app/api/revalidate/route.ts`) compares the secret in constant time, accepts only tags of the shape `site:<name>` / `hub:<name>` (at most 30), and calls `revalidateTag(tag, { expire: 0 })` for each: the next request refetches from the bot (no stale copy).

Needs `SITE_URL` set on the bot (the website's public address; locally `http://localhost:3000`) and the same `INTERNAL_API_SECRET` / `JONNYBOT_INTERNAL_API_SECRET` pair that already links the two. With either missing the bot says so once at startup and the timers apply.

Clan sites (runeclans.com) are a second, optional target: `CLAN_SITES_URL` (e.g. `https://runeclans.com`). It gets the same shared tags plus per-site tags, see "Clan sites" below. Unset, clan sites refresh on their timers.

## Tag names

Kept in step in two places: `SiteInvalidator.Area` (bot) and `src/lib/cacheTags.ts` (site).

| Tag | What reads it |
|---|---|
| `site:<name>` | Every clan-site read of `/internal/site/<name>` (name = first path segment): `members`, `overview`, `member`, `leaderboard`, `records`, `events`, `polls`, `signups`, `news`, `options`, `online`, `feed`, ... |
| `hub:clans` | Clans hub directory and clan pages (`/internal/directory/clans...`) |
| `hub:events` | Hub events (`/internal/directory/events`, a clan's events) |
| `hub:leaderboards` | Hub leaderboards |
| `hub:sites` | Clan websites from the builder (`/internal/directory/sites/<slug>`) |

| Bot `Area` | Tags it sends | Written from |
|---|---|---|
| `ROSTER` | members, overview, member, leaderboard, records, hub:clans, hub:leaderboards | `ClanMemberRepository` (upsert, inactive, join date), `PlayerLinkRepository` (rename, unlink) |
| `EVENTS` | site:events, hub:events, hub:clans | `EventRepository` (create, update, archive, settings, stage, join, leave) |
| `POLLS` | site:polls | `PollRepository` (post, votes, close) |
| `SIGNUPS` | site:signups | `SignupRepository` (create, status, delete, entries) |
| `NEWS` | site:news | `SiteNewsService` (the news channels setting) |
| `OPTIONS` | site:options | `CommunitySettings` (site options) |
| `HUB` | hub:clans, hub:events, hub:leaderboards, hub:sites | `ClanProfileRepository` (profile, verification, suspension, shared events), `ClanSiteRepository` (a clan website saved) |

## Clan sites (T-364, T-393)

A builder v3 clan site tags each data read `site:<slug>`, `site:<slug>:<key>` and `clan-site:<slug>` (runeclans `packages/api-client/src/sites/siteData.ts`, `<key>` being the data source key from `packages/builder/src/v3/widgets`). A write that knows its server calls `SiteInvalidator.signal(guildId, Area...)`; a write that only knows a row (a poll vote, a signup entry, joining an event) calls `signalLater(() -> guildOf(row), Area...)`, and the lookup runs on the invalidator's own thread, never the write's. On flush the bot merges the areas per server, reads each server's site address (its clan profile slug) once, and sends `CLAN_SITES_URL` two kinds of request:

1. the shared tags (as `SITE_URL` gets them, for the hub pages and v2 clan site reads);
2. the per-site tags, in their own requests of at most 30, so a site that refuses their shape still gets the shared ones. They are never sent to `SITE_URL` (rsyounglings' route accepts only `site:<name>` / `hub:<name>`).

| Bot `Area` | Per-site tags on the server's own site |
|---|---|
| `ROSTER` | `site:<slug>:roster`, `:overview`, `:member`, `:leaderboard`, `:records` |
| `EVENTS` | `:calendarEvents`, `:discordEvents`, `:eventDetail`, `:eventStandings`, `:hubEvents` |
| `POLLS` | `:polls` |
| `SIGNUPS` | `:signups` |
| `NEWS` | `:discordFeed` |
| `OPTIONS` | none (site options are the home site's) |
| `HUB` | the whole site: `site:<slug>` and `clan-site:<slug>` |

So a poll vote in server A clears `site:<A's slug>:polls` and no other clan site's polls. A server without a site address (or one the website would refuse) sends only the shared tags. The website's route (runeclans `apps/runeclans/src/app/api/revalidate`, T-500) accepts `site:<name>`, `hub:<name>`, `site:<slug>:<key>` (key `[A-Za-z0-9-]{1,40}`) and `clan-site:<slug>`.

When adding a v3 data source that shows bot data, add its key to the matching `Area` here. The bot's own `SiteCache` still clears every server's keys for a route after a poll or signup write (its answers live seconds; narrowing it is left for later).

Not signalled (the timers cover them): player stat snapshots written by polling, Discord-side news messages, points and anything that is not in a table above. To cover another write, add one `SiteInvalidator.signal(Area.X)` after it succeeds; to add a page's tag, tag its fetch (`siteTag(path)` for `/internal/site` reads, `tagsForInternalPath` for `botCall`).

## Trying it locally

Set `SITE_URL=http://localhost:3000` on the bot, start both, change a poll or an event on the dashboard, and the public page shows it on the next reload. `curl -X POST localhost:3000/api/revalidate` without the secret answers 401.
