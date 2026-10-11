package com.younglings.bot.starboard;

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
