package com.younglings.bot.lockdown;

import com.younglings.bot.lockdown.LockMath.Snapshot;
import com.younglings.bot.lockdown.LockdownRepository.Config;
import com.younglings.bot.lockdown.LockdownRepository.Lock;
import com.younglings.bot.lockdown.LockdownService.Outcome;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.PermissionOverride;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import net.dv8tion.jda.api.requests.restaction.PermissionOverrideAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The lock arithmetic is in LockMathTest; here JDA is mocked to check what is written down, changed and put back. Nothing touches Discord. */
class LockdownServiceTest {
    private static final long GUILD = 5L;
    private static final long CHANNEL = 50L;
    private static final long EVERYONE = 5L;
    private static final long STAFF = 60L;
    private static final long VIEW = Permission.getRaw(Permission.VIEW_CHANNEL);
    private static final long SEND = Permission.getRaw(Permission.MESSAGE_SEND);

    private LockdownRepository repository;
    private LockdownService service;
    private Guild guild;
    private StandardGuildMessageChannel channel;
    private Role everyone;
    private Role staff;
    private PermissionOverrideAction everyoneAction;
    private PermissionOverrideAction staffAction;

    @BeforeEach
    void setUp() {
        repository = mock(LockdownRepository.class);
        service = new LockdownService(repository);
        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        SelfMember self = mock(SelfMember.class);
        when(self.hasPermission(any(StandardGuildMessageChannel.class), any(Permission[].class))).thenReturn(true);
        when(guild.getSelfMember()).thenReturn(self);
        channel = mock(StandardGuildMessageChannel.class);
        when(channel.getIdLong()).thenReturn(CHANNEL);
        when(channel.getId()).thenReturn("50");
        when(channel.canTalk()).thenReturn(false);
        everyone = role(EVERYONE, true);
        staff = role(STAFF, false);
        when(guild.getPublicRole()).thenReturn(everyone);
        everyoneAction = mock(PermissionOverrideAction.class, Mockito.RETURNS_SELF);
        staffAction = mock(PermissionOverrideAction.class, Mockito.RETURNS_SELF);
        when(channel.upsertPermissionOverride(everyone)).thenReturn(everyoneAction);
        when(channel.upsertPermissionOverride(staff)).thenReturn(staffAction);
        when(repository.addLock(any())).thenReturn(true);
    }

    private Role role(long id, boolean isPublic) {
        Role role = mock(Role.class);
        when(role.getIdLong()).thenReturn(id);
        when(role.isPublicRole()).thenReturn(isPublic);
        when(guild.getRoleById(id)).thenReturn(role);
        return role;
    }

    private void overrideOf(Role role, long allow, long deny) {
        PermissionOverride override = mock(PermissionOverride.class);
        when(override.getAllowedRaw()).thenReturn(allow);
        when(override.getDeniedRaw()).thenReturn(deny);
        when(channel.getPermissionOverride(role)).thenReturn(override);
    }

    @Test
    void lockingWritesDownTheOriginalsFirstThenDeniesSpeakingToEveryoneAndAllowsTheStaffRole() {
        overrideOf(everyone, VIEW | SEND, 0);
        when(repository.config(GUILD)).thenReturn(new Config(GUILD, List.of(CHANNEL), List.of(STAFF)));

        Outcome outcome = service.lock(guild, channel, 9L, "raid", false);

        assertEquals(Outcome.DONE, outcome);
        ArgumentCaptor<Lock> saved = ArgumentCaptor.forClass(Lock.class);
        verify(repository).addLock(saved.capture());
        assertEquals(List.of(new Snapshot(EVERYONE, true, VIEW | SEND, 0), new Snapshot(STAFF, false, 0, 0)), LockMath.decode(saved.getValue().snapshots()));
        verify(everyoneAction).setPermissions(VIEW, LockMath.SPEAK_RAW);
        verify(staffAction).setPermissions(LockMath.KEEP_TALKING_RAW, 0L);
    }

    @Test
    void aChannelThatIsAlreadyLockedIsLeftAloneSoItsOriginalsAreNeverLost() {
        when(repository.lock(GUILD, CHANNEL)).thenReturn(Optional.of(new Lock(GUILD, CHANNEL, 1, Instant.now(), "", false, "")));

        assertEquals(Outcome.ALREADY, service.lock(guild, channel, 9L, null, false));

        verify(repository, never()).addLock(any());
        verify(channel, never()).upsertPermissionOverride(any(Role.class));
    }

    @Test
    void withoutPermissionNothingIsRecordedOrChanged() {
        when(guild.getSelfMember().hasPermission(any(StandardGuildMessageChannel.class), any(Permission[].class))).thenReturn(false);

        assertEquals(Outcome.NO_PERMISSION, service.lock(guild, channel, 9L, null, false));

        verify(repository, never()).addLock(any());
        verify(channel, never()).upsertPermissionOverride(any(Role.class));
    }

    @Test
    void unlockingPutsBackTheExactOverwriteAndRemovesOnesThatDidNotExist() {
        List<Snapshot> before = List.of(new Snapshot(EVERYONE, true, VIEW | SEND, 7), new Snapshot(STAFF, false, 0, 0));
        when(repository.lock(GUILD, CHANNEL)).thenReturn(Optional.of(new Lock(GUILD, CHANNEL, 1, Instant.now(), "", false, LockMath.encode(before))));
        PermissionOverride staffNow = mock(PermissionOverride.class);
        var deleteAction = mock(net.dv8tion.jda.api.requests.restaction.AuditableRestAction.class, Mockito.RETURNS_SELF);
        when(staffNow.delete()).thenReturn(deleteAction);
        when(channel.getPermissionOverride(staff)).thenReturn(staffNow);

        assertEquals(Outcome.DONE, service.unlock(guild, channel, 9L));

        verify(everyoneAction).setPermissions(VIEW | SEND, 7L);
        verify(staffNow).delete();
        verify(staffAction, never()).setPermissions(anyLong(), anyLong());
        verify(repository).removeLock(GUILD, CHANNEL);
    }

    @Test
    void unlockingAChannelThatIsNotLockedDoesNothing() {
        when(repository.lock(GUILD, CHANNEL)).thenReturn(Optional.empty());

        assertEquals(Outcome.NOT_LOCKED, service.unlock(guild, channel, 9L));

        verify(channel, never()).upsertPermissionOverride(any(Role.class));
    }

    @Test
    void lockdownEndOnlyUnlocksWhatTheLockdownLocked() {
        when(repository.locks(GUILD)).thenReturn(List.of(
                new Lock(GUILD, CHANNEL, 1, Instant.now(), "", false, LockMath.encode(List.of(new Snapshot(EVERYONE, false, 0, 0))))));

        int[] result = service.end(guild, 9L);

        assertEquals(0, result[0]);
        verify(repository, never()).removeLock(anyLong(), anyLong());
    }
}
