package com.younglings.bot.clanprofile;

import com.younglings.bot.clanprofile.SlugChangeRules.State;
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

/** The stored state of each server's address change ({@link SlugChangeRules}): one row per server that has ever asked for one. */
@BService
public class SlugChangeRepository {
    private static final Logger log = LoggerFactory.getLogger(SlugChangeRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public SlugChangeRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "clan address changes", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.clan_slug_change (
                    guild_id BIGINT PRIMARY KEY,
                    pending_slug TEXT NULL,
                    requested_at TIMESTAMPTZ NULL,
                    last_changed_at TIMESTAMPTZ NULL
                );
                """
        ));
    }

    public State get(long guildId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(
                "SELECT pending_slug, requested_at, last_changed_at FROM younglings.clan_slug_change WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? read(rs) : State.NONE;
            }
        } catch (SQLException e) {
            log.error("Failed to read the address change of guild {}", guildId, e);
            throw new RuntimeException("Failed to read the address change", e);
        }
    }

    public void save(long guildId, State state) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("""
                INSERT INTO younglings.clan_slug_change (guild_id, pending_slug, requested_at, last_changed_at) VALUES (?, ?, ?, ?)
                ON CONFLICT (guild_id) DO UPDATE SET pending_slug = EXCLUDED.pending_slug, requested_at = EXCLUDED.requested_at,
                    last_changed_at = EXCLUDED.last_changed_at
                """)) {
            s.setLong(1, guildId);
            if (state.pendingSlug() == null) s.setNull(2, Types.VARCHAR);
            else s.setString(2, state.pendingSlug());
            time(s, 3, state.requestedAt());
            time(s, 4, state.lastChangedAt());
            s.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to save the address change of guild {}", guildId, e);
            throw new RuntimeException("Failed to save the address change", e);
        }
    }

    /** The servers whose request's hour is over at {@code now}. */
    public List<Long> dueGuilds(Instant now) {
        List<Long> out = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(
                "SELECT guild_id FROM younglings.clan_slug_change WHERE pending_slug IS NOT NULL AND requested_at <= ?")) {
            s.setTimestamp(1, Timestamp.from(now.minus(SlugChangeRules.WINDOW)));
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) out.add(rs.getLong(1));
            }
            return out;
        } catch (SQLException e) {
            log.error("Failed to list the due address changes", e);
            throw new RuntimeException("Failed to list the due address changes", e);
        }
    }

    private static State read(ResultSet rs) throws SQLException {
        Timestamp requested = rs.getTimestamp("requested_at");
        Timestamp changed = rs.getTimestamp("last_changed_at");
        return new State(rs.getString("pending_slug"), requested == null ? null : requested.toInstant(), changed == null ? null : changed.toInstant());
    }

    private static void time(PreparedStatement s, int index, Instant value) throws SQLException {
        if (value == null) s.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE);
        else s.setTimestamp(index, Timestamp.from(value));
    }
}
