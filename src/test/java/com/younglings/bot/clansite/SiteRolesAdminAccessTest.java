package com.younglings.bot.clansite;

import com.younglings.bot.clansite.SiteRoles.AdminAccess;
import com.younglings.bot.clansite.SiteRoles.Facts;
import com.younglings.bot.clansite.SiteRoles.Role;
import com.younglings.bot.clansite.SiteRoles.Settings;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** N5's access table: overall admins own every site, and the site's own admin access (people, roles, the staff-server rule). Made-up ids. */
class SiteRolesAdminAccessTest {
    private static final long BASE = Long.parseLong("9".repeat(17));
    private static final long USER = BASE + 1;
    private static final long ADMIN_ROLE = BASE + 20;
    private static final long STAFF_SERVER = BASE + 30;

    private static Facts outsider() {
        return new Facts(USER, false, false, false, Set.of(), null);
    }

    private static Settings with(AdminAccess access) {
        return Settings.DEFAULT.withAdminAccess(access);
    }

    @Test
    void anOverallAdminOwnsEverySiteInTheServerOrNot() {
        assertEquals(Role.OWNER, SiteRoles.resolve(outsider().withOverallAdmin(true), Settings.DEFAULT));
        assertEquals(Role.OWNER, SiteRoles.resolve(new Facts(USER, true, false, false, Set.of(), null).withOverallAdmin(true), Settings.DEFAULT));
        assertEquals(Role.GUEST, SiteRoles.resolve(outsider(), Settings.DEFAULT));
    }

    @Test
    void peopleNamedByUserIdAreAdminsInTheServerOrNot() {
        Settings s = with(new AdminAccess(List.of(USER), List.of(), null, 0));
        assertEquals(Role.ADMIN, SiteRoles.resolve(outsider(), s));
        assertEquals(Role.ADMIN, SiteRoles.resolveWithoutBot(USER, s), "the public pages of a bot-less site agree");
        assertEquals(Role.OWNER, SiteRoles.resolve(new Facts(USER, true, true, false, Set.of(), null), s), "the server owner stays the owner");
    }

    @Test
    void aHeldRoleCountsOnlyInTheServer() {
        Settings s = with(new AdminAccess(List.of(), List.of(ADMIN_ROLE), null, 0));
        assertEquals(Role.ADMIN, SiteRoles.resolve(new Facts(USER, true, false, false, Set.of(ADMIN_ROLE), null), s));
        assertEquals(Role.MEMBER, SiteRoles.resolve(new Facts(USER, true, false, false, Set.of(), null), s));
    }

    @Test
    void theStaffServerRule() {
        Settings s = with(new AdminAccess(List.of(), List.of(), STAFF_SERVER, 0x20L));
        assertEquals(Role.ADMIN, SiteRoles.resolve(outsider().withStaffServers(Map.of(STAFF_SERVER, 0x20L | 0x400L)), s));
        assertEquals(Role.ADMIN, SiteRoles.resolve(outsider().withStaffServers(Map.of(STAFF_SERVER, 0x8L)), s), "Administrator always counts");
        assertEquals(Role.GUEST, SiteRoles.resolve(outsider().withStaffServers(Map.of(STAFF_SERVER, 0x2L)), s));
        assertEquals(Role.GUEST, SiteRoles.resolve(outsider().withStaffServers(Map.of(STAFF_SERVER + 1, 0x20L)), s));
        assertEquals(Role.GUEST, SiteRoles.resolve(outsider().withStaffServers(Map.of(STAFF_SERVER, 0x20L)), Settings.DEFAULT), "no rule set");
        assertEquals(new AdminAccess(List.of(), List.of(), null, 0), new AdminAccess(List.of(), List.of(), null, 0x20L), "no server, no permission");
    }
}
