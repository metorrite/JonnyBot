package com.younglings.bot.premium;

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

/** The servers on the premium polling plan. */
@BService
public class PremiumGuildRepository {
    private static final Logger log = LoggerFactory.getLogger(PremiumGuildRepository.class);

    public record PremiumGuild(long guildId, String label) {}

    private final ConnectionSupplier connectionSupplier;

    public PremiumGuildRepository(ConnectionSupplier connectionSupplier, PremiumDatabaseInitializer initializer) {
        this.connectionSupplier = connectionSupplier;
    }

    public List<PremiumGuild> all() {
        List<PremiumGuild> guilds = new ArrayList<>();
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT guild_id, label FROM younglings.premium_guild ORDER BY added_at, guild_id");
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) guilds.add(new PremiumGuild(rs.getLong("guild_id"), rs.getString("label")));
            return guilds;
        } catch (SQLException e) {
            log.error("Failed to read the premium guilds", e);
            throw new RuntimeException("Failed to read the premium guilds", e);
        }
    }

    public boolean isPremium(long guildId) {
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM younglings.premium_guild WHERE guild_id = ?")) {
            statement.setLong(1, guildId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.error("Failed to check whether guild {} is premium", guildId, e);
            return false;
        }
    }

    /** Adds the server, or changes its label if it is already there. */
    public void upsert(long guildId, String label, long addedBy) {
        String sql = "INSERT INTO younglings.premium_guild (guild_id, label, added_by) VALUES (?, ?, ?) ON CONFLICT (guild_id) DO UPDATE SET label = EXCLUDED.label";
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, guildId);
            statement.setString(2, label);
            statement.setLong(3, addedBy);
            statement.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to save premium guild {}", guildId, e);
            throw new RuntimeException("Failed to save the premium guild", e);
        }
    }

    public boolean delete(long guildId) {
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM younglings.premium_guild WHERE guild_id = ?")) {
            statement.setLong(1, guildId);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to delete premium guild {}", guildId, e);
            throw new RuntimeException("Failed to delete the premium guild", e);
        }
    }
}
