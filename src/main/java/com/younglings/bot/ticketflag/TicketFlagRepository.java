package com.younglings.bot.ticketflag;

import com.younglings.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Staff flags on a ticket's player: a short private note that a help experience with this person went badly. Visible to staff on the dashboard only;
 * the player is never told and nothing happens automatically. A server turns the feature on (it is off by default).
 */
@BService
public class TicketFlagRepository {
    private static final Logger log = LoggerFactory.getLogger(TicketFlagRepository.class);

    public record Flag(long id, long guildId, long ticketId, int ticketNumber, long playerId, long flaggedBy, String note, OffsetDateTime createdAt) {}

    private static final String COLUMNS = "id, guild_id, ticket_id, ticket_number, player_id, flagged_by, note, created_at";

    private final ConnectionSupplier connectionSupplier;

    public TicketFlagRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "ticket flags", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.ticket_flag_settings (
                    guild_id BIGINT PRIMARY KEY,
                    enabled BOOLEAN NOT NULL DEFAULT FALSE
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.ticket_experience_flag (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    ticket_id BIGINT NOT NULL,
                    ticket_number INTEGER NOT NULL,
                    player_id BIGINT NOT NULL,
                    flagged_by BIGINT NOT NULL,
                    note TEXT NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                "CREATE INDEX IF NOT EXISTS ticket_experience_flag_guild_idx ON younglings.ticket_experience_flag (guild_id, player_id);"));
    }

    public boolean enabled(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT enabled FROM younglings.ticket_flag_settings WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        } catch (SQLException e) {
            log.error("Failed to read the ticket flag setting of guild {}", guildId, e);
            throw new RuntimeException("Failed to read the ticket flag setting", e);
        }
    }

    public void setEnabled(long guildId, boolean enabled) {
        String sql = "INSERT INTO younglings.ticket_flag_settings (guild_id, enabled) VALUES (?, ?) ON CONFLICT (guild_id) DO UPDATE SET enabled = EXCLUDED.enabled";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            s.setBoolean(2, enabled);
            s.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to save the ticket flag setting of guild {}", guildId, e);
            throw new RuntimeException("Failed to save the ticket flag setting", e);
        }
    }

    public long add(long guildId, long ticketId, int ticketNumber, long playerId, long flaggedBy, String note) {
        String sql = "INSERT INTO younglings.ticket_experience_flag (guild_id, ticket_id, ticket_number, player_id, flagged_by, note) VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            s.setLong(1, guildId);
            s.setLong(2, ticketId);
            s.setInt(3, ticketNumber);
            s.setLong(4, playerId);
            s.setLong(5, flaggedBy);
            s.setString(6, note);
            s.executeUpdate();
            try (ResultSet keys = s.getGeneratedKeys()) {
                keys.next();
                return keys.getLong("id");
            }
        } catch (SQLException e) {
            log.error("Failed to add a ticket flag in guild {}", guildId, e);
            throw new RuntimeException("Failed to add a ticket flag", e);
        }
    }

    /** Newest first; {@code playerId} narrows it to one player. */
    public List<Flag> list(long guildId, Long playerId, int limit) {
        String sql = "SELECT " + COLUMNS + " FROM younglings.ticket_experience_flag WHERE guild_id = ?" + (playerId == null ? "" : " AND player_id = ?")
                + " ORDER BY created_at DESC, id DESC LIMIT ?";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            int i = 1;
            s.setLong(i++, guildId);
            if (playerId != null) s.setLong(i++, playerId);
            s.setInt(i, limit);
            List<Flag> out = new ArrayList<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    out.add(new Flag(rs.getLong("id"), rs.getLong("guild_id"), rs.getLong("ticket_id"), rs.getInt("ticket_number"), rs.getLong("player_id"),
                            rs.getLong("flagged_by"), rs.getString("note"), rs.getObject("created_at", OffsetDateTime.class)));
                }
            }
            return out;
        } catch (SQLException e) {
            log.error("Failed to list ticket flags of guild {}", guildId, e);
            throw new RuntimeException("Failed to list ticket flags", e);
        }
    }

    public int count(long guildId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT COUNT(*) FROM younglings.ticket_experience_flag WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count ticket flags", e);
        }
    }

    /** True if a flag of this guild was removed. */
    public boolean delete(long guildId, long id) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.ticket_experience_flag WHERE guild_id = ? AND id = ?")) {
            s.setLong(1, guildId);
            s.setLong(2, id);
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to delete ticket flag {}", id, e);
            throw new RuntimeException("Failed to delete a ticket flag", e);
        }
    }
}
