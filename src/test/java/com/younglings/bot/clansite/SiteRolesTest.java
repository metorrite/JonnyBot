package com.younglings.bot.clansite;

import com.younglings.bot.clansite.SiteRoles.Action;
import com.younglings.bot.clansite.SiteRoles.Facts;
import com.younglings.bot.clansite.SiteRoles.Role;
import com.younglings.bot.clansite.SiteRoles.Settings;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The clan website role table (T-368, docs/BUILDER-V3.md 8.2). Ids are made up at runtime. */
class SiteRolesTest {
    private static final long BASE = Long.parseLong("9".repeat(17));
    private static final long USER = BASE + 1;
    private static final long EDITOR_ROLE = BASE + 10;
    private static final long MEMBER_ROLE = BASE + 11;
    private static final long CLAN_ROLE = BASE + 12;
    private static final long STAFF_ROLE = BASE + 13;
    private static final long OTHER_ROLE = BASE + 14;

    private static Facts in(boolean owner, boolean admin, Long clanRole, Long... held) {
        return new Facts(USER, true, owner, admin, Set.of(held), clanRole);
    }

    private static Settings settings(List<Long> editors, List<Long> members, boolean editorsPublish, Long claimedBy) {
        return new Settings(editors, members, List.of(STAFF_ROLE), editorsPublish, claimedBy);
    }

    @Test
    void theRoleTable() {
        Settings plain = Settings.DEFAULT;
        assertEquals(Role.VISITOR, SiteRoles.resolve(null, plain), "not signed in");
        assertEquals(Role.GUEST, SiteRoles.resolve(new Facts(USER, false, false, false, Set.of(), null), plain), "signed in, not in the server");
        assertEquals(Role.OWNER, SiteRoles.resolve(in(true, false, null), plain), "the server owner");
        assertEquals(Role.OWNER, SiteRoles.resolve(in(false, false, null), settings(List.of(), List.of(), false, USER)), "whoever claimed the site");
        assertEquals(Role.GUEST, SiteRoles.resolve(new Facts(USER, false, false, false, Set.of(), null), settings(List.of(), List.of(), false, USER)),
                "a claimer who left the server owns nothing");
        assertEquals(Role.ADMIN, SiteRoles.resolve(in(false, true, null), plain), "anyone the dashboard lets in");
        Settings editors = settings(List.of(EDITOR_ROLE), List.of(), false, null);
        assertEquals(Role.EDITOR, SiteRoles.resolve(in(false, false, null, EDITOR_ROLE), editors), "holds an editor role");
        assertEquals(Role.MEMBER, SiteRoles.resolve(in(false, false, null, OTHER_ROLE), editors), "no editor role: a member");
        assertEquals(Role.MEMBER, SiteRoles.resolve(in(false, false, null, EDITOR_ROLE), plain), "editor roles are empty by default");
    }

    @Test
    void whoCountsAsAMember() {
        Settings named = settings(List.of(), List.of(MEMBER_ROLE), false, null);
        assertEquals(Role.MEMBER, SiteRoles.resolve(in(false, false, CLAN_ROLE, MEMBER_ROLE), named), "holds a named member role");
        assertEquals(Role.GUEST, SiteRoles.resolve(in(false, false, CLAN_ROLE, CLAN_ROLE), named), "named roles win over the clan Member role");

        assertEquals(Role.MEMBER, SiteRoles.resolve(in(false, false, CLAN_ROLE, CLAN_ROLE), Settings.DEFAULT), "default: the clan Member role");
        assertEquals(Role.GUEST, SiteRoles.resolve(in(false, false, CLAN_ROLE, OTHER_ROLE), Settings.DEFAULT), "in the server without the Member role");
        assertEquals(Role.MEMBER, SiteRoles.resolve(in(false, false, null), Settings.DEFAULT), "no Member role set up: anyone in the server");
    }

    @Test
    void whatEachRoleMayDo() {
        Settings off = settings(List.of(EDITOR_ROLE), List.of(), false, null);
        Settings on = settings(List.of(EDITOR_ROLE), List.of(), true, null);
        assertTrue(SiteRoles.allows(Role.EDITOR, Action.EDIT, off), "an editor saves the draft");
        assertFalse(SiteRoles.allows(Role.EDITOR, Action.PUBLISH, off), "but can't publish by default");
        assertTrue(SiteRoles.allows(Role.EDITOR, Action.PUBLISH, on), "unless Editors may publish is on");
        assertFalse(SiteRoles.allows(Role.EDITOR, Action.SETTINGS, on));
        assertFalse(SiteRoles.allows(Role.MEMBER, Action.EDIT, on));
        assertFalse(SiteRoles.allows(Role.GUEST, Action.EDIT, on));
        assertFalse(SiteRoles.allows(Role.VISITOR, Action.EDIT, on));
        for (Action a : List.of(Action.EDIT, Action.PUBLISH, Action.SETTINGS)) assertTrue(SiteRoles.allows(Role.ADMIN, a, off), a.name());
        assertFalse(SiteRoles.allows(Role.ADMIN, Action.OWN, off), "admins can't delete or hand over the site");
        for (Action a : Action.values()) assertTrue(SiteRoles.allows(Role.OWNER, a, off), a.name());
    }

    @Test
    void staffPages() {
        Settings s = settings(List.of(EDITOR_ROLE), List.of(), false, null);
        Facts staff = in(false, false, null, STAFF_ROLE);
        assertTrue(SiteRoles.isStaff(SiteRoles.resolve(staff, s), staff, s), "holds a staff role");
        Facts editor = in(false, false, null, EDITOR_ROLE);
        assertTrue(SiteRoles.isStaff(Role.EDITOR, editor, s));
        assertTrue(SiteRoles.isStaff(Role.ADMIN, in(false, true, null), s));
        Facts member = in(false, false, null, OTHER_ROLE);
        assertFalse(SiteRoles.isStaff(Role.MEMBER, member, s));
        assertFalse(SiteRoles.isStaff(Role.GUEST, new Facts(USER, false, false, false, Set.of(STAFF_ROLE), null), s), "not in the server");
        assertFalse(SiteRoles.isStaff(Role.VISITOR, null, s));
    }
}
