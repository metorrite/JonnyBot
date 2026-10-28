package com.younglings.bot.internal;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.younglings.bot.internal.RecapGate.Decision;
import com.younglings.bot.internal.RecapGate.Outcome;
import com.younglings.bot.member.MemberProfileRepository;
import com.younglings.bot.member.MemberProfileRepository.RecapOwner;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Who may see a member's recap ({@link RecapGate}): the rule on its own, then over a stand-in repository. Fake ids are built at run time. */
class RecapGateTest {
    private static final long GUILD = Long.parseLong("3".repeat(17) + "1");
    private static final long MEMBER = Long.parseLong("2".repeat(17) + "1");
    private static final long SOMEONE_ELSE = Long.parseLong("2".repeat(17) + "2");
    private static final String KEY = "month:2026-09:final";

    private static RecapOwner owner(boolean hideRecap, boolean seen) {
        return new RecapOwner(MEMBER, hideRecap, seen);
    }

    @Test
    void theMemberAlwaysSeesTheirOwnRecap() {
        assertEquals(new Decision(Outcome.SUBJECT, true), RecapGate.decide(false, MEMBER, owner(false, false), KEY), "before opening it: now shared");
        assertEquals(new Decision(Outcome.SUBJECT, true), RecapGate.decide(false, MEMBER, owner(false, true), KEY));
        assertEquals(new Decision(Outcome.SUBJECT, false), RecapGate.decide(false, MEMBER, owner(true, true), KEY), "turned off: only they see it");
        assertEquals(new Decision(Outcome.SUBJECT, false), RecapGate.decide(false, MEMBER, owner(false, false), null), "no key: only ever theirs");
    }

    @Test
    void anyoneElseSeesItOnceTheMemberOpenedIt() {
        for (Long viewer : java.util.Arrays.asList(SOMEONE_ELSE, null)) {
            assertEquals(Outcome.SHOWN, RecapGate.decide(false, viewer, owner(false, true), KEY).outcome(), "opened, not hidden: " + viewer);
            assertEquals(Outcome.PRIVATE, RecapGate.decide(false, viewer, owner(false, false), KEY).outcome(), "not opened yet: no spoilers");
            assertEquals(Outcome.PRIVATE, RecapGate.decide(false, viewer, owner(true, true), KEY).outcome(), "the member turned recaps off");
            assertEquals(Outcome.PRIVATE, RecapGate.decide(false, viewer, owner(false, true), null).outcome(), "a period with no key is never shared");
        }
        assertFalse(RecapGate.decide(false, SOMEONE_ELSE, owner(false, true), KEY).sharedWithClan(), "sharedWithClan is only for the member");
    }

    @Test
    void anUnlinkedNameOrABotlessSiteIsPrivateForEveryone() {
        for (Long viewer : java.util.Arrays.asList(MEMBER, SOMEONE_ELSE, null)) {
            assertEquals(Outcome.PRIVATE, RecapGate.decide(false, viewer, null, KEY).outcome(), "nobody linked the name");
            assertEquals(Outcome.PRIVATE, RecapGate.decide(true, viewer, owner(false, true), KEY).outcome(), "bot-less: nobody can be the member");
        }
    }

    @Test
    void theViewerIsDigitsOnlyOrNobody() {
        assertEquals(MEMBER, RecapGate.viewer(Long.toString(MEMBER)));
        assertEquals(MEMBER, RecapGate.viewer(" " + MEMBER + " "));
        for (String raw : java.util.Arrays.asList(null, "", " ", "abc", "12a", "-5", "1".repeat(21), "9".repeat(20), MEMBER + "," + SOMEONE_ELSE)) {
            assertNull(RecapGate.viewer(raw), "anonymous: " + raw);
        }
        HttpExchange exchange = mock(HttpExchange.class);
        assertNull(RecapGate.viewer(exchange), "no headers at all");
        Headers headers = new Headers();
        headers.add("x-rc-viewer", Long.toString(MEMBER));
        when(exchange.getRequestHeaders()).thenReturn(headers);
        assertEquals(MEMBER, RecapGate.viewer(exchange), "header names are compared without case");
    }

    // ---------- over the repository ----------

    private final MemberProfileRepository profiles = mock(MemberProfileRepository.class);
    private final RecapGate gate = new RecapGate(profiles);

