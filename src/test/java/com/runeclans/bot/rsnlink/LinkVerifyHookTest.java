package com.runeclans.bot.rsnlink;

import com.runeclans.bot.rsnlink.LinkTestSupport.MutableClock;
import com.runeclans.bot.rsnlink.verify.MemoryVerifyStore;
import com.runeclans.bot.rsnlink.verify.PgVerifyStore;
import com.runeclans.bot.rsnlink.verify.PublicData;
import com.runeclans.bot.rsnlink.verify.VerifySettings;
import com.runeclans.bot.rsnlink.verify.VerifyStore;
import com.runeclans.bot.rsnlink.verify.XpLinkVerifier;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "plugin knew it first" check (T-170) plugged into the real link service and its database, with Jagex's public data stubbed (no
 * network). Covers the {@code /v1/xp} route, the {@code xpReports} flag, raising a link from the background, and the dispute tie-breaker.
 * Every Discord id and tracking id is made up.
 */
class LinkVerifyHookTest {
    private static final String ALICE = "100000000000000001";
    private static final String BOB = "100000000000000002";
    private static final int FISHING = 10;
    private static final int COOKING = 7;

    private LinkDatabase database;
    private LinkSettings settings;
    private MutableClock clock;
    private LinkService service;
    private XpLinkVerifier verifier;
    private final List<Runnable> queued = new ArrayList<>();
    private final StubData data = new StubData();
    private int ip;

    /** Public data the test sets. Only the main Hiscores; RuneMetrics private. */
    static final class StubData implements PublicData {
        long[] main = table(Map.of());

        static long[] table(Map<Integer, Long> xp) {
            long[] t = new long[29];
            Arrays.fill(t, -1);
            xp.forEach((k, v) -> t[k] = v);
            return t;
        }

        @Override
        public Hiscores hiscores(Board board, String rsn) {
            return board == Board.MAIN ? new Hiscores.Found(main.clone()) : new Hiscores.NotListed();
        }

        @Override
        public RuneMetrics runeMetrics(String rsn) {
            return new RuneMetrics.Private();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        String url = LinkTestSupport.databaseUrl();
        database = new LinkDatabase(url, 3);
        LinkTestSupport.resetSchema(database);
        clock = new MutableClock(Instant.parse("2026-10-22T12:00:00Z"));
        settings = LinkTestSupport.settings(url);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (database != null) {
            LinkTestSupport.resetSchema(database);
            database.close();
        }
    }

    private void startWith(boolean verifyOn, boolean pgStore) {
        VerifySettings vs = verifyOn ? new VerifySettings(true, 2, 100, 24, 20, "test") : VerifySettings.off();
        PgVerifyStore pg = new PgVerifyStore(database::connection, LinkTestSupport.TEST_SCHEMA);
        VerifyStore store = pgStore ? pg : new MemoryVerifyStore();
        verifier = new XpLinkVerifier(vs, store, data, clock, queued::add, null);
        service = new LinkService(settings, database, LinkDmSender.NONE, List.of(verifier), clock, new SecureRandom());
        service.start();
        if (pgStore) pg.migrate();
    }

    private void drain() {
        while (!queued.isEmpty()) queued.removeFirst().run();
    }

    private record Linked(String token, String result) {}

    private Linked link(String rsn, String tracking, String discordId) {
        String from = "192.0.2." + (++ip);
        DataObject start = service.startPairing(rsn, tracking, "RS Link 1.0.0", from);
        String session = "browser-session-" + UUID.randomUUID();
        service.webCode(start.getString("userCode"), session, null, from);
        String result = service.webConfirm(session, discordId, "someone", "link").getString("result");
        clock.advance(Duration.ofSeconds(6));
        DataObject polled = service.poll(start.getString("pairingId"), start.getString("deviceSecret"));
        return new Linked(polled.getString("token"), result);
    }

