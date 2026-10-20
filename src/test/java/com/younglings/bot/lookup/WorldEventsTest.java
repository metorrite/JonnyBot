package com.younglings.bot.lookup;

import com.younglings.bot.lookup.VosService.Vos;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Parsing against responses saved from the real services (src/test/resources/lookup); nothing here touches the network. */
class WorldEventsTest {
    @Test
    void parsesTheVoiceOfSeren() {
        Vos v = VosService.parse(GePriceServiceTest.sample("vos.json")).orElseThrow();
        assertFalse(v.district1().isBlank());
        assertFalse(v.district2().isBlank());
        assertNotNull(v.since());
    }

    @Test
    void garbageVosIsEmpty() {
        assertTrue(VosService.parse("<html>").isEmpty());
        assertTrue(VosService.parse("{\"district1\":\"A\"}").isEmpty());
    }

    @Test
    void aReportIsCurrentOnlyForTheHourItDescribes() {
        Instant hour = Instant.parse("2026-10-19T10:00:00Z");
        Vos v = new Vos("Cadarn", "Iorwerth", hour);
        assertTrue(v.current(hour.plusSeconds(20 * 60)));
        assertFalse(v.current(hour.plusSeconds(61 * 60)));
        assertEquals(Instant.parse("2026-10-19T11:00:00Z"), VosService.nextChange(hour.plusSeconds(20 * 60)));
    }

    @Test
    void parsesTheWikisDndTable() {
        List<DndTable.Entry> rows = DndTable.parse(GePriceServiceTest.sample("dnd-frequency.json"));
        assertTrue(rows.size() > 15, "rows: " + rows.size());
        DndTable.Entry evilTree = rows.stream().filter(e -> e.name().equals("Evil Tree")).findFirst().orElseThrow();
        assertEquals("When available", evilTree.category());
        assertEquals("Every 2 hours", evilTree.timing());
        assertEquals("No limit", evilTree.frequency());
        DndTable.Entry supply = rows.stream().filter(e -> e.name().equals("Goebie Supply Runs")).findFirst().orElseThrow();
        assertEquals("Daily", supply.category());
        DndTable.Entry penguins = rows.stream().filter(e -> e.name().equals("Penguin Hide and Seek")).findFirst().orElseThrow();
        assertEquals("Weekly", penguins.category());
        assertEquals("", penguins.timing());
        // markup is gone
        rows.forEach(e -> assertFalse((e.name() + e.timing() + e.frequency() + e.requirements()).matches("(?s).*(\\[\\[|]]|\\{\\{|<ref|'''|<br).*"), e.toString()));
    }

    @Test
    void anUnrecognisedLayoutGivesNoRowsNotWrongOnes() {
        assertTrue(DndTable.parse("not json").isEmpty());
        assertTrue(DndTable.parseWikitext("just some prose").isEmpty());
    }

    @Test
    void theHourlyReminderIsDueOnlyInTheFirstTenMinutes() {
        assertEquals("2026-10-19T10", ReminderKind.VOS.dueKey(Instant.parse("2026-10-19T10:00:30Z")));
        assertEquals("2026-10-19T10", ReminderKind.VOS.dueKey(Instant.parse("2026-10-19T10:09:59Z")));
        assertNull(ReminderKind.VOS.dueKey(Instant.parse("2026-10-19T10:10:00Z")));
    }

    @Test
    void thePostedTextNamesBothDistrictsAndTheNextChange() {
        Instant now = Instant.parse("2026-10-19T10:01:00Z");
        String text = LookupReminderScheduler.vosText(new Vos("Cadarn", "Iorwerth", Instant.parse("2026-10-19T10:00:00Z")), now);
        assertTrue(text.contains("Cadarn and Iorwerth"));
        assertTrue(text.contains("<t:" + Instant.parse("2026-10-19T11:00:00Z").getEpochSecond() + ":R>"));
    }

    @Test
    void theCommandCardShowsBothSourcesAndSaysWhenOneIsDown() {
        Instant now = Instant.parse("2026-10-19T10:01:00Z");
        var rows = Optional.of(List.of(new DndTable.Entry("When available", "Evil Tree", "Every 2 hours", "No limit", "")));
        String all = text(com.younglings.bot.commands.lookup.WorldEventsCommandAccess.card(Optional.of(new Vos("A", "B", Instant.parse("2026-10-19T10:00:00Z"))), rows, now));
        assertTrue(all.contains("A and B"));
        assertTrue(all.contains("Evil Tree: every 2 hours"));
        String down = text(com.younglings.bot.commands.lookup.WorldEventsCommandAccess.card(Optional.empty(), Optional.empty(), now));
        assertTrue(down.contains("isn't answering"));
    }

    private static String text(net.dv8tion.jda.api.components.container.Container c) {
        return c.getComponents().stream().filter(x -> x instanceof net.dv8tion.jda.api.components.textdisplay.TextDisplay)
                .map(x -> ((net.dv8tion.jda.api.components.textdisplay.TextDisplay) x).getContent()).reduce("", String::concat);
    }
}
