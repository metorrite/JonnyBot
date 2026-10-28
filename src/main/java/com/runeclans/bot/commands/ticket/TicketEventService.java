package com.runeclans.bot.commands.ticket;

import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.events.EventProblem;
import com.runeclans.bot.events.EventService;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.permission.AdminRoleFilter;
import com.runeclans.bot.ticket.HelpExtras;
import com.runeclans.bot.ticket.HelpExtrasRepository;
import com.runeclans.bot.ticket.TicketModels.Panel;
import com.runeclans.bot.ticket.TicketModels.PanelRoles;
import com.runeclans.bot.ticket.TicketModels.Status;
import com.runeclans.bot.ticket.TicketModels.Ticket;
import com.runeclans.bot.ticket.TicketRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The "Make it an event" button of a help ticket (off until the server switches it on): turns the ticket into a boss-night event for the boss it asks about, opens
 * its signups, and puts the member and the helpers who joined the ticket on the roster. It can be done once per ticket. Who may press it: a helper who joined the
 * ticket, the panel's helper and staff roles, and admins; not the member on their own.
 */
@BService
public class TicketEventService {
    private static final Logger log = LoggerFactory.getLogger(TicketEventService.class);

    private final TicketRepository tickets;
    private final HelpExtrasRepository extras;
    private final EventService events;
    private final AdminRoleFilter adminRoleFilter;

    public TicketEventService(TicketRepository tickets, HelpExtrasRepository extras, EventService events, AdminRoleFilter adminRoleFilter) {
        this.tickets = tickets;
        this.extras = extras;
        this.events = events;
        this.adminRoleFilter = adminRoleFilter;
    }

    /** Whether this member may turn this ticket into an event. Pure apart from the sets it is given, so it can be tested. */
    static boolean mayMake(long memberId, Set<Long> memberRoles, boolean admin, PanelRoles roles, List<Long> joinedHelpers) {
        if (admin || joinedHelpers.contains(memberId)) return true;
        return memberRoles.stream().anyMatch(r -> roles.helperRoleIds().contains(r) || roles.staffRoleIds().contains(r));
    }

    public void handle(ButtonInteractionEvent event, Guild guild, Member member, long ticketId) {
        Ticket ticket = tickets.getTicket(ticketId);
        Panel panel = ticket == null || ticket.panelId() == null ? null : tickets.getPanel(ticket.panelId());
        if (ticket == null || panel == null || ticket.guildId() != guild.getIdLong()) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "ticket.this_ticket_is_no_longer_available"));
            return;
        }
        if (ticket.status() == Status.CLOSED) {
            Containers.replyEphemeral(event, Containers.INFO, Text.of(event, "ticket.this_ticket_is_closed"));
            return;
        }
        HelpExtras settings = extras.get(guild.getIdLong());
        if (!settings.eventButton() || !panel.isHelpPanel()) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "ticket.making_events_from_tickets_isn_t"));
            return;
        }
        Set<Long> memberRoles = member.getRoles().stream().map(r -> r.getIdLong()).collect(Collectors.toSet());
        boolean admin = member.hasPermission(Permission.MANAGE_SERVER) || adminRoleFilter.isAuthorized(guild, member);
        if (!mayMake(member.getIdLong(), memberRoles, admin, tickets.getRoles(panel.id()), tickets.getHelpers(ticketId))) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "ticket.only_a_helper_on_this_ticket"));
            return;
        }
        var existing = extras.eventOf(ticketId);
        if (existing.isPresent()) {
            Containers.replyEphemeral(event, Containers.INFO, Text.of(event, "ticket.this_ticket_already_became_event_sign", existing.getAsLong(), existing.getAsLong()));
            return;
        }
        TicketEventPlan.Plan plan;
        try {
            plan = TicketEventPlan.plan(tickets.getFields(panel.id()), ticket.answers(), ticket.number(), settings, Instant.now());
        } catch (IllegalArgumentException e) {
            Containers.replyEphemeral(event, Containers.WARNING, e.getMessage());
            return;
        }
        if (!extras.claimEvent(ticketId, member.getIdLong())) {
            Containers.replyEphemeral(event, Containers.INFO, Text.of(event, "ticket.someone_is_already_making_an_event"));
            return;
        }
        event.deferReply().queue();
        long guildId = guild.getIdLong();
        try {
            long eventId = events.createEvent(guildId, member.getIdLong(), plan.input());
            extras.setEvent(ticketId, eventId);
            events.moveStage(guildId, eventId, com.runeclans.bot.events.EventRules.Status.SIGNUPS);
            Set<Long> people = new LinkedHashSet<>();
            people.add(ticket.requesterId());
            people.addAll(tickets.getHelpers(ticketId));
            List<Long> added = new ArrayList<>();
            List<Long> notAdded = new ArrayList<>();
            for (long user : people) {
                try {
                    events.join(guildId, eventId, guildId, user, null, true, null);
                    added.add(user);
                } catch (EventProblem p) {
                    log.info("Couldn't add {} to the event made from ticket {}: {}", user, ticketId, p.getMessage());
                    notAdded.add(user);
                }
            }
            event.getHook().sendMessageComponents(Containers.toast(Containers.SUCCESS, lines(event.getGuild().getIdLong(), plan, eventId, added, notAdded))).useComponentsV2(true).queue();
            log.info("{} made event {} from ticket {}", member.getId(), eventId, ticketId);
        } catch (EventProblem | IllegalArgumentException e) {
            // nothing was made (or it can't be used): free the ticket so the button can be pressed again
            if (extras.eventOf(ticketId).isEmpty()) extras.forgetEvent(ticketId);
            event.getHook().sendMessageComponents(Containers.toast(Containers.WARNING, Text.of(event, "ticket.i_couldn_t_make_the_event", e.getMessage()))).useComponentsV2(true).queue();
        }
    }

    static String[] lines(long guildId, TicketEventPlan.Plan plan, long eventId, List<Long> added, List<Long> notAdded) {
        List<String> lines = new ArrayList<>();
        lines.add(Text.of(guildId, "ticket.event.made_from_this_ticket", plan.input().name()));
        lines.add(Text.of(guildId, "ticket.event.signups_are_open", plan.summary(), eventId));
        if (!added.isEmpty()) lines.add(Text.of(guildId, "ticket.event.on_the_roster", added.stream().map(id -> "<@" + id + ">").collect(Collectors.joining(", "))));
        if (!notAdded.isEmpty()) lines.add(Text.of(guildId, "ticket.event.couldnt_be_added", notAdded.stream().map(id -> "<@" + id + ">").collect(Collectors.joining(", "))));
        lines.add(Text.of(guildId, "ticket.event.staff_prizes_teams"));
        return lines.toArray(String[]::new);
    }
}
