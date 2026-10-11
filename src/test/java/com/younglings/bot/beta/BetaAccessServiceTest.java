package com.younglings.bot.beta;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BetaAccessServiceTest {
    private static final long USER = 42;
    private static final long BETA_GUILD = 1001;

    private final AtomicLong now = new AtomicLong(1_000_000);
    private BetaGuildRepository repository;
    private JDA jda;
    private Guild guild;
    private Member member;
    private BetaAccessService service;

    @BeforeEach
    void setUp() {
        repository = mock(BetaGuildRepository.class);
        when(repository.all()).thenReturn(List.of(new BetaGuildRepository.BetaGuild(BETA_GUILD, "Friends")));
        jda = mock(JDA.class);
        guild = mock(Guild.class);
        member = mock(Member.class);
        when(jda.getGuildById(BETA_GUILD)).thenReturn(guild);
        when(guild.getMemberById(USER)).thenReturn(member);
        service = new BetaAccessService(repository);
        service.clock = now::get;
    }

    @Test
    void anAdministratorOfABetaServerGetsIn() {
        when(member.hasPermission(Permission.ADMINISTRATOR)).thenReturn(true);

        assertTrue(service.managesAnyBetaGuild(jda, USER));
    }

    @Test
    void someoneWithManageServerGetsIn() {
        when(member.hasPermission(Permission.MANAGE_SERVER)).thenReturn(true);

        assertTrue(service.managesAnyBetaGuild(jda, USER));
    }

    @Test
    void theOwnerOfABetaServerGetsIn() {
        when(member.isOwner()).thenReturn(true);

        assertTrue(service.managesAnyBetaGuild(jda, USER));
    }

    @Test
    void anOrdinaryMemberOfABetaServerDoesNot() {
        assertFalse(service.managesAnyBetaGuild(jda, USER));
    }

    @Test
    void aBetaServerTheBotIsNotInLetsNobodyThrough() {
        when(jda.getGuildById(BETA_GUILD)).thenReturn(null);

        assertFalse(service.managesAnyBetaGuild(jda, USER));
    }

    @Test
    void aPersonNotInAnyBetaServerDoesNot() {
        when(guild.getMemberById(USER)).thenReturn(null);
        when(guild.retrieveMemberById(USER)).thenThrow(new IllegalStateException("not a member"));

        assertFalse(service.managesAnyBetaGuild(jda, USER));
    }

    @Test
    void anAnswerIsRememberedForAMinuteAndForgottenWhenTheListChanges() {
        when(member.hasPermission(Permission.MANAGE_SERVER)).thenReturn(true);
        assertTrue(service.managesAnyBetaGuild(jda, USER));

        when(member.hasPermission(Permission.MANAGE_SERVER)).thenReturn(false);
        assertTrue(service.managesAnyBetaGuild(jda, USER), "still remembered");
        verify(repository, times(1)).all();

        service.listChanged();
        assertFalse(service.managesAnyBetaGuild(jda, USER));

        when(member.hasPermission(Permission.MANAGE_SERVER)).thenReturn(true);
        now.addAndGet(61_000);
        assertTrue(service.managesAnyBetaGuild(jda, USER), "looked again after a minute");
    }
}
