package com.runeclans.bot.forms;

import com.runeclans.bot.commands.ticket.TicketService;
import com.runeclans.bot.discord.DiscordLookups;
import com.runeclans.bot.discord.jobs.DiscordJob;
import com.runeclans.bot.discord.jobs.DiscordJobHandler;
import com.runeclans.bot.discord.jobs.DiscordJobs;
import com.runeclans.bot.forms.WebForms.Answer;
import com.runeclans.bot.forms.WebForms.Form;
import com.runeclans.bot.ticket.TicketRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.ArrayList;
import java.util.List;

/**
 * T-590: the Discord side of an application sent from a clan site, run on the Discord job outbox so the website's request (which gives up after
 * 8 s) never waits on Discord. The answers were already saved by {@link WebFormService#submitFromSite}; this opens the ticket and gives the role
 * (only to someone in the server by now) and posts the answers to the form's channel. Not idempotent: a second run would post twice, so the
 * outbox never repeats it after a fault that leaves it unclear whether Discord took it.
 */
@BService
public class SiteApplicationJob implements DiscordJobHandler {
    public static final String KIND = "forms.site-application";

    private final WebFormRepository forms;
    private final WebFormSteps steps;

    public SiteApplicationJob(WebFormRepository forms, TicketRepository tickets, TicketService ticketService) {
        this.forms = forms;
        this.steps = new WebFormSteps(forms, tickets, ticketService);
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public boolean idempotent() {
        return false;
    }

    /** What the job needs: the saved answers travel with it, so it does the same whatever happens to the form's questions meanwhile. */
    static DataObject payload(long formId, long submissionId, long userId, String site, List<Answer> answers) {
        DataArray list = DataArray.empty();
        for (Answer a : answers) list.add(DataObject.empty().put("questionId", a.questionId()).put("label", a.label()).put("value", a.value()));
        return DataObject.empty().put("formId", Long.toString(formId)).put("submissionId", Long.toString(submissionId)).put("userId", Long.toString(userId))
                .put("site", site).put("answers", list);
    }

    /** The answers {@link #payload} carries, in order. */
    static List<Answer> answers(DataObject payload) {
        List<Answer> out = new ArrayList<>();
        DataArray list = payload.optArray("answers").orElse(DataArray.empty());
        for (int i = 0; i < list.length(); i++) {
            DataObject a = list.getObject(i);
            out.add(new Answer(a.getString("questionId", ""), a.getString("label", ""), a.getString("value", "")));
        }
        return out;
    }

    @Override
    public DataObject run(JDA jda, DiscordJob job) {
        Guild guild = DiscordJobs.guild(jda, job);
        DataObject p = job.payload();
        long formId = Long.parseLong(p.getString("formId"));
        long submissionId = Long.parseLong(p.getString("submissionId"));
        long userId = Long.parseLong(p.getString("userId"));
        String site = p.isNull("site") ? null : p.getString("site", null);
        List<Answer> answers = answers(p);
        Form saved = forms.get(formId).filter(f -> f.guildId() == guild.getIdLong())
                .orElseThrow(() -> new DiscordJobs.Refused("The form was deleted, so the answers weren't passed on. They were saved."));
        Form form = WebForms.asSiteForm(saved, answers.stream().map(Answer::label).toList());
        if (form.questions().size() != answers.size()) throw new DiscordJobs.Refused("The saved answers no longer match; they were saved but not passed on.");

        // Before anything is sent, so a slow answer here (a fault the outbox tries again) can never post twice.
        Member member = DiscordLookups.memberOrSlow(guild, userId);
        List<String> notes = new ArrayList<>();
        boolean ticketOpened = false;
        if (form.panelId() != null && member != null) {
            try {
                ticketOpened = steps.openTicket(guild, member, form, submissionId, answers, notes);
            } catch (RuntimeException e) {
                notes.add("Your answers were saved, but the ticket could not be opened. Someone will still see them.");
            }
        }
        if (form.channelId() != null) steps.postToChannel(guild, member, userId, form, answers, site);
        if (form.roleId() != null && member != null) steps.giveRole(guild, member, form, notes);
        DataArray said = DataArray.empty();
        notes.forEach(said::add);
        return DataObject.empty().put("ticketOpened", ticketOpened).put("notes", said);
    }
}
