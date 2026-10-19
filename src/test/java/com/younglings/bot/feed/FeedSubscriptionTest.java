package com.younglings.bot.feed;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FeedSubscriptionTest {
    private static final String ID = "UCabcdefghijklmnopqrstuv";

    @Test
    void youtubeSourceIsTheChannelIdFromAnythingPasted() {
        assertEquals(ID, FeedSubscription.normalizeSource(FeedKind.YOUTUBE, ID));
        assertEquals(ID, FeedSubscription.normalizeSource(FeedKind.YOUTUBE, " https://www.youtube.com/channel/" + ID + "/videos "));
        assertNull(FeedSubscription.normalizeSource(FeedKind.YOUTUBE, "https://www.youtube.com/@someone"));
        assertNull(FeedSubscription.normalizeSource(FeedKind.YOUTUBE, ""));
    }

    @Test
    void subredditSourceAcceptsNamesAndLinks() {
        assertEquals("runescape", FeedSubscription.normalizeSource(FeedKind.REDDIT, "runescape"));
        assertEquals("runescape", FeedSubscription.normalizeSource(FeedKind.REDDIT, "r/runescape"));
        assertEquals("runescape", FeedSubscription.normalizeSource(FeedKind.REDDIT, "/r/RuneScape/".toLowerCase()));
        assertEquals("runescape", FeedSubscription.normalizeSource(FeedKind.REDDIT, "https://www.reddit.com/r/runescape/new"));
        assertNull(FeedSubscription.normalizeSource(FeedKind.REDDIT, "a"));
        assertNull(FeedSubscription.normalizeSource(FeedKind.REDDIT, "bad name!"));
    }

    @Test
    void feedUrlsPointAtTheRightPlace() {
        assertEquals("https://www.youtube.com/feeds/videos.xml?channel_id=" + ID, new FeedSubscription(1, 2, FeedKind.YOUTUBE, ID, "", 3, "", true).feedUrl());
        assertEquals("https://www.reddit.com/r/runescape/new.json?limit=25&raw_json=1", new FeedSubscription(1, 2, FeedKind.REDDIT, "runescape", "", 3, "", true).feedUrl());
    }

    @Test
    void renderFillsThePlaceholdersOrUsesTheDefault() {
        FeedItem item = new FeedItem("v1", "Big win", "https://x/v1", "Clan TV");
        assertEquals("Clan TV: Big win https://x/v1", new FeedSubscription(1, 2, FeedKind.YOUTUBE, ID, "", 3, "{author}: {title} {url}", true).render(item));
        assertEquals("Clan TV posted a new video: Big win\nhttps://x/v1", new FeedSubscription(1, 2, FeedKind.YOUTUBE, ID, "", 3, "", true).render(item));
        assertEquals("Someone", new FeedSubscription(1, 2, FeedKind.YOUTUBE, ID, "", 3, "{author}", true).render(new FeedItem("v", "t", "u", "")));
    }
}
