package com.younglings.bot.rsnlink;

import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AliasSignerTest {
    private static byte[] seed() {
        byte[] seed = new byte[32];
        new SecureRandom().nextBytes(seed);
        return seed;
    }

    @Test
    void theSameSeedAlwaysGivesTheSameKey() {
        byte[] seed = seed();
        AliasSigner a = new AliasSigner(seed, "http://localhost:3000");
        AliasSigner b = new AliasSigner(seed, "http://localhost:3000");
        assertEquals(a.keyId(), b.keyId());
        assertEquals(a.keySet().toString(), b.keySet().toString());
        assertNotEquals(a.keyId(), new AliasSigner(seed(), "http://localhost:3000").keyId());
        String jws = a.assertion("runesync", "a1_x", "zezima", "implicit", false, Instant.now());
        assertTrue(b.verify(jws), "a restart can check what was signed before it");
    }

    @Test
    void assertionsCarryOnlyTheAgreedClaimsAndRejectTampering() {
        AliasSigner signer = new AliasSigner(seed(), "http://localhost:3000");
        Instant now = Instant.parse("2026-10-20T00:00:00Z");
        String jws = signer.assertion("runesync", "a1_abc", "zezima", "implicit", true, now);
        String[] parts = jws.split("\\.");
        DataObject header = DataObject.fromJson(Base64.getUrlDecoder().decode(parts[0]));
        DataObject claims = DataObject.fromJson(Base64.getUrlDecoder().decode(parts[1]));
        assertEquals("EdDSA", header.getString("alg"));
        assertEquals(signer.keyId(), header.getString("kid"));
        assertEquals("http://localhost:3000", claims.getString("iss"));
        assertEquals("a1_abc", claims.getString("alias"));
        assertEquals("zezima", claims.getString("rsn"));
        assertEquals("implicit", claims.getString("ver"));
        assertTrue(claims.getBoolean("disputed"));
        assertEquals(now.getEpochSecond() + 15 * 60, claims.getLong("exp"));
        assertEquals(8, claims.keys().size(), "iss, plugin, alias, rsn, ver, iat, exp, disputed: no Discord data");
        assertTrue(signer.verify(jws));

        String forged = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(claims.put("alias", "a1_other").toJson()) + "." + parts[2];
        assertFalse(signer.verify(forged));
        DataObject key = signer.keySet().getArray("keys").getObject(0);
        assertEquals("OKP", key.getString("kty"));
        assertEquals("Ed25519", key.getString("crv"));
        assertEquals(32, Base64.getUrlDecoder().decode(key.getString("x")).length);
    }
}
