package com.younglings.bot.roletools;

import com.younglings.bot.configure.GuildSettings;
import com.younglings.bot.configure.GuildSettingsService;
import com.younglings.bot.permission.DashboardAccess;
import com.younglings.bot.permission.PermissionGroup;
import com.younglings.bot.permission.PermissionGroupService;
import com.younglings.bot.roletools.RoleRules.Mode;
import com.younglings.bot.roletools.RoleRules.Status;
import com.younglings.bot.roletools.RoleToolkitRepository.Job;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction;
import net.dv8tion.jda.api.requests.restaction.CacheRestAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Everything here is mocks: no Discord and no database, so no real member's role can be touched. */
class RoleToolkitServiceTest {
    private static final long GUILD = 10;
    private static final long SOURCE = 100;
    private static final long TARGET = 200;
    private static final long CLAN_ROLE = 300;

    private RoleToolkitRepository repository;
    private GuildSettingsService settings;
    private PermissionGroupService groups;
    private DashboardAccess access;
    private RoleToolkitService service;
    private Guild guild;
    private Member admin;
    private Role source;
    private Role target;
    private Role clanRole;

    @BeforeEach
    void setUp() {
        repository = mock(RoleToolkitRepository.class);
        settings = mock(GuildSettingsService.class);
        groups = mock(PermissionGroupService.class);
        access = mock(DashboardAccess.class);
        service = spy(new RoleToolkitService(repository, settings, groups, access));
        service.pauseMillis = 0;

        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        SelfMember self = mock(SelfMember.class);
        when(guild.getSelfMember()).thenReturn(self);
        when(self.canInteract(any(Member.class))).thenReturn(true);
        when(self.canInteract(any(Role.class))).thenReturn(true);
        admin = member(1);
        when(access.tierOf(guild, admin)).thenReturn(DashboardAccess.Tier.ADMIN);
        source = role(SOURCE, "Source");
        target = role(TARGET, "Target");
        clanRole = role(CLAN_ROLE, "Member");
        when(guild.getRoleById(SOURCE)).thenReturn(source);
        when(guild.getRoleById(TARGET)).thenReturn(target);
        when(guild.getRoleById(CLAN_ROLE)).thenReturn(clanRole);
        when(settings.getEffective(GUILD)).thenReturn(new GuildSettings(GUILD, "Clan", null, null, null, CLAN_ROLE, null, null, null, true, null, null, null));
        when(groups.groups(GUILD)).thenReturn(List.of());
        when(repository.allowed(GUILD)).thenReturn(Map.of());
    }

    private Member member(long id) {
        Member m = mock(Member.class);
        User user = mock(User.class);
        when(m.getUser()).thenReturn(user);
        when(m.getIdLong()).thenReturn(id);
        when(m.getRoles()).thenReturn(List.of());
        when(m.canInteract(any(Role.class))).thenReturn(true);
        return m;
    }

    private Role role(long id, String name) {
        Role r = mock(Role.class);
        when(r.getIdLong()).thenReturn(id);
        when(r.getName()).thenReturn(name);
        when(r.isPublicRole()).thenReturn(false);
        when(r.isManaged()).thenReturn(false);
        when(r.getPermissions()).thenReturn(java.util.EnumSet.noneOf(net.dv8tion.jda.api.Permission.class));
        return r;
    }

    private Job job(Status status) {
        return new Job(7, GUILD, Mode.ADD, SOURCE, TARGET, 1, status, 2, 0, 0, Instant.EPOCH);
    }

    // ---------- the dry run ----------

    @Test
    void theDryRunStoresWhoWouldChangeAndChangesNoRole() throws Exception {
        Member holder = member(5);
        Member already = member(6);
        when(holder.getRoles()).thenReturn(List.of(source));
        when(already.getRoles()).thenReturn(List.of(source, target));
        doReturnMembers(List.of(holder, already));
        when(repository.plan(eq(GUILD), eq(Mode.ADD), eq(SOURCE), eq(TARGET), eq(1L), any())).thenReturn(job(Status.PLANNED));

        Job planned = service.planBulk(guild, admin, Mode.ADD, source, target);

        assertEquals(Status.PLANNED, planned.status());
        verify(repository).plan(GUILD, Mode.ADD, SOURCE, TARGET, 1L, List.of(5L));
        verify(guild, never()).addRoleToMember(any(Member.class), any(Role.class));
        verify(guild, never()).removeRoleFromMember(any(Member.class), any(Role.class));
        verify(guild, never()).modifyMemberRoles(any(Member.class), any(java.util.Collection.class), any(java.util.Collection.class));
    }

