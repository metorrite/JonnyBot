package com.younglings.bot.linkedroles;

import com.younglings.bot.linkedroles.RoleMetadata.Profile;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Linked Roles without a database or Discord: the metadata schema and values, the token seal, and the settings. */
class LinkedRolesUnitTest {
    static byte[] randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    static String fakeAppId() {
        return "4" + "2".repeat(17);
    }

    // ---------- schema and values ----------

    @Test
    void theSchemaFitsDiscordsRules() {
        assertTrue(RoleMetadata.SCHEMA.size() <= 5, "Discord allows at most 5 records");
        Set<String> keys = new HashSet<>();
        for (RoleMetadata.Field f : RoleMetadata.SCHEMA) {
            assertTrue(f.key().matches("[a-z0-9_]{1,50}"), f.key());
            assertTrue(keys.add(f.key()), "duplicate " + f.key());
            assertTrue(!f.name().isEmpty() && f.name().length() <= 100, f.name());
            assertTrue(!f.description().isEmpty() && f.description().length() <= 200, f.description());
            assertTrue(f.type() >= 1 && f.type() <= 8, "type " + f.type());
        }
        assertEquals(5, RoleMetadata.schemaJson().length());
        assertEquals(7, RoleMetadata.schemaJson().getObject(0).getInt("type"));
    }

    @Test
    void valuesAreStringsAndUnknownsAreLeftOut() {
        Instant since = Instant.parse("2026-01-02T03:04:05.678Z");
        Map<String, String> v = RoleMetadata.values(new Profile("Zezima", since, 2898, 138, true));
        assertEquals("1", v.get("rsn_linked"));
        assertEquals("2898", v.get("total_level"));
        assertEquals("138", v.get("combat_level"));
        assertEquals("1", v.get("clan_member"));
        assertEquals("2026-01-02T03:04:05Z", v.get("linked_since"));

        Map<String, String> noStats = RoleMetadata.values(new Profile("Zezima", since, null, null, false));
        assertFalse(noStats.containsKey("total_level"), "no snapshot yet: the condition fails instead of guessing");
        assertEquals("0", noStats.get("clan_member"));

        Map<String, String> none = RoleMetadata.values(Profile.NONE);
        assertEquals(Map.of("rsn_linked", "0", "clan_member", "0"), none);
        v.values().forEach(value -> assertTrue(value.length() <= 100));
    }

    @Test
    void theConnectionNamesThePlatformAndTheRsn() {
        DataObject body = RoleMetadata.connectionJson(new Profile("Zezima", null, 99, 3, false));
        assertEquals("RuneScape", body.getString("platform_name"));
        assertEquals("Zezima", body.getString("platform_username"));
        assertEquals("99", body.getObject("metadata").getString("total_level"));
        assertFalse(RoleMetadata.connectionJson(Profile.NONE).hasKey("platform_username"));
        assertTrue(RoleMetadata.clearedJson().getObject("metadata").keys().isEmpty());
    }

    @Test
    void theFingerprintChangesWithALevelUp() {
        Profile before = new Profile("Zezima", null, 2000, 120, false);
        assertEquals(RoleMetadata.fingerprint(before), RoleMetadata.fingerprint(new Profile("Zezima", null, 2000, 120, false)));
        assertNotEquals(RoleMetadata.fingerprint(before), RoleMetadata.fingerprint(new Profile("Zezima", null, 2001, 120, false)));
    }

    // ---------- the token seal ----------

    @Test
    void aSealedTokenOpensOnlyForItsOwnerAndKey() {
        byte[] key = randomKey();
        TokenBox box = new TokenBox(key);
        String token = "fake-access-" + System.nanoTime();
        byte[] sealed = box.seal(token, 42L);
        assertEquals(token, box.open(sealed, 42L));
        assertFalse(new String(sealed, java.nio.charset.StandardCharsets.ISO_8859_1).contains(token), "never stored in clear");
        assertNull(box.open(sealed, 43L), "another person's row");
        assertNull(new TokenBox(randomKey()).open(sealed, 42L), "another key");
        byte[] tampered = sealed.clone();
        tampered[tampered.length - 1] ^= 1;
        assertNull(box.open(tampered, 42L));
        assertNull(box.open(new byte[3], 42L));
        byte[] again = box.seal(token, 42L);
        assertFalse(java.util.Arrays.equals(sealed, again), "a fresh IV each time");
        assertArrayEquals(token.getBytes(), box.open(again, 42L).getBytes());
    }

    // ---------- settings ----------

    static Map<String, String> fullEnv() {
        Map<String, String> env = new HashMap<>();
        env.put("LINKED_ROLES_ENABLED", "true");
        env.put("LINKED_ROLES_TOKEN_KEY", Base64.getEncoder().encodeToString(randomKey()));
        env.put("LINKED_ROLES_CLIENT_ID", fakeAppId());
        env.put("LINKED_ROLES_CLIENT_SECRET", "test-client-secret-not-real");
        return env;
    }

