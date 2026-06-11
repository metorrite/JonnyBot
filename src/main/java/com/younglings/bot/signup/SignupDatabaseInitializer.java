package com.younglings.bot.signup;

import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.Statement;

@BService
public class SignupDatabaseInitializer {
    private static final Logger log = LoggerFactory.getLogger(SignupDatabaseInitializer.class);

    public SignupDatabaseInitializer(ConnectionSupplier connectionSupplier) {
        initialize(connectionSupplier);
    }

    private void initialize(ConnectionSupplier connectionSupplier) {
        log.info("Initializing signup database tables...");

        try (Connection connection = connectionSupplier.getConnection();
             Statement statement = connection.createStatement()) {

            statement.execute("""
                    CREATE SCHEMA IF NOT EXISTS younglings;
                    """);

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS younglings.signup (
                        signup_id BIGSERIAL PRIMARY KEY,
                        guild_id BIGINT NOT NULL,
                        title TEXT NOT NULL,
                        notification_message TEXT NULL,
                        max_signups INTEGER NULL,
                        status TEXT NOT NULL DEFAULT 'ACTIVE',
                        created_by_user_id BIGINT NOT NULL,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                        deleted_at TIMESTAMPTZ NULL
                    );
                    """);

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS younglings.signup_entry (
                        entry_id BIGSERIAL PRIMARY KEY,
                        signup_id BIGINT NOT NULL REFERENCES younglings.signup(signup_id) ON DELETE CASCADE,
                        discord_user_id BIGINT NOT NULL,
                        rsn TEXT NOT NULL,
                        queue_position INTEGER NOT NULL,
                        added_by_user_id BIGINT NOT NULL,
                        added_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                        UNIQUE (signup_id, discord_user_id)
                    );
                    """);

            statement.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS signup_entry_unique_rsn_lower
                    ON younglings.signup_entry (signup_id, LOWER(rsn));
                    """);

            statement.execute("""
                    CREATE INDEX IF NOT EXISTS signup_guild_deleted_idx
                    ON younglings.signup (guild_id, deleted_at);
                    """);

            statement.execute("""
                    CREATE INDEX IF NOT EXISTS signup_status_idx
                    ON younglings.signup (status);
                    """);

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS younglings.signup_message (
                        message_id BIGINT PRIMARY KEY,
                        signup_id BIGINT NOT NULL REFERENCES younglings.signup(signup_id) ON DELETE CASCADE,
                        guild_id BIGINT NOT NULL,
                        channel_id BIGINT NOT NULL,
                        message_type TEXT NOT NULL,
                        active BOOLEAN NOT NULL DEFAULT TRUE,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                    );
                    """);

            // Migration: add type system columns (safe to run on existing databases)
            statement.execute("""
                    ALTER TABLE younglings.signup
                        ALTER COLUMN notification_message DROP NOT NULL;
                    """);

            statement.execute("""
                    ALTER TABLE younglings.signup
                        ADD COLUMN IF NOT EXISTS signup_type TEXT NOT NULL DEFAULT 'QUEUE';
                    """);

            statement.execute("""
                    ALTER TABLE younglings.signup
                        ADD COLUMN IF NOT EXISTS submission_field_label TEXT NULL;
                    """);

            statement.execute("""
                    ALTER TABLE younglings.signup
                        ADD COLUMN IF NOT EXISTS group_role_id BIGINT NULL;
                    """);

            statement.execute("""
                    ALTER TABLE younglings.signup_entry
                        ADD COLUMN IF NOT EXISTS submission_value TEXT NULL;
                    """);

            // Migration: relax per-user uniqueness so SUBMISSION signups allow multiple entries per user
            statement.execute("""
                    ALTER TABLE younglings.signup_entry
                        DROP CONSTRAINT IF EXISTS signup_entry_signup_id_discord_user_id_key;
                    """);

            statement.execute("""
                    DROP INDEX IF EXISTS younglings.signup_entry_unique_rsn_lower;
                    """);

            log.info("Signup database tables initialized successfully.");

        } catch (Exception e) {
            log.error("Failed to initialize signup database tables.", e);
            throw new RuntimeException("Failed to initialize signup database tables", e);
        }
    }
}
