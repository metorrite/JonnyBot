package com.younglings.bot.commands.ticket;

import com.younglings.bot.permission.AdminRoleFilter;
import com.younglings.bot.permission.MemberAccess;
import com.younglings.bot.runescape.PlayerLink;
import com.younglings.bot.runescape.PlayerLinkService;
import com.younglings.bot.ticket.TicketModels.Answer;
import com.younglings.bot.ticket.TicketModels.Field;
import com.younglings.bot.ticket.TicketModels.HelpSettings;
import com.younglings.bot.ticket.TicketModels.FieldKind;
import com.younglings.bot.ticket.TicketModels.Option;
import com.younglings.bot.ticket.TicketModels.Panel;
import com.younglings.bot.ticket.TicketModels.PanelRoles;
import com.younglings.bot.ticket.TicketModels.Settings;
import com.younglings.bot.ticket.TicketModels.Ticket;
import com.younglings.bot.ticket.TicketRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.checkbox.Checkbox;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.exceptions.ErrorHandler;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.restaction.ChannelAction;
import net.dv8tion.jda.api.utils.FileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * The ticket system's flows: opening a ticket (form → private channel → opening message), helpers joining,
 * closing with a saved transcript, and the two background jobs — pinging the next tier when nobody joins, and
 * deleting closed tickets' channels. Every decision is read from the database each time, so a restart in the
 * middle of anything loses nothing.
 */
@BService
public class TicketService {
    private static final Logger log = LoggerFactory.getLogger(TicketService.class);

    /** How many messages a transcript keeps, newest first; a ticket longer than this loses its oldest messages. */
    static final int MAX_TRANSCRIPT_MESSAGES = 5000;

    private final TicketRepository repository;
    private final PlayerLinkService linkService;
    private final AdminRoleFilter adminRoleFilter;
    private final MemberAccess memberAccess;
    private final HelpPingService helpPings;

    public TicketService(TicketRepository repository, PlayerLinkService linkService, AdminRoleFilter adminRoleFilter, MemberAccess memberAccess,
                         HelpPingService helpPings) {
        this.repository = repository;
        this.linkService = linkService;
        this.adminRoleFilter = adminRoleFilter;
        this.memberAccess = memberAccess;
        this.helpPings = helpPings;
    }

    // ================= the form =================

    /** The form for a panel: one input per question, in order. */
    public Modal buildForm(Panel panel, List<Field> fields) {
        return buildForm("ticket_form:" + panel.id(), panel, fields);
    }

    /** A form with the given modal id for some of a panel's questions (the guided help flow asks the rest itself). */
    public Modal buildForm(String modalId, Panel panel, List<Field> fields) {
        Modal.Builder modal = Modal.create(modalId, truncate(panel.title(), 45));
        for (Field field : fields) {
            String id = "f" + field.id();
            switch (field.kind()) {
                case SHORT, PARAGRAPH -> {
                    TextInput.Builder input = TextInput.create(id, field.kind() == FieldKind.SHORT ? TextInputStyle.SHORT : TextInputStyle.PARAGRAPH)
                            .setRequired(field.required())
                            .setMaxLength(field.maxLength() != null ? field.maxLength() : (field.kind() == FieldKind.SHORT ? 100 : 1000));
                    if (field.placeholder() != null && !field.placeholder().isBlank()) input.setPlaceholder(truncate(field.placeholder(), 100));
                    modal.addComponents(Label.of(field.label(), input.build()));
                }
                case SELECT -> {
                    StringSelectMenu.Builder menu = StringSelectMenu.create(id).setRequiredRange(field.required() ? 1 : 0, 1).setRequired(field.required());
                    if (field.placeholder() != null && !field.placeholder().isBlank()) menu.setPlaceholder(truncate(field.placeholder(), 100));
                    for (Option option : field.options()) menu.addOption(truncate(option.label(), 100), String.valueOf(option.id()));
                    modal.addComponents(Label.of(field.label(), menu.build()));
                }
                case CHECKBOX -> modal.addComponents(Label.of(field.label(), Checkbox.of(id)));
            }
        }
        return modal.build();
    }

