package com.runeclans.bot.afk;

import com.runeclans.bot.hub.HubService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AfkNicknamesTest {
    @Test
    void theTagGoesInFrontAndIsNeverAddedTwice() {
        assertEquals("[AFK] Axley", AfkNicknames.tag("Axley"));
        assertEquals("[AFK] Axley", AfkNicknames.tag("[AFK] Axley"));
    }

    @Test
    void aLongNameIsCutSoTheNicknameFitsDiscordsLimit() {
        String tagged = AfkNicknames.tag("x".repeat(40));
        assertEquals(32, tagged.length());
        assertTrue(tagged.startsWith("[AFK] x"));
    }

    @Test
    void untaggingRestoresTheNameAndLeavesOtherNicknamesAlone() {
        assertEquals("Axley", AfkNicknames.untag("[AFK] Axley"));
        assertNull(AfkNicknames.untag("Axley"), "no tag, nothing to undo");
        assertNull(AfkNicknames.untag("[AFK] "), "nothing left: no nickname");
        assertFalse(AfkNicknames.isTagged(null));
    }

    @Test
    void theAwayStatusSettingsRoundTripAndDefaultToOff() {
        assertFalse(HubService.parseAfk("{}").nicknameTag());
        assertFalse(HubService.parseAfk(null).nicknameTag());
        assertFalse(HubService.parseAfk("not json").nicknameTag());
        var saved = HubService.parseAfk(HubService.writeAfk(new HubService.AfkPolicy(true, List.of(5L, 6L))));
        assertTrue(saved.nicknameTag());
        assertEquals(List.of(5L, 6L), saved.ignoreChannelIds());
    }
}
