package com.runeclans.bot.wheel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The message posted with the wheel GIF when a winner is announced, from a template a server writes. Placeholders: {@code {winner}} (the winning name),
 * {@code {mention}} (a Discord mention of the winner when their account is linked, otherwise just the name), {@code {week}} (like "7 Oct to 14 Oct") and
 * {@code {entries}} (how many entries were on the wheel). When a week has several winners, {@code {winner}} (or {@code {winners}}) and {@code {mention}} list all of
 * them: "A, B and C". {@code {wheel}} is left as it is: it marks where the wheel's GIF address goes when the message is copied from the website, and
 * {@link #withoutWheel} removes it when the bot posts the GIF itself as an attachment. Pure, so it can be tested.
 */
public final class WheelMessage {
    public static final String DEFAULT = "🎡 This week's Clan Citadel cap winner is {mention}! Well done, and thanks to everyone who capped. ({week})";
    public static final int MAX_LENGTH = 1500;
    /** Where the wheel's GIF address goes in a copied message. */
    public static final String WHEEL = "{wheel}";

    private WheelMessage() {}

    /** What is wrong with a template, in words, or an empty list. */
    public static List<String> problems(String template) {
        List<String> problems = new ArrayList<>();
        if (template == null || template.isBlank()) problems.add("Write the message that is posted with the wheel.");
        else if (template.length() > MAX_LENGTH) problems.add("Keep the message to " + MAX_LENGTH + " characters or fewer.");
        else if (!template.contains("{winner}") && !template.contains("{winners}") && !template.contains("{mention}")) problems.add("The message needs {winner} or {mention} so it says who won.");
        return problems;
    }

    /** Fills in the placeholders. {@code mention} may be null, in which case {@code {mention}} shows the winner's name. */
    public static String render(String template, String winner, String mention, String week, int entries) {
        return renderMany(template, List.of(winner), java.util.Collections.singletonList(mention), week, entries);
    }

    /**
     * As {@link #render}, for a week with several winners in order of place. {@code mentions} is parallel to {@code winners}; a null entry means that
     * winner has no Discord account to mention, so their name is shown instead.
     */
    public static String renderMany(String template, List<String> winners, List<String> mentions, String week, int entries) {
        String text = template == null || template.isBlank() ? DEFAULT : template;
        List<String> names = new ArrayList<>();
        List<String> pings = new ArrayList<>();
        for (int i = 0; i < winners.size(); i++) {
            names.add("**" + winners.get(i) + "**");
            String mention = i < mentions.size() ? mentions.get(i) : null;
            pings.add(Objects.requireNonNullElse(mention, "**" + winners.get(i) + "**"));
        }
        return text.replace("{mention}", join(pings))
                .replace("{winners}", join(names))
                .replace("{winner}", join(names))
                .replace("{week}", week == null ? "" : week)
                .replace("{entries}", Integer.toString(entries));
    }

    /** The message without its {@code {wheel}} tag (the bot attaches the GIF itself), without leaving the blank line the tag sat on. */
    public static String withoutWheel(String text) {
        if (text == null || !text.contains(WHEEL)) return text;
        return text.replaceAll("[ \\t]*\\{wheel}[ \\t]*\\n?", "").stripTrailing();
    }

    /** "A", "A and B", "A, B and C". */
    static String join(List<String> items) {
        if (items.isEmpty()) return "";
        if (items.size() == 1) return items.get(0);
        return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.get(items.size() - 1);
    }
}
