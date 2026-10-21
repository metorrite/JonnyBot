package com.younglings.bot.rsnlink;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinkCryptoTest {
    private final SecureRandom random = new SecureRandom();

    private LinkCrypto crypto(Map<String, String> env) {
        return new LinkCrypto(LinkSettings.fromEnv(env::get), random);
    }

    @Test
    void discordIdsRoundTripAndNeverEncryptTheSameWayTwice() {
        LinkCrypto crypto = crypto(LinkTestSupport.env("postgresql://u:p@localhost:5432/x"));
        byte[] a = crypto.encrypt("123456789012345678");
        byte[] b = crypto.encrypt("123456789012345678");
        assertFalse(Arrays.equals(a, b), "a fresh nonce each time");
        assertEquals("123456789012345678", crypto.decrypt(a, LinkCrypto.CURRENT_KEY_VERSION));
        a[a.length - 1] ^= 1;
        byte[] tampered = a;
        assertThrows(IllegalStateException.class, () -> crypto.decrypt(tampered, LinkCrypto.CURRENT_KEY_VERSION));
    }

    @Test
    void theOldKeyStillOpensRowsDuringARotation() {
        Map<String, String> env = LinkTestSupport.env("postgresql://u:p@localhost:5432/x");
        byte[] sealed = crypto(env).encrypt("123456789012345678");
        env.put("LINK_ENC_KEY_OLD", env.get("LINK_ENC_KEY"));
        env.put("LINK_ENC_KEY", LinkTestSupport.randomKey(random));
        LinkCrypto rotated = crypto(env);
        assertEquals("123456789012345678", rotated.decrypt(sealed, (short) 0));
        assertThrows(IllegalStateException.class, () -> rotated.decrypt(sealed, LinkCrypto.CURRENT_KEY_VERSION));
    }

    @Test
    void lookupsAreKeyedAndKeptApartByKind() {
        Map<String, String> env = LinkTestSupport.env("postgresql://u:p@localhost:5432/x");
        LinkCrypto crypto = crypto(env);
        assertTrue(Arrays.equals(crypto.lookup("discord", "1"), crypto.lookup("discord", "1")));
        assertFalse(Arrays.equals(crypto.lookup("discord", "1"), crypto.lookup("ip", "1")));
        env.put("LINK_LOOKUP_KEY", LinkTestSupport.randomKey(random));
        assertFalse(Arrays.equals(crypto.lookup("discord", "1"), crypto(env).lookup("discord", "1")), "another key, another fingerprint");
    }

    @Test
    void aliasesAreStablePerLinkAndDifferentPerPlugin() {
        LinkCrypto crypto = crypto(LinkTestSupport.env("postgresql://u:p@localhost:5432/x"));
        String a = crypto.alias("link-1", "RuneSync");
        assertTrue(a.matches("a1_[a-z2-7]{26}"), a);
        assertEquals(a, crypto.alias("link-1", "runesync"), "plugin names compare in lower case");
        assertNotEquals(a, crypto.alias("link-1", "OtherPlugin"));
        assertNotEquals(a, crypto.alias("link-2", "RuneSync"));
    }

    @Test
    void base32MatchesTheStandard() {
        assertEquals("mzxw6ytboi", LinkCrypto.base32("foobar".getBytes()));
    }
}
