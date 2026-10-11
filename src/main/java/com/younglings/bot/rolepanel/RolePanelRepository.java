package com.younglings.bot.rolepanel;

import com.younglings.bot.database.SchemaBootstrapper;
import com.younglings.bot.rolepanel.RolePanel.RoleButton;
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
public class RolePanelRepository {
    private static final Logger log = LoggerFactory.getLogger(RolePanelRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public RolePanelRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "role panel", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.role_panel (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    name TEXT NOT NULL,
                    title TEXT NOT NULL DEFAULT '',
                    description TEXT NOT NULL DEFAULT '',
                    color INTEGER NULL,
                    mode TEXT NOT NULL DEFAULT 'TOGGLE',
                    channel_id BIGINT NULL,
                    message_id BIGINT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                "CREATE INDEX IF NOT EXISTS role_panel_guild ON younglings.role_panel (guild_id);",
                """
                CREATE TABLE IF NOT EXISTS younglings.role_panel_button (
                    panel_id BIGINT NOT NULL REFERENCES younglings.role_panel (id) ON DELETE CASCADE,
                    position INTEGER NOT NULL,
                    guild_id BIGINT NOT NULL,
                    role_id BIGINT NOT NULL,
                    label TEXT NOT NULL DEFAULT '',
                    emoji TEXT NULL,
                    style TEXT NOT NULL DEFAULT 'primary',
                    PRIMARY KEY (panel_id, position)
                );
                """));
    }

    public List<RolePanel> list(long guildId) {
        List<RolePanel> panels = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT * FROM younglings.role_panel WHERE guild_id = ? ORDER BY id")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) panels.add(map(c, rs));
            }
            return panels;
        } catch (SQLException e) {
            log.error("Failed to list the role panels of guild {}", guildId, e);
            throw new RuntimeException("Failed to list the role panels", e);
        }
    }

    public Optional<RolePanel> get(long guildId, long id) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT * FROM younglings.role_panel WHERE guild_id = ? AND id = ?")) {
            s.setLong(1, guildId);
            s.setLong(2, id);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(map(c, rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            log.error("Failed to read role panel {}", id, e);
            throw new RuntimeException("Failed to read the role panel", e);
        }
    }

    /** Creates the panel when its id is null, otherwise replaces its text and buttons (leaving where it is posted alone). Returns the saved panel. */
    public RolePanel save(RolePanel panel) {
        try (Connection c = connectionSupplier.getConnection()) {
            c.setAutoCommit(false);
            try {
                long id;
                if (panel.id() == null) {
                    try (PreparedStatement s = c.prepareStatement(
                            "INSERT INTO younglings.role_panel (guild_id, name, title, description, color, mode) VALUES (?, ?, ?, ?, ?, ?) RETURNING id")) {
                        s.setLong(1, panel.guildId());
                        s.setString(2, panel.name());
                        s.setString(3, panel.title());
                        s.setString(4, panel.description());
                        if (panel.color() == null) s.setNull(5, java.sql.Types.INTEGER); else s.setInt(5, panel.color());
                        s.setString(6, panel.mode().name());
                        try (ResultSet rs = s.executeQuery()) {
                            rs.next();
                            id = rs.getLong(1);
                        }
                    }
                } else {
                    id = panel.id();
                    try (PreparedStatement s = c.prepareStatement(
                            "UPDATE younglings.role_panel SET name = ?, title = ?, description = ?, color = ?, mode = ? WHERE id = ? AND guild_id = ?")) {
                        s.setString(1, panel.name());
                        s.setString(2, panel.title());
                        s.setString(3, panel.description());
                        if (panel.color() == null) s.setNull(4, java.sql.Types.INTEGER); else s.setInt(4, panel.color());
                        s.setString(5, panel.mode().name());
                        s.setLong(6, id);
                        s.setLong(7, panel.guildId());
                        s.executeUpdate();
                    }
                    try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings.role_panel_button WHERE panel_id = ?")) {
                        s.setLong(1, id);
                        s.executeUpdate();
                    }
                }
                int position = 0;
                for (RoleButton button : panel.buttons()) {
                    try (PreparedStatement s = c.prepareStatement(
                            "INSERT INTO younglings.role_panel_button (panel_id, position, guild_id, role_id, label, emoji, style) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                        s.setLong(1, id);
                        s.setInt(2, position++);
                        s.setLong(3, panel.guildId());
                        s.setLong(4, button.roleId());
                        s.setString(5, button.label());
                        s.setString(6, button.emoji());
                        s.setString(7, button.style());
                        s.executeUpdate();
                    }
                }
                c.commit();
                return get(panel.guildId(), id).orElseThrow();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            log.error("Failed to save a role panel for guild {}", panel.guildId(), e);
            throw new RuntimeException("Failed to save the role panel", e);
        }
    }

    public boolean delete(long guildId, long id) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("DELETE FROM younglings.role_panel WHERE guild_id = ? AND id = ?")) {
            s.setLong(1, guildId);
            s.setLong(2, id);
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to delete role panel {}", id, e);
            throw new RuntimeException("Failed to delete the role panel", e);
        }
    }

    public void setPosted(long guildId, long id, Long channelId, Long messageId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("UPDATE younglings.role_panel SET channel_id = ?, message_id = ? WHERE guild_id = ? AND id = ?")) {
            if (channelId == null) s.setNull(1, java.sql.Types.BIGINT); else s.setLong(1, channelId);
            if (messageId == null) s.setNull(2, java.sql.Types.BIGINT); else s.setLong(2, messageId);
            s.setLong(3, guildId);
            s.setLong(4, id);
            s.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to record where role panel {} is posted", id, e);
            throw new RuntimeException("Failed to record the posted panel", e);
        }
    }

    private RolePanel map(Connection c, ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        List<RoleButton> buttons = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement("SELECT role_id, label, emoji, style FROM younglings.role_panel_button WHERE panel_id = ? ORDER BY position")) {
            s.setLong(1, id);
            try (ResultSet b = s.executeQuery()) {
                while (b.next()) buttons.add(new RoleButton(b.getLong("role_id"), b.getString("label"), b.getString("emoji"), b.getString("style")));
            }
        }
        int color = rs.getInt("color");
        boolean noColor = rs.wasNull();
        long channel = rs.getLong("channel_id");
        boolean noChannel = rs.wasNull();
        long message = rs.getLong("message_id");
        boolean noMessage = rs.wasNull();
        return new RolePanel(id, rs.getLong("guild_id"), rs.getString("name"), rs.getString("title"), rs.getString("description"),
                noColor ? null : color, RolePanel.Mode.valueOf(rs.getString("mode")), noChannel ? null : channel, noMessage ? null : message, buttons);
    }
}
