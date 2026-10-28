package com.runeclans.bot.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JonnybotUrlTest {
    @Test
    void unsetItIsTheAddressItAlwaysWas() {
        assertEquals("https://jonnybot.rsyounglings.com", BotConfig.jonnybotUrlFrom(null));
        assertEquals("https://jonnybot.rsyounglings.com", BotConfig.jonnybotUrlFrom("  "));
    }

    @Test
    void aConfiguredAddressIsUsedWithoutATrailingSlash() {
        assertEquals("https://jonnybot.example", BotConfig.jonnybotUrlFrom(" https://jonnybot.example/ "));
    }
}
