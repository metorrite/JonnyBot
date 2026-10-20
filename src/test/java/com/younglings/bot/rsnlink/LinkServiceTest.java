package com.younglings.bot.rsnlink;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.younglings.bot.rsnlink.LinkTestSupport.MutableClock;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The link service's rules against a real Postgres (the local dev one, or CI's throwaway one), in the schema {@code rsnlink_test}, which is
 * dropped before every test. Every Discord id, tracking id and IP here is made up; keys are random per run.
 */
class LinkServiceTest {
    private static final String ALICE = "100000000000000001";
    private static final String BOB = "100000000000000002";
    private static final String IP = "203.0.113.7";

    private LinkDatabase database;
    private LinkService service;
    private MutableClock clock;
    private final List<LinkVerifier> verifiers = new ArrayList<>();
    private LinkSettings settings;
    private int ipCounter;

    @BeforeEach
    void setUp() throws Exception {
        String url = LinkTestSupport.databaseUrl();
        database = new LinkDatabase(url, 2);
        LinkTestSupport.resetSchema(database);
        clock = new MutableClock(Instant.parse("2026-10-20T12:00:00Z"));
        settings = LinkTestSupport.settings(url);
        service = new LinkService(settings, database, LinkDmSender.NONE, verifiers, clock, new SecureRandom());
        service.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (database != null) {
            LinkTestSupport.resetSchema(database);
            database.close();
        }
    }

    // ---------- helpers ----------

    private record Paired(String pairingId, String deviceSecret, String userCode, String session) {}

    private Paired start(String rsn, String tracking) {
        return start(rsn, tracking, IP);
    }

    private Paired start(String rsn, String tracking, String ip) {
        DataObject r = service.startPairing(rsn, tracking, "RS Link 1.0.0", ip);
        return new Paired(r.getString("pairingId"), r.getString("deviceSecret"), r.getString("userCode"), "browser-session-" + UUID.randomUUID());
    }

    /** Code entry, sign-in and Link, as the website would do them. Returns the confirm result. */
    private String approve(Paired p, String discordId) {
        service.webCode(p.userCode(), p.session(), null, IP);
        return service.webConfirm(p.session(), discordId, "someone", "link").getString("result");
    }

    private DataObject pollNow(Paired p) {
        clock.advance(Duration.ofSeconds(6));
        return service.poll(p.pairingId(), p.deviceSecret());
    }

    private String link(String rsn, String tracking, String discordId) {
        Paired p = start(rsn, tracking, "192.0.2." + (++ipCounter % 250));
        approve(p, discordId);
        DataObject r = pollNow(p);
        assertEquals("linked", r.getString("status"));
        return r.getString("token");
    }

