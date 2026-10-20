package com.younglings.bot.commands.ticket;

import com.younglings.bot.events.EventQuickCreate;
import com.younglings.bot.events.EventRules;
import com.younglings.bot.events.EventService.EventInput;
import com.younglings.bot.ticket.HelpExtras;
import com.younglings.bot.ticket.TicketModels.Answer;
import com.younglings.bot.ticket.TicketModels.Field;
import com.younglings.bot.ticket.TicketModels.FieldKind;
import com.younglings.bot.ticket.TicketModels.FieldPurpose;

import java.time.Instant;
import java.util.List;

/**
 * What event a help ticket turns into: a boss night for the boss the ticket asked about, named after it (and its tier), with what the member wrote carried into the
 * description and signups open. Pure, so it can be tested; a message from an {@link IllegalArgumentException} is safe to show.
 */
public final class TicketEventPlan {
    private TicketEventPlan() {}

    public record Plan(EventInput input, String boss, String summary) {}

    /** The answer to the question marked for {@code purpose}, or "" when there is none or it was left empty. */
    static String answerFor(List<Field> fields, List<Answer> answers, FieldPurpose purpose) {
        for (Field field : fields) {
            if (field.purpose() != purpose) continue;
            return answers.stream().filter(a -> a.label().equals(field.label())).map(a -> a.answer() == null ? "" : a.answer().strip()).findFirst().orElse("");
        }
        return "";
    }

    /** The free text the member wrote: the first written (paragraph or short) question that has no part in the help rules. */
    static String noteOf(List<Field> fields, List<Answer> answers) {
        for (Field field : fields) {
            if (field.purpose() != FieldPurpose.NONE || (field.kind() != FieldKind.PARAGRAPH && field.kind() != FieldKind.SHORT)) continue;
            String said = answers.stream().filter(a -> a.label().equals(field.label())).map(a -> a.answer() == null ? "" : a.answer().strip()).findFirst().orElse("");
            if (!said.isEmpty()) return said;
        }
        return "";
    }

    public static Plan plan(List<Field> fields, List<Answer> answers, int ticketNumber, HelpExtras extras, Instant now) {
        String boss = answerFor(fields, answers, FieldPurpose.BOSS);
        if (boss.isEmpty()) throw new IllegalArgumentException("This ticket doesn't name a boss, so there is nothing to build an event around.");
        String tier = answerFor(fields, answers, FieldPurpose.TIER);
        String achievement = answerFor(fields, answers, FieldPurpose.ACHIEVEMENT);
        String note = noteOf(fields, answers);

        String name = boss + (tier.isEmpty() ? "" : " (" + tier + ")") + " group";
        if (name.length() > EventRules.MAX_NAME) name = name.substring(0, EventRules.MAX_NAME - 1) + "…";

        StringBuilder description = new StringBuilder("Made from ticket #" + String.format("%04d", ticketNumber) + ".");
        if (!achievement.isEmpty()) description.append(" Achievement: ").append(achievement).append('.');
        if (!note.isEmpty()) description.append("\n\n").append(note);
        String text = description.length() > EventRules.MAX_DESCRIPTION ? description.substring(0, EventRules.MAX_DESCRIPTION - 1) + "…" : description.toString();

        EventQuickCreate.Plan quick = EventQuickCreate.plan(EventQuickCreate.Kind.BOSS_NIGHT, name, extras.eventDays(), extras.eventStartHours(), boss, now);
        EventInput in = quick.input();
        EventInput withText = new EventInput(in.name(), text, in.template(), in.scope(), in.scoringMode(), in.startsAt(), in.endsAt(), in.maxParticipants(), in.config());
        return new Plan(withText, boss, quick.summary());
    }
}
