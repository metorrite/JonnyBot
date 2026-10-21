package com.younglings.bot.customtag;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The variables a tag's text can use: {@code {user}} (a mention of the member), {@code {username}} (their name), {@code {server}}, {@code {channel}} (a
 * channel mention), {@code {args}} (everything the member typed after the tag's name) and {@code {arg1}} to {@code {arg9}} (the first nine words of it).
 * A variable with nothing to fill becomes empty. The result is cut to Discord's 2000 characters. Pure, so it can be tested.
 */
public final class CustomTagVariables {
    private static final Pattern ARG = Pattern.compile("\\{arg([1-9])}");

    private CustomTagVariables() {}

    public record Context(String userMention, String username, String serverName, String channelMention, String args) {}

    public static String fill(String template, Context c) {
        String args = c.args() == null ? "" : c.args().strip();
        String[] words = args.isEmpty() ? new String[0] : args.split("\\s+");
        Matcher m = ARG.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            int i = Integer.parseInt(m.group(1)) - 1;
            m.appendReplacement(out, Matcher.quoteReplacement(i < words.length ? words[i] : ""));
        }
        m.appendTail(out);
        String text = out.toString().replace("{user}", c.userMention()).replace("{username}", c.username()).replace("{server}", c.serverName())
                .replace("{channel}", c.channelMention()).replace("{args}", args);
        return text.length() > 2000 ? text.substring(0, 2000) : text;
    }
}
