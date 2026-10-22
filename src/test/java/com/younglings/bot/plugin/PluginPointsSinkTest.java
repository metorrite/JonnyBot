package com.younglings.bot.plugin;

import com.younglings.bot.plugin.PluginPointsRules.Rule;
import com.younglings.bot.points.PointsRepository;
import com.younglings.bot.points.PointsRules.Category;
import com.younglings.bot.points.PointsService;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PluginPointsSinkTest {
    private static final long GUILD = 100L;
    private static final long USER = 7L;
    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");

    private final PluginPointsRepository rules = mock(PluginPointsRepository.class);
    private final PointsRepository points = mock(PointsRepository.class);
    private final PointsService service = mock(PointsService.class);
    private final PluginPointsSink sink = new PluginPointsSink(rules, points, service);

    private static PluginPayload.Event event(String key, PluginEventType type, String data) {
        return new PluginPayload.Event(key, type, NOW, null, null, null, data);
    }

    private void pointsOn(boolean on) {
        when(points.settings(GUILD)).thenReturn(new PointsRepository.Settings(GUILD, on, "points", null));
    }

    @Test
    void aKillPaysTheClansPointsOnceWithAKeyPerReport() {
        pointsOn(true);
        when(rules.rules(GUILD)).thenReturn(List.of(new Rule(PluginEventType.KILL, 5, 0, null)));
        when(service.apply(anyLong(), anyLong(), anyLong(), anyString(), any(), anyString(), any())).thenReturn(new PointsService.Result(PointsService.Outcome.DONE, null, 5, 1));

        sink.pay(USER, List.of(GUILD), List.of(event("rs1:anon:1:1", PluginEventType.KILL, "{\"bossName\":\"Telos\",\"killCount\":4}")), NOW);

        verify(service).apply(GUILD, USER, 5, "RuneSync: boss kill (Telos)", Category.PLUGIN, "plugin:KILL:7:rs1:anon:1:1", null);
    }

    @Test
    void nothingIsPaidWhenTheServerDoesntUsePointsOrHasNoRule() {
        pointsOn(false);
        when(rules.rules(GUILD)).thenReturn(List.of(new Rule(PluginEventType.KILL, 5, 0, null)));
        sink.pay(USER, List.of(GUILD), List.of(event("k1", PluginEventType.KILL, "{\"bossName\":\"Telos\"}")), NOW);

        pointsOn(true);
        when(rules.rules(GUILD)).thenReturn(List.of(new Rule(PluginEventType.CLUE, 5, 0, null)));
        sink.pay(USER, List.of(GUILD), List.of(event("k2", PluginEventType.KILL, "{\"bossName\":\"Telos\"}")), NOW);

        verify(service, never()).apply(anyLong(), anyLong(), anyLong(), anyString(), any(), anyString(), any());
    }

    @Test
    void theDailyCapSkipsAnAwardThatWouldPassIt() {
        pointsOn(true);
        when(rules.rules(GUILD)).thenReturn(List.of(new Rule(PluginEventType.CLUE, 10, 25, null)));
        when(rules.paidSince(eq(GUILD), eq(USER), eq(PluginEventType.CLUE), any())).thenReturn(20L);

        sink.pay(USER, List.of(GUILD), List.of(event("c1", PluginEventType.CLUE, "{\"tier\":\"elite\",\"count\":3}")), NOW);

        verify(service, never()).apply(anyLong(), anyLong(), anyLong(), anyString(), any(), anyString(), any());
        verify(rules).paidSince(GUILD, USER, PluginEventType.CLUE, Instant.parse("2026-10-21T00:00:00Z"));
    }

    @Test
    void onlyValuableDropsPay() {
        Rule rule = new Rule(PluginEventType.DROP, 3, 0, 5_000_000L);
        assertFalse(PluginPointsRules.pays(rule, DataObject.fromJson("{\"itemName\":\"Bones\",\"valueGp\":100}")));
        assertFalse(PluginPointsRules.pays(rule, DataObject.fromJson("{\"itemName\":\"Unpriced\"}")), "an unpriced drop never pays");
        assertTrue(PluginPointsRules.pays(rule, DataObject.fromJson("{\"itemName\":\"Torva boots\",\"valueGp\":9000000}")));
        assertTrue(PluginPointsRules.pays(new Rule(PluginEventType.DROP, 3, 0, null), DataObject.fromJson("{\"valueGp\":1000000}")), "default smallest value is 1m");
    }

    @Test
    void rulesAreChecked() {
        assertNull(PluginPointsRules.problem(new Rule(PluginEventType.KILL, 5, 50, null)));
        assertNotNull(PluginPointsRules.problem(new Rule(PluginEventType.XP, 5, 0, null)));
        assertNotNull(PluginPointsRules.problem(new Rule(PluginEventType.KILL, -1, 0, null)));
        assertNotNull(PluginPointsRules.problem(new Rule(PluginEventType.KILL, PluginPointsRules.MAX_POINTS + 1, 0, null)));
        assertEquals(0, PluginPointsRules.afterCap(new Rule(PluginEventType.KILL, 5, 10, null), 6));
        assertEquals(5, PluginPointsRules.afterCap(new Rule(PluginEventType.KILL, 5, 10, null), 5));
        assertEquals(5, PluginPointsRules.afterCap(new Rule(PluginEventType.KILL, 5, 0, null), 1_000));
    }

    @Test
    void reasonsReadWell() {
        assertEquals("RuneSync: level up (Fletching 99)", PluginPointsRules.reason(PluginEventType.LEVEL_UP, DataObject.fromJson("{\"skill\":\"Fletching\",\"level\":99}")));
        assertEquals("RuneSync: clue scroll (elite clue)", PluginPointsRules.reason(PluginEventType.CLUE, DataObject.fromJson("{\"tier\":\"elite\"}")));
        assertEquals("RuneSync: quest", PluginPointsRules.reason(PluginEventType.QUEST_COMPLETE, DataObject.empty()));
    }
}
