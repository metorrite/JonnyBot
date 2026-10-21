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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The DM fallback (T-115) against a real Postgres, with a fake Discord that records what would have been sent. All ids are made up. */
class LinkDmTest {
    private static final String ALICE = "100000000000000001";
    private static final String BOB = "100000000000000002";

    /** A fake bot: knows some usernames, can reach some ids, records each DM. */
    static final class FakeDiscord implements LinkDmSender {
        final Map<String, String> usernames = new HashMap<>();
        final List<String> reachable = new ArrayList<>();
        final List<String[]> sent = new ArrayList<>();

        @Override
        public Optional<String> resolveUsername(String username) {
            return Optional.ofNullable(usernames.get(username));
        }

        @Override
        public boolean sendCode(String discordId, String rsnDisplay, String userCode, String pairingId, long expiresInMinutes) {
            if (!reachable.contains(discordId)) return false;
            sent.add(new String[]{discordId, rsnDisplay, userCode, pairingId});
            return true;
        }

        String lastCode() {
            return sent.get(sent.size() - 1)[2];
        }
    }

    private LinkDatabase database;
    private LinkService service;
    private MutableClock clock;
    private FakeDiscord discord;
    private int ip;

    @BeforeEach
    void setUp() throws Exception {
        String url = LinkTestSupport.databaseUrl();
        database = new LinkDatabase(url, 2);
        LinkTestSupport.resetSchema(database);
        clock = new MutableClock(Instant.parse("2026-10-20T12:00:00Z"));
        discord = new FakeDiscord();
        discord.reachable.add(ALICE);
        discord.usernames.put("alice.example", ALICE);
        service = new LinkService(LinkTestSupport.settings(url), database, discord, List.of(), clock, new SecureRandom(), Runnable::run);
        service.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (database != null) {
            LinkTestSupport.resetSchema(database);
            database.close();
        }
    }

    private DataObject start(String rsn, String tracking, String discordInput) {
        return service.dmFlow().start(rsn, tracking, discordInput, "RS Link 1.0.0", "192.0.2." + (++ip));
    }

