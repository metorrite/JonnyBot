package com.younglings.bot.feed;

import java.util.regex.Pattern;

/**
 * One followed feed: a YouTube channel or a subreddit, posted to one channel with a message template. {@code source} is the YouTube
 * channel id ("UC...") or the subreddit name without "r/". {@code flair} (Reddit only) limits posts to that flair; blank means all.
 */
public record FeedSubscription(long id, long guildId, FeedKind kind, String source, String flair, long channelId, String template, boolean enabled) {
    public static final int MAX_PER_KIND_PER_GUILD = 10;
    public static final int MAX_TEMPLATE_LENGTH = 500;
    public static final int MAX_FLAIR_LENGTH = 64;
    private static final Pattern YOUTUBE_ID = Pattern.compile("UC[0-9A-Za-z_-]{22}");
    private static final Pattern SUBREDDIT = Pattern.compile("[A-Za-z0-9_]{2,21}");

    /** The canonical source for what a person typed or pasted (a channel URL, "r/name", ...), or null when it isn't one. */
    public static String normalizeSource(FeedKind kind, String raw) {
        if (raw == null) return null;
        String text = raw.strip();
        if (kind == FeedKind.YOUTUBE) {
            var matcher = YOUTUBE_ID.matcher(text);
            return matcher.find() ? matcher.group() : null;
        }
        String name = text.replaceFirst("(?i)^(https?://)?(www\\.|old\\.)?(reddit\\.com)?/?", "").replaceFirst("(?i)^r/", "").replaceAll("/.*$", "");
        return SUBREDDIT.matcher(name).matches() ? name : null;
    }

    /** The URL of the feed to read. */
    public String feedUrl() {
        return kind == FeedKind.YOUTUBE
                ? "https://www.youtube.com/feeds/videos.xml?channel_id=" + source
                : "https://www.reddit.com/r/" + source + "/new.json?limit=25&raw_json=1";
    }

    /** The message for an item: the template with {title}, {url}, {author}, {source} filled in. */
    public String render(FeedItem item) {
        String text = (template == null || template.isBlank() ? kind.defaultTemplate() : template)
                .replace("{title}", item.title()).replace("{url}", item.url())
                .replace("{author}", item.author().isBlank() ? "Someone" : item.author()).replace("{source}", source);
        return text.length() > 1900 ? text.substring(0, 1900) : text;
    }
}
