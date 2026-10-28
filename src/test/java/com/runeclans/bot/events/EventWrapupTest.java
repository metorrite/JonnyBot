package com.runeclans.bot.events;

import com.runeclans.bot.events.EventSpace.Grant;
import com.runeclans.bot.events.EventSpace.Kind;
import com.runeclans.bot.events.EventSpace.Level;
import com.runeclans.bot.events.EventSpace.Who;
import com.runeclans.bot.events.EventWrapup.ChannelsAction;
import com.runeclans.bot.events.EventWrapup.Msg;
import com.runeclans.bot.events.EventWrapup.Setting;
import com.runeclans.bot.events.EventWrapup.State;
import com.runeclans.bot.events.EventWrapup.Step;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure rules of wrapping an event up: the settings, which steps are due when, what locked and archived channels allow, and the tidy transcripts. */
class EventWrapupTest {
    private static final Instant END = Instant.parse("2026-10-20T12:00:00Z");

    // ---------- settings ----------

    @Test
    void anEventWithNoWrapupSettingsLocksItsChannelsAndTakesItsRolesBackAfterAWeek() {
        Setting s = EventWrapup.parse(DataObject.fromJson("{}"));
        assertTrue(s.lockOnFinish());
        assertEquals(7, s.rolesDays());
        assertEquals(-1, s.channelsDays(), "the channels wait for an organiser");
        assertEquals(ChannelsAction.ARCHIVE, s.channelsAction());
        assertNull(s.archiveCategoryId());
        assertTrue(EventWrapup.problems(DataObject.fromJson("{}")).isEmpty());
    }

    @Test
    void settingsAreReadAndWrittenBack() {
        Setting s = EventWrapup.parse(DataObject.fromJson("{\"wrapup\":{\"lockOnFinish\":false,\"rolesDays\":0,\"channelsDays\":30,\"channelsAction\":\"delete\",\"archiveCategoryId\":\"123\"}}"));
        assertFalse(s.lockOnFinish());
        assertEquals(0, s.rolesDays());
        assertEquals(30, s.channelsDays());
        assertEquals(ChannelsAction.DELETE, s.channelsAction());
        assertEquals(123L, s.archiveCategoryId());
        DataObject json = EventWrapup.toJson(s);
        assertEquals(30, json.getInt("channelsDays"));
        assertEquals("123", json.getString("archiveCategoryId"));
        assertEquals(s, EventWrapup.parse(DataObject.empty().put("wrapup", json)));
    }

    @Test
    void settingsThatDontMakeSenseAreReportedAndIgnored() {
        DataObject bad = DataObject.fromJson("{\"wrapup\":{\"rolesDays\":-5,\"channelsDays\":99999,\"channelsAction\":\"burn\",\"archiveCategoryId\":\"abc\",\"lockOnFinish\":\"yes\"}}");
        assertEquals(5, EventWrapup.problems(bad).size(), EventWrapup.problems(bad).toString());
        assertEquals(Setting.DEFAULT, EventWrapup.parse(bad), "what can't be read stays at its default");
        assertTrue(EventWrapup.problems(DataObject.fromJson("{\"wrapup\":{\"rolesDays\":\"3\"}}")).isEmpty(), "a number sent as text is fine");
    }

    // ---------- what is due ----------

    @Test
    void nothingIsDueBeforeTheEventHasFinishedAndItsEndIsNoted() {
        State noted = new State(END, null, null, null);
        assertTrue(EventWrapup.due(Setting.DEFAULT, EventRules.Status.RUNNING, true, true, noted, END.plus(Duration.ofDays(30))).isEmpty());
        assertTrue(EventWrapup.due(Setting.DEFAULT, EventRules.Status.DONE, true, true, State.NONE, END.plus(Duration.ofDays(30))).isEmpty(), "the end isn't noted yet");
    }

