package com.younglings.bot.upload;

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
import java.util.Optional;

@BService
public class UploadedImageRepository {
    private static final Logger log = LoggerFactory.getLogger(UploadedImageRepository.class);

    /** What is stored under a token; {@code version} is the upload time in epoch seconds, so an address can change when the picture does. */
    public record Stored(String token, String contentType, byte[] data, long version) {}

    /** What an upload came to. */
    public record Saved(String token, long version) {}

    /** The first key of this repository's advisory locks (the second is the server), so they never meet another feature's. */
    private static final int LOCK_SPACE = 0x1408;

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

    /** One picture of a library, without its bytes: {@code bytes} is its stored size. */
    public record Listed(String token, String contentType, long bytes, long version) {}

    /**
     * Adds a picture under its own token (the token is its slot) unless the server already has {@code limit} pictures in {@code scope}.
     * Uploads for one server are taken one at a time (a transaction lock on the server), so two at once can't both take the last place.
     */
    public Optional<Saved> addIfRoom(long guildId, String scope, String token, String contentType, byte[] data, long limit) {
        String count = "SELECT count(*) FROM younglings.uploaded_image WHERE guild_id = ? AND scope = ?";
        String insert = """
                INSERT INTO younglings.uploaded_image (guild_id, scope, slot, token, content_type, data)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING token, EXTRACT(EPOCH FROM updated_at)::bigint AS version
                """;
        try (Connection connection = connectionSupplier.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement lock = connection.prepareStatement("SELECT pg_advisory_xact_lock(?, ?)")) {
                    lock.setInt(1, LOCK_SPACE);
                    lock.setInt(2, Long.hashCode(guildId));
                    lock.execute();
                }
                long used;
                try (PreparedStatement statement = connection.prepareStatement(count)) {
                    statement.setLong(1, guildId);
                    statement.setString(2, scope);
                    try (ResultSet rs = statement.executeQuery()) {
                        rs.next();
                        used = rs.getLong(1);
                    }
                }
                if (used >= limit) {
                    connection.rollback();
                    return Optional.empty();
                }
                Saved saved;
                try (PreparedStatement statement = connection.prepareStatement(insert)) {
                    statement.setLong(1, guildId);
                    statement.setString(2, scope);
                    statement.setString(3, token);
                    statement.setString(4, token);
                    statement.setString(5, contentType);
                    statement.setBytes(6, data);
                    try (ResultSet rs = statement.executeQuery()) {
                        rs.next();
                        saved = new Saved(rs.getString("token"), rs.getLong("version"));
                    }
                }
                connection.commit();
                return Optional.of(saved);
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            log.error("Failed to add an uploaded image for guild {} ({})", guildId, scope, e);
            throw new RuntimeException("Failed to store the image", e);
        }
    }

    /** The pictures a server has in {@code scope}, oldest first, without their bytes. */
    public List<Listed> list(long guildId, String scope) {
        String sql = """
                SELECT token, content_type, octet_length(data) AS bytes, EXTRACT(EPOCH FROM updated_at)::bigint AS version
                FROM younglings.uploaded_image WHERE guild_id = ? AND scope = ? ORDER BY updated_at, token
                """;
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, guildId);
            statement.setString(2, scope);
            List<Listed> out = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) out.add(new Listed(rs.getString("token"), rs.getString("content_type"), rs.getLong("bytes"), rs.getLong("version")));
            }
            return out;
        } catch (SQLException e) {
            log.error("Failed to list the uploaded images of guild {} ({})", guildId, scope, e);
            throw new RuntimeException("Failed to list the images", e);
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
