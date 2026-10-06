package com.younglings.bot.runescape;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class XpTextTest {
    @Test
    void wholeMillionsBecomeM() {
        assertEquals("200M XP in Necromancy", XpText.shorten("200000000XP in Necromancy"));
        assertEquals("I reached 50M XP in Attack", XpText.shorten("I reached 50,000,000 XP in Attack"));
        assertEquals("2M XP in Magic", XpText.shorten("2000000XP in Magic"));
    }

    @Test
    void otherNumbersAreLeftAlone() {
        assertEquals("13034431XP in Slayer", XpText.shorten("13034431XP in Slayer"));
        assertEquals("Levelled up Attack.", XpText.shorten("Levelled up Attack."));
        assertEquals("I killed 1,500 Vorago", XpText.shorten("I killed 1,500 Vorago"));
        assertEquals("Capped at my Clan Citadel", XpText.shorten("Capped at my Clan Citadel"));
    }

    @Test
    void amountFormatting() {
        assertEquals("200M", XpText.amount(200_000_000));
        assertEquals("13,034,431", XpText.amount(13_034_431));
        assertEquals("0", XpText.amount(0));
    }
}
