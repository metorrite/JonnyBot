package com.younglings.bot.clansite;

import com.younglings.bot.database.SchemaBootstrapper;
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
import java.util.ArrayList;
import java.util.List;
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

    /** One published copy kept for the version history (T-363). {@code layout} is only filled in by {@link #version}. */
    public record Version(long id, long guildId, int rev, String label, Long publishedBy, Instant publishedAt, String layout) {}

    /** Published copies kept per server; the plan decides how many of them can be seen and restored (docs/BUILDER-V3.md 6.5, 10.2). */
    public static final int KEPT_VERSIONS = 30;
    public static final int MAX_VERSION_LABEL = 60;

    /**
     * The layout table is made by {@link ClanSiteRepository} (with the other clan website tables), which is why it is asked for here. The
     * version table is made here; its rows go with the server's layout row (ON DELETE CASCADE), so every path that deletes a site's layout
     * (deleting the website, the cleanup of switched-off sites) deletes its versions too.
     */
    public ClanSiteLayoutRepository(ConnectionSupplier connectionSupplier, ClanSiteRepository tables) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "clan website versions", List.of(
                """
                CREATE TABLE IF NOT EXISTS younglings.clan_site_layout_version (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL REFERENCES younglings.clan_site_layout (guild_id) ON DELETE CASCADE,
                    layout TEXT NOT NULL,
                    rev INT NOT NULL,
                    label TEXT NULL,
                    published_by BIGINT NULL,
                    published_at TIMESTAMPTZ NOT NULL
                );
                """,
                "CREATE INDEX IF NOT EXISTS clan_site_layout_version_guild ON younglings.clan_site_layout_version (guild_id, id DESC);"
        ));
    }

    /** A server's layouts without their documents: revisions, times and whether a published copy exists (the bot owner's site list, T-423). */
    public record Summary(long guildId, int draftRev, int publishedRev, boolean hasPublished, Instant updatedAt, Instant publishedAt) {}

    /** Every server's layout summary, read without the (large) documents. */
    public List<Summary> summaries() {
        List<Summary> list = new ArrayList<>();
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT guild_id, draft_rev, published_rev, published IS NOT NULL AS has_published, updated_at, published_at FROM younglings.clan_site_layout ORDER BY guild_id");
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                list.add(new Summary(rs.getLong("guild_id"), rs.getInt("draft_rev"), rs.getInt("published_rev"), rs.getBoolean("has_published"),
                        instant(rs, "updated_at"), instant(rs, "published_at")));
            }
            return list;
        } catch (SQLException e) {
            log.error("Failed to read the clan website layouts", e);
            throw new RuntimeException("Failed to read the clan website layouts", e);
        }
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

    /** Makes the current draft the published layout, without a label. False when the server has no draft. */
    public boolean publishDraft(long guildId, long actorId) {
        return publishDraft(guildId, actorId, null);
    }

    /**
     * Makes the current draft the published layout and records it as a version (with an optional label), dropping all but the newest
     * {@link #KEPT_VERSIONS}; all in one transaction. False when the server has no draft.
     */
    public boolean publishDraft(long guildId, long actorId, String label) {
        String publish = "UPDATE younglings.clan_site_layout SET published = draft, published_rev = draft_rev, published_at = ?, updated_by = ? WHERE guild_id = ?";
        String record = "INSERT INTO younglings.clan_site_layout_version (guild_id, layout, rev, label, published_by, published_at) "
                + "SELECT guild_id, draft, draft_rev, ?, ?, ? FROM younglings.clan_site_layout WHERE guild_id = ?";
        String prune = "DELETE FROM younglings.clan_site_layout_version WHERE guild_id = ? AND id NOT IN "
                + "(SELECT id FROM younglings.clan_site_layout_version WHERE guild_id = ? ORDER BY id DESC LIMIT ?)";
        Timestamp now = Timestamp.from(Instant.now());
        try (Connection connection = connectionSupplier.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement p = connection.prepareStatement(publish);
                 PreparedStatement r = connection.prepareStatement(record);
                 PreparedStatement d = connection.prepareStatement(prune)) {
                p.setTimestamp(1, now);
                p.setLong(2, actorId);
                p.setLong(3, guildId);
                if (p.executeUpdate() != 1) {
                    connection.rollback();
                    return false;
                }
                String clean = label == null || label.isBlank() ? null : label.strip();
                if (clean != null && clean.length() > MAX_VERSION_LABEL) clean = clean.substring(0, MAX_VERSION_LABEL);
                r.setString(1, clean);
                r.setLong(2, actorId);
                r.setTimestamp(3, now);
                r.setLong(4, guildId);
                r.executeUpdate();
                d.setLong(1, guildId);
                d.setLong(2, guildId);
                d.setInt(3, KEPT_VERSIONS);
                d.executeUpdate();
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
            SiteInvalidator.signal(guildId, SiteInvalidator.Area.HUB);
            return true;
        } catch (SQLException e) {
            log.error("Failed to publish the clan website layout of guild {}", guildId, e);
            throw new RuntimeException("Failed to publish the clan website layout", e);
        }
    }

    /** The newest {@code limit} published versions of a server's site, newest first, without their layouts. */
    public List<Version> versions(long guildId, int limit) {
        String sql = "SELECT id, guild_id, rev, label, published_by, published_at FROM younglings.clan_site_layout_version WHERE guild_id = ? ORDER BY id DESC LIMIT ?";
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, guildId);
            statement.setInt(2, Math.max(0, limit));
            List<Version> out = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) out.add(version(rs, null));
            }
            return out;
        } catch (SQLException e) {
            log.error("Failed to read the clan website versions of guild {}", guildId, e);
            throw new RuntimeException("Failed to read the clan website versions", e);
        }
    }

    /** One version with its layout, if it belongs to this server and is among its newest {@code limit}. */
    public Optional<Version> version(long guildId, long versionId, int limit) {
        String sql = "SELECT id, guild_id, rev, label, published_by, published_at, layout FROM younglings.clan_site_layout_version WHERE id = ? AND guild_id = ? "
                + "AND id IN (SELECT id FROM younglings.clan_site_layout_version WHERE guild_id = ? ORDER BY id DESC LIMIT ?)";
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, versionId);
            statement.setLong(2, guildId);
            statement.setLong(3, guildId);
            statement.setInt(4, Math.max(0, limit));
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(version(rs, rs.getString("layout"))) : Optional.empty();
            }
        } catch (SQLException e) {
            log.error("Failed to read clan website version {} of guild {}", versionId, guildId, e);
            throw new RuntimeException("Failed to read the clan website version", e);
        }
    }

    /**
     * Copies a version into the draft as a new revision; the public site doesn't change until the next publish. With {@code baseRev} above 0
     * it is refused (-1) when someone saved a newer draft in between, as for {@link #saveDraft}. Returns the new draft revision, or -1.
     */
    public int restoreVersion(long guildId, long versionId, int baseRev, long actorId) {
        String sql = "UPDATE younglings.clan_site_layout l SET draft = v.layout, draft_rev = l.draft_rev + 1, updated_by = ?, updated_at = ? "
                + "FROM younglings.clan_site_layout_version v WHERE v.id = ? AND v.guild_id = l.guild_id AND l.guild_id = ? AND (? = 0 OR l.draft_rev = ?) "
                + "RETURNING l.draft_rev";
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, actorId);
            statement.setTimestamp(2, Timestamp.from(Instant.now()));
            statement.setLong(3, versionId);
            statement.setLong(4, guildId);
            statement.setInt(5, Math.max(0, baseRev));
            statement.setInt(6, Math.max(0, baseRev));
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        } catch (SQLException e) {
            log.error("Failed to restore clan website version {} of guild {}", versionId, guildId, e);
            throw new RuntimeException("Failed to restore the clan website version", e);
        }
    }

    private static Version version(ResultSet rs, String layout) throws SQLException {
        long by = rs.getLong("published_by");
        Long publishedBy = rs.wasNull() ? null : by;
        return new Version(rs.getLong("id"), rs.getLong("guild_id"), rs.getInt("rev"), rs.getString("label"), publishedBy, instant(rs, "published_at"), layout);
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
        SiteInvalidator.signal(guildId, SiteInvalidator.Area.HUB);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
