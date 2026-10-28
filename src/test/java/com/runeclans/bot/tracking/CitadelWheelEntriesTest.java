package com.runeclans.bot.tracking;

import com.runeclans.bot.permission.AdminRoleFilter;
import com.runeclans.bot.runescape.ClanMemberRepository;
import com.runeclans.bot.runescape.ClanPointsRepository;
import com.runeclans.bot.runescape.PlayerLink;
import com.runeclans.bot.runescape.PlayerLinkRepository;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.concurrent.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CitadelWheelEntriesTest {
    private static final long GUILD = 5L;
    private static final OffsetDateTime START = OffsetDateTime.parse("2026-10-07T00:10:00Z");
    private static final OffsetDateTime END = OffsetDateTime.parse("2026-10-14T00:10:00Z");

    private WeeklyDigestService digest;
    private PlayerLinkRepository links;
    private AdminRoleFilter adminFilter;
    private CitadelWheelEntries entries;
    private Guild guild;
    private Member member;

    @BeforeEach
    void setUp() {
        digest = mock(WeeklyDigestService.class);
        ClanMemberRepository clanMembers = mock(ClanMemberRepository.class);
        links = mock(PlayerLinkRepository.class);
        ClanPointsRepository ranks = mock(ClanPointsRepository.class);
        adminFilter = mock(AdminRoleFilter.class);
        entries = new CitadelWheelEntries(digest, clanMembers, links, ranks, adminFilter);

        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        member = mock(Member.class);
        when(member.getIdLong()).thenReturn(77L);
        when(digest.getCappedRsns(GUILD, START, END)).thenReturn(List.of("Axley", "Giff"));
        when(links.getAllLinks(GUILD)).thenReturn(List.of(new PlayerLink(1, GUILD, 77L, "Axley", "x", null, null)));
    }

    @Test
    void whoIsAnAdminIsRememberedBetweenPageLoads() {
        when(guild.getMemberById(77L)).thenReturn(member);
        when(adminFilter.isAuthorized(guild, member)).thenReturn(true);

        var first = entries.forWeek(guild, START, END);
        var second = entries.forWeek(guild, START, END);

        assertEquals(List.of("Giff"), first.names(), "the admin is left off");
        assertEquals(first.names(), second.names());
        verify(adminFilter, times(1)).isAuthorized(guild, member);
    }

    @Test
    void peopleTheBotDoesNotHoldAreLookedUpInOneRequestNotOneEach() {
        when(guild.getMemberById(77L)).thenReturn(null);
        when(adminFilter.isAuthorized(guild, member)).thenReturn(false);
        @SuppressWarnings("unchecked")
        Task<List<Member>> task = mock(Task.class);
        when(task.onSuccess(any())).thenAnswer(invocation -> {
            Consumer<List<Member>> done = invocation.getArgument(0);
            done.accept(List.of(member));
            return task;
        });
        when(task.onError(any())).thenReturn(task);
        when(guild.retrieveMembersByIds(anyCollection())).thenReturn(task);

        var result = entries.forWeek(guild, START, END);

        assertEquals(List.of("Axley", "Giff"), result.names());
        verify(guild, times(1)).retrieveMembersByIds(anyCollection());
        verify(guild, never()).retrieveMemberById(anyLong());
    }
}
