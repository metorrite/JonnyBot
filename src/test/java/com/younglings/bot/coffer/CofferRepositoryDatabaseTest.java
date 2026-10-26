package com.younglings.bot.coffer;

import com.younglings.bot.commands.coffer.CofferGiveaway;
import com.younglings.bot.commands.coffer.CofferTransfer;
import com.younglings.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The coffer's SQL behind the dashboard's Clan coffer page (#449) against the local Postgres (skipped where there isn't one): balances move with
 * each write, a write the balance can't cover changes nothing, and the page's newest-first lists stay inside their own server.
 */
class CofferRepositoryDatabaseTest {
    private static final long GUILD = 987654321012346L;
    private static final long OTHER_GUILD = 987654321012347L;
    private static final long ANN = 11L;
    private static final long BOB = 12L;
    private static final long CAT = 13L;

    private ConnectionSupplier db;
    private CofferRepository coffer;

    @BeforeEach
    void setUp() throws Exception {
        db = LocalDatabase.supplier();
        coffer = new CofferRepository(db, new CofferDatabaseInitializer(db));
        clean();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (db != null) clean();
    }

    private void clean() throws Exception {
        try (Connection c = db.getConnection()) {
            for (String table : new String[]{"coffer_donation", "coffer_holder", "coffer_transfer", "coffer_giveaway"}) {
                try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings." + table + " WHERE guild_id IN (?, ?)")) {
                    s.setLong(1, GUILD);
                    s.setLong(2, OTHER_GUILD);
                    s.executeUpdate();
                }
            }
        }
    }

    /** Spreads the rows' times apart, oldest first in id order, so "newest first" doesn't hang on how fast the inserts ran. */
    private void spreadTimes(String table, String idColumn, String timeColumn) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement s = c.prepareStatement("UPDATE younglings." + table + " SET " + timeColumn
                + " = TIMESTAMPTZ '2026-10-20 12:00:00+00' + (" + idColumn + " - (SELECT MIN(" + idColumn + ") FROM younglings." + table
                + " WHERE guild_id = ?)) * INTERVAL '1 minute' WHERE guild_id = ?")) {
            s.setLong(1, GUILD);
            s.setLong(2, GUILD);
            s.executeUpdate();
        }
    }

    @Test
    void aDonationRaisesTheHoldersBalanceAndAGiveawayOrTransferMovesIt() {
        coffer.insertDonation(GUILD, "Some Donor", 100_000_000L, ANN);
        assertEquals(100_000_000L, coffer.getHolderBalance(GUILD, ANN));

        assertTrue(coffer.insertGiveaway(GUILD, ANN, CAT, 10_000_000L, "Drop party"));
        assertEquals(90_000_000L, coffer.getHolderBalance(GUILD, ANN));
        assertEquals(0L, coffer.getHolderBalance(GUILD, CAT), "a giveaway pays out of the coffer; the winner doesn't become a holder");

        assertTrue(coffer.executeTransfer(GUILD, ANN, BOB, 40_000_000L));
        assertEquals(50_000_000L, coffer.getHolderBalance(GUILD, ANN));
        assertEquals(40_000_000L, coffer.getHolderBalance(GUILD, BOB));
        assertEquals(90_000_000L, coffer.getHolders(GUILD).stream().mapToLong(com.younglings.bot.commands.coffer.CofferHolder::amount).sum());
    }

    @Test
    void aWriteTheBalanceCantCoverChangesNothing() {
        coffer.insertDonation(GUILD, "Some Donor", 5_000_000L, ANN);
        assertFalse(coffer.insertGiveaway(GUILD, ANN, CAT, 5_000_001L, null));
        assertFalse(coffer.executeTransfer(GUILD, ANN, BOB, 5_000_001L));
        assertFalse(coffer.executeTransfer(GUILD, BOB, ANN, 1L), "someone holding nothing can't hand anything over");
        assertEquals(5_000_000L, coffer.getHolderBalance(GUILD, ANN));
        assertEquals(0L, coffer.getHolderBalance(GUILD, BOB));
        assertEquals(List.of(), coffer.getRecentGiveaways(GUILD, 50));
        assertEquals(List.of(), coffer.getRecentTransfers(GUILD, 50));
    }

    @Test
    void theRecentGiveawaysAreNewestFirstLimitedAndThisServersOnly() throws Exception {
        coffer.insertDonation(GUILD, "Donor", 100L, ANN);
        coffer.insertDonation(OTHER_GUILD, "Donor", 100L, ANN);
        for (int i = 1; i <= 3; i++) assertTrue(coffer.insertGiveaway(GUILD, ANN, CAT, i, "prize " + i));
        assertTrue(coffer.insertGiveaway(OTHER_GUILD, ANN, CAT, 50L, "elsewhere"));
        spreadTimes("coffer_giveaway", "giveaway_id", "given_at");

        List<CofferGiveaway> all = coffer.getRecentGiveaways(GUILD, 50);
        assertEquals(List.of("prize 3", "prize 2", "prize 1"), all.stream().map(CofferGiveaway::description).toList());
        CofferGiveaway newest = all.getFirst();
        assertEquals(GUILD, newest.guildId());
        assertEquals(ANN, newest.givenByDiscordId());
        assertEquals(CAT, newest.recipientDiscordId());
        assertEquals(3L, newest.amount());
        assertEquals(2, coffer.getRecentGiveaways(GUILD, 2).size());
    }

    @Test
    void theRecentTransfersAreTheCompletedOnesNewestFirst() throws Exception {
        coffer.insertDonation(GUILD, "Donor", 1_000L, ANN);
        assertTrue(coffer.executeTransfer(GUILD, ANN, BOB, 100L));
        assertTrue(coffer.executeTransfer(GUILD, ANN, CAT, 200L));
        coffer.insertPendingTransfer(GUILD, ANN, BOB, 300L);
        spreadTimes("coffer_transfer", "transfer_id", "created_at");

        List<CofferTransfer> transfers = coffer.getRecentTransfers(GUILD, 50);
        assertEquals(List.of(200L, 100L), transfers.stream().map(CofferTransfer::amount).toList(), "a transfer still waiting to be accepted isn't listed");
        assertEquals(CAT, transfers.getFirst().toDiscordId());
        assertEquals(List.of(), coffer.getRecentTransfers(OTHER_GUILD, 50));
    }
}
