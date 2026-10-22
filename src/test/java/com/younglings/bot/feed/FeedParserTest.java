package com.younglings.bot.feed;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FeedParserTest {
    private static String sample(String name) throws IOException {
        try (var in = FeedParserTest.class.getResourceAsStream("/feed/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void youtubeEntriesKeepOrderAndSkipBrokenOnes() throws IOException {
        List<FeedItem> items = FeedParser.youtube(sample("youtube-sample.xml"));
        assertEquals(2, items.size());
        assertEquals("NEWEST000001", items.get(0).id());
        assertEquals("Barrows & chill: a \"full\" run", items.get(0).title());
        assertEquals("https://www.youtube.com/watch?v=NEWEST000001", items.get(0).url());
        assertEquals("Example Clan", items.get(0).author());
    }

    @Test
    void youtubeRejectsWhatIsNotAFeedAndDoctypes() {
        assertThrows(IllegalArgumentException.class, () -> FeedParser.youtube("<html>Sorry, rate limited"));
        assertThrows(IllegalArgumentException.class, () -> FeedParser.youtube("<!DOCTYPE feed [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><feed>&x;</feed>"));
    }

    @Test
    void redditSkipsNsfwAndStickiedAlways() throws IOException {
        List<FeedItem> items = FeedParser.reddit(sample("reddit-sample.json"), "");
        assertEquals(List.of("abc111", "abc444", "abc555"), items.stream().map(FeedItem::id).toList());
        assertEquals("https://www.reddit.com/r/runescape/comments/abc111/patch_notes/", items.get(0).url());
        assertEquals("Mod_Ash", items.get(0).author());
    }

    @Test
    void redditFlairFilterIsCaseInsensitiveAndExact() throws IOException {
        assertEquals(List.of("abc111"), FeedParser.reddit(sample("reddit-sample.json"), " news ").stream().map(FeedItem::id).toList());
        assertEquals(List.of(), FeedParser.reddit(sample("reddit-sample.json"), "Nope"));
    }

    @Test
    void redditRejectsNonListings() {
        assertThrows(IllegalArgumentException.class, () -> FeedParser.reddit("<html>blocked</html>", ""));
        assertThrows(IllegalArgumentException.class, () -> FeedParser.reddit("{\"message\":\"Too Many Requests\",\"error\":429}", ""));
    }
}
