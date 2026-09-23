package com.younglings.bot.signup;

import com.younglings.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@BService
public class SignupDatabaseInitializer {
    private static final Logger log = LoggerFactory.getLogger(SignupDatabaseInitializer.class);

    public SignupDatabaseInitializer(ConnectionSupplier connectionSupplier) {
        SchemaBootstrapper.run(connectionSupplier, log, "signup", List.of(
                """
                CREATE SCHEMA IF NOT EXISTS younglings;
                """,

                """
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
                """,

                """
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
                """,

                """
                CREATE UNIQUE INDEX IF NOT EXISTS signup_entry_unique_rsn_lower
                ON younglings.signup_entry (signup_id, LOWER(rsn));
                """,

                """
                CREATE INDEX IF NOT EXISTS signup_guild_deleted_idx
                ON younglings.signup (guild_id, deleted_at);
                """,

                """
                CREATE INDEX IF NOT EXISTS signup_status_idx
                ON younglings.signup (status);
                """,

                """
                CREATE TABLE IF NOT EXISTS younglings.signup_message (
                    message_id BIGINT PRIMARY KEY,
                    signup_id BIGINT NOT NULL REFERENCES younglings.signup(signup_id) ON DELETE CASCADE,
                    guild_id BIGINT NOT NULL,
                    channel_id BIGINT NOT NULL,
                    message_type TEXT NOT NULL,
                    active BOOLEAN NOT NULL DEFAULT TRUE,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,

                // Migration: add type system columns (safe to run on existing databases)
                """
                ALTER TABLE younglings.signup
                    ALTER COLUMN notification_message DROP NOT NULL;
                """,

                """
                ALTER TABLE younglings.signup
                    ADD COLUMN IF NOT EXISTS signup_type TEXT NOT NULL DEFAULT 'QUEUE';
                """,

                """
                ALTER TABLE younglings.signup
                    ADD COLUMN IF NOT EXISTS submission_field_label TEXT NULL;
                """,

                """
                ALTER TABLE younglings.signup
                    ADD COLUMN IF NOT EXISTS group_role_id BIGINT NULL;
                """,

                """
                ALTER TABLE younglings.signup_entry
                    ADD COLUMN IF NOT EXISTS submission_value TEXT NULL;
                """,

                // Migration: relax per-user uniqueness so SUBMISSION signups allow multiple entries per user
                """
                ALTER TABLE younglings.signup_entry
                    DROP CONSTRAINT IF EXISTS signup_entry_signup_id_discord_user_id_key;
                """,

                """
                DROP INDEX IF EXISTS younglings.signup_entry_unique_rsn_lower;
                """,

                // Migration: track last activity (bumped whenever an entry is added) so stale,
                // untouched signups can be auto-closed after a long period of inactivity.
                """
                ALTER TABLE younglings.signup
                    ADD COLUMN IF NOT EXISTS last_activity_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
                """,

                """
                CREATE INDEX IF NOT EXISTS signup_last_activity_idx
                ON younglings.signup (last_activity_at) WHERE deleted_at IS NULL;
                """
        ));
    }
}
