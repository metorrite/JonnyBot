package com.younglings.bot.plugin;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginTokensTest {
    private final SecureRandom random = new SecureRandom();

    @Test
    void aTokenHasThePrefixAndIsLongAndUnique() {
        String a = PluginTokens.generate(random);
        String b = PluginTokens.generate(random);

        assertTrue(a.startsWith("jbp_"));
        assertEquals(47, a.length());
        assertNotEquals(a, b);
        assertTrue(PluginTokens.looksLikeToken(a));
    }

    @Test
    void junkDoesNotLookLikeAToken() {
        assertFalse(PluginTokens.looksLikeToken(null));
        assertFalse(PluginTokens.looksLikeToken(""));
        assertFalse(PluginTokens.looksLikeToken("jbp_short"));
        assertFalse(PluginTokens.looksLikeToken("xxx_" + "a".repeat(43)));
        assertFalse(PluginTokens.looksLikeToken("jbp_" + "!".repeat(43)));
    }

    @Test
    void theHashIsStableHexAndDiffersPerToken() {
        String a = PluginTokens.generate(random);

        assertEquals(PluginTokens.hash(a), PluginTokens.hash(a));
        assertEquals(64, PluginTokens.hash(a).length());
        assertNotEquals(PluginTokens.hash(a), PluginTokens.hash(PluginTokens.generate(random)));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", PluginTokens.hash(""));
    }

    @Test
    void labelsAreTidiedAndBounded() {
        assertEquals("Plugin", PluginTokens.cleanLabel(null));
        assertEquals("Plugin", PluginTokens.cleanLabel("   "));
        assertEquals("My laptop", PluginTokens.cleanLabel("  My    laptop  "));
        assertEquals(PluginTokens.MAX_LABEL, PluginTokens.cleanLabel("x".repeat(100)).length());
    }

    @Test
    void thePreviewRevealsOnlyTheStart() {
        String token = PluginTokens.generate(random);
        String preview = PluginTokens.preview(token);

        assertTrue(preview.startsWith(token.substring(0, 8)));
        assertTrue(preview.endsWith("…"));
        assertTrue(preview.length() < 12);
    }

    @Test
    void retentionDaysDefaultWhenUnsetOrUnreadable() {
        assertEquals(180, PluginRetention.retentionDays(null));
        assertEquals(180, PluginRetention.retentionDays(" "));
        assertEquals(180, PluginRetention.retentionDays("lots"));
        assertEquals(30, PluginRetention.retentionDays(" 30 "));
        assertEquals(0, PluginRetention.retentionDays("0"));
        assertEquals(0, PluginRetention.retentionDays("-5"));
    }
}
