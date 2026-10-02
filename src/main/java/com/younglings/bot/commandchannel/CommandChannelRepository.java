package com.younglings.bot.commandchannel;

import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Backs the command-only-channels feature — see {@link CommandChannelService}. Every mutation is scoped by {@code guild_id} so one guild's panel can never touch another's rows. */
@BService
public class CommandChannelRepository {
    private static final Logger log = LoggerFactory.getLogger(CommandChannelRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public CommandChannelRepository(ConnectionSupplier connectionSupplier, CommandChannelDatabaseInitializer initializer) {
        this.connectionSupplier = connectionSupplier;
    }

    /** Every group in the guild with its channels and roles, ordered by name. */
    public List<CommandChannelGroup> getGroups(long guildId) {
        try (Connection connection = connectionSupplier.getConnection()) {
            Map<Long, Set<Long>> channels = new HashMap<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT group_id, channel_id FROM younglings.command_channel_channel WHERE guild_id = ?")) {
                ps.setLong(1, guildId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) channels.computeIfAbsent(rs.getLong(1), k -> new HashSet<>()).add(rs.getLong(2));
                }
            }

            Map<Long, Set<Long>> applyRoles = new HashMap<>();
            Map<Long, Set<Long>> exemptRoles = new HashMap<>();
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT r.group_id, r.role_id, r.mode FROM younglings.command_channel_role r
                    JOIN younglings.command_channel_group g ON g.id = r.group_id
                    WHERE g.guild_id = ?
                    """)) {
                ps.setLong(1, guildId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<Long, Set<Long>> target = rs.getString(3).equals("APPLY") ? applyRoles : exemptRoles;
                        target.computeIfAbsent(rs.getLong(1), k -> new HashSet<>()).add(rs.getLong(2));
                    }
                }
            }

            List<CommandChannelGroup> groups = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT id, guild_id, name, custom_message, enabled, apply_below_role_id, exempt_from_role_id
                    FROM younglings.command_channel_group WHERE guild_id = ? ORDER BY LOWER(name)
                    """)) {
                ps.setLong(1, guildId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long id = rs.getLong("id");
                        groups.add(new CommandChannelGroup(id, rs.getLong("guild_id"), rs.getString("name"),
                                rs.getString("custom_message"), rs.getBoolean("enabled"),
                                (Long) rs.getObject("apply_below_role_id"), (Long) rs.getObject("exempt_from_role_id"),
                                Set.copyOf(channels.getOrDefault(id, Set.of())),
                                Set.copyOf(applyRoles.getOrDefault(id, Set.of())),
                                Set.copyOf(exemptRoles.getOrDefault(id, Set.of()))));
                    }
                }
            }
            return groups;

        } catch (SQLException e) {
            log.error("Failed to load command-channel groups for guild {}", guildId, e);
            throw new RuntimeException("Failed to load command-channel groups", e);
        }
    }

    public Optional<CommandChannelGroup> getGroup(long guildId, long groupId) {
        return getGroups(guildId).stream().filter(g -> g.id() == groupId).findFirst();
    }

    /** Creates the group if no group of that name (case-insensitive) exists yet; a no-op otherwise. Used to seed the fixed "Default" and "Custom" groups. */
    public void ensureGroup(long guildId, String name, String customMessage) {
        String sql = """
                INSERT INTO younglings.command_channel_group (guild_id, name, custom_message)
                VALUES (?, ?, ?)
                ON CONFLICT (guild_id, LOWER(name)) DO NOTHING
                """;
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, guildId);
            ps.setString(2, name);
            ps.setString(3, customMessage);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to ensure command-channel group '{}' for guild {}", name, guildId, e);
            throw new RuntimeException("Failed to ensure command-channel group", e);
        }
    }

    /** The new group's id, or {@code -1} if a group with that name (case-insensitive) already exists. */
    public long createGroup(long guildId, String name) {
        String sql = """
                INSERT INTO younglings.command_channel_group (guild_id, name)
                VALUES (?, ?)
                ON CONFLICT (guild_id, LOWER(name)) DO NOTHING
                RETURNING id
                """;
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, guildId);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        } catch (SQLException e) {
            log.error("Failed to create command-channel group '{}' for guild {}", name, guildId, e);
            throw new RuntimeException("Failed to create command-channel group", e);
        }
    }

    public void deleteGroup(long guildId, long groupId) {
        update("DELETE FROM younglings.command_channel_group WHERE id = ? AND guild_id = ?", groupId, guildId);
    }

    public void setEnabled(long guildId, long groupId, boolean enabled) {
        run("UPDATE younglings.command_channel_group SET enabled = ? WHERE id = ? AND guild_id = ?",
                ps -> { ps.setBoolean(1, enabled); ps.setLong(2, groupId); ps.setLong(3, guildId); });
    }

    /** {@code null} (or blank) clears it back to the built-in default. */
    public void setCustomMessage(long guildId, long groupId, String message) {
        String value = message == null || message.isBlank() ? null : message.trim();
        run("UPDATE younglings.command_channel_group SET custom_message = ? WHERE id = ? AND guild_id = ?",
                ps -> { ps.setString(1, value); ps.setLong(2, groupId); ps.setLong(3, guildId); });
    }

    public void setApplyBelowRole(long guildId, long groupId, Long roleId) {
        run("UPDATE younglings.command_channel_group SET apply_below_role_id = ? WHERE id = ? AND guild_id = ?",
                ps -> { ps.setObject(1, roleId); ps.setLong(2, groupId); ps.setLong(3, guildId); });
    }

    public void setExemptFromRole(long guildId, long groupId, Long roleId) {
        run("UPDATE younglings.command_channel_group SET exempt_from_role_id = ? WHERE id = ? AND guild_id = ?",
                ps -> { ps.setObject(1, roleId); ps.setLong(2, groupId); ps.setLong(3, guildId); });
    }

    /** The id of the group that already owns this channel in this guild, or empty. */
    public Optional<Long> findGroupIdForChannel(long guildId, long channelId) {
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT group_id FROM younglings.command_channel_channel WHERE guild_id = ? AND channel_id = ?")) {
            ps.setLong(1, guildId);
            ps.setLong(2, channelId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            log.error("Failed to look up command-channel group for channel {}", channelId, e);
            throw new RuntimeException("Failed to look up command-channel group", e);
        }
    }

    /** {@code false} if the channel already belongs to some group (a channel can only be in one). */
    public boolean addChannel(long guildId, long groupId, long channelId) {
        String sql = """
                INSERT INTO younglings.command_channel_channel (group_id, guild_id, channel_id)
                SELECT g.id, g.guild_id, ? FROM younglings.command_channel_group g WHERE g.id = ? AND g.guild_id = ?
                ON CONFLICT (guild_id, channel_id) DO NOTHING
                """;
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, channelId);
            ps.setLong(2, groupId);
            ps.setLong(3, guildId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to add channel {} to command-channel group {}", channelId, groupId, e);
            throw new RuntimeException("Failed to add command-channel channel", e);
        }
    }

    public void removeChannel(long guildId, long groupId, long channelId) {
        run("DELETE FROM younglings.command_channel_channel WHERE group_id = ? AND guild_id = ? AND channel_id = ?",
                ps -> { ps.setLong(1, groupId); ps.setLong(2, guildId); ps.setLong(3, channelId); });
    }

    /**
     * Replaces this group's whole {@code mode} ("APPLY" or "EXEMPT") role set with {@code roleIds}. A
     * role that was in the *other* set moves to this one (the primary key allows a role to be only one
     * or the other), so the most recent change always wins. One transaction — a failure leaves the old
     * set intact rather than half-replaced.
     */
    public void setRoles(long guildId, long groupId, String mode, Set<Long> roleIds) {
        try (Connection connection = connectionSupplier.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement owns = connection.prepareStatement(
                        "SELECT 1 FROM younglings.command_channel_group WHERE id = ? AND guild_id = ?")) {
                    owns.setLong(1, groupId);
                    owns.setLong(2, guildId);
                    try (ResultSet rs = owns.executeQuery()) {
                        if (!rs.next()) return; // not this guild's group
                    }
                }
                try (PreparedStatement clear = connection.prepareStatement(
                        "DELETE FROM younglings.command_channel_role WHERE group_id = ? AND mode = ?")) {
                    clear.setLong(1, groupId);
                    clear.setString(2, mode);
                    clear.executeUpdate();
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO younglings.command_channel_role (group_id, role_id, mode) VALUES (?, ?, ?)
                        ON CONFLICT (group_id, role_id) DO UPDATE SET mode = EXCLUDED.mode
                        """)) {
                    for (long roleId : roleIds) {
                        insert.setLong(1, groupId);
                        insert.setLong(2, roleId);
                        insert.setString(3, mode);
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            log.error("Failed to set {} roles for command-channel group {}", mode, groupId, e);
            throw new RuntimeException("Failed to set command-channel roles", e);
        }
    }

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private void update(String sql, long first, long second) {
        run(sql, ps -> { ps.setLong(1, first); ps.setLong(2, second); });
    }

    private void run(String sql, Binder binder) {
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            binder.bind(ps);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Command-channel statement failed: {}", sql, e);
            throw new RuntimeException("Command-channel statement failed", e);
        }
    }
}
