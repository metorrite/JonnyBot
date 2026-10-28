package com.runeclans.bot.rsnlink.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdaLinkDmSenderTest {
    @Test
    void theDmIsNeutralAndNamesTheAskingRsn() {
        String text = JdaLinkDmSender.text("Iron_Man", "BHKD-7Q2M", 10);
        assertTrue(text.contains("**Iron\\_Man**"), "the RSN is shown, escaped so _ isn't italics");
        assertTrue(text.contains("BHKD-7Q2M"));
        assertTrue(text.contains("expires in 10 minutes"));
        assertTrue(text.contains("ignore this message"));
        assertTrue(text.contains("the RuneLink plugin"), "the plugin's current name");
        assertFalse(text.contains("RS Link"), "not its old name");
        assertFalse(text.toLowerCase().contains("http"), "no links");
        assertFalse(text.toLowerCase().contains("younglings"), "no clan name");
        assertFalse(text.toLowerCase().contains("discord.gg"), "no invite");
    }
}