    /** Why this person can't open a ticket on this panel right now, or empty if they can. */
    public Optional<String> cannotOpen(Guild guild, long userId, Panel panel, List<Field> fields) {
        if (!panel.enabled()) return Optional.of("This ticket type isn't accepting new tickets right now.");
        if (fields.size() > TicketRules.MAX_FIELDS) return Optional.of("This ticket form is set up incorrectly — an admin needs to fix it.");
        if (fields.isEmpty()) return Optional.of("This ticket form has no questions yet — an admin needs to set it up.");

        int open = repository.countOpenFor(guild.getIdLong(), panel.id(), userId);
        if (open >= panel.perUserLimit()) {
            return Optional.of(panel.perUserLimit() == 1 ? "You already have an open ticket of this type — close it first."
                    : "You already have " + open + " open tickets of this type — close one first.");
        }
        return Optional.empty();
    }

    // ================= opening =================

    /**
     * Reserves the ticket (and its number) in the database first, then creates the private channel and posts the
     * opening message. If the channel can't be created the ticket is closed with a note rather than left
     * dangling, and the future completes exceptionally.
     *
     * @param rawValues each question's submitted value by field id: the text, the chosen option's id, or "true"/"false"
     */
    public CompletableFuture<Ticket> openTicket(Guild guild, Member requester, Panel panel, List<Field> fields, Map<Long, String> rawValues) {
        List<Answer> answers = new ArrayList<>();
        for (Field field : fields) answers.add(new Answer(field.label(), displayAnswer(field, rawValues.get(field.id()))));

        Optional<Option> routing = TicketRules.routingOption(fields, field -> rawValues.get(field.id()));
        Ticket ticket = repository.createTicket(guild.getIdLong(), panel.id(), requester.getIdLong(),
                routing.map(Option::id).orElse(null), routing.map(Option::label).orElse(null), answers);

        PanelRoles roles = repository.getRoles(panel.id());
        String name = TicketRules.channelName(panel.channelNameTemplate(), ticket.number(), requester.getUser().getName(), ticket.routingLabel());

        Category category = panel.categoryId() == null ? null : guild.getCategoryById(panel.categoryId());
        ChannelAction<TextChannel> action = category != null ? category.createTextChannel(name) : guild.createTextChannel(name);
        action = action.setTopic("Ticket #" + String.format("%04d", ticket.number()) + " · " + requester.getUser().getName());
        for (TicketRules.Overwrite o : TicketRules.overwrites(guild.getPublicRole().getIdLong(), requester.getIdLong(), guild.getSelfMember().getIdLong(), TicketRules.participantRoles(roles))) {
            action = o.isRole() ? action.addRolePermissionOverride(o.id(), o.allow(), o.deny()) : action.addMemberPermissionOverride(o.id(), o.allow(), o.deny());
        }

        PingPlan plan = planPings(guild, requester, panel, ticket, routing.orElse(null), fields, rawValues);
        final ChannelAction<TextChannel> channelAction = action;
        return plan.users().thenCompose(pingUsers -> {
                    // A help ticket remembers who it pinged and how long it waits, so editing its message later shows what really happened.
                    if (panel.isHelpPanel()) repository.setHelpRouting(ticket.id(), plan.escalationOverride(), true, pingUsers);
                    return channelAction.submit().thenCompose(channel -> {
                        repository.setChannel(ticket.id(), channel.getIdLong());
                        Ticket withChannel = repository.getTicket(ticket.id());
                        long[] mentioned = java.util.stream.LongStream.concat(java.util.stream.LongStream.of(requester.getIdLong()),
                                pingUsers.stream().mapToLong(Long::longValue)).toArray();
                        return channel.sendMessage(TicketView.opening(panel, withChannel, rsnsOf(guild.getIdLong(), requester.getIdLong()), List.of(), plan.role(), pingUsers).toCreate())
                                .setAllowedMentions(EnumSet.noneOf(Message.MentionType.class))
                                .mentionUsers(mentioned)
                                .mentionRoles(plan.role() == null ? new long[0] : new long[]{plan.role()})
                                .submit()
                                .thenApply(message -> {
                                    repository.setWelcomeMessage(ticket.id(), message.getIdLong());
                                    return repository.getTicket(ticket.id());
                                });
                    });
                })
                .whenComplete((created, error) -> {
                    if (error != null) {
                        log.warn("Failed to open ticket {} for user {}", ticket.id(), requester.getIdLong(), error);
                        repository.closeTicket(ticket.id(), guild.getSelfMember().getIdLong(), "The ticket channel could not be created.");
                    }
                });
    }

