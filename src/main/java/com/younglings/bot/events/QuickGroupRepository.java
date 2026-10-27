package com.younglings.bot.events;

import com.younglings.bot.database.SchemaBootstrapper;
import com.younglings.bot.events.EventRules.Scope;
import com.younglings.bot.events.EventRules.ScoringMode;
import com.younglings.bot.events.EventRules.Status;
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
 * The group finder's storage (T-589). A group is an event row ({@link QuickGroups#TEMPLATE}) made through {@link EventRepository#createEvent}, and its
 * members are that event's players: this adds only the role a member took ({@code event_participant.group_role}), the server's switch and channel
 * ({@code group_finder_settings}, off until an owner turns it on), and the Discord message a group was posted as ({@code group_finder_post}).
 */
@BService
public class QuickGroupRepository {
    private static final Logger log = LoggerFactory.getLogger(QuickGroupRepository.class);

    /** The server's group finder: whether members can post groups on its site, and the channel the bot posts them in (null: not posted). */
    public record Settings(boolean enabled, Long channelId) {
        public static final Settings OFF = new Settings(false, null);
    }

    /** A group as the finder reads it. */
    public record Group(long id, long guildId, String activity, String note, Instant startsAt, Instant endsAt, long hostId, String config, Status status) {
        public boolean open() {
            return status == Status.SIGNUPS;
        }
    }

    /** A member of a group and the role they took. */
    public record Member(long userId, String role, Instant joinedAt) {}

    /** Where a group was posted in Discord. */
    public record Post(long channelId, long messageId) {}

    /** What a join did. */
    public enum Joined { JOINED, SWITCHED, ALREADY, FULL, CLOSED }

    private final ConnectionSupplier connectionSupplier;
    private final EventRepository events;

    public QuickGroupRepository(ConnectionSupplier connectionSupplier, EventRepository events) {
        this.connectionSupplier = connectionSupplier;
        this.events = events; // also makes sure the event tables exist before the column below is added
        SchemaBootstrapper.run(connectionSupplier, log, "group finder", List.of(
                // the role slot a member of a quick group took; null for every other event's players
                "ALTER TABLE younglings.event_participant ADD COLUMN IF NOT EXISTS group_role TEXT NULL;",
                """
                CREATE TABLE IF NOT EXISTS younglings.group_finder_settings (
                    guild_id BIGINT PRIMARY KEY,
                    enabled BOOLEAN NOT NULL DEFAULT FALSE,
                    channel_id BIGINT NULL,
                    updated_by BIGINT NULL,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.group_finder_post (
                    event_id BIGINT PRIMARY KEY REFERENCES younglings.event (id) ON DELETE CASCADE,
                    channel_id BIGINT NOT NULL,
                    message_id BIGINT NOT NULL
                );
                """,
                "CREATE INDEX IF NOT EXISTS event_quick_group_open ON younglings.event (guild_id, starts_at) WHERE template = '" + QuickGroups.TEMPLATE + "' AND status = 'SIGNUPS';"));
    }

    // ---------- settings ----------

