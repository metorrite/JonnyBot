package com.runeclans.bot.goodbye;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GoodbyeServiceTest {
    private static String render(String template) {
        return GoodbyeService.render(template, "PvmRyan", "https://cdn/x.png", "Younglings", "goodbyes", 181);
    }

    @Test
    void theDefaultMessageNamesTheLeaverAndTheServerSize() {
        assertEquals("**PvmRyan** has left **Younglings**. We're now 181 members.", render(GoodbyeConfig.DEFAULT_CONTENT));
    }

    @Test
    void theUserVariableIsTheirNameInBoldNotAMentionOfSomeoneWhoIsGone() {
        assertEquals("Bye **PvmRyan**", render("Bye {user}"));
    }

    @Test
    void everyoneAndHereShowTheWordsWithoutPinging() {
        assertEquals("@everyone @here", render("{everyone} {here}"));
    }

    @Test
    void anUnknownVariableIsLeftAsTyped() {
        assertEquals("{nope}", render("{nope}"));
    }

    @Test
    void aMentionByNameIsLeftAsAPlainNameBecauseThereIsNobodyToLookUp() {
        assertFalse(render("{@Someone}").contains("<@"));
    }
}
