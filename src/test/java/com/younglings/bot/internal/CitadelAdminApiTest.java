package com.younglings.bot.internal;

import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.tracking.WeeklyDigestService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CitadelAdminApiTest {
    private WeeklyDigestService digest;
    private CitadelAdminApi api;
    private Guild guild;
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-14T12:30:00Z");

    @BeforeEach
    void setUp() {
        digest = mock(WeeklyDigestService.class);
        api = new CitadelAdminApi(digest);
        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(7L);
    }

    @Test
    void withNoDateItAnswersForTheWeekThatJustFinished() {
        when(digest.getCappedRsns(eq(7L), any(), any())).thenReturn(List.of("Axley", "Giff"));
        DataObject answer = api.cappers(guild, null, NOW);
        assertEquals("2026-10-14", answer.getString("end"));
        assertEquals("2026-10-07", answer.getString("start"));
        assertEquals(2, answer.getArray("cappers").length());
        assertEquals(8, answer.getArray("weeks").length());
        assertEquals("2026-10-14", answer.getArray("weeks").getObject(0).getString("end"));
        assertEquals("2026-08-26", answer.getArray("weeks").getObject(7).getString("end"));
        verify(digest).getCappedRsns(7L, OffsetDateTime.parse("2026-10-07T00:10:00Z"), OffsetDateTime.parse("2026-10-14T00:10:00Z"));
    }

    @Test
    void aGivenWednesdayPicksThatWeek() {
        when(digest.getCappedRsns(eq(7L), any(), any())).thenReturn(List.of());
        api.cappers(guild, "2026-09-30", NOW);
        verify(digest).getCappedRsns(7L, OffsetDateTime.parse("2026-09-23T00:10:00Z"), OffsetDateTime.parse("2026-09-30T00:10:00Z"));
    }

    @Test
    void aBadOrFutureDateIsRefusedWithAReason() {
        assertEquals(400, assertThrows(ApiError.class, () -> api.cappers(guild, "nonsense", NOW)).status);
        assertEquals(400, assertThrows(ApiError.class, () -> api.cappers(guild, "2026-10-15", NOW)).status, "not a Wednesday");
        assertEquals(400, assertThrows(ApiError.class, () -> api.cappers(guild, "2027-01-06", NOW)).status, "not yet");
    }

    @Test
    void onlyItsOwnRouteIsAnswered() {
        assertNull(api.route(guild, null, "GET", new String[]{"events"}, DataObject::empty));
        assertEquals(405, assertThrows(ApiError.class, () -> api.route(guild, null, "POST", new String[]{"citadel", "cappers"}, DataObject::empty)).status);
    }
}
