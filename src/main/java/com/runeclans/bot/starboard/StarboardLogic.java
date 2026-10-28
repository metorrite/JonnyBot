package com.runeclans.bot.starboard;

import java.util.Collection;

/** The starboard's rules, apart from Discord so they can be tested. */
public final class StarboardLogic {
    private StarboardLogic() {}

    /** One person who reacted. */
    public record Reactor(long id, boolean bot) {}

    /** How many stars count: every real person who reacted, except (unless the server allows it) the message's own author. */
    public static int count(Collection<Reactor> reactors, long authorId, boolean selfStar) {
        int n = 0;
        for (Reactor r : reactors) {
            if (r.bot()) continue;
            if (!selfStar && r.id() == authorId) continue;
            n++;
        }
        return n;
    }

    /** The reactions a message in {@code channelId} (or in a thread of {@code parentId}) needs: the channel's own rule, else its parent's, else the server's. */
    public static int thresholdFor(StarboardConfig config, long channelId, Long parentId) {
        Integer own = config.channelThresholds().get(channelId);
        if (own == null && parentId != null) own = config.channelThresholds().get(parentId);
        return own != null ? own : config.threshold();
    }

    /** Whether messages from this channel may be copied at all: on the allow-list when there is one, and visible to everyone when hidden channels are skipped. */
    public static boolean sourceAllowed(StarboardConfig config, long channelId, Long parentId, boolean visibleToEveryone) {
        if (!config.onlyChannels().isEmpty() && !config.onlyChannels().contains(channelId) && (parentId == null || !config.onlyChannels().contains(parentId))) return false;
        return !config.skipHiddenChannels() || visibleToEveryone;
    }

    /** What to do about a message now that it has {@code stars}. */
    public enum Action { NOTHING, POST, UPDATE, REMOVE }

    public static Action decide(int stars, int threshold, boolean alreadyPosted) {
        if (stars >= threshold) return alreadyPosted ? Action.UPDATE : Action.POST;
        return alreadyPosted ? Action.REMOVE : Action.NOTHING;
    }

    /** The line above the copy on the board. */
    public static String headline(String emoji, int stars, long channelId) {
        return emoji + " **" + stars + "** | <#" + channelId + ">";
    }
}
