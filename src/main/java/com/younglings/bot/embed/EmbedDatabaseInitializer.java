package com.younglings.bot.embed;

import com.younglings.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@BService
public class EmbedDatabaseInitializer {
    private static final Logger log = LoggerFactory.getLogger(EmbedDatabaseInitializer.class);

    public EmbedDatabaseInitializer(ConnectionSupplier connectionSupplier) {
        SchemaBootstrapper.run(connectionSupplier, log, "embed", List.of(
                """
                CREATE SCHEMA IF NOT EXISTS younglings;
                """,

                // One row per embed posted via /embed post — lets /embed remove find and delete
                // exactly what this system posted, without touching messages from anything else.
                """
                CREATE TABLE IF NOT EXISTS younglings.posted_embed (
                    posted_embed_id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    channel_id BIGINT NOT NULL,
                    message_id BIGINT NOT NULL,
                    embed_type TEXT NOT NULL,
                    posted_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,

                """
                CREATE INDEX IF NOT EXISTS posted_embed_guild_idx
                ON younglings.posted_embed (guild_id);
                """
        ));
    }
}
