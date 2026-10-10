package com.younglings.bot.notice;

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
public class NoticeRepository {
    private static final Logger log = LoggerFactory.getLogger(NoticeRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public NoticeRepository(ConnectionSupplier connectionSupplier, NoticeDatabaseInitializer initializer) {
        this.connectionSupplier = connectionSupplier;
    }

    /** Every notice, newest first. */
    public List<Notice> all() {
        String sql = "SELECT id, severity, body, created_at FROM younglings.bot_notice ORDER BY created_at DESC, id DESC";
        List<Notice> notices = new ArrayList<>();
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                notices.add(new Notice(rs.getLong("id"), rs.getString("severity"), rs.getString("body"), rs.getObject("created_at", java.time.OffsetDateTime.class)));
            }
            return notices;
        } catch (SQLException e) {
            log.error("Failed to read the bot notices", e);
            throw new RuntimeException("Failed to read bot notices", e);
        }
    }

    public void add(String severity, String body, long createdBy) {
        String sql = "INSERT INTO younglings.bot_notice (severity, body, created_by) VALUES (?, ?, ?)";
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, severity);
            statement.setString(2, body);
            statement.setLong(3, createdBy);
            statement.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to add a bot notice", e);
            throw new RuntimeException("Failed to add a bot notice", e);
        }
    }

    public boolean delete(long id) {
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM younglings.bot_notice WHERE id = ?")) {
            statement.setLong(1, id);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to delete bot notice {}", id, e);
            throw new RuntimeException("Failed to delete a bot notice", e);
        }
    }
}