    /** Who a ticket pings as it opens: a role, and/or helpers (looked up asynchronously), plus its own escalation wait on a help panel. */
    private record PingPlan(Long role, CompletableFuture<List<Long>> users, Integer escalationOverride) {}

    /**
     * On an ordinary panel the ticket pings its tier's role. On a PvM Help panel it follows the member or guest settings instead: a guest's
     * pings nobody (unless guest pings are on), and a ping goes to the individual helpers who chose the ticket's tier in their ping
     * settings, not to a role.
     */
    private PingPlan planPings(Guild guild, Member requester, Panel panel, Ticket ticket, Option routing, List<Field> fields, Map<Long, String> rawValues) {
        Long role = TicketRules.pingRole(panel, routing);
        if (!panel.isHelpPanel()) return new PingPlan(role, CompletableFuture.completedFuture(List.of()), null);

        HelpSettings settings = repository.getHelpSettings(guild.getIdLong());
        HelpRules.Pings pings = HelpRules.pingsFor(settings, memberAccess.isMemberTier(guild, requester));
        CompletableFuture<List<Long>> users = pings.pingOnOpen()
                ? helpPings.usersFor(guild, settings, HelpRules.tierOf(fields, rawValues))
                : CompletableFuture.completedFuture(List.of());
        return new PingPlan(null, users, pings.escalationHours() == null ? 0 : pings.escalationHours());
    }

    /** The reason a guest's form can't open a ticket yet (a Master or Grandmaster request with no earlier attempts), or empty if it can. */
    public Optional<String> attemptsProblem(Guild guild, Member requester, Panel panel, List<Field> fields, Map<Long, String> rawValues) {
        if (panel.helpKind() != com.younglings.bot.ticket.TicketModels.HelpKind.CA) return Optional.empty();
        return HelpRules.attemptsProblem(repository.getHelpSettings(guild.getIdLong()), panel, fields, rawValues, memberAccess.isMemberTier(guild, requester));
    }

    private static String displayAnswer(Field field, String raw) {
        if (raw == null) return "";
        return switch (field.kind()) {
            case SELECT -> field.options().stream().filter(o -> String.valueOf(o.id()).equals(raw)).map(Option::label).findFirst().orElse("");
            case CHECKBOX -> "true".equalsIgnoreCase(raw) ? "Yes" : "No";
            default -> raw.strip();
        };
    }

    List<String> rsnsOf(long guildId, long userId) {
        try {
            return linkService.getLinksForUser(guildId, userId).stream().map(PlayerLink::rsn).toList();
        } catch (Exception e) {
            log.warn("Couldn't look up linked RuneScape names for user {}", userId, e);
            return List.of();
        }
    }

    // ================= the opening message =================

    /** Re-renders a ticket's opening message from the database — what the Join button and closing both use. */
    public void refreshWelcome(Guild guild, Ticket ticket) {
        if (ticket.channelId() == null || ticket.welcomeMessageId() == null) return;
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, ticket.channelId());
        if (channel == null) return;

