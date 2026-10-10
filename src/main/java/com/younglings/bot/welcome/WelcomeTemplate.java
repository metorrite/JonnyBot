package com.younglings.bot.welcome;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills the variables in a welcome message. The set matches Dyno's Welcome module, so a message can be moved across
 * as it is:
 * <pre>
 * {user}      the new member, as a mention          {username}  their name
 * {avatar}    their avatar's address                {server}    the server's name
 * {channel}   the welcome channel's name            {count}     how many members the server has now
 * {@name}     a member by name                      {&amp;role}     a role by name
 * {#channel}  a channel by name                     {everyone} {here}  show the words, never ping
 * </pre>
 * A {@code {…}} that isn't one of these is left exactly as typed. Mentions only come alive in message text, embed
 * descriptions and field values; in a title, author or footer Discord shows them as raw ids, so those places get the
 * plain name instead.
 */
public final class WelcomeTemplate {
    private WelcomeTemplate() {}

    /** Everything a message can ask about; the real one reads from Discord, tests and previews supply their own. */
    public interface Lookup {
        String userMention();

        String username();

        String avatarUrl();

        String serverName();

        String channelName();

        int memberCount();

        /** A mention for the member with this name, or {@code null} if there isn't one to be found. */
        String findUser(String name);

        String findRole(String name);

        String findChannel(String name);
    }

    /** The variables, for the editor to list: name and what it gives. */
    public static final List<String[]> VARIABLES = List.of(
            new String[]{"{user}", "The new member, as a mention"},
            new String[]{"{username}", "The new member's name"},
            new String[]{"{avatar}", "The new member's avatar address (use it as an image or thumbnail)"},
            new String[]{"{server}", "The server's name"},
            new String[]{"{channel}", "The welcome channel's name"},
            new String[]{"{count}", "How many members the server has"},
            new String[]{"{@name}", "Mention a member by name"},
            new String[]{"{&role}", "Mention a role by name"},
            new String[]{"{#channel}", "Link a channel by name"},
            new String[]{"{everyone}", "Shows @everyone without pinging"},
            new String[]{"{here}", "Shows @here without pinging"},
            new String[]{"{rs_button}", "Where the link button goes (Container messages only)"});

    private static final Pattern TOKEN = Pattern.compile("\\{([^{}\\n]{1,100})}");

    /** For text where mentions render: the message line, an embed description, a field value. */
    public static String render(String template, Lookup lookup) {
        return render(template, lookup, false);
    }

    /** For titles, author, footer and addresses, where a mention would show as a raw id: names instead. */
    public static String renderPlain(String template, Lookup lookup) {
        return render(template, lookup, true);
    }

    private static String render(String template, Lookup lookup, boolean plain) {
        if (template == null || template.isEmpty()) return "";
        Matcher matcher = TOKEN.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = resolve(matcher.group(1), lookup, plain);
            matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? matcher.group() : value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String resolve(String token, Lookup lookup, boolean plain) {
        switch (token) {
            case "user":
                return plain ? lookup.username() : lookup.userMention();
            case "username":
                return lookup.username();
            case "avatar":
                return lookup.avatarUrl();
            case "server":
                return lookup.serverName();
            case "channel":
                return lookup.channelName();
            case "count":
                return Integer.toString(lookup.memberCount());
            case "everyone":
                return "@everyone";
            case "here":
                return "@here";
            default:
                break;
        }
        if (token.length() < 2) return null;
        String name = token.substring(1).strip();
        if (name.isEmpty()) return null;
        return switch (token.charAt(0)) {
            case '@' -> orPlain(plain ? null : lookup.findUser(name), "@" + name);
            case '&' -> orPlain(plain ? null : lookup.findRole(name), "@" + name);
            case '#' -> orPlain(plain ? null : lookup.findChannel(name), "#" + name);
            default -> null;
        };
    }

    private static String orPlain(String mention, String fallback) {
        return mention != null ? mention : fallback;
    }
}