    /** The server's settings; never saved reads as off. */
    public Settings settings(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT enabled, channel_id FROM younglings.group_finder_settings WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) return Settings.OFF;
                boolean enabled = rs.getBoolean(1);
                long channel = rs.getLong(2);
                return new Settings(enabled, rs.wasNull() ? null : channel);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the group finder settings", e);
        }
    }

    public void saveSettings(long guildId, Settings settings, long by) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("""
                INSERT INTO younglings.group_finder_settings (guild_id, enabled, channel_id, updated_by, updated_at) VALUES (?, ?, ?, ?, NOW())
                ON CONFLICT (guild_id) DO UPDATE SET enabled = EXCLUDED.enabled, channel_id = EXCLUDED.channel_id, updated_by = EXCLUDED.updated_by, updated_at = NOW()""")) {
            s.setLong(1, guildId);
            s.setBoolean(2, settings.enabled());
            if (settings.channelId() == null) s.setNull(3, Types.BIGINT); else s.setLong(3, settings.channelId());
            s.setLong(4, by);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the group finder settings", e);
        }
    }

    // ---------- groups ----------

    /** Makes a group: an event row of the quick-group kind, taking signups at once. */
    public long create(long guildId, QuickGroups.Plan plan, long hostId) {
        long id = events.createEvent(guildId, QuickGroups.TEMPLATE, plan.activity(), plan.note(), Scope.PRIVATE, ScoringMode.INDIVIDUAL, plan.startsAt(),
                QuickGroups.endsAt(plan.startsAt()), plan.places(), QuickGroups.config(plan), hostId);
        events.moveStage(guildId, id, Status.DRAFT, Status.SIGNUPS);
        return id;
    }

    private static final String COLUMNS = "id, guild_id, name, description, starts_at, ends_at, created_by, config, status";

    private static Group map(ResultSet rs) throws SQLException {
        Timestamp starts = rs.getTimestamp(5), ends = rs.getTimestamp(6);
        return new Group(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), starts == null ? null : starts.toInstant(),
                ends == null ? null : ends.toInstant(), rs.getLong(7), rs.getString(8), Status.valueOf(rs.getString(9)));
    }

    public Optional<Group> group(long guildId, long id) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(
                "SELECT " + COLUMNS + " FROM younglings.event WHERE guild_id = ? AND id = ? AND template = ?")) {
            s.setLong(1, guildId);
            s.setLong(2, id);
            s.setString(3, QuickGroups.TEMPLATE);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the group", e);
        }
    }

    /** The server's open groups that are still listed at {@code now}, soonest first. */
    public List<Group> open(long guildId, Instant now, int limit) {
        List<Group> list = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(
                "SELECT " + COLUMNS + " FROM younglings.event WHERE guild_id = ? AND template = ? AND status = 'SIGNUPS' AND (ends_at IS NULL OR ends_at > ?) "
                        + "ORDER BY starts_at NULLS LAST, id LIMIT ?")) {
            s.setLong(1, guildId);
            s.setString(2, QuickGroups.TEMPLATE);
            s.setTimestamp(3, Timestamp.from(now));
            s.setInt(4, limit);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the open groups", e);
        }
    }

    /** Open groups in the server, and of those the ones {@code hostId} posted. */
    public int[] countOpen(long guildId, long hostId, Instant now) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(
                "SELECT COUNT(*), COUNT(*) FILTER (WHERE created_by = ?) FROM younglings.event WHERE guild_id = ? AND template = ? AND status = 'SIGNUPS' "
                        + "AND (ends_at IS NULL OR ends_at > ?)")) {
            s.setLong(1, hostId);
            s.setLong(2, guildId);
            s.setString(3, QuickGroups.TEMPLATE);
            s.setTimestamp(4, Timestamp.from(now));
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return new int[]{rs.getInt(1), rs.getInt(2)};
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count the open groups", e);
        }
    }

    /** A group's members, first come first. */
    public List<Member> members(long eventId) {
        List<Member> list = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(
                "SELECT user_id, group_role, joined_at FROM younglings.event_participant WHERE event_id = ? ORDER BY joined_at, user_id")) {
            s.setLong(1, eventId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) list.add(new Member(rs.getLong(1), rs.getString(2), rs.getTimestamp(3).toInstant()));
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the group's members", e);
        }
    }

    /**
     * Puts a member in a role slot of an open group, or moves them to it from the role they had. One join at a time per group (the same lock the
     * event engine takes on its player list), so two people can't both take the last place.
     */
    public Joined join(long guildId, long eventId, long userId, QuickGroups.Slot slot) {
        try (Connection c = connectionSupplier.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement lock = c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                    lock.setString(1, "event-participants:" + eventId);
                    lock.execute();
                }
                try (PreparedStatement s = c.prepareStatement("SELECT 1 FROM younglings.event WHERE guild_id = ? AND id = ? AND template = ? AND status = 'SIGNUPS'")) {
                    s.setLong(1, guildId);
                    s.setLong(2, eventId);
                    s.setString(3, QuickGroups.TEMPLATE);
                    try (ResultSet rs = s.executeQuery()) {
                        if (!rs.next()) {
                            c.rollback();
                            return Joined.CLOSED;
                        }
                    }
                }
                String held = null;
                boolean member = false;
                try (PreparedStatement s = c.prepareStatement("SELECT group_role FROM younglings.event_participant WHERE event_id = ? AND user_id = ?")) {
                    s.setLong(1, eventId);
                    s.setLong(2, userId);
                    try (ResultSet rs = s.executeQuery()) {
                        if (rs.next()) {
                            member = true;
                            held = rs.getString(1);
                        }
                    }
                }
                if (member && held != null && held.equalsIgnoreCase(slot.role())) {
                    c.rollback();
                    return Joined.ALREADY;
                }
                int taken;
                try (PreparedStatement s = c.prepareStatement("SELECT COUNT(*) FROM younglings.event_participant WHERE event_id = ? AND LOWER(group_role) = LOWER(?)")) {
                    s.setLong(1, eventId);
                    s.setString(2, slot.role());
                    try (ResultSet rs = s.executeQuery()) {
                        rs.next();
                        taken = rs.getInt(1);
                    }
                }
                if (!QuickGroups.hasRoom(slot, taken)) {
                    c.rollback();
                    return Joined.FULL;
                }
                String sql = member
                        ? "UPDATE younglings.event_participant SET group_role = ? WHERE event_id = ? AND user_id = ?"
                        : "INSERT INTO younglings.event_participant (group_role, event_id, user_id, guild_id, kind, status, source) VALUES (?, ?, ?, ?, 'HOME_CLAN', 'ACTIVE', 'SELF')";
                try (PreparedStatement s = c.prepareStatement(sql)) {
                    s.setString(1, slot.role());
                    s.setLong(2, eventId);
                    s.setLong(3, userId);
                    if (!member) s.setLong(4, guildId);
                    s.executeUpdate();
                }
                c.commit();
                return member ? Joined.SWITCHED : Joined.JOINED;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to join the group", e);
        }
    }

    /** Takes a member out of a group. False when they weren't in it. */
    public boolean leave(long eventId, long userId) {
        return events.leave(eventId, userId);
    }

    /** Closes an open group (its host, staff, or the clock). False when it was already closed. */
    public boolean close(long guildId, long eventId) {
        return events.moveStage(guildId, eventId, Status.SIGNUPS, Status.DONE);
    }

    /** Closes every group (in every server) whose listing ended before {@code now}, answering which ones, for the sweep. */
    public List<Group> closeEnded(Instant now) {
        List<Group> list = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(
                "UPDATE younglings.event SET status = 'DONE', updated_at = NOW() WHERE template = ? AND status = 'SIGNUPS' AND ends_at IS NOT NULL AND ends_at <= ? "
                        + "RETURNING " + COLUMNS)) {
            s.setString(1, QuickGroups.TEMPLATE);
            s.setTimestamp(2, Timestamp.from(now));
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to close the ended groups", e);
        }
    }

    // ---------- the Discord post ----------

    public Optional<Post> post(long eventId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT channel_id, message_id FROM younglings.group_finder_post WHERE event_id = ?")) {
            s.setLong(1, eventId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(new Post(rs.getLong(1), rs.getLong(2))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the group's post", e);
        }
    }

    public void savePost(long eventId, long channelId, long messageId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("""
                INSERT INTO younglings.group_finder_post (event_id, channel_id, message_id) VALUES (?, ?, ?)
                ON CONFLICT (event_id) DO UPDATE SET channel_id = EXCLUDED.channel_id, message_id = EXCLUDED.message_id""")) {
            s.setLong(1, eventId);
            s.setLong(2, channelId);
            s.setLong(3, messageId);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the group's post", e);
        }
    }

    public void forgetPost(long eventId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.group_finder_post WHERE event_id = ?")) {
            s.setLong(1, eventId);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to forget the group's post", e);
        }
    }
}
