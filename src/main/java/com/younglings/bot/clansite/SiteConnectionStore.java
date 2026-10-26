package com.younglings.bot.clansite;

import com.younglings.bot.clansite.SiteDiscordSettings.OnlineMode;
import com.younglings.bot.database.SchemaBootstrapper;
import com.younglings.bot.hubsites.SiteChannelMappings;
import com.younglings.bot.internal.SiteNewsService;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A clan site's connections (N7, the site admin's Connections page): which Discord server feeds the site, who supplies its Discord data (the
 * provider), and the Discord features the site shows (the online list, the channel feeds and Discord events). These settings live only
 * here and are edited only from the site admin; the provider reads them when the website asks it for data (the website pulls).
 * <p>
 * A site is stored by its server's id, so the connected server can only be that server (or none, after a disconnect). The provider is
 * {@code runeclan-bot} today; the column holds a hub application id once other bots can supply data (runeclans-hub
 * docs/PLATFORM-DESIGN.md, sections 4 and 12).
 * <p>
 * One-time copy: when this table is first created, every server's online mode ({@code site_discord_setting}) and website channels
 * ({@code site_news_channel}) are copied in, so live sites keep what their admins chose on the bot dashboard. A server's row is only ever
 * copied while it has none, so later edits here are never overwritten.
 * <p>
 * With {@code SITES_BACKEND=hub} the feed channels are kept as mappings of the server's hub site ({@link SiteChannelMappings}); the rest
 * stays here.
 * <p>
 * The admin's yes to show a private feed channel (one {@code @everyone} can't read) is kept here too, per channel, whichever side keeps the
 * channels: a channel that turns private after it was chosen has no yes, so it drops off the site until the admin says so.
 */
@BService
public class SiteConnectionStore {
    private static final Logger log = LoggerFactory.getLogger(SiteConnectionStore.class);
    /** The one provider that works today. */
    public static final String RUNECLAN_BOT = "runeclan-bot";
    public static final int DEFAULT_ONLINE_LIMIT = 24;
    public static final int MAX_ONLINE_LIMIT = 100;
    public static final int DEFAULT_FEED_LIMIT = 5;
    public static final int MAX_FEED_LIMIT = 25;

    /** One channel the site shows, in the admin's order, with an optional display name. */
    public record Channel(long channelId, String label, int position) {}

    /**
     * A site's connections. {@code discordGuildId} null means disconnected: the site then shows nothing from Discord. {@code connectedBy}
     * is null for a site connected when it was made (or by the one-time copy).
     */
    public record Connection(long siteId, Long discordGuildId, Long connectedBy, Instant connectedAt, String providerId, OnlineMode onlineMode,
                             int onlineLimit, boolean groupByRole, int feedLimit, boolean eventsEnabled, List<Channel> channels, boolean copiedFromBot) {
        public boolean connected() {
            return discordGuildId != null && discordGuildId == siteId;
        }

        /** A new site: its own server, RuneClan Bot, the online list and feeds off, Discord events on (as before connections existed). */
        static Connection defaults(long siteId) {
            return new Connection(siteId, siteId, null, null, RUNECLAN_BOT, OnlineMode.OFF, DEFAULT_ONLINE_LIMIT, true, DEFAULT_FEED_LIMIT, true,
                    List.of(), false);
        }

        public Connection withFeatures(OnlineMode mode, int onlineLimit, boolean groupByRole, int feedLimit, boolean eventsEnabled, List<Channel> channels) {
            return new Connection(siteId, discordGuildId, connectedBy, connectedAt, providerId, mode, onlineLimit, groupByRole, feedLimit, eventsEnabled,
                    channels, copiedFromBot);
        }
    }

    private final ConnectionSupplier connectionSupplier;
    private final SiteChannelMappings mappings;