        Panel panel = ticket.panelId() == null ? null : repository.getPanel(ticket.panelId());
        Long pingRole = panel == null ? null : pingRoleFor(panel, ticket);
        List<Long> pingUsers = pingUsersFor(ticket);
        List<String> rsns = rsnsOf(guild.getIdLong(), ticket.requesterId());
        List<Long> helpers = repository.getHelpers(ticket.id());
        channel.retrieveMessageById(ticket.welcomeMessageId()).queue(
                message -> {
                    // A ticket opened before the Ticket Tool style layout still has the older card: keep updating that kind in the old way.
                    if (message.getFlags().contains(Message.MessageFlag.IS_COMPONENTS_V2)) {
                        var container = TicketView.welcome(panel, ticket, rsns, helpers, pingRole);
                        message.editMessageComponents(container).useComponentsV2(true).queue(null, error -> log.warn("Couldn't update ticket {}'s message", ticket.id(), error));
                    } else {
                        message.editMessage(TicketView.opening(panel, ticket, rsns, helpers, pingRole, pingUsers).toEdit()).queue(null, error -> log.warn("Couldn't update ticket {}'s message", ticket.id(), error));
                    }
                },
                error -> log.warn("Couldn't find ticket {}'s opening message", ticket.id()));
    }

    /** The helpers this ticket pinged when it opened, so a later edit of its message shows the same names. */
    List<Long> pingUsersFor(Ticket ticket) {
        return repository.getPingUserIds(ticket.id());
    }

    Long pingRoleFor(Panel panel, Ticket ticket) {
        if (repository.isPingSuppressed(ticket.id())) return null;
        Option routing = routingOptionOf(panel, ticket);
        return TicketRules.pingRole(panel, routing);
    }

    private Option routingOptionOf(Panel panel, Ticket ticket) {
        if (ticket.routingOptionId() == null) return null;
        return repository.getFields(panel.id()).stream().flatMap(f -> f.options().stream())
                .filter(o -> o.id() == ticket.routingOptionId()).findFirst().orElse(null);
    }

    // ================= helpers =================

    public TicketRules.JoinResult tryJoin(Guild guild, Member member, Ticket ticket, Panel panel) {
        PanelRoles roles = repository.getRoles(panel.id());
        Set<Long> memberRoles = member.getRoles().stream().map(r -> r.getIdLong()).collect(Collectors.toSet());
        List<Long> helpers = repository.getHelpers(ticket.id());

        TicketRules.JoinResult result = TicketRules.canJoin(panel, roles, memberRoles, adminRoleFilter.isAuthorized(guild, member),
                ticket.requesterId() == member.getIdLong(), helpers.contains(member.getIdLong()), helpers.size(), ticket.status() == com.younglings.bot.ticket.TicketModels.Status.OPEN);
        if (result != TicketRules.JoinResult.OK) return result;

        if (!repository.addHelper(ticket.id(), member.getIdLong())) return TicketRules.JoinResult.ALREADY_HELPER;

        // A staff member or admin without a helper role may not otherwise see the channel — make sure they can.
        GuildMessageChannel channel = ticket.channelId() == null ? null : guild.getChannelById(GuildMessageChannel.class, ticket.channelId());
        if (channel instanceof TextChannel text) {
            text.upsertPermissionOverride(member).grant(EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY,
                    Permission.MESSAGE_ATTACH_FILES, Permission.MESSAGE_EMBED_LINKS)).queue(null, error -> log.debug("Couldn't grant the helper channel access", error));
        }
        return TicketRules.JoinResult.OK;
    }

    public boolean mayClose(Guild guild, Member member, Ticket ticket, Panel panel) {
        PanelRoles roles = panel == null ? PanelRoles.none() : repository.getRoles(panel.id());
        Set<Long> memberRoles = member.getRoles().stream().map(r -> r.getIdLong()).collect(Collectors.toSet());
        return TicketRules.canClose(ticket, panel, member.getIdLong(), memberRoles, roles, adminRoleFilter.isAuthorized(guild, member), repository.getHelpers(ticket.id()));
    }

    // ================= closing =================

    /**
     * Closes the ticket: marks it closed (so it can only happen once), saves the transcript to the database,
     * posts it to the log channel and DMs it to the requester, updates the opening message, and says the channel
     * is about to go. The channel itself is deleted by the cleanup sweep, which survives a restart.
     */
    public CompletableFuture<Void> closeTicket(Guild guild, Ticket ticket, long closedBy, String reason) {
        if (!repository.closeTicket(ticket.id(), closedBy, reason)) return CompletableFuture.completedFuture(null);

        Ticket closed = repository.getTicket(ticket.id());
        Settings settings = repository.getSettings(guild.getIdLong());
        GuildMessageChannel channel = ticket.channelId() == null ? null : guild.getChannelById(GuildMessageChannel.class, ticket.channelId());
        if (channel == null) return CompletableFuture.completedFuture(null);

        return channel.getIterableHistory().takeAsync(MAX_TRANSCRIPT_MESSAGES)
                .thenAccept(messages -> {
                    Collections.reverse(messages); // newest-first from Discord → oldest-first for reading
                    saveAndSendTranscript(guild, closed, settings, messages);
                    refreshWelcome(guild, closed);
                    channel.sendMessage("🔒 This ticket is closed. The channel will be deleted in " + settings.closeDelaySeconds() + " seconds.")
                            .setAllowedMentions(EnumSet.noneOf(Message.MentionType.class)).queue(null, error -> {});
                })
                .exceptionally(error -> {
                    log.warn("Closing ticket {} hit a problem after marking it closed", ticket.id(), error);
                    return null;
                });
    }

    private void saveAndSendTranscript(Guild guild, Ticket ticket, Settings settings, List<Message> messages) {
        Panel panel = ticket.panelId() == null ? null : repository.getPanel(ticket.panelId());
        List<TicketView.Line> lines = new ArrayList<>();
        for (Message message : messages) lines.add(new TicketView.Line(message.getTimeCreated(), message.getAuthor().getName() + (message.getAuthor().isBot() ? " [bot]" : ""), textOf(message)));

        List<String> helperNames = repository.getHelpers(ticket.id()).stream().map(id -> nameOf(guild, id)).toList();
        String text = TicketView.transcript(ticket, panel == null ? null : panel.title(), nameOf(guild, ticket.requesterId()), helperNames, lines);
        repository.saveTranscript(ticket.id(), text, lines.size());

        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        String fileName = "ticket-" + String.format("%04d", ticket.number()) + ".txt";
        String summary = "🎫 Ticket **#" + String.format("%04d", ticket.number()) + "**" + (panel == null ? "" : " (" + panel.title() + ")") + " closed"
                + (ticket.closedBy() == null ? "" : " by <@" + ticket.closedBy() + ">") + ". Requested by <@" + ticket.requesterId() + ">.";

        if (settings.logChannelId() != null) {
            GuildMessageChannel logChannel = guild.getChannelById(GuildMessageChannel.class, settings.logChannelId());
            if (logChannel != null) {
                logChannel.sendMessage(summary).setAllowedMentions(EnumSet.noneOf(Message.MentionType.class))
                        .addFiles(FileUpload.fromData(bytes, fileName)).queue(null, error -> log.warn("Couldn't post ticket {}'s transcript to the log channel", ticket.id(), error));
            }
        }
        if (settings.transcriptDm()) {
            guild.getJDA().retrieveUserById(ticket.requesterId()).queue(user -> user.openPrivateChannel()
                    .flatMap(dm -> dm.sendMessage("Here's the record of your ticket.").addFiles(FileUpload.fromData(bytes, fileName)))
                    .queue(null, new ErrorHandler().ignore(ErrorResponse.CANNOT_SEND_TO_USER)
                            .handle(Throwable.class, error -> log.debug("Couldn't DM ticket {}'s transcript", ticket.id(), error))), error -> {});
        }
    }

    /** The text of a message, including what a bot message carries in its components or embeds, and any attachment names. */
    static String textOf(Message message) {
        StringBuilder text = new StringBuilder(message.getContentDisplay());
        for (var display : message.getComponentTree().findAll(net.dv8tion.jda.api.components.textdisplay.TextDisplay.class)) {
            if (!text.isEmpty()) text.append("\n");
            text.append(display.getContent());
        }
        for (var embed : message.getEmbeds()) {
            if (embed.getTitle() != null) text.append(text.isEmpty() ? "" : "\n").append(embed.getTitle());
            if (embed.getDescription() != null) text.append(text.isEmpty() ? "" : "\n").append(embed.getDescription());
        }
        for (var attachment : message.getAttachments()) text.append(text.isEmpty() ? "" : "\n").append("[attachment: ").append(attachment.getFileName()).append("]");
        String result = text.toString();
        return result.length() > 4000 ? result.substring(0, 3999) + "…" : result;
    }

    private static String nameOf(Guild guild, long userId) {
        Member member = guild.getMemberById(userId);
        if (member != null) return member.getEffectiveName();
        User user = guild.getJDA().getUserById(userId);
        return user != null ? user.getName() : "user " + userId;
    }

    // ================= background jobs =================

    /** Pings the escalation role on tickets nobody joined in time — once per ticket. */
    public void escalateDue(JDA jda) {
        for (Ticket ticket : repository.getEscalationDue()) {
            try {
                Guild guild = jda.getGuildById(ticket.guildId());
                Panel panel = ticket.panelId() == null ? null : repository.getPanel(ticket.panelId());
                if (guild == null || panel == null || ticket.channelId() == null) continue;

                Long role = TicketRules.escalateRole(panel, routingOptionOf(panel, ticket));
                // A help panel with no escalation role of its own goes up to PVM Helper+.
                if (role == null && panel.isHelpPanel()) role = repository.getHelpSettings(ticket.guildId()).helperPlusRoleId();
                if (!repository.markEscalated(ticket.id())) continue; // someone else got there first
                GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, ticket.channelId());
                if (role == null || channel == null) continue;

                Integer waited = repository.getEscalationHours(ticket.id());
                channel.sendMessage("⏰ Nobody has joined this ticket" + (waited == null ? "" : " after " + waited + " hour(s)") + " — <@&" + role + ">, can someone take a look?")
                        .setAllowedMentions(EnumSet.noneOf(Message.MentionType.class)).mentionRoles(role)
                        .queue(null, error -> log.warn("Couldn't post the escalation for ticket {}", ticket.id(), error));
            } catch (Exception e) {
                log.warn("Escalation check failed for ticket {}", ticket.id(), e);
            }
        }
    }

    /** Deletes the channel of every closed ticket past its grace period — also catches ones whose delete was lost to a restart. */
    public void sweepClosed(JDA jda) {
        for (Guild guild : jda.getGuilds()) {
            Settings settings = repository.getSettings(guild.getIdLong());
            OffsetDateTime cutoff = OffsetDateTime.now().minusSeconds(settings.closeDelaySeconds());
            for (Ticket ticket : repository.getClosedAwaitingDeletion(cutoff)) {
                if (ticket.guildId() != guild.getIdLong() || ticket.channelId() == null) continue;
                GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, ticket.channelId());
                if (channel == null) {
                    repository.markChannelDeleted(ticket.id());
                    continue;
                }
                channel.delete().queue(success -> repository.markChannelDeleted(ticket.id()),
                        new ErrorHandler().handle(ErrorResponse.UNKNOWN_CHANNEL, e -> repository.markChannelDeleted(ticket.id()))
                                .handle(Throwable.class, e -> log.warn("Couldn't delete closed ticket {}'s channel", ticket.id(), e)));
            }
        }
    }

    /** Applies each server's transcript retention setting. */
    public void purgeOldTranscripts(JDA jda) {
        for (Guild guild : jda.getGuilds()) {
            Settings settings = repository.getSettings(guild.getIdLong());
            if (settings.transcriptRetentionDays() == null) continue;
            int removed = repository.purgeTranscriptsBefore(guild.getIdLong(), OffsetDateTime.now().minusDays(settings.transcriptRetentionDays()));
            if (removed > 0) log.info("Removed {} ticket transcript(s) older than {} days in guild {}", removed, settings.transcriptRetentionDays(), guild.getIdLong());
        }
    }

    // ================= panel messages =================

    /** Posts the panel's message in {@code channel}, or edits the one already posted there; remembers where it is so a later edit updates it in place. */
    public CompletableFuture<Void> postPanel(Panel panel, GuildMessageChannel channel) {
        var container = TicketView.panelMessage(panel);
        boolean sameChannel = panel.postedChannelId() != null && panel.postedChannelId() == channel.getIdLong() && panel.postedMessageId() != null;

        CompletableFuture<Message> result = new CompletableFuture<>();
        if (sameChannel) {
            channel.retrieveMessageById(panel.postedMessageId()).queue(
                    existing -> existing.editMessageComponents(container).useComponentsV2(true).queue(result::complete, result::completeExceptionally),
                    error -> sendPanel(channel, container, result));
        } else {
            sendPanel(channel, container, result);
        }
        return result.thenAccept(message -> repository.setPosted(panel.id(), channel.getIdLong(), message.getIdLong()));
    }

    private static void sendPanel(GuildMessageChannel channel, net.dv8tion.jda.api.components.container.Container container, CompletableFuture<Message> result) {
        channel.sendMessageComponents(container).useComponentsV2(true).queue(result::complete, result::completeExceptionally);
    }

    private static String truncate(String text, int max) {
        return text.length() > max ? text.substring(0, max - 1) + "…" : text;
    }
}
