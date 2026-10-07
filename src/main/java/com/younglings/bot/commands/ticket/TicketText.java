package com.younglings.bot.commands.ticket;

import com.younglings.bot.ticket.TicketModels.Panel;
import com.younglings.bot.ticket.TicketModels.Status;
import com.younglings.bot.ticket.TicketModels.Ticket;

import java.util.List;

/**
 * Fills in the placeholders an admin can put in a panel's welcome line or embedded message, so the same wording
 * can greet whoever opened the ticket and name what it's about. Pure text work, so it can be tested directly.
 * <ul>
 *   <li>{@code {user}}: a mention of whoever opened the ticket</li>
 *   <li>{@code {number}}: the ticket number, like 0042</li>
 *   <li>{@code {panel}}: the panel's title</li>
 *   <li>{@code {type}}: the dropdown choice that routed the ticket (a tier, say), or nothing</li>
 *   <li>{@code {rsn}}: the requester's linked RuneScape name(s), or nothing</li>
 *   <li>{@code {ping}}: a mention of the role pinged for this ticket, or nothing; while it's missing from the welcome
 *       line the ping is added on a line of its own</li>
 *   <li>{@code {helpers}}: a mention of each helper who joined, or "nobody yet"</li>
 * </ul>
 */
final class TicketText {
    private TicketText() {}

    /** What the dashboard tells admins they can write, in the order it lists them. */
    static final List<String> PLACEHOLDERS = List.of("{user}", "{number}", "{panel}", "{type}", "{rsn}", "{ping}", "{helpers}");

    static boolean usesPing(String template) {
        return template != null && template.contains("{ping}");
    }

    static String fill(String template, Panel panel, Ticket ticket, List<String> rsns, List<Long> helperIds, Long pingRoleId) {
        return fill(template, panel, ticket, rsns, helperIds, pingRoleId, List.of());
    }

    /** What {@code {ping}} stands for: the pinged role and/or the helpers who opted in for the ticket's tier, or nothing once it's closed. */
    static String pingMentions(Ticket ticket, Long pingRoleId, List<Long> pingUserIds) {
        if (ticket.status() != Status.OPEN) return "";
        List<String> mentions = new java.util.ArrayList<>();
        if (pingRoleId != null) mentions.add("<@&" + pingRoleId + ">");
        for (long id : pingUserIds) mentions.add("<@" + id + ">");
        return String.join(" ", mentions);
    }

    static String fill(String template, Panel panel, Ticket ticket, List<String> rsns, List<Long> helperIds, Long pingRoleId, List<Long> pingUserIds) {
        String ping = pingMentions(ticket, pingRoleId, pingUserIds);
        String helpers = helperIds.isEmpty() ? "nobody yet" : String.join(", ", helperIds.stream().map(id -> "<@" + id + ">").toList());

        return template
                .replace("{user}", "<@" + ticket.requesterId() + ">")
                .replace("{number}", String.format("%04d", ticket.number()))
                .replace("{panel}", panel == null ? "" : panel.title())
                .replace("{type}", ticket.routingLabel() == null ? "" : ticket.routingLabel())
                .replace("{rsn}", String.join(", ", rsns))
                .replace("{ping}", ping)
                .replace("{helpers}", helpers)
                .strip();
    }
}
