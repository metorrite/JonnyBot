package com.younglings.bot.wheel;

import java.util.ArrayList;
import java.util.List;

/**
 * The message posted with the wheel GIF when a winner is announced, from a template a server writes. Placeholders: {@code {winner}} (the winning name),
 * {@code {mention}} (a Discord mention of the winner when their account is linked, otherwise just the name), {@code {week}} (like "7 Oct to 14 Oct") and
 * {@code {entries}} (how many entries were on the wheel). Pure, so it can be tested.
 */
public final class WheelMessage {
    public static final String DEFAULT = "🎡 This week's Clan Citadel cap winner is {mention}! Well done, and thanks to everyone who capped. ({week})";
    public static final int MAX_LENGTH = 1500;

    private WheelMessage() {}

    /** What is wrong with a template, in words, or an empty list. */
    public static List<String> problems(String template) {
        List<String> problems = new ArrayList<>();
        if (template == null || template.isBlank()) problems.add("Write the message that is posted with the wheel.");
        else if (template.length() > MAX_LENGTH) problems.add("Keep the message to " + MAX_LENGTH + " characters or fewer.");
        else if (!template.contains("{winner}") && !template.contains("{mention}")) problems.add("The message needs {winner} or {mention} so it says who won.");
        return problems;
    }

    /** Fills in the placeholders. {@code mention} may be null, in which case {@code {mention}} shows the winner's name. */
    public static String render(String template, String winner, String mention, String week, int entries) {
        String text = template == null || template.isBlank() ? DEFAULT : template;
        return text.replace("{mention}", mention == null ? "**" + winner + "**" : mention)
                .replace("{winner}", "**" + winner + "**")
                .replace("{week}", week == null ? "" : week)
                .replace("{entries}", Integer.toString(entries));
    }
}
