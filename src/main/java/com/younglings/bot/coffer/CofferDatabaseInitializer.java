package com.younglings.bot.coffer;

import com.younglings.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@BService
public class CofferDatabaseInitializer {
    private static final Logger log = LoggerFactory.getLogger(CofferDatabaseInitializer.class);

    public CofferDatabaseInitializer(ConnectionSupplier connectionSupplier) {
        SchemaBootstrapper.run(connectionSupplier, log, "coffer", List.of(
                """
                CREATE SCHEMA IF NOT EXISTS younglings;
                """,

                // Donation log — one row per /coffer submit call
                """
                CREATE TABLE IF NOT EXISTS younglings.coffer_donation (
                    donation_id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    donor_name TEXT NOT NULL,
                    amount BIGINT NOT NULL,
                    submitted_by_discord_id BIGINT NOT NULL,
                    submitted_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,

                """
                CREATE INDEX IF NOT EXISTS coffer_donation_guild_time_idx
                ON younglings.coffer_donation (guild_id, submitted_at DESC);
                """,

                // Running balance per holder — upserted on every transaction
                """
                CREATE TABLE IF NOT EXISTS younglings.coffer_holder (
                    holder_id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    discord_user_id BIGINT NOT NULL,
                    amount BIGINT NOT NULL DEFAULT 0,
                    UNIQUE (guild_id, discord_user_id)
                );
                """,

                // Transfer log — supports future 2-part verification (REQUIRE_TRANSFER_VERIFICATION flag)
                """
                CREATE TABLE IF NOT EXISTS younglings.coffer_transfer (
                    transfer_id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    from_discord_id BIGINT NOT NULL,
                    to_discord_id BIGINT NOT NULL,
                    amount BIGINT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'COMPLETED',
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    resolved_at TIMESTAMPTZ NULL
                );
                """,

                // Giveaway log — one row per /coffer giveaway call
                """
                CREATE TABLE IF NOT EXISTS younglings.coffer_giveaway (
                    giveaway_id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    given_by_discord_id BIGINT NOT NULL,
                    recipient_discord_id BIGINT NOT NULL,
                    amount BIGINT NOT NULL,
                    description TEXT NULL,
                    given_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """
        ));
    }
}
