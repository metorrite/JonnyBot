package com.younglings.bot.autodelete;

import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A channel that removes certain messages the moment they are posted: Discord invite links, any link, or messages carrying attachments.
 * People who can manage messages in the channel are never affected, so moderators can still post what they need.
 */
public record AutoDeleteRule(long guildId, long channelId, Set<Filter> filters) {
    public static final int MAX_RULES_PER_GUILD = 25;

    public enum Filter {
        INVITES("Discord invites"), LINKS("Links"), ATTACHMENTS("Files and images");

        private final String label;

        Filter(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private static final Pattern INVITE = Pattern.compile("(?i)(?:discord(?:app)?\\.com/invite|discord\\.gg|discord\\.me|discord\\.io|dsc\\.gg)/\\S+");
    private static final Pattern LINK = Pattern.compile("(?i)\\b(?:https?://|www\\.)\\S+");

    public AutoDeleteRule {
        filters = filters.isEmpty() ? EnumSet.noneOf(Filter.class) : EnumSet.copyOf(filters);
    }

    /** Whether a message with this text and number of attachments breaks the rule, and which filter caught it (null if none). */
    public Filter violation(String content, int attachments) {
        if (filters.contains(Filter.INVITES) && INVITE.matcher(content).find()) return Filter.INVITES;
        if (filters.contains(Filter.LINKS) && (LINK.matcher(content).find() || INVITE.matcher(content).find())) return Filter.LINKS;
        if (filters.contains(Filter.ATTACHMENTS) && attachments > 0) return Filter.ATTACHMENTS;
        return null;
    }

    /** The short note posted (and then removed) when a message is deleted. */
    public static String notice(String mention, Filter filter) {
        return switch (filter) {
            case INVITES -> mention + ", invite links aren't allowed in this channel.";
            case LINKS -> mention + ", links aren't allowed in this channel.";
            case ATTACHMENTS -> mention + ", files and images aren't allowed in this channel.";
        };
    }
}
