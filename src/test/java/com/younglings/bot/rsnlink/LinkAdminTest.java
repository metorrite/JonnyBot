package com.younglings.bot.rsnlink;

import com.younglings.bot.rsnlink.LinkTestSupport.MutableClock;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The owner's abuse view (T-118) against a real Postgres. Made-up ids only. */
class LinkAdminTest {
    private static final String ALICE = "100000000000000001";
    private static final String BOB = "100000000000000002";
    private static final String OWNER = "100000000000000099";
    private static final String IP = "203.0.113.7";

    private LinkDatabase database;
    private LinkService service;
    private LinkAdmin admin;
    private MutableClock clock;
    private int ip;

    @BeforeEach
    void setUp() throws Exception {
        String url = LinkTestSupport.databaseUrl();
        database = new LinkDatabase(url, 2);
        LinkTestSupport.resetSchema(database);
        clock = new MutableClock(Instant.parse("2026-10-20T12:00:00Z"));
        service = new LinkService(LinkTestSupport.settings(url), database, LinkDmSender.NONE, List.of(), clock, new SecureRandom(), Runnable::run);
        service.start();
        admin = new LinkAdmin(service);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (database != null) {
            LinkTestSupport.resetSchema(database);
            database.close();
        }
    }

    private String link(String rsn, String tracking, String discordId) {
        DataObject r = service.startPairing(rsn, tracking, "RS Link", "192.0.2." + (++ip));
        String session = "browser-session-" + UUID.randomUUID();
        service.webCode(r.getString("userCode"), session, null, "192.0.2." + ip);
        service.webConfirm(session, discordId, "name-" + discordId.substring(15), "link");
        clock.advance(Duration.ofSeconds(6));
        return service.poll(r.getString("pairingId"), r.getString("deviceSecret")).getString("token");
    }

    @Test
    void theOverviewCountsWithoutShowingAnySubject() {
        link("Zezima", "1", ALICE);
        for (int i = 0; i < 3; i++) assertThrows(LinkException.class, () -> service.webCode("BBBB-BBBB", "s-" + UUID.randomUUID(), null, IP));
        DataObject overview = admin.overview();
        assertEquals(1, overview.getInt("activeLinks"));
        assertEquals(3, overview.getObject("events").getObject("wrong_code").getInt("day"));
        assertFalse(overview.getBoolean("dmBreakerOpen"));
        DataObject events = admin.events(null, 50);
        assertEquals(3, events.getArray("events").length());
        String all = events.toString() + overview;
        assertFalse(all.contains(IP), "subjects are fingerprints, never the IP");
        assertEquals(8, events.getArray("events").getObject(0).getString("subject").length());
    }

    @Test
    void theOwnerCanBlockAndUnblockATypedSubject() {
        DataObject blocked = admin.block("ip", IP, "spamming codes", 2, OWNER);
        assertEquals(1, blocked.getArray("blocks").length());
        assertFalse(blocked.toString().contains(IP));
        assertEquals(429, assertThrows(LinkException.class, () -> service.startPairing("Zezima", null, "RS Link", IP)).status());
        admin.unblock(blocked.getString("blockedId"), OWNER);
        service.startPairing("Zezima", null, "RS Link", IP);
        assertEquals(List.of("unblock", "block"), admin.audit(10).getArray("audit").stream(DataArrayGet::object).map(o -> o.getString("action")).toList());
        assertEquals("bad_value", assertThrows(LinkException.class, () -> admin.block("discord", "not-an-id", null, 1, OWNER)).code());
        assertEquals("bad_subject", assertThrows(LinkException.class, () -> admin.block("email", "x", null, 1, OWNER)).code());
    }

    @Test
    void aBlockedDiscordIdCantConfirm() {
        admin.block("discord", ALICE, null, 24, OWNER);
        DataObject r = service.startPairing("Zezima", null, "RS Link", IP);
        String session = "browser-session-" + UUID.randomUUID();
        service.webCode(r.getString("userCode"), session, null, IP);
        assertEquals(429, assertThrows(LinkException.class, () -> service.webConfirm(session, ALICE, null, "link")).status());
    }

    @Test
    void disputesShowBothSidesAndTheOwnerCanSettleThem() {
        String squatter = link("Zezima", "111", BOB);
        String owner = link("Zezima", "222", ALICE);
        DataObject list = admin.disputes(null);
        DataObject d = list.getArray("disputes").getObject(0);
        assertEquals("open", d.getString("status"));
        assertEquals("active", d.getObject("incumbent").getString("status"));
        assertEquals("disputed", d.getObject("claimant").getString("status"));
        assertFalse(list.toString().contains(ALICE), "no Discord ids in the list");

        admin.resolveDispute(d.getString("id"), "claimant", "owner knows them", OWNER);
        assertTrue(service.session(owner, "Zezima", "222").getBoolean("linked"));
        assertEquals("token_revoked", assertThrows(LinkException.class, () -> service.session(squatter, "Zezima", "111")).code());
        assertEquals("resolved_claimant", admin.disputes(null).getArray("disputes").getObject(0).getString("status"));
        assertEquals("not_found", assertThrows(LinkException.class, () -> admin.resolveDispute(d.getString("id"), "incumbent", null, OWNER)).code());
    }

    @Test
    void findRemoveAndRevealALink() {
        String token = link("Zezima", "1", ALICE);
        DataObject found = admin.findLinks("zezima").getArray("links").getObject(0);
        assertEquals("Zezima", found.getString("rsn"));
        assertFalse(found.toString().contains(ALICE));

        DataObject revealed = admin.revealDiscord(found.getString("linkId"), OWNER);
        assertEquals(ALICE, revealed.getString("discordId"));
        assertEquals("name-001", revealed.getString("discordName"));

        admin.removeLink(found.getString("linkId"), "bad link", OWNER);
        assertEquals("token_revoked", assertThrows(LinkException.class, () -> service.session(token, "Zezima", "1")).code());
        assertEquals("removed", admin.findLinks("Zezima").getArray("links").getObject(0).getString("status"));
        List<String> actions = admin.audit(10).getArray("audit").stream(DataArrayGet::object).map(o -> o.getString("action")).toList();
        assertEquals(List.of("remove_link", "reveal_discord"), actions);
    }

    /** DataArray.stream needs a getter; this keeps the calls short. */
    private static final class DataArrayGet {
        static DataObject object(net.dv8tion.jda.api.utils.data.DataArray array, int index) {
            return array.getObject(index);
        }
    }
}
