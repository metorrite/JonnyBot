package com.younglings.bot.rsnlink;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinkCodesTest {
    private final SecureRandom random = new SecureRandom();

    @Test
    void userCodesAreEightUnambiguousSymbolsShownInTwoGroups() {
        for (int i = 0; i < 500; i++) {
            String code = LinkCodes.newUserCode(random);
            assertTrue(code.matches("[BCDFGHJKMNPQRSTVWXZ2-9]{4}-[BCDFGHJKMNPQRSTVWXZ2-9]{4}"), code);
            assertFalse(code.matches(".*[AEIOU01L].*"), "no vowels or look-alikes: " + code);
        }
    }

    @Test
    void typedCodesAreForgiving() {
        assertEquals("BHKD7Q2M", LinkCodes.normaliseCode("bhkd-7q2m"));
        assertEquals("BHKD7Q2M", LinkCodes.normaliseCode(" BHKD 7Q2M "));
        assertNull(LinkCodes.normaliseCode("BHKD-7Q2"), "too short");
        assertNull(LinkCodes.normaliseCode("BHKD-7Q2O"), "O is never used");
        assertNull(LinkCodes.normaliseCode(null));
    }

    @Test
    void tokensHaveTheirOwnPrefixAndShape() {
        String token = LinkCodes.newToken(random);
        assertTrue(token.startsWith("lnk_"));
        assertTrue(LinkCodes.looksLikeToken(token));
        assertFalse(LinkCodes.looksLikeToken("jbp_" + token.substring(4)), "the clan plugin's tokens are not link tokens");
        assertNotEquals(token, LinkCodes.newToken(random));
        assertTrue(LinkCodes.looksLikeDeviceSecret(LinkCodes.newDeviceSecret(random)));
    }

    @Test
    void namesAreCleanedAndMatchedLikeTheGameDoes() {
        assertEquals("Zezima", LinkCodes.normaliseRsn("  Zezima "));
        assertEquals("Iron Man", LinkCodes.normaliseRsn("Iron Man"));
        assertNull(LinkCodes.normaliseRsn("ThisNameIsTooLong"));
        assertNull(LinkCodes.normaliseRsn("Bad<name>"));
        assertEquals(LinkCodes.rsnLookup("Iron_Man"), LinkCodes.rsnLookup("iron man"));
        assertEquals(LinkCodes.rsnLookup("Iron-Man"), LinkCodes.rsnLookup("IRON MAN"));
    }

    @Test
    void pluginNamesAndTrackingIds() {
        assertEquals("runesync", LinkCodes.normalisePluginName(" RuneSync "));
        assertNull(LinkCodes.normalisePluginName(""));
        assertNull(LinkCodes.normalisePluginName("bad/name"));
        assertEquals("-12345", LinkCodes.normaliseTrackingId(" -12345 "));
        assertNull(LinkCodes.normaliseTrackingId("12a"));
    }
}
