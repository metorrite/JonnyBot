package com.younglings.bot.tempvoice;

import com.younglings.bot.tempvoice.TempVoiceRules.Action;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TempVoiceRulesTest {
    private static final long OWNER = 10;
    private static final long OTHER = 20;

    // ---------- names

    @Test
    void theNameFillsInTheUserAndFallsBackToTheDefault() {
        assertEquals("Zezima's channel", TempVoiceRules.channelName("{user}'s channel", "Zezima"));
        assertEquals("Zezima's channel", TempVoiceRules.channelName("", "Zezima"));
        assertEquals("Raid night", TempVoiceRules.channelName("Raid night", "Zezima"));
        assertEquals("someone's channel", TempVoiceRules.channelName(null, null));
    }

    @Test
    void aNameIsCleanedOfMentionsControlCharactersAndExtraSpaces() {
        assertEquals("everyone here", TempVoiceRules.cleanName("@everyone   here"));
        assertEquals("ab", TempVoiceRules.cleanName("a\u0000​b"));
        assertEquals("Voice channel", TempVoiceRules.cleanName("   "));
        assertEquals("Voice channel", TempVoiceRules.cleanName(null));
        assertEquals("general", TempVoiceRules.cleanName("#general"));
    }

    @Test
    void aNameIsCutToDiscordsHundredCharacters() {
        assertEquals(100, TempVoiceRules.cleanName("x".repeat(250)).length());
        assertEquals(100, TempVoiceRules.channelName("{user}", "y".repeat(300)).length());
    }

    @Test
    void hubSettingsAreChecked() {
        assertTrue(TempVoiceRules.hubProblems("{user}'s channel", 0).isEmpty());
        assertTrue(TempVoiceRules.hubProblems("Lounge", 99).isEmpty());
        assertFalse(TempVoiceRules.hubProblems("", 0).isEmpty());
        assertFalse(TempVoiceRules.hubProblems("x".repeat(101), 0).isEmpty());
        assertFalse(TempVoiceRules.hubProblems("Lounge", 100).isEmpty());
        assertFalse(TempVoiceRules.hubProblems("Lounge", -1).isEmpty());
        assertFalse(TempVoiceRules.hubProblems("@@@", 0).isEmpty(), "nothing left after cleaning");
    }

    @Test
    void aTypedLimitIsAWholeNumberUpToNinetyNine() {
        assertEquals(0, TempVoiceRules.parseLimit("0"));
        assertEquals(5, TempVoiceRules.parseLimit(" 5 "));
        assertEquals(99, TempVoiceRules.parseLimit("99"));
        assertEquals(-1, TempVoiceRules.parseLimit("100"));
        assertEquals(-1, TempVoiceRules.parseLimit("-3"));
        assertEquals(-1, TempVoiceRules.parseLimit("ten"));
        assertEquals(-1, TempVoiceRules.parseLimit(""));
        assertEquals(-1, TempVoiceRules.parseLimit(null));
    }

    // ---------- when channels are made and deleted

    @Test
    void aChannelIsDeletedOnlyWhenItIsTemporaryAndEmpty() {
        assertTrue(TempVoiceRules.deleteWhenEmpty(true, 0));
        assertFalse(TempVoiceRules.deleteWhenEmpty(true, 1));
        assertFalse(TempVoiceRules.deleteWhenEmpty(false, 0), "an ordinary channel is never deleted");
    }

    @Test
    void aMemberGetsANewChannelOnlyWhenTheyHaveNoneAndTheServerHasRoomAndTheyAreNotHammering() {
        long now = 1_000_000;
        assertTrue(TempVoiceRules.shouldCreate(false, 0, now, null));
        assertFalse(TempVoiceRules.shouldCreate(true, 0, now, null), "they already have one");
        assertFalse(TempVoiceRules.shouldCreate(false, TempVoiceRules.MAX_TEMP_CHANNELS_PER_GUILD, now, null), "the server is at its cap");
        assertTrue(TempVoiceRules.shouldCreate(false, TempVoiceRules.MAX_TEMP_CHANNELS_PER_GUILD - 1, now, null));
        assertFalse(TempVoiceRules.shouldCreate(false, 0, now, now - 2000L), "two seconds after the last one");
        assertTrue(TempVoiceRules.shouldCreate(false, 0, now, now - TempVoiceRules.CREATE_COOLDOWN_SECONDS * 1000L));
    }

    // ---------- who may use the controls

    @Test
    void onlyTheOwnerUsesTheControls() {
        for (Action a : new Action[]{Action.RENAME, Action.LIMIT, Action.LOCK, Action.UNLOCK, Action.PERMIT, Action.KICK, Action.TRANSFER}) {
            assertTrue(TempVoiceRules.allowed(a, OWNER, OWNER, true, true), a.name());
            assertFalse(TempVoiceRules.allowed(a, OWNER, OTHER, true, true), a.name() + " by someone else in the channel");
            assertFalse(TempVoiceRules.allowed(a, OWNER, OTHER, false, true), a.name() + " even when the owner has left");
            assertNotNull(TempVoiceRules.refusal(a, OWNER, OTHER, true, true));
            assertNull(TempVoiceRules.refusal(a, OWNER, OWNER, true, true));
        }
    }

    @Test
    void claimNeedsAMemberInsideAndTheOwnerGone() {
        assertTrue(TempVoiceRules.allowed(Action.CLAIM, OWNER, OTHER, false, true));
        assertFalse(TempVoiceRules.allowed(Action.CLAIM, OWNER, OTHER, true, true), "the owner is still there");
        assertFalse(TempVoiceRules.allowed(Action.CLAIM, OWNER, OTHER, false, false), "not in the channel");
        assertFalse(TempVoiceRules.allowed(Action.CLAIM, OWNER, OWNER, false, true), "already the owner");
        assertEquals("The owner is still here, so it can't be claimed.", TempVoiceRules.refusal(Action.CLAIM, OWNER, OTHER, true, true));
        assertEquals("Join the channel first to claim it.", TempVoiceRules.refusal(Action.CLAIM, OWNER, OTHER, false, false));
        assertEquals("You already own this channel.", TempVoiceRules.refusal(Action.CLAIM, OWNER, OWNER, false, true));
    }

    @Test
    void aTargetIsAnotherRealMember() {
        assertTrue(TempVoiceRules.validTarget(OWNER, OTHER, false));
        assertFalse(TempVoiceRules.validTarget(OWNER, OWNER, false));
        assertFalse(TempVoiceRules.validTarget(OWNER, OTHER, true));
    }

    // ---------- the panel's ids and message

    @Test
    void controlIdsAreParsedStrictly() {
        assertEquals("rename", TempVoiceRules.parseId("tv:rename:123")[1]);
        assertEquals("123", TempVoiceRules.parseId("tv:rename:123")[2]);
        assertNull(TempVoiceRules.parseId("rr:1:2"));
        assertNull(TempVoiceRules.parseId("tv:rename"));
        assertNull(TempVoiceRules.parseId("tv:rename:abc"));
        assertNull(TempVoiceRules.parseId("tv:rename:1:2"));
        assertNull(TempVoiceRules.parseId(null));
        assertEquals(Action.CLAIM, TempVoiceRules.action("claim"));
        assertNull(TempVoiceRules.action("explode"));
    }

    @Test
    void thePanelHasAllEightControlsPointingAtTheChannel() {
        MessageCreateData panel = TempVoiceService.panel(555, OWNER).build();
        var buttons = panel.getComponents().stream().flatMap(c -> c.asActionRow().getComponents().stream()).map(c -> c.asButton().getCustomId()).toList();
        assertEquals(8, buttons.size());
        for (String name : new String[]{"rename", "limit", "lock", "unlock", "permit", "kick", "transfer", "claim"}) assertTrue(buttons.contains("tv:" + name + ":555"), name);
    }
}
