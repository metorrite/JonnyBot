package com.runeclans.bot.accountgate;

import com.runeclans.bot.accountgate.AccountGateConfig.Action;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Nothing here touches Discord: JDA is mocked, and the point is which actions are (not) taken. */
class AccountGateListenerTest {
    private static final long GUILD = 5L;
    private static final long USER = 77L;
    private AccountGateRepository repository;
    private AccountGateListener listener;
    private GuildMemberJoinEvent event;
    private Guild guild;
    private Member member;
    private User user;

    @BeforeEach
    void setUp() {
        repository = mock(AccountGateRepository.class);
        listener = new AccountGateListener(repository);
        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        SelfMember self = mock(SelfMember.class);
        when(self.hasPermission(Permission.BAN_MEMBERS)).thenReturn(true);
        when(self.hasPermission(Permission.KICK_MEMBERS)).thenReturn(true);
        when(self.canInteract(any(Member.class))).thenReturn(true);
        when(guild.getSelfMember()).thenReturn(self);
        user = mock(User.class, Mockito.RETURNS_DEEP_STUBS);
        when(user.isBot()).thenReturn(false);
        when(user.getIdLong()).thenReturn(USER);
        when(user.getName()).thenReturn("freediscordnitro");
        when(user.getEffectiveName()).thenReturn("Free Nitro");
        when(user.getTimeCreated()).thenReturn(OffsetDateTime.now().minusYears(3));
        member = mock(Member.class);
        when(member.getUser()).thenReturn(user);
        when(member.getEffectiveName()).thenReturn("Free Nitro");
        event = mock(GuildMemberJoinEvent.class);
        when(event.getGuild()).thenReturn(guild);
        when(event.getUser()).thenReturn(user);
        when(event.getMember()).thenReturn(member);
    }

    private AccountGateConfig config(boolean rulesOn, Action action, Set<Long> exempt) {
        return new AccountGateConfig(GUILD, false, 7, Action.ALERT, null, rulesOn, action, List.of("free*nitro"), exempt);
    }

    @Test
    void withNameRulesOffAMatchingNameIsLeftAlone() {
        when(repository.get(GUILD)).thenReturn(config(false, Action.BAN, Set.of()));

        listener.onGuildMemberJoin(event);

        verify(guild, never()).ban(any(Member.class), anyInt(), any(TimeUnit.class));
        verify(guild, never()).kick(any(Member.class));
    }

    @Test
    void anExemptPersonIsNeverTouchedEvenWhenTheirNameMatches() {
        when(repository.get(GUILD)).thenReturn(config(true, Action.BAN, Set.of(USER)));

        listener.onGuildMemberJoin(event);

        verify(guild, never()).ban(any(Member.class), anyInt(), any(TimeUnit.class));
        verify(guild, never()).kick(any(Member.class));
    }

    @Test
    void aNameThatMatchesWithTheAlertActionOnlyReports() {
        when(repository.get(GUILD)).thenReturn(config(true, Action.ALERT, Set.of()));

        listener.onGuildMemberJoin(event);

        verify(guild, never()).ban(any(Member.class), anyInt(), any(TimeUnit.class));
        verify(guild, never()).kick(any(Member.class));
    }

    @Test
    void aBotIsNeverTouched() {
        when(user.isBot()).thenReturn(true);
        when(repository.get(GUILD)).thenReturn(config(true, Action.BAN, Set.of()));

        listener.onGuildMemberJoin(event);

        verify(repository, never()).get(GUILD);
    }
}