    /** Takes the two older stores only so their tables exist before the one-time copy reads them. */
    public SiteConnectionStore(ConnectionSupplier connectionSupplier, SiteDiscordSettings discordSettings, SiteNewsService news, SiteChannelMappings mappings) {
        this.connectionSupplier = connectionSupplier;
        this.mappings = mappings;
        SchemaBootstrapper.run(connectionSupplier, log, "site connections", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.site_connection (
                    guild_id BIGINT PRIMARY KEY,
                    discord_guild_id BIGINT NULL,
                    connected_by BIGINT NULL,
                    connected_at TIMESTAMPTZ NULL,
                    provider_id TEXT NOT NULL DEFAULT 'runeclan-bot',
                    online_mode TEXT NOT NULL DEFAULT 'off',
                    online_limit INTEGER NOT NULL DEFAULT 24,
                    online_group_by_role BOOLEAN NOT NULL DEFAULT TRUE,
                    feed_limit INTEGER NOT NULL DEFAULT 5,
                    events_enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    copied_from_bot BOOLEAN NOT NULL DEFAULT FALSE,
                    updated_by BIGINT NULL,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.site_connection_channel (
                    guild_id BIGINT NOT NULL,
                    channel_id BIGINT NOT NULL,
                    label TEXT NULL,
                    position INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (guild_id, channel_id)
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.site_private_channel_ok (
                    guild_id BIGINT NOT NULL,
                    channel_id BIGINT NOT NULL,
                    approved_by BIGINT NOT NULL,
                    approved_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (guild_id, channel_id)
                );
                """));
        copyFromBot();
    }

    /** The one-time copy (see the class comment), for every server that has no row yet. Safe to run on every start. */
    void copyFromBot() {
        try (java.sql.Connection c = connectionSupplier.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement channels = c.prepareStatement("""
                         INSERT INTO younglings.site_connection_channel (guild_id, channel_id, label, position)
                         SELECT n.guild_id, n.channel_id, n.label, n.position FROM younglings.site_news_channel n
                         WHERE NOT EXISTS (SELECT 1 FROM younglings.site_connection s WHERE s.guild_id = n.guild_id)
                         ON CONFLICT DO NOTHING
                         """);
                 PreparedStatement rows = c.prepareStatement("""
                         INSERT INTO younglings.site_connection (guild_id, discord_guild_id, online_mode, copied_from_bot)
                         SELECT g.guild_id, g.guild_id, COALESCE(d.online_mode, 'off'), TRUE
                         FROM (SELECT guild_id FROM younglings.site_discord_setting UNION SELECT guild_id FROM younglings.site_news_channel) g
                         LEFT JOIN younglings.site_discord_setting d ON d.guild_id = g.guild_id
                         ON CONFLICT (guild_id) DO NOTHING
                         """)) {
                channels.executeUpdate();
                int copied = rows.executeUpdate();
                c.commit();
                if (copied > 0) log.info("Copied the Discord website settings of {} server(s) into their site connections", copied);
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw fail("copy the bot's Discord website settings into site connections", e);
        }
    }

    public Connection of(long siteId) {
        try (java.sql.Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("""
                     SELECT discord_guild_id, connected_by, connected_at, provider_id, online_mode, online_limit, online_group_by_role, feed_limit,
                            events_enabled, copied_from_bot
                     FROM younglings.site_connection WHERE guild_id = ?
                     """)) {
            s.setLong(1, siteId);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) {
                    Connection defaults = Connection.defaults(siteId);
                    List<Channel> mapped = mapped(siteId);
                    return mapped == null ? defaults : defaults.withFeatures(defaults.onlineMode(), defaults.onlineLimit(), defaults.groupByRole(),
                            defaults.feedLimit(), defaults.eventsEnabled(), mapped);
                }
                long discord = rs.getLong(1);
                Long discordGuild = rs.wasNull() ? null : discord;
                long by = rs.getLong(2);
                Long connectedBy = rs.wasNull() ? null : by;
                Timestamp at = rs.getTimestamp(3);
                return new Connection(siteId, discordGuild, connectedBy, at == null ? null : at.toInstant(), rs.getString(4),
                        OnlineMode.parse(rs.getString(5)).orElse(OnlineMode.OFF), rs.getInt(6), rs.getBoolean(7), rs.getInt(8), rs.getBoolean(9),
                        channelsOf(c, siteId), rs.getBoolean(10));
            }
        } catch (SQLException e) {
            throw fail("read a site's connections", e);
        }
    }

    /** The feed channels: the hub site's mappings under {@code SITES_BACKEND=hub}, else this store's own. */
    private List<Channel> channelsOf(java.sql.Connection c, long siteId) throws SQLException {
        List<Channel> mapped = mapped(siteId);
        return mapped != null ? mapped : channels(c, siteId);
    }

    /** The hub site's mapped feed channels, or null when this store's own table holds them. */
    private List<Channel> mapped(long siteId) {
        return mappings.channels(siteId, SiteChannelMappings.Kind.FEED)
                .map(list -> list.stream().map(m -> new Channel(m.channelId(), m.label(), m.position())).toList())
                .orElse(null);
    }

    private static List<Channel> channels(java.sql.Connection c, long siteId) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "SELECT channel_id, label, position FROM younglings.site_connection_channel WHERE guild_id = ? ORDER BY position, channel_id")) {
            s.setLong(1, siteId);
            List<Channel> out = new ArrayList<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) out.add(new Channel(rs.getLong(1), rs.getString(2), rs.getInt(3)));
            }
            return List.copyOf(out);
        }
    }

    /** The feed channels whose admin said to show them although they're private (see the class comment). */
    public Set<Long> privateOk(long siteId) {
        try (java.sql.Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT channel_id FROM younglings.site_private_channel_ok WHERE guild_id = ?")) {
            s.setLong(1, siteId);
            Set<Long> out = new HashSet<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) out.add(rs.getLong(1));
            }
            return Set.copyOf(out);
        } catch (SQLException e) {
            throw fail("read a site's private channel choices", e);
        }
    }

    /** Saves the Discord features, keeping the admin's yes for each private channel that is still chosen. */
    public void saveFeatures(Connection next, long actorId) {
        saveFeatures(next, actorId, null);
    }

    /**
     * Saves the Discord features (the online list, the feeds' channels and post count, Discord events), keeping the server and provider.
     * {@code privateOk} is every chosen private channel the admin said to show (a yes already given keeps who gave it and when); null
     * keeps the yeses of the channels still chosen. A channel no longer chosen loses its yes either way.
     */
    public void saveFeatures(Connection next, long actorId, Set<Long> privateOk) {
        boolean mapped = mappings.replace(next.siteId(), SiteChannelMappings.Kind.FEED,
                next.channels().stream().map(ch -> new SiteChannelMappings.Mapped(ch.channelId(), ch.label(), ch.position())).toList());
        try (java.sql.Connection c = connectionSupplier.getConnection()) {
            c.setAutoCommit(false);
            try {
                upsert(c, next, actorId);
                if (!mapped) saveChannels(c, next);
                savePrivateOk(c, next, privateOk, actorId);
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw fail("save a site's Discord features", e);
        }
    }

    private static void saveChannels(java.sql.Connection c, Connection next) throws SQLException {
        try (PreparedStatement delete = c.prepareStatement("DELETE FROM younglings.site_connection_channel WHERE guild_id = ?")) {
            delete.setLong(1, next.siteId());
            delete.executeUpdate();
        }
        try (PreparedStatement insert = c.prepareStatement(
                "INSERT INTO younglings.site_connection_channel (guild_id, channel_id, label, position) VALUES (?, ?, ?, ?)")) {
            int position = 0;
            for (Channel channel : next.channels()) {
                insert.setLong(1, next.siteId());
                insert.setLong(2, channel.channelId());
                insert.setString(3, channel.label());
                insert.setInt(4, position++);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static void savePrivateOk(java.sql.Connection c, Connection next, Set<Long> privateOk, long actorId) throws SQLException {
        List<Long> keep = next.channels().stream().map(Channel::channelId).filter(id -> privateOk == null || privateOk.contains(id)).toList();
        try (PreparedStatement delete = c.prepareStatement(
                "DELETE FROM younglings.site_private_channel_ok WHERE guild_id = ? AND NOT (channel_id = ANY (?))")) {
            delete.setLong(1, next.siteId());
            delete.setArray(2, c.createArrayOf("BIGINT", keep.toArray()));
            delete.executeUpdate();
        }
        if (privateOk == null || keep.isEmpty()) return;
        try (PreparedStatement insert = c.prepareStatement("""
                INSERT INTO younglings.site_private_channel_ok (guild_id, channel_id, approved_by) VALUES (?, ?, ?)
                ON CONFLICT (guild_id, channel_id) DO NOTHING
                """)) {
            for (long channelId : keep) {
                insert.setLong(1, next.siteId());
                insert.setLong(2, channelId);
                insert.setLong(3, actorId);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    /** Connects the site's own server ({@code connect}) or disconnects it; the features are kept for when it is connected again. */
    public void setConnected(long siteId, boolean connect, long actorId) {
        Connection now = of(siteId);
        Connection next = new Connection(siteId, connect ? siteId : null, connect ? actorId : null, connect ? Instant.now() : null, now.providerId(),
                now.onlineMode(), now.onlineLimit(), now.groupByRole(), now.feedLimit(), now.eventsEnabled(), now.channels(), now.copiedFromBot());
        try (java.sql.Connection c = connectionSupplier.getConnection()) {
            upsert(c, next, actorId);
        } catch (SQLException e) {
            throw fail("connect or disconnect a site's server", e);
        }
    }

    private static void upsert(java.sql.Connection c, Connection next, long actorId) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("""
                INSERT INTO younglings.site_connection (guild_id, discord_guild_id, connected_by, connected_at, provider_id, online_mode, online_limit,
                    online_group_by_role, feed_limit, events_enabled, copied_from_bot, updated_by, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
                ON CONFLICT (guild_id) DO UPDATE SET discord_guild_id = EXCLUDED.discord_guild_id, connected_by = EXCLUDED.connected_by,
                    connected_at = EXCLUDED.connected_at, provider_id = EXCLUDED.provider_id, online_mode = EXCLUDED.online_mode,
                    online_limit = EXCLUDED.online_limit, online_group_by_role = EXCLUDED.online_group_by_role, feed_limit = EXCLUDED.feed_limit,
                    events_enabled = EXCLUDED.events_enabled, updated_by = EXCLUDED.updated_by, updated_at = NOW()
                """)) {
            s.setLong(1, next.siteId());
            if (next.discordGuildId() == null) s.setNull(2, Types.BIGINT);
            else s.setLong(2, next.discordGuildId());
            if (next.connectedBy() == null) s.setNull(3, Types.BIGINT);
            else s.setLong(3, next.connectedBy());
            s.setTimestamp(4, next.connectedAt() == null ? null : Timestamp.from(next.connectedAt()));
            s.setString(5, next.providerId());
            s.setString(6, next.onlineMode().key());
            s.setInt(7, next.onlineLimit());
            s.setBoolean(8, next.groupByRole());
            s.setInt(9, next.feedLimit());
            s.setBoolean(10, next.eventsEnabled());
            s.setBoolean(11, next.copiedFromBot());
            s.setLong(12, actorId);
            s.executeUpdate();
        }
    }

    private static RuntimeException fail(String action, SQLException e) {
        log.error("Failed to {}", action, e);
        return new RuntimeException("Failed to " + action, e);
    }
}
