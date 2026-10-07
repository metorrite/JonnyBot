package com.younglings.bot.ticket;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/** The ticket system's plain data types, shared by the repository, the bot's flows and the website API. */
public final class TicketModels {
    private TicketModels() {}

    public enum FieldKind { SHORT, PARAGRAPH, SELECT, CHECKBOX }

    public enum Status { OPEN, CLOSED }

    /** Which part of the PvM Help system a panel belongs to; NONE is an ordinary ticket panel the help rules never touch. */
    public enum HelpKind { NONE, PVM, CA }

    /**
     * What a question means to the PvM Help rules: the boss, the tier, the one achievement they want help with, or whether they have already made
     * attempts. A help panel that has a boss question is filled in through a guided flow (boss, tier, achievement chosen from the achievement
     * catalogue) instead of one long form.
     */
    public enum FieldPurpose { NONE, BOSS, TIER, ACHIEVEMENT, ATTEMPTS }

    public record Settings(long guildId, Long logChannelId, int nextNumber, boolean transcriptDm, int closeDelaySeconds, Integer transcriptRetentionDays) {
        public static Settings defaults(long guildId) {
            return new Settings(guildId, null, 1, true, 10, null);
        }
    }

    public record Panel(long id, long guildId, String name, String title, String description, String buttonLabel, Long categoryId,
                        String channelNameTemplate, String welcomeText, boolean enabled, int perUserLimit, Long defaultPingRoleId,
                        Integer helperCap, Integer escalationHours, Long defaultEscalateRoleId, Long postedChannelId, Long postedMessageId,
                        String openingMessage, boolean closeByRequester, boolean closeByHelpers, HelpKind helpKind) {
        /**
         * The plain-text line posted above a new ticket's embeds. It can use the placeholders listed in
         * {@code TicketText}; {@code {user}} becomes a mention of whoever opened the ticket.
         */
        public static final String DEFAULT_OPENING = "{user} Welcome";
        /** The first embed's text when a panel doesn't set its own. */
        public static final String DEFAULT_SUPPORT = "Support will be with you shortly.\nTo close this press the close button.";

        /** An ordinary ticket panel (not part of the PvM Help system), for code that predates help kinds. */
        public Panel(long id, long guildId, String name, String title, String description, String buttonLabel, Long categoryId,
                     String channelNameTemplate, String welcomeText, boolean enabled, int perUserLimit, Long defaultPingRoleId,
                     Integer helperCap, Integer escalationHours, Long defaultEscalateRoleId, Long postedChannelId, Long postedMessageId,
                     String openingMessage, boolean closeByRequester, boolean closeByHelpers) {
            this(id, guildId, name, title, description, buttonLabel, categoryId, channelNameTemplate, welcomeText, enabled, perUserLimit,
                    defaultPingRoleId, helperCap, escalationHours, defaultEscalateRoleId, postedChannelId, postedMessageId, openingMessage,
                    closeByRequester, closeByHelpers, HelpKind.NONE);
        }

        /** A panel whose requester and joined helpers may close their ticket, for code that predates per-panel close settings. */
        public Panel(long id, long guildId, String name, String title, String description, String buttonLabel, Long categoryId,
                     String channelNameTemplate, String welcomeText, boolean enabled, int perUserLimit, Long defaultPingRoleId,
                     Integer helperCap, Integer escalationHours, Long defaultEscalateRoleId, Long postedChannelId, Long postedMessageId,
                     String openingMessage) {
            this(id, guildId, name, title, description, buttonLabel, categoryId, channelNameTemplate, welcomeText, enabled, perUserLimit,
                    defaultPingRoleId, helperCap, escalationHours, defaultEscalateRoleId, postedChannelId, postedMessageId, openingMessage,
                    true, true, HelpKind.NONE);
        }

        /** A panel with the default opening message, for code that predates it. */
        public Panel(long id, long guildId, String name, String title, String description, String buttonLabel, Long categoryId,
                     String channelNameTemplate, String welcomeText, boolean enabled, int perUserLimit, Long defaultPingRoleId,
                     Integer helperCap, Integer escalationHours, Long defaultEscalateRoleId, Long postedChannelId, Long postedMessageId) {
            this(id, guildId, name, title, description, buttonLabel, categoryId, channelNameTemplate, welcomeText, enabled, perUserLimit,
                    defaultPingRoleId, helperCap, escalationHours, defaultEscalateRoleId, postedChannelId, postedMessageId, DEFAULT_OPENING,
                    true, true, HelpKind.NONE);
        }

        /** Whether tickets on this panel use the join-as-helper system at all. */
        public boolean usesHelpers() {
            return helperCap != null;
        }

        /** Whether the PvM Help rules (member and guest pings, the Master+ attempts rule) apply to this panel's tickets. */
        public boolean isHelpPanel() {
            return helpKind != HelpKind.NONE;
        }
    }

