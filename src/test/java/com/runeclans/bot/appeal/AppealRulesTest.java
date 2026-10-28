package com.runeclans.bot.appeal;

import com.runeclans.bot.appeal.AppealRules.Appeal;
import com.runeclans.bot.appeal.AppealRules.Status;
import com.runeclans.bot.moderation.ModAction;
import com.runeclans.bot.moderation.ModCase;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which cases can be appealed and when (T-033). */
class AppealRulesTest {
    private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");

    private static ModCase c(long no, ModAction action, Integer minutes, Instant at) {
        return new ModCase(1, no, 7, "someone", 9, "mod", action, "reason " + no, minutes, ModCase.COMMAND, at, null, null, null);
    }

    private static List<Long> nos(List<ModCase> cases) {
        return cases.stream().map(ModCase::caseNo).toList();
    }

    @Test
    void theLatestBanCanBeAppealedUntilAnUnban() {
        List<ModCase> banned = List.of(c(5, ModAction.BAN, null, NOW.minusSeconds(60)), c(4, ModAction.UNBAN, null, NOW.minusSeconds(600)), c(3, ModAction.BAN, null, NOW.minusSeconds(900)));
        assertEquals(List.of(5L), nos(AppealRules.appealable(banned, NOW)), "only the latest ban, the older one was lifted");
        List<ModCase> lifted = List.of(c(6, ModAction.UNBAN, null, NOW), c(5, ModAction.BAN, null, NOW.minusSeconds(60)));
        assertTrue(AppealRules.appealable(lifted, NOW).isEmpty());
    }

    @Test
    void aTimeoutCanBeAppealedOnlyWhileItRuns() {
        assertEquals(List.of(2L), nos(AppealRules.appealable(List.of(c(2, ModAction.TIMEOUT, 60, NOW.minusSeconds(600))), NOW)));
        assertTrue(AppealRules.appealable(List.of(c(2, ModAction.TIMEOUT, 5, NOW.minusSeconds(600))), NOW).isEmpty(), "over");
        assertTrue(AppealRules.appealable(List.of(c(3, ModAction.UNTIMEOUT, null, NOW), c(2, ModAction.TIMEOUT, 60, NOW.minusSeconds(600))), NOW).isEmpty(), "lifted");
        assertTrue(AppealRules.appealable(List.of(c(2, ModAction.WARN, null, NOW), c(1, ModAction.KICK, null, NOW)), NOW).isEmpty(), "warnings and kicks aren't appealed");
    }

    private static Appeal appeal(Status status, Instant decided) {
        return new Appeal(1, 1, 5, 7, "someone", "text", status, null, null, NOW.minusSeconds(86_400 * 10L), status == Status.OPEN ? null : 9L, decided, "no");
    }

    @Test
    void oneOpenAppealPerCaseAndACooldownAfterARejection() {
        assertNull(AppealRules.refusal(List.of(), 7, NOW));
        assertTrue(AppealRules.refusal(List.of(appeal(Status.OPEN, null)), 7, NOW).contains("already have an appeal"));
        String cooling = AppealRules.refusal(List.of(appeal(Status.REJECTED, NOW.minus(Duration.ofDays(2)))), 7, NOW);
        assertTrue(cooling.contains(NOW.plus(Duration.ofDays(5)).toString()), cooling);
        assertNull(AppealRules.refusal(List.of(appeal(Status.REJECTED, NOW.minus(Duration.ofDays(8)))), 7, NOW), "the cooldown has passed");
        assertNull(AppealRules.refusal(List.of(appeal(Status.REJECTED, NOW.minus(Duration.ofDays(2)))), 0, NOW), "a server can turn the cooldown off");
    }

    @Test
    void theTextNeedsSubstanceAndTheLiftMatchesTheCase() {
        assertTrue(AppealRules.textProblem("sorry").contains("at least"));
        assertNull(AppealRules.textProblem("I was banned by mistake, it was my brother on my account."));
        assertTrue(AppealRules.textProblem("x".repeat(AppealRules.MAX_TEXT + 1)).contains("fewer"));
        assertEquals(ModAction.UNBAN, AppealRules.liftOf(ModAction.BAN));
        assertEquals(ModAction.UNTIMEOUT, AppealRules.liftOf(ModAction.TIMEOUT));
        assertNull(AppealRules.liftOf(ModAction.KICK));
        assertEquals("https://site.example/jonnybot/appeal/42", AppealLinks.linkFrom("https://site.example", 42));
        assertNull(AppealLinks.linkFrom(null, 42));
    }
}
