package com.runeclans.bot.internal;

import com.runeclans.bot.clansite.SiteRoles;
import com.runeclans.bot.notice.BotOwners;
import com.runeclans.bot.permission.OwnerOverride;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    // ---------- the owner-override route (T-445) ----------

    private static final long STRANGER = BASE + 3;

    private record Row(long guildId, String actorId, String name, String method, String route) {}

    private final List<Row> rows = new ArrayList<>();
    private final OwnerOverrideRoute.Audit audit = (g, a, n, m, r, status) -> rows.add(new Row(g, a, n, m, r));

    private static OwnerOverride overrideFor(long owner) {
        BotOwners owners = mock(BotOwners.class);
        when(owners.isKnownOwner(owner)).thenReturn(true);
        return new OwnerOverride(owners);
    }

    private DataObject call(OwnerOverride o, long asker, String method, DataObject body) {
        return OwnerOverrideRoute.handle(o, id -> id == OWNER, () -> body, audit, () -> "owner-name", GUILD, asker, method);
    }

    private static int status(Runnable r) {
        try {
            r.run();
            return 200;
        } catch (TicketAdminApi.ApiError e) {
            return e.status;
        }
    }

    @Test
    void onlyTheBotOwnerMayUseTheRouteWhateverTheySend() {
        OwnerOverride o = overrideFor(OWNER);
        DataObject ack = DataObject.empty().put("acknowledged", true).put("minutes", 60);
        assertEquals(403, status(() -> call(o, STRANGER, "POST", ack)), "a forged lift from anyone else");
        assertEquals(403, status(() -> call(o, STRANGER, "GET", DataObject.empty())));
        assertEquals(403, status(() -> call(o, STRANGER, "DELETE", DataObject.empty())));
        assertEquals(403, status(() -> call(o, 0, "POST", ack)), "nobody signed in");
        assertFalse(o.active(GUILD, STRANGER));
        assertTrue(rows.isEmpty(), "nothing logged for a refused call");
    }

    @Test
    void liftingNeedsTheAcknowledgement() {
        OwnerOverride o = overrideFor(OWNER);
        assertEquals(400, status(() -> call(o, OWNER, "POST", DataObject.empty())));
        assertEquals(400, status(() -> call(o, OWNER, "POST", DataObject.empty().put("acknowledged", false))));
        assertEquals(400, status(() -> call(o, OWNER, "POST", DataObject.empty().put("acknowledged", "yes"))), "only a real true counts");
        assertFalse(o.active(GUILD, OWNER));
        assertTrue(rows.isEmpty());
        assertEquals(405, status(() -> call(o, OWNER, "PUT", DataObject.empty())));
    }

    @Test
    void aLiftIsLoggedWithItsLengthKeptInRangeAndEndingIsLoggedToo() {
        OwnerOverride o = overrideFor(OWNER);
        DataObject state = call(o, OWNER, "POST", DataObject.empty().put("acknowledged", true).put("minutes", 100_000));
        assertTrue(state.getBoolean("active"));
        assertEquals(OwnerOverride.MAX_MINUTES, state.getInt("minutes"), "kept within the range");
        assertEquals(List.of(new Row(GUILD, Long.toString(OWNER), "owner-name" + OwnerOverrideRoute.OVERRIDE_MARK, "POST", "owner-override/240min")), rows);

        call(o, OWNER, "POST", DataObject.empty().put("acknowledged", true));
        assertEquals(OwnerOverride.DEFAULT_MINUTES, call(o, OWNER, "GET", DataObject.empty()).getInt("minutes"), "30 when the website doesn't say");

        DataObject ended = call(o, OWNER, "DELETE", DataObject.empty());
        assertFalse(ended.getBoolean("active"));
        assertTrue(ended.isNull("until"));
        assertEquals(new Row(GUILD, Long.toString(OWNER), "owner-name (bot owner)", "DELETE", "owner-override"), rows.get(rows.size() - 1));
        int before = rows.size();
        call(o, OWNER, "DELETE", DataObject.empty());
        assertEquals(before, rows.size(), "ending nothing logs nothing");
    }

    @Test
    void aLiftThatCantBeLoggedNeverHappens() {
        OwnerOverride o = overrideFor(OWNER);
        OwnerOverrideRoute.Audit broken = (g, a, n, m, r, status) -> {
            throw new IllegalStateException("database down");
        };
        assertThrows(IllegalStateException.class, () -> OwnerOverrideRoute.handle(o, id -> id == OWNER,
                () -> DataObject.empty().put("acknowledged", true), broken, () -> "owner-name", GUILD, OWNER, "POST"));
        assertFalse(o.active(GUILD, OWNER), "restrictions stay in place");
    }

    @Test
    void theStandInIsUsedOnlyForSomeoneNotFoundWhileTheOverrideRuns() {
        Member real = mock(Member.class);
        Member standIn = mock(Member.class);
        assertSame(real, OwnerOverrideRoute.actorFor(real, true, () -> standIn), "a real member stays themselves");
        assertSame(real, OwnerOverrideRoute.actorFor(real, false, () -> standIn));
        assertSame(standIn, OwnerOverrideRoute.actorFor(null, true, () -> standIn));
        assertNull(OwnerOverrideRoute.actorFor(null, false, () -> standIn), "no override: nobody, the dashboard's 403 stands");
        assertEquals("Name" + OwnerOverrideRoute.OVERRIDE_MARK, OwnerOverrideRoute.auditName("Name", true));
        assertEquals("Name", OwnerOverrideRoute.auditName("Name", false));
    }
}
