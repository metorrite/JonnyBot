package com.younglings.bot.ban;

import com.younglings.bot.ban.AnnouncePolicy.Ping;
import com.younglings.bot.ban.AnnouncePolicy.Plan;
import net.dv8tion.jda.api.entities.Message;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The /announce mention rules, with no Discord in sight: nothing here can post or ping. */
class AnnouncePolicyTest {
    @Test
    void everyoneAndHereAreRefusedUnlessTheSenderMayMentionEveryone() {
        Plan everyone = AnnouncePolicy.plan("Server restart tonight", Ping.EVERYONE, null, false, false);
        assertFalse(everyone.ok());
        assertTrue(everyone.refusal().contains("@everyone"));
        assertNull(everyone.content());
        assertTrue(everyone.parse().isEmpty());

        Plan here = AnnouncePolicy.plan("Server restart tonight", Ping.HERE, null, false, false);
        assertFalse(here.ok());
        assertTrue(here.refusal().contains("@here"));
    }

    @Test
    void everyoneIsAllowedForASenderWhoMayMentionEveryone() {
        Plan plan = AnnouncePolicy.plan("Server restart tonight", Ping.EVERYONE, null, false, true);
        assertTrue(plan.ok());
        assertEquals("@everyone Server restart tonight", plan.content());
        assertEquals(EnumSet.of(Message.MentionType.EVERYONE), plan.parse());
        assertTrue(plan.roleIds().isEmpty());

        assertEquals("@here Hello", AnnouncePolicy.plan("Hello", Ping.HERE, null, false, true).content());
    }

    @Test
    void aRoleNeedsToBeMentionableOrTheSenderMayMentionEveryone() {
        assertFalse(AnnouncePolicy.plan("Raid at 8", Ping.ROLE, 55L, false, false).ok());
        assertTrue(AnnouncePolicy.plan("Raid at 8", Ping.ROLE, 55L, true, false).ok());
        Plan viaPermission = AnnouncePolicy.plan("Raid at 8", Ping.ROLE, 55L, false, true);
        assertTrue(viaPermission.ok());
        assertEquals("<@&55> Raid at 8", viaPermission.content());
        assertEquals(List.of(55L), viaPermission.roleIds());
        assertTrue(viaPermission.parse().isEmpty(), "only the chosen role may be pinged, never everyone");
        assertFalse(AnnouncePolicy.plan("Raid", Ping.ROLE, null, true, true).ok(), "a role ping needs a role");
    }

    @Test
    void noPingMeansNobodyIsPingedWhateverTheBodySays() {
        Plan plan = AnnouncePolicy.plan("Hello @everyone and @here, and <@&99>", Ping.NONE, null, false, true);
        assertTrue(plan.ok());
        assertTrue(plan.parse().isEmpty());
        assertTrue(plan.roleIds().isEmpty());
        assertFalse(plan.content().contains("@everyone"), "an @everyone typed in the body is defused");
        assertFalse(plan.content().contains("@here"));
    }

    @Test
    void aBodyCannotSmuggleAnotherPingInBehindTheChosenOne() {
        Plan plan = AnnouncePolicy.plan("Heads up @everyone", Ping.HERE, null, false, true);
        assertTrue(plan.ok());
        assertTrue(plan.content().startsWith("@here "));
        assertFalse(plan.content().substring("@here ".length()).contains("@everyone"));
    }

    @Test
    void emptyAndTooLongMessagesAreRefused() {
        assertFalse(AnnouncePolicy.plan("   ", Ping.NONE, null, false, true).ok());
        assertFalse(AnnouncePolicy.plan(null, Ping.NONE, null, false, true).ok());
        assertFalse(AnnouncePolicy.plan("x".repeat(AnnouncePolicy.MAX_MESSAGE + 1), Ping.NONE, null, false, true).ok());
        assertTrue(AnnouncePolicy.plan("x".repeat(AnnouncePolicy.MAX_MESSAGE), Ping.NONE, null, false, true).ok());
    }

    @Test
    void backslashNBecomesANewLine() {
        assertEquals("one\ntwo", AnnouncePolicy.plan("one\\ntwo", Ping.NONE, null, false, false).content());
    }

    @Test
    void onlyOnePingMayBeChosen() {
        assertNull(AnnouncePolicy.choose(true, true, false));
        assertNull(AnnouncePolicy.choose(true, false, true));
        assertEquals(Ping.EVERYONE, AnnouncePolicy.choose(true, false, false));
        assertEquals(Ping.HERE, AnnouncePolicy.choose(false, true, false));
        assertEquals(Ping.ROLE, AnnouncePolicy.choose(false, false, true));
        assertEquals(Ping.NONE, AnnouncePolicy.choose(false, false, false));
        assertFalse(AnnouncePolicy.plan("Hi", AnnouncePolicy.choose(true, true, false), null, false, true).ok());
    }
}
