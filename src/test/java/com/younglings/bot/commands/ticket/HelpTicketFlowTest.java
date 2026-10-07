package com.younglings.bot.commands.ticket;

import com.younglings.bot.combat.CombatAchievementLoader;
import com.younglings.bot.combat.CombatAchievementModels.Achievement;
import com.younglings.bot.ticket.TicketModels.Field;
import com.younglings.bot.ticket.TicketModels.Panel;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.tree.ComponentTree;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HelpTicketFlowTest {
    private static List<Achievement> achievements;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream stream = CombatAchievementLoader.class.getResourceAsStream(CombatAchievementLoader.RESOURCE)) {
            achievements = CombatAchievementLoader.parse(stream.readAllBytes()).achievements();
        }
    }

    private static List<String> bosses() {
        return achievements.stream().map(Achievement::subcategory).collect(Collectors.toCollection(() -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER))).stream().toList();
    }

    private static Panel panel(com.younglings.bot.ticket.TicketModels.HelpKind kind) {
        return new Panel(11, 1, "CA Help", "Combat Achievement Help", "", "Open", null, "ca-{number}", "", true, 1, null, 2, null, null, null, null,
                Panel.DEFAULT_OPENING, true, true, kind);
    }

    private static List<StringSelectMenu> menus(net.dv8tion.jda.api.components.container.Container container) {
        return ComponentTree.of(List.of(container)).findAll(StringSelectMenu.class);
    }

    // ---------- the choices ----------

    @Test
    void thirtyNineBossesFitInTwoBalancedDropdownsAndNoneIsLost() {
        List<String> bosses = bosses();
        assertEquals(39, bosses.size());
        var container = HelpTicketFlow.bossStep(panel(com.younglings.bot.ticket.TicketModels.HelpKind.CA), bosses);
        new MessageCreateBuilder().useComponentsV2(true).setComponents(container).build();

        var menus = menus(container);
        assertEquals(2, menus.size());
        assertTrue(menus.stream().allMatch(m -> m.getOptions().size() <= HelpTicketFlow.MAX_CHOICES));
        assertEquals(bosses.size(), menus.stream().mapToInt(m -> m.getOptions().size()).sum());
        // every value is the boss's place in the full list, whichever dropdown it is in
        for (var menu : menus) for (var option : menu.getOptions()) assertEquals(option.getLabel(), bosses.get(Integer.parseInt(option.getValue())));
    }

    @Test
    void aDungeonIsOneEntryAndItsBossesAreNotListedSeparately() {
        List<String> bosses = bosses();
        assertTrue(bosses.contains("Boss Dungeon: Sanctum of Rebirth"));
        assertFalse(bosses.contains("Astellarn"), "a boss inside a dungeon is only the achievements' subsubcategory");
        assertTrue(bosses.contains("Amascut") && bosses.contains("Arch-Glacor"));
    }

    @Test
    void chunksAreEvenAndKeepTheirOrder() {
        List<Integer> items = IntStream.range(0, 39).boxed().toList();
        var chunks = HelpTicketFlow.balancedChunks(items, 25);
        assertEquals(List.of(20, 19), chunks.stream().map(List::size).toList());
        assertEquals(items, chunks.stream().flatMap(List::stream).toList());
        assertEquals(1, HelpTicketFlow.balancedChunks(IntStream.range(0, 25).boxed().toList(), 25).size());
        assertTrue(HelpTicketFlow.balancedChunks(List.of(), 25).isEmpty());
    }

    @Test
    void theTierStepOffersNoTierOnlyWhenTheTierIsOptional() {
        var optional = menus(HelpTicketFlow.tierStep(panel(com.younglings.bot.ticket.TicketModels.HelpKind.PVM), "Vorago", 36, false)).getFirst();
        assertEquals(List.of("No tier", "Easy", "Medium", "Hard", "Elite", "Master", "Grandmaster"), optional.getOptions().stream().map(o -> o.getLabel()).toList());
        assertEquals("tickethelp_tier:11:36", optional.getCustomId());

        var required = menus(HelpTicketFlow.tierStep(panel(com.younglings.bot.ticket.TicketModels.HelpKind.CA), "Vorago", 36, true)).getFirst();
        assertEquals(List.of("Easy", "Medium", "Hard", "Elite", "Master", "Grandmaster"), required.getOptions().stream().map(o -> o.getLabel()).toList());
        assertEquals(List.of("1", "2", "3", "4", "5", "6"), required.getOptions().stream().map(o -> o.getValue()).toList());
    }

    @Test
    void voragosFortyTwoAchievementsSplitAcrossDropdownsAndCanBeSkipped() {
        List<Achievement> vorago = achievements.stream().filter(a -> a.subcategory().equals("Vorago")).toList();
        assertEquals(42, vorago.size());
        var container = HelpTicketFlow.achievementStep(panel(com.younglings.bot.ticket.TicketModels.HelpKind.PVM), "Vorago", null, 36, 0, vorago);
        new MessageCreateBuilder().useComponentsV2(true).setComponents(container).build();

        var menus = menus(container);
        assertEquals(2, menus.size());
        assertEquals(42, menus.stream().mapToInt(m -> m.getOptions().size()).sum());
        assertTrue(menus.stream().flatMap(m -> m.getOptions().stream()).allMatch(o -> o.getLabel().endsWith(")")), "with no tier chosen each shows its tier");
        assertTrue(menus.stream().flatMap(m -> m.getOptions().stream()).allMatch(o -> o.getDescription() != null && o.getDescription().length() <= 100));

        var buttons = ComponentTree.of(List.of(container)).findAll(Button.class);
        assertEquals(List.of("tickethelp_skip:11:36:0"), buttons.stream().map(Button::getCustomId).toList());
    }

    @Test
    void everyBossAndTierCombinationFitsInAMessage() {
        for (String boss : bosses()) {
            for (int tier = 0; tier <= 6; tier++) {
                int t = tier;
                List<Achievement> list = achievements.stream().filter(a -> a.subcategory().equals(boss) && (t == 0 || a.tierNumber() == t)).toList();
                if (list.isEmpty()) continue;
                var container = HelpTicketFlow.achievementStep(panel(com.younglings.bot.ticket.TicketModels.HelpKind.CA), boss, t == 0 ? null : HelpRules.TIERS.get(t - 1), 0, t, list);
                new MessageCreateBuilder().useComponentsV2(true).setComponents(container).build();
                assertTrue(menus(container).stream().allMatch(m -> m.getOptions().size() <= 25), boss + " tier " + t);
            }
        }
    }

    // ---------- the answers ----------

    @Test
    void theFlowsChoicesAreFiledUnderTheQuestionsMarkedForThem() {
        var fields = fieldsWithIds(DevHelpSetup.caPanel(1).fields());
        Map<Long, String> raw = HelpTicketFlow.rawValues(fields, "Amascut", "Master", "All Together, Now!", Map.of(104L, "true", 105L, "Stuck on phase 3"));

        assertEquals("Amascut", raw.get(100L));
        assertEquals(String.valueOf(fields.get(1).options().get(4).id()), raw.get(101L), "the tier is the matching dropdown option");
        assertEquals("All Together, Now!", raw.get(102L));
        assertEquals("true", raw.get(104L));
        assertEquals("Stuck on phase 3", raw.get(105L));
        assertEquals("Master", HelpRules.tierOf(fields, raw), "the guest rules read the tier the same way as from one form");
    }

    @Test
    void noTierOrAchievementLeavesThoseAnswersEmpty() {
        var fields = fieldsWithIds(DevHelpSetup.pvmPanel(1).fields());
        Map<Long, String> raw = HelpTicketFlow.rawValues(fields, "Vorago", null, null, Map.of());
        assertEquals("Vorago", raw.get(100L));
        assertFalse(raw.containsKey(101L));
        assertEquals("", raw.get(102L));
        assertEquals(null, HelpRules.tierOf(fields, raw));
    }

    @Test
    void theSmallFormAsksOnlyWhatTheFlowHasNot() {
        var rest = HelpTicketFlow.formFields(fieldsWithIds(DevHelpSetup.caPanel(1).fields()));
        assertEquals(List.of("Have you already made attempts yourself?", "Where you stand / what you need"), rest.stream().map(Field::label).toList());
    }

    @Test
    void onlyAHelpPanelWithABossQuestionUsesTheFlow() {
        assertTrue(HelpTicketFlow.applies(DevHelpSetup.caPanel(1).panel(), DevHelpSetup.caPanel(1).fields()));
        assertTrue(HelpTicketFlow.applies(DevHelpSetup.pvmPanel(1).panel(), DevHelpSetup.pvmPanel(1).fields()));
        assertFalse(HelpTicketFlow.applies(panel(com.younglings.bot.ticket.TicketModels.HelpKind.NONE), DevHelpSetup.caPanel(1).fields()), "an ordinary panel keeps its form");
        assertFalse(HelpTicketFlow.applies(DevHelpSetup.caPanel(1).panel(), List.of()), "no boss question");
    }

    /** Gives each question an id the way the database would: 100, 101, ... and its choices ids of their own. */
    private static List<Field> fieldsWithIds(List<Field> fields) {
        java.util.ArrayList<Field> out = new java.util.ArrayList<>();
        long id = 100;
        for (Field f : fields) {
            long fieldId = id++;
            java.util.ArrayList<com.younglings.bot.ticket.TicketModels.Option> options = new java.util.ArrayList<>();
            long optionId = fieldId * 100;
            for (var o : f.options()) options.add(new com.younglings.bot.ticket.TicketModels.Option(optionId++, fieldId, o.position(), o.label(), null, null));
            out.add(new Field(fieldId, 11, f.position(), f.label(), f.kind(), f.required(), f.placeholder(), f.maxLength(), options, f.purpose()));
        }
        return out;
    }
}