    @Test
    void byDefaultTheChannelsLockAtOnceAndTheRolesGoAfterSevenDays() {
        State state = new State(END, null, null, null);
        assertEquals(List.of(Step.LOCK), EventWrapup.due(Setting.DEFAULT, EventRules.Status.DONE, true, true, state, END));
        assertEquals(List.of(Step.LOCK), EventWrapup.due(Setting.DEFAULT, EventRules.Status.DONE, true, true, state, END.plus(Duration.ofDays(6))));
        assertEquals(List.of(Step.LOCK, Step.ROLES), EventWrapup.due(Setting.DEFAULT, EventRules.Status.DONE, true, true, state, END.plus(Duration.ofDays(7))));
        assertEquals(List.of(Step.ROLES), EventWrapup.due(Setting.DEFAULT, EventRules.Status.CANCELLED, true, true, new State(END, END, null, null), END.plus(Duration.ofDays(8))));
        assertTrue(EventWrapup.due(Setting.DEFAULT, EventRules.Status.DONE, true, true, new State(END, END, END, null), END.plus(Duration.ofDays(400))).isEmpty(), "channels never happen by themselves");
    }

    @Test
    void theChannelsStepRunsOnlyWhenADaysCountAndAnActionAreSet() {
        Setting archive = new Setting(true, 7, 14, ChannelsAction.ARCHIVE, null);
        State state = new State(END, END, END, null);
        assertTrue(EventWrapup.due(archive, EventRules.Status.DONE, true, false, state, END.plus(Duration.ofDays(13))).isEmpty());
        assertEquals(List.of(Step.CHANNELS), EventWrapup.due(archive, EventRules.Status.DONE, true, false, state, END.plus(Duration.ofDays(14))));
        assertTrue(EventWrapup.due(new Setting(true, 7, 14, ChannelsAction.NONE, null), EventRules.Status.DONE, true, false, state, END.plus(Duration.ofDays(99))).isEmpty());
        assertTrue(EventWrapup.due(archive, EventRules.Status.DONE, true, false, new State(END, END, END, END), END.plus(Duration.ofDays(99))).isEmpty(), "once only");
    }

    @Test
    void aStepWithNothingToActOnIsNotDueAndTurningLockingOffSkipsIt() {
        State state = new State(END, null, null, null);
        assertTrue(EventWrapup.due(Setting.DEFAULT, EventRules.Status.DONE, false, false, state, END.plus(Duration.ofDays(30))).isEmpty());
        Setting noLock = new Setting(false, 0, -1, ChannelsAction.ARCHIVE, null);
        assertEquals(List.of(Step.ROLES), EventWrapup.due(noLock, EventRules.Status.DONE, true, true, state, END));
        assertTrue(EventWrapup.due(new Setting(true, -1, -1, ChannelsAction.ARCHIVE, null), EventRules.Status.DONE, false, true, state, END.plus(Duration.ofDays(900))).isEmpty(), "-1 keeps the roles");
    }

    @Test
    void theScheduleSaysWhenEachStepWillHappenOrNullWhenItWont() {
        State state = new State(END, END, null, null);
        assertNull(EventWrapup.dueAt(Setting.DEFAULT, state, Step.LOCK), "already done");
        assertEquals(END.plus(Duration.ofDays(7)), EventWrapup.dueAt(Setting.DEFAULT, state, Step.ROLES));
        assertNull(EventWrapup.dueAt(Setting.DEFAULT, state, Step.CHANNELS), "never by itself");
        assertNull(EventWrapup.dueAt(Setting.DEFAULT, State.NONE, Step.ROLES), "not finished yet");
    }

    // ---------- access ----------

