package com.younglings.bot.runescape;

import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;

@BService
public class RsnRenameRepository {
    private static final Logger log = LoggerFactory.getLogger(RsnRenameRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public RsnRenameRepository(ConnectionSupplier connectionSupplier, RuneScapeDatabaseInitializer initializer) {
        this.connectionSupplier = connectionSupplier;
    }

    /** {@code confidence} is {@code "HIGH"}/{@code "MEDIUM"}/{@code "MANUAL"}; {@code status} is {@code "PENDING"}/{@code "CONFIRMED"}/{@code "REJECTED"}. */
    public record RenameCandidate(long id, long guildId, String oldRsn, String newRsn, String confidence,
                                   String basis, OffsetDateTime detectedAt, String status) {
    }

    public long create(long guildId, String oldRsn, String newRsn, String confidence, String basis) {
        String sql = """
                INSERT INTO younglings.rsn_rename_candidate (guild_id, old_rsn, new_rsn, confidence, basis)
                VALUES (?, ?, ?, ?, ?)
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            statement.setLong(1, guildId);
            statement.setString(2, oldRsn);
            statement.setString(3, newRsn);
            statement.setString(4, confidence);
            statement.setString(5, basis);
            statement.executeUpdate();

            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
            throw new SQLException("No id returned after creating rename candidate.");

        } catch (SQLException e) {
            log.error("Failed to create rename candidate '{}' -> '{}'", oldRsn, newRsn, e);
            throw new RuntimeException("Failed to create rename candidate", e);
        }
    }

    public RenameCandidate getById(long id) {
        String sql = """
                SELECT id, guild_id, old_rsn, new_rsn, confidence, basis, detected_at, status
                FROM younglings.rsn_rename_candidate WHERE id = ?
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, id);

            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }

        } catch (SQLException e) {
            log.error("Failed to get rename candidate {}", id, e);
            throw new RuntimeException("Failed to get rename candidate", e);
        }
    }

    public void resolve(long id, String status) {
        String sql = "UPDATE younglings.rsn_rename_candidate SET status = ?, resolved_at = NOW() WHERE id = ?";

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, status);
            statement.setLong(2, id);
            statement.executeUpdate();

        } catch (SQLException e) {
            log.error("Failed to resolve rename candidate {}", id, e);
            throw new RuntimeException("Failed to resolve rename candidate", e);
        }
    }

    private static RenameCandidate map(ResultSet rs) throws SQLException {
        return new RenameCandidate(rs.getLong("id"), rs.getLong("guild_id"), rs.getString("old_rsn"),
                rs.getString("new_rsn"), rs.getString("confidence"), rs.getString("basis"),
                rs.getObject("detected_at", OffsetDateTime.class), rs.getString("status"));
    }
}
