package com.runeclans.bot.twitch;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Everything about Twitch alerts that needs neither the network nor Discord, so it can be tested on its own. */
public final class TwitchRules {
    public static final int MAX_STREAMERS_PER_SERVER = 25;
    public static final int MAX_MESSAGE_LENGTH = 300;
    /** After this many polls in a row that find the streamer offline, the stream counts as over. A blip of one poll never does. */
    public static final int OFFLINE_AFTER_MISSES = 2;
    public static final String DEFAULT_MESSAGE = "{streamer} is live on Twitch!";

    private static final Pattern LOGIN = Pattern.compile("[a-z0-9_]{3,25}");

    private TwitchRules() {}

    /** What happens to the go-live post when the stream ends. */
    public enum OfflineAction {
        /** Edit the post to say the stream has ended (keeps the history, drops the ping). */
        EDIT,
        /** Delete the post. */
        DELETE,
        /** Leave the post as it is. */
        KEEP;

        public static Optional<OfflineAction> ofKey(String key) {
            for (OfflineAction action : values()) if (action.name().equalsIgnoreCase(key)) return Optional.of(action);
            return Optional.empty();
        }
    }

    /**
     * A Twitch login from what a person typed: a channel address or just the name, in any case. Empty when it cannot be one. Twitch logins are 3 to
     * 25 letters, numbers and underscores (the page address {@code twitch.tv/name} is accepted too).
     */
    public static Optional<String> loginFrom(String typed) {
        if (typed == null) return Optional.empty();
        String text = typed.strip().toLowerCase(Locale.ROOT);
        text = text.replaceFirst("^https?://", "").replaceFirst("^(www\\.|m\\.)?twitch\\.tv/", "");
        int end = text.indexOf('/');
        if (end >= 0) text = text.substring(0, end);
        int query = text.indexOf('?');
        if (query >= 0) text = text.substring(0, query);
        return LOGIN.matcher(text).matches() ? Optional.of(text) : Optional.empty();
    }

    /** The go-live line: {@code {streamer}}, {@code {title}}, {@code {game}} and {@code {url}} are filled in; an empty template uses the default. */
    public static String render(String template, String streamer, String title, String game, String url) {
        String text = template == null || template.isBlank() ? DEFAULT_MESSAGE : template;
        return text.replace("{streamer}", streamer).replace("{title}", title).replace("{game}", game == null ? "" : game).replace("{url}", url);
    }

    /** Twitch's preview address has {@code {width}x{height}} to fill in; the time defeats Discord's cache so the picture is the current one. */
    public static String thumbnail(String template, int width, int height, long nowMillis) {
        if (template == null || template.isBlank()) return null;
        return template.replace("{width}", Integer.toString(width)).replace("{height}", Integer.toString(height)) + "?t=" + (nowMillis / 60_000);
    }

    /** What the poller does with a streamer. */
    public enum Step {
        /** Nothing to do. */
        NOTHING,
        /** First look at this streamer: remember what is live now (a stream already running is recorded, never announced) and mark them looked at. */
        PRIME,
        /** A new stream has started: post the go-live message. */
        ANNOUNCE,
        /** Seen live again after a missed poll: forget the misses. */
        RESET_MISSES,
        /** Not live, but not for long enough to call the stream over. */
        COUNT_MISS,
        /** The stream is over: edit or delete the post (as the server chose) and forget the stream. */
        END
    }

    /**
     * @param primed       false until the streamer has been looked at once
     * @param postedStream the id of the stream already announced (or recorded at the first look), or null
     * @param liveStream   the id of the stream live now, or null if offline
     * @param misses       polls in a row that found them offline since they were last seen live
     */
    public static Step decide(boolean primed, String postedStream, String liveStream, int misses) {
        if (!primed) return Step.PRIME;
        if (liveStream != null) {
            if (!liveStream.equals(postedStream)) return Step.ANNOUNCE;
            return misses > 0 ? Step.RESET_MISSES : Step.NOTHING;
        }
        if (postedStream == null) return Step.NOTHING;
        return misses + 1 >= OFFLINE_AFTER_MISSES ? Step.END : Step.COUNT_MISS;
    }
}
