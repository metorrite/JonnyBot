package com.runeclans.bot.twitch;

import com.runeclans.bot.twitch.TwitchClient.Stream;
import com.runeclans.bot.twitch.TwitchClient.TwitchParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TwitchParserTest {
    private static final String STREAMS = """
            {"data":[
              {"id":"40001","user_id":"1","user_login":"Some_Streamer","user_name":"Some_Streamer","game_name":"Old School RuneScape","type":"live","title":"Friday raids","viewer_count":42,"thumbnail_url":"https://cdn/live_user_some-{width}x{height}.jpg"},
              {"id":"40002","user_id":"2","user_login":"rerunner","user_name":"ReRunner","game_name":"","type":"","title":"a rerun","viewer_count":0,"thumbnail_url":""}
            ],"pagination":{}}""";

    @Test
    void onlyStreamsThatAreActuallyLiveAreRead() {
        List<Stream> streams = TwitchParser.streams(STREAMS);

        assertEquals(1, streams.size());
        Stream s = streams.get(0);
        assertEquals("40001", s.id());
        assertEquals("some_streamer", s.login(), "logins are compared in lower case");
        assertEquals("Friday raids", s.title());
        assertEquals("Old School RuneScape", s.game());
        assertEquals(42, s.viewers());
    }

    @Test
    void noOneLiveIsAnEmptyList() {
        assertTrue(TwitchParser.streams("{\"data\":[]}").isEmpty());
    }

    @Test
    void aChannelThatExistsOrNot() {
        var found = TwitchParser.user("{\"data\":[{\"login\":\"Bob\",\"display_name\":\"Bob\",\"profile_image_url\":\"https://cdn/p.png\"}]}");

        assertEquals("bob", found.orElseThrow().login());
        assertEquals("Bob", found.orElseThrow().name());
        assertTrue(TwitchParser.user("{\"data\":[]}").isEmpty());
    }
}
