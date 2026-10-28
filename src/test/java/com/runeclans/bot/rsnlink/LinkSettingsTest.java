package com.runeclans.bot.rsnlink;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinkSettingsTest {
    @Test
    void theServiceIsOffUntilItsDatabaseIsSet() {
        assertNull(LinkSettings.fromEnv(name -> null));
    }

    @Test
    void missingOrShortKeysAreNamedButNeverShown() {
        Map<String, String> env = LinkTestSupport.env("postgresql://u:p@localhost:5432/x");
        String lookup = env.get("LINK_LOOKUP_KEY");
        env.remove("LINK_ENC_KEY");
        env.put("LINK_ALIAS_KEY", "c2hvcnQ=");
        var e = assertThrows(LinkSettings.InvalidSettings.class, () -> LinkSettings.fromEnv(env::get));
        assertTrue(e.getMessage().contains("LINK_ENC_KEY is missing"));
        assertTrue(e.getMessage().contains("LINK_ALIAS_KEY is shorter"));
        assertFalse(e.getMessage().contains(lookup));
    }

    @Test
    void plainHttpIsOnlyForLocalhostAndKeysMustDiffer() {
        Map<String, String> env = LinkTestSupport.env("postgresql://u:p@localhost:5432/x");
        env.put("LINK_PUBLIC_BASE_URL", "http://link.example.org");
        assertThrows(LinkSettings.InvalidSettings.class, () -> LinkSettings.fromEnv(env::get));
        env.put("LINK_PUBLIC_BASE_URL", "https://link.example.org/");
        assertEquals("https://link.example.org", LinkSettings.fromEnv(env::get).publicBaseUrl());
        env.put("LINK_ALIAS_KEY", env.get("LINK_LOOKUP_KEY"));
        assertThrows(LinkSettings.InvalidSettings.class, () -> LinkSettings.fromEnv(env::get));
    }

    @Test
    void printingTheSettingsHidesTheKeys() {
        LinkSettings s = LinkSettings.fromEnv(LinkTestSupport.env("postgresql://u:p@localhost:5432/x")::get);
        assertEquals("LinkSettings[schema=rsnlink, keys=hidden]", s.toString());
    }
}
