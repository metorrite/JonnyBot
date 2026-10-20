package com.younglings.bot.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TestSecretsTest {
    @Test
    void fakeValuesAreLabelledAndDifferEachCall() {
        var a = TestSecrets.fake("token");
        assertTrue(a.startsWith("token-"));
        assertEquals("token-".length() + 32, a.length());
        assertNotEquals(a, TestSecrets.fake("token"));
    }

    @Test
    void shapedFakesHaveTheRightStructure() {
        assertEquals(3, TestSecrets.fakeDiscordToken().split("[.]").length);
        assertTrue(TestSecrets.fakePostgresUrl().startsWith("postgresql://user:"));
    }
}
