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
                        Integer helperCap, Integer escalationHours, Long defaultEscalateRoleId, Long postedChannelId, Long postedMessageId) {
        /** Whether tickets on this panel use the join-as-helper system at all. */
        public boolean usesHelpers() {
            return helperCap != null;
        }
    }

    public record Option(long id, long fieldId, int position, String label, Long pingRoleId, Long escalateRoleId) {}

    public record Field(long id, long panelId, int position, String label, FieldKind kind, boolean required, String placeholder,
                        Integer maxLength, List<Option> options) {}

    /** HELPER roles see every ticket on a panel and may join as helpers; STAFF roles also close any ticket and ignore the cap. */
    public record PanelRoles(Set<Long> helperRoleIds, Set<Long> staffRoleIds) {
        public static PanelRoles none() {
            return new PanelRoles(Set.of(), Set.of());
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
