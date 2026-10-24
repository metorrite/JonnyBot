# Bot to site push invalidation

The website caches what it reads from the bot (`next: { revalidate: N }`, 10 s to 5 min). Those timers still apply, but when the bot changes data that a public page shows, it now also tells the site, and the page refreshes on its next visit instead of waiting out the timer.

## The flow

1. A repository write that changes public data calls `SiteInvalidator.signal(Area...)` (static, a no-op when no website is configured or in tests).
2. `SiteInvalidator` merges signals for one second (a roster sync writing 200 members is one call), then sends `POST <SITE_URL>/api/revalidate` on a background thread with header `X-Internal-Secret` (the same shared secret as every other bot-to-site call) and body `{"tags":["site:polls","hub:events"]}`. It never blocks the write; a failure is only logged (at most once a minute) and the timers are the fallback.
3. The site (`src/app/api/revalidate/route.ts`) compares the secret in constant time, accepts only tags of the shape `site:<name>` / `hub:<name>` (at most 30), and calls `revalidateTag(tag, { expire: 0 })` for each: the next request refetches from the bot (no stale copy).

Needs `SITE_URL` set on the bot (the website's public address; locally `http://localhost:3000`) and the same `INTERNAL_API_SECRET` / `JONNYBOT_INTERNAL_API_SECRET` pair that already links the two. With either missing the bot says so once at startup and the timers apply.

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

**Clan sites (T-364).** A clan site's data read (`/internal/directory/sites/<slug>/data/<route>`) is tagged `hub:sites` plus `site:<route>` by `tagsForInternalPath`, so every signal above clears the same answer on every clan site as on the home site (`site:polls` clears every site's polls). The bot's own cache does the same: a clan site's answer is cached under `<route>?<query>#g<server id>`, and the `SiteCache.invalidate("<route>")` calls after poll and signup writes clear those keys with the home server's. Per-site tags (`site:<slug>:<key>`, builder v3 section 5.3) need the site's `/api/revalidate` to accept that shape first; until then the bot keeps sending the shared tags.

Not signalled (the timers cover them): player stat snapshots written by polling, Discord-side news messages, points and anything that is not in a table above. To cover another write, add one `SiteInvalidator.signal(Area.X)` after it succeeds; to add a page's tag, tag its fetch (`siteTag(path)` for `/internal/site` reads, `tagsForInternalPath` for `botCall`).

## Trying it locally

Set `SITE_URL=http://localhost:3000` on the bot, start both, change a poll or an event on the dashboard, and the public page shows it on the next reload. `curl -X POST localhost:3000/api/revalidate` without the secret answers 401.
