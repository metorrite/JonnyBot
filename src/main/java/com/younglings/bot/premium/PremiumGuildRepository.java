package com.younglings.bot.premium;

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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The servers on a plan above the free one, with the plan, its end and the owner's note, and the log of every grant. {@link #all()} and
 * {@link #isPremium} only count grants that are still in force, so a plan that has run out stops working at once, before the daily sweep removes it.
 */
@BService
public class PremiumGuildRepository {
    private static final Logger log = LoggerFactory.getLogger(PremiumGuildRepository.class);

    public record PremiumGuild(long guildId, String label) {}

    /** One grant: {@code expiresAt} null means no end. */
    public record Grant(long guildId, String label, String tier, Instant expiresAt, String note, String source, Long addedBy, Instant addedAt) {
        public boolean inForce(Instant now) {
            return PremiumFeatures.inForce(expiresAt, now);
        }
    }

    /** One line of the history of a server's plan. */
    public record LogEntry(long id, long guildId, String action, String tier, Instant expiresAt, Long byUser, String note, Instant at) {}

    private static final String GRANT_COLUMNS = "guild_id, label, tier, expires_at, note, source, added_by, added_at";
    private static final String IN_FORCE = "(expires_at IS NULL OR expires_at > now())";

    private final ConnectionSupplier connectionSupplier;

    public PremiumGuildRepository(ConnectionSupplier connectionSupplier, PremiumDatabaseInitializer initializer) {
        this.connectionSupplier = connectionSupplier;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Grant mapGrant(ResultSet rs) throws SQLException {
        return new Grant(rs.getLong("guild_id"), rs.getString("label"), rs.getString("tier"), instant(rs, "expires_at"), rs.getString("note"),
                rs.getString("source"), (Long) rs.getObject("added_by"), instant(rs, "added_at"));
    }

    /** Servers whose grant is still in force. */
    public List<PremiumGuild> all() {
        List<PremiumGuild> guilds = new ArrayList<>();
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT guild_id, label FROM younglings.premium_guild WHERE " + IN_FORCE + " ORDER BY added_at, guild_id");
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) guilds.add(new PremiumGuild(rs.getLong("guild_id"), rs.getString("label")));
            return guilds;
        } catch (SQLException e) {
            log.error("Failed to read the premium guilds", e);
            throw new RuntimeException("Failed to read the premium guilds", e);
        }
    }

    /** Every grant, including ones that have run out but not yet been swept, for the owner's list. */
    public List<Grant> grants() {
        List<Grant> grants = new ArrayList<>();
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT " + GRANT_COLUMNS + " FROM younglings.premium_guild ORDER BY added_at, guild_id");
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) grants.add(mapGrant(rs));
            return grants;
        } catch (SQLException e) {
            log.error("Failed to read the premium grants", e);
            throw new RuntimeException("Failed to read the premium grants", e);
        }
    }

    /** The server's grant if it has one that is still in force, else null. */
    public Grant activeGrant(long guildId) {
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT " + GRANT_COLUMNS + " FROM younglings.premium_guild WHERE guild_id = ? AND " + IN_FORCE)) {
            statement.setLong(1, guildId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? mapGrant(rs) : null;
            }
        } catch (SQLException e) {
            log.error("Failed to read the premium grant of guild {}", guildId, e);
            return null;
        }
    }

    /** The server's grant, in force or not, else null. */
    public Grant grant(long guildId) {
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT " + GRANT_COLUMNS + " FROM younglings.premium_guild WHERE guild_id = ?")) {
            statement.setLong(1, guildId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? mapGrant(rs) : null;
            }
        } catch (SQLException e) {
            log.error("Failed to read the premium grant of guild {}", guildId, e);
            throw new RuntimeException("Failed to read the premium grant", e);
        }
    }

    public boolean isPremium(long guildId) {
        return activeGrant(guildId) != null;
    }

    /** Adds the server on the premium tier with no end, or changes its label if it is already there. */
    public void upsert(long guildId, String label, long addedBy) {
        upsert(guildId, label, PremiumFeatures.TIER_PREMIUM, null, null, "manual", addedBy);
    }

    /** Adds the server, or replaces its plan, end, note and source. The label is the owner's reminder of whose server it is. */
    public void upsert(long guildId, String label, String tier, Instant expiresAt, String note, String source, long addedBy) {
        upsertBy(guildId, label, tier, expiresAt, note, source, addedBy);
    }

    /** The same, where {@code addedBy} is null for a grant nobody typed in (a payment). */
    public void upsertBy(long guildId, String label, String tier, Instant expiresAt, String note, String source, Long addedBy) {
        String sql = """
                INSERT INTO younglings.premium_guild (guild_id, label, added_by, tier, expires_at, note, source) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (guild_id) DO UPDATE SET label = EXCLUDED.label, tier = EXCLUDED.tier, expires_at = EXCLUDED.expires_at, note = EXCLUDED.note, source = EXCLUDED.source
                """;
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, guildId);
            statement.setString(2, label);
            if (addedBy == null) statement.setNull(3, Types.BIGINT);
            else statement.setLong(3, addedBy);
            statement.setString(4, tier);
            if (expiresAt == null) statement.setNull(5, Types.TIMESTAMP_WITH_TIMEZONE);
            else statement.setTimestamp(5, Timestamp.from(expiresAt));
            if (note == null) statement.setNull(6, Types.VARCHAR);
            else statement.setString(6, note);
            statement.setString(7, source);
            statement.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to save premium guild {}", guildId, e);
            throw new RuntimeException("Failed to save the premium guild", e);
        }
    }

    public boolean delete(long guildId) {
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM younglings.premium_guild WHERE guild_id = ?")) {
            statement.setLong(1, guildId);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to delete premium guild {}", guildId, e);
            throw new RuntimeException("Failed to delete the premium guild", e);
        }
    }

    /** Grants that ended before {@code now}. */
    public List<Grant> expired(Instant now) {
        List<Grant> grants = new ArrayList<>();
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT " + GRANT_COLUMNS + " FROM younglings.premium_guild WHERE expires_at IS NOT NULL AND expires_at <= ?")) {
            statement.setTimestamp(1, Timestamp.from(now));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) grants.add(mapGrant(rs));
            }
            return grants;
        } catch (SQLException e) {
            log.error("Failed to read the expired premium grants", e);
            throw new RuntimeException("Failed to read the expired premium grants", e);
        }
    }

    public void log(long guildId, String action, String tier, Instant expiresAt, Long byUser, String note) {
        String sql = "INSERT INTO younglings.premium_grant_log (guild_id, action, tier, expires_at, by_user, note) VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, guildId);
            statement.setString(2, action);
            if (tier == null) statement.setNull(3, Types.VARCHAR);
            else statement.setString(3, tier);
            if (expiresAt == null) statement.setNull(4, Types.TIMESTAMP_WITH_TIMEZONE);
            else statement.setTimestamp(4, Timestamp.from(expiresAt));
            if (byUser == null) statement.setNull(5, Types.BIGINT);
            else statement.setLong(5, byUser);
            if (note == null) statement.setNull(6, Types.VARCHAR);
            else statement.setString(6, note);
            statement.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to log a premium {} for guild {}", action, guildId, e);
            throw new RuntimeException("Failed to log the premium change", e);
        }
    }

    /** Newest first. */
    public List<LogEntry> history(long guildId, int limit) {
        List<LogEntry> entries = new ArrayList<>();
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT id, guild_id, action, tier, expires_at, by_user, note, at FROM younglings.premium_grant_log WHERE guild_id = ? ORDER BY at DESC, id DESC LIMIT ?")) {
            statement.setLong(1, guildId);
            statement.setInt(2, limit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    entries.add(new LogEntry(rs.getLong("id"), rs.getLong("guild_id"), rs.getString("action"), rs.getString("tier"), instant(rs, "expires_at"),
                            (Long) rs.getObject("by_user"), rs.getString("note"), instant(rs, "at")));
                }
            }
            return entries;
        } catch (SQLException e) {
            log.error("Failed to read the premium history of guild {}", guildId, e);
            throw new RuntimeException("Failed to read the premium history", e);
        }
    }
}
