package com.younglings.bot.alliance;

import com.younglings.bot.alliance.AllianceVisibility.Ally;
import com.younglings.bot.alliance.AllianceVisibility.AllianceView;
import com.younglings.bot.alliance.AllianceVisibility.Group;
import com.younglings.bot.alliance.AllianceVisibility.Mode;
import com.younglings.bot.alliance.AllianceVisibility.Section;
import com.younglings.bot.alliance.AllianceVisibility.Visibility;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.younglings.bot.alliance.AllianceVisibility.Visibility.HIDDEN;
import static com.younglings.bot.alliance.AllianceVisibility.Visibility.NO_ALLIES;
import static com.younglings.bot.alliance.AllianceVisibility.Visibility.SHOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The mutual-hiding rule for the public hub, over many combinations of clans. A=1, B=2, C=3, D=4. */
class AllianceVisibilityTest {
    private static Ally ally(long id, Visibility v, boolean listed, boolean active) {
        return new Ally(id, v, listed, active);
    }

    private static Ally shown(long id) {
        return ally(id, SHOWN, true, true);
    }

    private static List<Long> named(Section section) {
        List<Long> out = new ArrayList<>();
        section.groups().forEach(g -> out.addAll(g.allies()));
        return out;
    }

    @Test
    void whenEveryoneShowsEachPageListsTheOthers() {
        AllianceView x = new AllianceView(10, "The Coalition", List.of(shown(1), shown(2), shown(3)));
        Section a = AllianceVisibility.sectionFor(1, SHOWN, List.of(x));
        assertEquals(Mode.LIST, a.mode());
        assertEquals(List.of(2L, 3L), a.groups().get(0).allies());
        assertEquals("The Coalition", a.groups().get(0).name());
        assertTrue(a.groups().get(0).pageAvailable());
        assertEquals(List.of(1L, 3L), AllianceVisibility.sectionFor(2, SHOWN, List.of(x)).groups().get(0).allies());
    }

    @Test
    void aClanThatHidesIsNeverNamedByItsAllies() {
        AllianceView x = new AllianceView(10, "X", List.of(shown(1), ally(2, HIDDEN, true, true), shown(3)));
        assertEquals(List.of(3L), named(AllianceVisibility.sectionFor(1, SHOWN, List.of(x))));
        assertEquals(List.of(1L), named(AllianceVisibility.sectionFor(3, SHOWN, List.of(x))));
        assertEquals(Mode.ABSENT, AllianceVisibility.sectionFor(2, HIDDEN, List.of(x)).mode(), "its own page says nothing");
        assertEquals(List.of(1L, 3L), AllianceVisibility.nameableMembers(x).stream().map(Ally::guildId).toList());
    }

    @Test
    void aClanThatSaysItHasNoAlliesIsNeverNamedEitherAndItsPageSaysNone() {
        AllianceView x = new AllianceView(10, "X", List.of(shown(1), ally(2, NO_ALLIES, true, true), shown(3)));
        assertEquals(List.of(3L), named(AllianceVisibility.sectionFor(1, SHOWN, List.of(x))));
        Section own = AllianceVisibility.sectionFor(2, NO_ALLIES, List.of(x));
        assertEquals(Mode.NONE, own.mode(), "it shows 'No allies' whatever is true");
        assertTrue(own.groups().isEmpty());
    }

    @Test
    void aClanThatIsNotListedIsNotNamedByOthersButMayShowItsOwnAllies() {
        AllianceView x = new AllianceView(10, "X", List.of(shown(1), ally(2, SHOWN, false, true), shown(3)));
        assertEquals(List.of(3L), named(AllianceVisibility.sectionFor(1, SHOWN, List.of(x))));
        assertEquals(List.of(1L, 3L), named(AllianceVisibility.sectionFor(2, SHOWN, List.of(x))), "opened by its own address it lists the allies that are nameable");
    }

    @Test
    void aClanThatWasOnlyInvitedIsNotAnAllyYet() {
        AllianceView x = new AllianceView(10, "X", List.of(shown(1), ally(2, SHOWN, true, false)));
        assertEquals(Mode.NONE, AllianceVisibility.sectionFor(1, SHOWN, List.of(x)).mode());
        assertFalse(AllianceVisibility.hasPage(x));
    }

    @Test
    void ifEveryAllyIsHiddenTheShownClanSaysNoAlliesWithoutRevealingThem() {
        AllianceView x = new AllianceView(10, "X", List.of(shown(1), ally(2, HIDDEN, true, true), ally(3, NO_ALLIES, true, true), ally(4, SHOWN, false, true)));
        Section a = AllianceVisibility.sectionFor(1, SHOWN, List.of(x));
        assertEquals(Mode.NONE, a.mode());
        assertTrue(a.groups().isEmpty());
    }

