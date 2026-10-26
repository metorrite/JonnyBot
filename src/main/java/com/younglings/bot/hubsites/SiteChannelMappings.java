package com.younglings.bot.hubsites;

import com.younglings.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A site's Discord channel picks under {@code SITES_BACKEND=hub} (T-505): the hub owns the site, but channel ids are Discord's and stay
 * with the bot, as mappings keyed by the hub's site id. Off, or for a server with no hub site, every method answers "not mine" (empty /
 * false) and the caller keeps using its own table, exactly as before.
 * <ul>
 *   <li>{@link Kind#NEWS}: the home site's news channels ({@code site_news_channel})</li>
 *   <li>{@link Kind#FEED}: the clan site's channel feeds (Connections, {@code site_connection_channel})</li>
 *   <li>{@link Kind#POLL}: the channel member-made polls go to ({@code site_setting.poll_channel_id})</li>
 * </ul>
 * The bot's side of the move step: the first time the hub names a server's site, that server's picks are copied in from the bot's own
 * tables (once per site, in one transaction), so the switch changes nothing a member sees. While the hub can't be asked, the site the
 * hub last named for the server is used.
 */
@BService
public class SiteChannelMappings {
    private static final Logger log = LoggerFactory.getLogger(SiteChannelMappings.class);

    public enum Kind {
        NEWS("news"), FEED("feed"), POLL("poll");

        private final String key;

        Kind(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }
    }

    /** One mapped channel, in the admin's order, with an optional display name. */
    public record Mapped(long channelId, String label, int position) {}

    private final ConnectionSupplier connectionSupplier;
    private final HubSites hubSites;
    /** Sites already recorded this run, by site id: the server each was recorded for, so a lookup costs no write. */
    private final Map<String, Long> recorded = new ConcurrentHashMap<>();

    public SiteChannelMappings(ConnectionSupplier connectionSupplier, HubSites hubSites) {
        this.connectionSupplier = connectionSupplier;
        this.hubSites = hubSites;
        SchemaBootstrapper.run(connectionSupplier, log, "hub site channel mappings", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                // which hub site a server's picks belong to, and when they were copied in from the bot's own tables
                """
                CREATE TABLE IF NOT EXISTS younglings.hub_site_mapping (
                    site_id TEXT PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    seeded_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                "CREATE INDEX IF NOT EXISTS hub_site_mapping_guild ON younglings.hub_site_mapping (guild_id, seen_at DESC);",
                """
                CREATE TABLE IF NOT EXISTS younglings.hub_site_channel (
                    site_id TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    channel_id BIGINT NOT NULL,
                    label TEXT NULL,
                    position INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (site_id, kind, channel_id)
                );
                """));
    }

    /**
     * The hub site this server's picks are kept under: the hub's answer (copying the server's picks in the first time), or while the hub
     * can't answer, the site it last named. Empty when off, or when the server has no hub site.
     */
    public Optional<String> siteFor(long guildId) {
        HubSites.Lookup lookup = hubSites.site(guildId);
        return switch (lookup.status()) {
            case OFF, NONE -> Optional.empty();
            case FOUND -> {
                remember(lookup.site().siteId(), guildId);
                yield Optional.of(lookup.site().siteId());
            }
            case UNKNOWN -> lastKnown(guildId);
        };
    }

    /** The server's mapped channels of {@code kind}, in order; empty (not an empty list) when the caller should use its own table. */
    public Optional<List<Mapped>> channels(long guildId, Kind kind) {
        Optional<String> site = siteFor(guildId);
        return site.map(id -> read(id, kind));
    }

    /** Replaces the server's mapped channels of {@code kind}. False (nothing written) when the caller should write its own table. */
    public boolean replace(long guildId, Kind kind, List<Mapped> channels) {
        Optional<String> site = siteFor(guildId);
        if (site.isEmpty()) return false;
        try (Connection c = connectionSupplier.getConnection()) {
            c.setAutoCommit(false);
            try {
                replace(c, site.get(), kind, channels);
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw fail("save a site's mapped channels", e);
        }
        return true;
    }

    /** The one mapped channel of {@code kind} (the poll channel): empty when the caller should use its own table, else the channel or null. */
    public Optional<Optional<Long>> single(long guildId, Kind kind) {
        return channels(guildId, kind).map(list -> list.isEmpty() ? Optional.empty() : Optional.of(list.get(0).channelId()));
    }

    /** Sets the one mapped channel of {@code kind} ({@code null} clears it). False when the caller should write its own table. */
    public boolean setSingle(long guildId, Kind kind, Long channelId) {
        return replace(guildId, kind, channelId == null ? List.of() : List.of(new Mapped(channelId, null, 0)));
    }

    private List<Mapped> read(String siteId, Kind kind) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement(
                     "SELECT channel_id, label, position FROM younglings.hub_site_channel WHERE site_id = ? AND kind = ? ORDER BY position, channel_id")) {
            s.setString(1, siteId);
            s.setString(2, kind.key());
            List<Mapped> out = new ArrayList<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) out.add(new Mapped(rs.getLong(1), rs.getString(2), rs.getInt(3)));
            }
            return List.copyOf(out);
        } catch (SQLException e) {
            throw fail("read a site's mapped channels", e);
        }
    }

    private static void replace(Connection c, String siteId, Kind kind, List<Mapped> channels) throws SQLException {
        try (PreparedStatement delete = c.prepareStatement("DELETE FROM younglings.hub_site_channel WHERE site_id = ? AND kind = ?")) {
            delete.setString(1, siteId);
            delete.setString(2, kind.key());
            delete.executeUpdate();
        }
        try (PreparedStatement insert = c.prepareStatement(
                "INSERT INTO younglings.hub_site_channel (site_id, kind, channel_id, label, position) VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING")) {
            int position = 0;
            for (Mapped channel : channels) {
                insert.setString(1, siteId);
                insert.setString(2, kind.key());
                insert.setLong(3, channel.channelId());
                insert.setString(4, channel.label());
                insert.setInt(5, position++);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    /**
     * Records that the hub named {@code siteId} for this server. The first time, the server's picks are copied in from the bot's own tables
     * in the same transaction, so a site never shows half of them.
     */
    void remember(String siteId, long guildId) {
        Long before = recorded.get(siteId);
        if (before != null && before == guildId) return;
        try (Connection c = connectionSupplier.getConnection()) {
            c.setAutoCommit(false);
            try {
                int added;
                try (PreparedStatement s = c.prepareStatement(
                        "INSERT INTO younglings.hub_site_mapping (site_id, guild_id) VALUES (?, ?) ON CONFLICT (site_id) DO NOTHING")) {
                    s.setString(1, siteId);
                    s.setLong(2, guildId);
                    added = s.executeUpdate();
                }
                if (added > 0) {
                    seed(c, siteId, guildId);
                    log.info("Copied a server's website channel picks into the mappings of its hub site");
                } else {
                    try (PreparedStatement s = c.prepareStatement(
                            "UPDATE younglings.hub_site_mapping SET guild_id = ?, seen_at = NOW() WHERE site_id = ?")) {
                        s.setLong(1, guildId);
                        s.setString(2, siteId);
                        s.executeUpdate();
                    }
                }
                c.commit();
                recorded.put(siteId, guildId);
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw fail("record a server's hub site", e);
        }
    }

    /** Copies the server's own picks under the site; a table not created yet (a feature never used) copies nothing. */
    private static void seed(Connection c, String siteId, long guildId) throws SQLException {
        copy(c, siteId, guildId, Kind.NEWS, "SELECT channel_id, label, position FROM younglings.site_news_channel WHERE guild_id = ?", "younglings.site_news_channel");
        copy(c, siteId, guildId, Kind.FEED, "SELECT channel_id, label, position FROM younglings.site_connection_channel WHERE guild_id = ?",
                "younglings.site_connection_channel");
        copy(c, siteId, guildId, Kind.POLL, "SELECT poll_channel_id, CAST(NULL AS TEXT), 0 FROM younglings.site_setting WHERE guild_id = ? AND poll_channel_id IS NOT NULL",
                "younglings.site_setting");
    }

    private static void copy(Connection c, String siteId, long guildId, Kind kind, String select, String table) throws SQLException {
        try (PreparedStatement exists = c.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
            exists.setString(1, table);
            try (ResultSet rs = exists.executeQuery()) {
                if (!rs.next() || !rs.getBoolean(1)) return;
            }
        }
        try (PreparedStatement s = c.prepareStatement("INSERT INTO younglings.hub_site_channel (site_id, kind, channel_id, label, position) SELECT CAST(? AS TEXT), CAST(? AS TEXT), x.* FROM ("
                + select + ") x ON CONFLICT DO NOTHING")) {
            s.setString(1, siteId);
            s.setString(2, kind.key());
            s.setLong(3, guildId);
            s.executeUpdate();
        }
    }

    private Optional<String> lastKnown(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT site_id FROM younglings.hub_site_mapping WHERE guild_id = ? ORDER BY seen_at DESC LIMIT 1")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw fail("read a server's last hub site", e);
        }
    }

    private static RuntimeException fail(String action, SQLException e) {
        log.error("Failed to {}", action, e);
        return new RuntimeException("Failed to " + action, e);
    }
}
