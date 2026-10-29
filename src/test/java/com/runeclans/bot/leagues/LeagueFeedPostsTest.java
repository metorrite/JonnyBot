package com.runeclans.bot.leagues;

import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.discord.jobs.DiscordOutbox;
import com.runeclans.bot.runeclans.HubWriteQueue;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The fan-out of a league feed entry to the servers that picked a channel and are in the league (T-760). */
class LeagueFeedPostsTest {
    private static final String LEAGUE = "0dc0dc00-0000-4000-8000-00000000000b";
    private static final long IN = Long.parseLong("2".repeat(18));
    private static final long OUT = Long.parseLong("3".repeat(18));
    private static final long DOWN = Long.parseLong("4".repeat(18));

    private final Map<String, RuneClansHubClient.Read> answers = new LinkedHashMap<>();
    private DiscordOutbox outbox;
    private LeagueDiscordStore store;
    private LeagueFeedPosts posts;

    @BeforeEach
    void setUp() {
        LeagueHub hub = LeagueHub.on(answers::get, (m, p, b) -> new HubWriteQueue.Result(HubWriteQueue.Outcome.REFUSED, null));
        outbox = mock(DiscordOutbox.class);
        store = mock(LeagueDiscordStore.class);
        BotConfig config = mock(BotConfig.class);
        when(config.getClanSitesUrl()).thenReturn("https://runeclans.com");
        posts = new LeagueFeedPosts(hub, store, outbox, config);
        answers.put("/v1/guilds/" + IN + "/leagues", read(200, DataObject.empty().put("leagues", DataArray.empty().add(DataObject.empty().put("id", LEAGUE)))));
        answers.put("/v1/guilds/" + OUT + "/leagues", read(200, DataObject.empty().put("leagues", DataArray.empty())));
    }

    private static RuneClansHubClient.Read read(int status, DataObject json) {
        return new RuneClansHubClient.Read(new RuneClansHubClient.Answer(status, json), false, false);
    }

    private static DataObject entry() {
        return DataObject.empty().put("item", "league").put("data",
                DataObject.empty().put("leagueId", LEAGUE).put("name", "Winter cup").put("event", "started").put("number", 2));
    }

    @Test
    void oneJobPerServerInTheLeagueWithAChannel() {
        Map<Long, Long> channels = new LinkedHashMap<>();
        channels.put(IN, 100L);
        channels.put(OUT, 200L);
        channels.put(DOWN, 300L);
        when(store.channels()).thenReturn(channels);
        int queued = posts.fanOut(LeaguePostLogic.plan("league.round", entry()).orElseThrow());
        assertEquals(1, queued);
        ArgumentCaptor<DataObject> payload = ArgumentCaptor.forClass(DataObject.class);
        verify(outbox).enqueue(eq(LeaguePostJobs.KIND), eq(IN), eq(0L), eq("league-post:" + LEAGUE + ":league.round:started:2:" + IN), payload.capture());
        assertEquals("100", payload.getValue().getString("channelId"));
        assertTrue(payload.getValue().getString("text").contains("Round 2 of **Winter cup** has started."), payload.getValue().getString("text"));
        assertTrue(payload.getValue().getString("text").contains("https://runeclans.com/clans/leagues/" + LEAGUE));
        verify(outbox, never()).enqueue(anyString(), eq(OUT), anyLong(), anyString(), any());
        verify(outbox, never()).enqueue(anyString(), eq(DOWN), anyLong(), anyString(), any());
    }

    @Test
    void anOutboxThatRefusesOneServerDoesNotStopTheOthers() {
        Map<Long, Long> channels = new LinkedHashMap<>();
        channels.put(DOWN, 300L);
        channels.put(IN, 100L);
        answers.put("/v1/guilds/" + DOWN + "/leagues", answers.get("/v1/guilds/" + IN + "/leagues"));
        when(store.channels()).thenReturn(channels);
        when(outbox.enqueue(anyString(), eq(DOWN), anyLong(), anyString(), any())).thenThrow(new IllegalStateException("database away"));
        assertEquals(1, posts.fanOut(LeaguePostLogic.plan("league.round", entry()).orElseThrow()));
        verify(outbox).enqueue(anyString(), eq(IN), anyLong(), anyString(), any());
    }

    @Test
    void nothingIsQueuedForNoiseOrWhenNobodyPickedAChannel() {
        when(store.channels()).thenReturn(Map.of());
        posts.onHubFeed("league.round", entry());
        assertEquals(0, posts.fanOut(LeaguePostLogic.plan("league.round", entry()).orElseThrow()));
        posts.onHubFeed("league.round", DataObject.empty().put("data", DataObject.empty().put("leagueId", LEAGUE).put("event", "attached").put("number", 2)));
        verify(outbox, never()).enqueue(anyString(), anyLong(), anyLong(), anyString(), any());
    }
}
