package com.younglings.bot.autodelete;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A channel that removes certain messages when they are posted: Discord invite links, any link, messages carrying attachments, or messages containing
 * certain words. People who can manage messages in the channel are never affected, so moderators can still post what they need.
 * <p>
 * Optionally the rule only applies to some authors ({@link RoleMode}: only people who have one of {@code roleIds}, or everyone except those who do), and
 * the delete can wait {@code delaySeconds} (0 to {@link #MAX_DELAY_SECONDS}). A delayed delete lives in memory only: if the bot restarts during the wait
 * the message is simply not deleted.
 */
public record AutoDeleteRule(long guildId, long channelId, Set<Filter> filters, List<String> words, RoleMode roleMode, Set<Long> roleIds, int delaySeconds) {
    public static final int MAX_RULES_PER_GUILD = 25;
    public static final int MAX_DELAY_SECONDS = 300;
    public static final int MAX_WORDS = 20;
    public static final int MIN_WORD_LENGTH = 3;
    public static final int MAX_WORD_LENGTH = 50;
    public static final int MAX_ROLES = 10;

    public enum Filter {
        INVITES("Discord invites"), LINKS("Links"), ATTACHMENTS("Files and images"), TEXT("Certain words");

        private final String label;

        Filter(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Whether the rule is for everyone, only for people with one of the roles, or for everyone except people with one of the roles. */
    public enum RoleMode { EVERYONE, ONLY_WITH, EXCEPT_WITH }

    private static final Pattern INVITE = Pattern.compile("(?i)(?:discord(?:app)?\\.com/invite|discord\\.gg|discord\\.me|discord\\.io|dsc\\.gg)/\\S+");
    private static final Pattern LINK = Pattern.compile("(?i)\\b(?:https?://|www\\.)\\S+");

    public AutoDeleteRule {
        filters = filters.isEmpty() ? EnumSet.noneOf(Filter.class) : EnumSet.copyOf(filters);
        words = words == null ? List.of() : List.copyOf(words);
        roleMode = roleMode == null ? RoleMode.EVERYONE : roleMode;
        roleIds = roleIds == null ? Set.of() : Set.copyOf(roleIds);
    }

    /** A rule of the original kind: the three message filters, for everyone, deleted at once. */
    public AutoDeleteRule(long guildId, long channelId, Set<Filter> filters) {
        this(guildId, channelId, filters, List.of(), RoleMode.EVERYONE, Set.of(), 0);
    }

    /** Whether a message with this text and number of attachments breaks the rule, and which filter caught it (null if none). */
    public Filter violation(String content, int attachments) {
        if (filters.contains(Filter.INVITES) && INVITE.matcher(content).find()) return Filter.INVITES;
        if (filters.contains(Filter.LINKS) && (LINK.matcher(content).find() || INVITE.matcher(content).find())) return Filter.LINKS;
        if (filters.contains(Filter.ATTACHMENTS) && attachments > 0) return Filter.ATTACHMENTS;
        if (filters.contains(Filter.TEXT) && containsWord(content)) return Filter.TEXT;
        return null;
    }

    /** Whether any of the rule's words appears in {@code content}, ignoring capital letters. Plain text only, never a pattern. */
    public boolean containsWord(String content) {
        if (words.isEmpty() || content == null) return false;
        String lower = content.toLowerCase(Locale.ROOT);
        for (String word : words) if (lower.contains(word.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    /** Whether the rule applies to an author who holds the roles {@code memberRoleIds}. */
    public boolean appliesTo(Set<Long> memberRoleIds) {
        boolean holds = memberRoleIds.stream().anyMatch(roleIds::contains);
        return switch (roleMode) {
            case EVERYONE -> true;
            case ONLY_WITH -> holds;
            case EXCEPT_WITH -> !holds;
        };
    }

    /** What is wrong with one word of the text filter, or null. */
    public static String wordProblem(String word) {
        String text = word == null ? "" : word.strip();
        if (text.length() < MIN_WORD_LENGTH) return "\"" + text + "\" is too short: a word needs at least " + MIN_WORD_LENGTH + " characters, or it would catch ordinary messages.";
        if (text.length() > MAX_WORD_LENGTH) return "A word can be at most " + MAX_WORD_LENGTH + " characters.";
        if (text.contains("\n")) return "A word can't contain a line break.";
        return null;
    }

    /** Words cleaned for storing: trimmed, without blanks or repeats (ignoring case), in order. */
    public static List<String> cleanWords(List<String> raw) {
        List<String> out = new ArrayList<>();
        for (String word : raw) {
            String text = word == null ? "" : word.strip();
            if (!text.isEmpty() && out.stream().noneMatch(w -> w.equalsIgnoreCase(text))) out.add(text);
        }
        return out;
    }

    /** The short note posted (and then removed) when a message is deleted. */
    public static String notice(String mention, Filter filter) {
        return switch (filter) {
            case INVITES -> mention + ", invite links aren't allowed in this channel.";
            case LINKS -> mention + ", links aren't allowed in this channel.";
            case ATTACHMENTS -> mention + ", files and images aren't allowed in this channel.";
            case TEXT -> mention + ", that message isn't allowed in this channel.";
        };
    }
}
