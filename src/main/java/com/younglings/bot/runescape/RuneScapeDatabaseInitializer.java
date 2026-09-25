package com.younglings.bot.runescape;

import com.younglings.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@BService
public class RuneScapeDatabaseInitializer {
    private static final Logger log = LoggerFactory.getLogger(RuneScapeDatabaseInitializer.class);

    public RuneScapeDatabaseInitializer(ConnectionSupplier connectionSupplier) {
        SchemaBootstrapper.run(connectionSupplier, log, "runescape", List.of(
                """
                CREATE SCHEMA IF NOT EXISTS younglings;
                """,

                // One confirmed RSN <-> Discord link per (guild, discord user). A member could
                // reasonably want more than one RSN linked (main + ironman, etc.) later, so this
                // intentionally isn't unique on discord_user_id alone.
                """
                CREATE TABLE IF NOT EXISTS younglings.player_link (
                    link_id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    discord_user_id BIGINT NOT NULL,
                    rsn TEXT NOT NULL,
                    is_primary BOOLEAN NOT NULL DEFAULT TRUE,
                    verified_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    verification_method TEXT NOT NULL
                );
                """,

                // Postgres table constraints can't reference an expression like LOWER(rsn) directly
                // (only plain columns) — a unique index is the correct way to enforce this.
                """
                CREATE UNIQUE INDEX IF NOT EXISTS player_link_unique_rsn_lower
                ON younglings.player_link (guild_id, LOWER(rsn));
                """,

                """
                CREATE INDEX IF NOT EXISTS player_link_discord_user_idx
                ON younglings.player_link (guild_id, discord_user_id);
                """,

                // Tracks a member's own self-service "Poll Now" clicks under /rs specifically — kept
                // separate from snapshot_at (which every poll source touches: admin, auto-poll, this
                // one) so the 30-minute self-poll cooldown only ever reacts to the member's own
                // clicks, never reset or consumed by someone else polling them in the meantime.
                """
                ALTER TABLE younglings.player_link
                    ADD COLUMN IF NOT EXISTS last_self_poll_at TIMESTAMPTZ NULL;
                """,

                // In-progress makeover-mage verification: a random appearance is assigned, the
                // player applies it in-game, then an admin confirms it against the fetched avatar
                // image (see RsnVerificationService) before a player_link row is created.
                """
                CREATE TABLE IF NOT EXISTS younglings.player_verification_attempt (
                    attempt_id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    discord_user_id BIGINT NOT NULL,
                    rsn TEXT NOT NULL,
                    assigned_hairstyle TEXT NOT NULL,
                    assigned_hair_color TEXT NOT NULL,
                    assigned_skin_tone TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'PENDING',
                    requested_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    resolved_at TIMESTAMPTZ NULL,
                    resolved_by_user_id BIGINT NULL
                );
                """,

                """
                CREATE INDEX IF NOT EXISTS player_verification_pending_idx
                ON younglings.player_verification_attempt (guild_id, status);
                """,

                // Periodic snapshot of a linked player's RuneMetrics profile — one row per poll
                // (manual only, see RuneScapeStatsScheduler/BotConfig#getRunescapeAutoPollEnabled),
                // so XP-gain-over-time can be derived by comparing rows rather than only ever
                // seeing the current total. skills_json is kept as a redundant denormalized copy —
                // player_skill_snapshot below is the real read path for per-skill history/queries.
                """
                CREATE TABLE IF NOT EXISTS younglings.player_stats_snapshot (
                    snapshot_id BIGSERIAL PRIMARY KEY,
                    rsn TEXT NOT NULL,
                    guild_id BIGINT NOT NULL,
                    snapshot_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    total_level INTEGER NOT NULL,
                    total_xp BIGINT NOT NULL,
                    combat_level INTEGER NOT NULL,
                    quests_complete INTEGER NOT NULL,
                    skills_json TEXT NOT NULL
                );
                """,

                """
                CREATE INDEX IF NOT EXISTS player_stats_snapshot_rsn_idx
                ON younglings.player_stats_snapshot (guild_id, LOWER(rsn), snapshot_at DESC);
                """,

                // Added after the table already existed in some environments — ADD COLUMN IF NOT
                // EXISTS instead of baking these into the CREATE TABLE above, since this
                // initializer has no other way to evolve a table that's already been created.
                """
                ALTER TABLE younglings.player_stats_snapshot
                    ADD COLUMN IF NOT EXISTS quests_started INTEGER NOT NULL DEFAULT 0;
                """,

                """
                ALTER TABLE younglings.player_stats_snapshot
                    ADD COLUMN IF NOT EXISTS quests_not_started INTEGER NOT NULL DEFAULT 0;
                """,

                // Per-skill history, one row per skill per snapshot — the normalized read path for
                // "show me Attack XP over time" style queries that a JSON blob can't do cheaply.
                """
                CREATE TABLE IF NOT EXISTS younglings.player_skill_snapshot (
                    id BIGSERIAL PRIMARY KEY,
                    snapshot_id BIGINT NOT NULL REFERENCES younglings.player_stats_snapshot(snapshot_id) ON DELETE CASCADE,
                    skill_id INTEGER NOT NULL,
                    level INTEGER NOT NULL,
                    xp BIGINT NOT NULL,
                    rank BIGINT NOT NULL
                );
                """,

                """
                CREATE INDEX IF NOT EXISTS player_skill_snapshot_snapshot_idx
                ON younglings.player_skill_snapshot (snapshot_id);
                """,

                // RuneMetrics' own "recent activities" feed (quest completions, level-ups, etc.),
                // captured every time we poll so we build up our own permanent history instead of
                // relying on Jagex's rolling feed (which only ever shows the most recent handful).
                // activity_date is kept as the API's own raw string ("23-Sep-2026 23:30") rather
                // than parsed into a timestamp — RuneScape doesn't document which timezone that's
                // in, and guessing would bake in a wrong assumption; recorded_at (when *we* saw it)
                // is what's actually reliable for our own ordering. The unique index dedupes across
                // repeated polls, since the same recent events reappear in the feed every time.
                """
                CREATE TABLE IF NOT EXISTS younglings.player_activity (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    rsn TEXT NOT NULL,
                    activity_date TEXT NOT NULL,
                    activity_text TEXT NOT NULL,
                    activity_details TEXT NOT NULL,
                    recorded_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,

                """
                CREATE UNIQUE INDEX IF NOT EXISTS player_activity_unique_idx
                ON younglings.player_activity (guild_id, LOWER(rsn), activity_date, activity_text);
                """,

                """
                CREATE INDEX IF NOT EXISTS player_activity_rsn_idx
                ON younglings.player_activity (guild_id, LOWER(rsn), recorded_at DESC);
                """,

                // The clan's roster, tracked independently of player_link — a name shows up here
                // the moment a clan sync sees it in the Clan Hiscores response, whether or not
                // anyone has ever verified that it's their own account. active flips to false (not
                // deleted) when a later sync no longer sees the name, so "left the clan" is a fact
                // you can see, not a silently vanished row.
                """
                CREATE TABLE IF NOT EXISTS younglings.clan_member (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    rsn TEXT NOT NULL,
                    clan_rank TEXT NOT NULL,
                    first_seen TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    last_seen TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    active BOOLEAN NOT NULL DEFAULT TRUE
                );
                """,

                """
                CREATE UNIQUE INDEX IF NOT EXISTS clan_member_unique_rsn_lower
                ON younglings.clan_member (guild_id, LOWER(rsn));
                """,

                // Total XP and kill count as of the last sync, straight from the Clan Hiscores CSV
                // line — kept so a rename-detection pass has a "last known" total XP for a name that
                // just disappeared from the roster, without needing a separate lookup or its own
                // history table. See RsnRenameService.
                """
                ALTER TABLE younglings.clan_member
                    ADD COLUMN IF NOT EXISTS total_xp BIGINT NOT NULL DEFAULT 0;
                """,

                """
                ALTER TABLE younglings.clan_member
                    ADD COLUMN IF NOT EXISTS kills BIGINT NOT NULL DEFAULT 0;
                """,

                // The date this member actually joined the *clan* in-game — distinct from
                // first_seen (when *we* first noticed them) and from player_link.verified_at (when
                // their Discord account got linked, which can happen long after or never). Not
                // populated automatically yet: the plan is for a future sync pass to read it off a
                // newly-appeared member's adventure log the day they're first detected (log entries
                // don't stick around forever, so this has to happen close to join time — with a
                // ~24h sync cadence, "yesterday" is the correct inferred date even when the log
                // itself is private by the time we look). Until that's built, this is set manually
                // per player via /rsadmin's Player Lookup, to backfill everyone already tracked.
                """
                ALTER TABLE younglings.clan_member
                    ADD COLUMN IF NOT EXISTS clan_joined_at DATE NULL;
                """,

                // A name that vanished from the clan roster the same sync cycle a new name appeared,
                // where XP/skill/activity evidence suggests they're the same account renamed rather
                // than one member leaving and another joining — see RsnRenameService for the
                // detection logic and RsnRenameInteractionListener for the confirm/reject buttons.
                // Kept even after resolution as an audit trail, not deleted.
                """
                CREATE TABLE IF NOT EXISTS younglings.rsn_rename_candidate (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    old_rsn TEXT NOT NULL,
                    new_rsn TEXT NOT NULL,
                    confidence TEXT NOT NULL,
                    basis TEXT NOT NULL,
                    detected_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    resolved_at TIMESTAMPTZ NULL,
                    status TEXT NOT NULL DEFAULT 'PENDING'
                );
                """,

                """
                CREATE INDEX IF NOT EXISTS rsn_rename_candidate_pending_idx
                ON younglings.rsn_rename_candidate (guild_id, status);
                """
        ));
    }
}