    @Test
    void theMembersFirstViewIsRecordedOnceAndNobodyElsesIs() {
        when(profiles.recapOwner(GUILD, "Someone", KEY)).thenReturn(owner(false, false));
        assertEquals(Outcome.PRIVATE, gate.check(GUILD, false, "Someone", KEY, SOMEONE_ELSE).outcome());
        assertEquals(Outcome.PRIVATE, gate.check(GUILD, false, "Someone", KEY, null).outcome());
        verify(profiles, never()).markRecapSeen(anyLong(), anyString(), anyString(), anyLong());

        assertEquals(new Decision(Outcome.SUBJECT, true), gate.check(GUILD, false, "Someone", KEY, MEMBER));
        verify(profiles).markRecapSeen(GUILD, "Someone", KEY, MEMBER);

        when(profiles.recapOwner(GUILD, "Someone", KEY)).thenReturn(owner(false, true));
        gate.check(GUILD, false, "Someone", KEY, MEMBER);
        verify(profiles).markRecapSeen(GUILD, "Someone", KEY, MEMBER); // still once: already marked

        when(profiles.recapOwner(GUILD, "Someone", null)).thenReturn(owner(false, false));
        gate.check(GUILD, false, "Someone", null, MEMBER);
        verify(profiles, never()).markRecapSeen(anyLong(), anyString(), isNull(), anyLong());
    }

    @Test
    void aBotlessSiteIsNeverAskedAndAFailedMarkKeepsTheRecapTheMembersOwn() {
        assertEquals(Outcome.PRIVATE, gate.check(GUILD, true, "Someone", KEY, MEMBER).outcome());
        verify(profiles, never()).recapOwner(anyLong(), any(), any());

        when(profiles.recapOwner(GUILD, "Someone", KEY)).thenReturn(owner(false, false));
        doThrow(new RuntimeException("db down")).when(profiles).markRecapSeen(anyLong(), anyString(), anyString(), anyLong());
        assertEquals(new Decision(Outcome.SUBJECT, false), gate.check(GUILD, false, "Someone", KEY, MEMBER), "shown to them, not yet shared");
    }

    /** A member recap answer as RecapService builds it, for last month as of 6 October 2026. */
    private static DataObject recap() {
        return DataObject.empty().put("scope", "member")
                .put("period", DataObject.empty().put("token", "last-month").put("label", "September 2026").put("from", "2026-09-01T00:00Z")
                        .put("to", "2026-10-01T00:00Z").put("toDate", false).put("days", 30))
                .put("subject", DataObject.empty().put("name", "Someone").put("rank", "Recruit"))
                .put("xp", DataObject.empty().put("total", 1234));
    }

    @Test
    void theAnswerIsACopyForTheMemberTheSameForAnAllowedViewerAndOnlyPrivateOtherwise() {
        DataObject cached = recap();
        String before = cached.toString();
        when(profiles.recapOwner(GUILD, "Someone", KEY)).thenReturn(owner(false, false));

        assertEquals(RecapGate.privateAnswer().toString(), gate.answer(GUILD, false, cached, null).toString());
        assertEquals(List.of("private"), List.copyOf(gate.answer(GUILD, false, cached, SOMEONE_ELSE).keys()), "nothing else, not even why");

        DataObject mine = gate.answer(GUILD, false, cached, MEMBER);
        assertTrue(mine.getBoolean("you"));
        assertTrue(mine.getBoolean("sharedWithClan"));
        assertEquals(1234, mine.getObject("xp").getLong("total"));
        assertEquals(before, cached.toString(), "the cached answer everyone shares is never changed");

        when(profiles.recapOwner(GUILD, "Someone", KEY)).thenReturn(owner(false, true));
        DataObject shown = gate.answer(GUILD, false, cached, null);
        assertSame(cached, shown);
        assertFalse(shown.hasKey("you"));

        assertNull(gate.answer(GUILD, false, null, MEMBER), "no such member or period stays a 404");
    }

    @Test
    void theKeyComesFromTheAnswersOwnPeriod() {
        assertEquals(KEY, RecapGate.periodKey(recap()));
        assertNull(RecapGate.periodKey(DataObject.empty()));
        assertNull(RecapGate.periodKey(DataObject.empty().put("period", DataObject.empty().put("token", "last-month").put("from", "not a date"))));
        DataObject custom = recap();
        custom.getObject("period").put("token", "2026-09-01_2026-09-20").put("to", "2026-09-21T00:00Z");
        assertNull(RecapGate.periodKey(custom), "a custom range has no key");
    }
}