    @Test
    void lockingTurnsChattingIntoReadingForPlayersAndLeavesStaffAlone() {
        for (Kind kind : new Kind[]{Kind.GENERAL, Kind.TEAM, Kind.PLAYER}) {
            List<Grant> locked = EventWrapup.lockedAccess(EventSpace.access(kind, 7));
            assertTrue(locked.stream().noneMatch(g -> g.level() == Level.CHAT), kind + " has nobody chatting any more");
            assertTrue(locked.stream().anyMatch(g -> g.level() == Level.READ), kind + " can still be read");
            assertTrue(locked.stream().anyMatch(g -> g.who().who() == Who.STAFF && g.level() == Level.MANAGE), "staff keep managing");
            assertTrue(locked.stream().anyMatch(g -> g.who().who() == Who.BOT && g.level() == Level.BOT), "the bot keeps its access");
        }
        assertEquals(EventSpace.access(Kind.ANNOUNCEMENTS, 0), EventWrapup.lockedAccess(EventSpace.access(Kind.ANNOUNCEMENTS, 0)), "announcements were read-only already");
    }

    @Test
    void anArchivedChannelIsHiddenFromEveryoneButStaffOrganisersAndTheBot() {
        List<Grant> archived = EventWrapup.archivedAccess();
        assertEquals(Level.HIDDEN, archived.stream().filter(g -> g.who().who() == Who.EVERYONE).findFirst().orElseThrow().level());
        assertTrue(archived.stream().noneMatch(g -> g.who().who() == Who.PARTICIPANTS || g.who().who() == Who.TEAM || g.who().who() == Who.PLAYER));
        assertEquals(Level.MANAGE, archived.stream().filter(g -> g.who().who() == Who.STAFF).findFirst().orElseThrow().level());
        assertEquals(Level.READ, archived.stream().filter(g -> g.who().who() == Who.ORGANISERS).findFirst().orElseThrow().level());
    }

    // ---------- transcripts ----------

    private static Msg msg(long id, String channel, boolean bot, String content, int minute) {
        return new Msg(id, channel, bot ? 1 : 2, bot ? "JonnyBot" : "Sam", bot, END.plus(Duration.ofMinutes(minute)), content, List.of());
    }

    @Test
    void aSubmissionAndItsReceiptCountOnceAndTheReviewMessageWins() {
        Msg review = msg(10, "review", true, "**Bingo · submission #12** from @Sam\nDragon pickaxe\n-# Approve or deny it with the buttons\n✅ Approved by @Staff", 1);
        Msg receipt = msg(11, "player-sam", true, "**Bingo · submission #12** from @Sam\nDragon pickaxe\n-# ✅ Approved", 1);
        Msg chat = msg(12, "player-sam", false, "thanks!", 2);
        EventWrapup.Dedupe d = EventWrapup.dedupe(List.of(receipt, chat, review), Set.of("review"));
        assertEquals(1, d.dropped());
        assertEquals(List.of(12L, 10L), d.messages().stream().map(Msg::id).toList(), "the receipt is gone, people's messages and the review message stay in order");
    }

    @Test
    void whenTheKeptMessageHasNoDecisionItGetsTheOneFromTheDuplicate() {
        Msg review = msg(10, "review", true, "**Bingo · submission #12** from @Sam\n-# Approve or deny it with the buttons", 1);
        Msg receipt = msg(11, "player-sam", true, "**Bingo · submission #12** from @Sam\n-# ❌ Not accepted: blurry", 1);
        EventWrapup.Dedupe d = EventWrapup.dedupe(List.of(review, receipt), Set.of("review"));
        assertEquals(1, d.messages().size());
        assertTrue(d.messages().get(0).content().endsWith("-# ❌ Not accepted: blurry"), d.messages().get(0).content());
    }

    @Test
    void withoutAPreferredChannelTheEarliestIsKeptAndDifferentSubmissionsAreNotMixedUp() {
        Msg a1 = msg(1, "x", true, "submission #1 here", 1);
        Msg a2 = msg(2, "y", true, "submission #1 again", 2);
        Msg b = msg(3, "y", true, "submission #10 is another", 3);
        EventWrapup.Dedupe d = EventWrapup.dedupe(List.of(a1, a2, b), Set.of());
        assertEquals(List.of(1L, 3L), d.messages().stream().map(Msg::id).toList());
        assertEquals(1, d.dropped());
        assertEquals(0, EventWrapup.dedupe(List.of(msg(1, "x", false, "submission #1 by a person", 1), msg(2, "y", false, "submission #1 by a person", 2)), Set.of()).dropped(), "people's messages are never folded");
    }

