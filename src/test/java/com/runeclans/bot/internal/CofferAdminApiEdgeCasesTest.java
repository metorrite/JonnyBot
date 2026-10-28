package com.runeclans.bot.internal;

import com.runeclans.bot.coffer.CofferRepository;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import com.runeclans.bot.permission.DashboardAccess;
import com.runeclans.bot.tracking.CitadelPrizes;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The dashboard's Clan coffer routes (#449) on the edges: bad input is a 400 that writes nothing, a balance that changed under the asker is a
 * 409, nobody outside the server is paid, and nobody without dashboard access is let in by the coffer itself.
 */
class CofferAdminApiEdgeCasesTest {
    private static final long GUILD = 7L;
    private static final long ASKER = 1L;
    private static final long HOLDER = 55L;
    private static final long WINNER = 66L;
    /** Not in the server: neither cached nor found by a lookup. */
    private static final long STRANGER = 99L;

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
        when(guild.getIdLong()).thenReturn(GUILD);
        for (long id : new long[]{ASKER, HOLDER, WINNER}) {
            Member m = mock(Member.class);
            when(m.getIdLong()).thenReturn(id);
            when(m.getEffectiveName()).thenReturn("member" + id);
            when(guild.getMemberById(id)).thenReturn(m);
        }
    }

    private static Member actor() {
        Member m = mock(Member.class);
        when(m.getId()).thenReturn(Long.toString(ASKER));
        when(m.getIdLong()).thenReturn(ASKER);
        return m;
    }

    private DataObject post(String what, DataObject body) {
        return api.route(guild, actor(), "POST", new String[]{"coffer", what}, () -> body);
    }

    private int status(String what, DataObject body) {
        return assertThrows(ApiError.class, () -> post(what, body)).status;
    }

    private void nothingWritten() {
        verify(coffer, never()).insertDonation(anyLong(), anyString(), anyLong(), anyLong());
        verify(coffer, never()).insertGiveaway(anyLong(), anyLong(), anyLong(), anyLong(), any());
        verify(coffer, never()).executeTransfer(anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void theCofferNeverAdmitsSomeoneWithoutDashboardAccessAndEventCoordinatorsDontReachIt() {
        Member nobody = mock(Member.class);
        for (String[] path : List.of(new String[]{"coffer"}, new String[]{"coffer", "donation"}, new String[]{"coffer", "giveaway"}, new String[]{"coffer", "transfer"})) {
            assertFalse(api.admits(guild, nobody, "GET", path), String.join("/", path));
            assertFalse(api.admits(guild, nobody, "POST", path), String.join("/", path));
            assertFalse(DashboardAccess.coordinatorMayUse("POST", path), "coordinators: " + String.join("/", path));
            assertFalse(DashboardAccess.coordinatorMayUse("GET", path), "coordinators: " + String.join("/", path));
        }
        assertFalse(DashboardAccess.coordinatorMayUse("PUT", new String[]{"citadel", "prize", "defaults"}));
        assertFalse(DashboardAccess.coordinatorMayUse("POST", new String[]{"citadel", "prize", "coffer"}));
    }

    @Test
    void writesArePostOnlyAndLongerPathsArentTheCoffers() {
        assertEquals(405, assertThrows(ApiError.class, () -> api.route(guild, actor(), "GET", new String[]{"coffer", "donation"}, DataObject::empty)).status);
        assertEquals(405, assertThrows(ApiError.class, () -> api.route(guild, actor(), "PUT", new String[]{"coffer", "transfer"}, DataObject::empty)).status);
        assertNull(api.route(guild, actor(), "POST", new String[]{"coffer", "donation", "x"}, DataObject::empty));
        assertNull(api.route(guild, actor(), "GET", new String[0], DataObject::empty));
        nothingWritten();
    }

    @Test
    void badAmountsAreA400AndWriteNothing() {
        for (String amount : new String[]{"", "   ", "0", "-5m", "NaN", "Infinity", "1e9", "1".repeat(31), "5 gp"}) {
            assertEquals(400, status("donation", DataObject.empty().put("donor", "Someone").put("amount", amount)), amount);
        }
        assertEquals(400, status("donation", DataObject.empty().put("donor", "Someone").put("amount", null)), "null amount");
        assertEquals(400, status("donation", DataObject.empty().put("donor", "Someone")), "no amount");
        nothingWritten();
    }

    @Test
    void aDonationNeedsADonorWithinTheLimitAndAHolderInTheServer() {
        assertEquals(400, status("donation", DataObject.empty().put("donor", "   ").put("amount", "1m")));
        assertEquals(400, status("donation", DataObject.empty().put("donor", "x".repeat(CofferAdminApi.MAX_NAME + 1)).put("amount", "1m")));
        assertEquals(400, status("donation", DataObject.empty().put("donor", "Someone").put("amount", "1m").put("heldBy", Long.toString(STRANGER))));
        nothingWritten();

        post("donation", DataObject.empty().put("donor", "x".repeat(CofferAdminApi.MAX_NAME)).put("amount", "1m").put("heldBy", ""));
        verify(coffer).insertDonation(GUILD, "x".repeat(CofferAdminApi.MAX_NAME), 1_000_000L, ASKER);
    }

    @Test
    void idsThatArentDiscordIdsAreA400EvenWhenTheyOverflowALong() {
        for (String id : new String[]{"abc", "12.5", "-55", "9".repeat(20), "1".repeat(21)}) {
            assertEquals(400, status("transfer", DataObject.empty().put("to", id).put("amount", "1m")), id);
            assertEquals(400, status("giveaway", DataObject.empty().put("recipient", id).put("amount", "1m")), id);
            assertEquals(400, status("donation", DataObject.empty().put("donor", "Someone").put("amount", "1m").put("heldBy", id)), id);
        }
        nothingWritten();
    }

    @Test
    void aGiveawayNeedsARecipientInTheServerAndAShortDescription() {
        when(coffer.getHolderBalance(GUILD, ASKER)).thenReturn(1_000_000_000L);
        assertEquals(400, status("giveaway", DataObject.empty().put("amount", "1m")), "nobody named");
        assertEquals(400, status("giveaway", DataObject.empty().put("recipientRsn", "   ").put("amount", "1m")), "a blank name is nobody");
        assertEquals(400, status("giveaway", DataObject.empty().put("recipient", Long.toString(STRANGER)).put("amount", "1m")), "not in the server");
        assertEquals(400, status("giveaway", DataObject.empty().put("recipient", Long.toString(ASKER)).put("amount", "1m")), "the asker paying themselves");
        assertEquals(400, status("giveaway", DataObject.empty().put("recipient", Long.toString(WINNER)).put("amount", "1m")
                .put("description", "x".repeat(CofferAdminApi.MAX_DESCRIPTION + 1))));
        nothingWritten();
        verifyNoInteractions(prizes);
    }

    @Test
    void aGiveawayToALinkedNameThatIsntInTheServerIsRefused() {
        when(prizes.linkedUser(guild, "Gone")).thenReturn(STRANGER);
        when(coffer.getHolderBalance(GUILD, ASKER)).thenReturn(1_000_000_000L);
        assertEquals(400, status("giveaway", DataObject.empty().put("recipientRsn", "Gone").put("amount", "1m")));
        nothingWritten();
    }

    @Test
    void anIdBeatsALinkedNameWhenBothAreSent() {
        when(coffer.getHolderBalance(GUILD, ASKER)).thenReturn(10_000_000L);
        when(coffer.insertGiveaway(GUILD, ASKER, WINNER, 1_000_000L, null)).thenReturn(true);
        post("giveaway", DataObject.empty().put("recipient", Long.toString(WINNER)).put("recipientRsn", "Someone Else").put("amount", "1m").put("description", "   "));
        verify(coffer).insertGiveaway(GUILD, ASKER, WINNER, 1_000_000L, null);
        verifyNoInteractions(prizes);
    }

    @Test
    void aBalanceThatChangedUnderTheAskerIsA409() {
        when(coffer.getHolderBalance(GUILD, HOLDER)).thenReturn(5_000_000L);
        when(coffer.insertGiveaway(anyLong(), anyLong(), anyLong(), anyLong(), any())).thenReturn(false);
        when(coffer.executeTransfer(anyLong(), anyLong(), anyLong(), anyLong())).thenReturn(false);
        assertEquals(409, status("giveaway", DataObject.empty().put("from", Long.toString(HOLDER)).put("recipient", Long.toString(WINNER)).put("amount", "5m")));
        assertEquals(409, status("transfer", DataObject.empty().put("from", Long.toString(HOLDER)).put("to", Long.toString(WINNER)).put("amount", "5m")));
    }

    @Test
    void aTransferCantSpendMoreThanTheGiverHoldsNorGoToSomeoneOutsideTheServer() {
        when(coffer.getHolderBalance(GUILD, HOLDER)).thenReturn(5_000_000L);
        assertEquals(409, status("transfer", DataObject.empty().put("from", Long.toString(HOLDER)).put("to", Long.toString(WINNER)).put("amount", "5000001")));
        assertEquals(400, status("transfer", DataObject.empty().put("from", Long.toString(HOLDER)).put("to", Long.toString(STRANGER)).put("amount", "1m")));
        assertEquals(400, status("transfer", DataObject.empty().put("from", Long.toString(HOLDER)).put("to", Long.toString(HOLDER)).put("amount", "1m")));
        nothingWritten();

        when(coffer.executeTransfer(GUILD, HOLDER, WINNER, 5_000_000L)).thenReturn(true);
        post("transfer", DataObject.empty().put("from", Long.toString(HOLDER)).put("to", Long.toString(WINNER)).put("amount", "5m"));
        verify(coffer).executeTransfer(GUILD, HOLDER, WINNER, 5_000_000L);
    }

    @Test
    void anEmptyCofferIsATotalOfZeroWithEmptyLists() {
        DataObject answer = api.route(guild, actor(), "GET", new String[]{"coffer"}, DataObject::empty);
        assertEquals(0L, answer.getLong("total"));
        for (String list : new String[]{"holders", "donations", "giveaways", "transfers"}) assertEquals(0, answer.getArray(list).length(), list);
    }
}
