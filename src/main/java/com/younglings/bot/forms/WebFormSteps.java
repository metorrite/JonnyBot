package com.younglings.bot.forms;

import com.younglings.bot.commands.ticket.TicketService;
import com.younglings.bot.forms.WebForms.Answer;
import com.younglings.bot.forms.WebForms.Form;
import com.younglings.bot.forms.WebForms.Question;
import com.younglings.bot.internal.MemberApi;
import com.younglings.bot.ticket.TicketModels.Field;
import com.younglings.bot.ticket.TicketModels.FieldKind;
import com.younglings.bot.ticket.TicketModels.Option;
import com.younglings.bot.ticket.TicketModels.Panel;
import com.younglings.bot.ticket.TicketRepository;
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
import java.util.concurrent.TimeUnit;

/**
 * The Discord side of a web form submission, shared by a send from the bot's own form page ({@link WebFormService#submit}) and a clan site's
 * application, which runs these on the Discord job outbox ({@link SiteApplicationJob}, T-590) so the website's request never waits on Discord.
 * Each step that cannot be done is reported in {@code notes} and skipped; none throws away the saved answers.
 */
final class WebFormSteps {
    private static final Logger log = LoggerFactory.getLogger(WebFormSteps.class);
    static final int TICKET_WAIT_SECONDS = 15;

    private final WebFormRepository forms;
    private final TicketRepository tickets;
    private final TicketService ticketService;

    WebFormSteps(WebFormRepository forms, TicketRepository tickets, TicketService ticketService) {
        this.forms = forms;
        this.tickets = tickets;
        this.ticketService = ticketService;
    }

    boolean openTicket(Guild guild, Member member, Form form, long submissionId, List<Answer> answers, List<String> notes) {
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
    void postToChannel(Guild guild, Member member, long userId, Form form, List<Answer> answers, String site) {
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

    void giveRole(Guild guild, Member member, Form form, List<String> notes) {
        Role role = guild.getRoleById(form.roleId());
        if (role == null || !MemberApi.safeToAssign(guild, role)) {
            log.warn("Web form {}: role {} is missing or not safe to hand out", form.id(), form.roleId());
            return;
        }
        guild.addRoleToMember(member, role).reason("Web form: " + form.name()).queue(ok -> {}, e -> log.warn("Web form {}: giving the role failed", form.id(), e));
    }

    private static String trim(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
