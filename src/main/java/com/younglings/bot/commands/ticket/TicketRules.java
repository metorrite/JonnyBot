package com.younglings.bot.commands.ticket;

import com.younglings.bot.ticket.TicketModels.Field;
import com.younglings.bot.ticket.TicketModels.FieldKind;
import com.younglings.bot.ticket.TicketModels.Option;
import com.younglings.bot.ticket.TicketModels.Panel;
import com.younglings.bot.ticket.TicketModels.PanelRoles;
import com.younglings.bot.ticket.TicketModels.Status;
import com.younglings.bot.ticket.TicketModels.Ticket;
import net.dv8tion.jda.api.Permission;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The ticket system's decisions, with no Discord calls in them: what a ticket channel is called, who can see
 * it, who may join or close it, which role a ticket pings. Kept apart so every rule can be tested directly.
 */
public final class TicketRules {
    private TicketRules() {}

    /** A Discord form holds at most this many questions. */
    public static final int MAX_FIELDS = 5;
    public static final int MAX_OPTIONS = 25;

    // ---------- channel names ----------

    /**
     * {@code {number}} (zero-padded to 4), {@code {user}} and {@code {type}} (the routing choice, e.g. a tier) are
     * filled in, then the result is cleaned to what Discord accepts for a text channel: lowercase letters,
     * digits, hyphens and underscores, up to 100 characters. Never returns an empty name.
     */
    public static String channelName(String template, int number, String requesterName, String routingLabel) {
        String raw = (template == null || template.isBlank() ? "ticket-{number}" : template)
                .replace("{number}", String.format("%04d", number))
                .replace("{user}", requesterName == null ? "" : requesterName)
                .replace("{type}", routingLabel == null ? "" : routingLabel);

        String cleaned = raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^[-_]+|[-_]+$", "");
        if (cleaned.length() > 100) cleaned = cleaned.substring(0, 100).replaceAll("[-_]+$", "");
        return cleaned.isEmpty() ? "ticket-" + String.format("%04d", number) : cleaned;
    }

    // ---------- who can see the channel ----------

    public record Overwrite(long id, boolean isRole, long allow, long deny) {}