    private int count(String sql) throws Exception {
        try (Connection c = database.connection(); PreparedStatement s = c.prepareStatement(sql.replace("{s}", LinkTestSupport.TEST_SCHEMA)); ResultSet rs = s.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    // ---------- the main flow ----------

    @Test
    void signInFlowLinksAndHandsTheTokenOutExactlyOnce() throws Exception {
        Paired p = start("Zezima", "4242");
        assertTrue(p.userCode().matches("[A-Z2-9]{4}-[A-Z2-9]{4}"));
        assertEquals("pending", pollNow(p).getString("status"));

        DataObject entered = service.webCode(p.userCode().toLowerCase().replace("-", " "), p.session(), null, IP);
        assertTrue(entered.getBoolean("accepted"));
        assertEquals("Zezima", service.webPairing(p.session(), ALICE).getString("rsn"));
        assertEquals("linked", service.webConfirm(p.session(), ALICE, "alice", "link").getString("result"));

        DataObject first = pollNow(p);
        assertEquals("linked", first.getString("status"));
        assertTrue(LinkCodes.looksLikeToken(first.getString("token")));
        assertEquals("implicit", first.getString("verification"));
        assertEquals("expired_or_unknown", pollNow(p).getString("status"), "the token is handed out once");

        try (Connection c = database.connection(); PreparedStatement s = c.prepareStatement("SELECT token_hash FROM " + LinkTestSupport.TEST_SCHEMA + ".link_token");
             ResultSet rs = s.executeQuery()) {
            assertTrue(rs.next());
            assertEquals(32, rs.getBytes(1).length, "only the SHA-256 of the token is stored");
        }
        try (Connection c = database.connection(); PreparedStatement s = c.prepareStatement("SELECT discord_enc FROM " + LinkTestSupport.TEST_SCHEMA + ".discord_user");
             ResultSet rs = s.executeQuery()) {
            assertTrue(rs.next());
            assertFalse(new String(rs.getBytes(1)).contains(ALICE), "the Discord id is encrypted at rest");
        }

        DataObject session = service.session(first.getString("token"), "Zezima", "4242");
        assertTrue(session.getBoolean("linked"));
        assertEquals("alice", session.getString("discordName"));
    }

    @Test
    void pollingTooFastIsSlowedDown() {
        Paired p = start("Zezima", null);
        clock.advance(Duration.ofSeconds(6));
        assertEquals("pending", service.poll(p.pairingId(), p.deviceSecret()).getString("status"));
        DataObject fast = service.poll(p.pairingId(), p.deviceSecret());
        assertEquals("slow_down", fast.getString("status"));
        assertEquals(10, fast.getInt("interval"));
        assertEquals("expired_or_unknown", service.poll(p.pairingId(), "x".repeat(43)).getString("status"), "a wrong secret learns nothing");
    }

    @Test
    void codesExpireAfterTenMinutes() {
        Paired p = start("Zezima", null);
        clock.advance(Duration.ofMinutes(10).plusSeconds(1));
        LinkException e = assertThrows(LinkException.class, () -> service.webCode(p.userCode(), p.session(), null, IP));
        assertEquals("expired", e.code());
        assertEquals("expired_or_unknown", service.poll(p.pairingId(), p.deviceSecret()).getString("status"));
    }

    @Test
    void aCodeIsBoundToTheFirstBrowserThatEntersIt() {
        Paired p = start("Zezima", null);
        service.webCode(p.userCode(), p.session(), null, IP);
        LinkException e = assertThrows(LinkException.class, () -> service.webCode(p.userCode(), "another-browser-" + UUID.randomUUID(), null, IP));
        assertEquals("code_in_use", e.code());
    }

    @Test
    void denyingEndsThePairingAndIsRecorded() throws Exception {
        Paired p = start("Zezima", null);
        service.webCode(p.userCode(), p.session(), null, IP);
        assertEquals("denied", service.webConfirm(p.session(), ALICE, null, "deny").getString("result"));
        assertEquals("denied", pollNow(p).getString("status"));
        assertEquals(1, count("SELECT count(*) FROM {s}.abuse_event WHERE kind = 'denied'"));
        assertEquals(0, count("SELECT count(*) FROM {s}.link"));
    }

    // ---------- limits ----------

    @Test
    void codeEntryIsLimitedPerIpAndRepeatOffendersAreBlocked() throws Exception {
        start("Zezima", null);
        for (int i = 0; i < 10; i++) {
            LinkException wrong = assertThrows(LinkException.class, () -> service.webCode("BBBB-BBBB", "s-" + UUID.randomUUID(), null, IP));
            assertEquals("wrong_code", wrong.code());
        }
        LinkException limited = assertThrows(LinkException.class, () -> service.webCode("BBBB-BBBB", "s-" + UUID.randomUUID(), null, IP));
        assertEquals(429, limited.status());
        assertTrue(limited.retryAfter() > 0);
        assertEquals(1, count("SELECT count(*) FROM {s}.abuse_event WHERE kind = 'lockout'"));

        // Two more lockouts in a day (each after the minute window refills) block the IP for 24 hours.
        for (int round = 0; round < 2; round++) {
            clock.advance(Duration.ofMinutes(61));
            for (int i = 0; i < 10; i++) assertThrows(LinkException.class, () -> service.webCode("BBBB-BBBB", "s-" + UUID.randomUUID(), null, IP));
            assertEquals(429, assertThrows(LinkException.class, () -> service.webCode("BBBB-BBBB", "s-" + UUID.randomUUID(), null, IP)).status());
        }
        assertEquals(1, count("SELECT count(*) FROM {s}.link_block WHERE created_by = 'auto'"));
        clock.advance(Duration.ofHours(2));
        Paired fresh = start("Other", null, "198.51.100.1");
        LinkException blocked = assertThrows(LinkException.class, () -> service.webCode(fresh.userCode(), fresh.session(), null, IP));
        assertEquals(429, blocked.status(), "a block looks exactly like a rate limit");
    }

    @Test
    void wrongCodesPerSignedInDiscordAreCapped() {
        start("Zezima", null);
        for (int i = 0; i < 10; i++) {
            String ip = "198.51.100." + i;
            assertThrows(LinkException.class, () -> service.webCode("BBBB-BBBB", "s-" + UUID.randomUUID(), ALICE, ip));
        }
        LinkException capped = assertThrows(LinkException.class, () -> service.webCode("BBBB-BBBB", "s-" + UUID.randomUUID(), ALICE, "198.51.100.200"));
        assertEquals(429, capped.status());
    }

    @Test
    void startingIsLimitedPerIpTrackingIdAndName() {
        for (int i = 0; i < 5; i++) start("Zezima", null, "198.51.100." + i);
        assertEquals(429, assertThrows(LinkException.class, () -> start("Zezima", null, "198.51.100.99")).status(), "5 per name per hour");
        for (int i = 0; i < 5; i++) start("Name" + i, "777", "198.51.100." + (10 + i));
        assertEquals(429, assertThrows(LinkException.class, () -> start("Name9", "777", "198.51.100.50")).status(), "5 per tracking id per hour");
        for (int i = 0; i < 10; i++) start("Ip" + i, null, IP);
        assertEquals(429, assertThrows(LinkException.class, () -> start("Ip99", null, IP)).status(), "10 per IP per hour");
        clock.advance(Duration.ofMinutes(61));
        start("Ip99", null, IP);
    }

    @Test
    void tokenRoutesAreLimitedPerToken() {
        String token = link("Zezima", "1", ALICE);
        for (int i = 0; i < 30; i++) service.alias(token, "RuneSync");
        assertEquals(429, assertThrows(LinkException.class, () -> service.alias(token, "RuneSync")).status());
    }

    @Test
    void atMostFiveDevicesPerLinkTheOldestIsRevoked() {
        String first = link("Zezima", "1", ALICE);
        for (int i = 0; i < 5; i++) {
            clock.advance(Duration.ofMinutes(13));
            link("Zezima", "1", ALICE);
        }
        assertEquals("token_revoked", assertThrows(LinkException.class, () -> service.session(first, "Zezima", "1")).code());
    }

    @Test
    void aDiscordAccountHoldsAtMostTwentyLinks() {
        for (int i = 0; i < 20; i++) link("Char" + i, Integer.toString(i), ALICE);
        Paired p = start("OneMore", "99", "198.51.100.9");
        service.webCode(p.userCode(), p.session(), null, "198.51.100.9");
        assertEquals("too_many_links", assertThrows(LinkException.class, () -> service.webConfirm(p.session(), ALICE, null, "link")).code());
    }

    // ---------- aliases ----------

    @Test
    void aliasesAreStableAcrossDevicesAndChangeAfterRelinking() {
        String token = link("Zezima", "1", ALICE);
        DataObject a = service.alias(token, "RuneSync");
        String again = link("Zezima", "1", ALICE);
        assertEquals(a.getString("alias"), service.alias(again, "runesync").getString("alias"), "same link, same alias");
        assertNotEquals(a.getString("alias"), service.alias(again, "Other").getString("alias"));
        assertTrue(a.getString("assertion").split("\\.").length == 3);

        service.unlink(again);
        String fresh = link("Zezima", "1", ALICE);
        assertNotEquals(a.getString("alias"), service.alias(fresh, "RuneSync").getString("alias"), "a fresh link is a fresh start");
    }

    // ---------- renames, unlink, delete ----------

    @Test
    void renamesFollowTheTrackingIdAndKeepTheLink() {
        String token = link("OldName", "55", ALICE);
        String alias = service.alias(token, "RuneSync").getString("alias");
        assertTrue(service.session(token, "NewName", "55").getBoolean("linked"));
        assertEquals(alias, service.alias(token, "RuneSync").getString("alias"));
        DataObject links = service.webLinks(ALICE).getArray("links").getObject(0);
        assertEquals("NewName", links.getString("rsn"));
        assertEquals("OldName", links.getArray("previousNames").getString(0));
        assertEquals("character_mismatch", assertThrows(LinkException.class, () -> service.session(token, "NewName", "56")).code());
    }

    @Test
    void withoutATrackingIdANewNameIsAnotherCharacter() {
        String token = link("Zezima", null, ALICE);
        assertEquals("character_mismatch", assertThrows(LinkException.class, () -> service.session(token, "Other", null)).code());
        assertTrue(service.session(token, "zezima", "321").getBoolean("linked"), "the tracking id is learned when first sent");
    }

    @Test
    void unlinkRevokesEveryTokenAndShowsOnTheWebForThirtyDays() throws Exception {
        String a = link("Zezima", "1", ALICE);
        String b = link("Zezima", "1", ALICE);
        service.unlink(a);
        assertEquals("token_revoked", assertThrows(LinkException.class, () -> service.session(b, "Zezima", "1")).code());
        DataObject shown = service.webLinks(ALICE).getArray("links").getObject(0);
        assertEquals("unlinked", shown.getString("status"));
        clock.advance(Duration.ofDays(31));
        service.sweep();
        assertEquals(0, service.webLinks(ALICE).getArray("links").length());
        assertEquals(0, count("SELECT count(*) FROM {s}.link"));
    }

    @Test
    void webUnlinkOnlyTouchesYourOwnLinks() {
        link("Zezima", "1", ALICE);
        String linkId = service.webLinks(ALICE).getArray("links").getObject(0).getString("linkId");
        assertEquals("not_found", assertThrows(LinkException.class, () -> service.webUnlink(BOB, linkId)).code());
        assertEquals("unlinked", service.webUnlink(ALICE, linkId).getArray("links").getObject(0).getString("status"));
    }

    @Test
    void forgetMeDeletesTheCharacterAtOnce() throws Exception {
        String token = link("Zezima", "1", ALICE);
        service.session(token, "Zezima2", "1");
        assertEquals("confirm_required", assertThrows(LinkException.class, () -> service.forgetMe(token, "yes")).code());
        service.forgetMe(token, "DELETE");
        assertEquals(0, count("SELECT count(*) FROM {s}.game_character"));
        assertEquals(0, count("SELECT count(*) FROM {s}.rsn_history"));
        assertEquals(0, count("SELECT count(*) FROM {s}.link_token"));
    }

    @Test
    void deleteMyDataRemovesTheDiscordAccountButKeepsABlock() throws Exception {
        link("Zezima", "1", ALICE);
        link("Alt", "2", ALICE);
        try (Connection c = database.connection()) {
            service.store().insertBlock(c, LinkService.SUBJECT_DISCORD, service.crypto().lookup(LinkService.SUBJECT_DISCORD, ALICE), "test", clock.instant().plus(Duration.ofDays(1)), "owner", clock.instant());
        }
        service.webDeleteMyData(ALICE, "DELETE");
        assertEquals(0, count("SELECT count(*) FROM {s}.discord_user"));
        assertEquals(0, count("SELECT count(*) FROM {s}.link"));
        assertEquals(0, count("SELECT count(*) FROM {s}.game_character"));
        assertEquals(1, count("SELECT count(*) FROM {s}.link_block"), "deleting must not be a way around a block");
    }

    // ---------- names claimed twice (section 11 + the 2026-10-20 research) ----------

    @Test
    void aSecondTrackedClaimWithinThirtyDaysIsHeldInDisputeNotRefused() throws Exception {
        String squatter = link("Zezima", "111", BOB);
        Paired owner = start("Zezima", "222", "198.51.100.5");
        assertEquals("disputed", approve(owner, ALICE));
        DataObject handed = pollNow(owner);
        assertEquals("linked", handed.getString("status"));
        assertTrue(handed.getBoolean("disputed"));
        String ownerToken = handed.getString("token");

        assertFalse(service.session(ownerToken, "Zezima", "222").getBoolean("linked"), "held for review, so not linked yet");
        assertEquals("not_linked", assertThrows(LinkException.class, () -> service.alias(ownerToken, "RuneSync")).code());
        assertTrue(service.session(squatter, "Zezima", "111").getBoolean("disputed"), "the incumbent is flagged too");
        assertEquals(1, count("SELECT count(*) FROM {s}.dispute WHERE status = 'open'"));
        assertEquals(1, count("SELECT count(*) FROM {s}.abuse_event WHERE kind = 'dispute_opened'"));

        service.unlink(squatter);
        assertTrue(service.session(ownerToken, "Zezima", "222").getBoolean("linked"), "when the incumbent leaves, the waiting claim becomes the link");
        assertEquals(1, count("SELECT count(*) FROM {s}.dispute WHERE status = 'resolved_claimant'"));
    }

    @Test
    void aNameQuietForThirtyDaysIsReleasedToTheNewOwner() throws Exception {
        String old = link("Zezima", "111", BOB);
        clock.advance(Duration.ofDays(31));
        Paired fresh = start("Zezima", "222");
        assertEquals("linked", approve(fresh, ALICE));
        assertEquals("token_revoked", assertThrows(LinkException.class, () -> service.session(old, "Zezima", "111")).code());
        assertEquals(1, count("SELECT count(*) FROM {s}.link WHERE status = 'released'"));
        assertEquals(0, count("SELECT count(*) FROM {s}.dispute"));
    }

    @Test
    void aClaimWithoutATrackingIdCantTakeATrackedName() throws Exception {
        link("Zezima", "111", BOB);
        Paired p = start("Zezima", null, "198.51.100.5");
        service.webCode(p.userCode(), p.session(), null, "198.51.100.5");
        assertEquals("name_unavailable", assertThrows(LinkException.class, () -> service.webConfirm(p.session(), ALICE, null, "link")).code());
        assertEquals("refused", pollNow(p).getString("status"));
        assertEquals(0, count("SELECT count(*) FROM {s}.dispute"));
    }

    @Test
    void aTrackedClaimTakesOverATrackingLessLink() throws Exception {
        String loose = link("Zezima", null, BOB);
        assertEquals("linked", approve(start("Zezima", "222"), ALICE));
        assertEquals("token_revoked", assertThrows(LinkException.class, () -> service.session(loose, "Zezima", null)).code());
        assertEquals(1, count("SELECT count(*) FROM {s}.link WHERE status = 'superseded'"));
    }

    @Test
    void twoTrackingLessClaimsByDifferentAccountsAreDisputed() throws Exception {
        link("Zezima", null, BOB);
        assertEquals("disputed", approve(start("Zezima", null, "198.51.100.5"), ALICE));
        assertEquals(1, count("SELECT count(*) FROM {s}.dispute WHERE status = 'open'"));
    }

    @Test
    void theSameCharacterMovingToAnotherDiscordSupersedesTheOldLink() throws Exception {
        String old = link("Zezima", "111", BOB);
        assertEquals("linked", approve(start("Zezima", "111"), ALICE));
        assertEquals("token_revoked", assertThrows(LinkException.class, () -> service.session(old, "Zezima", "111")).code());
        assertEquals(0, count("SELECT count(*) FROM {s}.dispute"));
    }

    @Test
    void aVerifiedLinkCantBeDisputedByAnImplicitClaim() throws Exception {
        verifiers.add(new LinkVerifier() {
            @Override
            public String method() {
                return "test_only";
            }

            @Override
            public Optional<VerificationResult> check(LinkContext context) {
                return context.hasTrackingId() && context.rsnLookup().equals("zezima") && context.report() != null
                        ? Optional.of(new VerificationResult(Verification.PUBLIC_DATA_CORROBORATED, "test_only", "{}")) : Optional.empty();
            }
        });
        service = new LinkService(settings, database, LinkDmSender.NONE, verifiers, clock, new SecureRandom());
        String owner = link("Zezima", "111", BOB);
        assertEquals("public_data_corroborated", service.session(owner, "Zezima", "111").getString("verification"));
        service = new LinkService(settings, database, LinkDmSender.NONE, List.of(), clock, new SecureRandom());
        Paired p = start("Zezima", "222", "198.51.100.5");
        service.webCode(p.userCode(), p.session(), null, "198.51.100.5");
        assertEquals("name_unavailable", assertThrows(LinkException.class, () -> service.webConfirm(p.session(), ALICE, null, "link")).code());
        assertTrue(service.session(owner, "Zezima", "111").getBoolean("linked"), "the refusal left nothing half-done");
    }

    // ---------- retention ----------

    @Test
    void endedPairingsAndOldEventsAreSwept() throws Exception {
        start("Zezima", null);
        clock.advance(Duration.ofMinutes(11));
        service.sweep();
        assertEquals(1, count("SELECT count(*) FROM {s}.pairing WHERE status = 'expired'"));
        clock.advance(Duration.ofHours(25));
        service.sweep();
        assertEquals(0, count("SELECT count(*) FROM {s}.pairing"));
    }

    // ---------- nothing secret in the logs ----------

    @Test
    void noCodeTokenSecretIdOrIpEverReachesTheLogs() {
        Logger root = (Logger) LoggerFactory.getLogger("com.younglings.bot.rsnlink");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            Paired p = start("Zezima", "98765432123");
            approve(p, ALICE);
            String token = pollNow(p).getString("token");
            service.session(token, "Zezima", "98765432123");
            service.alias(token, "RuneSync");
            Paired dispute = start("Zezima", "12345678987", "198.51.100.77");
            approve(dispute, BOB);
            service.unlink(token);
            assertThrows(LinkException.class, () -> service.webCode("BBBB-BBBB", "s-" + UUID.randomUUID(), BOB, IP));
            service.webDeleteMyData(ALICE, "DELETE");
            service.sweep();

            List<String> forbidden = List.of(p.userCode(), p.userCode().replace("-", ""), p.deviceSecret(), token, ALICE, BOB, "98765432123", "12345678987",
                    IP, "198.51.100.77", p.session());
            assertFalse(appender.list.isEmpty(), "the flows do log");
            for (ILoggingEvent event : appender.list) {
                String line = event.getFormattedMessage() + (event.getThrowableProxy() == null ? "" : event.getThrowableProxy().getMessage());
                for (String value : forbidden) assertFalse(line.contains(value), "a log line held a secret value: " + event.getMessage());
            }
        } finally {
            root.detachAppender(appender);
        }
    }

    @Test
    void theSchemaIsAtItsLatestVersion() throws Exception {
        assertEquals(LinkSchema.latestVersion(), count("SELECT max(version) FROM {s}.schema_version"));
        service.start();
        assertEquals(LinkSchema.latestVersion(), count("SELECT count(*) FROM {s}.schema_version"), "starting again changes nothing");
    }
}
