package com.runeclans.bot.events;

import com.runeclans.bot.database.SchemaBootstrapper;
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
import java.util.OptionalInt;

/**
 * Skill of the week (T-541): each server's settings (off unless saved on, the rota and where in it the next week is) and one row per week opened,
 * with its competition and, once closed, its winners as JSON ({@code [{rank, userId, xp}]}). T-711 adds the options (members vote for next week's
 * skill instead of the rota, a channel the week's skill and winners are announced in, auto-enrolling members who share RuneSync XP), each off by
 * default, the votes (one per member per week) and who was auto-enrolled in each week (once only, so a member who leaves stays out).
 */
@BService
public class SkillWeekRepository {
    private static final Logger log = LoggerFactory.getLogger(SkillWeekRepository.class);

    /** How a week's skill is chosen: the rota's next, or the members' vote among the rota's skills (T-711). */
    public enum Mode { ROTA, VOTE }

    /**
     * The options (T-711), all off by default: {@code mode}, the channel the week's skill and its winners are posted in ({@code announceChannelId},
     * null = no posts), and whether members who share RuneSync XP are put in the week's competition by themselves ({@code autoEnrol}).
     */
    public record Options(Mode mode, Long announceChannelId, boolean autoEnrol) {
        public static final Options OFF = new Options(Mode.ROTA, null, false);
    }

    /** A server's settings; {@code updatedBy} is who switched it on, the organiser of the competitions it opens. */
    public record Settings(boolean enabled, List<Integer> rota, int position, long updatedBy, Options options) {
        public static final Settings OFF = new Settings(false, List.of(), 0, 0);

