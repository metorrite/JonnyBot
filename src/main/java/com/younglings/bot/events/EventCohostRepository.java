package com.younglings.bot.events;

import com.younglings.bot.database.SchemaBootstrapper;
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
import java.util.List;
import java.util.Optional;

/**
 * The servers that co-host an event (T-219, design {@code docs/EVENTS-CROSS-SERVER.md}): invited by the host, then accepted or declined by the other
 * server's admins. Everything about the event itself (roles, channels, proof) stays in the host's server; a co-host only brings players.
 */
@BService
public class EventCohostRepository {
    private static final Logger log = LoggerFactory.getLogger(EventCohostRepository.class);

    public enum Status { INVITED, ACCEPTED, DECLINED, REMOVED }

    public record Cohost(long eventId, long guildId, Status status, Long invitedBy, Long decidedBy, Instant createdAt, Instant decidedAt) {}

    private final ConnectionSupplier connectionSupplier;

    public EventCohostRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "event co-hosts", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.event_cohost (
                    event_id BIGINT NOT NULL REFERENCES younglings.event (id) ON DELETE CASCADE,
                    guild_id BIGINT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'INVITED',
                    invited_by BIGINT NULL,
                    decided_by BIGINT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    decided_at TIMESTAMPTZ NULL,
                    PRIMARY KEY (event_id, guild_id)
                );
                """,
                "CREATE INDEX IF NOT EXISTS event_cohost_guild ON younglings.event_cohost (guild_id, status);",
                // T-220: the co-host's seat quota (null = no quota), read with the roster by EventRepository#cohostQuotas
                "ALTER TABLE younglings.event_cohost ADD COLUMN IF NOT EXISTS quota INT NULL;"));
    }

    /** Sets each listed co-host's seat quota (null clears it). Servers that aren't co-hosts of the event are left alone. */
    public void setQuotas(long eventId, java.util.Map<Long, Integer> quotas) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("UPDATE younglings.event_cohost SET quota = ? WHERE event_id = ? AND guild_id = ?")) {
            for (var q : quotas.entrySet()) {
                if (q.getValue() == null) s.setNull(1, Types.INTEGER);
                else s.setInt(1, q.getValue());
                s.setLong(2, eventId);
                s.setLong(3, q.getKey());
                s.addBatch();
            }
            s.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the co-hosts' seat quotas", e);
        }
    }

    private static Cohost map(ResultSet rs) throws SQLException {
        long invitedBy = rs.getLong("invited_by");
        Long invited = rs.wasNull() ? null : invitedBy;
        long decidedBy = rs.getLong("decided_by");
        Long decided = rs.wasNull() ? null : decidedBy;
        Timestamp decidedAt = rs.getTimestamp("decided_at");
        return new Cohost(rs.getLong("event_id"), rs.getLong("guild_id"), Status.valueOf(rs.getString("status")), invited, decided, rs.getTimestamp("created_at").toInstant(),
                decidedAt == null ? null : decidedAt.toInstant());
    }

    private List<Cohost> query(String sql, long id) {
        List<Cohost> list = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, id);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the co-hosts", e);
        }
    }

    /** An event's co-hosts, every status, in the order they were invited. */
    public List<Cohost> forEvent(long eventId) {
        return query("SELECT * FROM younglings.event_cohost WHERE event_id = ? ORDER BY created_at, guild_id", eventId);
    }

    /** The events a server is invited to or co-hosts (not declined or removed ones), newest first. */
    public List<Cohost> forGuild(long guildId) {
        return query("SELECT * FROM younglings.event_cohost WHERE guild_id = ? AND status IN ('INVITED', 'ACCEPTED') ORDER BY created_at DESC", guildId);
    }

    public Optional<Cohost> get(long eventId, long guildId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT * FROM younglings.event_cohost WHERE event_id = ? AND guild_id = ?")) {
            s.setLong(1, eventId);
            s.setLong(2, guildId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the co-host", e);
        }
    }

    /** Invites a server (again, if it declined or was removed before). */
    public void invite(long eventId, long guildId, long by) {
        String sql = """
                INSERT INTO younglings.event_cohost (event_id, guild_id, status, invited_by) VALUES (?, ?, 'INVITED', ?)
                ON CONFLICT (event_id, guild_id) DO UPDATE SET status = 'INVITED', invited_by = EXCLUDED.invited_by, decided_by = NULL, decided_at = NULL, created_at = NOW()
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, eventId);
            s.setLong(2, guildId);
            s.setLong(3, by);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to invite the co-host", e);
        }
    }

    /** Moves a co-host from {@code from} to {@code to}. False when it wasn't in {@code from} (someone else acted first). */
    public boolean decide(long eventId, long guildId, Status from, Status to, Long by) {
        String sql = "UPDATE younglings.event_cohost SET status = ?, decided_by = ?, decided_at = NOW() WHERE event_id = ? AND guild_id = ? AND status = ?";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, to.name());
            if (by == null) s.setNull(2, Types.BIGINT);
            else s.setLong(2, by);
            s.setLong(3, eventId);
            s.setLong(4, guildId);
            s.setString(5, from.name());
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to change the co-host", e);
        }
    }
}
