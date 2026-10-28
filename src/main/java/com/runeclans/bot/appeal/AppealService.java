package com.runeclans.bot.appeal;

import com.runeclans.bot.appeal.AppealRules.Appeal;
import com.runeclans.bot.appeal.AppealRules.Settings;
import com.runeclans.bot.appeal.AppealRules.Status;
import com.runeclans.bot.commands.ticket.TicketRules;
import com.runeclans.bot.commands.ticket.TicketService;
import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.moderation.ModAction;
import com.runeclans.bot.moderation.ModCase;
import com.runeclans.bot.moderation.ModCaseRepository;
import com.runeclans.bot.moderation.ModService;
import com.runeclans.bot.ticket.TicketModels.Answer;
import com.runeclans.bot.ticket.TicketModels.Panel;
import com.runeclans.bot.ticket.TicketModels.Ticket;
import com.runeclans.bot.ticket.TicketRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.restaction.ChannelAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Ban and mute appeals (T-033). Someone signs in on the JonnyBot site and appeals one of their cases there; each appeal opens a staff-only ticket in the
 * server's chosen appeals panel with the case history and Approve / Reject, and (opt-in) a read-only copy goes to a chosen channel. Approve lifts the
 * ban or timeout through {@link ModService} (with the same permission checks and a case of its own) and closes the appeal; Reject records why, and the
 * same case can be appealed again only after the server's cooldown.
 * <p>
 * The ticket is opened by JonnyBot on the person's behalf, not by them: they are usually banned (so can't see the server), and a ticket's requester can
 * be sent its whole transcript when it closes, which must never carry staff talk to the person appealing.
 */
@BService
public class AppealService {
    private static final Logger log = LoggerFactory.getLogger(AppealService.class);
    static final String APPROVE = "appeal_approve:";
    static final String REJECT = "appeal_reject:";
    static final int HISTORY_SHOWN = 10;
    /** The appeal's text is written by the person appealing: nothing in it (or in their name) may ping anyone. */
    static final java.util.EnumSet<net.dv8tion.jda.api.entities.Message.MentionType> NO_PINGS = java.util.EnumSet.noneOf(net.dv8tion.jda.api.entities.Message.MentionType.class);

    private final AppealRepository appeals;
    private final ModCaseRepository cases;
    private final ModService moderation;
    private final TicketRepository tickets;
    private final TicketService ticketService;

    /** Why something couldn't be done, with the HTTP status to answer. */
    public static final class AppealProblem extends RuntimeException {
        private final int status;

