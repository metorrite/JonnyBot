package com.younglings.bot.beta;

import com.younglings.bot.access.AccessLevel;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@BService
public class BetaGuildRepository {
    private static final Logger log = LoggerFactory.getLogger(BetaGuildRepository.class);

    /** One trusted server. {@code level} is the least a person must be there; null for a stored level the code no longer knows, which lets nobody in. */
    public record BetaGuild(long guildId, String label, AccessLevel level) {}

    private final ConnectionSupplier connectionSupplier;

    public BetaGuildRepository(ConnectionSupplier connectionSupplier, BetaDatabaseInitializer initializer) {
        this.connectionSupplier = connectionSupplier;
    }

    public List<BetaGuild> all() {
        List<BetaGuild> guilds = new ArrayList<>();
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT guild_id, label, min_level FROM younglings.beta_guild ORDER BY added_at, guild_id");
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                AccessLevel level = AccessLevel.ofKey(rs.getString("min_level")).orElse(null);
                guilds.add(new BetaGuild(rs.getLong("guild_id"), rs.getString("label"), level));
            }
            return guilds;
        } catch (SQLException e) {
            log.error("Failed to read the beta guilds", e);
            throw new RuntimeException("Failed to read the beta guilds", e);
        }
    }

    /** Adds the server, or changes its label and level if it is already there. */
    public void upsert(long guildId, String label, AccessLevel level, long addedBy) {
        String sql = "INSERT INTO younglings.beta_guild (guild_id, label, min_level, added_by) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT (guild_id) DO UPDATE SET label = EXCLUDED.label, min_level = EXCLUDED.min_level";
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, guildId);
            statement.setString(2, label);
            statement.setString(3, level.key());
            statement.setLong(4, addedBy);
            statement.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to save beta guild {}", guildId, e);
            throw new RuntimeException("Failed to save the beta guild", e);
        }
    }

    public boolean delete(long guildId) {
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM younglings.beta_guild WHERE guild_id = ?")) {
            statement.setLong(1, guildId);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to delete beta guild {}", guildId, e);
            throw new RuntimeException("Failed to delete the beta guild", e);
        }
    }
}
