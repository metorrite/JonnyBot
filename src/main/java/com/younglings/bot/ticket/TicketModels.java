package com.younglings.bot.ticket;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/** The ticket system's plain data types, shared by the repository, the bot's flows and the website API. */
public final class TicketModels {
    private TicketModels() {}

    public enum FieldKind { SHORT, PARAGRAPH, SELECT, CHECKBOX }

    public enum Status { OPEN, CLOSED }

    public record Settings(long guildId, Long logChannelId, int nextNumber, boolean transcriptDm, int closeDelaySeconds, Integer transcriptRetentionDays) {
        public static Settings defaults(long guildId) {
            return new Settings(guildId, null, 1, true, 10, null);
        }
    }

    public record Panel(long id, long guildId, String name, String title, String description, String buttonLabel, Long categoryId,
                        String channelNameTemplate, String welcomeText, boolean enabled, int perUserLimit, Long defaultPingRoleId,
                        Integer helperCap, Integer escalationHours, Long defaultEscalateRoleId, Long postedChannelId, Long postedMessageId,
                        String openingMessage, boolean closeByRequester, boolean closeByHelpers) {
        /**
         * The plain-text line posted above a new ticket's embeds. It can use the placeholders listed in
         * {@code TicketText}; {@code {user}} becomes a mention of whoever opened the ticket.
         */
        public static final String DEFAULT_OPENING = "{user} Welcome";
        /** The first embed's text when a panel doesn't set its own. */
        public static final String DEFAULT_SUPPORT = "Support will be with you shortly.\nTo close this press the close button.";

        /** A panel whose requester and joined helpers may close their ticket, for code that predates per-panel close settings. */
        public Panel(long id, long guildId, String name, String title, String description, String buttonLabel, Long categoryId,
                     String channelNameTemplate, String welcomeText, boolean enabled, int perUserLimit, Long defaultPingRoleId,
                     Integer helperCap, Integer escalationHours, Long defaultEscalateRoleId, Long postedChannelId, Long postedMessageId,
                     String openingMessage) {
            this(id, guildId, name, title, description, buttonLabel, categoryId, channelNameTemplate, welcomeText, enabled, perUserLimit,
                    defaultPingRoleId, helperCap, escalationHours, defaultEscalateRoleId, postedChannelId, postedMessageId, openingMessage, true, true);
        }

        /** A panel with the default opening message, for code that predates it. */
        public Panel(long id, long guildId, String name, String title, String description, String buttonLabel, Long categoryId,
                     String channelNameTemplate, String welcomeText, boolean enabled, int perUserLimit, Long defaultPingRoleId,
                     Integer helperCap, Integer escalationHours, Long defaultEscalateRoleId, Long postedChannelId, Long postedMessageId) {
            this(id, guildId, name, title, description, buttonLabel, categoryId, channelNameTemplate, welcomeText, enabled, perUserLimit,
                    defaultPingRoleId, helperCap, escalationHours, defaultEscalateRoleId, postedChannelId, postedMessageId, DEFAULT_OPENING, true, true);
        }

        /** Whether tickets on this panel use the join-as-helper system at all. */
        public boolean usesHelpers() {
            return helperCap != null;
        }
    }

    public record Option(long id, long fieldId, int position, String label, Long pingRoleId, Long escalateRoleId) {}

    public record Field(long id, long panelId, int position, String label, FieldKind kind, boolean required, String placeholder,
                        Integer maxLength, List<Option> options) {}

    /**
     * HELPER roles see every ticket on a panel and may join as helpers; STAFF roles also close any ticket and ignore the cap;
     * CLOSE roles may close any ticket on the panel without seeing it as staff (a role can sit in more than one set).
     */
    public record PanelRoles(Set<Long> helperRoleIds, Set<Long> staffRoleIds, Set<Long> closeRoleIds) {
        public PanelRoles(Set<Long> helperRoleIds, Set<Long> staffRoleIds) {
            this(helperRoleIds, staffRoleIds, Set.of());
        }

        public static PanelRoles none() {
            return new PanelRoles(Set.of(), Set.of(), Set.of());
        }
    }

    /** A panel with everything the dashboard edits at once. {@code panel.id()} is 0 for a panel that doesn't exist yet. */
    public record PanelDefinition(Panel panel, List<Field> fields, PanelRoles roles) {}

    public record Answer(String label, String answer) {}

    public record Ticket(long id, long guildId, Long panelId, int number, Long channelId, long requesterId, Status status,
                         Long routingOptionId, String routingLabel, List<Answer> answers, Long welcomeMessageId,
                         OffsetDateTime createdAt, OffsetDateTime escalatedAt, OffsetDateTime closedAt, Long closedBy,
                         String closeReason, boolean channelDeleted) {}
}
