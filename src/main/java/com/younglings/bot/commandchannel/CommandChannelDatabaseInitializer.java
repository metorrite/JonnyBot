package com.younglings.bot.commandchannel;

import com.younglings.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@BService
public class CommandChannelDatabaseInitializer {
    private static final Logger log = LoggerFactory.getLogger(CommandChannelDatabaseInitializer.class);

    public CommandChannelDatabaseInitializer(ConnectionSupplier connectionSupplier) {
        SchemaBootstrapper.run(connectionSupplier, log, "commandchannel", List.of(
                """
                CREATE SCHEMA IF NOT EXISTS younglings;
                """,

                // A "group" is one command-only rule: a set of channels sharing one notice message and
                // one set of who-it-applies-to rules. custom_message NULL means "use the built-in
                // default". apply_below_role_id / exempt_from_role_id are the two rank-based rules (see
                // CommandChannelService#appliesTo); the per-role APPLY/EXEMPT lists live in
                // command_channel_role.
                """
                CREATE TABLE IF NOT EXISTS younglings.command_channel_group (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    name TEXT NOT NULL,
                    custom_message TEXT NULL,
                    enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    apply_below_role_id BIGINT NULL,
                    exempt_from_role_id BIGINT NULL
                );
                """,

                // Postgres table constraints can't reference an expression like LOWER(name) (only plain
                // columns) — a unique index is the way to make "Default"/"default" collide.
                """
                CREATE UNIQUE INDEX IF NOT EXISTS command_channel_group_unique_name
                ON younglings.command_channel_group (guild_id, LOWER(name));
                """,

                // One row per channel. Unique on (guild, channel): a channel can only ever be in one
                // group, so a message there always resolves to exactly one rule set.
                """
                CREATE TABLE IF NOT EXISTS younglings.command_channel_channel (
                    id BIGSERIAL PRIMARY KEY,
                    group_id BIGINT NOT NULL REFERENCES younglings.command_channel_group(id) ON DELETE CASCADE,
                    guild_id BIGINT NOT NULL,
                    channel_id BIGINT NOT NULL
                );
                """,

                """
                CREATE UNIQUE INDEX IF NOT EXISTS command_channel_channel_unique
                ON younglings.command_channel_channel (guild_id, channel_id);
                """,

                // mode is 'APPLY' (this role is subject to the rule) or 'EXEMPT' (this role is not) — a
                // fixed two-value set in code, same reasoning as tracking_group_config.group_key. The
                // primary key makes a role either one or the other per group, never both.
                """
                CREATE TABLE IF NOT EXISTS younglings.command_channel_role (
                    group_id BIGINT NOT NULL REFERENCES younglings.command_channel_group(id) ON DELETE CASCADE,
                    role_id BIGINT NOT NULL,
                    mode TEXT NOT NULL,
                    PRIMARY KEY (group_id, role_id)
                );
                """
        ));
    }
}
