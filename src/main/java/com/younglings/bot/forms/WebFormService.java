package com.younglings.bot.forms;

import com.younglings.bot.commands.ticket.TicketService;
import com.younglings.bot.discord.DiscordLookups;
import com.younglings.bot.forms.WebForms.Answer;
import com.younglings.bot.forms.WebForms.Checked;
import com.younglings.bot.forms.WebForms.Form;
import com.younglings.bot.forms.WebForms.Question;
import com.younglings.bot.internal.MemberApi;
import com.younglings.bot.ticket.TicketModels.Field;
import com.younglings.bot.ticket.TicketModels.FieldKind;
import com.younglings.bot.ticket.TicketModels.Option;
import com.younglings.bot.ticket.TicketModels.Panel;
import com.younglings.bot.ticket.TicketRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Turns a submitted web form into what the owner chose: the answers are saved first (so they are never lost), then a ticket is opened in the chosen
 * panel, the answers are posted to the chosen channel, and the chosen role is given. A step that cannot be done (a deleted channel, a full panel) is
 * reported back and skipped; it never throws away the submission.
 */
@BService
public class WebFormService {
    private static final Logger log = LoggerFactory.getLogger(WebFormService.class);
    private static final int TICKET_WAIT_SECONDS = 15;

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
    private final TicketRepository tickets;
    private final TicketService ticketService;

