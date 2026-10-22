package com.younglings.bot.siteaccess;

import com.younglings.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@BService
public class SiteSwitchDatabaseInitializer {
    private static final Logger log = LoggerFactory.getLogger(SiteSwitchDatabaseInitializer.class);

    public SiteSwitchDatabaseInitializer(ConnectionSupplier connectionSupplier) {
        SchemaBootstrapper.run(connectionSupplier, log, "site access switches", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",

                // A switch that has never been touched has no row and counts as closed. The website's environment variables stay
                // the hard override (see the website's siteSwitches.ts).
                """
                CREATE TABLE IF NOT EXISTS younglings.site_switch (
                    switch_key TEXT PRIMARY KEY,
                    enabled BOOLEAN NOT NULL DEFAULT FALSE,
                    updated_by BIGINT NULL,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                );
                """
        ));
    }
}
