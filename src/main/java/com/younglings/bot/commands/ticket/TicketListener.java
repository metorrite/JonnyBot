package com.younglings.bot.commands.ticket;

import com.younglings.bot.discord.Containers;
import com.younglings.bot.ticket.TicketModels.Field;
import com.younglings.bot.ticket.TicketModels.FieldKind;
import com.younglings.bot.ticket.TicketModels.Panel;
import com.younglings.bot.ticket.TicketModels.Status;
import com.younglings.bot.ticket.TicketModels.Ticket;
import com.younglings.bot.ticket.TicketRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.modals.Modal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every button and form of the ticket system. Each custom id carries just a panel or ticket id
 * ({@code ticket_open:<panel>}, {@code ticket_join:<ticket>}, {@code ticket_close:<ticket>}), and everything
 * else is read from the database on the click — so panels and tickets posted before a restart keep working.
 */
@BService
public class TicketListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(TicketListener.class);

    private final TicketRepository repository;
    private final TicketService service;
    private final HelpTicketFlow helpFlow;

    public TicketListener(TicketRepository repository, TicketService service, HelpTicketFlow helpFlow) {
        this.repository = repository;
        this.service = service;
        this.helpFlow = helpFlow;
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith("ticket_")) return;

        try {
            Guild guild = event.getGuild();
            Member member = event.getMember();
            if (guild == null || member == null) return;

            if (id.startsWith(TicketView.OPEN_PREFIX)) handleOpen(event, guild, member, parse(id, TicketView.OPEN_PREFIX));
            else if (id.startsWith(TicketView.JOIN_PREFIX)) handleJoin(event, guild, member, parse(id, TicketView.JOIN_PREFIX));
            else if (id.startsWith(TicketView.CLOSE_PREFIX)) handleCloseButton(event, guild, member, parse(id, TicketView.CLOSE_PREFIX));
        } catch (Exception e) {
            log.error("Unhandled exception in ticket button '{}'", id, e);
            Containers.replyError(event);
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        String id = event.getModalId();
        if (!id.startsWith("ticket_")) return;

        try {
            Guild guild = event.getGuild();
            Member member = event.getMember();
            if (guild == null || member == null) return;

            if (id.startsWith("ticket_form:")) handleForm(event, guild, member, parse(id, "ticket_form:"));
            else if (id.startsWith("ticket_close_modal:")) handleCloseModal(event, guild, member, parse(id, "ticket_close_modal:"));
        } catch (Exception e) {
            log.error("Unhandled exception in ticket form '{}'", id, e);
            Containers.replyError(event);
        }
    }

    // ---------- open ----------

    private void handleOpen(ButtonInteractionEvent event, Guild guild, Member member, long panelId) {
        Panel panel = repository.getPanel(panelId);
        if (panel == null || panel.guildId() != guild.getIdLong()) {
            Containers.replyEphemeral(event, Containers.WARNING, "That ticket type no longer exists.");
            return;
        }
        List<Field> fields = repository.getFields(panelId);
        Optional<String> blocked = service.cannotOpen(guild, member.getIdLong(), panel, fields);
        if (blocked.isPresent()) {
            Containers.replyEphemeral(event, Containers.WARNING, blocked.get());
            return;
        }
        // A PvM Help or CA Help panel is asked for step by step (boss, tier, achievement), not as one long form.
        if (HelpTicketFlow.applies(panel, fields)) {
            helpFlow.start(event, panel);
            return;
        }
        event.replyModal(service.buildForm(panel, fields)).queue();
    }

    private void handleForm(ModalInteractionEvent event, Guild guild, Member member, long panelId) {
        Panel panel = repository.getPanel(panelId);
        if (panel == null || panel.guildId() != guild.getIdLong()) {
            Containers.replyEphemeral(event, Containers.WARNING, "That ticket type no longer exists.");
            return;
        }
        List<Field> fields = repository.getFields(panelId);
        Optional<String> blocked = service.cannotOpen(guild, member.getIdLong(), panel, fields); // re-checked: a second form could have been submitted meanwhile
        if (blocked.isPresent()) {
            Containers.replyEphemeral(event, Containers.WARNING, blocked.get());
            return;
        }

        Map<Long, String> raw = readValues(event, fields);

        Optional<String> needsAttempts = service.attemptsProblem(guild, member, panel, fields, raw);
        if (needsAttempts.isPresent()) {
            Containers.replyEphemeral(event, Containers.WARNING, needsAttempts.get());
            return;
        }

        event.deferReply(true).queue();
        service.openTicket(guild, member, panel, fields, raw).whenComplete((ticket, error) -> {
            if (error != null || ticket == null || ticket.channelId() == null) {
                event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.DANGER,
                        "I couldn't open the ticket — an admin needs to check that I can create channels in its category."))).useComponentsV2(true).queue();
            } else {
                event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.SUCCESS,
                        "✅ Your ticket is open: <#" + ticket.channelId() + ">"))).useComponentsV2(true).queue();
            }
        });
    }

    /** What was submitted for each of these questions, by question id: the text, the chosen option's id, or "true"/"false". */
    static Map<Long, String> readValues(ModalInteractionEvent event, List<Field> fields) {
        Map<Long, String> raw = new HashMap<>();
        for (Field field : fields) {
            var mapping = event.getValue("f" + field.id());
            if (mapping == null) continue;
            switch (field.kind()) {
                case SHORT, PARAGRAPH -> raw.put(field.id(), mapping.getAsString());
                case SELECT -> {
                    List<String> picked = mapping.getAsStringList();
                    if (!picked.isEmpty()) raw.put(field.id(), picked.getFirst());
                }
                case CHECKBOX -> raw.put(field.id(), String.valueOf(mapping.getAsBoolean()));
            }
        }
        return raw;
    }

    // ---------- join ----------

    private void handleJoin(ButtonInteractionEvent event, Guild guild, Member member, long ticketId) {
        Ticket ticket = repository.getTicket(ticketId);
        Panel panel = ticket == null || ticket.panelId() == null ? null : repository.getPanel(ticket.panelId());
        if (ticket == null || panel == null || ticket.guildId() != guild.getIdLong()) {
            Containers.replyEphemeral(event, Containers.WARNING, "This ticket is no longer available.");
            return;
        }

        TicketRules.JoinResult result = service.tryJoin(guild, member, ticket, panel);
        switch (result) {
            case OK -> {
                Ticket fresh = repository.getTicket(ticketId);
                List<String> rsns = service.rsnsOf(guild.getIdLong(), fresh.requesterId());
                List<Long> helpers = repository.getHelpers(ticketId);
                Long pingRole = service.pingRoleFor(panel, fresh);
                List<Long> pingUsers = service.pingUsersFor(fresh);
                if (event.getMessage().getFlags().contains(net.dv8tion.jda.api.entities.Message.MessageFlag.IS_COMPONENTS_V2)) {
                    // Opened before the Ticket Tool style layout: still the older card.
                    event.editComponents(List.of(TicketView.welcome(panel, fresh, rsns, helpers, pingRole))).useComponentsV2(true).queue();
                } else {
                    event.editMessage(TicketView.opening(panel, fresh, rsns, helpers, pingRole, pingUsers).toEdit()).queue();
                }
            }
            case TICKET_CLOSED -> Containers.replyEphemeral(event, Containers.WARNING, "This ticket is already closed.");
            case NO_HELPER_SYSTEM -> Containers.replyEphemeral(event, Containers.WARNING, "This ticket type doesn't use helpers.");
            case IS_REQUESTER -> Containers.replyEphemeral(event, Containers.WARNING, "It's your own ticket — you can't join it as a helper.");
            case ALREADY_HELPER -> Containers.replyEphemeral(event, Containers.INFO, "You've already joined this ticket.");
            case NOT_ELIGIBLE -> Containers.replyEphemeral(event, Containers.WARNING, "Only helpers can join. Ask an admin for the helper role if you'd like to help out.");
            case FULL -> Containers.replyEphemeral(event, Containers.WARNING, "This ticket already has " + panel.helperCap() + " helper(s) — that's the limit.");
        }
    }

    // ---------- close ----------

    private void handleCloseButton(ButtonInteractionEvent event, Guild guild, Member member, long ticketId) {
        Ticket ticket = repository.getTicket(ticketId);
        if (ticket == null || ticket.guildId() != guild.getIdLong()) {
            Containers.replyEphemeral(event, Containers.WARNING, "This ticket is no longer available.");
            return;
        }
        if (ticket.status() == Status.CLOSED) {
            Containers.replyEphemeral(event, Containers.INFO, "This ticket is already closed.");
            return;
        }
        Panel panel = ticket.panelId() == null ? null : repository.getPanel(ticket.panelId());
        if (!service.mayClose(guild, member, ticket, panel)) {
            Containers.replyEphemeral(event, Containers.WARNING, "You don't have permission to close this ticket.");
            return;
        }

        TextInput reason = TextInput.create("reason", TextInputStyle.PARAGRAPH)
                .setPlaceholder("Optional — what was resolved, or why it's being closed")
                .setRequired(false)
                .setMaxLength(500)
                .build();
        event.replyModal(Modal.create("ticket_close_modal:" + ticketId, "Close this ticket").addComponents(Label.of("Reason", reason)).build()).queue();
    }

    private void handleCloseModal(ModalInteractionEvent event, Guild guild, Member member, long ticketId) {
        Ticket ticket = repository.getTicket(ticketId);
        Panel panel = ticket == null || ticket.panelId() == null ? null : repository.getPanel(ticket.panelId());
        if (ticket == null || ticket.guildId() != guild.getIdLong() || !service.mayClose(guild, member, ticket, panel)) {
            Containers.replyEphemeral(event, Containers.WARNING, "You can't close this ticket.");
            return;
        }

        String reason = event.getValue("reason").getAsString().strip();
        event.deferReply(true).queue();
        service.closeTicket(guild, ticket, member.getIdLong(), reason.isEmpty() ? null : reason)
                .whenComplete((ignored, error) -> event.getHook().editOriginalComponents(List.of(Containers.toast(
                        error == null ? Containers.SUCCESS : Containers.DANGER,
                        error == null ? "Ticket closed — the transcript is saved." : "Something went wrong closing the ticket — check the logs."))).useComponentsV2(true).queue());
    }

    private static long parse(String id, String prefix) {
        try {
            return Long.parseLong(id.substring(prefix.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
