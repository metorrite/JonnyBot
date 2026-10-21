package com.younglings.bot.rsnlink;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * The link service's own connection pool (LINK-SYSTEM.md section 2: its own DataSource, never the clan database's). Built from
 * {@code LINK_DATABASE_URL} in the same {@code postgresql://user:password@host:port/db} form as the bot's {@code DATABASE_URL}.
 */
public final class LinkDatabase implements AutoCloseable {
    private final HikariDataSource dataSource;

    public LinkDatabase(String databaseUrl, int poolSize) {
        URI uri = URI.create(databaseUrl.trim());
        String userInfo = uri.getUserInfo();
        if (userInfo == null) throw new LinkSettings.InvalidSettings("LINK_DATABASE_URL must include a user: postgresql://user:password@host:port/db");
        int colon = userInfo.indexOf(':');
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:postgresql://" + uri.getHost() + ":" + (uri.getPort() < 0 ? 5432 : uri.getPort()) + uri.getPath());
        config.setUsername(colon < 0 ? userInfo : userInfo.substring(0, colon));
        config.setPassword(colon < 0 ? "" : userInfo.substring(colon + 1));
        config.setMaximumPoolSize(poolSize);
        config.setPoolName("rsnlink");
        this.dataSource = new HikariDataSource(config);
    }

    public Connection connection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
