package com.younglings.bot.permission;

import com.younglings.bot.configure.GuildSettings;
import com.younglings.bot.configure.GuildSettingsService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PermissionGroupServiceTest {
    private static final long GUILD = 1L;

    private PermissionGroupRepository repository;
    private GuildSettingsService legacy;
    private PermissionGroupService service;

    @BeforeEach
    void setUp() {
        repository = mock(PermissionGroupRepository.class);
        legacy = mock(GuildSettingsService.class);
        service = new PermissionGroupService(repository, legacy);
    }

    private static PermissionGroup builtin(String key, boolean includeHigher, Long... roles) {
        return new PermissionGroup(key.hashCode(), GUILD, key, key, true, includeHigher, List.of(roles));
    }

    private static PermissionGroup custom(String key, String name, Long... roles) {
        return new PermissionGroup(key.hashCode(), GUILD, key, name, false, false, List.of(roles));
    }

    private void existing(PermissionGroup... groups) {
        when(repository.list(GUILD)).thenReturn(List.of(groups));
    }

    private static final List<PermissionGroup> BUILTINS = List.of(
            builtin(PermissionGroup.ADMIN, true, 100L), builtin(PermissionGroup.SUPPORT, false), builtin(PermissionGroup.DEVELOPER, false));

    // ---------- who is in a group ----------

    @Test
    void holdingOneOfTheGroupsRolesIsEnough() {
        assertTrue(PermissionGroupService.qualifies(List.of(7L, 8L), 3, List.of(8L, 9L), false, OptionalInt.of(50)));
    }

    @Test
    void aHigherRoleOnlyCountsForAGroupThatIncludesHigherRoles() {
        assertFalse(PermissionGroupService.qualifies(List.of(7L), 60, List.of(8L), false, OptionalInt.of(50)));
        assertTrue(PermissionGroupService.qualifies(List.of(7L), 60, List.of(8L), true, OptionalInt.of(50)));
        assertTrue(PermissionGroupService.qualifies(List.of(7L), 50, List.of(8L), true, OptionalInt.of(50)), "the same rank counts");
        assertFalse(PermissionGroupService.qualifies(List.of(7L), 49, List.of(8L), true, OptionalInt.of(50)));
    }

    @Test
    void aGroupWhoseRolesAreAllGoneIncludesNobodyEvenWithHigherRoles() {
        assertFalse(PermissionGroupService.qualifies(List.of(7L), 99, List.of(8L), true, OptionalInt.empty()));
    }

    @Test
    void someoneWithNoRolesIsNeverInAGroup() {
        assertFalse(PermissionGroupService.qualifies(List.of(), -1, List.of(8L), true, OptionalInt.of(0)));
    }

    @Test
    void aSavedChoiceNamesAGroupOrASingleRoleAndAnythingElseMatchesNobody() {
        existing(BUILTINS.get(0), BUILTINS.get(1), BUILTINS.get(2), custom("cweb", "Web Dev", 300L));
        Guild guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        Role webRole = mock(Role.class);
        when(webRole.getIdLong()).thenReturn(300L);
        when(webRole.getPosition()).thenReturn(5);
        Member member = mock(Member.class);
        when(member.getRoles()).thenReturn(List.of(webRole));
        when(guild.getRoleById(300L)).thenReturn(webRole);

        assertTrue(service.matches(guild, member, "group:cweb"));
        assertTrue(service.matches(guild, member, "role:300"));
        assertFalse(service.matches(guild, member, "role:301"));
        assertFalse(service.matches(guild, member, "group:deleted"), "a group that no longer exists fails closed");
        assertFalse(service.matches(guild, member, "role:not-a-number"));
        assertFalse(service.matches(guild, member, "everyone"));
        assertFalse(service.matches(guild, member, null));
    }

    // ---------- first look at a server ----------

    @Test
    void theBuiltInGroupsAreSeededFromTheServersOldSingleRoles() {
        when(repository.list(GUILD)).thenReturn(List.of(), BUILTINS);
        when(legacy.getEffective(GUILD)).thenReturn(new GuildSettings(GUILD, null, 100L, null, null, null, null, null, null, true, null, 200L, 300L));

        service.groups(GUILD);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PermissionGroup>> seeded = ArgumentCaptor.forClass(List.class);
        verify(repository).createMissing(eq(GUILD), seeded.capture());
        assertEquals(List.of(100L), seeded.getValue().get(0).roleIds());
        assertTrue(seeded.getValue().get(0).includeHigher(), "Admin has always included roles above it");
        assertEquals(List.of(200L), seeded.getValue().get(1).roleIds());
        assertFalse(seeded.getValue().get(1).includeHigher());
        assertEquals(List.of(300L), seeded.getValue().get(2).roleIds());
    }

    @Test
    void aServerWithNothingSetStartsWithEmptyBuiltInGroups() {
        when(repository.list(GUILD)).thenReturn(List.of(), BUILTINS);
        when(legacy.getEffective(GUILD)).thenReturn(new GuildSettings(GUILD, null, null, null, null, null, null, null, null, true, null, null, null));

        service.groups(GUILD);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PermissionGroup>> seeded = ArgumentCaptor.forClass(List.class);
        verify(repository).createMissing(eq(GUILD), seeded.capture());
        assertTrue(seeded.getValue().stream().allMatch(g -> g.roleIds().isEmpty()));
    }

    @Test
    void aServerThatAlreadyHasItsGroupsIsNotSeededAgain() {
        existing(BUILTINS.get(0), BUILTINS.get(1), BUILTINS.get(2));

        service.groups(GUILD);
        service.groups(GUILD);

        verify(repository, never()).createMissing(anyLong(), anyList());
    }

    // ---------- saving ----------

    private void withBuiltins() {
        existing(BUILTINS.get(0), BUILTINS.get(1), BUILTINS.get(2), custom("cweb", "Web Dev", 300L));
    }

    @SuppressWarnings("unchecked")
    private List<PermissionGroup> saved() {
        ArgumentCaptor<List<PermissionGroup>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).replaceAll(eq(GUILD), captor.capture());
        return captor.getValue();
    }

    @Test
    void aNewGroupGetsAKeyAndSeveralRoles() {
        withBuiltins();

        service.save(GUILD, List.of(new PermissionGroupService.Draft("cweb", "Web Dev", false, List.of(300L)),
                new PermissionGroupService.Draft(null, "Discord Dev", false, List.of(400L, 401L, 400L))));

        List<PermissionGroup> target = saved();
        PermissionGroup created = target.stream().filter(g -> g.name().equals("Discord Dev")).findFirst().orElseThrow();
        assertTrue(created.key().startsWith("c"));
        assertEquals(List.of(400L, 401L), created.roleIds(), "a role listed twice counts once");
        assertEquals(5, target.size());
    }

    @Test
    void aGroupLeftOutOfASaveIsDeletedButTheBuiltInsAlwaysStay() {
        withBuiltins();

        service.save(GUILD, List.of());

        List<PermissionGroup> target = saved();
        assertEquals(3, target.size());
        assertTrue(target.stream().allMatch(PermissionGroup::builtin));
    }

    @Test
    void aBuiltInGroupKeepsItsNameButTakesNewRoles() {
        withBuiltins();

        service.save(GUILD, List.of(new PermissionGroupService.Draft(PermissionGroup.ADMIN, "Boss", true, List.of(100L, 101L))));

        PermissionGroup admin = saved().getFirst();
        assertEquals("admin", admin.name());
        assertEquals(List.of(100L, 101L), admin.roleIds());
    }

    @Test
    void namesMustBeUniqueIgnoringCaseAndCannotReuseABuiltInName() {
        existing(BUILTINS.get(0), BUILTINS.get(1), BUILTINS.get(2));

        var thrown = assertThrows(PermissionGroupService.InvalidGroupsException.class, () -> service.save(GUILD, List.of(
                new PermissionGroupService.Draft(null, "web dev", false, List.of()),
                new PermissionGroupService.Draft(null, "WEB DEV", false, List.of()),
                new PermissionGroupService.Draft(null, "admin", false, List.of()))));

        assertEquals(2, thrown.problems().size());
        verify(repository, never()).replaceAll(anyLong(), anyList());
    }

    @Test
    void blankAndOverlongNamesAndTooManyRolesAreRefused() {
        withBuiltins();
        List<Long> tooMany = java.util.stream.LongStream.rangeClosed(1, 26).boxed().toList();

        var thrown = assertThrows(PermissionGroupService.InvalidGroupsException.class, () -> service.save(GUILD, List.of(
                new PermissionGroupService.Draft(null, "   ", false, List.of()),
                new PermissionGroupService.Draft(null, "x".repeat(33), false, List.of()),
                new PermissionGroupService.Draft(null, "Big", false, tooMany))));

        assertEquals(3, thrown.problems().size());
    }

    @Test
    void aGroupThatWasRemovedInTheMeantimeIsReportedNotRecreated() {
        withBuiltins();

        var thrown = assertThrows(PermissionGroupService.InvalidGroupsException.class, () -> service.save(GUILD,
                List.of(new PermissionGroupService.Draft("cgone", "Ghost", false, List.of()))));

        assertTrue(thrown.problems().getFirst().contains("Reload"));
    }

    @Test
    void setRolesReplacesOnlyThatGroupsRoles() {
        withBuiltins();

        service.setRoles(GUILD, PermissionGroup.SUPPORT, List.of(55L));

        List<PermissionGroup> target = saved();
        assertEquals(List.of(55L), target.stream().filter(g -> g.key().equals("support")).findFirst().orElseThrow().roleIds());
        assertEquals(List.of(100L), target.getFirst().roleIds());
        assertEquals(List.of(300L), target.stream().filter(g -> g.key().equals("cweb")).findFirst().orElseThrow().roleIds());
    }
}
