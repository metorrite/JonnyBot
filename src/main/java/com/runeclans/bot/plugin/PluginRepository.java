package com.runeclans.bot.plugin;

import com.runeclans.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Plugin tokens, each player's consent, and the events the plugin has reported. None of it belongs to a single server: it belongs to the player. */
@BService
public class PluginRepository {
    private static final Logger log = LoggerFactory.getLogger(PluginRepository.class);

    /** A token's record (never the token itself). */
    public record Token(long id, long discordUserId, String label, String preview, Instant createdAt, Instant lastUsedAt, Instant revokedAt) {
        public boolean active() {
            return revokedAt == null;
        }
    }

    /** Aggregate counts for a group of players: how many have a token, how many reported recently, and how many allow each category. */
    public record Coverage(int withToken, int reportingRecently, Map<PluginCategory, Integer> consent) {}

    private final ConnectionSupplier connectionSupplier;

    public PluginRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "plugin", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.plugin_token (
                    id BIGSERIAL PRIMARY KEY,
                    discord_user_id BIGINT NOT NULL,
                    token_hash TEXT NOT NULL UNIQUE,
                    preview TEXT NOT NULL,
                    label TEXT NOT NULL DEFAULT 'Plugin',
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    last_used_at TIMESTAMPTZ NULL,
                    revoked_at TIMESTAMPTZ NULL
                );
                """,
                "CREATE INDEX IF NOT EXISTS plugin_token_user ON younglings.plugin_token (discord_user_id);",
                """
                CREATE TABLE IF NOT EXISTS younglings.plugin_consent (
                    discord_user_id BIGINT NOT NULL,
                    category TEXT NOT NULL,
                    PRIMARY KEY (discord_user_id, category)
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.plugin_event (
                    id BIGSERIAL PRIMARY KEY,
                    discord_user_id BIGINT NOT NULL,
                    rsn TEXT NOT NULL,
                    event_key TEXT NOT NULL,
                    type TEXT NOT NULL,
                    category TEXT NOT NULL,
                    occurred_at TIMESTAMPTZ NOT NULL,
                    received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    source_kind TEXT NULL,
                    source_name TEXT NULL,
                    source_id BIGINT NULL,
                    data JSONB NOT NULL,
                    client TEXT NULL,
                    UNIQUE (discord_user_id, event_key)
                );
                """,
                "CREATE INDEX IF NOT EXISTS plugin_event_rsn_time ON younglings.plugin_event (LOWER(rsn), occurred_at DESC);",
                "CREATE INDEX IF NOT EXISTS plugin_event_received ON younglings.plugin_event (received_at);"));
    }

    // ---------- tokens ----------

    public long createToken(long userId, String hash, String preview, String label) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("INSERT INTO younglings.plugin_token (discord_user_id, token_hash, preview, label) VALUES (?, ?, ?, ?) RETURNING id")) {
            s.setLong(1, userId);
            s.setString(2, hash);
            s.setString(3, preview);
            s.setString(4, label);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create the plugin token", e);
        }
    }

    public List<Token> tokens(long userId) {
        List<Token> list = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT * FROM younglings.plugin_token WHERE discord_user_id = ? ORDER BY id DESC")) {
            s.setLong(1, userId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the plugin tokens", e);
        }
    }

    public int activeTokenCount(long userId) {
        return (int) tokens(userId).stream().filter(Token::active).count();
    }

    /** The active token with this hash, or empty if there is none or it was revoked. */
    public Optional<Token> activeByHash(String hash) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT * FROM younglings.plugin_token WHERE token_hash = ? AND revoked_at IS NULL")) {
            s.setString(1, hash);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to look up the plugin token", e);
        }
    }

    public void touch(long tokenId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("UPDATE younglings.plugin_token SET last_used_at = NOW() WHERE id = ?")) {
            s.setLong(1, tokenId);
            s.executeUpdate();
        } catch (SQLException e) {
            log.warn("Couldn't record that plugin token {} was used", tokenId, e);
        }
    }

    /** Revokes one of the user's own tokens. Returns false if it isn't theirs or was already revoked. */
    public boolean revoke(long userId, long tokenId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("UPDATE younglings.plugin_token SET revoked_at = NOW() WHERE id = ? AND discord_user_id = ? AND revoked_at IS NULL")) {
            s.setLong(1, tokenId);
            s.setLong(2, userId);
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to revoke the plugin token", e);
        }
    }

    // ---------- consent ----------

    public Set<PluginCategory> consent(long userId) {
        Set<PluginCategory> set = EnumSet.noneOf(PluginCategory.class);
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT category FROM younglings.plugin_consent WHERE discord_user_id = ?")) {
            s.setLong(1, userId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    try {
                        set.add(PluginCategory.valueOf(rs.getString(1)));
                    } catch (IllegalArgumentException ignored) {
                        // a category this version doesn't know is ignored
                    }
                }
            }
            return set;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the plugin consent", e);
        }
    }

    /** Replaces the user's consent with exactly these categories. */
    public void setConsent(long userId, Set<PluginCategory> categories) {
        try (Connection c = connectionSupplier.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings.plugin_consent WHERE discord_user_id = ?")) {
                    s.setLong(1, userId);
                    s.executeUpdate();
                }
                try (PreparedStatement s = c.prepareStatement("INSERT INTO younglings.plugin_consent (discord_user_id, category) VALUES (?, ?)")) {
                    for (PluginCategory category : categories) {
                        s.setLong(1, userId);
                        s.setString(2, category.name());
                        s.addBatch();
                    }
                    s.executeBatch();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the plugin consent", e);
        }
    }

    // ---------- events ----------

    /** Stores an event. Returns false when this player already sent an event with the same key (a retry), which is not an error. */
    public boolean insertEvent(long userId, String rsn, PluginPayload.Event e, String client) {
        String sql = """
                INSERT INTO younglings.plugin_event (discord_user_id, rsn, event_key, type, category, occurred_at, source_kind, source_name, source_id, data, client)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (discord_user_id, event_key) DO NOTHING
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, userId);
            s.setString(2, rsn);
            s.setString(3, e.key());
            s.setString(4, e.type().name());
            s.setString(5, e.type().category().name());
            s.setTimestamp(6, Timestamp.from(e.at()));
            s.setString(7, e.sourceKind());
            s.setString(8, e.sourceName());
            if (e.sourceId() == null) s.setNull(9, java.sql.Types.BIGINT); else s.setLong(9, e.sourceId());
            s.setString(10, e.dataJson());
            s.setString(11, client);
            return s.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new RuntimeException("Failed to store the plugin event", ex);
        }
    }

    public int eventCount(long userId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT COUNT(*) FROM younglings.plugin_event WHERE discord_user_id = ?")) {
            s.setLong(1, userId);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count the plugin events", e);
        }
    }

    /** Everything the plugin ever reported for this player, their tokens and their choices: the player's "delete my data". Returns how many events were removed. */
    public int deleteAllFor(long userId) {
        try (Connection c = connectionSupplier.getConnection()) {
            c.setAutoCommit(false);
            try {
                int events;
                try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings.plugin_event WHERE discord_user_id = ?")) {
                    s.setLong(1, userId);
                    events = s.executeUpdate();
                }
                for (String table : new String[]{"plugin_token", "plugin_consent"}) {
                    try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings.%s WHERE discord_user_id = ?".formatted(table))) {
                        s.setLong(1, userId);
                        s.executeUpdate();
                    }
                }
                c.commit();
                return events;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete the player's plugin data", e);
        }
    }

    /** Deletes events received before {@code cutoff}. */
    public int purgeEventsBefore(Instant cutoff) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.plugin_event WHERE received_at < ?")) {
            s.setTimestamp(1, Timestamp.from(cutoff));
            return s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to purge old plugin events", e);
        }
    }

    // ---------- coverage ----------

    /** Counts only: how many of these players have an active token, how many used one since {@code recentSince}, and how many allow each category. */
    public Coverage coverage(Collection<Long> userIds, Instant recentSince) {
        Map<PluginCategory, Integer> consent = new EnumMap<>(PluginCategory.class);
        for (PluginCategory category : PluginCategory.values()) consent.put(category, 0);
        if (userIds.isEmpty()) return new Coverage(0, 0, consent);
        Long[] ids = userIds.toArray(new Long[0]);
        try (Connection c = connectionSupplier.getConnection()) {
            java.sql.Array array = c.createArrayOf("bigint", ids);
            int withToken;
            int recent;
            try (PreparedStatement s = c.prepareStatement("""
                    SELECT COUNT(DISTINCT discord_user_id) FILTER (WHERE revoked_at IS NULL),
                           COUNT(DISTINCT discord_user_id) FILTER (WHERE revoked_at IS NULL AND last_used_at >= ?)
                    FROM younglings.plugin_token WHERE discord_user_id = ANY (?)""")) {
                s.setTimestamp(1, Timestamp.from(recentSince));
                s.setArray(2, array);
                try (ResultSet rs = s.executeQuery()) {
                    rs.next();
                    withToken = rs.getInt(1);
                    recent = rs.getInt(2);
                }
            }
            try (PreparedStatement s = c.prepareStatement("SELECT category, COUNT(*) FROM younglings.plugin_consent WHERE discord_user_id = ANY (?) GROUP BY category")) {
                s.setArray(1, array);
                try (ResultSet rs = s.executeQuery()) {
                    while (rs.next()) {
                        try {
                            consent.put(PluginCategory.valueOf(rs.getString(1)), rs.getInt(2));
                        } catch (IllegalArgumentException ignored) {
                            // unknown category
                        }
                    }
                }
            }
            return new Coverage(withToken, recent, consent);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count plugin coverage", e);
        }
    }

    private static Token map(ResultSet rs) throws SQLException {
        return new Token(rs.getLong("id"), rs.getLong("discord_user_id"), rs.getString("label"), rs.getString("preview"), rs.getTimestamp("created_at").toInstant(),
                instant(rs, "last_used_at"), instant(rs, "revoked_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
