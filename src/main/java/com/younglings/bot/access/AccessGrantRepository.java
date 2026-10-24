package com.younglings.bot.access;

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
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The restricted-content access list. Rows that name an area or level the code no longer knows are skipped, so they can never let anyone in. */
@BService
public class AccessGrantRepository {
    private static final Logger log = LoggerFactory.getLogger(AccessGrantRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public AccessGrantRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "access grants", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.access_grant (
                    id BIGSERIAL PRIMARY KEY,
                    area TEXT NOT NULL,
                    user_id BIGINT NULL,
                    guild_id BIGINT NULL,
                    min_level TEXT NULL,
                    note TEXT NOT NULL DEFAULT '',
                    granted_by BIGINT NOT NULL,
                    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    expires_at TIMESTAMPTZ NULL,
                    CHECK ((user_id IS NOT NULL AND guild_id IS NULL AND min_level IS NULL)
                        OR (user_id IS NULL AND guild_id IS NOT NULL AND min_level IS NOT NULL))
                );
                """,
                // Who a user grant's person is, as Discord last said: the list shows it without asking Discord again.
                "ALTER TABLE younglings.access_grant ADD COLUMN IF NOT EXISTS user_name TEXT NULL;",
                "ALTER TABLE younglings.access_grant ADD COLUMN IF NOT EXISTS avatar_url TEXT NULL;"
        ));
    }

    public List<AccessGrant> all() {
        List<AccessGrant> grants = new ArrayList<>();
        String sql = "SELECT id, area, user_id, guild_id, min_level, note, granted_by, granted_at, expires_at, user_name, avatar_url FROM younglings.access_grant ORDER BY id";
        try (Connection connection = connectionSupplier.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                Optional<AccessArea> area = AccessArea.ofKey(rs.getString("area"));
                long userId = rs.getLong("user_id");
                boolean hasUser = !rs.wasNull();
                long guildId = rs.getLong("guild_id");
                boolean hasGuild = !rs.wasNull();
                String levelKey = rs.getString("min_level");
                Optional<AccessLevel> level = levelKey == null ? Optional.empty() : AccessLevel.ofKey(levelKey);
                if (area.isEmpty() || (hasUser == hasGuild) || (hasGuild && level.isEmpty())) continue; // unknown or malformed: lets nobody in
                Timestamp expires = rs.getTimestamp("expires_at");
                grants.add(new AccessGrant(rs.getLong("id"), area.get(), hasUser ? userId : null, hasGuild ? guildId : null, hasGuild ? level.get() : null,
                        rs.getString("note"), rs.getLong("granted_by"), rs.getTimestamp("granted_at").toInstant(), expires == null ? null : expires.toInstant(),
                        rs.getString("user_name"), rs.getString("avatar_url")));
            }
            return grants;
        } catch (SQLException e) {
            log.error("Failed to read the access list", e);
            throw new RuntimeException("Failed to read the access list", e);
        }
    }

    /** Adds a grant for one user ({@code guildId}/{@code level} null) or for a server and level ({@code userId} null). Returns its id. */
    public long add(AccessArea area, Long userId, Long guildId, AccessLevel level, String note, long grantedBy, Instant expiresAt) {
        String sql = "INSERT INTO younglings.access_grant (area, user_id, guild_id, min_level, note, granted_by, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id";
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, area.key());
            if (userId == null) statement.setNull(2, Types.BIGINT); else statement.setLong(2, userId);
            if (guildId == null) statement.setNull(3, Types.BIGINT); else statement.setLong(3, guildId);
            if (level == null) statement.setNull(4, Types.VARCHAR); else statement.setString(4, level.key());
            statement.setString(5, note);
            statement.setLong(6, grantedBy);
            if (expiresAt == null) statement.setNull(7, Types.TIMESTAMP_WITH_TIMEZONE); else statement.setTimestamp(7, Timestamp.from(expiresAt));
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            log.error("Failed to add an access grant", e);
            throw new RuntimeException("Failed to add the access grant", e);
        }
    }

    /** Remembers who a user is on all of their grants (what Discord just said), so the list can name them without asking. */
    public void rememberUser(long userId, String userName, String avatarUrl) {
        String sql = "UPDATE younglings.access_grant SET user_name = ?, avatar_url = ? WHERE user_id = ? "
                + "AND (user_name IS DISTINCT FROM ? OR avatar_url IS DISTINCT FROM ?)";
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, userName);
            statement.setString(2, avatarUrl);
            statement.setLong(3, userId);
            statement.setString(4, userName);
            statement.setString(5, avatarUrl);
            statement.executeUpdate();
        } catch (SQLException e) {
            log.warn("Failed to remember the name of access-grant user {}", userId, e);
        }
    }

    public boolean delete(long id) {
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM younglings.access_grant WHERE id = ?")) {
            statement.setLong(1, id);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to delete access grant {}", id, e);
            throw new RuntimeException("Failed to delete the access grant", e);
        }
    }
}
