package com.runeclans.bot.forms;

import com.runeclans.bot.commands.ticket.TicketService;
import com.runeclans.bot.discord.jobs.DiscordOutbox;
import com.runeclans.bot.forms.WebForms.Answer;
import com.runeclans.bot.forms.WebForms.Form;
import com.runeclans.bot.testsupport.QueuedJobs;
import com.runeclans.bot.ticket.TicketRepository;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** T-590 review: a clan site's application answers at once and leaves every Discord step to the outbox, so the website's 8 s never runs out. */
class SiteApplicationJobTest {
    private static final long GUILD = 11L;
    private static final long USER = 22L;
    private static final long FORM = 33L;

    private static Form form(Long panelId, Long channelId, Long roleId) {
        return new Form(FORM, GUILD, "Apply", "Apply", "", true, panelId, channelId, roleId, false, 0, List.of());
    }

    private record Setup(WebFormService service, WebFormRepository forms, TicketService tickets, DiscordOutbox outbox, Guild guild) {}

    private static Setup setup(Form saved, Member member) {
        WebFormRepository forms = mock(WebFormRepository.class);
        when(forms.get(FORM)).thenReturn(Optional.of(saved));
        when(forms.addSubmission(any(), anyLong(), any())).thenReturn(5L);
        TicketService tickets = mock(TicketService.class);
        DiscordOutbox outbox = QueuedJobs.outbox();
        Guild guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        when(guild.getMemberById(USER)).thenReturn(member);
        return new Setup(new WebFormService(forms, mock(TicketRepository.class), tickets, outbox), forms, tickets, outbox, guild);
    }

    @Test
    void siteApplicationQueuesTheDiscordStepsInsteadOfWaitingOnThem() {
        Setup s = setup(form(1L, 2L, 3L), mock(Member.class));
        var outcome = s.service().submitFromSite(s.guild(), USER, FORM, List.of("Why join?", "RSN"), List.of("Fun", "Zezima"), "iron-legion");

        assertEquals(5L, outcome.submissionId());
        assertFalse(outcome.ticketOpened());
        assertTrue(outcome.notes().isEmpty());
        verifyNoInteractions(s.tickets());
        ArgumentCaptor<DataObject> payload = ArgumentCaptor.forClass(DataObject.class);
        verify(s.outbox()).enqueue(eq(SiteApplicationJob.KIND), eq(GUILD), eq(USER), isNull(), payload.capture());
        assertEquals("5", payload.getValue().getString("submissionId"));
        assertEquals("iron-legion", payload.getValue().getString("site"));
        assertEquals(List.of(new Answer("q1", "Why join?", "Fun"), new Answer("q2", "RSN", "Zezima")), SiteApplicationJob.answers(payload.getValue()));
    }

    @Test
    void aFormThatSendsNowhereQueuesNothing() {
        Setup s = setup(form(null, null, null), mock(Member.class));
        s.service().submitFromSite(s.guild(), USER, FORM, List.of("Why?"), List.of("Fun"), null);
        verify(s.outbox(), never()).enqueue(anyString(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void aFailedQueueKeepsTheAnswersAndSaysSo() {
        Setup s = setup(form(null, 2L, null), mock(Member.class));
        when(s.outbox().enqueue(anyString(), anyLong(), anyLong(), any(), any())).thenThrow(new IllegalStateException("db down"));
        var outcome = s.service().submitFromSite(s.guild(), USER, FORM, List.of("Why?"), List.of("Fun"), null);
        assertEquals(5L, outcome.submissionId());
        assertEquals(1, outcome.notes().size());
    }

    @Test
    void thePayloadKeepsTheAnswersInOrderAndANullSite() {
        List<Answer> answers = List.of(new Answer("q1", "A", "1"), new Answer("q2", "B", "line\nbreak"));
        DataObject p = DataObject.fromJson(SiteApplicationJob.payload(FORM, 9, USER, null, answers).toJson());
        assertEquals(answers, SiteApplicationJob.answers(p));
        assertTrue(p.isNull("site"));
        assertEquals(Long.toString(USER), p.getString("userId"));
    }
}