        public AppealProblem(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    public AppealService(AppealRepository appeals, ModCaseRepository cases, ModService moderation, TicketRepository tickets, TicketService ticketService) {
        this.appeals = appeals;
        this.cases = cases;
        this.moderation = moderation;
        this.tickets = tickets;
        this.ticketService = ticketService;
    }

    // ---------- the person appealing ----------

    /** The person's cases they can appeal now, and their appeals, for the appeal page. Only their own. */
    public record Mine(Settings settings, List<ModCase> appealable, List<Appeal> appeals) {}

    public Mine mine(long guildId, long userId, Instant now) {
        Settings settings = appeals.settings(guildId);
        return new Mine(settings, AppealRules.appealable(cases.forUser(guildId, userId, 50), now), appeals.forUser(guildId, userId));
    }

    /**
     * Sends an appeal. Checks the server takes appeals, the case is theirs and can be appealed now (and, on Discord, that the ban or timeout is still
     * there), that there is no open appeal and no recent rejection, then saves it and opens the staff ticket and the copy.
     */
    public CompletableFuture<Appeal> submit(Guild guild, long userId, String userName, long caseNo, String text, Instant now) {
        Settings settings = appeals.settings(guild.getIdLong());
        if (!settings.enabled()) throw new AppealProblem(403, "This server doesn't take appeals on the website.");
        String problem = AppealRules.textProblem(text);
        if (problem != null) throw new AppealProblem(400, problem);
        ModCase appealed = AppealRules.appealable(cases.forUser(guild.getIdLong(), userId, 50), now).stream().filter(c -> c.caseNo() == caseNo).findFirst()
                .orElseThrow(() -> new AppealProblem(404, "That isn't one of your cases you can appeal."));
        String refusal = AppealRules.refusal(appeals.forCase(guild.getIdLong(), caseNo, userId), settings.cooldownDays(), now);
        if (refusal != null) throw new AppealProblem(409, refusal);
        return stillInForce(guild, appealed).thenApply(inForce -> {
            if (!inForce) throw new AppealProblem(409, appealed.action() == ModAction.BAN ? "You aren't banned from this server any more." : "Your timeout has already ended.");
            Appeal saved = appeals.create(guild.getIdLong(), caseNo, userId, userName, text.strip())
                    .orElseThrow(() -> new AppealProblem(409, "You already have an appeal waiting for this case. Staff will look at it."));
            log.info("User {} appealed case {} in {} (appeal {})", userId, caseNo, guild.getId(), saved.id());
            openTicket(guild, settings, saved, appealed);
            postCopy(guild, settings, saved, appealed);
            return saved;
        });
    }

    /** Whether Discord still has the ban (or the timeout) the case is about. */
    CompletableFuture<Boolean> stillInForce(Guild guild, ModCase c) {
        CompletableFuture<Boolean> answer = new CompletableFuture<>();
        if (c.action() == ModAction.BAN) {
            guild.retrieveBan(net.dv8tion.jda.api.entities.UserSnowflake.fromId(c.userId())).queue(ban -> answer.complete(true),
                    error -> answer.complete(!(error instanceof ErrorResponseException r && r.getErrorResponse() == ErrorResponse.UNKNOWN_BAN)));
        } else {
            guild.retrieveMemberById(c.userId()).queue(member -> answer.complete(member.isTimedOut()), error -> answer.complete(false));
        }
        return answer;
    }

    // ---------- staff ----------

    /** A server's appeals for the dashboard. */
    public List<Appeal> list(long guildId) {
        return appeals.forGuild(guildId, 100);
    }

    /**
     * Approves an appeal: marks it approved (so two staff can't both act), lifts the ban or timeout through {@link ModService} as {@code actor} (who
     * needs the permission for it), and closes its ticket. When Discord or the rules refuse the lift, the appeal is open again and the reason is given.
     */
    public CompletableFuture<Appeal> approve(Guild guild, Member actor, long appealId, String note) {
        Appeal appeal = open(guild.getIdLong(), appealId);
        ModCase c = cases.find(guild.getIdLong(), appeal.caseNo()).orElseThrow(() -> new AppealProblem(404, "The case of that appeal no longer exists."));
        ModAction lift = AppealRules.liftOf(c.action());
        if (lift == null) throw new AppealProblem(409, "That case can't be lifted.");
        String cleanNote = cleanNote(note);
        if (!appeals.decide(guild.getIdLong(), appealId, Status.APPROVED, actor.getIdLong(), cleanNote)) throw new AppealProblem(409, "Someone has already decided that appeal.");
        String reason = "Appeal approved" + (cleanNote.isEmpty() ? "" : ": " + cleanNote);
        return guild.getJDA().retrieveUserById(appeal.userId()).submit()
                .thenCompose(user -> lift == ModAction.UNBAN
                        ? moderation.execute(guild, actor, user, null, lift, reason, null, 0, "APPEAL")
                        : guild.retrieveMember(user).submit().thenCompose(member -> moderation.execute(guild, actor, user, member, lift, reason, null, 0, "APPEAL")))
                .handle((outcome, error) -> {
                    if (error != null || outcome == null || !outcome.done()) {
                        appeals.reopen(guild.getIdLong(), appealId);
                        String why = error != null ? "Discord refused: " + (error.getCause() != null ? error.getCause().getMessage() : error.getMessage()) : outcome == null ? "It couldn't be lifted." : outcome.message();
                        throw new AppealProblem(409, why);
                    }
                    Appeal decided = appeals.find(guild.getIdLong(), appealId).orElse(appeal);
                    finish(guild, decided, actor.getIdLong(), "Appeal approved: " + lift.label.toLowerCase() + " done (case #" + outcome.modCase().caseNo() + ").");
                    log.info("Appeal {} in {} approved by {}", appealId, guild.getId(), actor.getId());
                    return decided;
                });
    }

    /** Rejects an appeal with a reason the person sees on the appeal page. Needs the permission the lift would need. */
    public Appeal reject(Guild guild, Member actor, long appealId, String note) {
        Appeal appeal = open(guild.getIdLong(), appealId);
        ModCase c = cases.find(guild.getIdLong(), appeal.caseNo()).orElse(null);
        ModAction lift = c == null ? null : AppealRules.liftOf(c.action());
        if (lift != null && lift.permission != null && !actor.hasPermission(lift.permission)) {
            throw new AppealProblem(403, "Deciding this appeal needs the " + lift.permission.getName() + " permission.");
        }
        String cleanNote = cleanNote(note);
        if (cleanNote.isEmpty()) throw new AppealProblem(400, "Say why it was turned down. They see it on the appeal page.");
        if (!appeals.decide(guild.getIdLong(), appealId, Status.REJECTED, actor.getIdLong(), cleanNote)) throw new AppealProblem(409, "Someone has already decided that appeal.");
        Appeal decided = appeals.find(guild.getIdLong(), appealId).orElse(appeal);
        finish(guild, decided, actor.getIdLong(), "Appeal turned down: " + cleanNote);
        log.info("Appeal {} in {} rejected by {}", appealId, guild.getId(), actor.getId());
        return decided;
    }

    private Appeal open(long guildId, long appealId) {
        Appeal appeal = appeals.find(guildId, appealId).orElseThrow(() -> new AppealProblem(404, "That appeal doesn't exist."));
        if (appeal.status() != Status.OPEN) throw new AppealProblem(409, "Someone has already decided that appeal.");
        return appeal;
    }

    private static String cleanNote(String note) {
        String n = note == null ? "" : note.strip();
        if (n.length() > AppealRules.MAX_NOTE) throw new AppealProblem(400, "Keep the note to " + AppealRules.MAX_NOTE + " characters or fewer.");
        return n;
    }

    /** After a decision: the ticket closes with it, and the copy shows the outcome. */
    private void finish(Guild guild, Appeal appeal, long by, String closeReason) {
        if (appeal.ticketId() != null) {
            try {
                Ticket ticket = tickets.getTicket(appeal.ticketId());
                if (ticket != null) ticketService.closeTicket(guild, ticket, by, closeReason);
            } catch (RuntimeException e) {
                log.warn("Couldn't close the ticket of appeal {}", appeal.id(), e);
            }
        }
        Settings settings = appeals.settings(guild.getIdLong());
        if (settings.mirrorChannelId() != null && appeal.mirrorMessageId() != null) {
            GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, settings.mirrorChannelId());
            ModCase c = cases.find(guild.getIdLong(), appeal.caseNo()).orElse(null);
            if (channel != null && c != null) {
                channel.editMessageComponentsById(appeal.mirrorMessageId(), List.of(copyCard(appeal, c))).useComponentsV2(true).setAllowedMentions(NO_PINGS).queue(ok -> { }, e -> { });
            }
        }
    }

