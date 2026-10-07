package com.younglings.bot.commands.ticket;

import com.younglings.bot.ticket.TicketModels.Field;
import com.younglings.bot.ticket.TicketModels.FieldKind;
import com.younglings.bot.ticket.TicketModels.FieldPurpose;
import com.younglings.bot.ticket.TicketModels.HelpKind;
import com.younglings.bot.ticket.TicketModels.HelpSettings;
import com.younglings.bot.ticket.TicketModels.Option;
import com.younglings.bot.ticket.TicketModels.Panel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The PvM Help system's decisions, with no Discord calls in them: whether a ticket pings anyone when it opens and how long it waits
 * before escalating, whether a guest must describe earlier attempts, and what a valid set of settings is. Kept apart so every rule
 * can be tested directly.
 */
public final class HelpRules {
    private HelpRules() {}

    public static final int MIN_HOURS = 1;
    public static final int MAX_HOURS = 720;

    /** How a ticket's pings are set up as it opens: whether the helpers are pinged right away, and the hours before the next role up is pinged ({@code null} = never). */
    public record Pings(boolean pingOnOpen, Integer escalationHours) {}

    /**
     * A member's ticket follows the member settings. A guest's pings nobody unless guest pings are switched on, and then follows the
     * guest settings. (A guest is anyone who isn't a clan member, whether or not they have linked a RuneScape name.)
     */
    public static Pings pingsFor(HelpSettings settings, boolean member) {
        if (member) return new Pings(settings.memberPingOnOpen(), settings.memberEscalationHours());
        if (!settings.guestPingsEnabled()) return new Pings(false, null);
        return new Pings(settings.guestPingOnOpen(), settings.guestEscalationHours());
    }

    /** The tier names that count as Master and above, trimmed and without blanks. */
    public static List<String> highTierLabels(HelpSettings settings) {
        if (settings.highTierLabels() == null) return List.of();
        return Arrays.stream(settings.highTierLabels().split(","))
                .map(String::strip)
                .filter(label -> !label.isEmpty())
                .toList();
    }

    /**
     * On a CA Help panel, a guest who picks a Master or Grandmaster tier has to say what they have already tried. Returns the sentence to
     * show them when they haven't, or empty when the ticket may open. Members, other tiers, other panels and a panel with no attempts
     * question are never held up.
     *
     * @param rawValues each question's submitted value by field id (a dropdown's value is the chosen option's id)
     */
    public static Optional<String> attemptsProblem(HelpSettings settings, Panel panel, List<Field> fields, Map<Long, String> rawValues, boolean member) {
        if (member || panel.helpKind() != HelpKind.CA || !settings.guestHighTierNeedsAttempts()) return Optional.empty();

        Field tier = fields.stream().filter(f -> f.purpose() == FieldPurpose.TIER && f.kind() == FieldKind.SELECT).findFirst().orElse(null);
        Field attempts = fields.stream().filter(f -> f.purpose() == FieldPurpose.ATTEMPTS).findFirst().orElse(null);
        if (tier == null || attempts == null) return Optional.empty();

        String picked = rawValues.get(tier.id());
        String pickedLabel = tier.options().stream().filter(o -> String.valueOf(o.id()).equals(picked)).map(Option::label).findFirst().orElse(null);
        if (pickedLabel == null || highTierLabels(settings).stream().noneMatch(pickedLabel::equalsIgnoreCase)) return Optional.empty();

        String said = rawValues.get(attempts.id());
        if (said != null && !said.isBlank()) return Optional.empty();
        return Optional.of("**" + pickedLabel + "** tickets from guests need a note of your earlier attempts in \"" + attempts.label()
                + "\". Open the ticket again and fill that in.");
    }

    /** Everything wrong with a set of settings, one sentence each — empty if they can be saved. */
    public static List<String> validate(HelpSettings settings) {
        List<String> problems = new ArrayList<>();
        checkHours(problems, settings.memberEscalationHours(), "The member escalation wait");
        checkHours(problems, settings.guestEscalationHours(), "The guest escalation wait");
        if (settings.guidelines() != null && settings.guidelines().length() > HelpSettings.MAX_GUIDELINES) {
            problems.add("The guidelines must be " + HelpSettings.MAX_GUIDELINES + " characters or fewer.");
        }
        if (settings.highTierLabels() == null || settings.highTierLabels().length() > 200) problems.add("The Master and above tier names must be 200 characters or fewer.");
        else if (settings.guestHighTierNeedsAttempts() && highTierLabels(settings).isEmpty()) problems.add("Name at least one tier for the earlier-attempts rule, or switch the rule off.");
        return problems;
    }

    private static void checkHours(List<String> problems, Integer hours, String what) {
        if (hours != null && (hours < MIN_HOURS || hours > MAX_HOURS)) problems.add(what + " must be between " + MIN_HOURS + " and " + MAX_HOURS + " hours.");
    }

    /** Problems with how a panel's questions are marked for the help rules; the dashboard's Save adds these to the general panel checks. */
    public static List<String> validatePanelHelp(Panel panel, List<Field> fields) {
        List<String> problems = new ArrayList<>();
        long tiers = fields.stream().filter(f -> f.purpose() == FieldPurpose.TIER).count();
        long attempts = fields.stream().filter(f -> f.purpose() == FieldPurpose.ATTEMPTS).count();

        if (!panel.isHelpPanel()) {
            if (tiers + attempts > 0) problems.add("Questions can only be marked as the tier or earlier attempts on a PvM Help or CA Help panel.");
            return problems;
        }
        if (tiers > 1) problems.add("Only one question can be marked as the tier.");
        if (attempts > 1) problems.add("Only one question can be marked as earlier attempts.");
        for (Field field : fields) {
            if (field.purpose() == FieldPurpose.TIER && field.kind() != FieldKind.SELECT) problems.add("The question marked as the tier (\"" + field.label() + "\") has to be a dropdown.");
            if (field.purpose() == FieldPurpose.ATTEMPTS && field.kind() != FieldKind.SHORT && field.kind() != FieldKind.PARAGRAPH) problems.add("The question marked as earlier attempts (\"" + field.label() + "\") has to be a written answer.");
        }
        if (panel.helpKind() == HelpKind.CA) {
            boolean requiredTier = fields.stream().anyMatch(f -> f.purpose() == FieldPurpose.TIER && f.kind() == FieldKind.SELECT && f.required());
            if (!requiredTier) problems.add("A CA Help panel needs a required dropdown marked as the tier.");
        }
        return problems;
    }
}
