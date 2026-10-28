package com.runeclans.bot.access;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccessGrantServiceTest {
    private static final long LISTED = 42;
    private static final long STRANGER = 43;
    private static final long SERVER = 1001;

    private final AtomicLong now = new AtomicLong(1_000_000);
    private AccessGrantRepository repository;
    private JDA jda;
    private Guild guild;
    private Member member;
    private AccessGrantService service;

    @BeforeEach
    void setUp() {
        repository = mock(AccessGrantRepository.class);
        jda = mock(JDA.class);
        guild = mock(Guild.class);
        member = mock(Member.class);
        when(jda.getGuildById(SERVER)).thenReturn(guild);
        when(guild.getMemberById(STRANGER)).thenReturn(member);
        service = new AccessGrantService(GateSource.ownTables(null, repository::all, null));
        service.clock = now::get;
    }

    private static AccessGrant user(AccessArea area, long id, Instant expires) {
        return new AccessGrant(1, area, id, null, null, "", 7, Instant.EPOCH, expires);
    }

    private static AccessGrant server(AccessArea area, AccessLevel level) {
        return new AccessGrant(2, area, null, SERVER, level, "", 7, Instant.EPOCH, null);
    }

    @Test
    void aListedUserGetsIntoThatAreaOnlyAndAnUnlistedUserDoesNot() {
        when(repository.all()).thenReturn(List.of(user(AccessArea.HUB, LISTED, null)));

        assertTrue(service.allows(jda, AccessArea.HUB, LISTED));
        assertFalse(service.allows(jda, AccessArea.JONNYBOT, LISTED));
        assertFalse(service.allows(jda, AccessArea.HUB, STRANGER));
    }

    @Test
    void anExpiredGrantLetsNobodyIn() {
        when(repository.all()).thenReturn(List.of(user(AccessArea.HUB, LISTED, Instant.ofEpochMilli(500_000))));

        assertFalse(service.allows(jda, AccessArea.HUB, LISTED));
    }

    @Test
    void aServerGrantNeedsTheLevelInThatServer() {
        when(repository.all()).thenReturn(List.of(server(AccessArea.DASHBOARD, AccessLevel.MANAGE_SERVER)));

        assertFalse(service.allows(jda, AccessArea.DASHBOARD, STRANGER), "an ordinary member is not enough");

        when(member.hasPermission(Permission.MANAGE_SERVER)).thenReturn(true);
        service.listChanged();
        assertTrue(service.allows(jda, AccessArea.DASHBOARD, STRANGER));
    }

    @Test
    void theLevelsAreOrderedAndOwnerIsTheHighest() {
        when(member.hasPermission(Permission.ADMINISTRATOR)).thenReturn(true);

        assertTrue(AccessLevel.MEMBER.satisfiedBy(member));
        assertTrue(AccessLevel.MANAGE_SERVER.satisfiedBy(member));
        assertTrue(AccessLevel.ADMINISTRATOR.satisfiedBy(member));
        assertFalse(AccessLevel.OWNER.satisfiedBy(member));
        assertFalse(AccessLevel.MEMBER.satisfiedBy(null));
    }

    @Test
    void aServerTheBotIsNotInOrAPersonNotInItLetsNobodyIn() {
        when(repository.all()).thenReturn(List.of(server(AccessArea.JONNYBOT, AccessLevel.MEMBER)));
        when(guild.getMemberById(LISTED)).thenReturn(null);
        when(guild.retrieveMemberById(LISTED)).thenThrow(new RuntimeException("unknown member"));

        assertFalse(service.allows(jda, AccessArea.JONNYBOT, LISTED));

        when(jda.getGuildById(SERVER)).thenReturn(null);
        service.listChanged();
        assertFalse(service.allows(jda, AccessArea.JONNYBOT, STRANGER));
    }

    @Test
    void whenDiscordHangsTheCheckSaysTryAgainAndRemembersNothing() {
        com.runeclans.bot.discord.DiscordLookups.forgetAll();
        try {
            when(repository.all()).thenReturn(List.of(server(AccessArea.JONNYBOT, AccessLevel.MEMBER)));
            @SuppressWarnings("unchecked")
            net.dv8tion.jda.api.requests.restaction.CacheRestAction<Member> hanging = mock(net.dv8tion.jda.api.requests.restaction.CacheRestAction.class);
            when(hanging.submit()).thenReturn(new java.util.concurrent.CompletableFuture<>());
            when(guild.retrieveMemberById(LISTED)).thenReturn(hanging);

            org.junit.jupiter.api.Assertions.assertThrows(com.runeclans.bot.discord.DiscordLookups.Slow.class, () -> service.allows(jda, AccessArea.JONNYBOT, LISTED));

            when(guild.getMemberById(LISTED)).thenReturn(member);
            assertTrue(service.allows(jda, AccessArea.JONNYBOT, LISTED), "the slow answer was not remembered as a no");
        } finally {
            com.runeclans.bot.discord.DiscordLookups.forgetAll();
        }
    }

    @Test
    void anUnreadableListIsClosedAndNotRemembered() {
        when(repository.all()).thenThrow(new RuntimeException("db down")).thenReturn(List.of(user(AccessArea.HUB, LISTED, null)));

        assertFalse(service.allows(jda, AccessArea.HUB, LISTED));
        assertTrue(service.allows(jda, AccessArea.HUB, LISTED));
    }

    @Test
    void answersAreRememberedForAMinuteAndForgottenWhenTheListChanges() {
        when(repository.all()).thenReturn(List.of(user(AccessArea.HUB, LISTED, null)));

        service.allows(jda, AccessArea.HUB, LISTED);
        service.allows(jda, AccessArea.HUB, LISTED);
        verify(repository, times(1)).all();

        now.addAndGet(61_000);
        service.allows(jda, AccessArea.HUB, LISTED);
        verify(repository, times(2)).all();

        service.listChanged();
        service.allows(jda, AccessArea.HUB, LISTED);
        verify(repository, times(3)).all();
    }

    @Test
    void noUserIdNeverMatches() {
        when(repository.all()).thenReturn(List.of(server(AccessArea.JONNYBOT, AccessLevel.MEMBER)));

        assertFalse(service.allows(jda, AccessArea.JONNYBOT, 0));
    }
}
