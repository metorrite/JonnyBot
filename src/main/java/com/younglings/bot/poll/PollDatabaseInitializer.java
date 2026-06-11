package com.younglings.bot.poll;

import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.Statement;

@BService
public class PollDatabaseInitializer {
    private static final Logger log = LoggerFactory.getLogger(PollDatabaseInitializer.class);

    public PollDatabaseInitializer(ConnectionSupplier connectionSupplier) {
        initialize(connectionSupplier);
    }

    private void initialize(ConnectionSupplier connectionSupplier) {
        log.info("Initializing poll database tables...");

        try (Connection connection = connectionSupplier.getConnection();
             Statement statement = connection.createStatement()) {

            statement.execute("CREATE SCHEMA IF NOT EXISTS younglings;");

            statement.execute("""
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
                    """);

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS younglings.poll_option (
                        option_id BIGSERIAL PRIMARY KEY,
                        poll_id BIGINT NOT NULL REFERENCES younglings.poll(poll_id) ON DELETE CASCADE,
                        option_number INTEGER NOT NULL,
                        label TEXT NOT NULL,
                        UNIQUE (poll_id, option_number)
                    );
                    """);

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS younglings.poll_vote (
                        vote_id BIGSERIAL PRIMARY KEY,
                        poll_id BIGINT NOT NULL REFERENCES younglings.poll(poll_id) ON DELETE CASCADE,
                        option_id BIGINT NOT NULL REFERENCES younglings.poll_option(option_id) ON DELETE CASCADE,
                        user_id BIGINT NOT NULL,
                        voted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                        UNIQUE (poll_id, option_id, user_id)
                    );
                    """);

            statement.execute("""
                    CREATE INDEX IF NOT EXISTS poll_guild_status_idx
                    ON younglings.poll (guild_id, status);
                    """);

            log.info("Poll database tables initialized successfully.");

        } catch (Exception e) {
            log.error("Failed to initialize poll database tables.", e);
            throw new RuntimeException("Failed to initialize poll database tables", e);
        }
    }
}
