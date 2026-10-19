# RSN link system: design and protocol spec

Ticket T-112. Status: **draft; Drake's decisions of 2026-10-19 applied (section 18)**. Build tickets T-113..T-118 follow this document; a build worker who finds a gap here asks (ticket back to `planning`) rather than inventing.

## 0. In one paragraph

A standalone RS3 plugin named **RS Link** (Drake's pick, section 15) lets a player link the character they are logged into to their Discord account. The plugin shows a short code and a short web address; the player opens the address on any device, signs in with Discord, types the code and confirms "Link RuneScape name X to this Discord". The plugin then holds a private random token and never shows it to anyone. Other plugins depend on it and can ask only two things: "is this player linked?" and "what is this player's alias for my plugin?" (a stable random-looking id that is different for every plugin). For players who prefer it, a fallback has JonnyBot DM them a code instead, which only works when JonnyBot can reach them on Discord. Trust is implicit for now, with a `verification` field and a hook so a real check can be added later. The service has its own database, its own keys and a neutral, unbranded web face whose address is configuration, so the final domain can be chosen before the Jagex submission.

## 1. Fixed decisions (from Drake's Q&A, 2026-10-19; not re-opened here)

- Generic: any plugin can depend on it. JonnyBot and RuneSync are its first users, nothing more.
- Primary pairing: Discord sign-in, device-code style. Fallback: a DM code, only when the bot can DM the player.
- Codes expire (about 10 minutes), wrong attempts lock out, cooldowns per account and per IP. No server membership or slash command needed.
- Sign-in page: neutral and unbranded (same visual theme is fine; no clan name, no ads, no "personal gain" look). Base URL is configuration; final domain decided before submission.
- Dependents get "is linked" plus a per-plugin alias only. Raw lookups (RSN to Discord and back) are not in v1.
- Trust: implicit for now; no staff verification ever; verification field and hook for later (T-122).
- Plugin: MIT, public repo (starts private until Drake says). The server stays private.
- Database: dedicated, local dev database for now; hosting region decided at release (T-120).

## 2. The pieces

```
  RS3 client (player's PC)                     Neutral link host (website app, own host face)          JonnyBot process
 +---------------------------+   HTTPS         +--------------------------------------+   internal   +------------------------------+
 | RS Link plugin (Lua)      | --------------> | /v1/*   plugin API (proxy + limits)  | -----------> | rsnlink service (Java)       |
 |  - pairing panel          |                 | /       code entry + Discord sign-in |  X-Internal- |  - own DataSource            |
 |  - token in PersistentDB  |                 | /links  my links, unlink, delete     |   Secret     |  - own schema `rsnlink`      |
 |  - library for dependents |                 +--------------------------------------+              |  - DM fallback via JDA       |
 +---------------------------+                         ^ browser (any device)                         +--------------+---------------+
        ^ functions only (no token)                    |                                                             |
 +---------------------------+                  Discord OAuth2 (identify)                              Link database (separate DB)
 | Dependent plugin          |                                                                       local: `jonnybot_link` on dev Postgres
 +---------------------------+
```

- **RS Link plugin** (new repo, T-116): Lua 5.4. Shows the pairing panel, talks to the link host, stores the token, exports a small library.
- **Link host face** (website, T-114): a separate host face of the existing Next.js app, chosen by hostname like the `api.`/`clans.`/`jonnybot.` faces in `src/proxy.ts`. It serves the plugin API (thin proxy with its own per-IP limits, the same pattern as `src/lib/pluginProxy.ts`) and the sign-in pages. It shares no cookies, sessions or nav with the clan site or the dashboard.
- **rsnlink service** (bot, T-113): a new package `com.younglings.bot.rsnlink` inside the JonnyBot process for now (Drake, 2026-10-19), exposed only on the internal port behind `X-Internal-Secret`. It becomes its own service when Drake is ready to transition, so the module boundary is kept clean enough that the split is a move, not a rewrite:
  - **its own DataSource** (`LINK_DATABASE_URL`), schema, keys and config; it never touches the clan schema `younglings`;
  - it imports nothing from other bot packages except a tiny shared HTTP/bootstrap layer, and other packages never import its internals; a unit test checks both directions;
  - what it needs from JonnyBot sits behind two interfaces: `LinkDmSender` (send a DM, resolve a username; JDA today, a small bot-side endpoint after the split) and `LinkHttpRoutes` (mounting its routes); the `/internal/rsnlink/*` contract, tables and keys move unchanged;
  - consumers inside JonnyBot (T-121) use only the same internal routes a separate service would expose, never its classes.
- **DM fallback** (bot, T-115): uses JonnyBot's gateway connection to send the code. This is the one place the JonnyBot name appears, because it is the bot that sends the DM.
- **Abuse view** (website `/admin` Super admin + bot `/internal/admin/rsnlink/*`, T-118).

Why the website proxies instead of the plugin calling the bot: the bot's port is not public, the website already owns TLS, hosts and per-IP limits, and one manifest base URL then covers both the API and the page the player is told to open.

## 3. Research answers

### 3.1 Jagex plugin guide (developer.runescape.com, cleared for AI reading)

Paraphrased from the guide pages named in brackets; no Jagex text is copied.

| Question | Answer | Design consequence |
|---|---|---|
| How does a dependency expose functions? [Plugin Structure] | `plugin.json` lists it in `requiredDependencies` / `optionalDependencies` (array = imported under its plugin name; object = `"VarName": "PluginName"` to rename). The dependency loads first; whatever its `main.lua` returns (a table of functions in the examples) is cloned and handed to the dependent as a variable named after it. Dependents call `RSLink:IsLinked()`. Optional dependencies can be nil, so dependents check first. A global `PluginDependencyUpdate(pluginIdentifier, enabled)` is called when an optional dependency is turned on or off. Cycles are an error. Names that are not valid Lua identifiers get `_` substitutions. | The name "RS Link" has a space, so the array form would import it as `RS_Link`. Dependents should use the object form `{ "RSLink": "RS Link" }` and call `RSLink:...`; the README says so. T-116 checks in the beta client which string the import actually uses (the manifest `name` or the plugin folder id). Export **functions only**: data fields in a cloned table would be snapshots, while functions keep reading the plugin's live internal state. The guide also says exported tables may be shared across imports, so the library must not hold per-dependent state in the exported table. |
| Does a dependency's HTTP use its own manifest URLs? [Plugin Structure, Plugin, HTTP] | **Not documented.** `HTTPRequest.new(base, path)` takes a base from `Plugin.urls`, and only manifest base URLs are allowed, but nothing says whose manifest and permissions apply when a dependent calls a dependency function that makes a request. | Exported functions **never make HTTP calls themselves**. They read cached state or put a request on a queue; RS Link's own event subscription (registered by RS Link, so running as RS Link) sends the queue and calls the dependent's callback afterwards. Dependents need no `httpAccess` and no URL for linking. Question goes to Jagex via T-119. |
| Can dependents read the dependency's PersistentDB? [Persistent DB] | Records are isolated per plugin; one plugin cannot read or change another's. Not said directly for dependents, but isolation implies no. Scope per character or per account is not documented, and so is when data is written to disk (examples set values on shutdown). | The token lives in RS Link's PersistentDB, keyed per character inside (section 9.3) so it works whether storage is per account or per PC. Save straight after pairing, not only in `PluginShutdown` (shutdown does not run on a crash). |
| How to show a code and a text box? [Simple UI tutorial, Input Field, Text, Button] | Attach a GroupBox to `TOPLEVEL_V2__PLUGIN_BUILD_LAYER_TOP` with a stylesheet (invisible without one); `ui.Text` needs a font; `ui.Button` fires `ONBUTTONPRESSED`. `ui.InputField` fires `ONCONTENTCHANGED` with a cause (`SUBMIT` on Enter, Escape, focus loss), `Setup(visibility, filter, maxLength)` sets a filter (`ALPHANUMERIC`, `NUMERIC`, ...) and length. Masked fields (`HIDDEN`) cannot be read back. | Code box: `ALPHANUMERIC`, max 9, visible. Discord username-or-id box: filter `NONE` (usernames contain `.` and `_`), max 32, checked by the rules in 4.2. No documented clipboard or "open URL" function, so the player **types** the address and code: both must be short and unambiguous. |
| Character identity [Player] | `Player.GetTrackingID()` returns a 64-bit per-character value or nil (needs `trackingAccess`; arrives a few seconds after login). Stability across name changes is not documented. Display name comes from the player entity (`Player.GetEntity()`). | Tracking id is optional but strongly recommended; the service keys characters on it when present (RSN recycling, section 11). Plugin waits up to 10 s for it before pairing. Ask Jagex whether it survives name changes (T-119). |

Still unknown, for T-119: HTTP rate limits and timeouts; dependency HTTP context; PersistentDB scope; tracking id stability; whether any Jagex-signed proof of the logged-in player is coming.

### 3.2 Discord

- **No device grant.** Discord OAuth2 supports the authorization code grant (with refresh), implicit and client credentials; there is no RFC 8628 device grant. So "device-style" is built on top: our own short user code typed on our page, plus a normal authorization code sign-in in the browser.
- **`identify` scope** returns the user's id, username, global name and avatar, without email. That is all we ask for. We use the access token once to read `/users/@me`, then **throw it away** (no refresh token stored).
- **State and PKCE:** Discord returns `state` and an `iss` value (`https://discord.com`) on the redirect; we require both to match. PKCE is only mentioned in passing in Discord's docs, so the server also uses the client secret (confidential client) and sends PKCE S256 as an extra.
- **Consent screen shows the OAuth application's name.** Decision (Q2): a **separate, neutral Discord application** eventually, **JonnyBot's application for now**. Client id, secret and redirect URL are configuration (`LINK_DISCORD_CLIENT_ID` / `LINK_DISCORD_CLIENT_SECRET` / `LINK_PUBLIC_BASE_URL`), so the switch is a config change plus registering the redirect URL on the new app; no code change. Discord does not allow "discord" inside usernames, so the separate app is simply named "RS Link" (section 15).
- **DMs.** Discord's Create DM docs say DMs should generally follow a user action, must not be used to message a whole server, and that opening many DMs quickly gets a bot rate limited or blocked from opening new ones. The pages read do **not** state the mutual-server rule or what a user-installed app may do.
- **Usernames are unique.** Since Discord's username change, a username is unique and lowercase: letters, digits, `.` and `_` (Discord blog post on usernames); old `name#1234` tags keep working as aliases. The developer docs' user page still calls usernames "not unique" (wording from the discriminator days) and gives 2 to 32 characters, so the id stays the stored key and a username is only an input to resolve.
- **Resolving a username as a bot:** there is no bot API to look a user up by username across Discord. A bot can only find members of servers it is in (its member cache, or each server's member search). That loses nothing here, because the DM can only reach someone who shares a server with JonnyBot anyway (next bullet).
- **Honest limit for the DM fallback:** a bot can only DM a user it shares a server with, and only if that user allows DMs from members of that server (Discord's per-server privacy setting); otherwise the send fails with "Cannot send messages to this user". Whether a **user-installed** JonnyBot can DM the person who installed it is **not documented**; T-115 treats it as unsupported until Drake tests it with the dev bot (test step in T-115). So in practice the fallback reaches only players who are in a server with JonnyBot and have server DMs on, which is a small slice of all RS3 players. Sign-in is the path that always works.

## 4. Flows

### 4.1 Primary: sign in with Discord (device-style)

1. Player opens the RS Link panel and presses **Link Discord**. Plugin reads the display name and (if granted) tracking id, then `POST /v1/pair/start`.
2. Service creates a pairing and returns `userCode` (shown) and `deviceSecret` (never shown). Panel shows:
   *"On your phone or PC go to **link.example.org** and enter **BHKD-7Q2M**. Expires in 10 minutes."* (host from config).
3. Plugin polls `POST /v1/pair/poll` every `interval` seconds with the device secret.
4. In the browser, the page asks for the code first (`POST /web/code`). Valid code: page says "Code accepted. Now sign in with Discord." and starts OAuth. The RSN is **not** shown before sign-in, so a guessed code reveals nothing.
5. After Discord sign-in, the **confirm screen** shows, large: *"Link RuneScape name **Zezima** to Discord **@someone**?"* with the warning *"Only continue if you are logged in to RuneScape as Zezima right now and this code is on your own screen. Never enter a code someone sent you."* Buttons: **Link** / **This isn't me**.
6. **Link**: the service creates or reuses the link (section 9.2 rules) and marks the pairing approved. **This isn't me**: pairing denied, abuse event recorded.
7. The next poll returns `linked` with the **token, once**. Pairing becomes `consumed`; polling again returns `expired_or_unknown`. Plugin saves the token at once.
8. If the plugin never collects the token (game closed), the link still exists; the player just pairs again and the same link is reused (same alias), with a new token.

### 4.2 Fallback: DM code

1. In the panel the player types their Discord **username or user id** (decision Q4) and presses **Send me a code**. `POST /v1/pair/dm`.
   - Input rules: all digits and 17 to 20 long = an id; otherwise a username: trimmed, a leading `@` dropped, lowercased, 2 to 32 of `a-z 0-9 . _`. Old `name#1234` tags are refused with "type your new username".
   - Username resolution (T-115): exact username match only (never display names or nicknames), first JonnyBot's member cache, then each shared server's member search, stopping at the first hit; a username that matches two different ids is treated as not found. It runs in the background after the reply, never while the request waits, so response timing leaks nothing.
2. The service always answers the same way (`202`, a pairing id and device secret), whether or not a DM could be sent, so the endpoint cannot be used to learn whether an id or username exists, shares a server with JonnyBot or accepts DMs (decision Q3). The panel says: *"If JonnyBot can reach you, a DM with a code arrives within a minute (check Message Requests). No DM? Use Sign in instead; it always works."*
3. JonnyBot DMs (neutral text, section 13.3) the code, the RSN that asked, and two buttons: **Not me** (denies the pairing) and **Stop these messages** (opts the Discord ID out of DM codes for 90 days).
4. Player types the code into the panel: `POST /v1/pair/dm/confirm`. Correct code returns the token once. Five wrong codes lock the pairing.

### 4.3 After pairing

- On each login (first `ServerLogic` tick with a display name, and tracking id if granted) the plugin calls `POST /v1/session` with the token: updates last-seen and reports a new display name if it changed (section 11).
- Dependents ask the library; the library answers from cache or queues a call (section 10).
- Unlink from the plugin: `POST /v1/unlink`, then the token is deleted from PersistentDB.

## 5. API

All JSON, UTF-8, `Cache-Control: no-store`. Public paths are on the link host (`<LINK_PUBLIC_BASE_URL>/v1/...`, also `/api/link/v1/...` on any host for local dev). The website forwards to the bot at `/internal/rsnlink/...` with `X-Internal-Secret`; the bot is the authority for every rule. Errors are `{ "error": "<code>", "message": "<plain words>", "retryAfter": <seconds, optional> }`.

### 5.1 Plugin routes

| Route | Auth | Body | Success |
|---|---|---|---|
| `POST /v1/pair/start` | none | `{ "rsn": "Zezima", "trackingId": "123..." or null, "client": { "name": "RS Link", "version": "1.0.0" } }` | `201 { "pairingId", "deviceSecret", "userCode": "BHKD-7Q2M", "verifyUrl": "https://link.example.org", "expiresIn": 600, "interval": 5 }` |
| `POST /v1/pair/poll` | device secret in body | `{ "pairingId", "deviceSecret" }` | `200 { "status": "pending" }`, `{ "status": "slow_down", "interval": 10 }`, `{ "status": "denied" }`, `{ "status": "expired_or_unknown" }`, or `{ "status": "linked", "token": "lnk_example-token-not-real", "verification": "implicit" }` (token only on this one response) |
| `POST /v1/pair/dm` | none | `{ "rsn", "trackingId", "discord": "someone" or "1234...", "client" }` | always `202 { "pairingId", "deviceSecret", "expiresIn": 600 }` |
| `POST /v1/pair/dm/confirm` | device secret | `{ "pairingId", "deviceSecret", "code" }` | `200 { "status": "linked", "token", "verification" }`; wrong code `400 { "error": "wrong_code", "attemptsLeft": 3 }`; `423 locked` |
| `POST /v1/session` | `Authorization: Bearer lnk_...` | `{ "rsn", "trackingId" }` | `200 { "linked": true, "verification": "implicit", "discordName": "someone" }` or `401 token_revoked` |
| `POST /v1/alias` | Bearer | `{ "plugin": "RuneSync" }` | `200 { "alias": "a1_7k2m...", "assertion": "<signed, 15 min>" }` |
| `POST /v1/unlink` | Bearer | `{}` | `200 { "unlinked": true }` (revokes every token on that link) |
| `POST /v1/forget-me` | Bearer | `{ "confirm": "DELETE" }` | `200`: deletes this character, its links and tokens (the plugin's "delete my data") |
| `GET /v1/keys` | none | | public key set for checking assertions (section 8.3) |

`discordName` goes only to the RS Link panel so the player sees "Linked to @someone"; the library never passes it on.

Formats: user codes are 8 characters from the 27-symbol alphabet `BCDFGHJKMNPQRSTVWXZ23456789` (no vowels, so no accidental words; no 0/O, 1/I/L look-alikes), shown as `XXXX-XXXX`, drawn with `SecureRandom`. Device secrets are 32 random bytes, base64url. Tokens are `lnk_` + 43 base64url characters (32 random bytes), the same shape as `PluginTokens` but a different prefix so scanners and code can tell them apart. Pairing ids are random UUIDs.

Code input is forgiving: case-insensitive, spaces and hyphens ignored. RSN is cleaned with the same rule as `PluginPayload.normaliseRsn` (non-breaking spaces made ordinary, 1 to 12 of letters, digits, space, `-`, `_`); the lookup form lowercases it and treats space, `_` and `-` alike.

### 5.2 Sign-in page routes (cookie session on the link host only)

| Route | What |
|---|---|
| `GET /` | Code entry page (the address the player types). `?code=` pre-fills, for players reading the code from a phone photo. |
| `POST /web/code` | `{ "code" }` checks the code, binds the pairing to this browser session. |
| `GET /web/auth/discord`, `GET /web/auth/callback` | OAuth start and callback (state, `iss` and PKCE checked; token used once and dropped). |
| `GET /web/pairing` | What the confirm screen shows (RSN, Discord name), only after sign-in. |
| `POST /web/confirm` | `{ "decision": "link" | "deny" }`. |
| `GET /links` (page) + `GET /web/links` | The signed-in Discord's links: RSN, linked date, last seen, verification, method. |
| `POST /web/unlink` | `{ "linkId" }`. |
| `POST /web/delete-my-data` | `{ "confirm": "DELETE" }`, signs the session out after. |
| `POST /web/signout` | |

All POSTs need the session cookie (`HttpOnly`, `Secure`, `SameSite=Lax`, host-only, `__Host-` prefix) plus a CSRF token. No other cookies, no analytics.

### 5.3 Bot internal routes

`/internal/rsnlink/...` mirrors 5.1 and 5.2 (the website passes the client IP and, for web routes, the signed-in Discord id it got from the callback). Super admin routes for T-118 live under `/internal/admin/rsnlink/...` and are owner-only (`BotOwners`), audit-logged.

## 6. Data model (dedicated database)

Database from `LINK_DATABASE_URL`; locally a new database `jonnybot_link` on the dev Postgres (not the `younglings` schema, not a copy of live). Schema `rsnlink`, created by the service's own bootstrapper (`CREATE ... IF NOT EXISTS`, the house pattern) with a `schema_version` row so later changes are ordered migrations.

| Table | Columns (main ones) | Notes |
|---|---|---|
| `discord_user` | `id uuid pk`, `discord_lookup bytea unique` (HMAC of the id), `discord_enc bytea` (AES-256-GCM), `key_version smallint`, `dm_optout_until timestamptz`, `created_at` | The raw Discord id is never stored in plain text. Lookups use the HMAC. |
| `character` | `id uuid pk`, `tracking_lookup bytea unique null` (HMAC of tracking id), `rsn_display text`, `rsn_lookup text`, `last_seen_at`, `created_at` | `rsn_lookup` is the lowercased form; not unique (recycling, section 11). |
| `link` | `id uuid pk`, `character_id`, `discord_user_id`, `status` (`active`, `unlinked`, `released`, `superseded`), `method` (`oauth`, `dm`), `verification text not null default 'implicit'`, `verification_data jsonb`, `verified_at`, `created_at`, `ended_at` | Partial unique index: one `active` link per character. A Discord user may have up to 20 active links (several characters). |
| `link_token` | `id`, `link_id`, `token_hash bytea unique` (SHA-256), `client text`, `created_at`, `last_used_at`, `revoked_at` | At most 5 active per link (several PCs); the oldest is revoked when a sixth is issued. |
| `pairing` | `id uuid pk`, `method`, `code_lookup bytea` (HMAC), `device_secret_hash bytea`, `rsn_display`, `rsn_lookup`, `tracking_lookup`, `target_discord_lookup` (DM only), `discord_user_id` (after sign-in), `browser_session_hash`, `status` (`pending`, `approved`, `denied`, `consumed`, `expired`, `locked`), `attempts smallint`, `created_at`, `expires_at`, `ip_lookup` | Deleted 24 h after it ends. Unique `code_lookup` among pending rows; a collision just draws again. |
| `rsn_history` | `character_id`, `rsn_display`, `seen_from`, `seen_to` | Shown only to the owner on `/links`. |
| `abuse_event` | `id`, `at`, `kind`, `subject_kind` (`ip`, `discord`, `tracking`, `rsn`), `subject_lookup bytea`, `detail jsonb` (counts, never codes or tokens) | Kept 30 days. Feeds T-118. |
| `block` | `subject_kind`, `subject_lookup`, `reason`, `until`, `created_by`, `created_at` | Owner-set or automatic (section 8). |
| `admin_audit` | `at`, `actor`, `action`, `target` | T-118 actions. |

Every "lookup" column is an HMAC-SHA-256 with a server key, never a plain hash: Discord ids, tracking ids, IPs and 8-character codes are guessable, so an unkeyed hash would not protect them.

### 6.1 Keys and secrets (all from env; never in git, never logged)

| Env | Use |
|---|---|
| `LINK_DATABASE_URL` | The link database. |
| `LINK_ENC_KEY` (+ `LINK_ENC_KEY_OLD` during rotation) | AES-256-GCM for Discord ids; `key_version` per row allows rotation by re-encrypting in the background. |
| `LINK_LOOKUP_KEY` | HMAC for the lookup columns. |
| `LINK_ALIAS_KEY` | HMAC for aliases (separate so it can never be rotated by accident: rotating it changes every alias). |
| `LINK_SIGNING_KEY` | Ed25519 private key for alias assertions; public half published at `/v1/keys`. |
| `LINK_DISCORD_CLIENT_ID`, `LINK_DISCORD_CLIENT_SECRET` | The OAuth application used for sign-in (website side only): JonnyBot's app for now, the separate neutral app later. |
| `LINK_PUBLIC_BASE_URL` | Section 14. |

The service refuses to start if a key is missing or shorter than 32 bytes. Local dev keys go in the git-ignored env files only. Tests build random keys at runtime; no key-shaped literal appears in the repo.

## 7. Lifetimes

| Thing | Lifetime |
|---|---|
| Pairing and its user code (both flows) | 10 minutes, one use |
| Poll interval | 5 s; `slow_down` adds 5 s each time a plugin polls too fast |
| OAuth `state` / PKCE verifier | 10 minutes, one use |
| Discord access token | not stored: used once in the callback, then dropped |
| Sign-in page session | 15 minutes idle, 1 hour max; only for pairing and managing links |
| Link token | no fixed expiry; revoked by unlink, delete, a sixth device, or 365 days unused |
| Alias assertion | 15 minutes |
| Ended pairings | deleted after 24 h |
| Ended links and their tokens | deleted 30 days after unlink (so an accidental unlink can be shown on `/links`), at once on delete-my-data |
| Abuse events | 30 days |
| DM opt-out | 90 days, renewable from the DM button |

## 8. Rate limits, lockouts and blocks

Two layers: the website applies coarse per-IP limits before forwarding (as `pluginProxy.ts` does), the bot applies the real ones below. The bot runs as one instance today, so in-memory token buckets are fine; counters that must survive a restart (lockouts, blocks, DM caps) are in the database. All numbers are constants in one class so they can be tuned.

| Action | Limit | On breach |
|---|---|---|
| Wrong code on one pairing (web or DM confirm) | 5 attempts | pairing `locked` |
| Code entry on the web, per IP | 10 per minute, 30 per hour | 429 |
| Wrong codes per signed-in Discord | 10 per hour | that Discord cannot enter codes for 1 hour |
| `pair/start` per IP / per tracking id / per RSN | 10 / 5 / 5 per hour | 429 |
| `pair/dm` per target Discord | 1 per 10 minutes, 3 per day, none while opted out | silently not sent (same 202) |
| `pair/dm` per IP / per tracking id | 5 / 3 per hour | 429 |
| DMs in total (circuit breaker) | 300 per hour across the service | new DM requests silently not sent; owner alerted in T-118 |
| Poll per pairing | 1 per interval | `slow_down` |
| Token routes per token | 60 per minute | 429 |
| Alias route per token | 30 per minute (library caches) | 429 |
| Repeat offenders | 3 lockouts of the same IP, tracking id or Discord in 24 h | automatic `block` for 24 h, shown in T-118 |

429 answers carry `Retry-After`. Blocks answer exactly like rate limits, so a blocked party learns nothing extra.

## 9. Links, unlink and delete-my-data

### 9.1 What a link is

One character (tracking id if known, else the RSN) linked to one Discord account. A Discord account can link several characters. A character has at most one active Discord link.

### 9.2 Creating a link on approval

- Same character and same Discord already active: **reuse** the link (same id, so the same aliases), issue a new token.
- Same character (same tracking id), different Discord: the new link becomes active, the old one `superseded`. Only someone running the plugin as that character can present its tracking id, so this is the owner moving to another Discord.
- RSN already actively linked under a different tracking id, or a claim without a tracking id for an RSN whose link has one: see section 11 (recycling and forgery).
- Otherwise: new character row (if needed) and new link.

### 9.3 Plugin side storage

PersistentDB keys: `token.<characterKey>` where `characterKey` is the tracking id as a string if granted, otherwise the RSN lookup form; plus `ui.*` for panel settings. Saved right after pairing and after unlink. Never exported.

### 9.4 Unlink

- From the plugin (`/v1/unlink`): this character's link ends, all its tokens revoked, the plugin deletes its stored token. Dependents get `onLinkChanged(false)`.
- From the web (`/links`): same, for any of the signed-in Discord's links. The plugin finds out on its next `/v1/session` (401 `token_revoked`), deletes the token and fires `onLinkChanged(false)`.

### 9.5 Delete my data

- **Web** (signed in with Discord): deletes the `discord_user` row, every link, token, pairing and history row of theirs, and abuse events that point at their Discord lookup. Only an active `block` is kept (as an HMAC, no readable id) until it ends, because otherwise deleting would be a way around a block; the privacy text says so.
- **Plugin** (`/v1/forget-me`): deletes this character, its links, tokens and history. The plugin then clears its PersistentDB.
- Both are immediate hard deletes. Backups: the honest wording on how long backups keep data is written at release once hosting is chosen (T-120).

## 10. Plugin library for dependents

Exported from RS Link's `main.lua`. Functions only; none returns the token, the Discord id or the Discord name.

```lua
-- In the dependent's plugin.json: "requiredDependencies": { "RSLink": "RS Link" }  (object form, see 3.1; or optionalDependencies)

RSLink:ApiVersion()                 -- 1
RSLink:IsLinked()                   -- true / false / nil (nil = not known yet this session)
RSLink:GetVerification()            -- "implicit" today; later values in section 12
RSLink:GetAlias("MyPlugin", function(alias, err) end)
RSLink:GetAssertion("MyPlugin", function(assertion, err) end)   -- signed, for the dependent's own server
RSLink:OnLinkChanged("MyPlugin", function(linked) end)          -- one callback per name, replaced on re-subscribe
RSLink:OpenLinkPanel()              -- opens the pairing panel for the player
```

Rules for the implementation (T-116):
- No HTTP inside these functions (section 3.1): calls go on a queue that RS Link's own `ServerLogic` subscription sends. Callbacks run later from RS Link's loop, wrapped in `pcall` so a dependent's error cannot break RS Link.
- Aliases are cached per plugin name for the session.
- `err` values: `"not_linked"`, `"offline"`, `"rate_limited"`, `"bad_name"`.
- Plugin names for aliases: trimmed, 1 to 64 of letters, digits, `.`, `_`, `-`; compared lowercase.

## 11. RSN changes, recycling and forged claims

- **Rename:** `/v1/session` with the same tracking id and a new name: the character's `rsn_display` changes, the old one goes to `rsn_history`. Link and aliases stay.
- **Recycling** (a name released by one player and taken by another): the new owner's pairing carries a different tracking id, so it is a different character. If the old character with that name has **not** reported the name for 30 days, its link is marked `released` (the name is clearly no longer theirs) and the new link is made. If it reported the name within 30 days, the claim is refused with a generic "This name can't be linked right now; if it is yours, try again later or use the plugin with tracking allowed" message.
- **Claims without a tracking id:** allowed (tracking is optional), but they cannot take a name that is actively linked with a tracking id; they get the same generic refusal. A tracking-id claim can take over a tracking-less link for the same name after the confirm step (the tracking-less one becomes `superseded`).
- **Forged claims** (someone calling the API with curl and a name they don't own): with implicit trust the service cannot tell a real client from a script. What limits the harm: a forger cannot know another player's tracking id, so they cannot take over a tracked link; dependents only ever see an alias and the `implicit` level; and section 12's hook lets a real check be added. This is exactly why links are `implicit`, and consumers that need strong proof (clan ranks in T-121) keep their own checks.

## 12. Trust and the verification hook

- `link.verification` is a text value: `implicit` for every link in v1. Planned values: `tracking_consistent`, `hiscores_challenge`, `jagex_signed`. `verification_data` (jsonb) and `verified_at` hold the evidence. No migration is needed to add a method.
- Java hook: `interface LinkVerifier { String method(); Optional<VerificationResult> check(LinkContext ctx); }`. The service collects every `LinkVerifier` bean and runs them after a link is made and on `/v1/session`; the strongest result wins. v1 ships with **no** verifiers.
- The level is visible to the plugin (`GetVerification()`), in assertions (`ver`), and on `/links`, so consumers can decide how much to trust a link.
- Staff checks are never added (volume). Parked ideas live in T-122: a Jagex-signed identity (asked in T-119); a random Hiscores XP challenge (gain XP in a named skill; maxed players still gain XP up to 200m; clue-scroll fallback for 200m-all accounts); tracking-id clash detection.

## 13. Aliases and assertions

### 13.1 Alias

```
alias = "a1_" + base32_lower( HMAC-SHA-256( LINK_ALIAS_KEY, "alias|v1|" + link.id + "|" + lowercase(pluginName) ) )[0..26]
```

- Stable for the life of the link: re-pairing on a new PC reuses the link, so the alias does not change. Unlinking and linking again makes a new link and therefore new aliases (a fresh start is a privacy feature).
- Different for every plugin name, and the key is server-side, so plugins cannot match players across each other.
- A plugin that lies about its name only gets a different alias for the **same local player**. It can never get another player's data: the token is the local player's, and no route takes an RSN or Discord id to look up someone else.
- Honest limit, stated in the README: a dishonest dependent running on the player's own PC could ask for the alias another plugin would get for that same player. It still learns nothing about the Discord account or other players.

### 13.2 Assertion (so a dependent's server can trust an alias)

A dependent's own server cannot tell a real alias from a made-up one, so `/v1/alias` also returns a short signed statement (compact JWS, EdDSA): `{ "iss": <base URL>, "plugin": "runesync", "alias": "a1_...", "rsn": "zezima", "ver": "implicit", "iat", "exp" (+15 min) }`. The server checks it against `/v1/keys`. `rsn` is the local player's own name, which the dependent already knows; no Discord data is in it.

### 13.3 DM text (T-115)

> Someone playing RuneScape as **Zezima** asked to link this Discord account using the RS Link plugin.
> If that was you, type this code into the plugin: **BHKD-7Q2M** (expires in 10 minutes).
> If it wasn't you, ignore this message. Nothing is linked without the code.
> [Not me] [Stop these messages]

No clan name, no invite, no links other than the configured base URL.

## 14. Neutral branding and configuration

**The page** (T-114):
- Uses the website's visual theme (colours, fonts, components) but no clan name, clan logo, JonnyBot branding, Discord server invites, ads, donation links, or links into the clan site.
- Title and header are the plugin name and the words "Link your RuneScape name to Discord". Footer: privacy page, source link to the public plugin repo, contact email (T-120).
- OAuth app: JonnyBot's for now, so Discord's consent screen says JonnyBot; later a separate neutral app (Q2), switched by config only. The page itself stays unbranded either way.
- Not "official": it never uses Jagex or Discord logos, and says "not affiliated with Jagex or Discord".
- Phone-width first; clear messages for wrong, expired and locked codes and for rate limits.

**Privacy text** (short, must match the code):
> We store your RuneScape name (and the plugin's tracking number, if you allowed it) and your Discord account id. Your Discord id is encrypted, sign-in tokens are stored only as fingerprints, and we never see your Discord password or email. Plugins that use this link only see whether you are linked and a code that is different for every plugin; they never see your Discord account. You can unlink or delete everything at any time on this page.

**Config:**
- `LINK_PUBLIC_BASE_URL` (bot and website): used for `verifyUrl`, DM text, assertions' `iss`, and the page's own links. Local: `http://localhost:3000`.
- `LINK_HOST` (website): the hostname that gets the link face; unset = the face is reachable only under `/link` (dev), like the `JONNYBOT_SUBDOMAIN` flag pattern.
- Plugin `plugin.json`: `"urls": { "link": "<base URL>" }`. Dev builds point at `http://localhost:3000` (the beta client allows plain http to localhost). The release value is set in T-120 once Drake picks the domain; changing it after release means a plugin update and a new Jagex review, so pick before submitting.
- The address should be short enough to type from the panel (e.g. a 2-word host, no path).

## 15. Plugin name: RS Link (decided)

Drake picked **RS Link** (2026-10-19), replacing the earlier "Discord Link". The name does not contain "Discord", which keeps clear of Discord's brand guidelines (discord.com/branding): third parties may not put the Discord marks into a product, bot, brand or domain name, but may use the word to describe what a product works with, as long as it does not suggest Discord made or endorses it.

- Discord appears only in descriptions, e.g. "Link your RuneScape name to your Discord account", plus "not affiliated with Jagex or Discord" on the page and in the README.
- Lua import: the name has a space, so dependents use the object form `{ "RSLink": "RS Link" }` and call `RSLink:...` (section 3.1).
- The web host, OAuth app and repo names avoid "discord" too.
- **Before release (T-120):** check against Jagex's plugin guidelines that the name does not suggest the plugin is official or made by Jagex, and that using "RS" (short for RuneScape, a Jagex trademark) in a plugin name is allowed; if not, rename before any other plugin depends on it.

## 16. Abuse list and mitigations

| Abuse | Mitigation |
|---|---|
| **DM spam** (typing other people's Discord ids) | Per-target 1 per 10 min and 3 per day, per-IP and per-tracking limits, service-wide hourly cap, "Stop these messages" opt-out, neutral text that names the asking RSN, DMs only follow a player action. |
| **Code guessing** | 8 characters from 27 unambiguous symbols (about 2.8 x 10^11 codes) live for 10 minutes; 5 tries per pairing then locked; per-IP and per-Discord limits; repeat offenders blocked. |
| **Enumeration** (does this Discord id / RSN exist?) | `pair/dm` always answers 202; the RSN is shown only after sign-in; blocks look like rate limits; no route takes someone else's RSN or Discord id. |
| **Doxxing via nearby-player lookups** (seeing a player in game and looking up their Discord) | No RSN-to-Discord or Discord-to-RSN route exists; dependents get an alias only; Jagex rules forbid exporting other players' data anyway; aliases differ per plugin so services can't join their data. Raw lookups stay out until an opt-in design exists. |
| **Phishing look-alikes** (fake sign-in pages, "enter this code" scams) | One fixed host from config shown in the panel; the plugin never shows other links; OAuth scope `identify` only (nothing to steal but a username); confirm screen warns "never enter a code someone sent you"; privacy page explains we never ask for a password; public repo lets anyone check the real address. |
| **Consent phishing** (attacker gets a victim to approve the attacker's code, linking the attacker's character to the victim's Discord) | Confirm screen always names the RSN and warns to continue only if you are logged in as it now; "This isn't me" denies and records it; the result is only an `implicit` link the victim can see and remove on `/links`. |
| **RSN recycling** | Keyed on tracking id when granted; 30-day rule before a recycled name's old link is released; renames tracked in history (section 11). |
| **Forged claims / impersonation** | Cannot take over a tracked link without the tracking id; `implicit` level visible to every consumer; strong-proof uses keep their own checks; verification hook for later. |
| **Token theft** (from the player's PC) | Token only in RS Link's own PersistentDB, never exported; revocable from `/links`; max 5 per link; prefix `lnk_` so secret scanners recognise it; stored server-side only as SHA-256. |
| **Malicious dependent** | Gets functions only; no token, Discord id or name; cannot make RS Link call arbitrary URLs; plugin name only changes the alias. |
| **Database leak** | Discord ids encrypted, lookups are keyed HMACs, tokens hashed, codes hashed; keys only in env; separate database from clan data. |
| **Log leak** | Never log codes, tokens, device secrets, Discord ids, tracking ids or IPs in plain text; log the pairing id and lookup prefix only. A unit test runs the flows with a capturing logger and fails if any of those values appear. |
| **OAuth CSRF / session mix-up** | `state`, `iss` and PKCE checked; pairing bound to the browser session that entered the code; `__Host-` cookie, CSRF token on POSTs. |
| **Scale** (tens of thousands of players, login bursts after updates) | Polling only while a pairing is open (10 min max, `slow_down`); `/v1/session` once per login; aliases cached in the plugin; indexed lookups only; per-IP limits at the website; DM circuit breaker protects the bot from Discord limits; single-instance limits noted as a thing to revisit if the service is scaled out. |
| **Bot account risk** (Discord flags JonnyBot for DM volume) | Same DM caps and opt-out; DMs only to users who share a server; the primary flow sends no DMs at all. |

## 17. How each build ticket maps to this spec

| Ticket | Builds | Sections |
|---|---|---|
| **T-113** link service + database | `rsnlink` package with the clean boundary of section 2 (import test, `LinkDmSender`), own DataSource and schema, every table, keys, codes, tokens, pairing state machine, section 9 link rules, section 11 recycling, rate limits and blocks, aliases and assertions, verification field + `LinkVerifier` hook (no verifiers), internal routes 5.3, delete paths, retention jobs. Tests: expiry, lockout, limits, token hashing, alias stability, recycling rules, "no secrets in logs". | 2, 5, 6, 7, 8, 9, 11, 12, 13.1, 13.2, 16 |
| **T-114** neutral sign-in page | Link host face (`LINK_HOST`), plugin API proxy with per-IP limits, code entry, OAuth (JonnyBot's app for now, client from config), confirm screen, `/links` with unlink and delete-my-data, privacy text, preview-harness views of each screen with made-up data. | 3.2, 4.1, 5.1 (proxy), 5.2, 14 |
| **T-115** DM fallback | `pair/dm` + confirm, id or username input with resolution among shared servers, uniform 202, DM sending with JDA, DM buttons (Not me, Stop), opt-out, DM caps and circuit breaker, panel copy; Drake tests user-install DMs with the dev bot. | 3.2, 4.2, 8, 13.3 |
| **T-116** RS Link plugin | New private repo (MIT), `plugin.json` (`httpAccess` required, `trackingAccess` optional, one `link` URL), pairing panel (code + address, Discord username-or-id box, code box, status, unlink, delete), token in PersistentDB, `/v1/session` on login, library section 10 with queue + `pcall`, README for dependents. | 3.1, 4, 9.3, 10, 14 |
| **T-117** RuneSync uses it | Adds RS Link to RuneSync's dependencies, uses `IsLinked` / `GetAlias` / `GetAssertion`, drops the dev-echo pairing assumptions. | 10, 13.2 |
| **T-118** abuse view | Super admin page: counts and recent `abuse_event`s, lockouts, DM breaker state, block/unblock (IP, tracking, Discord), remove a link; owner-only, audit-logged; Discord ids shown only to the owner (decrypted on demand); never codes or tokens. | 5.3, 6, 8, 16 |
| T-119 (Drake asks Jagex) | Unknowns in 3.1. | 3.1 |
| T-120 (release) | Domain, region, privacy page, backup wording, contact email, repo public, submission. | 9.5, 14 |
| T-121 (clan server) | Needs Discord ids, which dependents never get: the opt-in for JonnyBot servers is decided there (Q5). | 1, 13 |
| T-122 (parked) | Verifiers against the hook. | 12 |

Website-first rule: the player's controls (link, unlink, delete, see links) are on the link host, not in Discord. Discord-only use is the DM fallback, which still works. Owner controls are in Super admin (T-118); there are no per-server settings in v1, so no dashboard page is needed until T-121.

## 18. Decisions (Drake, 2026-10-19)

1. **Name:** "RS Link" (replaces "Discord Link"); Discord only in descriptions; Jagex-guideline check on "RS" before release (section 15).
2. **Sign-in app:** a separate neutral Discord app eventually, JonnyBot's app for now. Client id, secret and redirect come from config, so switching is config only (sections 3.2, 6.1, 14).
3. **DM fallback answer:** always the same, whether or not a DM can be sent (section 4.2).
4. **DM fallback input:** a Discord id or a username. Usernames are unique since Discord's username change; they are resolved only among servers JonnyBot shares, exact match only, and the same-answer rule still applies (sections 3.2, 4.2).
5. **JonnyBot servers seeing the real link:** decided in T-121.
6. **Signed alias assertions:** included now (section 13.2).
7. **Recycled names:** 30-day wait (section 11).
8. **Where it runs:** inside the bot for now; split into its own service when ready, with a clean module boundary so the split is a move, not a rewrite (section 2).

Still open, not blocking the build tickets: the pre-release name check against Jagex's guidelines (section 15, T-120) and the Jagex unknowns in section 3.1 (T-119).
