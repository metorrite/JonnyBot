package com.younglings.bot.hub;

import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@BService
public class HubRepository {
    private static final Logger log = LoggerFactory.getLogger(HubRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public HubRepository(ConnectionSupplier connectionSupplier, HubDatabaseInitializer initializer) {
        this.connectionSupplier = connectionSupplier;
    }

    /** Every Hub setting a server has saved, by command key. Commands it never changed have no entry. */
    public Map<String, HubSettings> all(long guildId) {
        String sql = "SELECT command_key, enabled, custom_access, allowed_refs, channel_ids, extras FROM younglings.hub_command_setting WHERE guild_id = ?";
        Map<String, HubSettings> result = new LinkedHashMap<>();
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, guildId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    List<String> refs = new ArrayList<>();
                    Array refArray = rs.getArray("allowed_refs");
                    for (Object o : (Object[]) refArray.getArray()) refs.add((String) o);
                    List<Long> channels = new ArrayList<>();
                    Array channelArray = rs.getArray("channel_ids");
                    for (Object o : (Object[]) channelArray.getArray()) channels.add((Long) o);
                    String key = rs.getString("command_key");
                    result.put(key, new HubSettings(guildId, key, rs.getBoolean("enabled"), rs.getBoolean("custom_access"),
                            List.copyOf(refs), List.copyOf(channels), rs.getString("extras")));
                }
            }
            return result;
        } catch (SQLException e) {
            log.error("Failed to read the Hub settings of guild {}", guildId, e);
            throw new RuntimeException("Failed to read Hub settings", e);
        }
    }

    public void save(HubSettings settings) {
        String sql = """
                INSERT INTO younglings.hub_command_setting (guild_id, command_key, enabled, custom_access, allowed_refs, channel_ids, extras)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (guild_id, command_key) DO UPDATE SET enabled = EXCLUDED.enabled, custom_access = EXCLUDED.custom_access,
                    allowed_refs = EXCLUDED.allowed_refs, channel_ids = EXCLUDED.channel_ids, extras = EXCLUDED.extras
                """;
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, settings.guildId());
            statement.setString(2, settings.commandKey());
            statement.setBoolean(3, settings.enabled());
            statement.setBoolean(4, settings.customAccess());
            statement.setArray(5, connection.createArrayOf("text", settings.allowedRefs().toArray()));
            statement.setArray(6, connection.createArrayOf("bigint", settings.channelIds().toArray()));
            statement.setString(7, settings.extras());
            statement.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to save the Hub settings of guild {} for '{}'", settings.guildId(), settings.commandKey(), e);
            throw new RuntimeException("Failed to save Hub settings", e);
        }
    }
}
