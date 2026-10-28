package com.runeclans.bot.starboard;

import net.dv8tion.jda.api.entities.emoji.Emoji;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StarboardChannelRulesTest {
    private static StarboardConfig config(Map<Long, Integer> rules, boolean skipHidden, Set<Long> only) {
        return new StarboardConfig(1, true, 9L, "⭐", 3, false, rules, skipHidden, only);
    }

    @Test
    void aChannelWithItsOwnNumberUsesItAndOthersUseTheServers() {
        StarboardConfig c = config(Map.of(10L, 6), false, Set.of());
        assertEquals(6, StarboardLogic.thresholdFor(c, 10, null));
        assertEquals(3, StarboardLogic.thresholdFor(c, 11, null));
    }

    @Test
    void aThreadFollowsItsParentChannelsNumberUnlessItHasItsOwn() {
        StarboardConfig c = config(Map.of(10L, 6, 55L, 2), false, Set.of());
        assertEquals(6, StarboardLogic.thresholdFor(c, 99, 10L));
        assertEquals(2, StarboardLogic.thresholdFor(c, 55, 10L));
    }

    @Test
    void hiddenChannelsAreOnlySkippedWhenTheServerAsksForIt() {
        assertTrue(StarboardLogic.sourceAllowed(config(Map.of(), false, Set.of()), 10, null, false), "today's behaviour is unchanged");
        assertFalse(StarboardLogic.sourceAllowed(config(Map.of(), true, Set.of()), 10, null, false));
        assertTrue(StarboardLogic.sourceAllowed(config(Map.of(), true, Set.of()), 10, null, true));
    }

    @Test
    void anAllowListLimitsWhereMessagesAreCopiedFrom() {
        StarboardConfig c = config(Map.of(), false, Set.of(10L));
        assertTrue(StarboardLogic.sourceAllowed(c, 10, null, true));
        assertTrue(StarboardLogic.sourceAllowed(c, 99, 10L, true), "a thread of an allowed channel");
        assertFalse(StarboardLogic.sourceAllowed(c, 11, null, true));
    }

    @Test
    void rulesAndChannelListsSurviveBeingStored() {
        Map<Long, Integer> rules = new LinkedHashMap<>();
        rules.put(10L, 6);
        rules.put(20L, 2);
        assertEquals(rules, StarboardConfig.readThresholds(StarboardConfig.writeThresholds(rules)));
        assertEquals(Set.of(1L, 2L), StarboardConfig.readIds(StarboardConfig.writeIds(Set.of(1L, 2L))));
        assertTrue(StarboardConfig.readThresholds("junk,1:x,:").isEmpty());
        assertTrue(StarboardConfig.readIds(null).isEmpty());
    }

    @Test
    void aCustomServerEmojiIsAReactionCodeJustLikeAUnicodeOne() {
        assertEquals("rune:123456789012345678", Emoji.fromFormatted("<:rune:123456789012345678>").getAsReactionCode());
        assertEquals("rune:123456789012345678", Emoji.fromFormatted("<a:rune:123456789012345678>").getAsReactionCode());
        assertEquals("⭐", Emoji.fromFormatted("⭐").getAsReactionCode());
    }
}
