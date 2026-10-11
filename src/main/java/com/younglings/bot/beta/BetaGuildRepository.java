package com.younglings.bot.beta;

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

    public record BetaGuild(long guildId, String label) {}

    private final ConnectionSupplier connectionSupplier;

    public BetaGuildRepository(ConnectionSupplier connectionSupplier, BetaDatabaseInitializer initializer) {
        this.connectionSupplier = connectionSupplier;
    }

    public List<BetaGuild> all() {
        List<BetaGuild> guilds = new ArrayList<>();
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT guild_id, label FROM younglings.beta_guild ORDER BY added_at, guild_id");
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) guilds.add(new BetaGuild(rs.getLong("guild_id"), rs.getString("label")));
            return guilds;
        } catch (SQLException e) {
            log.error("Failed to read the beta guilds", e);
            throw new RuntimeException("Failed to read the beta guilds", e);
        }
    }

    /** Adds the server, or changes its label if it is already there. */
    public void upsert(long guildId, String label, long addedBy) {
        String sql = "INSERT INTO younglings.beta_guild (guild_id, label, added_by) VALUES (?, ?, ?) ON CONFLICT (guild_id) DO UPDATE SET label = EXCLUDED.label";
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, guildId);
            statement.setString(2, label);
            statement.setLong(3, addedBy);
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
