package com.younglings.bot.database;

import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

/**
 * Shared boot-time schema setup for the bot's feature modules (signup, poll, coffer).
 * <p>
 * Each feature owns its own list of idempotent DDL statements (using
 * {@code CREATE ... IF NOT EXISTS} / {@code ADD COLUMN IF NOT EXISTS}) and runs them once, in
 * order, on startup via its own small {@code *DatabaseInitializer} service. This class exists
 * purely to avoid repeating the same connection-handling/logging/error-wrapping boilerplate in
 * every one of those initializers.
 */
public final class SchemaBootstrapper {

    private SchemaBootstrapper() {}

    /**
     * Runs each statement in {@code statements}, in order, against a single connection.
     *
     * @param connectionSupplier source of the JDBC connection to use
     * @param log                the calling initializer's logger, so log lines attribute to it
     * @param featureName        short label used in log messages, e.g. {@code "signup"}
     * @param statements         idempotent DDL statements to execute in order
     * @throws RuntimeException wrapping any {@link Exception} thrown while running the statements
     */
    public static void run(ConnectionSupplier connectionSupplier, Logger log, String featureName,
                            List<String> statements) {
        log.info("Initializing {} database tables...", featureName);

        try (Connection connection = connectionSupplier.getConnection();
             Statement statement = connection.createStatement()) {

            for (String sql : statements) {
                statement.execute(sql);
            }

            log.info("{} database tables initialized successfully.", featureName);

        } catch (Exception e) {
            log.error("Failed to initialize {} database tables.", featureName, e);
            throw new RuntimeException("Failed to initialize " + featureName + " database tables", e);
        }
    }
}
