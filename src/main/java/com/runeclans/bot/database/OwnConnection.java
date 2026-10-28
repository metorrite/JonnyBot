package com.runeclans.bot.database;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * A database connection of its own, outside the pool, for work that must hold one session for a long time: {@code LISTEN} (the cache bus)
 * or a session advisory lock (the api leader). Opened from {@code DATABASE_URL} ({@code postgresql://user:password@host:port/db}).
 */
public final class OwnConnection {
    private OwnConnection() {}

    /** Opens one; {@code applicationName} shows in {@code pg_stat_activity}. */
    public static Connection open(String databaseUrl, String applicationName) throws SQLException {
        URI uri = URI.create(databaseUrl.trim());
        String userInfo = uri.getUserInfo() == null ? "" : uri.getUserInfo();
        int colon = userInfo.indexOf(':');
        Properties props = new Properties();
        props.setProperty("user", colon < 0 ? userInfo : userInfo.substring(0, colon));
        if (colon >= 0) props.setProperty("password", userInfo.substring(colon + 1));
        props.setProperty("ApplicationName", applicationName);
        props.setProperty("connectTimeout", "10");
        props.setProperty("tcpKeepAlive", "true");
        String jdbc = "jdbc:postgresql://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "") + uri.getPath();
        return DriverManager.getConnection(jdbc, props);
    }
}
