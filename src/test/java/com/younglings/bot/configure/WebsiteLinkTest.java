package com.younglings.bot.configure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebsiteLinkTest {
    @Test
    void aBareDomainBecomesHttps() {
        assertEquals("https://rsyounglings.com", WebsiteLink.normalize("rsyounglings.com").orElseThrow());
        assertEquals("https://rsyounglings.com/clan", WebsiteLink.normalize("  https://rsyounglings.com/clan  ").orElseThrow());
        assertEquals("http://example.org", WebsiteLink.normalize("http://example.org").orElseThrow());
    }

    @Test
    void thingsThatAreNotWebAddressesAreRefused() {
        assertTrue(WebsiteLink.normalize("javascript:alert(1)").isEmpty());
        assertTrue(WebsiteLink.normalize("ftp://example.com").isEmpty());
        assertTrue(WebsiteLink.normalize("not a url").isEmpty());
        assertTrue(WebsiteLink.normalize("localhost").isEmpty(), "needs a real domain");
        assertTrue(WebsiteLink.normalize("").isEmpty());
        assertTrue(WebsiteLink.normalize("https://" + "a".repeat(250) + ".com").isEmpty(), "too long");
    }

    @Test
    void charactersThatWouldBreakAMarkdownLinkAreEscaped() {
        assertEquals("https://example.com/a%28b%29", WebsiteLink.normalize("https://example.com/a(b)").orElseThrow());
    }

    @Test
    void theTitleIsALinkOnlyWhenThereIsAWebsite() {
        assertEquals("Younglings", WebsiteLink.linkedTitle("Younglings", null));
        assertEquals("[Younglings](https://rsyounglings.com)", WebsiteLink.linkedTitle("Younglings", "https://rsyounglings.com"));
    }
}
