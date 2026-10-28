package com.runeclans.bot.feed;

/** One entry read from a feed. {@code id} is what dedupes it; {@code author} may be empty. */
public record FeedItem(String id, String title, String url, String author) {
}
