package com.runeclans.bot.internal;

import com.runeclans.bot.chatlevel.ChatLevelRepository;
import com.runeclans.bot.chatlevel.ChatLevelRepository.Standing;
import com.runeclans.bot.coffer.CofferRepository;
import com.runeclans.bot.discord.DiscordLookups;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import com.runeclans.bot.tracking.CitadelPrizes;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.restaction.CacheRestAction;
import net.dv8tion.jda.api.utils.concurrent.Task;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Admin pages that look members up in Discord answer the same as before when Discord answers, and stop waiting when it doesn't. */
class MemberNamesTimeoutTest {
    private Guild guild;

    @BeforeEach
    void setUp() {
        DiscordLookups.forgetAll();
        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(7L);
        cached(1L, "Cached");
    }

    @AfterEach
    void tearDown() {
        DiscordLookups.forgetAll();
    }

    private Member member(long id, String name) {
        Member member = mock(Member.class);
        when(member.getIdLong()).thenReturn(id);
        when(member.getEffectiveName()).thenReturn(name);
        User user = mock(User.class);
        when(user.getIdLong()).thenReturn(id);
        when(user.getName()).thenReturn(name);
        when(member.getUser()).thenReturn(user);
        return member;
    }

    private void cached(long id, String name) {
        Member member = member(id, name);
        when(guild.getMemberById(id)).thenReturn(member);
    }

    /** A member lookup task that answers with these members, or never answers when {@code members} is null. */
    @SuppressWarnings("unchecked")
    private static Task<List<Member>> task(List<Member> members) {
        Task<List<Member>> task = mock(Task.class);
        when(task.onSuccess(any())).thenAnswer(i -> {
            if (members != null) ((Consumer<List<Member>>) i.getArgument(0)).accept(members);
            return task;
        });
        when(task.onError(any())).thenReturn(task);
        return task;
    }

    @SuppressWarnings("unchecked")
    private static CacheRestAction<Member> answering(CompletableFuture<Member> future) {
        CacheRestAction<Member> action = mock(CacheRestAction.class);
        when(action.submit()).thenReturn(future);
        return action;
    }

    @Test
    void namesComeFromTheCacheThenOneLookupForTheRest() {
        Member looked = member(2L, "Looked up");
        Task<List<Member>> task = task(List.of(looked));
        when(guild.retrieveMembersByIds(List.of(2L, 3L))).thenReturn(task);

        Map<Long, String> names = CitadelAdminApi.memberNames(guild, List.of(1L, 2L, 3L, 2L));

        assertEquals(Map.of(1L, "Cached", 2L, "Looked up"), names);
        verify(task, never()).cancel();
    }

    @Test
    void aLookupDiscordSitsOnStopsAtTheBudgetAndKeepsTheCachedNames() {
        Task<List<Member>> task = task(null);
        when(guild.retrieveMembersByIds(List.of(2L))).thenReturn(task);

        long started = System.nanoTime();
        Map<Long, String> names = CitadelAdminApi.memberNames(guild, List.of(1L, 2L));
        long tookMs = (System.nanoTime() - started) / 1_000_000;

        assertEquals(Map.of(1L, "Cached"), names);
        verify(task).cancel();
        assertTrue(tookMs < DiscordLookups.BUDGET.toMillis() + 1_000, "took " + tookMs + " ms");
    }

    @Test
    void theChatLevelLeaderboardUsesTheCacheFirstAndShowsFormerMembersAsBefore() {
        ChatLevelRepository repository = mock(ChatLevelRepository.class);
        when(repository.top(7L, 20)).thenReturn(List.of(new Standing(1L, 500, 10), new Standing(2L, 300, 6), new Standing(3L, 100, 2)));
        when(repository.countMembers(7L)).thenReturn(3L);
        Task<List<Member>> task = task(List.of(member(2L, "Looked up")));
        when(guild.retrieveMembersByIds(List.of(2L, 3L))).thenReturn(task);

        DataArray rows = new ChatLevelAdminApi(repository).top(guild).getArray("top");

        assertEquals("Cached", rows.getObject(0).getString("name"));
        assertEquals("Looked up", rows.getObject(1).getString("name"));
        assertEquals("Former member", rows.getObject(2).getString("name"));
        assertEquals(3, rows.getObject(2).getInt("rank"));
        verify(guild, never()).retrieveMembersByIds(List.of(1L, 2L, 3L));
    }

    private DataObject donation(CofferAdminApi api, String heldBy) {
        Member actor = mock(Member.class);
        when(actor.getId()).thenReturn("1");
        when(actor.getIdLong()).thenReturn(1L);
        return api.route(guild, actor, "POST", new String[]{"coffer", "donation"},
                () -> DataObject.empty().put("donor", "Someone").put("amount", "1m").put("heldBy", heldBy));
    }

    @Test
    void theCofferRefusesSomeoneNotInTheServerAndSaysSlowRatherThanNoWhenDiscordHangs() {
        CofferRepository coffer = mock(CofferRepository.class);
        CofferAdminApi api = new CofferAdminApi(coffer, mock(CitadelPrizes.class));

        Member found = member(4L, "Found");
        when(guild.retrieveMemberById(4L)).thenAnswer(i -> answering(CompletableFuture.completedFuture(found)));
        donation(api, "4");
        verify(coffer).insertDonation(7L, "Someone", 1_000_000L, 4L);

        ErrorResponseException unknown = mock(ErrorResponseException.class);
        when(unknown.getErrorResponse()).thenReturn(ErrorResponse.UNKNOWN_MEMBER);
        when(guild.retrieveMemberById(5L)).thenAnswer(i -> answering(CompletableFuture.failedFuture(unknown)));
        assertEquals(400, assertThrows(ApiError.class, () -> donation(api, "5")).status);

        when(guild.retrieveMemberById(6L)).thenAnswer(i -> answering(new CompletableFuture<>()));
        long started = System.nanoTime();
        assertThrows(DiscordLookups.Slow.class, () -> donation(api, "6"));
        long tookMs = (System.nanoTime() - started) / 1_000_000;
        assertTrue(tookMs < DiscordLookups.BUDGET.toMillis() + 1_000, "took " + tookMs + " ms");
        verify(coffer, never()).insertDonation(anyLong(), anyString(), anyLong(), org.mockito.ArgumentMatchers.eq(5L));
        verify(coffer, never()).insertDonation(anyLong(), anyString(), anyLong(), org.mockito.ArgumentMatchers.eq(6L));
    }
}
