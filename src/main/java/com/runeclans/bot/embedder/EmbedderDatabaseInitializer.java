package com.runeclans.bot.embedder;

import com.runeclans.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@BService
public class EmbedderDatabaseInitializer {
    private static final Logger log = LoggerFactory.getLogger(EmbedderDatabaseInitializer.class);

    public EmbedderDatabaseInitializer(ConnectionSupplier connectionSupplier) {
        SchemaBootstrapper.run(connectionSupplier, log, "embedder", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",

                // One row per message composed on the dashboard's Message embedder page. A row with no message_id is a draft; once it is posted the
                // channel and message id say which Discord message the row edits and deletes. body_json is the shared message editor's shape.
                """
                CREATE TABLE IF NOT EXISTS younglings.embedder_message (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    name TEXT NOT NULL,
                    body_json TEXT NOT NULL,
                    channel_id BIGINT NULL,
                    message_id BIGINT NULL,
                    created_by BIGINT NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    posted_at TIMESTAMPTZ NULL
                );
                """,
                "CREATE INDEX IF NOT EXISTS embedder_message_guild_idx ON younglings.embedder_message (guild_id);"
        ));
    }
}
