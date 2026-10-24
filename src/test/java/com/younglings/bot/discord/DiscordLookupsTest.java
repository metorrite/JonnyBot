package com.younglings.bot.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.restaction.CacheRestAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiscordLookupsTest {
    private static final long USER = 811_000_000_000_000_001L;
    private static final long GUILD = 811_000_000_000_000_900L;

    private JDA jda;
    private Guild guild;
    private final AtomicLong now = new AtomicLong(1_000_000);

    @BeforeEach
    void setUp() {
        DiscordLookups.forgetAll();
        DiscordLookups.clock = now::get;
        jda = mock(JDA.class);
        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
    }

    @AfterEach
    void tearDown() {
        DiscordLookups.forgetAll();
    }

    @SuppressWarnings("unchecked")
    private static <T> CacheRestAction<T> answering(CompletableFuture<T> future) {
        CacheRestAction<T> action = mock(CacheRestAction.class);
        when(action.submit()).thenReturn(future);
        return action;
    }

    private static User user(String name) {
        User user = mock(User.class);
        when(user.getIdLong()).thenReturn(USER);
        when(user.getName()).thenReturn(name);
        when(user.getEffectiveAvatarUrl()).thenReturn("https://cdn.example/" + name + ".png");
        return user;
    }

    private static ErrorResponseException unknown(ErrorResponse why) {
        ErrorResponseException error = mock(ErrorResponseException.class);
        when(error.getErrorResponse()).thenReturn(why);
        return error;
    }

    @Test
    void aHangingLookupGivesUpWithinTheBudgetAndSaysSlow() {
        when(jda.retrieveUserById(USER)).thenAnswer(i -> answering(new CompletableFuture<User>()));

        long started = System.nanoTime();
        DiscordLookups.UserLookup found = DiscordLookups.user(jda, USER, Duration.ofMillis(200));
        long tookMs = (System.nanoTime() - started) / 1_000_000;

        assertEquals(DiscordLookups.Outcome.SLOW, found.outcome());
        assertNull(found.name());
        assertTrue(tookMs < 1_000, "gave up after " + tookMs + " ms");
    }

    @Test
    void aMissAsksDiscordOnceAndTheNextLookupIsACacheHit() {
        User tester = user("tester");
        when(jda.retrieveUserById(USER)).thenAnswer(i -> answering(CompletableFuture.completedFuture(tester)));

        assertNull(DiscordLookups.cachedUser(jda, USER), "nothing known before asking");
        assertEquals("tester", DiscordLookups.user(jda, USER, Duration.ofMillis(200)).name());
        assertEquals("tester", DiscordLookups.user(jda, USER, Duration.ofMillis(200)).name());
        assertEquals("https://cdn.example/tester.png", DiscordLookups.cachedUser(jda, USER).avatarUrl());
        verify(jda, times(1)).retrieveUserById(USER);

        now.addAndGet(DiscordLookups.USER_TTL_MS + 1);
        assertNull(DiscordLookups.cachedUser(jda, USER), "forgotten after six hours");
        DiscordLookups.user(jda, USER, Duration.ofMillis(200));
        verify(jda, times(2)).retrieveUserById(USER);
    }

    @Test
    void anAnswerThatComesAfterTheBudgetStillFillsTheCacheAndIsAskedOnlyOnce() {
        CompletableFuture<User> late = new CompletableFuture<>();
        when(jda.retrieveUserById(USER)).thenAnswer(i -> answering(late));

        assertEquals(DiscordLookups.Outcome.SLOW, DiscordLookups.user(jda, USER, Duration.ofMillis(50)).outcome());
        assertEquals(DiscordLookups.Outcome.SLOW, DiscordLookups.user(jda, USER, Duration.ofMillis(50)).outcome());
        verify(jda, times(1)).retrieveUserById(USER); // the second waited on the question already asked

        late.complete(user("late"));
        assertEquals("late", DiscordLookups.cachedUser(jda, USER).name());
        assertEquals(DiscordLookups.Outcome.FOUND, DiscordLookups.user(jda, USER, Duration.ZERO).outcome());
    }

    @Test
    void anUnknownUserIsRememberedAsUnknown() {
        when(jda.retrieveUserById(USER)).thenAnswer(i -> answering(CompletableFuture.<User>failedFuture(unknown(ErrorResponse.UNKNOWN_USER))));

        assertEquals(DiscordLookups.Outcome.ABSENT, DiscordLookups.user(jda, USER, Duration.ofMillis(200)).outcome());
        assertEquals(DiscordLookups.Outcome.ABSENT, DiscordLookups.user(jda, USER, Duration.ofMillis(200)).outcome());
        DiscordLookups.refreshUser(jda, USER, u -> {});
        verify(jda, times(1)).retrieveUserById(USER);
    }

    @Test
    void aBackgroundRefreshHandsOverTheAnswerWithoutWaiting() {
        CompletableFuture<User> pending = new CompletableFuture<>();
        when(jda.retrieveUserById(USER)).thenAnswer(i -> answering(pending));
        AtomicReference<DiscordLookups.UserInfo> got = new AtomicReference<>();

        DiscordLookups.refreshUser(jda, USER, got::set);
        assertNull(got.get());
        pending.complete(user("later"));

        assertEquals("later", got.get().name());
    }

    @Test
    void aMemberCheckThatHangsThrowsSlowRatherThanSayingNo() {
        when(guild.retrieveMemberById(USER)).thenAnswer(i -> answering(new CompletableFuture<Member>()));

        assertEquals(DiscordLookups.Outcome.SLOW, DiscordLookups.member(guild, USER, Duration.ofMillis(50)).outcome());
        assertThrows(DiscordLookups.Slow.class, () -> DiscordLookups.memberOrSlow(guild, USER));
    }

    @Test
    void notInTheServerIsBelievedForAMinuteAndAMemberIsAlwaysAskedAfresh() {
        when(guild.retrieveMemberById(USER)).thenAnswer(i -> answering(CompletableFuture.<Member>failedFuture(unknown(ErrorResponse.UNKNOWN_MEMBER))));
        assertNull(DiscordLookups.memberOrSlow(guild, USER));
        assertNull(DiscordLookups.memberOrSlow(guild, USER));
        verify(guild, times(1)).retrieveMemberById(USER);

        now.addAndGet(DiscordLookups.NOT_MEMBER_TTL_MS + 1);
        Member member = mock(Member.class);
        User u = user("member");
        when(member.getUser()).thenReturn(u);
        when(guild.retrieveMemberById(USER)).thenAnswer(i -> answering(CompletableFuture.completedFuture(member)));
        assertSame(member, DiscordLookups.memberOrSlow(guild, USER));
        assertSame(member, DiscordLookups.memberOrSlow(guild, USER));
        verify(guild, times(3)).retrieveMemberById(USER); // members are never kept: roles and nicknames stay current
        assertEquals("member", DiscordLookups.cachedUser(jda, USER).name(), "a member answer also names the user");
    }

    @Test
    void anotherDiscordErrorIsSlowNotAbsent() {
        when(guild.retrieveMemberById(USER)).thenAnswer(i -> answering(CompletableFuture.<Member>failedFuture(unknown(ErrorResponse.SERVER_ERROR))));
        assertThrows(DiscordLookups.Slow.class, () -> DiscordLookups.memberOrSlow(guild, USER));
    }

    @Test
    void manyServersShareOneWait() {
        Guild slow1 = mock(Guild.class);
        Guild slow2 = mock(Guild.class);
        when(slow1.getIdLong()).thenReturn(GUILD + 1);
        when(slow2.getIdLong()).thenReturn(GUILD + 2);
        when(slow1.retrieveMemberById(USER)).thenAnswer(i -> answering(new CompletableFuture<Member>()));
        when(slow2.retrieveMemberById(USER)).thenAnswer(i -> answering(new CompletableFuture<Member>()));
        Member cached = mock(Member.class);
        when(guild.getMemberById(USER)).thenReturn(cached);

        long started = System.nanoTime();
        List<DiscordLookups.MemberLookup> found = DiscordLookups.memberInEach(List.of(slow1, guild, slow2), USER, Duration.ofMillis(300));
        long tookMs = (System.nanoTime() - started) / 1_000_000;

        assertEquals(DiscordLookups.Outcome.SLOW, found.get(0).outcome());
        assertSame(cached, found.get(1).member());
        assertEquals(DiscordLookups.Outcome.SLOW, found.get(2).outcome());
        assertTrue(tookMs < 600, "one shared wait, not one per server: " + tookMs + " ms");
    }
}