    private int count(String sql) throws Exception {
        try (Connection c = database.connection(); PreparedStatement s = c.prepareStatement(sql.replace("{s}", LinkTestSupport.TEST_SCHEMA));
             ResultSet rs = s.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** A session the real owner plays: XP reported while not yet public, a lobby, then the Hiscores publish exactly those values. */
    private void ownerSession(String token, String tracking, long fishing10, long cooking10) {
        service.xpReport(token, "Zezima", tracking, "game", Map.of(FISHING, fishing10, COOKING, cooking10));
        clock.advance(Duration.ofSeconds(1));
        drain();
        service.xpReport(token, "Zezima", tracking, "lobby", Map.of());
        drain();
        data.main = StubData.table(Map.of(FISHING, fishing10 / 10, COOKING, cooking10 / 10));
        clock.advance(Duration.ofMinutes(3));
        verifier.verifier().runDue();
    }

    @Test
    void withTheCheckOffThePluginIsToldNotToSendXp() {
        startWith(false, false);
        String token = link("Zezima", "222", ALICE).token();
        assertFalse(service.session(token, "Zezima", "222").getBoolean("xpReports"));
        DataObject answer = service.xpReport(token, "Zezima", "222", "game", Map.of(FISHING, 10_012_345L));
        assertFalse(answer.getBoolean("accepted"));
        drain();
        assertTrue(queued.isEmpty());
    }

    @Test
    void anXpReportForAnotherCharacterIsRefused() {
        startWith(true, false);
        String token = link("Zezima", "222", ALICE).token();
        assertTrue(service.session(token, "Zezima", "222").getBoolean("xpReports"));
        assertEquals("character_mismatch", assertThrows(LinkException.class,
                () -> service.xpReport(token, "Someone Else", "222", "game", Map.of(FISHING, 1L))).code());
        assertEquals("character_mismatch", assertThrows(LinkException.class,
                () -> service.xpReport(token, "Zezima", "999", "game", Map.of(FISHING, 1L))).code());
        assertEquals("token_revoked", assertThrows(LinkException.class,
                () -> service.xpReport("lnk_" + "x".repeat(39), "Zezima", "222", "game", Map.of())).code());
    }

    @Test
    void theRealOwnerIsCorroboratedAndStopsBeingAskedForXp() throws Exception {
        startWith(true, true);
        data.main = StubData.table(Map.of(FISHING, 1_000_000L, COOKING, 500_000L));
        String token = link("Zezima", "222", ALICE).token();
        ownerSession(token, "222", 10_012_345L, 5_005_000L);

        DataObject session = service.session(token, "Zezima", "222");
        assertEquals("public_data_corroborated", session.getString("verification"));
        assertFalse(session.getBoolean("xpReports"), "nothing more to prove");
        assertEquals(1, count("SELECT count(*) FROM {s}.link WHERE verification = 'public_data_corroborated' AND verified_at IS NOT NULL"));
        assertEquals(2, count("SELECT count(*) FROM {s}.verify_claim WHERE status = 'MATCH'"));

        DataObject mine = service.webLinks(ALICE).getArray("links").getObject(0);
        assertEquals("corroborated", mine.getObject("verify").getObject(XpLinkVerifier.METHOD).getString("state"));
        assertTrue(new LinkAdmin(service).overview().getObject("verify").getObject(XpLinkVerifier.METHOD).getBoolean("enabled"));
    }

    @Test
    void theCorroboratedClaimantWinsADisputeAgainstASquatter() throws Exception {
        startWith(true, true);
        data.main = StubData.table(Map.of(FISHING, 1_000_000L, COOKING, 500_000L));
        String squatter = link("Zezima", "111", BOB).token();
        Linked owner = link("Zezima", "222", ALICE);
        assertEquals("disputed", owner.result());
        assertTrue(service.session(owner.token(), "Zezima", "222").getBoolean("xpReports"), "a held claim can still prove itself");

        // The squatter can only copy what is public, which is refused.
        service.xpReport(squatter, "Zezima", "111", "game", Map.of(FISHING, 10_000_000L));
        drain();
        ownerSession(owner.token(), "222", 10_012_345L, 5_005_000L);

        assertEquals(1, count("SELECT count(*) FROM {s}.dispute WHERE status = 'resolved_claimant' AND resolved_by = 'verification'"));
        assertEquals(1, count("SELECT count(*) FROM {s}.abuse_event WHERE kind = 'dispute_settled_by_verification'"));
        assertEquals("token_revoked", assertThrows(LinkException.class, () -> service.session(squatter, "Zezima", "111")).code());
        DataObject now = service.session(owner.token(), "Zezima", "222");
        assertTrue(now.getBoolean("linked"));
        assertFalse(now.getBoolean("disputed"));
        assertEquals("public_data_corroborated", now.getString("verification"));
    }

    @Test
    void aCorroboratedIncumbentKeepsTheNameAgainstANewClaim() throws Exception {
        startWith(true, false);
        data.main = StubData.table(Map.of(FISHING, 1_000_000L, COOKING, 500_000L));
        String owner = link("Zezima", "111", BOB).token();
        Linked claimant = link("Zezima", "222", ALICE);
        assertEquals("disputed", claimant.result());
        ownerSession(owner, "111", 10_012_345L, 5_005_000L);

        assertEquals(1, count("SELECT count(*) FROM {s}.dispute WHERE status = 'resolved_incumbent'"));
        assertEquals("token_revoked", assertThrows(LinkException.class, () -> service.session(claimant.token(), "Zezima", "222")).code());
        assertTrue(service.session(owner, "Zezima", "111").getBoolean("linked"));
    }

    @Test
    void aRaiseForAnEndedLinkChangesNothing() throws Exception {
        startWith(true, false);
        String token = link("Zezima", "222", ALICE).token();
        String linkId = service.webLinks(ALICE).getArray("links").getObject(0).getString("linkId");
        service.unlink(token);
        service.raiseFromBackground(linkId, new LinkVerifier.VerificationResult(Verification.PUBLIC_DATA_CORROBORATED, "test", "{}"));
        assertEquals(0, count("SELECT count(*) FROM {s}.link WHERE verification <> 'implicit'"));
    }
}
