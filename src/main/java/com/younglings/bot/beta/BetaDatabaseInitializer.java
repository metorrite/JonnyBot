package com.younglings.bot.beta;

import com.younglings.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@BService
public class BetaDatabaseInitializer {
    private static final Logger log = LoggerFactory.getLogger(BetaDatabaseInitializer.class);

    public BetaDatabaseInitializer(ConnectionSupplier connectionSupplier) {
        SchemaBootstrapper.run(connectionSupplier, log, "beta", List.of(
                """
                CREATE SCHEMA IF NOT EXISTS younglings;
                """,

                // The servers whose admin team may use JonnyBot's website while it is closed to everyone else: anyone with at least the row's
                // level in one of them gets in. Bot-wide, not per server. The label is only a reminder of whose server it is.
                """
                CREATE TABLE IF NOT EXISTS younglings.beta_guild (
                    guild_id BIGINT PRIMARY KEY,
                    label TEXT NOT NULL DEFAULT '',
                    added_by BIGINT NULL,
                    added_at TIMESTAMPTZ NOT NULL DEFAULT now()
                );
                """,

                // Per server, the least a person must be there to get in (T-345). Rows from before this column keep the old rule, Manage Server or
                // above; the website adds new servers at Administrator.
                """
                ALTER TABLE younglings.beta_guild ADD COLUMN IF NOT EXISTS min_level TEXT NOT NULL DEFAULT 'manage_server';
                """
        ));
        // The first servers on the list come from the BETA_SEED_GUILDS setting, once per database (BetaSeed), never from ids in the code.
        // A database an older build seeded keeps those rows and its seeded marker, so nothing changes there.
    }
}
