package com.younglings.bot.upload;

import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

@BService
public class UploadedImageRepository {
    private static final Logger log = LoggerFactory.getLogger(UploadedImageRepository.class);

    /** What is stored under a token; {@code version} is the upload time in epoch seconds, so an address can change when the picture does. */
    public record Stored(String token, String contentType, byte[] data, long version) {}

    /** What an upload came to. */
    public record Saved(String token, long version) {}

    private final ConnectionSupplier connectionSupplier;

    public UploadedImageRepository(ConnectionSupplier connectionSupplier, UploadedImageDatabaseInitializer initializer) {
        this.connectionSupplier = connectionSupplier;
    }

    /** Stores the picture for this place, replacing whatever was there. The token is kept across replacements; {@code newToken} is used only the first time. */
    public Saved put(long guildId, String scope, String slot, String newToken, String contentType, byte[] data) {
        String sql = """
                INSERT INTO younglings.uploaded_image (guild_id, scope, slot, token, content_type, data)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (guild_id, scope, slot) DO UPDATE SET content_type = EXCLUDED.content_type, data = EXCLUDED.data, updated_at = now()
                RETURNING token, EXTRACT(EPOCH FROM updated_at)::bigint AS version
                """;
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, guildId);
            statement.setString(2, scope);
            statement.setString(3, slot);
            statement.setString(4, newToken);
            statement.setString(5, contentType);
            statement.setBytes(6, data);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return new Saved(rs.getString("token"), rs.getLong("version"));
            }
        } catch (SQLException e) {
            log.error("Failed to store an uploaded image for guild {} ({}/{})", guildId, scope, slot, e);
            throw new RuntimeException("Failed to store the image", e);
        }
    }

    public boolean delete(long guildId, String scope, String slot) {
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM younglings.uploaded_image WHERE guild_id = ? AND scope = ? AND slot = ?")) {
            statement.setLong(1, guildId);
            statement.setString(2, scope);
            statement.setString(3, slot);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to delete an uploaded image for guild {} ({}/{})", guildId, scope, slot, e);
            throw new RuntimeException("Failed to delete the image", e);
        }
    }

    public Optional<Stored> findByToken(String token) {
        String sql = "SELECT token, content_type, data, EXTRACT(EPOCH FROM updated_at)::bigint AS version FROM younglings.uploaded_image WHERE token = ?";
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, token);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next()
                        ? Optional.of(new Stored(rs.getString("token"), rs.getString("content_type"), rs.getBytes("data"), rs.getLong("version")))
                        : Optional.empty();
            }
        } catch (SQLException e) {
            log.error("Failed to read an uploaded image", e);
            throw new RuntimeException("Failed to read the image", e);
        }
    }
}