    public WebFormService(WebFormRepository forms, TicketRepository tickets, TicketService ticketService) {
        this.forms = forms;
        this.tickets = tickets;
        this.ticketService = ticketService;
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
                ticketOpened = openTicket(guild, member, form, submissionId, checked.answers(), notes);
            } catch (RuntimeException e) {
                log.warn("Web form {}: opening the ticket failed", form.id(), e);
                notes.add("Your answers were saved, but the ticket could not be opened. Someone will still see them.");
            }
        }
        if (form.channelId() != null) postToChannel(guild, member, member.getIdLong(), form, checked.answers(), null);
        if (form.roleId() != null) giveRole(guild, member, form, notes);
        return new Outcome(submissionId, ticketOpened, notes);
    }

    /**
     * T-590: an application sent from the clan's site (its application form widget), delivered where this form sends its answers. The person
     * signed in with Discord on the website but needn't be in the server yet (that's who applies): the answers are saved and posted to the
     * form's channel either way; a ticket is opened and the role given only to someone already in the server. The form's wait between sends
     * and one-per-person rule still apply. {@code labels} and {@code values} are the site's questions and answers, in order.
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
        boolean ticketOpened = false;
        if (form.panelId() != null) {
            if (member == null) {
                notes.add("You're not in the clan's Discord server yet, so no ticket was opened. Your answers were still sent.");
            } else {
                try {
                    ticketOpened = openTicket(guild, member, form, submissionId, checked.answers(), notes);
                } catch (RuntimeException e) {
                    log.warn("Web form {}: opening the ticket for a site application failed", form.id(), e);
                    notes.add("Your answers were saved, but the ticket could not be opened. Someone will still see them.");
                }
            }
        }
        if (form.channelId() != null) postToChannel(guild, member, userId, form, checked.answers(), site);
        if (form.roleId() != null && member != null) giveRole(guild, member, form, notes);
        return new Outcome(submissionId, ticketOpened, notes);
    }

    // ---------- the steps ----------

    private boolean openTicket(Guild guild, Member member, Form form, long submissionId, List<Answer> answers, List<String> notes) {
        Panel panel = tickets.getPanel(form.panelId());
        if (panel == null || panel.guildId() != guild.getIdLong()) {
            notes.add("The ticket panel for this form was removed, so no ticket was opened. Your answers were saved.");
            return false;
        }
        if (!panel.enabled()) {
            notes.add("Tickets of this type are closed right now, so no ticket was opened. Your answers were saved.");
            return false;
        }
        int open = tickets.countOpenFor(guild.getIdLong(), panel.id(), member.getIdLong());
        if (open >= panel.perUserLimit()) {
            notes.add("You already have an open ticket of this type, so no new one was opened. Your answers were saved.");
            return false;
        }

        List<Field> fields = new ArrayList<>();
        Map<Long, String> values = new HashMap<>();
        List<Question> questions = form.questions();
        for (int i = 0; i < questions.size(); i++) {
            Question q = questions.get(i);
            long fieldId = i + 1;
            FieldKind kind = switch (q.kind()) {
                case PARAGRAPH -> FieldKind.PARAGRAPH;
                case CHOICE -> FieldKind.SELECT;
                case CHECKBOX -> FieldKind.CHECKBOX;
                default -> FieldKind.SHORT;
            };
            List<Option> options = new ArrayList<>();
            if (q.kind() == WebForms.Kind.CHOICE) {
                for (int j = 0; j < q.options().size(); j++) options.add(new Option(j + 1, fieldId, j, q.options().get(j), null, null));
            }
            fields.add(new Field(fieldId, panel.id(), i, q.label(), kind, q.required(), null, q.maxLength(), options));

            String value = answers.get(i).value();
            values.put(fieldId, switch (q.kind()) {
                case CHOICE -> String.valueOf(q.options().indexOf(value) + 1);
                case CHECKBOX -> value.equalsIgnoreCase("Yes") ? "true" : "false";
                default -> value;
            });
        }

        try {
            var ticket = ticketService.openTicket(guild, member, panel, fields, values).get(TICKET_WAIT_SECONDS, TimeUnit.SECONDS);
            forms.setTicket(submissionId, ticket.id());
            return true;
        } catch (Exception e) {
            log.warn("Web form {}: the ticket for submission {} could not be created", form.id(), submissionId, e);
            notes.add("Your answers were saved, but the ticket could not be opened. Someone will still see them.");
            return false;
        }
    }

    /** {@code member} is null for someone not in the server (a site application, T-590); {@code site} names the clan site it came from. */
    private void postToChannel(Guild guild, Member member, long userId, Form form, List<Answer> answers, String site) {
        TextChannel channel = guild.getTextChannelById(form.channelId());
        if (channel == null || !channel.canTalk()) {
            log.warn("Web form {}: channel {} is missing or not writable", form.id(), form.channelId());
            return;
        }
        String from = "From <@" + userId + ">" + (member == null ? " (not in the server yet)" : "") + (site == null ? "" : "\nSent from the clan site " + trim(site, 60));
        EmbedBuilder embed = new EmbedBuilder().setTitle(trim(form.name() + " - new answers", 256)).setDescription(from).setTimestamp(Instant.now());
        if (member != null) embed.setThumbnail(member.getEffectiveAvatarUrl());
        for (var line : WebForms.asLines(answers).entrySet()) {
            if (embed.length() > 5000 || embed.getFields().size() >= 25) break;
            embed.addField(trim(line.getKey(), 256), trim(line.getValue(), 1024), false);
        }
        try {
            channel.sendMessageEmbeds(embed.build()).setAllowedMentions(EnumSet.noneOf(net.dv8tion.jda.api.entities.Message.MentionType.class)).queue(ok -> {}, e -> log.warn("Web form {}: posting to the channel failed", form.id(), e));
        } catch (RuntimeException e) {
            log.warn("Web form {}: posting to the channel failed", form.id(), e);
        }
    }

    private void giveRole(Guild guild, Member member, Form form, List<String> notes) {
        Role role = guild.getRoleById(form.roleId());
        if (role == null || !MemberApi.safeToAssign(guild, role)) {
            log.warn("Web form {}: role {} is missing or not safe to hand out", form.id(), form.roleId());
            return;
        }
        guild.addRoleToMember(member, role).reason("Web form: " + form.name()).queue(ok -> {}, e -> log.warn("Web form {}: giving the role failed", form.id(), e));
    }

    // ---------- small helpers ----------

    /** Null when they're not in the server; {@link DiscordLookups.Slow} (a 503) when Discord didn't answer in time. */
    private static Member memberOf(Guild guild, long userId) {
        return DiscordLookups.memberOrSlow(guild, userId);
    }

    private static String trim(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
