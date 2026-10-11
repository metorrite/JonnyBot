package com.younglings.bot.alliance;

import com.younglings.bot.alliance.AllianceRules.Status;
import com.younglings.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/** Alliances between servers, and which servers are in or invited to each. */
@BService
public class AllianceRepository {
    private static final Logger log = LoggerFactory.getLogger(AllianceRepository.class);

    public record Alliance(long id, String name, long createdByGuild, Instant createdAt) {}

    public record Membership(long allianceId, long guildId, Status status, Long invitedBy, Instant createdAt) {}

    private final ConnectionSupplier connectionSupplier;

    public AllianceRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "alliance", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.alliance (
                    id BIGSERIAL PRIMARY KEY,
                    name TEXT NOT NULL,
                    created_by_guild BIGINT NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                "CREATE UNIQUE INDEX IF NOT EXISTS alliance_name ON younglings.alliance (LOWER(name));",
                """
                CREATE TABLE IF NOT EXISTS younglings.alliance_member (
                    alliance_id BIGINT NOT NULL REFERENCES younglings.alliance (id) ON DELETE CASCADE,
                    guild_id BIGINT NOT NULL,
                    status TEXT NOT NULL,
                    invited_by BIGINT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (alliance_id, guild_id)
                );
                """,
                "CREATE INDEX IF NOT EXISTS alliance_member_guild ON younglings.alliance_member (guild_id);"));
    }

    /** Creates an alliance with {@code guildId} as its first (active) member. Empty when the name is taken. */
    public OptionalLong create(String name, long guildId, long by) {
        try (Connection c = connectionSupplier.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement s = c.prepareStatement("INSERT INTO younglings.alliance (name, created_by_guild) VALUES (?, ?) ON CONFLICT DO NOTHING RETURNING id");
                 PreparedStatement m = c.prepareStatement("INSERT INTO younglings.alliance_member (alliance_id, guild_id, status, invited_by) VALUES (?, ?, 'ACTIVE', ?)")) {
                s.setString(1, name);
                s.setLong(2, guildId);
                long id;
                try (ResultSet rs = s.executeQuery()) {
                    if (!rs.next()) {
                        c.rollback();
                        return OptionalLong.empty();
                    }
                    id = rs.getLong(1);
                }
                m.setLong(1, id);
                m.setLong(2, guildId);
                m.setLong(3, by);
                m.executeUpdate();
                c.commit();
                return OptionalLong.of(id);
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create the alliance", e);
        }
    }

    public Optional<Alliance> alliance(long id) {
        return alliances("SELECT * FROM younglings.alliance WHERE id = ?", id).stream().findFirst();
    }

    /** Every alliance this server is in or invited to. */
    public List<Alliance> alliancesOf(long guildId) {
        return alliances("SELECT a.* FROM younglings.alliance a JOIN younglings.alliance_member m ON m.alliance_id = a.id WHERE m.guild_id = ? ORDER BY a.name", guildId);
    }

    public List<Membership> membersOf(long allianceId) {
        return memberships("SELECT * FROM younglings.alliance_member WHERE alliance_id = ? ORDER BY status, created_at", allianceId);
    }

    public Optional<Membership> membership(long allianceId, long guildId) {
        return membershipsBy("SELECT * FROM younglings.alliance_member WHERE alliance_id = ? AND guild_id = ?", allianceId, guildId).stream().findFirst();
    }

    /** How many alliances the server is in or invited to. */
    public int countFor(long guildId) {
        return alliancesOf(guildId).size();
    }

    /** Invites a server. False when it is already in or invited, or the alliance is full (checked in the same statement). */
    public boolean invite(long allianceId, long guildId, long by, int maxMembers) {
        String sql = """
                INSERT INTO younglings.alliance_member (alliance_id, guild_id, status, invited_by)
                SELECT ?, ?, 'INVITED', ? WHERE (SELECT COUNT(*) FROM younglings.alliance_member WHERE alliance_id = ?) < ?
                ON CONFLICT DO NOTHING
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, allianceId);
            s.setLong(2, guildId);
            s.setLong(3, by);
            s.setLong(4, allianceId);
            s.setInt(5, maxMembers);
            return s.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to invite the clan", e);
        }
    }

    /** Turns an invitation into membership. False when there was no invitation. */
    public boolean accept(long allianceId, long guildId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("UPDATE younglings.alliance_member SET status = 'ACTIVE' WHERE alliance_id = ? AND guild_id = ? AND status = 'INVITED'")) {
            s.setLong(1, allianceId);
            s.setLong(2, guildId);
            return s.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to accept the invitation", e);
        }
    }

    /** Removes a server (leaving, declining an invitation, or being removed). An alliance with no active member left is deleted. */
    public boolean remove(long allianceId, long guildId) {
        try (Connection c = connectionSupplier.getConnection()) {
            boolean removed;
            try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings.alliance_member WHERE alliance_id = ? AND guild_id = ?")) {
                s.setLong(1, allianceId);
                s.setLong(2, guildId);
                removed = s.executeUpdate() == 1;
            }
            try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings.alliance WHERE id = ? AND NOT EXISTS (SELECT 1 FROM younglings.alliance_member WHERE alliance_id = ? AND status = 'ACTIVE')")) {
                s.setLong(1, allianceId);
                s.setLong(2, allianceId);
                s.executeUpdate();
            }
            return removed;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to remove the clan", e);
        }
    }

    private List<Alliance> alliances(String sql, long param) {
        List<Alliance> list = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, param);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) list.add(new Alliance(rs.getLong("id"), rs.getString("name"), rs.getLong("created_by_guild"), rs.getTimestamp("created_at").toInstant()));
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read alliances", e);
        }
    }

    private List<Membership> memberships(String sql, long param) {
        return membershipsBy(sql, param, null);
    }

    private List<Membership> membershipsBy(String sql, long first, Long second) {
        List<Membership> list = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, first);
            if (second != null) s.setLong(2, second);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    long by = rs.getLong("invited_by");
                    boolean noBy = rs.wasNull();
                    list.add(new Membership(rs.getLong("alliance_id"), rs.getLong("guild_id"), Status.valueOf(rs.getString("status")), noBy ? null : by,
                            rs.getTimestamp("created_at").toInstant()));
                }
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read alliance members", e);
        }
    }
}
