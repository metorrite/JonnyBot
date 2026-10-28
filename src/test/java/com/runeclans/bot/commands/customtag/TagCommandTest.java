package com.runeclans.bot.commands.customtag;

import com.runeclans.bot.customtag.CustomTag;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TagCommandTest {
    private static CustomTag tag(boolean enabled, Set<Long> roles, int cooldown) {
        return new CustomTag(1L, 1, "rules", "text", false, "", null, CustomTag.Delivery.CHANNEL, roles, cooldown, enabled, 0);
    }

    @Test
    void aSwitchedOffTagRefusesEveryone() {
        assertNotNull(TagCommand.refusal(tag(false, Set.of(), 0), Set.of(), true, null, 0));
    }

    @Test
    void roleLimitsApplyToMembersButNotStaff() {
        CustomTag t = tag(true, Set.of(5L), 0);
        assertNotNull(TagCommand.refusal(t, Set.of(6L), false, null, 0));
        assertNull(TagCommand.refusal(t, Set.of(5L, 6L), false, null, 0));
        assertNull(TagCommand.refusal(t, Set.of(), true, null, 0), "staff may use any tag");
        assertNull(TagCommand.refusal(tag(true, Set.of(), 0), Set.of(), false, null, 0), "no limit means everyone");
    }

    @Test
    void theCooldownCountsDownAndStaffAreNotHeldUp() {
        CustomTag t = tag(true, Set.of(), 30);
        assertNull(TagCommand.refusal(t, Set.of(), false, null, 100_000), "never used");
        String why = TagCommand.refusal(t, Set.of(), false, 100_000L, 110_000);
        assertNotNull(why);
        assertTrue(why.contains("20 more seconds"), why);
        assertNull(TagCommand.refusal(t, Set.of(), false, 100_000L, 130_000), "30 seconds have passed");
        assertNull(TagCommand.refusal(t, Set.of(), true, 100_000L, 101_000), "staff skip the wait");
    }
}
