package com.runeclans.bot.devtools;

import com.runeclans.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The stored dev snapshots (the zip itself is kept in the row), listed newest first and deleted after {@link #KEEP_DAYS} days. */
@BService
public class DevSnapshotRepository {
    private static final Logger log = LoggerFactory.getLogger(DevSnapshotRepository.class);
    public static final int KEEP_DAYS = 14;

    /** A snapshot without its bytes, for lists. */
    public record Meta(String id, long guildId, OffsetDateTime createdAt, long sizeBytes, long requestedBy, String focus) {}

    private final ConnectionSupplier connectionSupplier;

    public DevSnapshotRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "dev_snapshot", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                // One row per snapshot, zip included. Short-lived on purpose (see KEEP_DAYS) and capped at 5 MB each by the service.
                """
                CREATE TABLE IF NOT EXISTS younglings.dev_snapshot (
                    id TEXT PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    size_bytes BIGINT NOT NULL,
                    requested_by BIGINT NOT NULL,
                    focus TEXT NOT NULL DEFAULT '',
                    data BYTEA NOT NULL
                );
                """,
                "CREATE INDEX IF NOT EXISTS dev_snapshot_created_idx ON younglings.dev_snapshot (created_at DESC);"
        ));
    }

    public void save(String id, long guildId, long requestedBy, String focus, byte[] zip) {
        String sql = "INSERT INTO younglings.dev_snapshot (id, guild_id, size_bytes, requested_by, focus, data) VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection connection = connectionSupplier.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            statement.setLong(2, guildId);
            statement.setLong(3, zip.length);
            statement.setLong(4, requestedBy);
            statement.setString(5, focus == null ? "" : focus);
            statement.setBytes(6, zip);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to store the snapshot", e);
        }
    }

    public List<Meta> list() {
        String sql = "SELECT id, guild_id, created_at, size_bytes, requested_by, focus FROM younglings.dev_snapshot ORDER BY created_at DESC LIMIT 200";
        List<Meta> out = new ArrayList<>();
        try (Connection connection = connectionSupplier.getConnection(); PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) out.add(meta(rs));
            return out;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list the snapshots", e);
        }
    }

    public Optional<Meta> find(String id) {
        String sql = "SELECT id, guild_id, created_at, size_bytes, requested_by, focus FROM younglings.dev_snapshot WHERE id = ?";
        try (Connection connection = connectionSupplier.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(meta(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the snapshot", e);
        }
    }

    public Optional<byte[]> zip(String id) {
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT data FROM younglings.dev_snapshot WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(rs.getBytes(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the snapshot", e);
        }
    }

    public boolean delete(String id) {
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM younglings.dev_snapshot WHERE id = ?")) {
            statement.setString(1, id);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete the snapshot", e);
        }
    }

    /** Deletes snapshots older than {@link #KEEP_DAYS} days; returns how many went. */
    public int deleteExpired() {
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM younglings.dev_snapshot WHERE created_at < now() - make_interval(days => ?)")) {
            statement.setInt(1, KEEP_DAYS);
            return statement.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to delete expired dev snapshots", e);
            return 0;
        }
    }

    private static Meta meta(ResultSet rs) throws SQLException {
        return new Meta(rs.getString("id"), rs.getLong("guild_id"), rs.getObject("created_at", OffsetDateTime.class), rs.getLong("size_bytes"),
                rs.getLong("requested_by"), rs.getString("focus"));
    }
}
