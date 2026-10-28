package com.runeclans.bot.forms;

import com.runeclans.bot.commands.ticket.TicketService;
import com.runeclans.bot.discord.DiscordLookups;
import com.runeclans.bot.discord.jobs.DiscordOutbox;
import com.runeclans.bot.forms.WebForms.Checked;
import com.runeclans.bot.forms.WebForms.Form;
import com.runeclans.bot.ticket.TicketRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns a submitted web form into what the owner chose: the answers are saved first (so they are never lost), then a ticket is opened in the chosen
 * panel, the answers are posted to the chosen channel, and the chosen role is given. A step that cannot be done (a deleted channel, a full panel) is
 * reported back and skipped; it never throws away the submission.
 */
@BService
public class WebFormService {
    private static final Logger log = LoggerFactory.getLogger(WebFormService.class);

    /** Thrown for a refusal the person filling the form in should read: the status is an HTTP-style code. */
    public static final class FormProblem extends RuntimeException {
        private final int status;
        private final List<String> problems;

        public FormProblem(int status, String message) {
            this(status, message, List.of());
        }

        public FormProblem(int status, String message, List<String> problems) {
            super(message);
            this.status = status;
            this.problems = List.copyOf(problems);
        }

        public int status() {
            return status;
        }

        public List<String> problems() {
            return problems;
        }
    }

    /** What happened to a submission: {@code notes} are the steps that were skipped, worded for the person. */
    public record Outcome(long submissionId, boolean ticketOpened, List<String> notes) {}

    private final WebFormRepository forms;
    private final WebFormSteps steps;
    private final DiscordOutbox outbox;

    public WebFormService(WebFormRepository forms, TicketRepository tickets, TicketService ticketService, DiscordOutbox outbox) {
        this.forms = forms;
        this.steps = new WebFormSteps(forms, tickets, ticketService);
        this.outbox = outbox;
    }

    /** Checks and records a submission from {@code userId}, then does what the form says with it. */
    public Outcome submit(Guild guild, long userId, long formId, Map<String, String> raw) {
        Form form = forms.get(formId).filter(f -> f.guildId() == guild.getIdLong()).orElseThrow(() -> new FormProblem(404, "That form does not exist."));
        Member member = memberOf(guild, userId);
        if (member == null) throw new FormProblem(403, "Join the Discord server first, then send this form.");

        Optional<String> refusal = WebForms.cannotSubmit(form, forms.lastSubmission(form.id(), userId), Instant.now());
        if (refusal.isPresent()) throw new FormProblem(409, refusal.get());

        Checked checked = WebForms.check(form, raw);
        if (!checked.ok()) throw new FormProblem(400, checked.problems().getFirst(), checked.problems());

        long submissionId = forms.addSubmission(form, userId, checked.answers());
        List<String> notes = new ArrayList<>();
        boolean ticketOpened = false;

        if (form.panelId() != null) {
            try {
                ticketOpened = steps.openTicket(guild, member, form, submissionId, checked.answers(), notes);
            } catch (RuntimeException e) {
                log.warn("Web form {}: opening the ticket failed", form.id(), e);
                notes.add("Your answers were saved, but the ticket could not be opened. Someone will still see them.");
            }
        }
        if (form.channelId() != null) steps.postToChannel(guild, member, member.getIdLong(), form, checked.answers(), null);
        if (form.roleId() != null) steps.giveRole(guild, member, form, notes);
        return new Outcome(submissionId, ticketOpened, notes);
    }

    /**
     * T-590: an application sent from the clan's site (its application form widget), delivered where this form sends its answers. The person
     * signed in with Discord on the website but needn't be in the server yet (that's who applies): the answers are saved and posted to the
     * form's channel either way; a ticket is opened and the role given only to someone already in the server. The form's wait between sends
     * and one-per-person rule still apply. The answers are saved here; the Discord steps are queued on the Discord job outbox
     * ({@link SiteApplicationJob}), so this never waits on Discord beyond the short member check. {@code labels} and {@code values} are the
     * site's questions and answers, in order.
     */
    public Outcome submitFromSite(Guild guild, long userId, long formId, List<String> labels, List<String> values, String site) {
        Form saved = forms.get(formId).filter(f -> f.guildId() == guild.getIdLong() && f.enabled()).orElseThrow(() -> new FormProblem(404, "That form does not exist or is closed."));
        Optional<String> refusal = WebForms.cannotSubmit(saved, forms.lastSubmission(saved.id(), userId), Instant.now());
        if (refusal.isPresent()) throw new FormProblem(409, refusal.get());

        Form form = WebForms.asSiteForm(saved, labels);
        if (form.questions().isEmpty()) throw new FormProblem(400, "Answer the questions first.");
        Map<String, String> raw = new HashMap<>();
        for (int i = 0; i < form.questions().size(); i++) raw.put(form.questions().get(i).id(), i < values.size() ? values.get(i) : "");
        Checked checked = WebForms.check(form, raw);
        if (!checked.ok()) throw new FormProblem(400, checked.problems().getFirst(), checked.problems());

        Member member = memberOf(guild, userId);
        long submissionId = forms.addSubmission(form, userId, checked.answers());
        List<String> notes = new ArrayList<>();
        if (form.panelId() != null && member == null) notes.add("You're not in the clan's Discord server yet, so no ticket was opened. Your answers were still sent.");
        // The website gives up after 8 s, and opening a ticket can wait on Discord for longer: the ticket, the post and the role run on the
        // Discord job outbox, so the answer here never waits on Discord and a slow Discord can't make a delivered application look failed.
        if (form.panelId() != null || form.channelId() != null || form.roleId() != null) {
            try {
                outbox.enqueue(SiteApplicationJob.KIND, guild.getIdLong(), userId, null, SiteApplicationJob.payload(form.id(), submissionId, userId, site, checked.answers()));
            } catch (RuntimeException e) {
                log.warn("Web form {}: queueing site application {} for Discord failed", form.id(), submissionId, e);
                notes.add("Your answers were saved, but they couldn't be passed on in Discord right now. The clan's staff can still see them.");
            }
        }
        return new Outcome(submissionId, false, notes);
    }

    // ---------- small helpers ----------

    /** Null when they're not in the server; {@link DiscordLookups.Slow} (a 503) when Discord didn't answer in time. */
    private static Member memberOf(Guild guild, long userId) {
        return DiscordLookups.memberOrSlow(guild, userId);
    }
}