    public record Option(long id, long fieldId, int position, String label, Long pingRoleId, Long escalateRoleId) {}

    public record Field(long id, long panelId, int position, String label, FieldKind kind, boolean required, String placeholder,
                        Integer maxLength, List<Option> options, FieldPurpose purpose) {
        /** A question with no part in the PvM Help rules, for code that predates field purposes. */
        public Field(long id, long panelId, int position, String label, FieldKind kind, boolean required, String placeholder,
                     Integer maxLength, List<Option> options) {
            this(id, panelId, position, label, kind, required, placeholder, maxLength, options, FieldPurpose.NONE);
        }
    }

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

    /**
     * The PvM Help system's settings for a server. A member's ticket pings the helpers who opted in for its tier when it opens and,
     * after the escalation wait, the next role up. A guest's ticket (anyone who isn't a clan member) pings nobody unless guest pings are
     * switched on. {@code guidelines} is null until an admin edits them; the built-in draft is used until then.
     *
     * @param memberEscalationHours hours before an unanswered member ticket pings the next role up, or null for never
     * @param guestPingsEnabled     the master switch for guest tickets pinging anyone at all (off for now)
     * @param guestPingOnOpen       with guest pings on, whether guests' tickets ping when they open
     * @param guestEscalationHours  with guest pings on, hours before the next role up is pinged, or null for never
     * @param highTierLabels        the tier names, comma separated, that count as Master and above for the attempts rule
     */
    public record HelpSettings(long guildId, Long helperRoleId, Long helperPlusRoleId, String guidelines,
                               boolean memberPingOnOpen, Integer memberEscalationHours,
                               boolean guestPingsEnabled, boolean guestPingOnOpen, Integer guestEscalationHours,
                               boolean guestHighTierNeedsAttempts, String highTierLabels,
                               Long postedChannelId, Long postedMessageId) {
        public static final int MAX_GUIDELINES = 3500;

        /** Member tickets ping on opening and escalate after 72 hours; guests ping nobody; Master and Grandmaster guests must show earlier attempts. */
        public static HelpSettings defaults(long guildId) {
            return new HelpSettings(guildId, null, null, null, true, 72, false, true, null, true, "Master, Grandmaster", null, null);
        }

        public String guidelinesOrDefault() {
            return guidelines == null || guidelines.isBlank() ? DEFAULT_GUIDELINES : guidelines;
        }

        /** The first draft of the helper guidelines, written from the clan's ticket discussion. Admins replace it in /configure or on the website. */
        public static final String DEFAULT_GUIDELINES = """
                By taking the PVM Helper role you agree to the guidelines below. You represent the Younglings every time you help, so please read them properly.

                **What PvM help is**
                • Advice, guides, resources, tips and VOD reviews, given in the ticket. It is not going in game to carry or run the content for someone.
                • Teamforming comes first. If someone needs a group, point them to #teamforming; a ticket is the last resort.

                **Stay in your lane**
                • Only help with what you can really do and explain. If a ticket is outside your experience, leave it for someone who knows it.
                • If the member picked one specific achievement, help with that achievement only.
                • You can see every ticket. Master and Grandmaster tickets don't ping PVM Helpers, but you're welcome to help if you know the content well.

                **One or two helpers per ticket**
                • Press **Join as helper** to take a ticket. The limit is two helpers, and one is preferred. If a ticket is full, don't pile in.
                • Don't ping members or staff to find people to help. An admin will step in if needed.

                **Represent the clan**
                • Be patient, friendly and respectful, whatever the member's experience. No mocking, no gatekeeping, no taking over.
                • Keep what is said in a ticket inside the ticket.
                • Clan members come first. Guests can open tickets, but for now clan members get the priority.

                **If it goes wrong**
                • Staff can remove the role at any time, and will if a helper is disrespectful, misleading, or misusing tickets.
                """.strip();
    }
}
