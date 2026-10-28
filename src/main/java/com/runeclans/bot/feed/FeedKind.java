package com.runeclans.bot.feed;

/** The kinds of outside feed a server can follow. */
public enum FeedKind {
    YOUTUBE("YouTube", "{author} posted a new video: {title}\n{url}"),
    REDDIT("Reddit", "New post on r/{source}: {title}\n{url}");

    private final String label;
    private final String defaultTemplate;

    FeedKind(String label, String defaultTemplate) {
        this.label = label;
        this.defaultTemplate = defaultTemplate;
    }

    public String label() {
        return label;
    }

    public String defaultTemplate() {
        return defaultTemplate;
    }

    public static FeedKind parse(String raw) {
        if (raw == null) return null;
        for (FeedKind kind : values()) if (kind.name().equalsIgnoreCase(raw.strip())) return kind;
        return null;
    }
}