    private int count(String sql) throws Exception {
        try (Connection c = database.connection(); PreparedStatement s = c.prepareStatement(sql.replace("{s}", LinkTestSupport.TEST_SCHEMA)); ResultSet rs = s.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void inputIsAnIdOrAUsername() {
        assertEquals(ALICE, LinkDm.parse(" " + ALICE + " ").id());
        assertEquals("alice.example", LinkDm.parse("@Alice.Example").username());
        assertEquals("old_tag", assertThrows(LinkException.class, () -> LinkDm.parse("alice#1234")).code());
        assertEquals("bad_discord", assertThrows(LinkException.class, () -> LinkDm.parse("a")).code());
        assertEquals("bad_discord", assertThrows(LinkException.class, () -> LinkDm.parse("has space")).code());
    }

    @Test
    void theAnswerIsTheSameWhetherOrNotADmWasSent() {
        DataObject reached = start("Zezima", "1", "alice.example");
        DataObject unknown = start("Zezima", "2", "nobody.here");
        DataObject unreachable = start("Zezima", "3", BOB);
        for (DataObject answer : List.of(reached, unknown, unreachable)) {
            assertEquals(202, answer.getInt("__status"));
            assertEquals(List.of("__status", "deviceSecret", "expiresIn", "pairingId"), answer.keys().stream().sorted().toList());
        }
        assertEquals(1, discord.sent.size());
        assertEquals("Zezima", discord.sent.get(0)[1], "the DM names the RSN that asked");
    }

    @Test
    void theRightCodeLinksAndHandsTheTokenOverOnce() throws Exception {
        DataObject p = start("Zezima", "1", ALICE);
        DataObject linked = service.dmFlow().confirm(p.getString("pairingId"), p.getString("deviceSecret"), discord.lastCode().toLowerCase());
        assertEquals("linked", linked.getString("status"));
        assertTrue(LinkCodes.looksLikeToken(linked.getString("token")));
        assertTrue(service.session(linked.getString("token"), "Zezima", "1").getBoolean("linked"));
        assertEquals(1, count("SELECT count(*) FROM {s}.link WHERE method = 'dm'"));
        assertEquals("expired_or_unknown", assertThrows(LinkException.class,
                () -> service.dmFlow().confirm(p.getString("pairingId"), p.getString("deviceSecret"), discord.lastCode())).code());
    }

    @Test
    void fiveWrongCodesLockThePairing() throws Exception {
        DataObject p = start("Zezima", "1", ALICE);
        for (int left = 4; left >= 1; left--) {
            LinkException wrong = assertThrows(LinkException.class, () -> service.dmFlow().confirm(p.getString("pairingId"), p.getString("deviceSecret"), "BBBB-BBBB"));
            assertEquals("wrong_code", wrong.code());
            assertEquals(left, wrong.extra().get("attemptsLeft"));
        }
        assertEquals(423, assertThrows(LinkException.class, () -> service.dmFlow().confirm(p.getString("pairingId"), p.getString("deviceSecret"), "BBBB-BBBB")).status());
        assertEquals(423, assertThrows(LinkException.class, () -> service.dmFlow().confirm(p.getString("pairingId"), p.getString("deviceSecret"), discord.lastCode())).status(),
                "even the right code is refused once locked");
        assertEquals(2, count("SELECT count(*) FROM {s}.abuse_event WHERE kind = 'lockout'"), "the IP and the tracking id");
    }

    @Test
    void aCodeThatWasNeverSentCanNeverBeRight() {
        DataObject p = start("Zezima", "1", BOB);
        assertEquals("wrong_code", assertThrows(LinkException.class, () -> service.dmFlow().confirm(p.getString("pairingId"), p.getString("deviceSecret"), "BBBB-BBBB")).code());
    }

    @Test
    void oneDmPerTargetPerTenMinutesAndThreeADay() throws Exception {
        start("Zezima", "1", ALICE);
        start("Zezima", "2", ALICE);
        assertEquals(1, discord.sent.size(), "second within 10 minutes is silently not sent");
        clock.advance(Duration.ofMinutes(11));
        start("Zezima", "3", ALICE);
        clock.advance(Duration.ofMinutes(11));
        start("Zezima", "4", ALICE);
        clock.advance(Duration.ofMinutes(11));
        start("Zezima", "5", ALICE);
        assertEquals(3, discord.sent.size(), "at most 3 a day");
        assertEquals(1, count("SELECT count(*) FROM {s}.dm_send WHERE outcome = 'target_daily_cap'"));
    }

    @Test
    void theAskerIsLimitedPerIpAndPerTrackingId() {
        for (int i = 0; i < 3; i++) service.dmFlow().start("Zezima", "77", "x" + i + ".name", null, "192.0.2.200");
        assertEquals(429, assertThrows(LinkException.class, () -> service.dmFlow().start("Zezima", "77", "x9.name", null, "192.0.2.201")).status(), "3 per tracking id");
        for (int i = 0; i < 2; i++) service.dmFlow().start("Name" + i, null, "y" + i + ".name", null, "192.0.2.200");
        assertEquals(429, assertThrows(LinkException.class, () -> service.dmFlow().start("Other", null, "z.name", null, "192.0.2.200")).status(), "5 per IP");
    }

    @Test
    void notMeIsOnlyForThePersonTheDmWentTo() throws Exception {
        DataObject p = start("Zezima", "1", ALICE);
        assertFalse(service.dmFlow().notMe(p.getString("pairingId"), BOB));
        assertTrue(service.dmFlow().notMe(p.getString("pairingId"), ALICE));
        assertEquals("expired_or_unknown", assertThrows(LinkException.class,
                () -> service.dmFlow().confirm(p.getString("pairingId"), p.getString("deviceSecret"), discord.lastCode())).code());
        assertEquals(1, count("SELECT count(*) FROM {s}.abuse_event WHERE kind = 'dm_not_me' AND subject_kind = 'ip'"));
    }

    @Test
    void stopTheseMessagesOptsOutForNinetyDays() {
        DataObject p = start("Zezima", "1", ALICE);
        Instant until = service.dmFlow().stop(p.getString("pairingId"), ALICE);
        assertEquals(clock.instant().plus(Duration.ofDays(90)), until);
        clock.advance(Duration.ofDays(2));
        start("Zezima", "2", ALICE);
        assertEquals(1, discord.sent.size(), "no DM while opted out");
        clock.advance(Duration.ofDays(89));
        start("Zezima", "3", ALICE);
        assertEquals(2, discord.sent.size(), "the opt-out ends after 90 days");
    }

    @Test
    void theServiceWideBreakerStopsDmsAndIsRecorded() throws Exception {
        try (Connection c = database.connection()) {
            for (int i = 0; i < LinkLimits.DM_TOTAL.max(); i++) service.store().dmSend(c, new byte[]{(byte) i, (byte) (i >> 8)}, "sent", clock.instant().minusSeconds(60));
        }
        assertTrue(service.dmFlow().breakerOpen());
        assertEquals(202, start("Zezima", "1", ALICE).getInt("__status"));
        assertEquals(0, discord.sent.size());
        assertEquals(1, count("SELECT count(*) FROM {s}.abuse_event WHERE kind = 'dm_breaker'"));
    }

    @Test
    void noCodeSecretOrIdReachesTheLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger("com.younglings.bot.rsnlink");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            DataObject p = start("Zezima", "4455667788", "alice.example");
            String code = discord.lastCode();
            assertThrows(LinkException.class, () -> service.dmFlow().confirm(p.getString("pairingId"), p.getString("deviceSecret"), "BBBB-BBBB"));
            service.dmFlow().confirm(p.getString("pairingId"), p.getString("deviceSecret"), code);
            service.dmFlow().stop(p.getString("pairingId"), ALICE);
            List<String> forbidden = List.of(code, code.replace("-", ""), p.getString("deviceSecret"), ALICE, "alice.example", "4455667788");
            for (ILoggingEvent event : appender.list) {
                for (String value : forbidden) assertFalse(event.getFormattedMessage().contains(value), "a log line held a secret value: " + event.getMessage());
            }
        } finally {
            logger.detachAppender(appender);
        }
    }
}
