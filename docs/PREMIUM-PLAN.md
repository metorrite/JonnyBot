# Premium plan: options (design)

Ticket T-003. Status: **options with a default to build the plumbing** (Drake away, 2026-10-20). **No payments and no prices are designed or built here.** Section 6 lists the money decisions that are Drake's alone. The build tickets only make granting, revoking and gating tidy, so any plan Drake picks later is a data change, not a rewrite.

## 1. What is gated today

| Gate | Where | What premium changes |
|---|---|---|
| Faster RuneMetrics polling | `PollPriority.PREMIUM_CLAN`, `RosterPollScheduler`, `BotConfig.getRunescapePremiumPollWindowMinutes` (60 min; standard clan 180, linked non-clan 480) | members of a premium clan are refreshed every hour instead of every 3 hours, and take request slots first |
| Bot server profile (avatar, banner, bio) | `BrandingAdminApi` (403 "for premium servers") | only premium servers can set them; nickname is free |
| The list itself | `premium_guild (guild_id, label, added_by, added_at)`, `PremiumGuildRepository`, owner-only `PremiumAdminApi` (`premium-guilds`), website `/admin/(console)/polling-plan` | owner adds or removes a server by hand |

Nothing else checks premium. There is no tier, no expiry, no history and no way for a server to see its own plan except on the branding page.

## 2. Options for what premium is

| | Option A: one plan (recommended shape) | Option B: two plans | Option C: everything free |
|---|---|---|---|
| Plans | Standard (free) and Premium | Standard, Plus, Pro | Standard only; "Supporter" badge for donors |
| Polling | Premium 60 min | Plus 120, Pro 60 | everyone 180 / 480 |
| Bot profile (avatar, banner, bio) | Premium | Plus and Pro | everyone |
| Higher limits (see 3) | Premium | Pro | none |
| Events hub, clan website builder (when built) | free; builder custom domain Premium | builder on Plus, custom domain Pro | free |
| Effort to run | lowest | more support questions | none, but RuneMetrics budget is shared by all |
| Risk | | price confusion | polling capacity runs out as servers grow |

**Default built now:** the plumbing supports any of these. `premium_guild` gets a `tier` text column (`premium` for every existing row), so A is today's behaviour and B is adding tier names; C is "grant nobody".

## 3. Candidate limits (none switched on by default)

Limits a plan could raise later; each is one row in the feature registry (section 4) with today's value as the free value, so nothing changes until Drake sets one:

| Feature | Free value today | Possible premium value |
|---|---|---|
| Events running at once | unlimited | (keep unlimited unless load needs it) |
| Custom commands / tags | per T-035 limit | x4 |
| Social feeds (YouTube, Reddit, Twitch) | per feed limits | x4 |
| Ticket panels | per tickets limit | x3 |
| Scheduled posts | per current limit | x4 |
| Plugin event retention | 180 days | 365 days |
| Event export image budget | 12 MB | 50 MB |

## 4. Plumbing to build (whatever the plan)

- **Single source:** `premium_guild` stays the one table. New columns: `tier TEXT NOT NULL DEFAULT 'premium'`, `expires_at TIMESTAMPTZ NULL` (null = no end), `note TEXT NULL` (owner's private note), `source TEXT NOT NULL DEFAULT 'manual'` (later e.g. `trial`, `payment`).
- **History:** `premium_grant_log (guild_id, action GRANT|CHANGE|REVOKE|EXPIRE, tier, expires_at, by_user, note, at)`.
- **Feature registry:** a pure `PremiumFeatures` (feature key -> which tiers unlock it, free value, premium value). One check `Premium.allows(guildId, feature)` and `Premium.limit(guildId, feature)`. Today's two callers (polling priority, branding profile) move onto it; behaviour unchanged.
- **Expiry:** a daily sweep removes expired grants (logs EXPIRE) and, 7 days before, posts nothing to Discord; the owner console lists "ending soon". No automatic messages to servers (that is a Drake decision, section 6).
- **Owner console (website first):** the RuneClan Admin console (T-089, runeclans.com/admin, owner only) gets a "Premium" page replacing `/admin/(console)/polling-plan`: list with tier, expiry, note, source; grant (pick server the bot is in, tier, optional end date, note), change, revoke; history per server. The old path redirects.
- **Server's own view:** every server's dashboard shows a read-only **Plan** card (Server setup page): current plan, end date if any, what it includes (from the registry), and "Ask JonnyBot's owner" text where a contact or link would go (Drake decides, section 6). Premium-only controls show a small "Premium" badge and stay visible but disabled for standard servers (they already are on the branding page).
- **No Discord command** for granting (owner work belongs on the website); `/jonnybot plan` showing the server's plan is not needed in v1.

## 5. Decisions made for Drake

| # | Question | Default |
|---|---|---|
| P1 | Shape of the plan | Build plumbing for any; behave as Option A (one Premium tier) because that is today |
| P2 | Single source of truth | `premium_guild`, extended with tier/expiry/note/source |
| P3 | Who can grant | The bot owner only, in RuneClan Admin |
| P4 | New gates | None switched on; the registry lists candidates with free values equal to today |
| P5 | Branding images premium? | Stay premium (today) |
| P6 | Polling windows | Unchanged: 60 / 180 / 480 minutes |
| P7 | Expiry behaviour | Silent downgrade at expiry + "ending soon" list for the owner; no Discord messages |
| P8 | Servers see their plan | Yes, read-only Plan card; premium controls visible with a badge, disabled |

## 6. Drake's money decisions (not designed, not built)

1. Whether premium costs anything at all, or is only granted (e.g. to partner clans, beta testers, donors).
2. Prices, currency, billing period, trials, refunds.
3. Who pays: the server owner, any member ("gift"), or donations pooled for the clan.
4. Payment provider (e.g. Stripe, Ko-fi, Patreon, Discord's own app subscriptions) and the tax/legal setup that comes with it.
5. Which of the candidate limits in section 3 become real, and their numbers.
6. Whether premium perks for the clan website builder (custom domain, extra templates) are paid.
7. The contact/link shown on the Plan card ("how to get premium").
8. Whether servers get a message before their plan ends.

Until Drake decides these, granting stays manual and free.

## 7. Build tickets

| Ticket | What | Depends on |
|---|---|---|
| T-224 | Bot: tier/expiry/note/source columns, grant log, `PremiumFeatures` registry + one check, expiry sweep; move polling and branding onto it | none |
| T-225 | Website + bot routes: RuneClan Admin Premium page (grant/change/revoke/history, replaces polling-plan with redirect) and the read-only Plan card + Premium badges | T-224, T-089 |
