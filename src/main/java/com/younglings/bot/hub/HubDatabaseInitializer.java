package com.younglings.bot.hub;

import com.younglings.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@BService
public class HubDatabaseInitializer {
    private static final Logger log = LoggerFactory.getLogger(HubDatabaseInitializer.class);

    public HubDatabaseInitializer(ConnectionSupplier connectionSupplier) {
        SchemaBootstrapper.run(connectionSupplier, log, "hub", List.of(
                """
                CREATE SCHEMA IF NOT EXISTS younglings;
                """,

                // One row per server and Hub command, created the first time the server changes something. A command with
                // no row behaves exactly as it always has. extras holds the few settings only one command has, as JSON.
                """
                CREATE TABLE IF NOT EXISTS younglings.hub_command_setting (
                    guild_id BIGINT NOT NULL,
                    command_key TEXT NOT NULL,
                    enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    custom_access BOOLEAN NOT NULL DEFAULT FALSE,
                    allowed_refs TEXT[] NOT NULL DEFAULT '{}',
                    channel_ids BIGINT[] NOT NULL DEFAULT '{}',
                    extras TEXT NOT NULL DEFAULT '{}',
                    PRIMARY KEY (guild_id, command_key)
                );
                """
        ));
    }
}
