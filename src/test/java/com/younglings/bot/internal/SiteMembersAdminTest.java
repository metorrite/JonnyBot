package com.younglings.bot.internal;

import com.younglings.bot.clansite.SiteRoles;
import com.younglings.bot.clansite.SiteRoles.Role;
import com.younglings.bot.internal.SiteMembersAdmin.Person;
import com.younglings.bot.member.MemberProfileRepository.PrivacyDefaults;
import com.younglings.bot.member.MemberProfileRepository.Profile;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The site admin's member pages (T-395 privacy defaults, T-396 who has which site role): the pure parts. */
class SiteMembersAdminTest {
    private static final long GUILD = Long.parseLong("5".repeat(18));
    private static final long USER = Long.parseLong("4".repeat(18));

    private static Person person(String name, Role role, boolean staff) {
        return new Person(name, name.toLowerCase(), null, null, role, staff);
    }

    @Test
    void aDefaultOnlyAppliesUntilTheMemberChoosesAndNeverShowsWhatTheyHid() {
        PrivacyDefaults hideAll = new PrivacyDefaults(true, true, true);
        Profile untouched = Profile.defaults(GUILD, USER);
        Profile applied = untouched.withDefaults(hideAll);
        assertTrue(applied.hideFromLeaderboards() && applied.hideAdventureLog() && applied.hideDiscordLink(), "not chosen: the defaults apply");

        Profile chose = new Profile(GUILD, USER, "", null, null, false, false, true, false, false, true);
        assertSame(chose, chose.withDefaults(hideAll), "chosen: the member's own toggles win");

        Profile hid = new Profile(GUILD, USER, "", null, null, true, true, true, false, true);
        Profile none = hid.withDefaults(PrivacyDefaults.NONE);
        assertTrue(none.hideFromLeaderboards() && none.hideAdventureLog() && none.hideDiscordLink(), "a default never shows what a member hid");
        assertFalse(PrivacyDefaults.NONE.any(), "everything shows by default");
    }

    @Test
    void thePrivacyAnswer() {
        DataObject json = SiteMembersAdmin.privacyJson(new PrivacyDefaults(true, false, true));
        assertTrue(json.getBoolean("hideFromLeaderboards"));
        assertFalse(json.getBoolean("hideAdventureLog"));
        assertTrue(json.getBoolean("hideDiscordLink"));
    }

    @Test
    void aSearchMatchesAnyNameIgnoringCase() {
        assertTrue(SiteMembersAdmin.matches("", "anyone"));
        assertTrue(SiteMembersAdmin.matches(SiteMembersAdmin.clean("  ZEZ "), "Zezima", null));
        assertTrue(SiteMembersAdmin.matches("zez", "Display", "zezima"), "the username counts too");
        assertFalse(SiteMembersAdmin.matches("woox", "Zezima", "zezima"));
        assertEquals(SiteMembersAdmin.MAX_QUERY, SiteMembersAdmin.clean("x".repeat(500)).length());
    }

    @Test
    void peopleAreFilteredByRoleAndListedHighestFirst() {
        List<Person> people = List.of(person("bravo", Role.MEMBER, false), person("Alpha", Role.MEMBER, true), person("Owner", Role.OWNER, true),
                person("Ed", Role.EDITOR, true), person("Guest", Role.GUEST, false));
        assertEquals(List.of("Owner", "Ed", "Alpha", "bravo", "Guest"), SiteMembersAdmin.select(people, "").stream().map(Person::name).toList());
        assertEquals(List.of("Alpha", "bravo"), SiteMembersAdmin.select(people, "member").stream().map(Person::name).toList());
        assertEquals(List.of("Owner", "Ed", "Alpha"), SiteMembersAdmin.select(people, "staff").stream().map(Person::name).toList(),
                "staff: anyone holding a staff role, and editors and up");
    }

    @Test
    void theAnswerIsPagedWithNoDiscordIdWhereTheBotIsIn() {
        List<Person> people = new ArrayList<>();
        for (int i = 0; i < SiteMembersAdmin.PAGE_SIZE + 3; i++) people.add(person("p" + i, Role.MEMBER, false));
        DataObject first = SiteMembersAdmin.pageJson(people, 1, false);
        assertEquals(SiteMembersAdmin.PAGE_SIZE, first.getArray("people").length());
        assertEquals(2, first.getInt("pages"));
        assertEquals(SiteMembersAdmin.PAGE_SIZE + 3, first.getInt("total"));
        DataObject last = SiteMembersAdmin.pageJson(people, 99, false);
        assertEquals(2, last.getInt("page"), "a page past the end shows the last one");
        assertEquals(3, last.getArray("people").length());
        DataObject row = first.getArray("people").getObject(0);
        assertEquals("member", row.getString("role"));
        assertFalse(row.hasKey("userId"));
        assertEquals(0, SiteMembersAdmin.pageJson(List.of(), 1, false).getArray("people").length());
    }

    @Test
    void aBotlessSiteListsOnlyThePeopleItNamesById() {
        long owner = Long.parseLong("1".repeat(18));
        long admin = Long.parseLong("2".repeat(18));
        long editor = Long.parseLong("3".repeat(18));
        SiteRoles.Settings s = new SiteRoles.Settings(List.of(), List.of(), List.of(), false, owner, List.of(editor, admin),
                new SiteRoles.AdminAccess(List.of(admin), List.of(), null, 0));
        List<Person> people = SiteMembersAdmin.select(SiteMembersAdmin.knownPeople(s), "");
        assertEquals(List.of(String.valueOf(owner), String.valueOf(admin), String.valueOf(editor)), people.stream().map(Person::userId).toList());
        assertEquals(List.of(Role.OWNER, Role.ADMIN, Role.EDITOR), people.stream().map(Person::role).toList(), "someone named twice shows at the higher role");
    }

    @Test
    void aGetReadsItsQueryString() {
        DataObject q = AdminRouteExtension.queryOf(URI.create("http://x/internal/admin/1/clan-site/roles/people?q=Zez%20ima&role=editor&q=second&page"));
        assertEquals("Zez ima", q.getString("q"));
        assertEquals("editor", q.getString("role"));
        assertEquals("", q.getString("page"));
        assertEquals(0, AdminRouteExtension.queryOf(URI.create("http://x/a")).keys().size());
    }
}
