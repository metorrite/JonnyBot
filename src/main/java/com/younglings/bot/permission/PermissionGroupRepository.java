package com.younglings.bot.permission;

import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@BService
public class PermissionGroupRepository {
    private static final Logger log = LoggerFactory.getLogger(PermissionGroupRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public PermissionGroupRepository(ConnectionSupplier connectionSupplier, PermissionDatabaseInitializer initializer) {
        this.connectionSupplier = connectionSupplier;
    }

    /** Every group of a server, the built-in ones first (admin, support, developer) and then the server's own in the order they were made. */
    public List<PermissionGroup> list(long guildId) {
        String sql = """
                SELECT g.group_id, g.group_key, g.name, g.builtin, g.include_higher, r.role_id
                FROM younglings.permission_group g
                LEFT JOIN younglings.permission_group_role r ON r.group_id = g.group_id
                WHERE g.guild_id = ?
                ORDER BY g.builtin DESC, CASE g.group_key WHEN 'admin' THEN 0 WHEN 'support' THEN 1 WHEN 'developer' THEN 2 ELSE 3 END, g.group_id, r.role_id
                """;
        Map<Long, PermissionGroup> groups = new LinkedHashMap<>();
        Map<Long, List<Long>> roles = new LinkedHashMap<>();
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, guildId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    long groupId = rs.getLong("group_id");
                    roles.computeIfAbsent(groupId, id -> new ArrayList<>());
                    groups.computeIfAbsent(groupId, id -> {
                        try {
                            return new PermissionGroup(groupId, guildId, rs.getString("group_key"), rs.getString("name"),
                                    rs.getBoolean("builtin"), rs.getBoolean("include_higher"), List.of());
                        } catch (SQLException e) {
                            throw new IllegalStateException(e);
                        }
                    });
                    long roleId = rs.getLong("role_id");
                    if (!rs.wasNull()) roles.get(groupId).add(roleId);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to read the permission groups of guild {}", guildId, e);
            throw new RuntimeException("Failed to read permission groups", e);
        }
        List<PermissionGroup> result = new ArrayList<>();
        for (var entry : groups.entrySet()) {
            PermissionGroup g = entry.getValue();
            result.add(new PermissionGroup(g.groupId(), g.guildId(), g.key(), g.name(), g.builtin(), g.includeHigher(), List.copyOf(roles.get(entry.getKey()))));
        }
        return result;
    }

    /**
     * Makes the server's groups exactly {@code target}, in one transaction: groups are matched by key, new keys are created,
     * and any group of the server's own that is missing from {@code target} is deleted (a built-in one never is).
     */
    public void replaceAll(long guildId, List<PermissionGroup> target) {
        try (Connection connection = connectionSupplier.getConnection()) {
            connection.setAutoCommit(false);
            try {
                for (PermissionGroup group : target) upsert(connection, guildId, group);
                deleteMissing(connection, guildId, target.stream().map(PermissionGroup::key).toList());
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            log.error("Failed to save the permission groups of guild {}", guildId, e);
            throw new RuntimeException("Failed to save permission groups", e);
        }
    }

    /** Creates the built-in groups that don't exist yet (never touching ones that do), so two servers starting up at once can't clash. */
    public void createMissing(long guildId, List<PermissionGroup> defaults) {
        try (Connection connection = connectionSupplier.getConnection()) {
            connection.setAutoCommit(false);
            try {
                for (PermissionGroup group : defaults) {
                    long groupId;
                    try (PreparedStatement insert = connection.prepareStatement("""
                            INSERT INTO younglings.permission_group (guild_id, group_key, name, builtin, include_higher)
                            VALUES (?, ?, ?, ?, ?) ON CONFLICT (guild_id, group_key) DO NOTHING RETURNING group_id
                            """)) {
                        insert.setLong(1, guildId);
                        insert.setString(2, group.key());
                        insert.setString(3, group.name());
                        insert.setBoolean(4, group.builtin());
                        insert.setBoolean(5, group.includeHigher());
                        try (ResultSet rs = insert.executeQuery()) {
                            if (!rs.next()) continue; // someone else created it first
                            groupId = rs.getLong(1);
                        }
                    }
                    writeRoles(connection, groupId, group.roleIds());
                }
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            log.error("Failed to create the built-in permission groups of guild {}", guildId, e);
            throw new RuntimeException("Failed to create permission groups", e);
        }
    }

    private void upsert(Connection connection, long guildId, PermissionGroup group) throws SQLException {
        long groupId;
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO younglings.permission_group (guild_id, group_key, name, builtin, include_higher)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (guild_id, group_key) DO UPDATE SET name = EXCLUDED.name, include_higher = EXCLUDED.include_higher
                RETURNING group_id
                """)) {
            statement.setLong(1, guildId);
            statement.setString(2, group.key());
            statement.setString(3, group.name());
            statement.setBoolean(4, group.builtin());
            statement.setBoolean(5, group.includeHigher());
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                groupId = rs.getLong(1);
            }
        }
        try (PreparedStatement clear = connection.prepareStatement("DELETE FROM younglings.permission_group_role WHERE group_id = ?")) {
            clear.setLong(1, groupId);
            clear.executeUpdate();
        }
        writeRoles(connection, groupId, group.roleIds());
    }

    private void writeRoles(Connection connection, long groupId, List<Long> roleIds) throws SQLException {
        if (roleIds.isEmpty()) return;
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO younglings.permission_group_role (group_id, role_id) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
            for (long roleId : Set.copyOf(roleIds)) {
                statement.setLong(1, groupId);
                statement.setLong(2, roleId);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void deleteMissing(Connection connection, long guildId, List<String> keepKeys) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM younglings.permission_group WHERE guild_id = ? AND builtin = FALSE AND NOT (group_key = ANY (?))")) {
            statement.setLong(1, guildId);
            statement.setArray(2, connection.createArrayOf("text", keepKeys.toArray()));
            statement.executeUpdate();
        }
    }
}
