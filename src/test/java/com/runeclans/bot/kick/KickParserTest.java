package com.runeclans.bot.kick;

import com.runeclans.bot.kick.KickClient.Stream;
import com.runeclans.bot.kick.KickClient.KickParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KickParserTest {
    private static final String CHANNELS = """
            {"message":"ok","data":[
              {"broadcaster_user_id":1,"slug":"Some_Streamer","stream_title":"Friday raids","category":{"id":5,"name":"Old School RuneScape","thumbnail":"x"},
               "stream":{"is_live":true,"viewer_count":42,"thumbnail":"https://cdn/live-thumb.jpg","start_time":"2026-10-20T01:00:00Z"}},
              {"broadcaster_user_id":2,"slug":"quiet","stream_title":"","category":null,"stream":{"is_live":false,"viewer_count":0,"thumbnail":"","start_time":"2026-10-19T01:00:00Z"}},
              {"broadcaster_user_id":3,"slug":"nostream","stream_title":"x","category":{"id":1,"name":"y","thumbnail":""}}
            ]}""";

    @Test
    void onlyStreamsThatAreActuallyLiveAreRead() {
        List<Stream> streams = KickParser.streams(CHANNELS);

        assertEquals(1, streams.size());
        Stream s = streams.get(0);
        assertEquals("2026-10-20T01:00:00Z", s.id(), "a stream is identified by when it started");
        assertEquals("some_streamer", s.login(), "names are compared in lower case");
        assertEquals("Some_Streamer", s.name());
        assertEquals("Friday raids", s.title());
        assertEquals("Old School RuneScape", s.game());
        assertEquals(42, s.viewers());
    }

    @Test
    void noOneLiveIsAnEmptyList() {
        assertTrue(KickParser.streams("{\"data\":[]}").isEmpty());
    }

    @Test
    void aChannelThatExistsOrNot() {
        var found = KickParser.user("{\"data\":[{\"slug\":\"Bob\"}]}");

        assertEquals("bob", found.orElseThrow().login());
        assertEquals("Bob", found.orElseThrow().name());
        assertTrue(KickParser.user("{\"data\":[]}").isEmpty());
    }
}
