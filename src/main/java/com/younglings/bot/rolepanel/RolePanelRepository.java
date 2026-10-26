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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@BService
public class RolePanelRepository {
    private static final Logger log = LoggerFactory.getLogger(RolePanelRepository.class);

    private final ConnectionSupplier connectionSupplier;
    /**
     * Per server, the message ids that carry a reactions-style panel, so a reaction on any other message (nearly all of them) needs no query.
     * Filled through computeIfAbsent and dropped after every write to the server's panels, so a read that races a write can't leave an old set
     * behind (the drop waits for the read to finish).
     */
    private final ConcurrentHashMap<Long, Set<Long>> reactionMessages = new ConcurrentHashMap<>();

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
                "ALTER TABLE younglings.role_panel ADD COLUMN IF NOT EXISTS style TEXT NOT NULL DEFAULT 'BUTTONS';",
                "ALTER TABLE younglings.role_panel ADD COLUMN IF NOT EXISTS rule TEXT NOT NULL DEFAULT 'NORMAL';",
                "ALTER TABLE younglings.role_panel ADD COLUMN IF NOT EXISTS attached BOOLEAN NOT NULL DEFAULT FALSE;",
                "CREATE INDEX IF NOT EXISTS role_panel_message ON younglings.role_panel (message_id);",
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
        try {
            return saveRow(panel);
        } finally {
            reactionMessages.remove(panel.guildId());
        }
    }

    private RolePanel saveRow(RolePanel panel) {
        try (Connection c = connectionSupplier.getConnection()) {
            c.setAutoCommit(false);
            try {
                long id;
                if (panel.id() == null) {
                    try (PreparedStatement s = c.prepareStatement(
                            "INSERT INTO younglings.role_panel (guild_id, name, title, description, color, mode, style, rule) VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id")) {
                        s.setLong(1, panel.guildId());
                        s.setString(2, panel.name());
                        s.setString(3, panel.title());
                        s.setString(4, panel.description());
                        if (panel.color() == null) s.setNull(5, java.sql.Types.INTEGER); else s.setInt(5, panel.color());
                        s.setString(6, panel.mode().name());
                        s.setString(7, panel.style().name());
                        s.setString(8, panel.rule().name());
                        try (ResultSet rs = s.executeQuery()) {
                            rs.next();
                            id = rs.getLong(1);
                        }
                    }
                } else {
                    id = panel.id();
                    try (PreparedStatement s = c.prepareStatement(
                            "UPDATE younglings.role_panel SET name = ?, title = ?, description = ?, color = ?, mode = ?, style = ?, rule = ? WHERE id = ? AND guild_id = ?")) {
                        s.setString(1, panel.name());
                        s.setString(2, panel.title());
                        s.setString(3, panel.description());
                        if (panel.color() == null) s.setNull(4, java.sql.Types.INTEGER); else s.setInt(4, panel.color());
                        s.setString(5, panel.mode().name());
                        s.setString(6, panel.style().name());
                        s.setString(7, panel.rule().name());
                        s.setLong(8, id);
                        s.setLong(9, panel.guildId());
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

    /**
     * False when no reactions-style panel sits on this message, so a reaction there can't be for a panel and {@link #byMessage} need not be asked.
     * True means one does (ask {@link #byMessage} as before). Read from a per-server set of message ids, loaded once and dropped on every write.
     */
    public boolean mayHaveReactionPanel(long guildId, long messageId) {
        return reactionMessages.computeIfAbsent(guildId, this::loadReactionMessages).contains(messageId);
    }

    /** Forgets a server's cached panel messages, for when its rows are deleted outside this class (the retention purge). */
    public void evict(long guildId) {
        reactionMessages.remove(guildId);
    }

    private Set<Long> loadReactionMessages(long guildId) {
        Set<Long> ids = new HashSet<>();
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT message_id FROM younglings.role_panel WHERE guild_id = ? AND style = ? AND message_id IS NOT NULL")) {
            s.setLong(1, guildId);
            s.setString(2, RolePanel.Style.REACTIONS.name());
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) ids.add(rs.getLong(1));
            }
            return Set.copyOf(ids);
        } catch (SQLException e) {
            log.error("Failed to list the reaction panel messages of guild {}", guildId, e);
            throw new RuntimeException("Failed to read the role panels", e);
        }
    }

    /** The panel whose message this is, or empty. Indexed; the reaction listener asks {@link #mayHaveReactionPanel} first. */
    public Optional<RolePanel> byMessage(long guildId, long messageId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT * FROM younglings.role_panel WHERE guild_id = ? AND message_id = ?")) {
            s.setLong(1, guildId);
            s.setLong(2, messageId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(map(c, rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            log.error("Failed to look up the role panel of message {}", messageId, e);
            throw new RuntimeException("Failed to read the role panel", e);
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
        } finally {
            reactionMessages.remove(guildId);
        }
    }

    public void setPosted(long guildId, long id, Long channelId, Long messageId) {
        setPosted(guildId, id, channelId, messageId, false);
    }

    /** {@code attached}: the message is somebody else's, which a reactions panel was put on by its link. */
    public void setPosted(long guildId, long id, Long channelId, Long messageId, boolean attached) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("UPDATE younglings.role_panel SET channel_id = ?, message_id = ?, attached = ? WHERE guild_id = ? AND id = ?")) {
            if (channelId == null) s.setNull(1, java.sql.Types.BIGINT); else s.setLong(1, channelId);
            if (messageId == null) s.setNull(2, java.sql.Types.BIGINT); else s.setLong(2, messageId);
            s.setBoolean(3, attached);
            s.setLong(4, guildId);
            s.setLong(5, id);
            s.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to record where role panel {} is posted", id, e);
            throw new RuntimeException("Failed to record the posted panel", e);
        } finally {
            reactionMessages.remove(guildId);
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
                noColor ? null : color, RolePanel.Mode.valueOf(rs.getString("mode")), noChannel ? null : channel, noMessage ? null : message, buttons,
                RolePanel.Style.valueOf(rs.getString("style")), RolePanel.Rule.valueOf(rs.getString("rule")), rs.getBoolean("attached"));
    }
}