    private static final EnumSet<Permission> PARTICIPANT = EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY,
            Permission.MESSAGE_ATTACH_FILES, Permission.MESSAGE_EMBED_LINKS, Permission.MESSAGE_ADD_REACTION, Permission.MESSAGE_EXT_EMOJI);
    private static final EnumSet<Permission> BOT = EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY,
            Permission.MESSAGE_ATTACH_FILES, Permission.MESSAGE_EMBED_LINKS, Permission.MANAGE_CHANNEL, Permission.MESSAGE_MANAGE);

    /** The ticket's own permission overwrites: hidden from everyone, visible to the requester, the helper/staff roles, and the bot. */
    public static List<Overwrite> overwrites(long everyoneRoleId, long requesterId, long botId, Set<Long> participantRoleIds) {
        List<Overwrite> overwrites = new ArrayList<>();
        overwrites.add(new Overwrite(everyoneRoleId, true, 0, Permission.getRaw(Permission.VIEW_CHANNEL)));
        overwrites.add(new Overwrite(requesterId, false, Permission.getRaw(PARTICIPANT), 0));
        for (long roleId : participantRoleIds) overwrites.add(new Overwrite(roleId, true, Permission.getRaw(PARTICIPANT), 0));
        overwrites.add(new Overwrite(botId, false, Permission.getRaw(BOT), 0));
        return overwrites;
    }

    /** Every role that should see all of a panel's tickets: its helper roles and its staff roles. */
    public static Set<Long> participantRoles(PanelRoles roles) {
        Set<Long> all = new java.util.HashSet<>(roles.helperRoleIds());
        all.addAll(roles.staffRoleIds());
        return all;
    }

    // ---------- joining as a helper ----------

    public enum JoinResult { OK, TICKET_CLOSED, NO_HELPER_SYSTEM, IS_REQUESTER, ALREADY_HELPER, NOT_ELIGIBLE, FULL }

    public static boolean isStaff(PanelRoles roles, Set<Long> memberRoleIds, boolean isAdmin) {
        return isAdmin || roles.staffRoleIds().stream().anyMatch(memberRoleIds::contains);
    }

    /**
     * May this person join as a helper? Staff (a staff role, or an admin) can always step in, past the cap.
     * Everyone else needs a helper role, can't be the requester, and only gets a place while there's room.
     */
    public static JoinResult canJoin(Panel panel, PanelRoles roles, Set<Long> memberRoleIds, boolean isAdmin, boolean isRequester,
                                     boolean alreadyHelper, int helperCount, boolean ticketOpen) {
        if (!ticketOpen) return JoinResult.TICKET_CLOSED;
        if (!panel.usesHelpers()) return JoinResult.NO_HELPER_SYSTEM;
        if (isRequester) return JoinResult.IS_REQUESTER;
        if (alreadyHelper) return JoinResult.ALREADY_HELPER;

        boolean staff = isStaff(roles, memberRoleIds, isAdmin);
        boolean helper = staff || roles.helperRoleIds().stream().anyMatch(memberRoleIds::contains);
        if (!helper) return JoinResult.NOT_ELIGIBLE;
        if (!staff && helperCount >= panel.helperCap()) return JoinResult.FULL;
        return JoinResult.OK;
    }

    /** The requester, anyone who joined as a helper, staff, and admins may close a ticket. */
    public static boolean canClose(Ticket ticket, long userId, Set<Long> memberRoleIds, PanelRoles roles, boolean isAdmin, Collection<Long> helperIds) {
        return ticket.status() == Status.OPEN
                && (ticket.requesterId() == userId || helperIds.contains(userId) || isStaff(roles, memberRoleIds, isAdmin));
    }

    // ---------- routing ----------

    /** The option picked in the panel's first dropdown question that routes pings (any choice carrying a ping or escalation role). */
    public static Optional<Option> routingOption(List<Field> fields, java.util.function.Function<Field, String> pickedOptionIdForField) {
        for (Field field : fields) {
            if (field.kind() != FieldKind.SELECT) continue;
            boolean routes = field.options().stream().anyMatch(o -> o.pingRoleId() != null || o.escalateRoleId() != null);
            if (!routes) continue;

            String picked = pickedOptionIdForField.apply(field);
            if (picked == null) return Optional.empty();
            return field.options().stream().filter(o -> String.valueOf(o.id()).equals(picked)).findFirst();
        }
        return Optional.empty();
    }

    /** The role pinged when a ticket opens: the chosen option's, else the panel's default. */
    public static Long pingRole(Panel panel, Option routing) {
        return routing != null && routing.pingRoleId() != null ? routing.pingRoleId() : panel.defaultPingRoleId();
    }

    /** The role pinged when nobody joins in time: the chosen option's, else the panel's default. */
    public static Long escalateRole(Panel panel, Option routing) {
        return routing != null && routing.escalateRoleId() != null ? routing.escalateRoleId() : panel.defaultEscalateRoleId();
    }

    // ---------- panel configuration checks (what the dashboard's Save runs) ----------

    /** Everything wrong with a panel as configured, one sentence each — empty if it's usable. */
    public static List<String> validatePanel(Panel panel, List<Field> fields) {
        List<String> problems = new ArrayList<>();
        if (panel.name() == null || panel.name().isBlank()) problems.add("The panel needs a name.");
        if (panel.title() == null || panel.title().isBlank()) problems.add("The panel needs a title.");
        if (panel.buttonLabel() == null || panel.buttonLabel().isBlank() || panel.buttonLabel().length() > 80) problems.add("The button label must be 1 to 80 characters.");
        if (panel.perUserLimit() < 1 || panel.perUserLimit() > 20) problems.add("Tickets per person must be between 1 and 20.");
        if (panel.helperCap() != null && (panel.helperCap() < 1 || panel.helperCap() > 25)) problems.add("The helper limit must be between 1 and 25.");
        if (panel.escalationHours() != null && (panel.escalationHours() < 1 || panel.escalationHours() > 720)) problems.add("The escalation wait must be between 1 and 720 hours.");
        if (panel.name() != null && panel.name().length() > 60) problems.add("The panel name must be 60 characters or fewer.");
        if (panel.title() != null && panel.title().length() > 100) problems.add("The title must be 100 characters or fewer.");
        if (panel.description() != null && panel.description().length() > 2500) problems.add("The description must be 2500 characters or fewer.");
        if (panel.welcomeText() != null && panel.welcomeText().length() > 1000) problems.add("The welcome text must be 1000 characters or fewer.");
        if (panel.channelNameTemplate() == null || panel.channelNameTemplate().isBlank() || panel.channelNameTemplate().length() > 60) problems.add("The channel name template must be 1 to 60 characters.");
        if (fields.size() > MAX_FIELDS) problems.add("A form holds at most " + MAX_FIELDS + " questions — this panel has " + fields.size() + ".");

        for (Field field : fields) {
            if (field.label() == null || field.label().isBlank() || field.label().length() > 45) problems.add("Each question's label must be 1 to 45 characters (\"" + field.label() + "\").");
            if (field.placeholder() != null && field.placeholder().length() > 100) problems.add("The placeholder for \"" + field.label() + "\" must be 100 characters or fewer.");
            if (field.maxLength() != null && (field.maxLength() < 1 || field.maxLength() > 4000)) problems.add("The answer length limit for \"" + field.label() + "\" must be between 1 and 4000.");
            if (field.kind() == FieldKind.SELECT) {
                if (field.options().isEmpty()) problems.add("The dropdown \"" + field.label() + "\" needs at least one choice.");
                if (field.options().size() > MAX_OPTIONS) problems.add("The dropdown \"" + field.label() + "\" has more than " + MAX_OPTIONS + " choices.");
                for (Option option : field.options()) {
                    if (option.label() == null || option.label().isBlank() || option.label().length() > 100) problems.add("Every choice in \"" + field.label() + "\" needs a label of 1 to 100 characters.");
                }
            }
        }
        return problems;
    }
}
