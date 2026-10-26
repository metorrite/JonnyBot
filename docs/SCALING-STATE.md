# One-process state: inventory and decisions

RuneClan Bot runs today as **one process**: one JDA connection, the internal HTTP API, every scheduler lane and the Discord job outbox drain,
all in the same JVM. Several things in the code quietly rely on that. This document lists every piece of static or in-memory state (and the
habits around it) that assumes one process, and records a decision for each, so the bot can later run as more than one copy.

Nothing here changes behaviour. It is the checklist the later steps work from.

## 1. The deployment shapes this is judged against

| Shape | What runs | Copies |
|---|---|---|
| **B1** one process, `ShardManager` with `SHARD_COUNT` shards (default 1) | everything | 1 |
| **B2** roles: `BOT_ROLE=gateway` and `BOT_ROLE=api` (default `all` = today) | *gateway*: Discord login, events, interactions, outbox drain. *api*: internal HTTP API and gateway-free schedules | gateway 1, api 2-3 behind a load balancer |
| **B3** shard sets: several gateway processes, each `SHARD_RANGE` of `SHARD_COUNT` | as B2, with one gateway process per shard set | gateway N, api 2-3 |

Under B1 nothing in this list breaks: a `ShardManager` in one JVM still sees every server. The decisions below matter for B2 and B3.

## 2. Decision key

| Decision | Meaning |
|---|---|
| **Move to Postgres** | Must be one value for the whole bot. Two copies would double a budget, double-post, or lose work. Moves to a table (or to the hub) before B2/B3 go live. |
| **One owner** | Correct only while exactly one process runs it. Pin it to one role (normally `api`) or guard it with a Postgres advisory lock; no data move needed. |
| **Invalidate across processes** | A per-process cache of database rows whose writes can arrive on another process (the website writes through `api`, the gateway reads). Needs a cross-process "forget X" signal (Postgres `LISTEN/NOTIFY`) or a short TTL. |
| **Per shard set is fine** | State about servers, members or interactions that only the process owning that server's shard ever sees. |
| **Per process is fine** | A local cache with a TTL, a lookup dedupe, an abuse limiter for button mashing, a log buffer, or a constant table. Worst case with N copies: N times the work or N times the limit, which is acceptable. |

## 3. Must fix before B2/B3 (the short list)

These are the items that break or misbehave with a second process. Each has a follow-up.

