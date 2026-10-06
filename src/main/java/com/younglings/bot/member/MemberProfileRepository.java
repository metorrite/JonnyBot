package com.younglings.bot.member;

import com.younglings.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What members choose for themselves on the website: their profile text and look, privacy toggles, DM
 * preferences, skill goals, and the server's list of self-assignable roles. Everything is keyed by guild and
 * Discord user so it survives restarts and a second server is a configuration matter, not a schema change.
 */
@BService
public class MemberProfileRepository {
    private static final Logger log = LoggerFactory.getLogger(MemberProfileRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public MemberProfileRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "member profile", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",

                """
                CREATE TABLE IF NOT EXISTS younglings.member_profile (
                    guild_id BIGINT NOT NULL,
                    discord_user_id BIGINT NOT NULL,
                    bio TEXT NOT NULL DEFAULT '',
                    accent_color TEXT NULL,
                    pinned_skill INTEGER NULL,
                    hide_adventure_log BOOLEAN NOT NULL DEFAULT FALSE,
                    hide_from_leaderboards BOOLEAN NOT NULL DEFAULT FALSE,
                    dm_goals BOOLEAN NOT NULL DEFAULT TRUE,
                    dm_events BOOLEAN NOT NULL DEFAULT FALSE,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (guild_id, discord_user_id)
                );
                """,

                // Added later: keeps a member's Discord name in the website's "Who's Online" list from linking to their clan profile.
                """
                ALTER TABLE younglings.member_profile ADD COLUMN IF NOT EXISTS hide_discord_link BOOLEAN NOT NULL DEFAULT FALSE;
                """,

                """
                CREATE TABLE IF NOT EXISTS younglings.member_goal (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    discord_user_id BIGINT NOT NULL,
                    rsn TEXT NOT NULL,
                    skill_id INTEGER NOT NULL,
                    target_level INTEGER NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    achieved_at TIMESTAMPTZ NULL,
                    UNIQUE (guild_id, discord_user_id, skill_id, target_level)
                );
                """,

                // The roles members may add to / remove from themselves on the website. An admin picks them in
                // the dashboard; the bot re-checks each one is safe every time a member toggles it.
                """
                CREATE TABLE IF NOT EXISTS younglings.self_role (
                    guild_id BIGINT NOT NULL,
                    role_id BIGINT NOT NULL,
                    label TEXT NULL,
                    description TEXT NULL,
                    position INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (guild_id, role_id)
                );
                """,

                // One reminder per event per person, remembered across restarts so a reboot never double-DMs.
                """
                CREATE TABLE IF NOT EXISTS younglings.event_reminder (
                    event_id BIGINT NOT NULL,
                    discord_user_id BIGINT NOT NULL,
                    sent_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (event_id, discord_user_id)
                );
                """
        ));
    }

    // ---------- profile settings ----------

    public record Profile(long guildId, long userId, String bio, String accentColor, Integer pinnedSkill, boolean hideAdventureLog,
                          boolean hideFromLeaderboards, boolean dmGoals, boolean dmEvents, boolean hideDiscordLink) {
        public static Profile defaults(long guildId, long userId) {
            return new Profile(guildId, userId, "", null, null, false, false, true, false, false);
        }
    }

    public Profile getProfile(long guildId, long userId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("""
                     SELECT bio, accent_color, pinned_skill, hide_adventure_log, hide_from_leaderboards, dm_goals, dm_events, hide_discord_link
                     FROM younglings.member_profile WHERE guild_id = ? AND discord_user_id = ?""")) {
            s.setLong(1, guildId);
            s.setLong(2, userId);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) return Profile.defaults(guildId, userId);
                return new Profile(guildId, userId, rs.getString("bio"), rs.getString("accent_color"), (Integer) rs.getObject("pinned_skill"),
                        rs.getBoolean("hide_adventure_log"), rs.getBoolean("hide_from_leaderboards"), rs.getBoolean("dm_goals"), rs.getBoolean("dm_events"), rs.getBoolean("hide_discord_link"));
            }
        } catch (SQLException e) {
            throw fail("read a member profile", e);
        }
    }

    public void saveProfile(Profile p) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("""
                     INSERT INTO younglings.member_profile (guild_id, discord_user_id, bio, accent_color, pinned_skill, hide_adventure_log,
                         hide_from_leaderboards, dm_goals, dm_events, hide_discord_link, updated_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
                     ON CONFLICT (guild_id, discord_user_id) DO UPDATE SET bio = EXCLUDED.bio, accent_color = EXCLUDED.accent_color,
                         pinned_skill = EXCLUDED.pinned_skill, hide_adventure_log = EXCLUDED.hide_adventure_log,
                         hide_from_leaderboards = EXCLUDED.hide_from_leaderboards, dm_goals = EXCLUDED.dm_goals,
                         dm_events = EXCLUDED.dm_events, hide_discord_link = EXCLUDED.hide_discord_link, updated_at = NOW()""")) {
            s.setLong(1, p.guildId());
            s.setLong(2, p.userId());
            s.setString(3, p.bio());
            if (p.accentColor() == null) s.setNull(4, Types.VARCHAR); else s.setString(4, p.accentColor());
            if (p.pinnedSkill() == null) s.setNull(5, Types.INTEGER); else s.setInt(5, p.pinnedSkill());
            s.setBoolean(6, p.hideAdventureLog());
            s.setBoolean(7, p.hideFromLeaderboards());
            s.setBoolean(8, p.dmGoals());
            s.setBoolean(9, p.dmEvents());
            s.setBoolean(10, p.hideDiscordLink());
            s.executeUpdate();
        } catch (SQLException e) {
            throw fail("save a member profile", e);
        }
    }

    /** Public profile bits for one RuneScape name: whoever has it linked, and what they chose to show. {@code null} if nobody's linked it. */
    public Profile getProfileForRsn(long guildId, String rsn) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("""
                     SELECT p.discord_user_id, p.bio, p.accent_color, p.pinned_skill, p.hide_adventure_log, p.hide_from_leaderboards, p.dm_goals, p.dm_events, p.hide_discord_link
                     FROM younglings.player_link l JOIN younglings.member_profile p ON p.guild_id = l.guild_id AND p.discord_user_id = l.discord_user_id
                     WHERE l.guild_id = ? AND LOWER(l.rsn) = LOWER(?)""")) {
            s.setLong(1, guildId);
            s.setString(2, rsn);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) return null;
                return new Profile(guildId, rs.getLong("discord_user_id"), rs.getString("bio"), rs.getString("accent_color"), (Integer) rs.getObject("pinned_skill"),
                        rs.getBoolean("hide_adventure_log"), rs.getBoolean("hide_from_leaderboards"), rs.getBoolean("dm_goals"), rs.getBoolean("dm_events"), rs.getBoolean("hide_discord_link"));
            }
        } catch (SQLException e) {
            throw fail("read a profile by name", e);
        }
    }

    /** Lower-cased RuneScape names whose owners hid them from rankings ({@code leaderboards = true}) or hid their adventure log. */
    public Set<String> hiddenRsns(long guildId, boolean leaderboards) {
        String column = leaderboards ? "hide_from_leaderboards" : "hide_adventure_log";
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT LOWER(l.rsn) AS rsn FROM younglings.player_link l JOIN younglings.member_profile p ON p.guild_id = l.guild_id AND p.discord_user_id = l.discord_user_id WHERE l.guild_id = ? AND p." + column)) {
            s.setLong(1, guildId);
            Set<String> hidden = new HashSet<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) hidden.add(rs.getString("rsn"));
            }
            return hidden;
        } catch (SQLException e) {
            throw fail("read hidden members", e);
        }
    }

    /** Discord users who asked not to be linked to their clan profile from the Who's Online list. */
    public Set<Long> hiddenDiscordUsers(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT discord_user_id FROM younglings.member_profile WHERE guild_id = ? AND hide_discord_link")) {
            s.setLong(1, guildId);
            Set<Long> hidden = new HashSet<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) hidden.add(rs.getLong(1));
            }
            return hidden;
        } catch (SQLException e) {
            throw fail("read hidden discord links", e);
        }
    }

    // ---------- goals ----------

    public record Goal(long id, long guildId, long userId, String rsn, int skillId, int targetLevel, OffsetDateTime createdAt, OffsetDateTime achievedAt) {}

    private static Goal mapGoal(ResultSet rs) throws SQLException {
        return new Goal(rs.getLong("id"), rs.getLong("guild_id"), rs.getLong("discord_user_id"), rs.getString("rsn"), rs.getInt("skill_id"),
                rs.getInt("target_level"), rs.getObject("created_at", OffsetDateTime.class), rs.getObject("achieved_at", OffsetDateTime.class));
    }

    public List<Goal> goalsFor(long guildId, long userId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT * FROM younglings.member_goal WHERE guild_id = ? AND discord_user_id = ? ORDER BY achieved_at IS NOT NULL, created_at DESC")) {
            s.setLong(1, guildId);
            s.setLong(2, userId);
            List<Goal> goals = new ArrayList<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) goals.add(mapGoal(rs));
            }
            return goals;
        } catch (SQLException e) {
            throw fail("read goals", e);
        }
    }

    /** @return the new goal's id, or {@code -1} if that exact goal already exists */
    public long addGoal(long guildId, long userId, String rsn, int skillId, int targetLevel) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("""
                     INSERT INTO younglings.member_goal (guild_id, discord_user_id, rsn, skill_id, target_level) VALUES (?, ?, ?, ?, ?)
                     ON CONFLICT DO NOTHING RETURNING id""")) {
            s.setLong(1, guildId);
            s.setLong(2, userId);
            s.setString(3, rsn);
            s.setInt(4, skillId);
            s.setInt(5, targetLevel);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        } catch (SQLException e) {
            throw fail("add a goal", e);
        }
    }

    public boolean deleteGoal(long guildId, long userId, long goalId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("DELETE FROM younglings.member_goal WHERE id = ? AND guild_id = ? AND discord_user_id = ?")) {
            s.setLong(1, goalId);
            s.setLong(2, guildId);
            s.setLong(3, userId);
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            throw fail("delete a goal", e);
        }
    }

    public List<Goal> pendingGoals() {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT * FROM younglings.member_goal WHERE achieved_at IS NULL");
             ResultSet rs = s.executeQuery()) {
            List<Goal> goals = new ArrayList<>();
            while (rs.next()) goals.add(mapGoal(rs));
            return goals;
        } catch (SQLException e) {
            throw fail("read pending goals", e);
        }
    }

    /** @return {@code true} if this call is the one that marked it achieved (so only one caller sends the congratulation) */
    public boolean markAchieved(long goalId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("UPDATE younglings.member_goal SET achieved_at = NOW() WHERE id = ? AND achieved_at IS NULL")) {
            s.setLong(1, goalId);
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            throw fail("mark a goal achieved", e);
        }
    }

    // ---------- self-assignable roles ----------

    public record SelfRole(long roleId, String label, String description, int position) {}

    public List<SelfRole> selfRoles(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT role_id, label, description, position FROM younglings.self_role WHERE guild_id = ? ORDER BY position, role_id")) {
            s.setLong(1, guildId);
            List<SelfRole> roles = new ArrayList<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) roles.add(new SelfRole(rs.getLong("role_id"), rs.getString("label"), rs.getString("description"), rs.getInt("position")));
            }
            return roles;
        } catch (SQLException e) {
            throw fail("read self-assignable roles", e);
        }
    }

    /** Replaces the server's whole list in one transaction. */
    public void replaceSelfRoles(long guildId, List<SelfRole> roles) {
        try (Connection c = connectionSupplier.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement delete = c.prepareStatement("DELETE FROM younglings.self_role WHERE guild_id = ?")) {
                    delete.setLong(1, guildId);
                    delete.executeUpdate();
                }
                try (PreparedStatement insert = c.prepareStatement("INSERT INTO younglings.self_role (guild_id, role_id, label, description, position) VALUES (?, ?, ?, ?, ?)")) {
                    int position = 0;
                    for (SelfRole role : roles) {
                        insert.setLong(1, guildId);
                        insert.setLong(2, role.roleId());
                        insert.setString(3, role.label());
                        insert.setString(4, role.description());
                        insert.setInt(5, position++);
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw fail("save self-assignable roles", e);
        }
    }

    // ---------- event reminders ----------

    /** @return {@code true} if this call recorded the reminder (so the caller should send it) */
    public boolean claimReminder(long eventId, long userId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("INSERT INTO younglings.event_reminder (event_id, discord_user_id) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
            s.setLong(1, eventId);
            s.setLong(2, userId);
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            throw fail("record an event reminder", e);
        }
    }

    /** Everyone in the server who asked for event reminders. */
    public List<Long> usersWantingEventReminders(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT discord_user_id FROM younglings.member_profile WHERE guild_id = ? AND dm_events")) {
            s.setLong(1, guildId);
            List<Long> users = new ArrayList<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) users.add(rs.getLong(1));
            }
            return users;
        } catch (SQLException e) {
            throw fail("read reminder subscribers", e);
        }
    }

    /** Whether a member wants goal-reached DMs (on by default). */
    public boolean wantsGoalDms(long guildId, long userId) {
        return getProfile(guildId, userId).dmGoals();
    }

    private static RuntimeException fail(String action, SQLException e) {
        log.error("Failed to {}", action, e);
        return new RuntimeException("Failed to " + action, e);
    }
}
