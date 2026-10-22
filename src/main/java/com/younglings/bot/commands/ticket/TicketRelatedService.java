package com.younglings.bot.commands.ticket;

import com.younglings.bot.discord.Containers;
import com.younglings.bot.i18n.Text;
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
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.channel.attribute.IPermissionContainer;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The two buttons of the related tickets notice. <b>Link</b> (a helper on either ticket, the panels' helper and staff roles, or an admin) records that the two tickets
 * are about the same group content and says so in both channels. <b>Merge</b> (staff and admins only) moves the member of the new ticket into the other ticket's
 * channel and closes the new one, with its transcript saved as for any closed ticket, and records the link. Off unless the server switched related tickets on.
 */
@BService
public class TicketRelatedService {
    private static final Logger log = LoggerFactory.getLogger(TicketRelatedService.class);

    private final TicketRepository tickets;
    private final HelpExtrasRepository extras;
    private final TicketService service;
    private final AdminRoleFilter adminRoleFilter;

    public TicketRelatedService(TicketRepository tickets, HelpExtrasRepository extras, TicketService service, AdminRoleFilter adminRoleFilter) {
        this.tickets = tickets;
        this.extras = extras;
        this.service = service;
        this.adminRoleFilter = adminRoleFilter;
    }

    /** Whether this member may merge tickets: staff roles of the panel and admins, not helpers as such. Pure. */
    static boolean mayMerge(Set<Long> memberRoles, boolean admin, PanelRoles roles) {
        return TicketRules.isStaff(roles, memberRoles, admin);
    }

    public void handle(ButtonInteractionEvent event, Guild guild, Member member, boolean merge, long from, long other) {
        Ticket a = tickets.getTicket(from);
        Ticket b = tickets.getTicket(other);
        if (a == null || b == null || a.guildId() != guild.getIdLong() || b.guildId() != guild.getIdLong()) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "ticket.one_of_these_tickets_is_no"));
            return;
        }
        HelpExtras settings = extras.get(guild.getIdLong());
        if (!settings.relatedTickets()) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "ticket.related_tickets_aren_t_switched_on"));
            return;
        }
        if (a.status() == Status.CLOSED || b.status() == Status.CLOSED) {
            Containers.replyEphemeral(event, Containers.INFO, Text.of(event, "ticket.one_of_these_tickets_is_already"));
            return;
        }
        Panel panelA = a.panelId() == null ? null : tickets.getPanel(a.panelId());
        Panel panelB = b.panelId() == null ? null : tickets.getPanel(b.panelId());
        if (panelA == null || panelB == null || !panelA.isHelpPanel() || !panelB.isHelpPanel()) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "ticket.only_help_tickets_can_be_linked"));
            return;
        }
        Set<Long> memberRoles = member.getRoles().stream().map(r -> r.getIdLong()).collect(Collectors.toSet());
        boolean admin = member.hasPermission(Permission.MANAGE_SERVER) || adminRoleFilter.isAuthorized(guild, member);
        if (merge) {
            if (!mayMerge(memberRoles, admin, tickets.getRoles(panelA.id()))) {
                Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "ticket.only_staff_and_admins_can_merge"));
                return;
            }
        } else {
            boolean ok = TicketEventService.mayMake(member.getIdLong(), memberRoles, admin, tickets.getRoles(panelA.id()), tickets.getHelpers(a.id()))
                    || TicketEventService.mayMake(member.getIdLong(), memberRoles, admin, tickets.getRoles(panelB.id()), tickets.getHelpers(b.id()));
            if (!ok) {
                Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "ticket.only_helpers_and_staff_can_link"));
                return;
            }
        }
        GuildMessageChannel channelA = a.channelId() == null ? null : guild.getChannelById(GuildMessageChannel.class, a.channelId());
        GuildMessageChannel channelB = b.channelId() == null ? null : guild.getChannelById(GuildMessageChannel.class, b.channelId());
        if (channelA == null || channelB == null) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "ticket.one_of_the_ticket_channels_is"));
            return;
        }
        if (merge) merge(event, guild, member, a, b, channelA, channelB);
        else link(event, member, a, b, channelA, channelB);
    }

    private void link(ButtonInteractionEvent event, Member member, Ticket a, Ticket b, GuildMessageChannel channelA, GuildMessageChannel channelB) {
        if (!extras.link(a.id(), b.id(), member.getIdLong())) {
            Containers.replyEphemeral(event, Containers.INFO, Text.of(event, "ticket.these_two_tickets_are_already_linked"));
            return;
        }
        say(channelA, "🔗 Linked with ticket " + RelatedTickets.number(b.number()) + " (<#" + channelB.getIdLong() + ">): both are about the same group boss. Linked by " + member.getAsMention() + ".");
        say(channelB, "🔗 Linked with ticket " + RelatedTickets.number(a.number()) + " (<#" + channelA.getIdLong() + ">): both are about the same group boss. Linked by " + member.getAsMention() + ".");
        Containers.replyEphemeral(event, Containers.SUCCESS, Text.of(event, "ticket.linked_ticket_with", RelatedTickets.number(a.number()), RelatedTickets.number(b.number())));
        log.info("{} linked tickets {} and {}", member.getId(), a.id(), b.id());
    }

    private void merge(ButtonInteractionEvent event, Guild guild, Member member, Ticket from, Ticket into, GuildMessageChannel channelFrom, GuildMessageChannel channelInto) {
        event.deferReply(true).queue();
        extras.link(from.id(), into.id(), member.getIdLong());
        guild.retrieveMemberById(from.requesterId()).queue(requester -> {
            // the member joins the other ticket's channel with the same rights a requester has in their own
            if (channelInto instanceof IPermissionContainer container) {
                container.upsertPermissionOverride(requester).grant(participantPermissions()).queue();
            }
            say(channelInto, Text.of(guild, "ticket.related.joined_from", requester.getAsMention(), RelatedTickets.number(from.number()), member.getAsMention()));
            service.closeTicket(guild, from, member.getIdLong(), "Merged into ticket " + RelatedTickets.number(into.number()) + " (<#" + channelInto.getIdLong() + ">)")
                    .whenComplete((ignored, error) -> event.getHook().editOriginalComponents(List.of(Containers.toast(error == null ? Containers.SUCCESS : Containers.DANGER,
                            error == null ? Text.of(guild, "ticket.related.merged", RelatedTickets.number(from.number()), RelatedTickets.number(into.number()))
                                    : Text.of(guild, "ticket.related.merge_close_failed")))).useComponentsV2(true).queue());
            log.info("{} merged ticket {} into {}", member.getId(), from.id(), into.id());
        }, error -> {
            log.warn("Couldn't merge ticket {} into {}: the member could not be found", from.id(), into.id(), error);
            event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.WARNING, Text.of(guild, "ticket.related.member_not_found")))).useComponentsV2(true).queue();
        });
    }

    private static EnumSet<Permission> participantPermissions() {
        return TicketRules.participantPermissions();
    }

    private static void say(GuildMessageChannel channel, String text) {
        channel.sendMessage(text).setAllowedMentions(EnumSet.noneOf(net.dv8tion.jda.api.entities.Message.MentionType.class))
                .queue(null, error -> log.warn("Couldn't post in {}", channel.getId(), error));
    }
}
