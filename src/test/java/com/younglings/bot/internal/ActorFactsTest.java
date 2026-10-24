package com.younglings.bot.internal;

import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The signed sign-in facts for a bot-less site (the contract shared with the website): only a fresh, signed answer about this server counts. */
class ActorFactsTest {
    private static final String SECRET = "test-secret-" + "x".repeat(20);
    private static final long USER = Long.parseLong("7".repeat(18));
    private static final long GUILD = Long.parseLong("8".repeat(18));
    private static final long NOW = 1_800_000_000L;

    static DataObject facts(long userId, long guildId, boolean admin, boolean owner, long iat, long exp) {
        return DataObject.empty().put("v", 1).put("userId", Long.toString(userId)).put("guildId", Long.toString(guildId))
                .put("admin", admin).put("owner", owner).put("iat", iat).put("exp", exp);
    }

    private static ActorFacts verify(DataObject json, long guildId) throws ActorFacts.Invalid {
        String actor = ActorFacts.encode(json);
        return ActorFacts.verify(actor, ActorFacts.sign(actor, SECRET), SECRET, guildId, NOW);
    }

    @Test
    void aValidSignatureGivesTheFacts() throws Exception {
        ActorFacts f = verify(facts(USER, GUILD, true, false, NOW - 10, NOW + 200), GUILD);
        assertEquals(USER, f.userId());
        assertEquals(GUILD, f.guildId());
        assertTrue(f.admin());
        assertFalse(f.owner());
    }

    @Test
    void theSignatureIsLowercaseHexHmacSha256OfTheExactHeader() {
        // RFC 4231 test case 2: key "Jefe", data "what do ya want for nothing?"
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843", ActorFacts.sign("what do ya want for nothing?", "Jefe"));
    }

    @Test
    void expiredFactsAreRefused() {
        var e = assertThrows(ActorFacts.Invalid.class, () -> verify(facts(USER, GUILD, true, true, NOW - 400, NOW - 100), GUILD));
        assertTrue(e.getMessage().contains("expired"));
        assertThrows(ActorFacts.Invalid.class, () -> verify(facts(USER, GUILD, true, true, NOW - 300, NOW), GUILD), "exp must be after now");
    }

    @Test
    void factsDatedInTheFutureOrLivingTooLongAreRefused() throws Exception {
        assertThrows(ActorFacts.Invalid.class, () -> verify(facts(USER, GUILD, true, true, NOW + 61, NOW + 200), GUILD));
        verify(facts(USER, GUILD, true, true, NOW + 60, NOW + 200), GUILD); // a minute of clock skew is fine
        assertThrows(ActorFacts.Invalid.class, () -> verify(facts(USER, GUILD, true, true, NOW - 10, NOW + 291), GUILD), "exp - iat over 300");
        verify(facts(USER, GUILD, true, true, NOW - 10, NOW + 290), GUILD); // exactly 300 is allowed
    }

    @Test
    void tamperedFactsAreRefused() {
        String actor = ActorFacts.encode(facts(USER, GUILD, false, false, NOW, NOW + 200));
        String signature = ActorFacts.sign(actor, SECRET);
        String promoted = ActorFacts.encode(facts(USER, GUILD, true, true, NOW, NOW + 200));
        var e = assertThrows(ActorFacts.Invalid.class, () -> ActorFacts.verify(promoted, signature, SECRET, GUILD, NOW));
        assertTrue(e.getMessage().contains("signature"));
        assertThrows(ActorFacts.Invalid.class, () -> ActorFacts.verify(actor, signature.toUpperCase(), SECRET, GUILD, NOW), "lowercase hex only");
        assertThrows(ActorFacts.Invalid.class, () -> ActorFacts.verify(actor, ActorFacts.sign(actor, SECRET + "other"), SECRET, GUILD, NOW), "another key");
    }

    @Test
    void factsAboutAnotherServerAreRefused() {
        var e = assertThrows(ActorFacts.Invalid.class, () -> verify(facts(USER, GUILD, true, true, NOW, NOW + 200), GUILD + 1));
        assertTrue(e.getMessage().contains("different server"));
    }

    @Test
    void missingMalformedOrUnknownVersionFactsAreRefused() {
        assertThrows(ActorFacts.Invalid.class, () -> ActorFacts.verify(null, null, SECRET, GUILD, NOW));
        assertThrows(ActorFacts.Invalid.class, () -> ActorFacts.verify("", "", SECRET, GUILD, NOW));
        assertThrows(ActorFacts.Invalid.class, () -> verify(facts(USER, GUILD, true, true, NOW, NOW + 200).put("v", 2), GUILD));
        assertThrows(ActorFacts.Invalid.class, () -> verify(facts(USER, GUILD, true, true, NOW, NOW + 200).put("userId", "12"), GUILD));
        assertThrows(ActorFacts.Invalid.class, () -> verify(facts(USER, GUILD, true, true, NOW, NOW + 200).remove("admin"), GUILD));
        String garbage = Base64.getUrlEncoder().withoutPadding().encodeToString("not json".getBytes(StandardCharsets.UTF_8));
        assertThrows(ActorFacts.Invalid.class, () -> ActorFacts.verify(garbage, ActorFacts.sign(garbage, SECRET), SECRET, GUILD, NOW));
        assertThrows(ActorFacts.Invalid.class, () -> ActorFacts.verify("a", "b", null, GUILD, NOW), "no secret configured");
    }

    /**
     * The website's own fixed vector (runeclans {@code packages/api-client/tests/actorFacts.test.mjs}): the exact headers its signer makes are
     * accepted here, so the two sides can't drift apart. Ids made at runtime as there.
     */
    @Test
    void theWebsitesFixedVectorIsAccepted() throws Exception {
        String secret = "rc-test-secret";
        long user = Long.parseLong("1".repeat(18));
        long guild = Long.parseLong("2".repeat(18));
        long iat = 1_760_000_000L;
        String actor = "eyJ2IjoxLCJ1c2VySWQiOiIxMTExMTExMTExMTExMTExMTEiLCJndWlsZElkIjoiMjIyMjIyMjIyMjIyMjIyMjIyIiwiYWRtaW4iOnRydWUsIm93bmVyIjpmYWxzZSwiaWF0IjoxNzYwMDAwMDAwLCJleHAiOjE3NjAwMDAzMDB9";
        String sig = "815303523e424fc260a5665362990623015fd548d174c4337d72d16e7009e1d0";

        assertEquals("{\"v\":1,\"userId\":\"" + user + "\",\"guildId\":\"" + guild + "\",\"admin\":true,\"owner\":false,\"iat\":" + iat + ",\"exp\":" + (iat + 300) + "}",
                new String(Base64.getUrlDecoder().decode(actor), StandardCharsets.UTF_8));
        assertEquals(sig, ActorFacts.sign(actor, secret), "the same HMAC as the website's");
        // the bot only checks facts, never makes them, so it reads the website's exact string rather than rebuilding it

        ActorFacts f = ActorFacts.verify(actor, sig, secret, guild, iat + 10);
        assertEquals(user, f.userId());
        assertEquals(guild, f.guildId());
        assertTrue(f.admin());
        assertFalse(f.owner());
        assertEquals(iat, f.iat());
        assertEquals(iat + 300, f.exp());
        assertThrows(ActorFacts.Invalid.class, () -> ActorFacts.verify(actor, sig, secret, guild, iat + 300), "expired at exp");
        assertThrows(ActorFacts.Invalid.class, () -> ActorFacts.verify(actor, sig, "other", guild, iat + 10));
    }
}
