package com.runeclans.bot.serverreminder;

import com.runeclans.bot.serverreminder.ServerReminder.Status;
import com.runeclans.bot.serverreminder.ServerReminderService.ReminderException;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServerReminderTest {
    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");
    private static final long GUILD = 5L;

    private ServerReminderRepository repository;
    private ServerReminderService service;
    private Guild guild;
    private GuildMessageChannel channel;
    private net.dv8tion.jda.api.requests.restaction.MessageCreateAction send;

    @BeforeEach
    void setUp() {
        repository = mock(ServerReminderRepository.class);
        service = new ServerReminderService(repository);
        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        SelfMember self = mock(SelfMember.class);
        when(guild.getSelfMember()).thenReturn(self);
        channel = mock(GuildMessageChannel.class);
        when(channel.canTalk()).thenReturn(true);
        when(channel.getName()).thenReturn("general");
        when(guild.getChannelById(GuildMessageChannel.class, 9L)).thenReturn(channel);
        send = mock(net.dv8tion.jda.api.requests.restaction.MessageCreateAction.class, org.mockito.Mockito.RETURNS_SELF);
        when(channel.sendMessage(any(CharSequence.class))).thenReturn(send);
        when(repository.add(anyLong(), anyLong(), any(), any(), any(), any(), anyLong(), any())).thenReturn(1L);
        when(repository.get(GUILD, 1L)).thenReturn(java.util.Optional.of(reminder(null, null)));
    }

    private static ServerReminder reminder(Long role, Duration every) {
        return new ServerReminder(1, GUILD, 9L, "Cap party tonight", role, every, NOW, Status.ACTIVE, 7, "Metorrite", null, 0, null);
    }

    @Test
    void aRepeatingReminderMovesToTheFirstFutureSlot() {
        Duration day = Duration.ofDays(1);
        assertEquals(NOW.plus(day), ServerReminder.nextAfter(NOW, day, NOW.plusSeconds(5)));
        assertEquals(NOW.plus(day.multipliedBy(4)), ServerReminder.nextAfter(NOW, day, NOW.plus(day.multipliedBy(3)).plusSeconds(1)), "a long outage gives one post, not a burst");
        assertEquals(NOW.plus(day.multipliedBy(2)), ServerReminder.nextAfter(NOW, day, NOW.plus(day)), "exactly on a slot waits for the next one");
    }

    @Test
    void repeatsCannotBeMoreOftenThanAnHourOrLessOftenThanSixtyDays() {
        assertNotNull(ServerReminder.everyProblem(Duration.ofMinutes(59)));
        assertNull(ServerReminder.everyProblem(Duration.ofHours(1)));
        assertNull(ServerReminder.everyProblem(null));
        assertNotNull(ServerReminder.everyProblem(Duration.ofDays(61)));
    }

    @Test
    void textMustBePresentAndNotTooLong() {
        assertNotNull(ServerReminder.textProblem("   "));
        assertNotNull(ServerReminder.textProblem("x".repeat(1001)));
        assertNull(ServerReminder.textProblem("fine"));
    }

    @Test
    void theRolePingIsInFrontOfTheText() {
        assertEquals("<@&70> Cap party tonight", ServerReminderService.messageOf(reminder(70L, null)));
        assertEquals("Cap party tonight", ServerReminderService.messageOf(reminder(null, null)));
    }

    @Test
    void aReminderInThePastOrWithABadRepeatIsRefusedBeforeAnythingIsSaved() {
        assertThrows(ReminderException.class, () -> service.create(guild, 7, "M", 9L, "hi there", null, Instant.now().minusSeconds(60), null));
        assertThrows(ReminderException.class, () -> service.create(guild, 7, "M", 9L, "hi there", null, Instant.now().plusSeconds(3600), Duration.ofMinutes(5)));
        verify(repository, never()).add(anyLong(), anyLong(), any(), any(), any(), any(), anyLong(), any());
    }

    @Test
    void everyoneCannotBePingedAndAnUnmentionableRoleIsExplained() {
        Role everyone = mock(Role.class);
        when(everyone.isPublicRole()).thenReturn(true);
        when(guild.getRoleById(5L)).thenReturn(everyone);
        Role quiet = mock(Role.class);
        when(quiet.getName()).thenReturn("Quiet");
        when(quiet.isMentionable()).thenReturn(false);
        when(guild.getRoleById(70L)).thenReturn(quiet);
        when(guild.getSelfMember().hasPermission(Permission.MESSAGE_MENTION_EVERYONE)).thenReturn(false);

        assertTrue(assertThrows(ReminderException.class, () -> service.create(guild, 7, "M", 9L, "hi there", 5L, Instant.now().plusSeconds(3600), null)).getMessage().contains("@everyone"));
        assertTrue(assertThrows(ReminderException.class, () -> service.create(guild, 7, "M", 9L, "hi there", 70L, Instant.now().plusSeconds(3600), null)).getMessage().contains("mentionable"));
    }

    @Test
    void aServerCanOnlyHaveSomeActiveReminders() {
        when(repository.activeCount(GUILD)).thenReturn(ServerReminder.MAX_ACTIVE_PER_GUILD);

        assertThrows(ReminderException.class, () -> service.create(guild, 7, "M", 9L, "hi there", null, Instant.now().plusSeconds(3600), null));
    }

    @Test
    void aGoodReminderIsSaved() {
        ServerReminder made = service.create(guild, 7, "Metorrite", 9L, "  hi there  ", null, Instant.now().plusSeconds(3600), Duration.ofDays(1));

        assertEquals(1, made.id());
        verify(repository).add(eq(GUILD), eq(9L), eq("hi there"), eq(null), eq(Duration.ofDays(1)), any(), eq(7L), eq("Metorrite"));
    }

    @Test
    void aReminderWhoseChannelIsGoneIsStoppedNotRetried() {
        when(guild.getChannelById(GuildMessageChannel.class, 9L)).thenReturn(null);

        service.post(guild, reminder(null, Duration.ofDays(1)));

        verify(repository).fail(eq(1L), any());
        verify(repository, never()).sent(anyLong(), any(), any());
    }

    @Test
    void aOneOffIsFinishedAndARepeatingOneMovedOnBeforeThePostIsSent() {
        service.post(guild, reminder(null, null));
        verify(repository).sent(eq(1L), any(), eq(null));

        service.post(guild, reminder(null, Duration.ofDays(1)));
        verify(repository).sent(eq(1L), any(), any(Instant.class));
    }

    @Test
    void onlyTheChosenRoleCanBePingedWhateverTheTextSays() {
        service.post(guild, new ServerReminder(1, GUILD, 9L, "@everyone look", 70L, null, NOW, Status.ACTIVE, 7, "M", null, 0, null));

        verify(channel).sendMessage("<@&70> @everyone look");
        // T-136: nothing pings by type (not even other roles in the text); the chosen role is the only one allowed
        verify(send, org.mockito.Mockito.atLeastOnce()).setAllowedMentions(java.util.EnumSet.noneOf(net.dv8tion.jda.api.entities.Message.MentionType.class));
        verify(send, never()).setAllowedMentions(java.util.EnumSet.of(net.dv8tion.jda.api.entities.Message.MentionType.ROLE));
        verify(send).mentionRoles(70L);
    }

    @Test
    void withoutARoleNothingAtAllCanBePinged() {
        service.post(guild, reminder(null, null));

        verify(send).setAllowedMentions(java.util.EnumSet.noneOf(net.dv8tion.jda.api.entities.Message.MentionType.class));
        verify(send, never()).mentionRoles(anyLong());
    }
}
