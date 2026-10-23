package com.younglings.bot.clansite;

import com.younglings.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A clan website's role settings (T-368, docs/BUILDER-V3.md 8.2): which Discord roles make someone an editor, a member or staff on the site,
 * whether editors may publish, and who claimed the site. One row per server; a server without one has {@link SiteRoles.Settings#DEFAULT}.
 */
@BService
public class SiteRoleRepository {
    private static final Logger log = LoggerFactory.getLogger(SiteRoleRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public SiteRoleRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "clan website roles", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.clan_site_roles (
                    guild_id BIGINT PRIMARY KEY,
                    editor_role_ids BIGINT[] NOT NULL DEFAULT '{}',
                    member_role_ids BIGINT[] NOT NULL DEFAULT '{}',
                    staff_role_ids BIGINT[] NOT NULL DEFAULT '{}',
                    editors_may_publish BOOLEAN NOT NULL DEFAULT FALSE,
                    claimed_by BIGINT NULL,
                    updated_by BIGINT NULL,
                    updated_at TIMESTAMPTZ NULL
                );
                """
        ));
    }

    public SiteRoles.Settings get(long guildId) {
        String sql = "SELECT editor_role_ids, member_role_ids, staff_role_ids, editors_may_publish, claimed_by FROM younglings.clan_site_roles WHERE guild_id = ?";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) return SiteRoles.Settings.DEFAULT;
                long claimed = rs.getLong("claimed_by");
                Long claimedBy = rs.wasNull() ? null : claimed;
                return new SiteRoles.Settings(ids(rs.getArray("editor_role_ids")), ids(rs.getArray("member_role_ids")), ids(rs.getArray("staff_role_ids")),
                        rs.getBoolean("editors_may_publish"), claimedBy);
            }
        } catch (SQLException e) {
            log.error("Failed to read the clan website roles of guild {}", guildId, e);
            throw new RuntimeException("Failed to read the clan website roles", e);
        }
    }

    /** Stores the role lists and the switch; who claimed the site is left as it is. */
    public void save(long guildId, SiteRoles.Settings settings, long actorId) {
        String sql = """
                INSERT INTO younglings.clan_site_roles (guild_id, editor_role_ids, member_role_ids, staff_role_ids, editors_may_publish, updated_by, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (guild_id) DO UPDATE SET editor_role_ids = EXCLUDED.editor_role_ids, member_role_ids = EXCLUDED.member_role_ids,
                    staff_role_ids = EXCLUDED.staff_role_ids, editors_may_publish = EXCLUDED.editors_may_publish,
                    updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            s.setArray(2, c.createArrayOf("BIGINT", settings.editorRoleIds().toArray()));
            s.setArray(3, c.createArrayOf("BIGINT", settings.memberRoleIds().toArray()));
            s.setArray(4, c.createArrayOf("BIGINT", settings.staffRoleIds().toArray()));
            s.setBoolean(5, settings.editorsMayPublish());
            s.setLong(6, actorId);
            s.setTimestamp(7, Timestamp.from(Instant.now()));
            s.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to save the clan website roles of guild {}", guildId, e);
            throw new RuntimeException("Failed to save the clan website roles", e);
        }
    }

    /** Records who claimed the site, only if nobody has. True when this call claimed it. */
    public boolean claimIfUnclaimed(long guildId, long userId) {
        String sql = """
                INSERT INTO younglings.clan_site_roles (guild_id, claimed_by, updated_by, updated_at) VALUES (?, ?, ?, ?)
                ON CONFLICT (guild_id) DO UPDATE SET claimed_by = EXCLUDED.claimed_by WHERE younglings.clan_site_roles.claimed_by IS NULL
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            s.setLong(2, userId);
            s.setLong(3, userId);
            s.setTimestamp(4, Timestamp.from(Instant.now()));
            return s.executeUpdate() == 1;
        } catch (SQLException e) {
            log.error("Failed to claim the clan website of guild {}", guildId, e);
            throw new RuntimeException("Failed to claim the clan website", e);
        }
    }

    /** Hands the site to someone else ({@code userId}), or to nobody but the server owner ({@code null}). */
    public void setClaimedBy(long guildId, Long userId, long actorId) {
        String sql = """
                INSERT INTO younglings.clan_site_roles (guild_id, claimed_by, updated_by, updated_at) VALUES (?, ?, ?, ?)
                ON CONFLICT (guild_id) DO UPDATE SET claimed_by = EXCLUDED.claimed_by, updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            if (userId == null) s.setNull(2, Types.BIGINT);
            else s.setLong(2, userId);
            s.setLong(3, actorId);
            s.setTimestamp(4, Timestamp.from(Instant.now()));
            s.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to hand over the clan website of guild {}", guildId, e);
            throw new RuntimeException("Failed to hand over the clan website", e);
        }
    }

    /** Forgets a server's role settings (with its website, when the clan deletes it). */
    public void delete(long guildId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.clan_site_roles WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            s.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to delete the clan website roles of guild {}", guildId, e);
            throw new RuntimeException("Failed to delete the clan website roles", e);
        }
    }

    private static List<Long> ids(Array array) throws SQLException {
        List<Long> out = new ArrayList<>();
        if (array == null) return out;
        for (Object o : (Object[]) array.getArray()) if (o instanceof Number n) out.add(n.longValue());
        return out;
    }
}