    @Test
    void offByDefaultAndOffWhenHalfSetUp() {
        assertFalse(LinkedRolesSettings.from(name -> null).enabled());
        assertTrue(LinkedRolesSettings.from(fullEnv()::get).enabled());
        for (String missing : new String[]{"LINKED_ROLES_ENABLED", "LINKED_ROLES_TOKEN_KEY", "LINKED_ROLES_CLIENT_ID", "LINKED_ROLES_CLIENT_SECRET"}) {
            Map<String, String> env = fullEnv();
            env.remove(missing);
            LinkedRolesSettings s = LinkedRolesSettings.from(env::get);
            assertFalse(s.enabled(), missing);
            assertTrue(s.problem() != null && !s.problem().isBlank());
        }
        Map<String, String> shortKey = fullEnv();
        shortKey.put("LINKED_ROLES_TOKEN_KEY", Base64.getEncoder().encodeToString(new byte[16]));
        assertFalse(LinkedRolesSettings.from(shortKey::get).enabled());
    }

    @Test
    void theApiBaseIsHttpsOrLocalhostOnly() {
        assertEquals(LinkedRolesSettings.DEFAULT_API, LinkedRolesSettings.apiBase(null));
        assertEquals("http://127.0.0.1:3245/api/v10", LinkedRolesSettings.apiBase("http://127.0.0.1:3245/api/v10/"));
        assertEquals("https://example.test/api", LinkedRolesSettings.apiBase("https://example.test/api"));
        assertNull(LinkedRolesSettings.apiBase("http://evil.example/api"));
        assertNull(LinkedRolesSettings.apiBase("https://user:pw@discord.com/api"));
        Map<String, String> env = fullEnv();
        env.put("LINKED_ROLES_DISCORD_API", "http://evil.example");
        assertFalse(LinkedRolesSettings.from(env::get).enabled());
    }

    @Test
    void theHubsRsLinkAnswerIsReadCarefully() {
        long a = Long.parseLong("1".repeat(18));
        long b = Long.parseLong("2".repeat(18));
        long stranger = Long.parseLong("3".repeat(18));
        DataObject answer = DataObject.fromJson("{\"links\":["
                + "{\"discordId\":\"" + a + "\",\"rsn\":\"Zezima\",\"linkedAt\":\"2026-05-01T00:00:00Z\"},"
                + "{\"discordId\":\"" + b + "\",\"rsn\":\"Not a valid name!\"},"
                + "{\"discordId\":\"" + stranger + "\",\"rsn\":\"Woox\"},"
                + "{\"discordId\":\"nope\",\"rsn\":\"Woox\"}, 7]}");
        Map<Long, RsLinkSource.Current> got = RsLinkSource.parse(answer, java.util.List.of(a, b)).orElseThrow();
        assertEquals(Map.of(a, new RsLinkSource.Current("Zezima", Instant.parse("2026-05-01T00:00:00Z"))), got,
                "a bad name or an id nobody asked about is ignored");
        assertTrue(RsLinkSource.parse(DataObject.fromJson("{}"), java.util.List.of(a)).isEmpty(), "an unexpected answer is not 'no links'");
        assertEquals(Map.of(), RsLinkSource.parse(DataObject.fromJson("{\"links\":[]}"), java.util.List.of(a)).orElseThrow());
        assertTrue(RsLinkSource.NONE.current(java.util.List.of(a)).isEmpty());

        // T-552: the hub names each link's level; only a level above implicit is confirmed
        DataObject levels = DataObject.fromJson("{\"links\":["
                + "{\"discordId\":\"" + a + "\",\"rsn\":\"Zezima\",\"verification\":\"public_data_corroborated\"},"
                + "{\"discordId\":\"" + b + "\",\"rsn\":\"Woox\",\"verification\":\"implicit\"}]}");
        Map<Long, RsLinkSource.Current> leveled = RsLinkSource.parse(levels, java.util.List.of(a, b)).orElseThrow();
        assertTrue(leveled.get(a).confirmed());
        assertFalse(leveled.get(b).confirmed());
        assertFalse(got.get(a).confirmed(), "an older hub that doesn't say is not confirmed");
    }

    @Test
    void requireConfirmedIsOffByDefault() {
        assertFalse(LinkedRolesSettings.from(fullEnv()::get).requireConfirmed());
        Map<String, String> env = fullEnv();
        env.put("LINKED_ROLES_REQUIRE_CONFIRMED", "true");
        assertTrue(LinkedRolesSettings.from(env::get).requireConfirmed());
    }

    @Test
    void rsLinkNamesAreCleaned() {
        assertEquals("Zezima", LinkedRoles.cleanRsn("  Zezima "));
        assertEquals("a b_c-1", LinkedRoles.cleanRsn("a b_c-1"));
        assertNull(LinkedRoles.cleanRsn(""));
        assertNull(LinkedRoles.cleanRsn("ThirteenChars"));
        assertNull(LinkedRoles.cleanRsn("<script>"));
    }
}
