package com.younglings.bot.fun;

import com.younglings.bot.hub.CommandTag;
import com.younglings.bot.hub.HubCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FunLogicTest {
    @Test
    void diceNotationIsReadInItsUsualForms() {
        var random = new Random(1);
        var two = FunLogic.roll("2d6", random).orElseThrow();
        assertEquals(2, two.dice());
        assertEquals(6, two.sides());
        assertEquals(two.results().stream().mapToInt(Integer::intValue).sum(), two.total());
        assertTrue(two.results().stream().allMatch(v -> v >= 1 && v <= 6));

        assertEquals("1d20", FunLogic.roll("d20", random).orElseThrow().describe());
        assertEquals("1d20", FunLogic.roll("20", random).orElseThrow().describe());
        var plus = FunLogic.roll(" 3D8 + 2 ", random).orElseThrow();
        assertEquals("3d8+2", plus.describe());
        assertEquals(plus.results().stream().mapToInt(Integer::intValue).sum() + 2, plus.total());
        var minus = FunLogic.roll("4d4-1", random).orElseThrow();
        assertEquals(minus.results().stream().mapToInt(Integer::intValue).sum() - 1, minus.total());
    }

    @Test
    void diceThatAreNotDiceOrTooBigAreRefused() {
        var random = new Random(1);
        for (String bad : List.of("", "hello", "2d", "d1", "0d6", "21d6", "2d1001", "2d6+1001", "2d6*3", "-2d6", "1d6+", "99999")) {
            assertTrue(FunLogic.roll(bad, random).isEmpty(), "'" + bad + "' should be refused");
        }
    }

    @Test
    void everyDieStaysInRange() {
        var random = new Random(7);
        for (int i = 0; i < 500; i++) {
            var roll = FunLogic.roll("1d2", random).orElseThrow();
            assertTrue(roll.total() == 1 || roll.total() == 2);
        }
    }

    @Test
    void rockPaperScissorsFollowsTheRules() {
        var m = FunLogic.Move.values();
        for (var a : m) assertEquals(FunLogic.Outcome.DRAW, FunLogic.rps(a, a));
        assertEquals(FunLogic.Outcome.WIN, FunLogic.rps(FunLogic.Move.ROCK, FunLogic.Move.SCISSORS));
        assertEquals(FunLogic.Outcome.WIN, FunLogic.rps(FunLogic.Move.PAPER, FunLogic.Move.ROCK));
        assertEquals(FunLogic.Outcome.WIN, FunLogic.rps(FunLogic.Move.SCISSORS, FunLogic.Move.PAPER));
        assertEquals(FunLogic.Outcome.LOSE, FunLogic.rps(FunLogic.Move.SCISSORS, FunLogic.Move.ROCK));
        assertEquals(FunLogic.Outcome.LOSE, FunLogic.rps(FunLogic.Move.ROCK, FunLogic.Move.PAPER));
        assertEquals(FunLogic.Outcome.LOSE, FunLogic.rps(FunLogic.Move.PAPER, FunLogic.Move.SCISSORS));
    }

    @Test
    void chooseSplitsOnCommasBarsAndTheWordOr() {
        assertEquals(List.of("pizza", "pasta", "soup"), FunLogic.options("pizza or pasta, soup"));
        assertEquals(List.of("a", "b", "c"), FunLogic.options("a | b ; c"));
        assertEquals(List.of("tea", "coffee"), FunLogic.options(" tea ,, coffee "));
        assertEquals(List.of("oregano"), FunLogic.options("oregano"), "a word containing 'or' is not split");
        assertEquals(List.of(), FunLogic.options(" , "));
    }

    @Test
    void theEightBallAlwaysAnswers() {
        var random = new Random(3);
        for (int i = 0; i < 50; i++) assertTrue(FunLogic.EIGHT_BALL.contains(FunLogic.eightBall(random)));
        assertEquals(20, new HashSet<>(FunLogic.EIGHT_BALL).size());
    }

    @Test
    void everyFunCommandIsOffByDefaultTaggedAndRegisteredPerServer() {
        Set<String> funNames = new HashSet<>();
        for (Method method : FunCommands.class.getDeclaredMethods()) {
            JDASlashCommand slash = method.getAnnotation(JDASlashCommand.class);
            if (slash != null) funNames.add(slash.name());
        }
        assertEquals(8, funNames.size());
        for (String name : funNames) {
            HubCommand command = HubCommand.ofSlashName(name).orElseThrow(() -> new AssertionError("/" + name + " has no Hub entry, so it could never be switched on"));
            assertTrue(command.tags().contains(CommandTag.FUN), "/" + name + " must be tagged Fun");
            assertFalse(command.defaultEnabled(), "/" + name + " must start off");
            assertTrue(command.registeredPerServer(), "/" + name + " must be registered per server so off means not shown");
        }
        for (HubCommand command : HubCommand.values()) {
            if (command.tags().contains(CommandTag.FUN)) assertTrue(funNames.contains(command.slashName()), "Hub entry /" + command.slashName() + " has no command");
            else assertEquals(!command.punishes(), command.defaultEnabled(), "only fun and punishing commands start off: " + command.key());
        }
    }
}
