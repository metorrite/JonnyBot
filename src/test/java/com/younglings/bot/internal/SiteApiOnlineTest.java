package com.younglings.bot.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiteApiOnlineTest {
    @Test
    void theOnlineAnswerSaysUnavailableInsteadOfNobody() {
        var answer = SiteApi.onlineUnavailable();

        assertTrue(answer.getBoolean("unavailable"));
        assertEquals(0, answer.getInt("total"));
        assertEquals(0, answer.getArray("groups").length());
    }
}
