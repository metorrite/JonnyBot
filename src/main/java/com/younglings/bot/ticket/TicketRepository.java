package com.younglings.bot.ticket;

import com.younglings.bot.ticket.TicketModels.Answer;
import com.younglings.bot.ticket.TicketModels.Field;
import com.younglings.bot.ticket.TicketModels.FieldKind;
import com.younglings.bot.ticket.TicketModels.Option;
import com.younglings.bot.ticket.TicketModels.Panel;
import com.younglings.bot.ticket.TicketModels.PanelDefinition;
import com.younglings.bot.ticket.TicketModels.PanelRoles;
import com.younglings.bot.ticket.TicketModels.Settings;
import com.younglings.bot.ticket.TicketModels.Status;
import com.younglings.bot.ticket.TicketModels.Ticket;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * All the ticket system's database access. Nothing here caches: the bot's buttons look everything up by
 * id on each click, which is what lets tickets and panels keep working across restarts.
 */
@BService
public class TicketRepository {
    private static final Logger log = LoggerFactory.getLogger(TicketRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public TicketRepository(ConnectionSupplier connectionSupplier, TicketDatabaseInitializer initializer) {
        this.connectionSupplier = connectionSupplier;
    }

    // ================= settings =================

    public Settings getSettings(long guildId) {
        String sql = "SELECT guild_id, log_channel_id, next_number, transcript_dm, close_delay_seconds, transcript_retention_days FROM younglings.ticket_settings WHERE guild_id = ?";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) return Settings.defaults(guildId);
                return new Settings(rs.getLong("guild_id"), (Long) rs.getObject("log_channel_id"), rs.getInt("next_number"),
                        rs.getBoolean("transcript_dm"), rs.getInt("close_delay_seconds"), (Integer) rs.getObject("transcript_retention_days"));
            }
        } catch (SQLException e) {
            throw fail("read ticket settings", e);
        }
    }

    /** Saves everything except the running ticket counter, which only ever moves forward when a ticket is created. */
    public void saveSettings(Settings settings) {
        String sql = """
                INSERT INTO younglings.ticket_settings (guild_id, log_channel_id, transcript_dm, close_delay_seconds, transcript_retention_days)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (guild_id) DO UPDATE SET
                    log_channel_id = EXCLUDED.log_channel_id,
                    transcript_dm = EXCLUDED.transcript_dm,
                    close_delay_seconds = EXCLUDED.close_delay_seconds,
                    transcript_retention_days = EXCLUDED.transcript_retention_days
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, settings.guildId());
            setLong(s, 2, settings.logChannelId());
            s.setBoolean(3, settings.transcriptDm());
            s.setInt(4, settings.closeDelaySeconds());
            setInt(s, 5, settings.transcriptRetentionDays());
            s.executeUpdate();
        } catch (SQLException e) {
            throw fail("save ticket settings", e);
        }
    }

    // ================= panels =================

    private static final String PANEL_COLUMNS = """
            id, guild_id, name, title, description, button_label, category_id, channel_name_template, welcome_text, enabled,
            per_user_limit, default_ping_role_id, helper_cap, escalation_hours, default_escalate_role_id, posted_channel_id, posted_message_id
            """;

    private static Panel mapPanel(ResultSet rs) throws SQLException {
        return new Panel(rs.getLong("id"), rs.getLong("guild_id"), rs.getString("name"), rs.getString("title"), rs.getString("description"),
                rs.getString("button_label"), (Long) rs.getObject("category_id"), rs.getString("channel_name_template"), rs.getString("welcome_text"),
                rs.getBoolean("enabled"), rs.getInt("per_user_limit"), (Long) rs.getObject("default_ping_role_id"), (Integer) rs.getObject("helper_cap"),
                (Integer) rs.getObject("escalation_hours"), (Long) rs.getObject("default_escalate_role_id"),
                (Long) rs.getObject("posted_channel_id"), (Long) rs.getObject("posted_message_id"));
    }

    public List<Panel> getPanels(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT " + PANEL_COLUMNS + " FROM younglings.ticket_panel WHERE guild_id = ? ORDER BY LOWER(name)")) {
            s.setLong(1, guildId);
            List<Panel> panels = new ArrayList<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) panels.add(mapPanel(rs));
            }
            return panels;
        } catch (SQLException e) {
            throw fail("list ticket panels", e);
        }
    }

    public Panel getPanel(long panelId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT " + PANEL_COLUMNS + " FROM younglings.ticket_panel WHERE id = ?")) {
            s.setLong(1, panelId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? mapPanel(rs) : null;
            }
        } catch (SQLException e) {
            throw fail("read ticket panel", e);
        }
    }

    public List<Field> getFields(long panelId) {
        Map<Long, List<Option>> optionsByField = new LinkedHashMap<>();
        String optionSql = """
                SELECT o.id, o.field_id, o.position, o.label, o.ping_role_id, o.escalate_role_id
                FROM younglings.ticket_panel_option o JOIN younglings.ticket_panel_field f ON f.id = o.field_id
                WHERE f.panel_id = ? ORDER BY o.position
                """;
        String fieldSql = "SELECT id, panel_id, position, label, kind, required, placeholder, max_length FROM younglings.ticket_panel_field WHERE panel_id = ? ORDER BY position";
        try (Connection c = connectionSupplier.getConnection()) {
            try (PreparedStatement s = c.prepareStatement(optionSql)) {
                s.setLong(1, panelId);
                try (ResultSet rs = s.executeQuery()) {
                    while (rs.next()) {
                        optionsByField.computeIfAbsent(rs.getLong("field_id"), k -> new ArrayList<>()).add(new Option(rs.getLong("id"), rs.getLong("field_id"),
                                rs.getInt("position"), rs.getString("label"), (Long) rs.getObject("ping_role_id"), (Long) rs.getObject("escalate_role_id")));
                    }
                }
            }
            List<Field> fields = new ArrayList<>();
            try (PreparedStatement s = c.prepareStatement(fieldSql)) {
                s.setLong(1, panelId);
                try (ResultSet rs = s.executeQuery()) {
                    while (rs.next()) {
                        long id = rs.getLong("id");
                        fields.add(new Field(id, rs.getLong("panel_id"), rs.getInt("position"), rs.getString("label"), FieldKind.valueOf(rs.getString("kind")),
                                rs.getBoolean("required"), rs.getString("placeholder"), (Integer) rs.getObject("max_length"),
                                optionsByField.getOrDefault(id, List.of())));
                    }
                }
            }
            return fields;
        } catch (SQLException e) {
            throw fail("read ticket panel fields", e);
        }
    }

    public PanelRoles getRoles(long panelId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT role_id, kind FROM younglings.ticket_panel_role WHERE panel_id = ?")) {
            s.setLong(1, panelId);
            Set<Long> helpers = new HashSet<>();
            Set<Long> staff = new HashSet<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) ("STAFF".equals(rs.getString("kind")) ? staff : helpers).add(rs.getLong("role_id"));
            }
            return new PanelRoles(helpers, staff);
        } catch (SQLException e) {
            throw fail("read ticket panel roles", e);
        }
    }

    public PanelDefinition getDefinition(long panelId) {
        Panel panel = getPanel(panelId);
        return panel == null ? null : new PanelDefinition(panel, getFields(panelId), getRoles(panelId));
    }

    /**
     * Creates the panel ({@code panel().id() == 0}) or replaces an existing one wholesale — the panel row, its
     * questions with their choices, and its roles — in one transaction, so the dashboard's "Save" is all-or-nothing.
     * Where the panel was posted is never touched here. Returns the panel's id.
     *
     * @throws IllegalArgumentException if another panel in the server already has that name
     */
    public long saveDefinition(PanelDefinition definition) {
        Panel p = definition.panel();
        try (Connection c = connectionSupplier.getConnection()) {
            c.setAutoCommit(false);
            try {
                long panelId = p.id();
                if (panelId == 0) {
                    String insert = """
                            INSERT INTO younglings.ticket_panel (guild_id, name, title, description, button_label, category_id, channel_name_template,
                                welcome_text, enabled, per_user_limit, default_ping_role_id, helper_cap, escalation_hours, default_escalate_role_id)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                            """;
                    try (PreparedStatement s = c.prepareStatement(insert)) {
                        bindPanel(s, p, 1);
                        try (ResultSet rs = s.executeQuery()) {
                            rs.next();
                            panelId = rs.getLong(1);
                        }
                    }
                } else {
                    String update = """
                            UPDATE younglings.ticket_panel SET name = ?, title = ?, description = ?, button_label = ?, category_id = ?, channel_name_template = ?,
                                welcome_text = ?, enabled = ?, per_user_limit = ?, default_ping_role_id = ?, helper_cap = ?, escalation_hours = ?,
                                default_escalate_role_id = ?
                            WHERE id = ? AND guild_id = ?
                            """;
                    try (PreparedStatement s = c.prepareStatement(update)) {
                        s.setString(1, p.name());
                        s.setString(2, p.title());
                        s.setString(3, p.description());
                        s.setString(4, p.buttonLabel());
                        setLong(s, 5, p.categoryId());
                        s.setString(6, p.channelNameTemplate());
                        s.setString(7, p.welcomeText());
                        s.setBoolean(8, p.enabled());
                        s.setInt(9, p.perUserLimit());
                        setLong(s, 10, p.defaultPingRoleId());
                        setInt(s, 11, p.helperCap());
                        setInt(s, 12, p.escalationHours());
                        setLong(s, 13, p.defaultEscalateRoleId());
                        s.setLong(14, panelId);
                        s.setLong(15, p.guildId());
                        if (s.executeUpdate() == 0) throw new IllegalArgumentException("That panel doesn't exist.");
                    }
                    try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings.ticket_panel_field WHERE panel_id = ?")) {
                        s.setLong(1, panelId);
                        s.executeUpdate();
                    }
                    try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings.ticket_panel_role WHERE panel_id = ?")) {
                        s.setLong(1, panelId);
                        s.executeUpdate();
                    }
                }

                int position = 0;
                for (Field field : definition.fields()) {
                    long fieldId;
                    try (PreparedStatement s = c.prepareStatement("""
                            INSERT INTO younglings.ticket_panel_field (panel_id, position, label, kind, required, placeholder, max_length)
                            VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id
                            """)) {
                        s.setLong(1, panelId);
                        s.setInt(2, position++);
                        s.setString(3, field.label());
                        s.setString(4, field.kind().name());
                        s.setBoolean(5, field.required());
                        s.setString(6, field.placeholder());
                        setInt(s, 7, field.maxLength());
                        try (ResultSet rs = s.executeQuery()) {
                            rs.next();
                            fieldId = rs.getLong(1);
                        }
                    }
                    int optionPosition = 0;
                    for (Option option : field.options()) {
                        try (PreparedStatement s = c.prepareStatement("""
                                INSERT INTO younglings.ticket_panel_option (field_id, position, label, ping_role_id, escalate_role_id)
                                VALUES (?, ?, ?, ?, ?)
                                """)) {
                            s.setLong(1, fieldId);
                            s.setInt(2, optionPosition++);
                            s.setString(3, option.label());
                            setLong(s, 4, option.pingRoleId());
                            setLong(s, 5, option.escalateRoleId());
                            s.executeUpdate();
                        }
                    }
                }

                insertRoles(c, panelId, definition.roles().helperRoleIds(), "HELPER");
                insertRoles(c, panelId, definition.roles().staffRoleIds(), "STAFF");

                c.commit();
                return panelId;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) throw new IllegalArgumentException("A panel with that name already exists.");
            throw fail("save ticket panel", e);
        }
    }

    private void bindPanel(PreparedStatement s, Panel p, int start) throws SQLException {
        int i = start;
        s.setLong(i++, p.guildId());
        s.setString(i++, p.name());
        s.setString(i++, p.title());
        s.setString(i++, p.description());
        s.setString(i++, p.buttonLabel());
        setLong(s, i++, p.categoryId());
        s.setString(i++, p.channelNameTemplate());
        s.setString(i++, p.welcomeText());
        s.setBoolean(i++, p.enabled());
        s.setInt(i++, p.perUserLimit());
        setLong(s, i++, p.defaultPingRoleId());
        setInt(s, i++, p.helperCap());
        setInt(s, i++, p.escalationHours());
        setLong(s, i, p.defaultEscalateRoleId());
    }

    private void insertRoles(Connection c, long panelId, Set<Long> roleIds, String kind) throws SQLException {
        for (long roleId : roleIds) {
            try (PreparedStatement s = c.prepareStatement("INSERT INTO younglings.ticket_panel_role (panel_id, role_id, kind) VALUES (?, ?, ?) ON CONFLICT (panel_id, role_id) DO NOTHING")) {
                s.setLong(1, panelId);
                s.setLong(2, roleId);
                s.setString(3, kind);
                s.executeUpdate();
            }
        }
    }

    public boolean deletePanel(long guildId, long panelId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("DELETE FROM younglings.ticket_panel WHERE id = ? AND guild_id = ?")) {
            s.setLong(1, panelId);
            s.setLong(2, guildId);
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            throw fail("delete ticket panel", e);
        }
    }

    public void setPosted(long panelId, Long channelId, Long messageId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("UPDATE younglings.ticket_panel SET posted_channel_id = ?, posted_message_id = ? WHERE id = ?")) {
            setLong(s, 1, channelId);
            setLong(s, 2, messageId);
            s.setLong(3, panelId);
            s.executeUpdate();
        } catch (SQLException e) {
            throw fail("record where a ticket panel was posted", e);
        }
    }

    // ================= tickets =================

    private static final String TICKET_COLUMNS = """
            id, guild_id, panel_id, number, channel_id, requester_id, status, routing_option_id, routing_label, answers, welcome_message_id,
            created_at, escalated_at, closed_at, closed_by, close_reason, channel_deleted
            """;

    private static Ticket mapTicket(ResultSet rs) throws SQLException {
        return new Ticket(rs.getLong("id"), rs.getLong("guild_id"), (Long) rs.getObject("panel_id"), rs.getInt("number"),
                (Long) rs.getObject("channel_id"), rs.getLong("requester_id"), Status.valueOf(rs.getString("status")),
                (Long) rs.getObject("routing_option_id"), rs.getString("routing_label"), parseAnswers(rs.getString("answers")),
                (Long) rs.getObject("welcome_message_id"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("escalated_at", OffsetDateTime.class), rs.getObject("closed_at", OffsetDateTime.class),
                (Long) rs.getObject("closed_by"), rs.getString("close_reason"), rs.getBoolean("channel_deleted"));
    }

    static String answersToJson(List<Answer> answers) {
        DataArray array = DataArray.empty();
        for (Answer a : answers) array.add(DataObject.empty().put("label", a.label()).put("answer", a.answer()));
        return array.toString();
    }

    static List<Answer> parseAnswers(String json) {
        List<Answer> answers = new ArrayList<>();
        DataArray array = DataArray.fromJson(json == null || json.isBlank() ? "[]" : json);
        for (int i = 0; i < array.length(); i++) {
            DataObject o = array.getObject(i);
            answers.add(new Answer(o.getString("label", ""), o.getString("answer", "")));
        }
        return answers;
    }

    /** Creates the ticket and takes its number from the server's running counter in the same transaction, so two tickets can never share a number. */
    public Ticket createTicket(long guildId, long panelId, long requesterId, Long routingOptionId, String routingLabel, List<Answer> answers) {
        try (Connection c = connectionSupplier.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement s = c.prepareStatement("INSERT INTO younglings.ticket_settings (guild_id) VALUES (?) ON CONFLICT (guild_id) DO NOTHING")) {
                    s.setLong(1, guildId);
                    s.executeUpdate();
                }
                int number;
                try (PreparedStatement s = c.prepareStatement("UPDATE younglings.ticket_settings SET next_number = next_number + 1 WHERE guild_id = ? RETURNING next_number - 1")) {
                    s.setLong(1, guildId);
                    try (ResultSet rs = s.executeQuery()) {
                        rs.next();
                        number = rs.getInt(1);
                    }
                }
                Ticket ticket;
                try (PreparedStatement s = c.prepareStatement("""
                        INSERT INTO younglings.ticket (guild_id, panel_id, number, requester_id, routing_option_id, routing_label, answers)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """ + " RETURNING " + TICKET_COLUMNS)) {
                    s.setLong(1, guildId);
                    s.setLong(2, panelId);
                    s.setInt(3, number);
                    s.setLong(4, requesterId);
                    setLong(s, 5, routingOptionId);
                    s.setString(6, routingLabel);
                    s.setString(7, answersToJson(answers));
                    try (ResultSet rs = s.executeQuery()) {
                        rs.next();
                        ticket = mapTicket(rs);
                    }
                }
                c.commit();
                return ticket;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw fail("create a ticket", e);
        }
    }

    public Ticket getTicket(long ticketId) {
        return queryOne("SELECT " + TICKET_COLUMNS + " FROM younglings.ticket WHERE id = ?", ticketId);
    }

    public Ticket getTicketByChannel(long channelId) {
        return queryOne("SELECT " + TICKET_COLUMNS + " FROM younglings.ticket WHERE channel_id = ?", channelId);
    }

    private Ticket queryOne(String sql, long param) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, param);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? mapTicket(rs) : null;
            }
        } catch (SQLException e) {
            throw fail("read a ticket", e);
        }
    }

    public void setChannel(long ticketId, long channelId) {
        update("UPDATE younglings.ticket SET channel_id = ? WHERE id = ?", channelId, ticketId);
    }

    public void setWelcomeMessage(long ticketId, long messageId) {
        update("UPDATE younglings.ticket SET welcome_message_id = ? WHERE id = ?", messageId, ticketId);
    }

    public void markChannelDeleted(long ticketId) {
        update("UPDATE younglings.ticket SET channel_deleted = TRUE WHERE id = ?", ticketId);
    }

    private void update(String sql, long... params) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) s.setLong(i + 1, params[i]);
            s.executeUpdate();
        } catch (SQLException e) {
            throw fail("update a ticket", e);
        }
    }

    public int countOpenFor(long guildId, long panelId, long userId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT COUNT(*) FROM younglings.ticket WHERE guild_id = ? AND panel_id = ? AND requester_id = ? AND status = 'OPEN'")) {
            s.setLong(1, guildId);
            s.setLong(2, panelId);
            s.setLong(3, userId);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw fail("count open tickets", e);
        }
    }

    /** Page of tickets for the dashboard, newest first; {@code status} and {@code panelId} are optional filters. */
    public List<Ticket> listTickets(long guildId, Status status, Long panelId, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT " + TICKET_COLUMNS + " FROM younglings.ticket WHERE guild_id = ?");
        if (status != null) sql.append(" AND status = ?");
        if (panelId != null) sql.append(" AND panel_id = ?");
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql.toString())) {
            int i = 1;
            s.setLong(i++, guildId);
            if (status != null) s.setString(i++, status.name());
            if (panelId != null) s.setLong(i++, panelId);
            s.setInt(i++, limit);
            s.setInt(i, offset);
            List<Ticket> tickets = new ArrayList<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) tickets.add(mapTicket(rs));
            }
            return tickets;
        } catch (SQLException e) {
            throw fail("list tickets", e);
        }
    }

    // ----- closing -----

    /** Marks an open ticket closed. Returns {@code false} if it was already closed, so two people closing at once only close it once. */
    public boolean closeTicket(long ticketId, long closedBy, String reason) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("UPDATE younglings.ticket SET status = 'CLOSED', closed_at = NOW(), closed_by = ?, close_reason = ? WHERE id = ? AND status = 'OPEN'")) {
            s.setLong(1, closedBy);
            s.setString(2, reason);
            s.setLong(3, ticketId);
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            throw fail("close a ticket", e);
        }
    }

    /** Closed tickets whose channel is still there and was closed at or before {@code closedBefore} — what the cleanup sweep deletes. */
    public List<Ticket> getClosedAwaitingDeletion(OffsetDateTime closedBefore) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT " + TICKET_COLUMNS + " FROM younglings.ticket WHERE status = 'CLOSED' AND channel_id IS NOT NULL AND channel_deleted = FALSE AND closed_at <= ?")) {
            s.setObject(1, closedBefore);
            List<Ticket> tickets = new ArrayList<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) tickets.add(mapTicket(rs));
            }
            return tickets;
        } catch (SQLException e) {
            throw fail("find closed tickets to clean up", e);
        }
    }

    // ----- transcripts -----

    public record Transcript(long ticketId, String content, int messageCount, OffsetDateTime createdAt) {}

    public void saveTranscript(long ticketId, String content, int messageCount) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("""
                     INSERT INTO younglings.ticket_transcript (ticket_id, content, message_count) VALUES (?, ?, ?)
                     ON CONFLICT (ticket_id) DO UPDATE SET content = EXCLUDED.content, message_count = EXCLUDED.message_count, created_at = NOW()
                     """)) {
            s.setLong(1, ticketId);
            s.setString(2, content);
            s.setInt(3, messageCount);
            s.executeUpdate();
        } catch (SQLException e) {
            throw fail("save a ticket transcript", e);
        }
    }

    public Transcript getTranscript(long ticketId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT ticket_id, content, message_count, created_at FROM younglings.ticket_transcript WHERE ticket_id = ?")) {
            s.setLong(1, ticketId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? new Transcript(rs.getLong("ticket_id"), rs.getString("content"), rs.getInt("message_count"), rs.getObject("created_at", OffsetDateTime.class)) : null;
            }
        } catch (SQLException e) {
            throw fail("read a ticket transcript", e);
        }
    }

    /** Deletes saved transcripts (not the tickets) older than the cut-off. Returns how many went. */
    public int purgeTranscriptsBefore(long guildId, OffsetDateTime cutoff) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("""
                     DELETE FROM younglings.ticket_transcript WHERE created_at < ?
                       AND ticket_id IN (SELECT id FROM younglings.ticket WHERE guild_id = ?)
                     """)) {
            s.setObject(1, cutoff);
            s.setLong(2, guildId);
            return s.executeUpdate();
        } catch (SQLException e) {
            throw fail("purge old ticket transcripts", e);
        }
    }

    // ----- helpers -----

    /** Adds a helper unless they're already one. Returns {@code true} if they were added. */
    public boolean addHelper(long ticketId, long userId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("INSERT INTO younglings.ticket_helper (ticket_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
            s.setLong(1, ticketId);
            s.setLong(2, userId);
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            throw fail("add a ticket helper", e);
        }
    }

    public List<Long> getHelpers(long ticketId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT user_id FROM younglings.ticket_helper WHERE ticket_id = ? ORDER BY joined_at")) {
            s.setLong(1, ticketId);
            List<Long> helpers = new ArrayList<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) helpers.add(rs.getLong(1));
            }
            return helpers;
        } catch (SQLException e) {
            throw fail("read ticket helpers", e);
        }
    }

    // ----- escalation -----

    /** Open tickets on a panel with an escalation window, nobody helping, and past that window, not yet escalated. */
    public List<Ticket> getEscalationDue() {
        String sql = "SELECT " + prefixed("t.", TICKET_COLUMNS) + """
                 FROM younglings.ticket t JOIN younglings.ticket_panel p ON p.id = t.panel_id
                WHERE t.status = 'OPEN' AND t.channel_id IS NOT NULL AND t.escalated_at IS NULL
                  AND p.escalation_hours IS NOT NULL
                  AND t.created_at <= NOW() - (p.escalation_hours * INTERVAL '1 hour')
                  AND NOT EXISTS (SELECT 1 FROM younglings.ticket_helper h WHERE h.ticket_id = t.id)
                """;
        try (Connection c = connectionSupplier.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            List<Ticket> tickets = new ArrayList<>();
            while (rs.next()) tickets.add(mapTicket(rs));
            return tickets;
        } catch (SQLException e) {
            throw fail("find tickets due for escalation", e);
        }
    }

    /** Records that a ticket was escalated. Returns {@code false} if something else already did, so a ticket is never pinged twice. */
    public boolean markEscalated(long ticketId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("UPDATE younglings.ticket SET escalated_at = NOW() WHERE id = ? AND escalated_at IS NULL")) {
            s.setLong(1, ticketId);
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            throw fail("mark a ticket escalated", e);
        }
    }

    // ----- flags -----

    public void addFlag(long ticketId, long userId, String note) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("INSERT INTO younglings.ticket_flag (ticket_id, user_id, note) VALUES (?, ?, ?)")) {
            s.setLong(1, ticketId);
            s.setLong(2, userId);
            s.setString(3, note);
            s.executeUpdate();
        } catch (SQLException e) {
            throw fail("save a ticket flag", e);
        }
    }

    // ================= helpers =================

    /** "id, guild_id" -> "t.id, t.guild_id" */
    static String prefixed(String prefix, String columns) {
        StringBuilder out = new StringBuilder();
        for (String column : columns.strip().split("\\s*,\\s*")) {
            if (!out.isEmpty()) out.append(", ");
            out.append(prefix).append(column.strip());
        }
        return out.toString();
    }

    private static void setLong(PreparedStatement s, int index, Long value) throws SQLException {
        if (value == null) s.setNull(index, Types.BIGINT);
        else s.setLong(index, value);
    }

    private static void setInt(PreparedStatement s, int index, Integer value) throws SQLException {
        if (value == null) s.setNull(index, Types.INTEGER);
        else s.setInt(index, value);
    }

    private static RuntimeException fail(String action, SQLException e) {
        log.error("Failed to {}", action, e);
        return new RuntimeException("Failed to " + action, e);
    }
}