| # | Item | Where | What goes wrong with two processes | Decision |
|---|---|---|---|---|
| M1 | RuneMetrics poll queue and budget | `runescape/polling/PollEngine` (`requestLog`, `inFlight`, `recentPolls`, `enqueuedAt`), `RateBucket`, `PollQueue.byKey`, `PollCoordinator.jobs` | Each copy has its own token bucket, so the shared per-IP budget is spent twice; the same player can be polled by both copies; recurring jobs register twice. | **One owner** now (polling runs only in the `api` role; other roles forward `submit` to it). **Move** to the hub's `poll_queue` and per-provider budget rows when polling moves there. |
| M2 | RuneMetrics daily tally | `RuneMetricsBudget` (saves `GREATEST(old, new)` per day and tier) | Two copies each save their own count; `GREATEST` keeps the larger, so the history under-counts. | Follows M1 (one owner). If polling ever runs in two copies, change the upsert to add deltas. |
| M3 | Discord outbox restart recovery | `DiscordJobRepository.recover()` | A starting process marks **every** `running` job as retrying or failed, including jobs another live process is running right now. | **Move to Postgres**: add `claimed_by` and `claimed_at`; recover only rows whose claim has expired or that this process claimed. |
| M4 | Discord outbox claim by shard | `DiscordJobRepository.claimDue` | Already `FOR UPDATE SKIP LOCKED` (no double run), but any gateway process takes any server's job; under B3 a process cannot reach servers on other shards and fails the job. | **Move to Postgres**: claim only rows whose `guild_id` maps to this process's shards (`(guild_id >> 22) % SHARD_COUNT`). |
| M5 | Boot-loaded live registries | `PollService.activePollsById` / `optionsByPollId`, `SignupService.activeSignupsById` | Loaded once at start-up and then trusted. A poll created through the website (`CommunityAdminApi`, `MemberApi`) on the `api` process is unknown to the gateway process until it restarts, so its buttons fail. | **Move to Postgres** as the source of truth: look up on a miss (load-through), keep the map only as a cache. |
| M6 | Long-running website jobs and their progress | `EventSpaceOps.jobs` / `locks` / `pending`, `EventWrapupOps.exports`, `RoleSyncBackfill.progress` | The dashboard starts a job on one `api` replica and polls progress every two seconds; the next poll can land on another replica that knows nothing ("no job"). The per-event `ReentrantLock` does not stop a second replica. | **Move to Postgres**: a small job table (state, done, total, message, finished_at) and `pg_advisory_xact_lock` per event instead of the in-memory lock. |
| M7 | Security rate limits for RuneLink | `LinkService` `WindowLimiter`s: `wrongCodesPerDiscord`, `codePerIpMinute`, `codePerIpHour`, `startPerIp`, `startPerRsn`, `startPerTracking`; `LinkDm` `dmPerIp`, `dmPerTracking` | These cap guessing of link codes and DM spam. With N `api` replicas behind a load balancer the real limit is N times the intended one. | **Move to Postgres** (a sliding-window table keyed by the hashed lookup, or the hub when RuneLink moves there). The other `WindowLimiter` uses (token and alias routes, web account) can stay per process. |
| M8 | Hiscores read budget for XP verification | `XpVerifier.globalReads` (and its `cache`), `RsnLinkModule` lane `rsnlink-verify` | Two workers would each spend the full public-read budget and check the same claims. | **One owner** (`api` role). The claims themselves are already in Postgres (`PgVerifyStore`). |
| M9 | "Server not visible" treated as "server gone" | `PollCloser` (clears the poll's schedule), `TwitchService.announce` (claims, then finds no channel, alert lost), `GuildRetentionService.sweep` (uses `getGuildById` as proof of absence), other lanes that skip a `null` guild | Under B3 a process sees only its own shards' servers, and under B2 the `api` role may have no gateway at all. A `null` from `getGuildById` then means "not mine", not "the bot left". | **One owner** per lane plus a shard check: add one helper (`ShardRouting.owns(guildId)`) and only treat `null` as "gone" for servers this process owns. Retention decides "gone" from the stored departure row alone. |
| M10 | Settings caches with no TTL | see section 4.3 | The website saves a setting on the `api` process; the gateway process keeps the old value until it restarts (for example an automod rule removed on the dashboard keeps deleting messages). | **Invalidate across processes**: one `LISTEN/NOTIFY` channel (`bot_cache`, payload `area:guildId`) that every cache subscribes to, or a short TTL as a stop-gap. |
| M11 | Scheduler lanes that read rows, not servers | most lanes in section 5 | A lane that loops over database rows (reminders, giveaways, scheduled posts, ticket closes, premium expiry) runs in every process that starts it, so the same reminder fires twice. | **One owner**: each lane declares its role (section 5). Lanes whose work needs Discord run in the gateway and filter rows to their own shards (`guild_id` → shard). |

## 4. Full inventory

Paths are under `src/main/java/com/younglings/bot/`. "TTL" means the entry expires on its own.

### 4.1 Budgets, rate limits and dedupe

| State | Where | Kind | Decision |
|---|---|---|---|
| RuneMetrics queue, bucket, in-flight, recent polls, counters | `runescape/polling/PollEngine`, `RateBucket`, `PollQueue`, `PollCoordinator.jobs` | global budget | M1: One owner, then move to the hub |
| RuneMetrics daily tally | `runescape/polling/RuneMetricsBudget` | global counter | M2: follows M1 |
| Hiscores read log and view cache | `rsnlink/verify/XpVerifier.globalReads`, `cache` | global budget | M8: One owner |
| RuneLink abuse limits | `rsnlink/LinkService` (9 `WindowLimiter`s), `rsnlink/LinkDm` (2) | security limit | M7: the code, start and DM limits move to Postgres; token and alias route limits per process |
| Website button and API limits | `internal/RateLimiter` used by `FormsPublicApi`, `TicketAdminApi`, `DirectorySite*Routes`, `WebUserStateRoutes`, `SiteMembersAdmin`, `AppealsPublicApi`, `MemberApi`, `SiteViewerRoutes`, `DiscordJobRoutes`, `ClanSiteAdminApi`, `OverallAdminApi`, `PluginApi`, `LinkedRolesApi`, `BotlessAdminApi` | anti-mashing | Per process is fine (N replicas allow N times the burst; the website and Discord have their own limits) |
| Plugin ingest limits | `internal/PluginApi` (`ingestLimit`, `accountLimit`, `runeSyncLimit`) | anti-flood | Per process is fine; revisit if plugin traffic grows (hub owns ingest long term) |
| Message cooldowns | `chatlevel/ChatLevelListener.lastEarned`, `highlight/HighlightListener.lastSent`, `autoreply/AutoReplyListener.lastAnswered`, `commands/customtag/TagCommand.lastUsed`, `commands/recap/WrappedCommand.lastUse`, `suggestion/SuggestionService.lastSubmitted`, `tempvoice/TempVoiceService.lastCreate`, `commandchannel/CommandChannelListener.lastNoticeAt`, `servertag/ServerTagListener.recent`, `honeypot/HoneypotListener.recent` | per-server event dedupe | Per shard set is fine (events for a server arrive on its shard only) |
| Automod histories, role-rule chain guard | `automod/AutomodListener.histories`, `rolerules/RoleRuleService.hops`, `recent` | per-server event state | Per shard set is fine |
| Loose proof reminder dedupe | `events/LooseProofReminderListener.reminded` | per-channel dedupe | Per shard set is fine |
| Presence member batch | `presence/PresenceBatch` (`pending`, `askedAt`) | gateway request batching | Per shard set is fine |
| Ephemeral message timers | `discord/EphemeralLifecycle.tracked` | interaction hooks | Per shard set is fine (the hook lives in the process that answered) |
| Discord lookup dedupe | `discord/DiscordLookups` static `usersAsked`, `membersAsked` | in-flight dedupe | Per process is fine |
| Roster refresh throttle | `runescape/ClanSyncService.lastRosterRefresh` | throttle | Per process is fine while clan sync is one owner (section 5); moves with polling to the hub |
| Panel redraw debounce | `internal/Debouncer`, `events/EventBoardPanels.pending`, `events/CohostPanels.redraws` | edit debounce | Per shard set is fine once the redraw runs where the server lives; until then two copies cost one extra edit at most |
| Twitch "live now" snapshot | `twitch/TwitchService.lastLive`, `lastPoll` | display cache | Per process is fine; the post itself is claimed in Postgres (`repository.claim`). The lane is One owner (M11) |
| Send-now guard | `tracking/TrackingConfigInteractionListener.sendNowInFlight` | double-click guard | Per shard set is fine |
| Hub write retry queue | `runeclans/HubWriteQueue.held` | best-effort retry, documented as memory-only | Per process is fine (lost on restart by design) |
| Hub site role reports | `hubsites/RoleReports.pending` | outgoing batch | Per shard set is fine (role changes come from the owning shard) |
| Command usage counts | `commandusage/CommandUsageCounter` | counter flushed with `uses = uses + EXCLUDED.uses` | Per process is fine (flushes add up) |
| Site invalidation batch | `internal/SiteInvalidator` (`pending`, `pendingGuilds`, static `instance`) | outgoing batch to the website | Per process is fine (each process announces its own writes) |

### 4.2 Work in progress and live registries

| State | Where | Decision |
|---|---|---|
| Active polls and options | `commands/poll/PollService` | M5: Move to Postgres (load-through) |
| Open signups | `commands/signup/SignupService.activeSignupsById` | M5: Move to Postgres (load-through) |
| Signup builder drafts | `commands/signup/SignupService.submissionDraftsByUserId` | Per shard set is fine (one user's slash-command session; lost on restart by design) |
| Team forming staged changes | `commands/teamforming/TeamformingService.pendingByUserId` | Per shard set is fine (same trade-off) |
| Prune confirmations | `commands/runescape/PruneInteractionListener.pendingSpecs` | Per shard set is fine (button on the same shard) |
| `/rs` share toggle | `commands/runescape/RsInteractionListener.shareEnabled` | Per shard set is fine |
| Event space setup jobs and locks | `events/EventSpaceOps` (`jobs`, `locks`, `pending`) | M6: Move to Postgres |
| Event wrap-up exports | `events/EventWrapupOps.exports` | M6: Move to Postgres |
| Role sync backfill progress | `runescape/RoleSyncBackfill.progress` | M6: Move to Postgres |
| Plugin XP tracker hand-off | `events/PluginXpTracker` (`runeSyncPlayers`, `disagreements`, `notCounted`, `aliases`) | One owner (must run in the same process as `EventTracker`, which reads `runeSyncPlayers`) |
| Discord outbox handlers | `discord/jobs/DiscordOutbox.handlers` | Per process is fine (a constant table); claims are M3/M4 |
| Event change subscribers | `events/EventChanges.subscribers` | Per process is fine for in-process listeners; a change made on `api` that a gateway listener must see goes through M10's signal |
| Snapshot log buffer | `devtools/SnapshotLogBuffer` | Per process is fine |

### 4.3 Caches of database rows (writes may come from the website)

No TTL: the cache is only refreshed when the same process writes. All of these are **M10: Invalidate across processes**.

| Cache | Where |
|---|---|
| Automod config | `automod/AutomodRepository.cache` |
| Auto replies | `autoreply/AutoReplyRepository.cache` |
| Custom tags and their switch | `customtag/CustomTagRepository.cache`, `enabled` |
| Highlights (configs, keywords, ignores) | `highlight/HighlightRepository` |
| Chat level config and roles | `chatlevel/ChatLevelRepository.configs`, `roles` |
| Server tag config | `servertag/ServerTagRepository.cache` |
| Honeypot config | `honeypot/HoneypotRepository.cache` |
| Auto-delete rules | `autodelete/AutoDeleteRepository.cache` |
| Message log config | `messagelog/MessageLogRepository.configs` |
| Points extras triggers | `points/PointsExtrasRepository.triggerCache` |
| Role rules | `rolerules/RoleRuleService.cache` |
| Command channels | `commandchannel/CommandChannelService.channelIndexByGuild` |
| Permission groups | `permission/PermissionGroupService.cache` |
| Hub command settings | `hub/HubService.cache` |
| Server language | `i18n/Messages.chosen` |
| AFK list | `afk/AfkService.byGuild` (the dashboard can clear AFK through `HubAdminApi`) |
| Bot-less site list | `clansite/BotlessSiteRepository.known` |

With a TTL (stale for at most the TTL on another replica): **Per process is fine**, and M10's signal shortens it when it lands.

| Cache | Where | TTL |
|---|---|---|
| Admin reads (Discord-derived answers) | `internal/AdminReads` static `StaleCache` | fresh 30 s, served stale up to 10 min while refreshing; local `invalidate` after a write only clears this process, so subscribe it to M10 |
| Site and directory caches | `internal/SiteCache`, `DirectoryApi`, `DirectoryClanVsClanSection`, `HubEventsApi`, `TicketAdminApi.archivedThreadCache` (`internal/TtlCache`) | short TTL |
| Site viewer roles | `clansite/SiteRoleGuard.viewers` | 60 s |
| Beta and access answers | `beta/BetaAccessService.remembered`, `access/AccessGrantService.remembered` | about a minute |
| Overall admins, bot owners | `access/OverallAdmins`, `notice/BotOwners` | timed reload |
| Plugin feed, RuneSync panel | `clansite/ClanSitePluginFeed.cache`, `plugin/RuneSyncPanelService.cache` | timed |
| Hub read cache | `runeclans/HubCache` | LRU with TTL |
| Hub feed id cache | `runeclans/RuneClansFeedPoller.Relay.ids` | timed |
| Lookups, prices | `lookup/LookupHttp.cache`, `plugin/PluginEnrichment.prices` (`util/TtlCache`) | 15 min / timed |
| Citadel wheel admin cache | `tracking/CitadelWheelEntries.adminCache` | timed |
| Event space "seen" | `events/EventSpaces.seen` | 60 s |
| Site channel mappings recorded this run | `hubsites/SiteChannelMappings.recorded` | per run; a second process repeats one idempotent write |
| Phishing domain list | `automod/PhishingList` | timed reload |
| Twitch and Kick app tokens | `twitch/TwitchClient`, `kick/KickClient` | until expiry; each process holds its own token (allowed by both APIs) |
| RuneLink signing keys | `plugin/RsLinkAuth.keys` | timed refetch |

### 4.4 Application-wide Discord state

| State | Where | Decision |
|---|---|---|
| Application emoji mentions and upload guard | `runescape/SkillEmojiCatalog`, `tracking/TrackingIconCatalog`, `runescape/GuildIconEmojiCatalog` (`uploadInFlight`) | Per process is fine for the cache. Emojis belong to the application, so two processes starting together can both upload the same missing emoji. One owner for uploads (gateway process holding shard 0); others only read the list. Low risk: only on a first boot after adding an icon. |
| Privileged intent flags | `config/PrivilegedIntents` (static) | Per process is fine (same application, same answer) |
| Dev-bot posting switch | `tracking/TrackingPostingToggle`, `alerts/AutoPostRouter` (static `instance`) | Per process is fine on the dev bot (it is one process by design); production starts ON from `LIVE_ENV` everywhere |
| Premium and billing switches | `premium/PremiumSwitch` (static `forced`), `premium/Billing` (static `instance`) | Per process is fine (`forced` is a test hook; the switch comes from the environment, the same in every process) |
| Feature flags shown on the status page | `internal/FeatureStatusRepository.FLAGS` | Per process is fine (constant table) |
| Text catalogue | `i18n/Text` (static `installed`, `english`) | Per process is fine (loaded from bundled files) |

### 4.5 Constant tables and thread naming (no decision needed)

Per process is fine: `runescape/SkillEmojiCatalog.ICON_BYTES`, `tracking/TrackingIconCatalog` byte tables, `events/BingoBackdrops.KEPT`,
`events/BingoLayout.IMAGES` / `all`, `events/BingoTiles.ICONS`, `internal/ColorRoleCatalog.COLORS`, `internal/WikiCatalog`,
`events/ClanVsClanTemplate.games`, `events/CompetitionTemplate.pluginSourceOffered`, `commands/poll/PollBarRenderer` font measurements,
`welcome/WelcomeCardRenderer.cache`, `fun/FunApis` prefetch queues, `util/BotScheduler.WORKER_NUMBER`, `internal/AdminReads.THREAD_NUMBER`,
`tools/IconDownloader` (a developer tool, not run by the bot), `rsnlink/verify/MemoryVerifyStore` (tests and database-less runs only).

## 5. Scheduler lanes

Every `BotScheduler.lane(...)` and which process should own it once roles exist. "gateway (own shards)" means the lane needs Discord and must
filter its rows to the servers on this process's shards; "api" means exactly one owner (the `api` role, guarded by an advisory lock while it
has more than one replica).

| Lane | Class | Owner |
|---|---|---|
| `poll-jobs` | `runescape/polling/PollCoordinator` | api |
| `runemetrics-budget` | `runescape/polling/RuneMetricsBudget` | api (with polling) |
| `runemetrics-clock-check` | `runescape/RuneMetricsClockCheck` | api |
| `clan-sync-scheduler` | `runescape/ClanSyncScheduler` | api (roster fetch); role sync results go through the outbox |
| `clan-points-scheduler` | `runescape/ClanPointsScheduler` | api |
| `weekly-digest-scheduler` | `runescape/WeeklyDigestScheduler` | gateway (own shards) |
| `event-tracker`, `plugin-xp-tracker` | `events/EventTracker`, `events/PluginXpTracker` | api, together (shared `runeSyncPlayers`) |
| `rsnlink-verify`, `rsnlink-retention` | `rsnlink/bridge/RsnLinkModule` | api |
| `rsnlink-hub-dm` | `rsnlink/bridge/HubDmButtons` | gateway (own shards) |
| `runeclans-feed` | `runeclans/RuneClansFeedPoller` | api (cursor in Postgres); posts through the outbox |
| `hub-sites` | `hubsites/HubSiteReporter` | gateway (own shards) |
| `discord-jobs` | `discord/jobs/DiscordOutbox` | gateway (own shards), see M3 and M4 |
| `ephemeral-lifecycle` | `discord/EphemeralLifecycle` | gateway (the process that answered) |
| `event-board-panels`, `cohost-panels`, `event-signups` | `events/...` | gateway (own shards) |
| `event-announcer-sweep` | `eventannounce/EventAnnounceService` | gateway (own shards) |
| `reminders` | `reminder/ReminderScheduler` | gateway (own shards) |
| `server-reminders` | `serverreminder/ServerReminderService` | gateway (own shards) |
| `scheduled-posts` | `internal/ScheduledPostRunner` | gateway (own shards) |
| `poll-closer` | `internal/PollCloser` | gateway (own shards), see M9 |
| `giveaways` | `giveaway/GiveawayService` | gateway (own shards) |
| `ticket-scheduler` | `commands/ticket/TicketScheduler` | gateway (own shards) |
| `signup-maintenance` | `commands/signup/SignupMaintenanceScheduler` | gateway (own shards) |
| `lookup-reminders` | `lookup/LookupReminderScheduler` | gateway (own shards) |
| `member-notifier` | `member/MemberNotifier` | gateway (own shards) |
| `autorole`, `role-keep`, `temp-voice`, `autopurge` | `autorole/...`, `rolekeep/...`, `tempvoice/...`, `autopurge/...` | gateway (own shards) |
| `clan-anniversaries` | `anniversary/AnniversaryService` | gateway (own shards) |
| `staff-digest` | `internal/StaffDigestService` | gateway (own shards) |
| `twitch`, `kick`, `feeds` | `twitch/TwitchService`, `kick/KickService`, `feed/FeedService` | api fetches once; posts filtered to own shards (or sent through the outbox) |
| `presence-members` | `presence/PresenceMemberLoader` | gateway (own shards) |
| `linked-roles` | `linkedroles/LinkedRoles` | api |
| `premium-expiry`, `billing-reconcile` | `premium/Premium`, `premium/Billing` | api |
| `guild-retention` | `retention/GuildRetentionService` | api, see M9 |
| `plugin-retention`, `message-log-purge`, `clan-site-cleanup` | `plugin/...`, `messagelog/...`, `clansite/...` | api (database only) |
| `address-change-scheduler` | `clanprofile/SlugChangeScheduler` | api |
| `command-usage` | `commandusage/CommandUsageListener` | every process (flushes add up) |
| `phishing-list` | `automod/PhishingList` | every process (each needs the list) |
| `debouncer`, `site-invalidator` | `internal/Debouncer`, `internal/SiteInvalidator` | every process (local batching) |

Two thread pools sit outside `BotScheduler` and are covered by M6: `EventSpaceOps` (`event-space`) and `EventWrapupOps` (`event-wrapup`),
plus the `RoleSyncBackfill` worker.

## 6. How this list was made, and keeping it current

Searched `src/main` for fields initialised with a mutable collection, atomic or cache (`ConcurrentHashMap`, `HashMap`, `ArrayDeque`,
`Atomic*`, `TtlCache`, `StaleCache`, `RateLimiter`, `WindowLimiter`, ...), every non-final or `volatile` static, every `BotScheduler.lane`,
every `FOR UPDATE` / `SKIP LOCKED` / advisory lock, and every `getGuildById(...) == null` branch in a scheduler. Then read each hit.

When adding state: if it must be one value for the whole bot (a budget, a dedupe that prevents a double post, a job other requests poll),
put it in Postgres from the start. A per-server cache of settings that the website can change needs the invalidation signal from M10 once it
exists. Add the row here either way.
