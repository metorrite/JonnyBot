package com.runeclans.bot.tracking;

import com.runeclans.bot.permission.AdminRoleFilter;
import com.runeclans.bot.wheel.WheelEligibility;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.modals.Modal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeeklyDigestWheelButtonsTest {
    private static final long GUILD = 123L;
    private CitadelWheelEntries service;
    private CitadelWheelPoster poster;
    private AdminRoleFilter adminFilter;
    private CitadelWinners winners;
    private WeeklyDigestInteractionListener listener;
    private Guild guild;

    @BeforeEach
    void setUp() {
        service = mock(CitadelWheelEntries.class);
        poster = mock(CitadelWheelPoster.class);
        adminFilter = mock(AdminRoleFilter.class);
        winners = mock(CitadelWinners.class);
        listener = new WeeklyDigestInteractionListener(service, poster, adminFilter, winners);
        listener.useExecutor(Runnable::run);
        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
    }

    private ButtonInteractionEvent click(String id, Member member) {
        ButtonInteractionEvent event = mock(ButtonInteractionEvent.class, Mockito.RETURNS_DEEP_STUBS);
        when(event.getComponentId()).thenReturn(id);
        when(event.getGuild()).thenReturn(guild);
        when(event.getMember()).thenReturn(member);
        return event;
    }

    private static Member member(boolean manageServer) {
        Member member = mock(Member.class);
        when(member.hasPermission(Permission.MANAGE_SERVER)).thenReturn(manageServer);
        return member;
    }

    @Test
    void copyNamesGivesEveryoneWhoCappedOnePerLineInACodeBlock() {
        when(service.forWeek(eq(guild), any(OffsetDateTime.class), any(OffsetDateTime.class))).thenReturn(new WheelEligibility.Result(List.of("Axley", "Berserker87", "G i f f"), List.of(new WheelEligibility.Left("Metorrite", "clan rank Admin")), List.of()));
        ButtonInteractionEvent event = click("weekly_copy_names:2026-10-14", member(false));

        listener.onButtonInteraction(event);

        verify(event).deferReply(true);
        ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
        verify(event.getHook()).editOriginal(reply.capture());
        assertTrue(reply.getValue().contains("```\nAxley\nBerserker87\nG i f f\n```"), reply.getValue());
        assertTrue(reply.getValue().contains("(3)"), "the count is shown");
        assertTrue(reply.getValue().contains("Left off (staff): Metorrite (clan rank Admin)"), "who was left off, and why: " + reply.getValue());
    }

    @Test
    void copyNamesAsksForTheWeekTheReportCovers() {
        when(service.forWeek(any(), any(), any())).thenReturn(new WheelEligibility.Result(List.of("Axley"), List.of(), List.of()));
        listener.onButtonInteraction(click("weekly_copy_names:2026-10-14", member(false)));

        ArgumentCaptor<OffsetDateTime> start = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> end = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(service).forWeek(eq(guild), start.capture(), end.capture());
        assertEquals(OffsetDateTime.parse("2026-10-07T00:10:00Z"), start.getValue());
        assertEquals(OffsetDateTime.parse("2026-10-14T00:10:00Z"), end.getValue());
    }

    @Test
    void onlyStaffGetTheExtraEntriesFormAndAMemberIsToldWhy() {
        ButtonInteractionEvent memberClick = click("weekly_spin_wheel:2026-10-14", member(false));
        when(adminFilter.isAuthorized(any(), any())).thenReturn(false);
        listener.onButtonInteraction(memberClick);
        verify(memberClick, never()).replyModal(any(Modal.class));

        ButtonInteractionEvent staffClick = click("weekly_spin_wheel:2026-10-14", member(true));
        listener.onButtonInteraction(staffClick);
        ArgumentCaptor<Modal> modal = ArgumentCaptor.forClass(Modal.class);
        verify(staffClick).replyModal(modal.capture());
        assertEquals("weekly_spin_modal:2026-10-14", modal.getValue().getId());
        assertFalse(modal.getValue().getComponents().isEmpty());
    }

    @Test
    void anAdminRoleCountsAsStaffToo() {
        Member admin = member(false);
        when(adminFilter.isAuthorized(guild, admin)).thenReturn(true);
        ButtonInteractionEvent click = click("weekly_spin_wheel:2026-10-14", admin);
        listener.onButtonInteraction(click);
        verify(click).replyModal(any(Modal.class));
    }

    @Test
    void aButtonThatIsntOursIsIgnored() {
        ButtonInteractionEvent event = click("something_else:1", member(true));
        listener.onButtonInteraction(event);
        verify(event, never()).replyModal(any(Modal.class));
        verify(event, never()).deferReply(anyBoolean());
    }

    private static String postIdFor(String winner) {
        return WeeklyDigestInteractionListener.postId("2026-10-14", 12, winner);
    }

    @Test
    void thePostWinnerIdCarriesTheWeekTheEntryCountAndTheWinnerWhateverTheirName() {
        String id = postIdFor("G i f f é");
        assertTrue(id.startsWith("weekly_post_winner:2026-10-14:12:"), id);
        assertTrue(id.length() <= 100);
        assertNull(WeeklyDigestInteractionListener.postId("2026-10-14", 12, "x".repeat(80)), "too long to fit in a button id");
    }

    @Test
    void aMemberCannotPostTheWinner() {
        ButtonInteractionEvent event = click(postIdFor("Axley"), member(false));
        listener.onButtonInteraction(event);
        verify(poster, never()).postWinner(any(), any(), any(), anyInt(), any());
        verify(event, never()).deferReply(anyBoolean());
    }

    @Test
    void staffPostTheWinnerOnceWithTheWeekAndTheSpunGif() {
        GuildMessageChannel channel = mock(GuildMessageChannel.class);
        when(channel.getAsMention()).thenReturn("<#9>");
        when(poster.postWinner(eq(guild), any(), eq("G i f f é"), eq(12), any())).thenReturn(channel);
        ButtonInteractionEvent event = click(postIdFor("G i f f é"), member(true));
        when(event.getMessageIdLong()).thenReturn(555L);
        when(event.getMessage().getAttachments()).thenReturn(List.of());

        listener.onButtonInteraction(event);
        listener.onButtonInteraction(event); // a second click on the same spin

        ArgumentCaptor<OffsetDateTime[]> window = ArgumentCaptor.forClass(OffsetDateTime[].class);
        verify(poster, times(1)).postWinner(eq(guild), window.capture(), eq("G i f f é"), eq(12), any());
        assertEquals(OffsetDateTime.parse("2026-10-07T00:10:00Z"), window.getValue()[0]);
        assertEquals(OffsetDateTime.parse("2026-10-14T00:10:00Z"), window.getValue()[1]);
    }

    @Test
    void whenThePostCannotBeMadeTheStaffMemberIsToldAndMayTryAgain() {
        when(poster.postWinner(any(), any(), any(), anyInt(), any())).thenThrow(new CitadelWheelPoster.CannotPost("Choose the channel first."));
        ButtonInteractionEvent event = click(postIdFor("Axley"), member(true));
        when(event.getMessageIdLong()).thenReturn(556L);
        when(event.getMessage().getAttachments()).thenReturn(List.of());

        listener.onButtonInteraction(event);
        listener.onButtonInteraction(event);

        verify(event.getHook(), times(2)).editOriginal("Choose the channel first.");
    }

    private static String lockIdFor(String winner) {
        return WeeklyDigestInteractionListener.lockId("2026-10-14", 12, winner);
    }

    @Test
    void aMemberCannotLockTheWinner() {
        ButtonInteractionEvent event = click(lockIdFor("Axley"), member(false));
        listener.onButtonInteraction(event);
        verify(winners, never()).lock(anyLong(), any(), any(), any(), any(), any(), any(), any());
        verify(event, never()).deferReply(anyBoolean());
    }

    @Test
    void staffLockTheSpunWinnerForAWeekThatHasNone() {
        ButtonInteractionEvent event = click(lockIdFor("G i f f é"), member(true));
        when(event.getMessage().getAttachments()).thenReturn(List.of());
        when(event.getUser().getIdLong()).thenReturn(77L);
        when(winners.forWeek(GUILD, java.time.LocalDate.parse("2026-10-14"))).thenReturn(List.of());

        listener.onButtonInteraction(event);

        verify(winners).lock(eq(GUILD), eq(java.time.LocalDate.parse("2026-10-14")), eq("G i f f é"), any(), eq(com.runeclans.bot.wheel.WheelWinnerRepository.Method.SPUN), eq(77L), any(),
                eq(com.runeclans.bot.wheel.WheelWinnerRepository.GifSource.BOT));
        verify(event.getHook()).editOriginal("Locked **G i f f é** in as the winner of the week ending 2026-10-14.");
    }

    @Test
    void aWeekThatAlreadyHasAWinnerIsNeverAddedToOrReplacedFromTheButton() {
        var existing = mock(com.runeclans.bot.wheel.WheelWinnerRepository.Winner.class);
        when(existing.winner()).thenReturn("Axley");
        when(winners.forWeek(GUILD, java.time.LocalDate.parse("2026-10-14"))).thenReturn(List.of(existing));
        ButtonInteractionEvent event = click(lockIdFor("Berserker87"), member(true));

        listener.onButtonInteraction(event);

        verify(winners, never()).lock(anyLong(), any(), any(), any(), any(), any(), any(), any());
        verify(event.getHook()).editOriginal(org.mockito.ArgumentMatchers.contains("already has a locked winner"));
    }
}
