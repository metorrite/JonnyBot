package com.younglings.bot.internal;

import com.younglings.bot.notice.BotOwners;
import com.younglings.bot.premium.Premium;
import com.younglings.bot.premium.PremiumFeatures;
import com.younglings.bot.premium.PremiumGuildRepository;
import com.younglings.bot.premium.PremiumGuildRepository.Grant;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PremiumAdminApiTest {
    private PremiumGuildRepository repository;
    private Premium premium;
    private BotOwners owners;
    private PremiumAdminApi api;
    private Guild guild;
    private Member actor;

    @BeforeEach
    void setUp() {
        repository = mock(PremiumGuildRepository.class);
        premium = mock(Premium.class);
        owners = mock(BotOwners.class);
        api = new PremiumAdminApi(repository, premium, owners);
        guild = mock(Guild.class);
        when(guild.getJDA()).thenReturn(mock(JDA.class));
        when(guild.getIdLong()).thenReturn(5L);
        actor = mock(Member.class);
        when(actor.getIdLong()).thenReturn(7L);
        when(owners.isOwner(any(), anyLong())).thenReturn(true);
        when(repository.grants()).thenReturn(List.of(new Grant(1388208054008545300L, "RS Noobs", "premium", null, null, "manual", 7L, Instant.now())));
    }

    @Test
    void theOwnerSeesTheListAndMayChangeIt() {
        DataObject result = api.list(guild, actor);

        assertEquals(1, result.getArray("guilds").length());
        assertEquals("premium", result.getArray("guilds").getObject(0).getString("tier"));
        assertTrue(result.getBoolean("canEdit"));
    }

    @Test
    void anAdminWhoIsNotTheOwnerSeesNoListOnlyTheirOwnPlan() {
        when(owners.isOwner(any(), anyLong())).thenReturn(false);
        when(repository.activeGrant(5L)).thenReturn(new Grant(5L, "", "premium", null, "private note", "manual", 7L, Instant.now()));

        DataObject result = api.list(guild, actor);

        assertEquals(0, result.getArray("guilds").length());
        assertFalse(result.getBoolean("canEdit"));
        assertTrue(result.getBoolean("thisServer"));
        assertEquals("premium", result.getObject("thisServerPlan").getString("tier"));
        long unlocked = PremiumFeatures.all().stream().filter(f -> PremiumFeatures.allows("premium", f.key())).count();
        assertEquals(unlocked, result.getObject("thisServerPlan").getArray("features").length(), "the plan card lists what it includes");
        assertFalse(result.toString().contains("private note"), "the owner's note is not shown to a server");
    }

    @Test
    void aServerWithNoPlanHasNoPlanCard() {
        when(owners.isOwner(any(), anyLong())).thenReturn(false);

        DataObject result = api.list(guild, actor);

        assertFalse(result.getBoolean("thisServer"));
        assertTrue(result.isNull("thisServerPlan"));
    }

    @Test
    void onlyTheOwnerCanChangeIt() {
        when(owners.isOwner(any(), anyLong())).thenReturn(false);

        assertEquals(403, assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, "577853260153618443", DataObject.empty())).status);
        assertEquals(403, assertThrows(TicketAdminApi.ApiError.class, () -> api.remove(guild, actor, "577853260153618443")).status);
        assertEquals(403, assertThrows(TicketAdminApi.ApiError.class, () -> api.history(guild, actor, "577853260153618443")).status);
        verify(premium, never()).grant(anyLong(), anyString(), anyString(), any(), any(), anyString(), anyLong());
        verify(premium, never()).revoke(anyLong(), anyLong());
    }

    @Test
    void theOwnerCanAddAServerWithATidyLabelAndTheDefaultPlan() {
        api.save(guild, actor, "577853260153618443", DataObject.empty().put("label", "  Koalafied  "));

        verify(premium).grant(577853260153618443L, "Koalafied", "premium", null, null, "manual", 7L);
    }

    @Test
    void aPlanCanHaveANameAnEndAndANote() {
        Instant end = Instant.now().plus(30, ChronoUnit.DAYS);

        api.save(guild, actor, "577853260153618443", DataObject.empty().put("label", "x").put("tier", " Plus ").put("expiresAt", end.toString()).put("note", " trial ").put("source", "trial"));

        verify(premium).grant(577853260153618443L, "x", "plus", end, "trial", "trial", 7L);
    }

    @Test
    void anEndDateIsAWholeDayAndMustBeInTheFuture() {
        Instant now = Instant.parse("2026-10-20T12:00:00Z");

        assertEquals(Instant.parse("2026-12-26T00:00:00Z"), PremiumAdminApi.parseEnd(DataObject.empty().put("expiresAt", "2026-12-25"), now));
        assertNull(PremiumAdminApi.parseEnd(DataObject.empty().put("expiresAt", "  "), now));
        assertNull(PremiumAdminApi.parseEnd(DataObject.empty(), now));
        assertEquals(400, assertThrows(TicketAdminApi.ApiError.class, () -> PremiumAdminApi.parseEnd(DataObject.empty().put("expiresAt", "2026-10-19"), now)).status);
        assertEquals(400, assertThrows(TicketAdminApi.ApiError.class, () -> PremiumAdminApi.parseEnd(DataObject.empty().put("expiresAt", "tomorrow"), now)).status);
    }

    @Test
    void aBadPlanNameIsRefused() {
        assertEquals(400, assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, "577853260153618443", DataObject.empty().put("tier", "Not A Tier!"))).status);
    }

    @Test
    void aServerIdMustLookLikeOne() {
        assertEquals(400, assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, "abc", DataObject.empty())).status);
    }

    @Test
    void removingAServerNotOnThePlanIsANotFound() {
        when(premium.revoke(eq(1062157106347786240L), anyLong())).thenReturn(false);

        assertEquals(404, assertThrows(TicketAdminApi.ApiError.class, () -> api.remove(guild, actor, "1062157106347786240")).status);
    }

    @Test
    void thePlansThatEndSoonAreListedForTheOwner() {
        Instant now = Instant.parse("2026-10-20T12:00:00Z");
        when(repository.grants()).thenReturn(List.of(
                new Grant(1L, "soon", "premium", now.plus(3, ChronoUnit.DAYS), null, "manual", 7L, now),
                new Grant(2L, "later", "premium", now.plus(60, ChronoUnit.DAYS), null, "manual", 7L, now),
                new Grant(3L, "never", "premium", null, null, "manual", 7L, now),
                new Grant(4L, "over", "premium", now.minus(1, ChronoUnit.DAYS), null, "manual", 7L, now)));

        DataObject result = api.list(guild, actor, now);

        assertEquals(4, result.getArray("guilds").length());
        assertEquals(1, result.getArray("endingSoon").length());
        assertEquals("soon", result.getArray("endingSoon").getObject(0).getString("label"));
        assertFalse(result.getArray("guilds").getObject(3).getBoolean("inForce"));
    }
}
