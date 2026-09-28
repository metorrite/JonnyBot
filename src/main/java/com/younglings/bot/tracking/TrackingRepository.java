package com.younglings.bot.tracking;

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
public class TrackingRepository {
    private static final Logger log = LoggerFactory.getLogger(TrackingRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public TrackingRepository(ConnectionSupplier connectionSupplier, TrackingDatabaseInitializer initializer) {
        this.connectionSupplier = connectionSupplier;
    }

    public record Destination(long id, long channelId) {}

    /** {@code true} if no row exists yet — a group with no destinations doesn't post anywhere regardless, so "never configured" defaults to on. */
    public boolean isEnabled(long guildId, String groupKey) {
        String sql = "SELECT enabled FROM younglings.tracking_group_config WHERE guild_id = ? AND group_key = ?";

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setString(2, groupKey);

            try (ResultSet rs = statement.executeQuery()) {
                return !rs.next() || rs.getBoolean("enabled");
            }

        } catch (SQLException e) {
            log.error("Failed to read tracking group enabled state for guild {} group {}", guildId, groupKey, e);
            throw new RuntimeException("Failed to read tracking group enabled state", e);
        }
    }

    public void setEnabled(long guildId, String groupKey, boolean enabled) {
        String sql = """
                INSERT INTO younglings.tracking_group_config (guild_id, group_key, enabled)
                VALUES (?, ?, ?)
                ON CONFLICT (guild_id, group_key) DO UPDATE SET enabled = EXCLUDED.enabled
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setString(2, groupKey);
            statement.setBoolean(3, enabled);
            statement.executeUpdate();

        } catch (SQLException e) {
            log.error("Failed to set tracking group enabled state for guild {} group {}", guildId, groupKey, e);
            throw new RuntimeException("Failed to set tracking group enabled state", e);
        }
    }

    public List<Destination> getDestinations(long guildId, String groupKey) {
        String sql = "SELECT id, channel_id FROM younglings.tracking_destination WHERE guild_id = ? AND group_key = ? ORDER BY id";

        List<Destination> results = new ArrayList<>();
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setString(2, groupKey);

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) results.add(new Destination(rs.getLong("id"), rs.getLong("channel_id")));
            }
            return results;

        } catch (SQLException e) {
            log.error("Failed to get tracking destinations for guild {} group {}", guildId, groupKey, e);
            throw new RuntimeException("Failed to get tracking destinations", e);
        }
    }

    /** Every configured destination for this guild across every group — {@link TrackingEventRouter} loads this in one shot per guild rather than one query per group. */
    public java.util.Map<String, List<Destination>> getAllDestinations(long guildId) {
        String sql = "SELECT group_key, id, channel_id FROM younglings.tracking_destination WHERE guild_id = ? ORDER BY id";

        java.util.Map<String, List<Destination>> byGroup = new java.util.HashMap<>();
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    byGroup.computeIfAbsent(rs.getString("group_key"), k -> new ArrayList<>())
                            .add(new Destination(rs.getLong("id"), rs.getLong("channel_id")));
                }
            }
            return byGroup;

        } catch (SQLException e) {
            log.error("Failed to get all tracking destinations for guild {}", guildId, e);
            throw new RuntimeException("Failed to get all tracking destinations", e);
        }
    }

    /** No-ops (via the unique constraint) if this exact destination is already configured for this group. */
    public void addDestination(long guildId, String groupKey, long channelId) {
        String sql = """
                INSERT INTO younglings.tracking_destination (guild_id, group_key, channel_id)
                VALUES (?, ?, ?)
                ON CONFLICT (guild_id, group_key, channel_id) DO NOTHING
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setString(2, groupKey);
            statement.setLong(3, channelId);
            statement.executeUpdate();

        } catch (SQLException e) {
            log.error("Failed to add tracking destination for guild {} group {}", guildId, groupKey, e);
            throw new RuntimeException("Failed to add tracking destination", e);
        }
    }

    public void removeDestination(long guildId, long destinationId) {
        String sql = "DELETE FROM younglings.tracking_destination WHERE guild_id = ? AND id = ?";

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setLong(2, destinationId);
            statement.executeUpdate();

        } catch (SQLException e) {
            log.error("Failed to remove tracking destination {} for guild {}", destinationId, guildId, e);
            throw new RuntimeException("Failed to remove tracking destination", e);
        }
    }

    // --- Test posts (the Tracking panel's "Send Test Posts" / "Clear Test Posts" buttons) ---

    public record TestMessage(long id, long channelId, long messageId) {}

    public void recordTestMessage(long guildId, long channelId, long messageId) {
        String sql = "INSERT INTO younglings.tracking_test_message (guild_id, channel_id, message_id) VALUES (?, ?, ?)";

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setLong(2, channelId);
            statement.setLong(3, messageId);
            statement.executeUpdate();

        } catch (SQLException e) {
            log.error("Failed to record test message for guild {}", guildId, e);
            throw new RuntimeException("Failed to record test message", e);
        }
    }

    public List<TestMessage> getTestMessages(long guildId) {
        String sql = "SELECT id, channel_id, message_id FROM younglings.tracking_test_message WHERE guild_id = ?";

        List<TestMessage> results = new ArrayList<>();
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) results.add(new TestMessage(rs.getLong("id"), rs.getLong("channel_id"), rs.getLong("message_id")));
            }
            return results;

        } catch (SQLException e) {
            log.error("Failed to get test messages for guild {}", guildId, e);
            throw new RuntimeException("Failed to get test messages", e);
        }
    }

    public void clearTestMessages(long guildId) {
        String sql = "DELETE FROM younglings.tracking_test_message WHERE guild_id = ?";

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.executeUpdate();

        } catch (SQLException e) {
            log.error("Failed to clear test messages for guild {}", guildId, e);
            throw new RuntimeException("Failed to clear test messages", e);
        }
    }
}
