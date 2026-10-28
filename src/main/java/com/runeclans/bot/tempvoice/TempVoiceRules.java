package com.runeclans.bot.tempvoice;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The rules for temporary voice channels (join a "Create VC" channel and get your own, deleted when it empties). Pure, so it can be tested; the
 * service and listener only carry out what this decides.
 */
public final class TempVoiceRules {
    private TempVoiceRules() {}

    public static final int MAX_HUBS_PER_GUILD = 5;
    /** Discord allows 500 channels in a server; a server's temporary channels stay far below that. */
    public static final int MAX_TEMP_CHANNELS_PER_GUILD = 40;
    public static final int MAX_NAME = 100;
    public static final int MAX_USER_LIMIT = 99;
    public static final String DEFAULT_PATTERN = "{user}'s channel";
    /** Someone who joins the hub again within this many seconds of the last time is moved to their channel but nothing new is made. */
    public static final int CREATE_COOLDOWN_SECONDS = 5;

    /** One hub: joining {@code hubChannelId} makes a channel in {@code categoryId} (null: the hub's own category) named from {@code namePattern}. */
    public record Hub(long guildId, long hubChannelId, Long categoryId, String namePattern, int userLimit) {}

    /** A temporary channel and who owns it. */
    public record Temp(long channelId, long guildId, long ownerId, long hubChannelId, boolean locked) {}

    /** What the controls on a channel's panel can do. */
    public enum Action { RENAME, LIMIT, LOCK, UNLOCK, PERMIT, KICK, TRANSFER, CLAIM }

    /** The name of a new channel: the pattern with {user} filled in, cleaned of mentions and control characters, and cut to Discord's limit. */
    public static String channelName(String pattern, String userName) {
        String p = pattern == null || pattern.isBlank() ? DEFAULT_PATTERN : pattern;
        return cleanName(p.replace("{user}", userName == null ? "someone" : userName));
    }

    /** A name a member typed or a pattern produced, made safe: no mentions, no control characters, collapsed spaces, at most 100 characters, never empty. */
    public static String cleanName(String raw) {
        String s = raw == null ? "" : raw.replaceAll("[\\p{Cntrl}\\u200B-\\u200F\\u202A-\\u202E]", "").replaceAll("[@#]", "").replaceAll("\\s+", " ").strip();
        if (s.length() > MAX_NAME) s = s.substring(0, MAX_NAME).strip();
        return s.isEmpty() ? "Voice channel" : s;
    }

    /** What is wrong with a hub's settings, as words, or an empty list. */
    public static List<String> hubProblems(String pattern, int userLimit) {
        List<String> problems = new ArrayList<>();
        String p = pattern == null ? "" : pattern.strip();
        if (p.isEmpty()) problems.add("Give the new channels a name, like {user}'s channel.");
        else if (p.length() > MAX_NAME) problems.add("The name can be at most " + MAX_NAME + " characters.");
        else if (cleanName(p.replace("{user}", "x")).equals("Voice channel") && !p.contains("{user}")) problems.add("The name needs some letters.");
        if (userLimit < 0 || userLimit > MAX_USER_LIMIT) problems.add("The member limit has to be between 0 (no limit) and " + MAX_USER_LIMIT + ".");
        return problems;
    }

    /** A member limit typed into the Limit box: a whole number from 0 to 99, or -1 when it isn't one. */
    public static int parseLimit(String raw) {
        if (raw == null || !raw.strip().matches("\\d{1,2}")) return -1;
        int n = Integer.parseInt(raw.strip());
        return n <= MAX_USER_LIMIT ? n : -1;
    }

    /** Whether the channel that was just left should be deleted: it is a temporary channel and nobody is in it. */
    public static boolean deleteWhenEmpty(boolean isTemp, int membersLeft) {
        return isTemp && membersLeft == 0;
    }

    /**
     * Who may press what. Only the owner uses the controls, except Claim, which any member in the channel may use when the owner is no longer in it.
     * {@code pressedByInChannel} says whether the person who pressed is in the voice channel.
     */
    public static boolean allowed(Action action, long ownerId, long pressedBy, boolean ownerInChannel, boolean pressedByInChannel) {
        if (action == Action.CLAIM) return pressedBy != ownerId && pressedByInChannel && !ownerInChannel;
        return pressedBy == ownerId;
    }

    /** Why the press was refused, as words for the person, or null when it is allowed. */
    public static String refusal(Action action, long ownerId, long pressedBy, boolean ownerInChannel, boolean pressedByInChannel) {
        if (allowed(action, ownerId, pressedBy, ownerInChannel, pressedByInChannel)) return null;
        if (action == Action.CLAIM) {
            if (pressedBy == ownerId) return "You already own this channel.";
            if (!pressedByInChannel) return "Join the channel first to claim it.";
            return "The owner is still here, so it can't be claimed.";
        }
        return "Only the channel's owner can do that.";
    }

    /** Whether {@code target} is somebody the owner may permit, kick or hand the channel to: another real member, not a bot. */
    public static boolean validTarget(long ownerId, long target, boolean isBot) {
        return target != ownerId && !isBot;
    }

    /** Whether a person who joined the hub should be given a new channel: they have none yet, the server is under its cap, and they are not hammering the hub. */
    public static boolean shouldCreate(boolean hasChannel, int channelsInGuild, long nowMillis, Long lastCreateMillis) {
        if (hasChannel || channelsInGuild >= MAX_TEMP_CHANNELS_PER_GUILD) return false;
        return lastCreateMillis == null || nowMillis - lastCreateMillis >= CREATE_COOLDOWN_SECONDS * 1000L;
    }

    /** "Pretty" check of the id format of the panel's buttons: {@code tv:<action>:<channelId>}. Returns the pieces or null. */
    public static String[] parseId(String id) {
        if (id == null || !id.startsWith("tv:")) return null;
        String[] parts = id.split(":");
        if (parts.length != 3 || !parts[2].matches("\\d{1,19}")) return null;
        return parts;
    }

    public static Action action(String name) {
        try {
            return Action.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