    // ---------- the staff ticket and the copy ----------

    /** What staff see: the appeal, the case it is about, and the person's case history in this server. */
    String summary(Appeal appeal, ModCase appealed, List<ModCase> history) {
        StringBuilder text = new StringBuilder("## Appeal #").append(appeal.id()).append(" · ").append(appeal.userName()).append(" (<@").append(appeal.userId()).append(">)\n");
        text.append("**Case #").append(appealed.caseNo()).append(":** ").append(appealed.action().label)
                .append(appealed.durationMinutes() == null ? "" : " (" + appealed.durationMinutes() + " min)").append(" on <t:").append(appealed.createdAt().getEpochSecond()).append(":d> · ")
                .append(appealed.reason()).append("\n\n**Their appeal:**\n").append(appeal.text()).append("\n\n**Case history here** (latest ").append(HISTORY_SHOWN).append("):");
        for (ModCase h : history) {
            text.append("\n#").append(h.caseNo()).append(" ").append(h.action().label).append(" <t:").append(h.createdAt().getEpochSecond()).append(":d> · ")
                    .append(h.reason().length() > 120 ? h.reason().substring(0, 119) + "…" : h.reason());
        }
        return text.length() > 3900 ? text.substring(0, 3899) + "…" : text.toString();
    }

    Container ticketCard(Appeal appeal, ModCase appealed, List<ModCase> history) {
        return Containers.card(Containers.WARNING, TextDisplay.of(summary(appeal, appealed, history)),
                ActionRow.of(Button.success(APPROVE + appeal.id(), "Approve: lift it"), Button.danger(REJECT + appeal.id(), "Reject")));
    }

