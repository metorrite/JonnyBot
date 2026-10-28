package com.runeclans.bot.twitch;

import com.runeclans.bot.internal.DirectorySiteStreamRoutes;
import com.runeclans.bot.twitch.TwitchClient.Stream;
import com.runeclans.bot.twitch.TwitchRepository.Settings;
import com.runeclans.bot.twitch.TwitchRepository.Streamer;
import com.runeclans.bot.twitch.TwitchRules.OfflineAction;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The clan website's "Live now" widget (T-587): the server's followed streamers live at the last poll. Made-up server id and channels. */
class TwitchLiveNowTest {
    private static final long GUILD = 987654321031002L;

    private static Streamer streamer(long id, String login, boolean enabled) {
        return new Streamer(id, GUILD, login, "", 1L, null, null, enabled, true, null, null, null, 0);
    }

    private static Stream stream(String login, int viewers) {
        return new Stream("s-" + login, login, login.toUpperCase(), "Bossing", "RuneScape", viewers, "https://img.example/" + login + "-{width}x{height}.jpg");
    }

    private static TwitchService service(boolean configured, boolean alertsOn) {
        TwitchRepository repository = mock(TwitchRepository.class);
        TwitchClient client = mock(TwitchClient.class);
        when(client.configured()).thenReturn(configured);
        when(repository.settings(GUILD)).thenReturn(new Settings(alertsOn, OfflineAction.EDIT));
        when(repository.streamers(GUILD)).thenReturn(List.of(streamer(1, "quietone", true), streamer(2, "bigstream", true), streamer(3, "smallstream", true),
                streamer(4, "paused", false)));
        return new TwitchService(repository, client);
    }

    @Test
    void onlyTheServersFollowedStreamersWhoAreLiveMostViewersFirst() {
        TwitchService twitch = service(true, true);
        Instant now = Instant.parse("2026-10-26T20:00:00Z");
        twitch.remember(Map.of("bigstream", stream("bigstream", 120), "smallstream", stream("smallstream", 4), "paused", stream("paused", 900),
                "elsewhere", stream("elsewhere", 50)), now);
        List<Stream> live = twitch.liveFor(GUILD, now.plusSeconds(60));
        assertEquals(List.of("bigstream", "smallstream"), live.stream().map(Stream::login).toList(), "a paused streamer or another server's isn't shown");
        assertTrue(twitch.liveFor(GUILD, now.plus(TwitchService.STALE).plusSeconds(1)).isEmpty(), "an old answer isn't 'live now'");
    }

    @Test
    void nothingWhileTwitchAlertsAreOff() {
        Instant now = Instant.parse("2026-10-26T20:00:00Z");
        TwitchService off = service(true, false);
        off.remember(Map.of("bigstream", stream("bigstream", 120)), now);
        assertFalse(off.followsStreamers(GUILD));
        assertTrue(off.liveFor(GUILD, now).isEmpty());
        TwitchService noApp = service(false, true);
        noApp.remember(Map.of("bigstream", stream("bigstream", 120)), now);
        assertTrue(noApp.liveFor(GUILD, now).isEmpty());
    }

    @Test
    void theAnswerHasAWatchLinkAndAThumbnailThatChangesEveryFiveMinutes() {
        long at = Instant.parse("2026-10-26T20:03:00Z").toEpochMilli();
        DataObject out = DirectorySiteStreamRoutes.json(true, List.of(stream("bigstream", 120)), at);
        DataObject first = out.getArray("streams").getObject(0);
        assertEquals("https://www.twitch.tv/bigstream", first.getString("url"));
        assertEquals("BIGSTREAM", first.getString("name"));
        String thumb = first.getString("thumbnail");
        assertTrue(thumb.startsWith("https://img.example/bigstream-440x248.jpg?t="), thumb);
        assertEquals(thumb, DirectorySiteStreamRoutes.json(true, List.of(stream("bigstream", 120)), at + Duration.ofMinutes(1).toMillis())
                .getArray("streams").getObject(0).getString("thumbnail"), "the same picture address within five minutes");
        assertTrue(out.getBoolean("following"));
    }
}
