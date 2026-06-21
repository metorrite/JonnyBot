package com.younglings.bot.coffer;

import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.Statement;

@BService
public class CofferDatabaseInitializer {
    private static final Logger log = LoggerFactory.getLogger(CofferDatabaseInitializer.class);

    public CofferDatabaseInitializer(ConnectionSupplier connectionSupplier) {
        initialize(connectionSupplier);
    }

    private void initialize(ConnectionSupplier connectionSupplier) {
        log.info("Initializing coffer database tables...");

        try (Connection connection = connectionSupplier.getConnection();
             Statement statement = connection.createStatement()) {

            statement.execute("""
                    CREATE SCHEMA IF NOT EXISTS younglings;
                    """);

            // Donation log — one row per /coffersubmit call
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS younglings.coffer_donation (
                        donation_id BIGSERIAL PRIMARY KEY,
                        guild_id BIGINT NOT NULL,
                        donor_name TEXT NOT NULL,
                        amount BIGINT NOT NULL,
                        submitted_by_discord_id BIGINT NOT NULL,
                        submitted_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                    );
                    """);

            statement.execute("""
                    CREATE INDEX IF NOT EXISTS coffer_donation_guild_time_idx
                    ON younglings.coffer_donation (guild_id, submitted_at DESC);
                    """);

            // Running balance per holder — upserted on every transaction
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS younglings.coffer_holder (
                        holder_id BIGSERIAL PRIMARY KEY,
                        guild_id BIGINT NOT NULL,
                        discord_user_id BIGINT NOT NULL,
                        amount BIGINT NOT NULL DEFAULT 0,
                        UNIQUE (guild_id, discord_user_id)
                    );
                    """);

            // Transfer log — supports future 2-part verification (REQUIRE_TRANSFER_VERIFICATION flag)
            statement.execute("""
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
                    """);

            // Giveaway log — one row per /coffergiveaway call
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS younglings.coffer_giveaway (
                        giveaway_id BIGSERIAL PRIMARY KEY,
                        guild_id BIGINT NOT NULL,
                        given_by_discord_id BIGINT NOT NULL,
                        recipient_discord_id BIGINT NOT NULL,
                        amount BIGINT NOT NULL,
                        description TEXT NULL,
                        given_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                    );
                    """);

            log.info("Coffer database tables initialized successfully.");

        } catch (Exception e) {
            log.error("Failed to initialize coffer database tables.", e);
            throw new RuntimeException("Failed to initialize coffer database tables", e);
        }
    }
}
