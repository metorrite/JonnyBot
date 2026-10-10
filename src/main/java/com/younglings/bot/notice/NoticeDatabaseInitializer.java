package com.younglings.bot.notice;

import com.younglings.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@BService
public class NoticeDatabaseInitializer {
    private static final Logger log = LoggerFactory.getLogger(NoticeDatabaseInitializer.class);

    public NoticeDatabaseInitializer(ConnectionSupplier connectionSupplier) {
        SchemaBootstrapper.run(connectionSupplier, log, "notice", List.of(
                """
                CREATE SCHEMA IF NOT EXISTS younglings;
                """,

                // Messages the bot's owner posts for every server's dashboard to show (a known issue, planned downtime).
                // Not per server: a notice is JonnyBot talking to everyone who runs it.
                """
                CREATE TABLE IF NOT EXISTS younglings.bot_notice (
                    id BIGSERIAL PRIMARY KEY,
                    severity TEXT NOT NULL DEFAULT 'info',
                    body TEXT NOT NULL,
                    created_by BIGINT NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                );
                """
        ));
    }
}
