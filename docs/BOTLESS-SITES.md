# Clan websites without the bot (bot-less sites)

A clan can build, edit and publish a builder v3 website for a Discord server that RuneClan Bot is **not** in. Everything a site needs is
already stored by server id (site, blocks, layouts and versions, site roles, clan profile and address, pictures, the clan's stats), so the
bot serves those from its database. What only Discord can answer (who owns or manages the server) the website signs instead.

While this is in beta, only people on the **site builder beta list** may run a bot-less site. A server the bot *is* in works exactly as
before and never looks at the signed facts.

## 1. Signed sign-in facts (the contract)

Every `/internal/admin/...` request about a server the bot isn't in must carry, besides `X-Internal-Secret`, `X-Actor-Id` and `X-Guild-Id`:

| Header | Value |
|---|---|
| `X-RC-Actor` | base64url, no padding, of compact JSON `{"v":1,"userId":"<discord id>","guildId":"<discord id>","admin":bool,"owner":bool,"iat":<unix s>,"exp":<unix s>}` |
| `X-RC-Actor-Sig` | lowercase hex HMAC-SHA256 over the exact `X-RC-Actor` string, keyed with the internal secret (`INTERNAL_API_SECRET`, the same one as `X-Internal-Secret`) |

`admin` is true when Discord told the website the person has Administrator or Manage Server there; `owner` when they own the server.

The bot checks: the signature (constant time), `v == 1`, `iat <= now + 60`, `exp > now`, `exp - iat <= 300`, `guildId` equals `X-Guild-Id`,
and `X-Actor-Id` (when sent) equals `userId`. Anything missing or wrong is **403** with `{"error": "...", "botless": true}`.

The facts are used **only** when the bot isn't in the server (and only once the bot has finished connecting, so a server still loading never
passes as bot-less). Code: `internal/ActorFacts`, `internal/BotlessAdminApi`.

## 2. Who may do what

- Not on the beta list: `whoami` answers `{"allowed": false, "notOnBeta": true, ...}`; every other route is 403 `{"notOnBeta": true}`.
  The bot's owners are always allowed.
- **Owner** (site role): `owner: true` in the facts, or the person who claimed the site (the first to save its layout).
- **Admin**: `admin: true` in the facts.
- **Editor**: named by Discord user id in the site's roles (`editorUserIds`). Discord roles can't be seen without the bot, so the role lists
  (`editorRoleIds`, `memberRoleIds`, `staffRoleIds`) must stay empty and `GET clan-site/roles` reports
  `"discordRoles": {"available": false, "reason": "Discord roles are unavailable without the bot..."}`.
- Everyone else: no access. The same action rules as a bot-present site apply (editors edit, admins publish and change settings, owners
  delete and hand over).

The first time an owner or admin on the beta list uses a server, it is recorded as a bot-less site (`younglings.botless_site`). That puts it
in the builder beta, in the daily clan sync, and keeps the plan's leave grace from ever starting.

## 3. Admin routes for a bot-less server

All under `/internal/admin/`, with `X-Guild-Id` naming the server and the signed facts above.

| Route | Who | What |
|---|---|---|
| `GET whoami` | anyone with valid facts | 200 always: `{allowed, tier ("ADMIN" or "NONE"), botless: true, id, siteRole, beta, notOnBeta, isOwner, guildId (when allowed), areas}` |
| `clan-site...` | by site role | Every site builder route, the same as a bot-present server (`GET/PUT clan-site`, `clan-site/layout`, `.../publish`, `.../versions...`, `clan-site/publish`, `unpublish`, `delete`, `export`, `roles`, `roles/hand-over`). `GET clan-site` also has `botless: true` and `clanName`. |
| `GET clan-site/clan-name` | editor | `{clanName, verified: false}` |
| `PUT clan-site/clan-name` `{clanName}` | admin | The RuneScape clan the site shows (letters, digits, spaces, `-`, `_`, up to 20; empty clears it). Not verified while in beta. Stored as the server's clan name, which the stats and clan sync read; a sync of that clan starts right away. |
| `GET\|PUT clan-profile` | admin | The clan profile (address, name, visibility). `clan-profile/verify` needs the bot. |
| `PUT\|DELETE images/site/{slot}` | admin | The site's logo and banner. |
| `site-pictures...` | editor | The site's picture library, as on a bot-present server. |
| `GET structure` | editor | Empty `roles`, `channels`... with `botless: true` and `unavailable` (no Discord roles or channels can be seen). |
| `GET audit` | admin | The server's dashboard changes (`?q=clan-site` for the site's). |
| anything else | | **409** `{"error": "That needs RuneClan Bot in the server...", "needsBot": true, "botless": true}` |

Writes are rate limited per person and written to the server's audit log, as on the dashboard.

## 4. Public site routes (`/internal/directory/sites/{slug}/...`)

- `GET sites/{slug}`: as before, plus `"botless": true` for a bot-less site.
- `GET sites/{slug}/data/{route}`: the clan's stats come from the database by server id, so `members`, `overview`, `leaderboard`, `recap`,
  `feed`, `records`, `citadel-grid`, `history`, `pvm`, `drops`, `bosses`, `boss`, `item`, `drop-log`, `member`, `member/skill`,
  `member/recap` answer as usual. The Discord-only ones (`events`, `polls`, `signups`, `coffer`) answer `{"needsBot": true}`;
  `notifications` has only the activity lines, empty events, polls and signups, and `needsBot: true`.
- `GET sites/{slug}/data/discord/online|feed` and `GET|POST sites/{slug}/discord/me`: `{"needsBot": true}`.
- `GET sites/{slug}/viewer?userId=`: the claimant is `owner`, editors by user id are `editor` (with `guildId`), everyone else `guest`
  (without the bot nobody can tell who is in the server); `botless: true`.

## 5. The beta list (owner's console)

The bot's owner manages the list from the console (home server, owner only). The entries are user grants of the access-list area
`site-builder-beta` (`AccessGrantAdminApi`), so they also show under "Site builder without the bot (beta)" on the full access list.

| Route | What |
|---|---|
| `GET site-builder-beta` | `{area: "site-builder-beta", users: [{userId, note, grantedBy, grantedAt, expiresAt, expired}]}` |
| `POST site-builder-beta` `{userId, note?}` | Adds the person (adding them again keeps one entry). |
| `DELETE site-builder-beta/{userId}` | Takes them off (404 when they aren't on it). |

`whoami` on any server reports the area as `areas["site-builder-beta"]`, from a grant alone (being a beta server's admin doesn't count).

## 6. Clan stats and polling

The daily clan sync (`ClanSyncScheduler`, 00:00 UTC) now groups the bot's servers **and** bot-less sites by clan name, so a clan is still
fetched and polled once. A bot-less site gets its own roster rows, join dates and roster events by server id; nothing is posted or changed
in Discord for it (no join or leave posts, rename checks or roles). Setting the clan name syncs that clan straight away (while auto-polling
is on).

## 7. Billing

Premium and billing stay off. A bot-less site counts as "bot present" for its plan: the 14-day leave grace never starts and its
subscription is never sent to be cancelled for the bot's absence.

## 8. When the bot joins later

Once the bot is in the server, every request takes the normal path (Discord roles, dashboard tiers) and the facts are ignored. The site and
everything stored for it carry over unchanged; editors named by user id keep working.
