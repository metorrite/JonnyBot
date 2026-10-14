package com.younglings.bot.tracking;

import com.younglings.bot.permission.AdminRoleFilter;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeeklyDigestWheelButtonsTest {
    private static final long GUILD = 123L;
    private WeeklyDigestService service;
    private AdminRoleFilter adminFilter;
    private WeeklyDigestInteractionListener listener;
    private Guild guild;

    @BeforeEach
    void setUp() {
        service = mock(WeeklyDigestService.class);
        adminFilter = mock(AdminRoleFilter.class);
        listener = new WeeklyDigestInteractionListener(service, adminFilter);
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
        when(service.getCappedRsns(eq(GUILD), any(OffsetDateTime.class), any(OffsetDateTime.class))).thenReturn(List.of("Axley", "Berserker87", "G i f f"));
        ButtonInteractionEvent event = click("weekly_copy_names:2026-10-14", member(false));

        listener.onButtonInteraction(event);

        ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
        verify(event).reply(reply.capture());
        assertTrue(reply.getValue().contains("```\nAxley\nBerserker87\nG i f f\n```"), reply.getValue());
        assertTrue(reply.getValue().contains("(3)"), "the count is shown");
    }

    @Test
    void copyNamesAsksForTheWeekTheReportCovers() {
        when(service.getCappedRsns(anyLong(), any(), any())).thenReturn(List.of("Axley"));
        listener.onButtonInteraction(click("weekly_copy_names:2026-10-14", member(false)));

        ArgumentCaptor<OffsetDateTime> start = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> end = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(service).getCappedRsns(eq(GUILD), start.capture(), end.capture());
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
        verify(event, never()).reply(anyString());
    }
}