        public Settings(boolean enabled, List<Integer> rota, int position, long updatedBy) {
            this(enabled, rota, position, updatedBy, Options.OFF);
        }
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
                "CREATE INDEX IF NOT EXISTS skill_week_open ON younglings.skill_week (week_start) WHERE closed_at IS NULL;",
                // T-711: the options, off by default
                "ALTER TABLE younglings.skill_week_settings ADD COLUMN IF NOT EXISTS mode TEXT NOT NULL DEFAULT 'ROTA';",
                "ALTER TABLE younglings.skill_week_settings ADD COLUMN IF NOT EXISTS announce_channel_id BIGINT NULL;",
                "ALTER TABLE younglings.skill_week_settings ADD COLUMN IF NOT EXISTS auto_enrol BOOLEAN NOT NULL DEFAULT FALSE;",
                // one vote per member for the skill of the week that starts at week_start
                """
                CREATE TABLE IF NOT EXISTS younglings.skill_week_vote (
                    guild_id BIGINT NOT NULL,
                    week_start TIMESTAMPTZ NOT NULL,
                    user_id BIGINT NOT NULL,
                    skill_id INT NOT NULL,
                    voted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (guild_id, week_start, user_id)
                );
                """,
                // who was auto-enrolled in a week's competition: each member once, so one who leaves isn't put back
                """
                CREATE TABLE IF NOT EXISTS younglings.skill_week_enrolled (
                    guild_id BIGINT NOT NULL,
                    week_start TIMESTAMPTZ NOT NULL,
                    user_id BIGINT NOT NULL,
                    enrolled_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (guild_id, week_start, user_id)
                );
                """));
    }

    private static final String SETTINGS = "enabled, rota, position, updated_by, mode, announce_channel_id, auto_enrol";

    private static Settings settingsOf(ResultSet rs) throws SQLException {
        Mode mode;
        try {
            mode = Mode.valueOf(rs.getString(5));
        } catch (RuntimeException e) {
            mode = Mode.ROTA;
        }
        long channel = rs.getLong(6);
        Long announce = rs.wasNull() ? null : channel;
        return new Settings(rs.getBoolean(1), rota(rs.getString(2)), rs.getInt(3), rs.getLong(4), new Options(mode, announce, rs.getBoolean(7)));
    }

    public Settings settings(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT " + SETTINGS + " FROM younglings.skill_week_settings WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? settingsOf(rs) : Settings.OFF;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the skill of the week settings", e);
        }
    }

    /**
     * Saves the switch and the rota; a changed rota goes on from the skill after the latest week's ({@link SkillWeekRules#positionAfter}). The
     * settings row is locked first, so a {@link #claim} running at the same time either sees the new rota or is seen by it.
     */
    public void save(long guildId, boolean enabled, List<Integer> rota, long actorId) {
        save(guildId, enabled, rota, Options.OFF, actorId);
    }

    /** {@link #save}, with the options (T-711). */
    public void save(long guildId, boolean enabled, List<Integer> rota, Options options, long actorId) {
        String sql = """
                INSERT INTO younglings.skill_week_settings (guild_id, enabled, rota, position, updated_by, updated_at, mode, announce_channel_id, auto_enrol)
                VALUES (?, ?, ?, ?, ?, NOW(), ?, ?, ?)
                ON CONFLICT (guild_id) DO UPDATE SET enabled = EXCLUDED.enabled, rota = EXCLUDED.rota, updated_by = EXCLUDED.updated_by, updated_at = NOW(),
                    mode = EXCLUDED.mode, announce_channel_id = EXCLUDED.announce_channel_id, auto_enrol = EXCLUDED.auto_enrol,
                    position = CASE WHEN younglings.skill_week_settings.rota = EXCLUDED.rota THEN younglings.skill_week_settings.position ELSE EXCLUDED.position END
                """;
        try (Connection c = connectionSupplier.getConnection()) {
            c.setAutoCommit(false);
            try {
                lockSettings(c, guildId);
                Integer last = null;
                try (PreparedStatement s = c.prepareStatement("SELECT skill_id FROM younglings.skill_week WHERE guild_id = ? ORDER BY week_start DESC LIMIT 1")) {
                    s.setLong(1, guildId);
                    try (ResultSet rs = s.executeQuery()) {
                        if (rs.next()) last = rs.getInt(1);
                    }
                }
                try (PreparedStatement s = c.prepareStatement(sql)) {
                    s.setLong(1, guildId);
                    s.setBoolean(2, enabled);
                    s.setString(3, DataArray.fromCollection(rota).toString());
                    s.setInt(4, SkillWeekRules.positionAfter(rota, last));
                    s.setLong(5, actorId);
                    s.setString(6, options.mode().name());
                    if (options.announceChannelId() == null) s.setNull(7, java.sql.Types.BIGINT); else s.setLong(7, options.announceChannelId());
                    s.setBoolean(8, options.autoEnrol());
                    s.executeUpdate();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the skill of the week settings", e);
        }
    }

    /** Locks a server's settings row for the transaction; the row read, or null when it has none. */
    private static Settings lockSettings(Connection c, long guildId) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT " + SETTINGS + " FROM younglings.skill_week_settings WHERE guild_id = ? FOR UPDATE")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? settingsOf(rs) : null;
            }
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
     * Claims a week for the skill at the rota's stored position and moves the rota on, in one transaction with the settings row locked, so the
     * skill always follows what is stored (a stale claim given back just before, or a rota saved at the same moment). The skill claimed, or empty
     * when the week was already claimed (another pass, or a restart), so a week is only ever opened once, or the server has no rota. In
     * {@link Mode#VOTE} the skill is the members' vote for that week ({@link SkillWeekRules#voteWinner}); the rota still moves on one, so it
     * breaks ties and picks when nobody voted, and switching back to the rota goes on from there.
     */
    public OptionalInt claim(long guildId, Instant start) {
        try (Connection c = connectionSupplier.getConnection()) {
            c.setAutoCommit(false);
            try {
                Settings settings = lockSettings(c, guildId);
                if (settings == null || settings.rota().isEmpty()) {
                    c.rollback();
                    return OptionalInt.empty();
                }
                int skillId = settings.options().mode() == Mode.VOTE
                        ? SkillWeekRules.voteWinner(settings.rota(), settings.position(), votes(c, guildId, start))
                        : SkillWeekRules.skillAt(settings.rota(), settings.position());
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
                return added == 1 ? OptionalInt.of(skillId) : OptionalInt.empty();
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
                lockSettings(c, guildId); // the same order as claim: settings row first, then the week
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

    /** Each skill's votes for the week that starts at {@code start}. */
    public java.util.Map<Integer, Integer> votes(long guildId, Instant start) {
        try (Connection c = connectionSupplier.getConnection()) {
            return votes(c, guildId, start);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the skill of the week votes", e);
        }
    }

    private static java.util.Map<Integer, Integer> votes(Connection c, long guildId, Instant start) throws SQLException {
        java.util.Map<Integer, Integer> out = new java.util.LinkedHashMap<>();
        try (PreparedStatement s = c.prepareStatement("SELECT skill_id, COUNT(*) FROM younglings.skill_week_vote WHERE guild_id = ? AND week_start = ? GROUP BY skill_id ORDER BY skill_id")) {
            s.setLong(1, guildId);
            s.setTimestamp(2, Timestamp.from(start));
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) out.put(rs.getInt(1), rs.getInt(2));
            }
        }
        return out;
    }

    /** The member's vote for the week that starts at {@code start}, if any. */
    public OptionalInt vote(long guildId, Instant start, long userId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT skill_id FROM younglings.skill_week_vote WHERE guild_id = ? AND week_start = ? AND user_id = ?")) {
            s.setLong(1, guildId);
            s.setTimestamp(2, Timestamp.from(start));
            s.setLong(3, userId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? OptionalInt.of(rs.getInt(1)) : OptionalInt.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read a skill of the week vote", e);
        }
    }

    /** Casts (or changes) the member's vote for the week that starts at {@code start}. */
    public void vote(long guildId, Instant start, long userId, int skillId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("""
                INSERT INTO younglings.skill_week_vote (guild_id, week_start, user_id, skill_id) VALUES (?, ?, ?, ?)
                ON CONFLICT (guild_id, week_start, user_id) DO UPDATE SET skill_id = EXCLUDED.skill_id, voted_at = NOW()
                """)) {
            s.setLong(1, guildId);
            s.setTimestamp(2, Timestamp.from(start));
            s.setLong(3, userId);
            s.setInt(4, skillId);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save a skill of the week vote", e);
        }
    }

    /** Notes that the member was auto-enrolled in the week: true the first time, false when they already were (T-711). */
    public boolean markEnrolled(long guildId, Instant start, long userId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(
                "INSERT INTO younglings.skill_week_enrolled (guild_id, week_start, user_id) VALUES (?, ?, ?) ON CONFLICT DO NOTHING")) {
            s.setLong(1, guildId);
            s.setTimestamp(2, Timestamp.from(start));
            s.setLong(3, userId);
            return s.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to note an auto-enrolled member", e);
        }
    }

    /** Who was already auto-enrolled in the week. */
    public java.util.Set<Long> enrolled(long guildId, Instant start) {
        java.util.Set<Long> out = new java.util.HashSet<>();
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT user_id FROM younglings.skill_week_enrolled WHERE guild_id = ? AND week_start = ?")) {
            s.setLong(1, guildId);
            s.setTimestamp(2, Timestamp.from(start));
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) out.add(rs.getLong(1));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the auto-enrolled members", e);
        }
        return out;
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
