package com.younglings.bot.database;

import com.younglings.bot.config.BotConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.flywaydb.core.Flyway;
import org.jspecify.annotations.NonNull;

import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;

@BService
public class DatabaseSource implements ConnectionSupplier {

    private final HikariDataSource dataSource;

    public DatabaseSource(BotConfig botConfig) {
        HikariConfig hikariConfig = createHikariConfig(botConfig.getDatabaseUrl());
        hikariConfig.setMaximumPoolSize(5);

        this.dataSource = new HikariDataSource(hikariConfig);

        runMigrations();
    }

    private void runMigrations() {
        Flyway.configure(getClass().getClassLoader())
                .dataSource(dataSource)
                .schemas("bc")
                .locations("bc_database_scripts")
                .validateMigrationNaming(true)
                .loggers("slf4j")
                .load()
                .migrate();
    }

    @Override
    public @NonNull Duration getMaxTransactionDuration() {
        return ConnectionSupplier.super.getMaxTransactionDuration();
    }

    @Override
    public int getMaxConnections() {
        return 5;
    }

    @Override
    public @NonNull Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    private static HikariConfig createHikariConfig(String databaseUrl) {
        URI uri = URI.create(databaseUrl.trim());

        String userInfo = uri.getUserInfo();
        if (userInfo == null || !userInfo.contains(":")) {
            throw new IllegalArgumentException(
                    "DATABASE_URL must include credentials: postgresql://user:password@host:port/db"
            );
        }

        int colonIdx = userInfo.indexOf(':');
        String username = userInfo.substring(0, colonIdx);
        String password = userInfo.substring(colonIdx + 1);

        String jdbcUrl = "jdbc:postgresql://" + uri.getHost() + ":" + uri.getPort() + uri.getPath();

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);

        return config;
    }
}
