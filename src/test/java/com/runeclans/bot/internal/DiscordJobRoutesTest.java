package com.runeclans.bot.internal;

import com.runeclans.bot.discord.jobs.DiscordJob;
import com.runeclans.bot.discord.jobs.DiscordJob.State;
import com.runeclans.bot.discord.jobs.DiscordOutbox;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiscordJobRoutesTest {
    private static final String GUILD = "5".repeat(17) + "2";
    private static final String ACTOR = "4".repeat(17) + "2";

    private static DiscordJob job(long id, State state, String error) {
        return new DiscordJob(id, "member.nickname", Long.parseLong(GUILD), Long.parseLong(ACTOR), null, DataObject.empty().put("secret", "x"), state, 1, 0, error,
                null, Instant.now().plusSeconds(5), Instant.now(), Instant.now());
    }

    @Test
    void theWebsiteReadsItsOwnJobsWithoutTheirPayload() {
        DiscordOutbox outbox = mock(DiscordOutbox.class);
        when(outbox.find(List.of(1L, 2L), Long.parseLong(GUILD), Long.parseLong(ACTOR)))
                .thenReturn(List.of(job(1, State.RETRYING, "Discord asked the bot to wait 3 s; it will try again by itself."), job(2, State.DONE, null)));
        DiscordJobRoutes routes = new DiscordJobRoutes(outbox);

        DiscordJobRoutes.Answer answer = routes.route("GET", "", GUILD, ACTOR, "1,2,2");
        assertEquals(200, answer.status());
        DataObject first = answer.body().getArray("jobs").getObject(0);
        assertEquals("retrying", first.getString("state"));
        assertEquals("1", first.getString("id"));
        assertEquals(false, first.hasKey("payload"));
        assertEquals("done", answer.body().getArray("jobs").getObject(1).getString("state"));

        assertEquals(400, routes.route("GET", "", GUILD, null, "1").status(), "always for one person");
        assertEquals(400, routes.route("GET", "", "nope", ACTOR, "1").status());
        when(outbox.find(List.of(1L), null, Long.parseLong(ACTOR))).thenReturn(List.of(job(1, State.QUEUED, null)));
        assertEquals("queued", routes.route("GET", "", null, ACTOR, "1").body().getArray("jobs").getObject(0).getString("state"), "a clan site knows only the person");
        assertEquals(400, routes.route("GET", "", GUILD, ACTOR, "1,x").status());
        assertEquals(400, routes.route("GET", "", GUILD, ACTOR, String.join(",", java.util.stream.IntStream.rangeClosed(1, DiscordJobRoutes.MAX_IDS + 1).mapToObj(Integer::toString).toList())).status());
        assertEquals(404, routes.route("DELETE", "/1", GUILD, ACTOR, null).status());
    }

    @Test
    void retryIsOnlyForAFailedJob() {
        DiscordOutbox outbox = mock(DiscordOutbox.class);
        when(outbox.retry(eq(9L), any(), anyLong())).thenReturn(true);
        when(outbox.get(9)).thenReturn(Optional.of(job(9, State.QUEUED, null)));
        DiscordJobRoutes routes = new DiscordJobRoutes(outbox);
        assertEquals("queued", routes.route("POST", "/9/retry", GUILD, ACTOR, null).body().getString("state"));
        assertEquals(409, routes.route("POST", "/8/retry", GUILD, ACTOR, null).status());
        assertEquals(400, routes.route("POST", "/x/retry", GUILD, ACTOR, null).status());
        verify(outbox, never()).retry(eq(0L), any(), anyLong());
    }
}