    Container copyCard(Appeal appeal, ModCase appealed) {
        String outcome = switch (appeal.status()) {
            case OPEN -> "Waiting for staff.";
            case APPROVED -> "**Approved**" + (appeal.note() == null ? "." : ": " + appeal.note());
            case REJECTED -> "**Turned down:** " + appeal.note();
        };
        String text = "**Appeal #" + appeal.id() + "** from " + appeal.userName() + " on case #" + appealed.caseNo() + " (" + appealed.action().label + ")\n" + appeal.text() + "\n\n" + outcome;
        Color tone = appeal.status() == Status.OPEN ? Containers.INFO : appeal.status() == Status.APPROVED ? Containers.SUCCESS : Containers.DANGER;
        return Containers.card(tone, TextDisplay.of(text.length() > 3900 ? text.substring(0, 3899) + "…" : text));
    }

    /** Opens the staff-only ticket in the appeals panel's category (nothing when no panel is chosen: the appeal is on the dashboard either way). */
    private void openTicket(Guild guild, Settings settings, Appeal appeal, ModCase appealed) {
        if (settings.panelId() == null) return;
        try {
            Panel panel = tickets.getPanel(settings.panelId());
            if (panel == null || panel.guildId() != guild.getIdLong()) return;
            long self = guild.getSelfMember().getIdLong();
            Ticket ticket = tickets.createTicket(guild.getIdLong(), panel.id(), self, null, "Appeal",
                    List.of(new Answer("Appeal", "#" + appeal.id() + " by " + appeal.userName() + " on case #" + appealed.caseNo())));
            appeals.setTicket(appeal.id(), ticket.id());
            Category category = panel.categoryId() == null ? null : guild.getCategoryById(panel.categoryId());
            String name = "appeal-" + String.format("%04d", ticket.number());
            ChannelAction<TextChannel> action = category != null ? category.createTextChannel(name) : guild.createTextChannel(name);
            action = action.setTopic("Appeal #" + appeal.id() + " · " + appeal.userName() + " · case #" + appealed.caseNo());
            // staff only: the person appealing is usually banned, and is never let in
            for (TicketRules.Overwrite o : TicketRules.overwrites(guild.getPublicRole().getIdLong(), self, self, TicketRules.participantRoles(tickets.getRoles(panel.id())))) {
                action = o.isRole() ? action.addRolePermissionOverride(o.id(), o.allow(), o.deny()) : action.addMemberPermissionOverride(o.id(), o.allow(), o.deny());
            }
            List<ModCase> history = cases.forUser(guild.getIdLong(), appeal.userId(), HISTORY_SHOWN);
            action.queue(channel -> {
                tickets.setChannel(ticket.id(), channel.getIdLong());
                channel.sendMessageComponents(List.of(ticketCard(appeal, appealed, history))).useComponentsV2(true).setAllowedMentions(NO_PINGS).queue(ok -> { },
                        e -> log.warn("Couldn't post appeal {} in its ticket", appeal.id(), e));
            }, error -> {
                log.warn("Couldn't open the ticket of appeal {}", appeal.id(), error);
                tickets.closeTicket(ticket.id(), self, "The ticket channel could not be created.");
            });
        } catch (RuntimeException e) {
            log.warn("Couldn't open the ticket of appeal {}", appeal.id(), e);
        }
    }

    /** The opt-in read-only copy in the server's chosen channel. */
    private void postCopy(Guild guild, Settings settings, Appeal appeal, ModCase appealed) {
        if (settings.mirrorChannelId() == null) return;
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, settings.mirrorChannelId());
        if (channel == null || !channel.canTalk()) return;
        channel.sendMessageComponents(List.of(copyCard(appeal, appealed))).useComponentsV2(true).setAllowedMentions(NO_PINGS)
                .queue(m -> appeals.setMirrorMessage(appeal.id(), m.getIdLong()), e -> log.debug("Couldn't post the copy of appeal {}: {}", appeal.id(), e.getMessage()));
    }

    /** The appeals a dashboard lists, with the case each is about. */
    public List<ModCase> casesOf(long guildId, List<Appeal> list) {
        List<ModCase> out = new ArrayList<>();
        for (Appeal a : list) cases.find(guildId, a.caseNo()).ifPresent(out::add);
        return out;
    }
}
