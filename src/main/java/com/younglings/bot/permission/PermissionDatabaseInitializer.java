package com.younglings.bot.permission;

import com.younglings.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@BService
public class PermissionDatabaseInitializer {
    private static final Logger log = LoggerFactory.getLogger(PermissionDatabaseInitializer.class);

    public PermissionDatabaseInitializer(ConnectionSupplier connectionSupplier) {
        SchemaBootstrapper.run(connectionSupplier, log, "permission", List.of(
                """
                CREATE SCHEMA IF NOT EXISTS younglings;
                """,

                // A server's own permission levels. The built-in ones (admin, support, developer) are created from the
                // server's old single-role settings the first time they are needed; the rest are the server's own.
                """
                CREATE TABLE IF NOT EXISTS younglings.permission_group (
                    group_id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    group_key TEXT NOT NULL,
                    name TEXT NOT NULL,
                    builtin BOOLEAN NOT NULL DEFAULT FALSE,
                    include_higher BOOLEAN NOT NULL DEFAULT FALSE,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,

                """
                CREATE UNIQUE INDEX IF NOT EXISTS permission_group_key_unique
                ON younglings.permission_group (guild_id, group_key);
                """,

                """
                CREATE UNIQUE INDEX IF NOT EXISTS permission_group_name_unique
                ON younglings.permission_group (guild_id, LOWER(name));
                """,

                """
                CREATE TABLE IF NOT EXISTS younglings.permission_group_role (
                    group_id BIGINT NOT NULL REFERENCES younglings.permission_group(group_id) ON DELETE CASCADE,
                    role_id BIGINT NOT NULL,
                    PRIMARY KEY (group_id, role_id)
                );
                """
        ));
    }
}
