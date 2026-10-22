package com.younglings.bot.clansite;

import com.younglings.bot.internal.SiteInvalidator;
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
import java.util.Optional;

/**
 * Storage for website builder v2 layouts (T-301, docs/BUILDER-V2.md in the runeclans repo): one row per server, with the draft the editor
 * saves and the copy the public site shows. Each draft save bumps {@code draft_rev}; a save made from an older revision is refused, so two
 * open editors can't silently overwrite each other. Publishing copies the draft as it is.
 */
@BService
public class ClanSiteLayoutRepository {
    private static final Logger log = LoggerFactory.getLogger(ClanSiteLayoutRepository.class);

    /** A server's layouts. {@code published} is null until the first publish. */
    public record Layouts(long guildId, String draft, int draftRev, String published, int publishedRev, Long updatedBy, Instant updatedAt, Instant publishedAt) {}

    private final ConnectionSupplier connectionSupplier;

    /** The table is made by {@link ClanSiteRepository} (with the other clan website tables), which is why it is asked for here. */
    public ClanSiteLayoutRepository(ConnectionSupplier connectionSupplier, ClanSiteRepository tables) {
        this.connectionSupplier = connectionSupplier;
    }

    public Optional<Layouts> byGuild(long guildId) {
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT guild_id, draft, draft_rev, published, published_rev, updated_by, updated_at, published_at FROM younglings.clan_site_layout WHERE guild_id = ?")) {
            statement.setLong(1, guildId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                long by = rs.getLong("updated_by");
                Long updatedBy = rs.wasNull() ? null : by;
                return Optional.of(new Layouts(rs.getLong("guild_id"), rs.getString("draft"), rs.getInt("draft_rev"), rs.getString("published"),
                        rs.getInt("published_rev"), updatedBy, instant(rs, "updated_at"), instant(rs, "published_at")));
            }
        } catch (SQLException e) {
            log.error("Failed to read the clan website layout of guild {}", guildId, e);
            throw new RuntimeException("Failed to read the clan website layout", e);
        }
    }

    /**
     * Saves the draft made from revision {@code baseRev} (0 when there was none yet). Returns the new revision, or -1 when someone saved a
     * newer one in between.
     */
    public int saveDraft(long guildId, String draft, int baseRev, long actorId) {
        String insert = "INSERT INTO younglings.clan_site_layout (guild_id, draft, draft_rev, updated_by, updated_at) VALUES (?, ?, 1, ?, ?) ON CONFLICT (guild_id) DO NOTHING";
        String update = "UPDATE younglings.clan_site_layout SET draft = ?, draft_rev = draft_rev + 1, updated_by = ?, updated_at = ? WHERE guild_id = ? AND draft_rev = ? RETURNING draft_rev";
        Timestamp now = Timestamp.from(Instant.now());
        try (Connection connection = connectionSupplier.getConnection()) {
            if (baseRev == 0) {
                try (PreparedStatement statement = connection.prepareStatement(insert)) {
                    statement.setLong(1, guildId);
                    statement.setString(2, draft);
                    statement.setLong(3, actorId);
                    statement.setTimestamp(4, now);
                    return statement.executeUpdate() == 1 ? 1 : -1;
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(update)) {
                statement.setString(1, draft);
                statement.setLong(2, actorId);
                statement.setTimestamp(3, now);
                statement.setLong(4, guildId);
                statement.setInt(5, baseRev);
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : -1;
                }
            }
        } catch (SQLException e) {
            log.error("Failed to save the clan website layout of guild {}", guildId, e);
            throw new RuntimeException("Failed to save the clan website layout", e);
        }
    }

    /** Makes the current draft the published layout. False when the server has no draft. */
    public boolean publishDraft(long guildId, long actorId) {
        String sql = "UPDATE younglings.clan_site_layout SET published = draft, published_rev = draft_rev, published_at = ?, updated_by = ? WHERE guild_id = ?";
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(Instant.now()));
            statement.setLong(2, actorId);
            statement.setLong(3, guildId);
            boolean done = statement.executeUpdate() == 1;
            if (done) SiteInvalidator.signal(SiteInvalidator.Area.HUB);
            return done;
        } catch (SQLException e) {
            log.error("Failed to publish the clan website layout of guild {}", guildId, e);
            throw new RuntimeException("Failed to publish the clan website layout", e);
        }
    }

    /** Deletes a server's layouts (with its site, when the clan deletes its website). */
    public void delete(long guildId) {
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM younglings.clan_site_layout WHERE guild_id = ?")) {
            statement.setLong(1, guildId);
            statement.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to delete the clan website layout of guild {}", guildId, e);
            throw new RuntimeException("Failed to delete the clan website layout", e);
        }
        SiteInvalidator.signal(SiteInvalidator.Area.HUB);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