    private void doReturnMembers(List<Member> members) throws Exception {
        org.mockito.Mockito.doReturn(members).when(service).membersWith(guild, source);
    }

    @Test
    void onlyAnAdminCanPlanAndTheClanSyncRolesAreRefused() throws Exception {
        Member junior = member(2);
        when(access.tierOf(guild, junior)).thenReturn(DashboardAccess.Tier.NONE);

        assertThrows(SecurityException.class, () -> service.planBulk(guild, junior, Mode.ADD, source, target));
        assertThrows(IllegalArgumentException.class, () -> service.planBulk(guild, admin, Mode.ADD, source, clanRole));
        assertThrows(IllegalArgumentException.class, () -> service.planBulk(guild, admin, Mode.ADD, source, source));
        verify(repository, never()).plan(anyLong(), any(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void aPlanOverTheLimitIsRefused() throws Exception {
        List<Member> many = new java.util.ArrayList<>();
        for (int i = 0; i < RoleRules.MAX_BULK_MEMBERS + 1; i++) many.add(member(1000 + i));
        doReturnMembers(many);

        assertThrows(IllegalArgumentException.class, () -> service.planBulk(guild, admin, Mode.ADD, source, target));
        verify(repository, never()).plan(anyLong(), any(), anyLong(), anyLong(), anyLong(), any());
    }

    // ---------- pressing Start ----------

    @Test
    void startingNeedsAPlannedOrPausedJobAndNoOtherRunningJob() {
        assertNotNull(service.start(guild, job(Status.DONE)));
        assertNotNull(service.start(guild, job(Status.CANCELLED)));
        assertNotNull(service.start(guild, job(Status.RUNNING)));
        when(repository.hasRunning(GUILD)).thenReturn(true);
        assertTrue(service.start(guild, job(Status.PLANNED)).contains("Another bulk job"));
        verify(repository, never()).move(anyLong(), any(), eq(Status.RUNNING));
    }

    @Test
    void startingARefusedTargetRoleIsRefusedEvenForAnOldPlan() {
        Job onClanRole = new Job(7, GUILD, Mode.ADD, SOURCE, CLAN_ROLE, 1, Status.PLANNED, 2, 0, 0, Instant.EPOCH);

        assertTrue(service.start(guild, onClanRole).contains("clan role sync"));
        verify(repository, never()).move(anyLong(), any(), eq(Status.RUNNING));
    }

    @Test
    void nothingStartsByItselfARestartLeavesAJobPausedForAPersonToResume() {
        // the repository pauses running jobs when it starts (see its schema statements); the service then only ever acts on a call to start().
        when(repository.get(GUILD, 7)).thenReturn(Optional.of(job(Status.PAUSED)));

        service.run(GUILD, 7);

        verify(repository, never()).pending(anyLong(), anyInt());
        verify(repository, never()).finishMember(anyLong(), anyLong(), anyBoolean());
        verify(guild, never()).addRoleToMember(any(Member.class), any(Role.class));
    }

    // ---------- the run ----------

    @Test
    void aRunningJobChangesEachPendingMemberAfterLookingAgain() {
        JDA jda = mock(JDA.class);
        when(jda.getGuildById(GUILD)).thenReturn(guild);
        service.useJda(jda);
        Member one = member(5);
        when(one.getRoles()).thenReturn(List.of(source));
        CacheRestAction<Member> lookup = lookup(one);
        when(guild.retrieveMemberById(5L)).thenReturn(lookup);
        AuditableRestAction<Void> action = changeAction();
        when(guild.addRoleToMember(one, target)).thenReturn(action);
        when(repository.get(GUILD, 7)).thenReturn(Optional.of(job(Status.RUNNING)));
        when(repository.pending(7, 50)).thenReturn(List.of(5L)).thenReturn(List.of());

        service.run(GUILD, 7);

        verify(guild).addRoleToMember(one, target);
        verify(repository).finishMember(7, 5, true);
        verify(repository).move(7, Status.RUNNING, Status.DONE);
    }

    @Test
    void aMemberWhoNoLongerNeedsTheChangeIsLeftAloneAndCounted() {
        JDA jda = mock(JDA.class);
        when(jda.getGuildById(GUILD)).thenReturn(guild);
        service.useJda(jda);
        Member one = member(5);
        when(one.getRoles()).thenReturn(List.of(source, target)); // got the target role since the dry run
        CacheRestAction<Member> found = lookup(one);
        when(guild.retrieveMemberById(5L)).thenReturn(found);
        when(repository.get(GUILD, 7)).thenReturn(Optional.of(job(Status.RUNNING)));
        when(repository.pending(7, 50)).thenReturn(List.of(5L)).thenReturn(List.of());

        service.run(GUILD, 7);

        verify(guild, never()).addRoleToMember(any(Member.class), any(Role.class));
        verify(repository).finishMember(7, 5, true);
    }

    @Test
    void aJobPausedByAPersonStopsBeforeTheNextMember() {
        JDA jda = mock(JDA.class);
        when(jda.getGuildById(GUILD)).thenReturn(guild);
        service.useJda(jda);
        when(repository.get(GUILD, 7)).thenReturn(Optional.of(job(Status.RUNNING)), Optional.of(job(Status.PAUSED)));
        when(repository.pending(7, 50)).thenReturn(List.of(5L, 6L));

        service.run(GUILD, 7);

        verify(repository, never()).finishMember(anyLong(), anyLong(), anyBoolean());
        verify(guild, never()).addRoleToMember(any(Member.class), any(Role.class));
    }

    @Test
    void ifTheTargetRoleBecomesRefusedMidJobItPausesInsteadOfChangingAnyone() {
        JDA jda = mock(JDA.class);
        when(jda.getGuildById(GUILD)).thenReturn(guild);
        service.useJda(jda);
        when(target.isManaged()).thenReturn(true);
        when(repository.get(GUILD, 7)).thenReturn(Optional.of(job(Status.RUNNING)));

        service.run(GUILD, 7);

        verify(repository).move(7, Status.RUNNING, Status.PAUSED);
        verify(repository, never()).finishMember(anyLong(), anyLong(), anyBoolean());
    }

    // ---------- /role ----------

    @Test
    void aMemberOfAGroupMayOnlyUseTheRolesOnItsList() {
        Member junior = member(2);
        when(access.tierOf(guild, junior)).thenReturn(DashboardAccess.Tier.NONE);
        PermissionGroup mods = new PermissionGroup(1, GUILD, "mods", "Mods", false, false, List.of());
        when(groups.groups(GUILD)).thenReturn(List.of(mods));
        when(groups.isMember(guild, junior, "mods")).thenReturn(true);
        when(repository.allowed(GUILD)).thenReturn(Map.of("mods", Set.of(TARGET)));
        Member victim = member(3);

        assertTrue(service.refuseChange(guild, junior, victim, target).isEmpty(), "listed role");
        assertTrue(service.refuseChange(guild, junior, victim, source).isPresent(), "role not on the list");
        assertTrue(service.refuseChange(guild, junior, victim, clanRole).isPresent(), "a clan sync role is refused even if someone listed it");
    }

    @Test
    void aMemberOfNoGroupAndNotAnAdminMayNotUseAnyRole() {
        Member nobody = member(2);
        when(access.tierOf(guild, nobody)).thenReturn(DashboardAccess.Tier.NONE);

        assertTrue(service.refuseChange(guild, nobody, member(3), target).isPresent());
        assertNull(service.roleRefusal(guild, target));
    }

    @Test
    void anAdminMayUseAnySafeRoleButStillNotAClanSyncRoleOrOneAboveTheBot() {
        Member victim = member(3);
        assertTrue(service.refuseChange(guild, admin, victim, target).isEmpty());
        assertTrue(service.refuseChange(guild, admin, victim, clanRole).isPresent());
        when(target.isManaged()).thenReturn(true);
        assertTrue(service.refuseChange(guild, admin, victim, target).isPresent());
    }

    @Test
    void aMemberTheBotCannotReachIsRefused() {
        Member victim = member(3);
        when(guild.getSelfMember().canInteract(victim)).thenReturn(false);

        assertTrue(service.refuseChange(guild, admin, victim, target).isPresent());
    }

    // ---------- small mock helpers ----------

    @SuppressWarnings("unchecked")
    private static CacheRestAction<Member> lookup(Member member) {
        CacheRestAction<Member> action = mock(CacheRestAction.class);
        when(action.complete()).thenReturn(member);
        return action;
    }

    @SuppressWarnings("unchecked")
    private static AuditableRestAction<Void> changeAction() {
        AuditableRestAction<Void> action = mock(AuditableRestAction.class);
        when(action.reason(any())).thenReturn(action);
        when(action.complete()).thenReturn(null);
        return action;
    }
}
