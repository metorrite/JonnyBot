package com.runeclans.bot.internal;

import com.runeclans.bot.clansite.SiteRoles;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** T-445: the owner's stand-in in a server they aren't in, and how RuneClan Admin edits are named in a clan's site history. Made-up ids. */
class OwnerOverrideRulesTest {
    private static final long BASE = Long.parseLong("7".repeat(17));
    private static final long GUILD = BASE + 1;
    private static final long OWNER = BASE + 2;

    @Test
    void theStandInHasTheOwnersIdAndNameButNoDiscordRights() {
        JDA jda = mock(JDA.class);
        User user = mock(User.class);
        when(user.getName()).thenReturn("owner-name");
        when(user.getIdLong()).thenReturn(OWNER);
        when(jda.getUserById(OWNER)).thenReturn(user);
        Guild guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        when(guild.getJDA()).thenReturn(jda);

        Member m = OwnerStandIn.of(guild, OWNER);
        assertEquals(OWNER, m.getIdLong());
        assertEquals(Long.toString(OWNER), m.getId());
        assertEquals("owner-name", m.getEffectiveName());
        assertSame(guild, m.getGuild());
        assertSame(user, m.getUser());
        assertEquals(List.of(), m.getRoles());
        assertFalse(m.isOwner(), "never the server's owner");
        assertFalse(m.hasPermission(Permission.ADMINISTRATOR), "no Discord permissions");
        assertFalse(m.canInteract(mock(Member.class)), "can't act on anyone by role hierarchy");
        assertThrows(UnsupportedOperationException.class, m::getTimeJoined, "nothing else a real member has");
    }

    private static SiteRoles.Facts outsider(boolean overall) {
        return new SiteRoles.Facts(OWNER, false, false, false, Set.of(), null).withOverallAdmin(overall);
    }

    @Test
    void aRuneClanAdminWhoIsNotTheSitesOwnerIsMarkedInItsHistory() {
        SiteRoles.Settings s = SiteRoles.Settings.DEFAULT;
        assertTrue(SiteRoles.onlyAsOverallAdmin(outsider(true), s));
        assertEquals("A RuneClan Admin", TicketAdminApi.siteAuditName(null, outsider(true), s));
        SiteRoles.Facts adminMember = new SiteRoles.Facts(OWNER, true, false, true, Set.of(), null).withOverallAdmin(true);
        assertTrue(SiteRoles.onlyAsOverallAdmin(adminMember, s), "an Admin of the server is still raised to owner only as a RuneClan Admin");
        assertEquals("Name (RuneClan Admin)", TicketAdminApi.siteAuditName("Name", adminMember, s));
    }

    @Test
    void theSitesRealOwnerAndOtherPeopleAreNamedAsBefore() {
        SiteRoles.Settings s = SiteRoles.Settings.DEFAULT;
        SiteRoles.Facts serverOwner = new SiteRoles.Facts(OWNER, true, true, true, Set.of(), null).withOverallAdmin(true);
        assertFalse(SiteRoles.onlyAsOverallAdmin(serverOwner, s), "owns the server: owner in their own right");
        assertEquals("Name", TicketAdminApi.siteAuditName("Name", serverOwner, s));
        assertFalse(SiteRoles.onlyAsOverallAdmin(outsider(false), s));
        assertFalse(SiteRoles.onlyAsOverallAdmin(null, s));
        assertEquals("A Site Admin", TicketAdminApi.siteAuditName(null, outsider(false), s));
        assertEquals("Name", TicketAdminApi.siteAuditName("Name", outsider(false), s));
    }
}
