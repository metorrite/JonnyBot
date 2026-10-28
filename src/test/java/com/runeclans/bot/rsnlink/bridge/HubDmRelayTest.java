package com.runeclans.bot.rsnlink.bridge;

import com.runeclans.bot.rsnlink.LinkDmSender;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The routes runeclans-hub calls to have the bot DM RuneLink codes (T-166), and the DM's buttons going back to the hub. Discord is faked. */
class HubDmRelayTest {
    private static final String ID = "1".repeat(18);
    private static final String PAIRING = "0f8fad5b-d9cb-469f-a165-70867728950e";

    /** Records what it was asked to do; sends only to {@link #ID}. */
    static final class FakeDiscord implements LinkDmSender {
        final List<String> sent = new ArrayList<>();

        @Override
        public Optional<String> resolveUsername(String username) {
            return username.equals("someone.name") ? Optional.of(ID) : Optional.empty();
        }

        @Override
        public boolean sendCode(String discordId, String rsnDisplay, String userCode, String pairingId, long expiresInMinutes) {
            sent.add(discordId + "|" + rsnDisplay + "|" + userCode + "|" + pairingId + "|" + expiresInMinutes);
            return discordId.equals(ID);
        }
    }

    private static DataObject send(String discordId) {
        return DataObject.empty().put("discordId", discordId).put("rsn", "Zezima").put("code", "ABCD-EF23").put("pairingId", PAIRING)
                .put("expiresInMinutes", 10);
    }

    @Test
    void sendPassesTheCodeToDiscordAndSaysWhetherItWent() {
        FakeDiscord discord = new FakeDiscord();
        HubDmRelay.Answer ok = HubDmRelay.answer("send", send(ID), discord);
        assertEquals(200, ok.status());
        assertTrue(ok.body().getBoolean("sent"));
        assertEquals(List.of(ID + "|Zezima|ABCD-EF23|" + PAIRING + "|10"), discord.sent);

        HubDmRelay.Answer refused = HubDmRelay.answer("send", send("2".repeat(18)), discord);
        assertEquals(200, refused.status());
        assertFalse(refused.body().getBoolean("sent"), "Discord refusing is an answer, not an error");
    }

    @Test
    void sendChecksEveryField() {
        FakeDiscord discord = new FakeDiscord();
        assertEquals("bad_discord_id", HubDmRelay.answer("send", send("12"), discord).body().getString("error"));
        assertEquals("bad_rsn", HubDmRelay.answer("send", send(ID).put("rsn", "<b>far too long</b>"), discord).body().getString("error"));
        assertEquals("bad_code", HubDmRelay.answer("send", send(ID).put("code", "**bold**"), discord).body().getString("error"));
        assertEquals("bad_pairing_id", HubDmRelay.answer("send", send(ID).put("pairingId", "x"), discord).body().getString("error"));
        assertEquals("bad_expiry", HubDmRelay.answer("send", send(ID).put("expiresInMinutes", 600), discord).body().getString("error"));
        assertTrue(discord.sent.isEmpty());
        assertEquals(404, HubDmRelay.answer("anything", DataObject.empty(), discord).status());
    }

    @Test
    void resolveFindsAUniqueUsernameOrNothing() {
        FakeDiscord discord = new FakeDiscord();
        assertEquals(ID, HubDmRelay.answer("resolve", DataObject.empty().put("username", "someone.name"), discord).body().getString("discordId"));
        HubDmRelay.Answer none = HubDmRelay.answer("resolve", DataObject.empty().put("username", "nobody.here"), discord);
        assertEquals(200, none.status());
        assertTrue(none.body().isNull("discordId"));
        assertEquals(400, HubDmRelay.answer("resolve", DataObject.empty().put("username", "Has Space"), discord).status());
    }

    @Test
    void onlyItsOwnSecretOpensIt() {
        String secret = "s".repeat(40);
        assertTrue(HubDmRelay.secretMatches(secret, secret));
        assertFalse(HubDmRelay.secretMatches(secret, "t".repeat(40)));
        assertFalse(HubDmRelay.secretMatches(secret, null));
        assertFalse(HubDmRelay.secretMatches(null, secret), "unset means closed");
        assertFalse(HubDmRelay.secretMatches(null, null));
    }

    @Test
    void theRuneLinkHeaderIsReadFirstAndTheOldOneStillWorks() {
        var both = new com.sun.net.httpserver.Headers();
        both.add("X-RuneLink-Dm-Secret", "new");
        both.add("X-RsLink-Dm-Secret", "old");
        assertEquals("new", HubDmRelay.providedSecret(both));
        var old = new com.sun.net.httpserver.Headers();
        old.add("X-RsLink-Dm-Secret", "old");
        assertEquals("old", HubDmRelay.providedSecret(old), "a hub that still sends the old header keeps working");
        assertNull(HubDmRelay.providedSecret(new com.sun.net.httpserver.Headers()));
    }

    @Test
    void theButtonsAreSentBackToTheHub() {
        RuneClansHubClient hub = mock(RuneClansHubClient.class);
        when(hub.call(eq("POST"), eq("/v1/link/dm/not-me"), anyString())).thenReturn(new RuneClansHubClient.Answer(200, DataObject.empty().put("denied", true)));
        when(hub.call(eq("POST"), eq("/v1/link/dm/stop"), anyString())).thenReturn(new RuneClansHubClient.Answer(200, DataObject.empty().put("optedOutUntil", "x")));
        assertEquals("Thanks. That request is cancelled and nothing was linked.", HubDmButtons.press(hub, true, PAIRING, ID));
        assertEquals("Done. You won't get link codes by DM for 90 days.", HubDmButtons.press(hub, false, PAIRING, ID));

        when(hub.call(eq("POST"), eq("/v1/link/dm/not-me"), anyString())).thenReturn(new RuneClansHubClient.Answer(200, DataObject.empty().put("denied", false)));
        assertEquals("That request has already ended. Nothing was linked.", HubDmButtons.press(hub, true, PAIRING, ID));

        when(hub.call(eq("POST"), eq("/v1/link/dm/not-me"), anyString())).thenReturn(null);
        assertNull(HubDmButtons.press(hub, true, PAIRING, ID), "an unreachable hub is 'try again', not a false 'done'");
    }

    @Test
    void hubButtonIdsDontCollideWithTheBotsOwn() {
        assertFalse((HubDmRelay.NOT_ME + PAIRING).startsWith(JdaLinkDmSender.NOT_ME));
        assertFalse((HubDmRelay.STOP + PAIRING).startsWith(JdaLinkDmSender.STOP));
        assertFalse((JdaLinkDmSender.NOT_ME + PAIRING).startsWith(HubDmRelay.NOT_ME));
        assertFalse((JdaLinkDmSender.STOP + PAIRING).startsWith(HubDmRelay.STOP));
    }
}
