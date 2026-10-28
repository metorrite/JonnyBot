package com.runeclans.bot.servertag;

import com.runeclans.bot.moderation.ModAction;
import com.runeclans.bot.moderation.ModCase;
import com.runeclans.bot.moderation.ModService;
import com.runeclans.bot.permission.AdminRoleFilter;
import com.runeclans.bot.servertag.ServerTagConfig.Action;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The server-tag decision and its listener: JDA objects are mocks, so nobody real is ever acted on. */
class ServerTagTest {
    private static final long GUILD = 10, FLAGGED = 777, OTHER = 888, EXEMPT = 300;

    private static ServerTagConfig on(Action action) {
        return new ServerTagConfig(GUILD, true, List.of(FLAGGED), action, 60, 500L, List.of(EXEMPT));
    }

    private static boolean act(ServerTagConfig c, Long primary, boolean identity, boolean bot, boolean owner, boolean staff, List<Long> roles) {
        return ServerTagRules.shouldAct(c, primary, identity, bot, owner, staff, roles);
    }

    @Test
    void itIsOffByDefaultAndTheDefaultActionOnlyAlerts() {
        ServerTagConfig d = ServerTagConfig.defaults(GUILD);
        assertFalse(d.enabled());
        assertEquals(Action.ALERT, d.action());
        assertFalse(act(d, FLAGGED, true, false, false, false, List.of()));
    }

    @Test
    void itActsOnlyOnAFlaggedTagThatTheMemberHasChosenToShow() {
        ServerTagConfig c = on(Action.KICK);
        assertTrue(act(c, FLAGGED, true, false, false, false, List.of()));
        assertFalse(act(c, OTHER, true, false, false, false, List.of()), "an unflagged server");
        assertFalse(act(c, null, true, false, false, false, List.of()), "no tag");
        assertFalse(act(c, FLAGGED, false, false, false, false, List.of()), "a tag they have hidden is stale");
    }

    @Test
    void botsTheOwnerStaffAndExemptRolesAreNeverActedOn() {
        ServerTagConfig c = on(Action.BAN);
        assertFalse(act(c, FLAGGED, true, true, false, false, List.of()));
        assertFalse(act(c, FLAGGED, true, false, true, false, List.of()));
        assertFalse(act(c, FLAGGED, true, false, false, true, List.of()));
        assertFalse(act(c, FLAGGED, true, false, false, false, List.of(EXEMPT)));
    }

    @Test
    void settingsAreCheckedBeforeTheyCanBeSwitchedOn() {
        assertFalse(new ServerTagConfig(GUILD, true, List.of(), Action.KICK, 60, null, List.of()).problems().isEmpty(), "nothing flagged");
        assertFalse(new ServerTagConfig(GUILD, true, List.of(GUILD), Action.KICK, 60, null, List.of()).problems().isEmpty(), "own server");
        assertFalse(new ServerTagConfig(GUILD, true, List.of(FLAGGED), Action.ALERT, 60, null, List.of()).problems().isEmpty(), "alert-only without a channel");
        assertFalse(new ServerTagConfig(GUILD, true, List.of(FLAGGED), Action.TIMEOUT, 0, null, List.of()).problems().isEmpty());
        assertTrue(on(Action.ALERT).problems().isEmpty());
        assertTrue(new ServerTagConfig(GUILD, false, List.of(), Action.ALERT, 60, null, List.of()).problems().isEmpty(), "off needs nothing");
    }

    @Test
    void theReasonKeepsTheMembersOwnTagShortAndOutOfTheWay() {
        assertEquals("Server tag moderation: shows the tag RAID of a flagged server (777)", ServerTagRules.reason("RAID", 777));
        assertFalse(ServerTagRules.reason("@everyone-and-more", 777).contains("@everyone"), "cut at 8 characters, so a ping can't be smuggled in");
        assertFalse(ServerTagRules.reason("x".repeat(100), 777).contains("x".repeat(9)));
    }

    // ---- the listener ----

    private ServerTagRepository repository;
    private ModService mod;
    private AdminRoleFilter admins;
    private ServerTagListener listener;
    private Guild guild;
    private Member member;
    private User user;

    @BeforeEach
    void setUp() {
        repository = mock(ServerTagRepository.class);
        mod = mock(ModService.class);
        admins = mock(AdminRoleFilter.class);
        listener = new ServerTagListener(repository, mod, admins);
        guild = mock(Guild.class);
        when(guild.getId()).thenReturn("10");
        when(guild.getIdLong()).thenReturn(GUILD);
        when(guild.getOwnerIdLong()).thenReturn(1L);
        user = mock(User.class);
        when(user.isBot()).thenReturn(false);
        member = mock(Member.class);
        when(member.getUser()).thenReturn(user);
        when(member.getId()).thenReturn("4");
        when(member.getIdLong()).thenReturn(4L);
        when(member.getRoles()).thenReturn(List.of());
        when(mod.execute(any(), any(), any(), any(), any(), anyString(), any(), anyInt(), anyString())).thenReturn(CompletableFuture.completedFuture(
                new ModService.Outcome(true, "done", new ModCase(GUILD, 3, 4, "x", 2, "Bot", ModAction.KICK, "r", null, ModCase.SERVER_TAG, java.time.Instant.now(), null, null, null))));
    }

    private void tag(long guildId, boolean identity) {
        User.PrimaryGuild primary = new User.PrimaryGuild(guildId, identity, "RAID", "badge");
        when(user.getPrimaryGuild()).thenReturn(primary);
    }

    @Test
    void aFlaggedTagGoesThroughTheModerationServiceWithTheServerTagSource() {
        when(repository.get(GUILD)).thenReturn(on(Action.KICK));
        tag(FLAGGED, true);
        listener.check(guild, member);
        verify(mod).execute(eq(guild), eq(null), eq(user), eq(member), eq(ModAction.KICK), eq(ServerTagRules.reason("RAID", FLAGGED)), eq(null), eq(0), eq(ModCase.SERVER_TAG));
    }

    @Test
    void alertOnlyNeverCallsTheModerationService() {
        when(repository.get(GUILD)).thenReturn(on(Action.ALERT));
        tag(FLAGGED, true);
        listener.check(guild, member);
        verify(mod, never()).execute(any(), any(), any(), any(), any(), anyString(), any(), anyInt(), anyString());
    }

    @Test
    void nothingHappensWhenOffUnflaggedHiddenOrForStaff() {
        when(repository.get(GUILD)).thenReturn(ServerTagConfig.defaults(GUILD));
        tag(FLAGGED, true);
        listener.check(guild, member);

        when(repository.get(GUILD)).thenReturn(on(Action.BAN));
        tag(OTHER, true);
        listener.check(guild, member);
        tag(FLAGGED, false);
        listener.check(guild, member);
        tag(FLAGGED, true);
        when(member.hasPermission(Permission.ADMINISTRATOR)).thenReturn(true);
        listener.check(guild, member);
        verify(mod, never()).execute(any(), any(), any(), any(), any(), anyString(), any(), anyInt(), anyString());
    }

    @Test
    void aProfileThatChangesAgainRightAfterIsNotActedOnTwice() {
        when(repository.get(GUILD)).thenReturn(on(Action.KICK));
        tag(FLAGGED, true);
        listener.check(guild, member);
        listener.check(guild, member);
        verify(mod, times(1)).execute(any(), any(), any(), any(), any(), anyString(), any(), anyInt(), anyString());
    }
}
