package com.younglings.bot.internal;

import com.younglings.bot.coffer.CofferRepository;
import com.younglings.bot.commands.coffer.CofferDonation;
import com.younglings.bot.commands.coffer.CofferGiveaway;
import com.younglings.bot.commands.coffer.CofferHolder;
import com.younglings.bot.commands.coffer.CofferTransfer;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.tracking.CitadelPrizes;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CofferAdminApiTest {
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-10-20T18:00:00Z");
    private CofferRepository coffer;
    private CitadelPrizes prizes;
    private CofferAdminApi api;
    private Guild guild;

    @BeforeEach
    void setUp() {
        coffer = mock(CofferRepository.class);
        prizes = mock(CitadelPrizes.class);
        api = new CofferAdminApi(coffer, prizes);
        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(7L);
        named(1L, "Metorrite");
        named(55L, "Axley");
        named(66L, "Giff");
    }

    private void named(long id, String name) {
        Member member = mock(Member.class);
        when(member.getEffectiveName()).thenReturn(name);
        when(member.getIdLong()).thenReturn(id);
        when(guild.getMemberById(id)).thenReturn(member);
    }

    private static Member actor() {
        Member member = mock(Member.class);
        when(member.getId()).thenReturn("1");
        when(member.getIdLong()).thenReturn(1L);
        return member;
    }

    private DataObject post(String what, DataObject body) {
        return api.route(guild, actor(), "POST", new String[]{"coffer", what}, () -> body);
    }

    @Test
    void theOverviewShowsTheTotalHoldersAndTheLatestOfEverythingWithNames() {
        when(coffer.getHolders(7L)).thenReturn(List.of(new CofferHolder(7L, 1L, 300_000_000L), new CofferHolder(7L, 55L, 50_000_000L)));
        when(coffer.getRecentDonations(7L, CofferAdminApi.RECENT)).thenReturn(List.of(new CofferDonation(1, 7L, "Some Donor", 100_000_000L, 1L, AT)));
        when(coffer.getRecentGiveaways(7L, CofferAdminApi.RECENT)).thenReturn(List.of(new CofferGiveaway(1, 7L, 55L, 66L, 10_000_000L, "Citadel wheel prize", AT)));
        when(coffer.getRecentTransfers(7L, CofferAdminApi.RECENT)).thenReturn(List.of(new CofferTransfer(1, 7L, 1L, 55L, 60_000_000L, "COMPLETED", AT, AT)));
        DataObject answer = api.route(guild, actor(), "GET", new String[]{"coffer"}, DataObject::empty);
        assertEquals(350_000_000L, answer.getLong("total"));
        assertEquals("Metorrite", answer.getArray("holders").getObject(0).getString("name"));
        assertEquals("Some Donor", answer.getArray("donations").getObject(0).getString("donor"));
        assertEquals("Metorrite", answer.getArray("donations").getObject(0).getString("heldByName"));
        assertEquals("Giff", answer.getArray("giveaways").getObject(0).getString("toName"));
        assertEquals("2026-10-20T18:00:00Z", answer.getArray("giveaways").getObject(0).getString("at"));
        assertEquals("Axley", answer.getArray("transfers").getObject(0).getString("toName"));
    }

    @Test
    void onlyItsOwnRoutesAreAnswered() {
        assertNull(api.route(guild, actor(), "GET", new String[]{"citadel"}, DataObject::empty));
        assertNull(api.route(guild, actor(), "POST", new String[]{"coffer", "nope"}, DataObject::empty));
        assertEquals(405, assertThrows(ApiError.class, () -> api.route(guild, actor(), "POST", new String[]{"coffer"}, DataObject::empty)).status);
    }

    @Test
    void aDonationIsHeldByTheAskerUnlessSomeoneElseIsChosen() {
        post("donation", DataObject.empty().put("donor", "  Some Donor ").put("amount", "100m"));
        verify(coffer).insertDonation(7L, "Some Donor", 100_000_000L, 1L);
        post("donation", DataObject.empty().put("donor", "Other").put("amount", "100,000K").put("heldBy", "55"));
        verify(coffer).insertDonation(7L, "Other", 100_000_000L, 55L);
        assertEquals(400, assertThrows(ApiError.class, () -> post("donation", DataObject.empty().put("amount", "1m"))).status);
        assertEquals(400, assertThrows(ApiError.class, () -> post("donation", DataObject.empty().put("donor", "X").put("amount", "lots"))).status);
    }

    @Test
    void aGiveawayGoesToALinkedNameOrAnIdAndNeedsTheBalance() {
        when(prizes.linkedUser(guild, "Giff")).thenReturn(66L);
        when(coffer.getHolderBalance(7L, 55L)).thenReturn(20_000_000L);
        when(coffer.insertGiveaway(7L, 55L, 66L, 10_000_000L, "Drop party")).thenReturn(true);
        post("giveaway", DataObject.empty().put("from", "55").put("recipientRsn", "Giff").put("amount", "10M").put("description", "Drop party"));
        verify(coffer).insertGiveaway(7L, 55L, 66L, 10_000_000L, "Drop party");

        assertEquals(409, assertThrows(ApiError.class, () -> post("giveaway", DataObject.empty().put("from", "55").put("recipient", "66").put("amount", "1b"))).status);
        assertEquals(400, assertThrows(ApiError.class, () -> post("giveaway", DataObject.empty().put("from", "55").put("recipientRsn", "Nobody").put("amount", "1m"))).status);
        assertEquals(400, assertThrows(ApiError.class, () -> post("giveaway", DataObject.empty().put("from", "55").put("recipient", "55").put("amount", "1m"))).status);
    }

    @Test
    void aTransferMovesGpBetweenTwoPeopleOnly() {
        when(coffer.getHolderBalance(7L, 1L)).thenReturn(300_000_000L);
        when(coffer.executeTransfer(7L, 1L, 55L, 60_000_000L)).thenReturn(true);
        post("transfer", DataObject.empty().put("to", "55").put("amount", "60_000_000"));
        verify(coffer).executeTransfer(7L, 1L, 55L, 60_000_000L);
        assertEquals(400, assertThrows(ApiError.class, () -> post("transfer", DataObject.empty().put("to", "1").put("amount", "1m"))).status);
        assertEquals(400, assertThrows(ApiError.class, () -> post("transfer", DataObject.empty().put("amount", "1m"))).status);
        verify(coffer, never()).executeTransfer(anyLong(), anyLong(), org.mockito.ArgumentMatchers.eq(1L), anyLong());
        verify(coffer, never()).insertDonation(anyLong(), anyString(), anyLong(), org.mockito.ArgumentMatchers.eq(99L));
    }
}
