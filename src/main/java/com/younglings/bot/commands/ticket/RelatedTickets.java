package com.younglings.bot.commands.ticket;

import com.younglings.bot.ticket.HelpExtras;
import com.younglings.bot.ticket.TicketModels.Answer;
import com.younglings.bot.ticket.TicketModels.Field;
import com.younglings.bot.ticket.TicketModels.FieldPurpose;

import java.util.ArrayList;
import java.util.List;

/**
 * Group content (Vorago, Solak...) is done together, so two members asking for help with the same group boss are better off in one ticket. This is the rule that
 * finds such tickets and words the notice; no Discord in it, so it can be tested. It only applies when the server switched related tickets on, to the bosses it named.
 */
public final class RelatedTickets {
    private RelatedTickets() {}

    /** An open ticket as far as matching goes. {@code boss} is what the ticket's boss question says ("" when it has none). */
    public record Candidate(long ticketId, int number, Long channelId, long requesterId, String boss) {}

    /** The most other tickets offered at once: each gets two buttons and a row holds five. */
    public static final int MAX_SHOWN = 2;

    /** The boss a ticket names, from the answer to its boss question. */
    public static String bossOf(List<Field> fields, List<Answer> answers) {
        return TicketEventPlan.answerFor(fields, answers, FieldPurpose.BOSS);
    }

    /**
     * The other open tickets about the same group boss as {@code me}, oldest first, at most {@link #MAX_SHOWN}. Empty when the extra is off, the boss isn't one of
     * the group bosses, or nobody else asked for it. A member's own other ticket is not a match (they can't group with themselves).
     */
    public static List<Candidate> siblings(HelpExtras extras, Candidate me, List<Candidate> others) {
        if (!extras.relatedTickets() || !extras.isGroupBoss(me.boss())) return List.of();
        List<Candidate> found = new ArrayList<>();
        for (Candidate other : others) {
            if (other.ticketId() == me.ticketId() || other.requesterId() == me.requesterId()) continue;
            if (other.boss() == null || !other.boss().strip().equalsIgnoreCase(me.boss().strip())) continue;
            found.add(other);
        }
        found.sort(java.util.Comparator.comparingInt(Candidate::number));
        return found.size() > MAX_SHOWN ? List.copyOf(found.subList(0, MAX_SHOWN)) : List.copyOf(found);
    }

    public static String number(int number) {
        return "#" + String.format("%04d", number);
    }

    /** The notice posted in the new ticket for helpers and staff. */
    public static String notice(Candidate me, List<Candidate> siblings) {
        StringBuilder text = new StringBuilder("### 🤝 Others want help with " + me.boss() + " too\n");
        for (Candidate s : siblings) {
            text.append("• Ticket ").append(number(s.number())).append(s.channelId() == null ? "" : " (<#" + s.channelId() + ">)").append(" from <@").append(s.requesterId()).append(">\n");
        }
        text.append("-# Group content goes better together. **Link** just connects the tickets (each says where the other is). **Merge** (staff) moves this member into the other ticket and closes this one, with the transcript saved.");
        return text.toString();
    }

    public static String linkId(long from, long other) {
        return "ticket_rel_link:" + from + ":" + other;
    }

    public static String mergeId(long from, long into) {
        return "ticket_rel_merge:" + from + ":" + into;
    }

    /** The two ticket ids in a button id made by {@link #linkId} or {@link #mergeId}, or null. */
    public static long[] parse(String id) {
        String[] parts = id.split(":");
        if (parts.length != 3) return null;
        try {
            return new long[]{Long.parseLong(parts[1]), Long.parseLong(parts[2])};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
