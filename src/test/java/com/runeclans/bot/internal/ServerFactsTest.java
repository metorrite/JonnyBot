package com.runeclans.bot.internal;

import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Signed staff-server facts (N5): the fixed vector the website's packages/api-client/tests/serverFacts.test.mjs signs (worked out separately
 * in Python), and every way facts are refused. Made-up ids, built at runtime.
 */
class ServerFactsTest {
    private static final String SECRET = "rc-test-secret";
    private static final long USER = Long.parseLong("1".repeat(18));
    private static final long STAFF = Long.parseLong("3".repeat(18));
    private static final long IAT = 1760000000L;
    private static final String VECTOR =
            "eyJ2IjoxLCJ1c2VySWQiOiIxMTExMTExMTExMTExMTExMTEiLCJzZXJ2ZXJzIjp7IjMzMzMzMzMzMzMzMzMzMzMzMyI6IjMyIn0sImlhdCI6MTc2MDAwMDAwMCwiZXhwIjoxNzYwMDAwMzAwfQ";
    private static final String VECTOR_SIG = "d2be70dcb61471f93f22058250b4002ada43bf079d69a202eb2b9eef5c787938";

    @Test
    void theWebsitesVectorChecksOut() {
        assertEquals(VECTOR_SIG, ActorFacts.sign(VECTOR, SECRET));
        assertEquals(Map.of(STAFF, 32L), ServerFacts.serversOf(VECTOR, VECTOR_SIG, SECRET, USER, IAT + 10));
    }

    @Test
    void anythingOffIsNoFacts() {
        assertTrue(ServerFacts.serversOf(VECTOR, "0".repeat(64), SECRET, USER, IAT + 10).isEmpty(), "bad signature");
        assertTrue(ServerFacts.serversOf(VECTOR, VECTOR_SIG, SECRET, USER + 1, IAT + 10).isEmpty(), "someone else's");
        assertTrue(ServerFacts.serversOf(VECTOR, VECTOR_SIG, SECRET, USER, IAT + 400).isEmpty(), "expired");
        assertTrue(ServerFacts.serversOf(VECTOR, VECTOR_SIG, SECRET, USER, IAT - 120).isEmpty(), "from the future");
        assertTrue(ServerFacts.serversOf(null, null, SECRET, USER, IAT).isEmpty());
        assertTrue(ServerFacts.serversOf(VECTOR, VECTOR_SIG, "", USER, IAT + 10).isEmpty(), "no secret");
        String tooLong = ServerFacts.encode(DataObject.empty().put("v", 1).put("userId", Long.toString(USER)).put("servers", DataObject.empty())
                .put("iat", IAT).put("exp", IAT + 1000));
        assertNull(ServerFacts.verify(tooLong, ActorFacts.sign(tooLong, SECRET), SECRET, USER, IAT + 10), "lasts too long");
    }

    @Test
    void malformedServersAreSkipped() {
        String facts = ServerFacts.encode(DataObject.empty().put("v", 1).put("userId", Long.toString(USER))
                .put("servers", DataObject.empty().put(Long.toString(STAFF), "32").put("nope", "8").put(Long.toString(STAFF + 1), "x"))
                .put("iat", IAT).put("exp", IAT + 300));
        assertEquals(Map.of(STAFF, 32L), ServerFacts.serversOf(facts, ActorFacts.sign(facts, SECRET), SECRET, USER, IAT + 10));
    }
}
