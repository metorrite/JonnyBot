package com.younglings.bot.commands.ticket;

import com.younglings.bot.discord.Containers;
import com.younglings.bot.ticket.HelpExtras;
import com.younglings.bot.ticket.HelpExtrasRepository;
import com.younglings.bot.ticket.TicketModels.Field;
import com.younglings.bot.ticket.TicketModels.Panel;
import com.younglings.bot.ticket.TicketModels.Status;
import com.younglings.bot.ticket.TicketModels.Ticket;
import com.younglings.bot.ticket.TicketRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * When a help ticket opens for a group boss that another open ticket also asks about, posts a notice in the new ticket with buttons to link them or merge (see
 * {@link RelatedTickets}). Does nothing unless the server switched related tickets on. Never lets a failure here get in the way of the ticket opening.
 */
@BService
public class TicketRelatedNotice {
    private static final Logger log = LoggerFactory.getLogger(TicketRelatedNotice.class);

    private final TicketRepository tickets;
    private final HelpExtrasRepository extras;

    public TicketRelatedNotice(TicketRepository tickets, HelpExtrasRepository extras) {
        this.tickets = tickets;
        this.extras = extras;
    }

    public void announce(Ticket ticket, Panel panel, GuildMessageChannel channel) {
        try {
            if (panel == null || !panel.isHelpPanel()) return;
            HelpExtras settings = extras.get(ticket.guildId());
            if (!settings.relatedTickets()) return;

            Map<Long, List<Field>> fieldsByPanel = new HashMap<>();
            RelatedTickets.Candidate me = candidate(ticket, fieldsByPanel);
            if (me == null || !settings.isGroupBoss(me.boss())) return;
            List<RelatedTickets.Candidate> others = new ArrayList<>();
            for (Ticket other : tickets.listTickets(ticket.guildId(), Status.OPEN, null, 200, 0)) {
                if (other.id() == ticket.id()) continue;
                RelatedTickets.Candidate c = candidate(other, fieldsByPanel);
                if (c != null) others.add(c);
            }
            List<RelatedTickets.Candidate> siblings = RelatedTickets.siblings(settings, me, others);
            if (siblings.isEmpty()) return;

            List<ContainerChildComponent> children = new ArrayList<>();
            children.add(TextDisplay.of(RelatedTickets.notice(me, siblings)));
            List<Button> buttons = new ArrayList<>();
            for (RelatedTickets.Candidate s : siblings) {
                buttons.add(Button.secondary(RelatedTickets.linkId(ticket.id(), s.ticketId()), "Link with " + RelatedTickets.number(s.number())));
                buttons.add(Button.danger(RelatedTickets.mergeId(ticket.id(), s.ticketId()), "Merge into " + RelatedTickets.number(s.number())));
            }
            children.add(ActionRow.of(buttons));
            channel.sendMessageComponents(Containers.card(Containers.INFO, children)).useComponentsV2(true)
                    .setAllowedMentions(EnumSet.noneOf(net.dv8tion.jda.api.entities.Message.MentionType.class))
                    .queue(null, error -> log.warn("Couldn't post the related tickets notice in ticket {}", ticket.id(), error));
        } catch (Exception e) {
            log.warn("Related tickets check failed for ticket {}", ticket.id(), e);
        }
    }

    private RelatedTickets.Candidate candidate(Ticket t, Map<Long, List<Field>> fieldsByPanel) {
        if (t.panelId() == null) return null;
        List<Field> fields = fieldsByPanel.computeIfAbsent(t.panelId(), tickets::getFields);
        String boss = RelatedTickets.bossOf(fields, t.answers());
        return boss.isEmpty() ? null : new RelatedTickets.Candidate(t.id(), t.number(), t.channelId(), t.requesterId(), boss);
    }
}
