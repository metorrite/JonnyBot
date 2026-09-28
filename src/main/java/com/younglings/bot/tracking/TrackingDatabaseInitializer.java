package com.younglings.bot.tracking;

import com.younglings.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@BService
public class TrackingDatabaseInitializer {
    private static final Logger log = LoggerFactory.getLogger(TrackingDatabaseInitializer.class);

    public TrackingDatabaseInitializer(ConnectionSupplier connectionSupplier) {
        SchemaBootstrapper.run(connectionSupplier, log, "tracking", List.of(
                """
                CREATE SCHEMA IF NOT EXISTS younglings;
                """,

                // group_key is one of TrackingGroup's enum names, not a foreign key to anything —
                // the set of groups is fixed in code (see TrackingGroup's javadoc for why), this
                // table only ever records a per-guild override of enabled/disabled. No row for a
                // given guild+group means "enabled, no destinations yet" (see TrackingRepository).
                """
                CREATE TABLE IF NOT EXISTS younglings.tracking_group_config (
                    guild_id BIGINT NOT NULL,
                    group_key TEXT NOT NULL,
                    enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    PRIMARY KEY (guild_id, group_key)
                );
                """,

                // One row per destination — a group with several rows posts to several
                // channels/threads at once. A forum post is just its own channel id under the hood
                // (JDA sends to a ThreadChannel exactly like any other channel), so there's no
                // separate "is this a thread" column.
                """
                CREATE TABLE IF NOT EXISTS younglings.tracking_destination (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    group_key TEXT NOT NULL,
                    channel_id BIGINT NOT NULL,
                    UNIQUE (guild_id, group_key, channel_id)
                );
                """,

                """
                CREATE INDEX IF NOT EXISTS tracking_destination_lookup_idx
                ON younglings.tracking_destination (guild_id, group_key);
                """,

                // Every message the "Send Test Posts" panel button sends, so "Clear Test Posts" knows
                // exactly which messages are safe to delete — never anything a real event posted.
                """
                CREATE TABLE IF NOT EXISTS younglings.tracking_test_message (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    channel_id BIGINT NOT NULL,
                    message_id BIGINT NOT NULL,
                    posted_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,

                """
                CREATE INDEX IF NOT EXISTS tracking_test_message_guild_idx
                ON younglings.tracking_test_message (guild_id);
                """,

                // Short-lived: a per-player/boss persistent kill counter, replaced the same day by
                // collapsing consecutive identical activities within a single poll's batch instead
                // (see RuneScapeStatsService#collapseConsecutive) — simpler, and scoped to what
                // actually happened between two polls rather than an all-time running count.
                """
                DROP TABLE IF EXISTS younglings.boss_kill_tally;
                """
        ));
    }
}