    @Test
    void anAllianceNeedsTwoNameableClansToHaveAPage() {
        assertTrue(AllianceVisibility.hasPage(new AllianceView(1, "X", List.of(shown(1), shown(2)))));
        assertFalse(AllianceVisibility.hasPage(new AllianceView(1, "X", List.of(shown(1), ally(2, HIDDEN, true, true)))));
        assertFalse(AllianceVisibility.hasPage(new AllianceView(1, "X", List.of(shown(1)))));
        // a group on a page links to the alliance page only when it has one
        AllianceView pair = new AllianceView(10, "X", List.of(shown(1), ally(2, SHOWN, false, true), shown(3)));
        Group g = AllianceVisibility.sectionFor(1, SHOWN, List.of(pair)).groups().get(0);
        assertEquals(List.of(3L), g.allies());
        assertTrue(g.pageAvailable(), "clans 1 and 3 can both be named");
        AllianceView lonely = new AllianceView(11, "Y", List.of(ally(1, SHOWN, false, true), shown(4)));
        Group lg = AllianceVisibility.sectionFor(1, SHOWN, List.of(lonely)).groups().get(0);
        assertEquals(List.of(4L), lg.allies());
        assertFalse(lg.pageAvailable(), "only clan 4 can be named, so there is no public alliance page to link to");
    }

    @Test
    void severalAlliancesAreGroupedAndEachHidesTheSameWay() {
        AllianceView x = new AllianceView(10, "X", List.of(shown(1), shown(2), ally(3, HIDDEN, true, true)));
        AllianceView y = new AllianceView(11, "Y", List.of(shown(1), shown(4)));
        AllianceView z = new AllianceView(12, "Z", List.of(shown(2), shown(4)));
        Section a = AllianceVisibility.sectionFor(1, SHOWN, List.of(x, y, z));
        assertEquals(2, a.groups().size(), "A is not in Z");
        assertEquals(List.of(2L), a.groups().get(0).allies());
        assertEquals(List.of(4L), a.groups().get(1).allies());
        assertFalse(named(a).contains(3L));
    }

    @Test
    void aClanSeesNothingOfAnAllianceItIsNotActiveIn() {
        AllianceView x = new AllianceView(10, "X", List.of(shown(2), shown(3)));
        assertEquals(Mode.NONE, AllianceVisibility.sectionFor(1, SHOWN, List.of(x)).mode());
    }

    @Test
    void visibilityDefaultsToHiddenAndUnknownValuesAreHidden() {
        assertEquals(HIDDEN, Visibility.parse(null));
        assertEquals(HIDDEN, Visibility.parse("everything"));
        assertEquals(SHOWN, Visibility.parse(" shown "));
        assertEquals(NO_ALLIES, Visibility.parse("no_allies"));
    }

    /** Every combination of three clans' choices, listing and membership: nobody who must stay hidden is ever named, on any page. */
    @Test
    void noCombinationEverNamesAClanThatMustStayHidden() {
        Visibility[] all = Visibility.values();
        boolean[] flags = {true, false};
        int combinations = 0;
        for (Visibility v1 : all) for (Visibility v2 : all) for (Visibility v3 : all)
            for (boolean l1 : flags) for (boolean l2 : flags) for (boolean l3 : flags)
                for (boolean a2 : flags) for (boolean a3 : flags) {
                    combinations++;
                    List<Ally> members = List.of(ally(1, v1, l1, true), ally(2, v2, l2, a2), ally(3, v3, l3, a3));
                    AllianceView view = new AllianceView(10, "X", members);
                    Map<Long, Ally> byId = Map.of(1L, members.get(0), 2L, members.get(1), 3L, members.get(2));
                    for (Ally viewer : members) {
                        if (!viewer.active()) continue;
                        Section section = AllianceVisibility.sectionFor(viewer.guildId(), viewer.visibility(), List.of(view));
                        for (long named : named(section)) {
                            Ally n = byId.get(named);
                            assertTrue(n.visibility() == SHOWN && n.listed() && n.active(), "clan " + named + " was named on clan " + viewer.guildId() + "'s page: " + members);
                            assertTrue(named != viewer.guildId());
                        }
                        // a clan that does not show says nothing, or none
                        if (viewer.visibility() == HIDDEN) assertEquals(Mode.ABSENT, section.mode());
                        if (viewer.visibility() == NO_ALLIES) assertEquals(Mode.NONE, section.mode());
                        if (viewer.visibility() == SHOWN) assertTrue(section.mode() == Mode.LIST || section.mode() == Mode.NONE);
                    }
                    for (Ally n : AllianceVisibility.nameableMembers(view)) assertTrue(n.visibility() == SHOWN && n.listed() && n.active(), "alliance page names " + n + " in " + members);
                }
        assertEquals(3 * 3 * 3 * 8 * 4, combinations);
    }
}
