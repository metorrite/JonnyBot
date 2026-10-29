package com.runeclans.bot.runescape;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ClanRosterAnswerTest {

    @Test
    void theRosterCsvIsParsed() {
        assertTrue(RuneScapeApiClient.isRosterAnswer("text/comma-separated-values"));
        assertTrue(RuneScapeApiClient.isRosterAnswer(""));
    }

    @Test
    void theRankingPageForAnUnknownClanIsNot() {
        assertFalse(RuneScapeApiClient.isRosterAnswer("text/html; charset=utf-8"));
        assertFalse(RuneScapeApiClient.isRosterAnswer("TEXT/HTML"));
    }
}