    @Test
    void aTranscriptListsEveryMessageWithTimeAuthorAndAttachments() {
        Msg withFile = new Msg(1, "player-sam", 2, "Sam", false, END, "look", List.of("https://cdn.discordapp.com/a.png"));
        String text = EventWrapup.transcript("#player-sam", List.of(withFile, msg(2, "player-sam", true, "ok", 5)));
        assertTrue(text.startsWith("# #player-sam\n2 messages"), text);
        assertTrue(text.contains("[2026-10-20 12:00] Sam: look"), text);
        assertTrue(text.contains("    attachment: https://cdn.discordapp.com/a.png"), text);
        assertTrue(text.contains("[2026-10-20 12:05] JonnyBot (bot): ok"), text);
    }

    @Test
    void namesAreSafeInAZip() {
        assertEquals("christmas-bingo-general", EventWrapup.safeName("Christmas Bingo · General!"));
        assertEquals("channel", EventWrapup.safeName("!!!"));
        assertEquals("channel", EventWrapup.safeName(null));
        assertTrue(EventWrapup.safeName("x".repeat(200)).length() <= 60);
    }

    // ---------- T-212: unlocking a reopened event, and the spreadsheet files ----------

    @Test
    void unlockOnReopenIsOnUnlessSwitchedOffAndSurvivesSaving() {
        assertTrue(Setting.DEFAULT.unlockOnReopen());
        assertTrue(EventWrapup.parse(DataObject.fromJson("{\"wrapup\":{\"lockOnFinish\":true}}")).unlockOnReopen(), "older settings without it are on");
        Setting off = EventWrapup.parse(DataObject.fromJson("{\"wrapup\":{\"unlockOnReopen\":false}}"));
        assertFalse(off.unlockOnReopen());
        assertFalse(EventWrapup.parse(DataObject.empty().put("wrapup", EventWrapup.toJson(off))).unlockOnReopen());
        assertFalse(EventWrapup.problems(DataObject.fromJson("{\"wrapup\":{\"unlockOnReopen\":\"yes\"}}")).isEmpty());
    }

    @Test
    void onlyLockedChannelsOfAReopenedEventAreUnlocked() {
        State locked = new State(END, END.plusSeconds(60), null, null);
        State notLocked = new State(END, null, null, null);
        assertTrue(EventWrapup.unlocksOnReopen(Setting.DEFAULT, EventRules.Status.RUNNING, locked));
        assertTrue(EventWrapup.unlocksOnReopen(Setting.DEFAULT, EventRules.Status.SCORING, locked));
        assertFalse(EventWrapup.unlocksOnReopen(Setting.DEFAULT, EventRules.Status.DONE, locked), "still over");
        assertFalse(EventWrapup.unlocksOnReopen(Setting.DEFAULT, EventRules.Status.RUNNING, notLocked), "nothing was locked");
        assertFalse(EventWrapup.unlocksOnReopen(new Setting(true, 7, -1, ChannelsAction.ARCHIVE, null, false), EventRules.Status.RUNNING, locked), "switched off");
    }

    @Test
    void csvQuotesWhatNeedsItAndDefusesFormulas() {
        String csv = EventWrapup.csv(List.of("name", "note"), List.of(List.of("Zezima", "plain"), List.of("Smith, John", "said \"hi\""), List.of("=HYPERLINK(1)", "-5"), List.of("two\nlines", "")));
        assertEquals("name,note\r\nZezima,plain\r\n\"Smith, John\",\"said \"\"hi\"\"\"\r\n'=HYPERLINK(1),-5\r\n\"two\nlines\",\r\n", csv);
        assertEquals("", EventWrapup.cell(null));
        assertEquals("'@everyone", EventWrapup.cell("@everyone"));
    }
}
