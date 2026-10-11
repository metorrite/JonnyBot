package com.younglings.bot.premium;

import com.younglings.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@BService
public class PremiumDatabaseInitializer {
    private static final Logger log = LoggerFactory.getLogger(PremiumDatabaseInitializer.class);

    public PremiumDatabaseInitializer(ConnectionSupplier connectionSupplier) {
        SchemaBootstrapper.run(connectionSupplier, log, "premium", List.of(
                """
                CREATE SCHEMA IF NOT EXISTS younglings;
                """,

                // The servers on the faster ("premium") polling plan: their clan's players are refreshed more often than a standard
                // server's. Bot-wide, set by the bot's owner. The label is only a reminder of whose server it is.
                """
                CREATE TABLE IF NOT EXISTS younglings.premium_guild (
                    guild_id BIGINT PRIMARY KEY,
                    label TEXT NOT NULL DEFAULT '',
                    added_by BIGINT NULL,
                    added_at TIMESTAMPTZ NOT NULL DEFAULT now()
                );
                """
        ));
    }
}
