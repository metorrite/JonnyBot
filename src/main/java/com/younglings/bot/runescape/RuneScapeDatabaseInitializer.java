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

                // Periodic snapshot of a linked player's RuneMetrics profile — one row per poll,
                // so XP-gain-over-time can be derived by comparing rows rather than only ever
                // seeing the current total.
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
                """
        ));
    }
}
