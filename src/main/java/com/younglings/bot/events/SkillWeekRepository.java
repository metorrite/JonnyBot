package com.younglings.bot.events;

import com.younglings.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataArray;
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
 * Skill of the week (T-541): each server's settings (off unless saved on, the rota and where in it the next week is) and one row per week opened,
 * with its competition and, once closed, its winners as JSON ({@code [{rank, userId, xp}]}).
 */
@BService
public class SkillWeekRepository {
    private static final Logger log = LoggerFactory.getLogger(SkillWeekRepository.class);

    /** A server's settings; {@code updatedBy} is who switched it on, the organiser of the competitions it opens. */
    public record Settings(boolean enabled, List<Integer> rota, int position, long updatedBy) {
        public static final Settings OFF = new Settings(false, List.of(), 0, 0);
    }

    /** One week: {@code eventId} null until its competition exists, {@code winners} null until it is closed. */
    public record Week(long guildId, Instant start, int skillId, Long eventId, Instant closedAt, String winners) {}

    private final ConnectionSupplier connectionSupplier;

    public SkillWeekRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "skill of the week", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.skill_week_settings (
                    guild_id BIGINT PRIMARY KEY,
                    enabled BOOLEAN NOT NULL DEFAULT FALSE,
                    rota TEXT NOT NULL DEFAULT '[]',
                    position INT NOT NULL DEFAULT 0,
                    updated_by BIGINT NOT NULL,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.skill_week (
                    guild_id BIGINT NOT NULL,
                    week_start TIMESTAMPTZ NOT NULL,
                    skill_id INT NOT NULL,
                    event_id BIGINT NULL,
                    closed_at TIMESTAMPTZ NULL,
                    winners TEXT NULL,
                    claimed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (guild_id, week_start)
                );
                """,
                "CREATE INDEX IF NOT EXISTS skill_week_open ON younglings.skill_week (week_start) WHERE closed_at IS NULL;"));
    }

    public Settings settings(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT enabled, rota, position, updated_by FROM younglings.skill_week_settings WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? new Settings(rs.getBoolean(1), rota(rs.getString(2)), rs.getInt(3), rs.getLong(4)) : Settings.OFF;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the skill of the week settings", e);
        }
    }

    /** Saves the switch and the rota; a changed rota goes on from the skill after the latest week's ({@link SkillWeekRules#positionAfter}). */
    public void save(long guildId, boolean enabled, List<Integer> rota, long actorId) {
        String sql = """
                INSERT INTO younglings.skill_week_settings (guild_id, enabled, rota, position, updated_by, updated_at) VALUES (?, ?, ?, ?, ?, NOW())
                ON CONFLICT (guild_id) DO UPDATE SET enabled = EXCLUDED.enabled, rota = EXCLUDED.rota, updated_by = EXCLUDED.updated_by, updated_at = NOW(),
                    position = CASE WHEN younglings.skill_week_settings.rota = EXCLUDED.rota THEN younglings.skill_week_settings.position ELSE EXCLUDED.position END
                """;
        List<Week> latest = recent(guildId, 1);
        int position = SkillWeekRules.positionAfter(rota, latest.isEmpty() ? null : latest.get(0).skillId());
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            s.setBoolean(2, enabled);
            s.setString(3, DataArray.fromCollection(rota).toString());
            s.setInt(4, position);
            s.setLong(5, actorId);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the skill of the week settings", e);
        }
    }

    /** The servers with skill of the week switched on. */
    public List<Long> enabledGuilds() {
        List<Long> out = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT guild_id FROM younglings.skill_week_settings WHERE enabled ORDER BY guild_id");
             ResultSet rs = s.executeQuery()) {
            while (rs.next()) out.add(rs.getLong(1));
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list skill of the week servers", e);
        }
        return out;
    }

    /**
     * Claims a week for {@code skillId} and moves the rota on, in one transaction. False when the week was already claimed (another pass, or a
     * restart), so a week is only ever opened once.
     */
    public boolean claim(long guildId, Instant start, int skillId) {
        try (Connection c = connectionSupplier.getConnection()) {
            c.setAutoCommit(false);
            try {
                int added;
                try (PreparedStatement s = c.prepareStatement("INSERT INTO younglings.skill_week (guild_id, week_start, skill_id) VALUES (?, ?, ?) ON CONFLICT DO NOTHING")) {
                    s.setLong(1, guildId);
                    s.setTimestamp(2, Timestamp.from(start));
                    s.setInt(3, skillId);
                    added = s.executeUpdate();
                }
                if (added == 1) {
                    try (PreparedStatement s = c.prepareStatement("UPDATE younglings.skill_week_settings SET position = position + 1 WHERE guild_id = ?")) {
                        s.setLong(1, guildId);
                        s.executeUpdate();
                    }
                }
                c.commit();
                return added == 1;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to claim a skill of the week", e);
        }
    }

    /** Gives a claimed week back (its competition couldn't be made), so a later pass tries again; the rota goes back one too. */
    public void release(long guildId, Instant start) {
        release(guildId, start, null);
    }

    /**
     * {@link #release}, only when the week still has no competition and was claimed before {@code claimedBefore}: the pass that claimed it died
     * (a restart) before its competition was kept. True when it was given back.
     */
    public boolean releaseStale(long guildId, Instant start, Instant claimedBefore) {
        return release(guildId, start, claimedBefore);
    }

    private boolean release(long guildId, Instant start, Instant claimedBefore) {
        try (Connection c = connectionSupplier.getConnection()) {
            c.setAutoCommit(false);
            try {
                int removed;
                String sql = "DELETE FROM younglings.skill_week WHERE guild_id = ? AND week_start = ? AND event_id IS NULL" + (claimedBefore == null ? "" : " AND claimed_at < ?");
                try (PreparedStatement s = c.prepareStatement(sql)) {
                    s.setLong(1, guildId);
                    s.setTimestamp(2, Timestamp.from(start));
                    if (claimedBefore != null) s.setTimestamp(3, Timestamp.from(claimedBefore));
                    removed = s.executeUpdate();
                }
                if (removed == 1) {
                    try (PreparedStatement s = c.prepareStatement("UPDATE younglings.skill_week_settings SET position = GREATEST(0, position - 1) WHERE guild_id = ?")) {
                        s.setLong(1, guildId);
                        s.executeUpdate();
                    }
                }
                c.commit();
                return removed == 1;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to release a skill of the week", e);
        }
    }

    public void setEvent(long guildId, Instant start, long eventId) {
        update("UPDATE younglings.skill_week SET event_id = ? WHERE guild_id = ? AND week_start = ?", guildId, start, s -> s.setLong(1, eventId));
    }

    /** Closes a week with its winners; false when it was already closed. */
    public boolean close(long guildId, Instant start, String winners) {
        return update("UPDATE younglings.skill_week SET closed_at = NOW(), winners = ? WHERE guild_id = ? AND week_start = ? AND closed_at IS NULL", guildId, start,
                s -> s.setString(1, winners)) == 1;
    }

    public Optional<Week> week(long guildId, Instant start) {
        List<Week> found = weeks("SELECT * FROM younglings.skill_week WHERE guild_id = ? AND week_start = ?", s -> {
            s.setLong(1, guildId);
            s.setTimestamp(2, Timestamp.from(start));
        });
        return found.stream().findFirst();
    }

    /** A server's latest weeks, newest first. */
    public List<Week> recent(long guildId, int limit) {
        return weeks("SELECT * FROM younglings.skill_week WHERE guild_id = ? ORDER BY week_start DESC LIMIT ?", s -> {
            s.setLong(1, guildId);
            s.setInt(2, limit);
        });
    }

    /** Every week not closed yet that started before {@code before}, oldest first. */
    public List<Week> unclosedBefore(Instant before) {
        return weeks("SELECT * FROM younglings.skill_week WHERE closed_at IS NULL AND week_start < ? ORDER BY week_start", s -> s.setTimestamp(1, Timestamp.from(before)));
    }

    private interface Binder {
        void bind(PreparedStatement s) throws SQLException;
    }

    private int update(String sql, long guildId, Instant start, Binder first) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            first.bind(s);
            s.setLong(2, guildId);
            s.setTimestamp(3, Timestamp.from(start));
            return s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update a skill of the week", e);
        }
    }

    private List<Week> weeks(String sql, Binder binder) {
        List<Week> out = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            binder.bind(s);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    long event = rs.getLong("event_id");
                    Long eventId = rs.wasNull() ? null : event;
                    Timestamp closed = rs.getTimestamp("closed_at");
                    out.add(new Week(rs.getLong("guild_id"), rs.getTimestamp("week_start").toInstant(), rs.getInt("skill_id"), eventId,
                            closed == null ? null : closed.toInstant(), rs.getString("winners")));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read skill of the week", e);
        }
        return out;
    }

    static List<Integer> rota(String raw) {
        List<Integer> out = new ArrayList<>();
        try {
            DataArray array = DataArray.fromJson(raw == null ? "[]" : raw);
            for (int i = 0; i < array.length(); i++) out.add(array.getInt(i));
        } catch (RuntimeException e) {
            log.warn("Unreadable skill of the week rota: {}", e.getMessage());
        }
        return out;
    }
}
