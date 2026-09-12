package com.younglings.bot.poll;

import com.younglings.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@BService
public class PollDatabaseInitializer {
    private static final Logger log = LoggerFactory.getLogger(PollDatabaseInitializer.class);

    public PollDatabaseInitializer(ConnectionSupplier connectionSupplier) {
        SchemaBootstrapper.run(connectionSupplier, log, "poll", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",

                """
                CREATE TABLE IF NOT EXISTS younglings.poll (
                    poll_id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    channel_id BIGINT NOT NULL,
                    message_id BIGINT NULL,
                    title TEXT NOT NULL,
                    anonymous BOOLEAN NOT NULL DEFAULT FALSE,
                    multiple_votes BOOLEAN NOT NULL DEFAULT FALSE,
                    status TEXT NOT NULL DEFAULT 'ACTIVE',
                    created_by_user_id BIGINT NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    closed_at TIMESTAMPTZ NULL
                );
                """,

                """
                CREATE TABLE IF NOT EXISTS younglings.poll_option (
                    option_id BIGSERIAL PRIMARY KEY,
                    poll_id BIGINT NOT NULL REFERENCES younglings.poll(poll_id) ON DELETE CASCADE,
                    option_number INTEGER NOT NULL,
                    label TEXT NOT NULL,
                    UNIQUE (poll_id, option_number)
                );
                """,

                """
                CREATE TABLE IF NOT EXISTS younglings.poll_vote (
                    vote_id BIGSERIAL PRIMARY KEY,
                    poll_id BIGINT NOT NULL REFERENCES younglings.poll(poll_id) ON DELETE CASCADE,
                    option_id BIGINT NOT NULL REFERENCES younglings.poll_option(option_id) ON DELETE CASCADE,
                    user_id BIGINT NOT NULL,
                    voted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    UNIQUE (poll_id, option_id, user_id)
                );
                """,

                """
                CREATE INDEX IF NOT EXISTS poll_guild_status_idx
                ON younglings.poll (guild_id, status);
                """
        ));
    }
}
