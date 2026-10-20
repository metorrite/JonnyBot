package com.younglings.bot.commands.ticket;

import com.younglings.bot.discord.Containers;
import com.younglings.bot.events.EventProblem;
import com.younglings.bot.events.EventService;
import com.younglings.bot.permission.AdminRoleFilter;
import com.younglings.bot.ticket.HelpExtras;
import com.younglings.bot.ticket.HelpExtrasRepository;
import com.younglings.bot.ticket.TicketModels.Panel;
import com.younglings.bot.ticket.TicketModels.PanelRoles;
import com.younglings.bot.ticket.TicketModels.Status;
import com.younglings.bot.ticket.TicketModels.Ticket;
import com.younglings.bot.ticket.TicketRepository;
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
            Containers.replyEphemeral(event, Containers.WARNING, "This ticket is no longer available.");
            return;
        }
        if (ticket.status() == Status.CLOSED) {
            Containers.replyEphemeral(event, Containers.INFO, "This ticket is closed.");
            return;
        }
        HelpExtras settings = extras.get(guild.getIdLong());
        if (!settings.eventButton() || !panel.isHelpPanel()) {
            Containers.replyEphemeral(event, Containers.WARNING, "Making events from tickets isn't switched on in this server. An admin can do that on the dashboard's PvM Help page.");
            return;
        }
        Set<Long> memberRoles = member.getRoles().stream().map(r -> r.getIdLong()).collect(Collectors.toSet());
        boolean admin = member.hasPermission(Permission.MANAGE_SERVER) || adminRoleFilter.isAuthorized(guild, member);
        if (!mayMake(member.getIdLong(), memberRoles, admin, tickets.getRoles(panel.id()), tickets.getHelpers(ticketId))) {
            Containers.replyEphemeral(event, Containers.WARNING, "Only a helper on this ticket, the ticket's helpers and staff, or an admin can make it an event.");
            return;
        }
        var existing = extras.eventOf(ticketId);
        if (existing.isPresent()) {
            Containers.replyEphemeral(event, Containers.INFO, "This ticket already became event **#" + existing.getAsLong() + "**. Sign up with `/events join id:" + existing.getAsLong() + "`.");
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
            Containers.replyEphemeral(event, Containers.INFO, "Someone is already making an event from this ticket.");
            return;
        }
        event.deferReply().queue();
        long guildId = guild.getIdLong();
        try {
            long eventId = events.createEvent(guildId, member.getIdLong(), plan.input());
            extras.setEvent(ticketId, eventId);
            events.moveStage(guildId, eventId, com.younglings.bot.events.EventRules.Status.SIGNUPS);
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
            event.getHook().sendMessageComponents(Containers.toast(Containers.SUCCESS, lines(plan, eventId, added, notAdded))).useComponentsV2(true).queue();
            log.info("{} made event {} from ticket {}", member.getId(), eventId, ticketId);
        } catch (EventProblem | IllegalArgumentException e) {
            // nothing was made (or it can't be used): free the ticket so the button can be pressed again
            if (extras.eventOf(ticketId).isEmpty()) extras.forgetEvent(ticketId);
            event.getHook().sendMessageComponents(Containers.toast(Containers.WARNING, "I couldn't make the event: " + e.getMessage())).useComponentsV2(true).queue();
        }
    }

    static String[] lines(TicketEventPlan.Plan plan, long eventId, List<Long> added, List<Long> notAdded) {
        List<String> lines = new ArrayList<>();
        lines.add("### 🎉 Event made from this ticket: " + plan.input().name());
        lines.add(plan.summary() + ". Signups are open: others can join with `/events join id:" + eventId + "`.");
        if (!added.isEmpty()) lines.add("On the roster: " + added.stream().map(id -> "<@" + id + ">").collect(Collectors.joining(", ")) + ".");
        if (!notAdded.isEmpty()) lines.add("Couldn't be added (they can join themselves): " + notAdded.stream().map(id -> "<@" + id + ">").collect(Collectors.joining(", ")) + ".");
        lines.add("-# Staff: prizes, teams and the exact start time are on the dashboard's Event manager.");
        return lines.toArray(String[]::new);
    }
}
