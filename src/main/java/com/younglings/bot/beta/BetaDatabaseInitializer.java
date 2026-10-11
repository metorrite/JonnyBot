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

                // The servers whose admin team may use JonnyBot's website while it is closed to everyone else: anyone with Administrator or
                // Manage Server in one of them gets in. Bot-wide, not per server. The label is only a reminder of whose server it is.
                """
                CREATE TABLE IF NOT EXISTS younglings.beta_guild (
                    guild_id BIGINT PRIMARY KEY,
                    label TEXT NOT NULL DEFAULT '',
                    added_by BIGINT NULL,
                    added_at TIMESTAMPTZ NOT NULL DEFAULT now()
                );
                """,

                // The first testers, put in once. A second marker table records that it happened, so removing one of them later sticks.
                """
                DO $$
                BEGIN
                    IF to_regclass('younglings.beta_guild_seeded') IS NULL THEN
                        INSERT INTO younglings.beta_guild (guild_id, label) VALUES
                            (577853260153618443, 'Koalafied'),
                            (1388208054008545300, 'RS Noobs'),
                            (1062157106347786240, 'Eternal Rising')
                        ON CONFLICT (guild_id) DO NOTHING;
                        CREATE TABLE younglings.beta_guild_seeded (done BOOLEAN);
                    END IF;
                END $$;
                """
        ));
    }
}
