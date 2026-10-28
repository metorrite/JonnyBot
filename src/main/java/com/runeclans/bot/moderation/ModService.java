package com.runeclans.bot.moderation;

import com.runeclans.bot.configure.WebsiteLink;
import com.runeclans.bot.permission.AdminRoleFilter;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Does a moderation action: checks {@link ModPolicy} first (a refused action touches nothing and writes no case), tells the member by DM
 * when the server wants that, does it on Discord, and only when Discord accepted it writes the numbered case. Nothing here runs unless a
 * moderator used one of the commands (all off until the server switches them on in the Hub), or another feature records its own action
 * with {@link #recordOnly}.
 */
@BService
public class ModService {
    private static final Logger log = LoggerFactory.getLogger(ModService.class);
    public static final int MAX_REASON = 500;
    public static final String NO_REASON = "No reason given";

    /** What happened, in words for the moderator, and the case if one was written. */
    public record Outcome(boolean done, String message, ModCase modCase) {
        static Outcome refused(String why) {
            return new Outcome(false, why, null);
        }
    }

    private final ModCaseRepository cases;
    private final AdminRoleFilter admins;
    private final com.runeclans.bot.appeal.AppealLinks appealLinks;

    public ModService(ModCaseRepository cases, AdminRoleFilter admins, com.runeclans.bot.appeal.AppealLinks appealLinks) {
        this.cases = cases;
        this.admins = admins;
        this.appealLinks = appealLinks;
    }

    /**
     * @param targetMember the person as a member of this server, null if they are not in it (a ban of someone who left, an unban)
     * @param durationMinutes TIMEOUT only
     * @param deleteDays   BAN only: days of their messages to delete, 0 to 7
     */
    public CompletableFuture<Outcome> execute(Guild guild, Member actor, User target, Member targetMember, ModAction action, String reason,
                                              Integer durationMinutes, int deleteDays) {
        return execute(guild, actor, target, targetMember, action, reason, durationMinutes, deleteDays, ModCase.COMMAND);
    }

    /**
     * The same, for another feature acting on its own (the honeypot passes {@code actor} null and its own {@code source}, which is written on the
     * case): the same policy, DM, Discord call and case.
     */
    public CompletableFuture<Outcome> execute(Guild guild, Member actor, User target, Member targetMember, ModAction action, String reason,
                                              Integer durationMinutes, int deleteDays, String source) {
        String text = cleanReason(reason);
        ModSettings settings = cases.settings(guild.getIdLong());
        String refusal = ModPolicy.refusal(action, guild, actor, target.getIdLong(), targetMember, new HashSet<>(settings.protectedRoleIds()),
                actor != null && admins.isAuthorized(guild, actor), targetMember != null && admins.isAuthorized(guild, targetMember));
        if (refusal != null) return CompletableFuture.completedFuture(Outcome.refused(refusal));
        if (action == ModAction.TIMEOUT && (durationMinutes == null || durationMinutes < 1 || durationMinutes > ModDurations.MAX_TIMEOUT_MINUTES)) {
            return CompletableFuture.completedFuture(Outcome.refused("A timeout must last between 1 minute and 28 days."));
        }
        if (action == ModAction.UNTIMEOUT && (targetMember == null || !targetMember.isTimedOut())) {
            return CompletableFuture.completedFuture(Outcome.refused("They aren't timed out."));
        }
        if ((action == ModAction.TIMEOUT || action == ModAction.UNTIMEOUT || action == ModAction.KICK) && targetMember == null) {
            return CompletableFuture.completedFuture(Outcome.refused("They aren't in this server."));
        }

        CompletableFuture<Void> told = action.dmsBefore() && settings.dmReason() && !target.isBot()
                ? tell(target, dmText(guild.getName(), action, text, durationMinutes, withAppealPage(guild.getIdLong(), settings), actor)) : CompletableFuture.completedFuture(null);
        return told.thenCompose(ignored -> discord(guild, target, targetMember, action, text, durationMinutes, deleteDays)).handle((ok, error) -> {
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                log.warn("Moderation {} of {} in {} failed: {}", action, target.getId(), guild.getId(), cause.getMessage());
                return Outcome.refused("Discord refused: " + cause.getMessage());
            }
            ModCase written = cases.record(guild.getIdLong(), target.getIdLong(), target.getName(), actor == null ? guild.getSelfMember().getIdLong() : actor.getIdLong(),
                    actor == null ? guild.getSelfMember().getEffectiveName() : actor.getEffectiveName(), action, text, action == ModAction.TIMEOUT ? durationMinutes : null, source);
            log.info("Moderation: {} {} {} in {} (case {})", actor == null ? "bot" : actor.getId(), action, target.getId(), guild.getId(), written.caseNo());
            return new Outcome(true, summary(action, target, durationMinutes, written.caseNo()), written);
        });
    }

    private CompletableFuture<Void> discord(Guild guild, User target, Member member, ModAction action, String reason, Integer minutes, int deleteDays) {
        String audit = reason.length() > 400 ? reason.substring(0, 400) : reason;
        return switch (action) {
            case BAN -> guild.ban(target, Math.max(0, Math.min(7, deleteDays)), TimeUnit.DAYS).reason(audit).submit();
            case SOFTBAN -> guild.ban(target, 1, TimeUnit.DAYS).reason(audit).submit().thenCompose(ok -> guild.unban(target).reason("Softban: " + audit).submit());
            case UNBAN -> guild.unban(target).reason(audit).submit();
            case KICK -> guild.kick(member).reason(audit).submit();
            case TIMEOUT -> member.timeoutFor(Duration.ofMinutes(minutes)).reason(audit).submit();
            case UNTIMEOUT -> member.removeTimeout().reason(audit).submit();
            case WARN -> CompletableFuture.completedFuture(null);
        };
    }

    /** The settings the DM is written from: the server's own appeal link, or the website's appeal page when the server takes appeals there (T-033). */
    ModSettings withAppealPage(long guildId, ModSettings settings) {
        if (settings.appealUrl() != null && !settings.appealUrl().isBlank()) return settings;
        String page = appealLinks == null ? null : appealLinks.linkFor(guildId);
        return page == null ? settings : settings.withAppealUrl(page);
    }

    /** DMs the person; a closed DM is not an error (the action still goes ahead). */
    private static CompletableFuture<Void> tell(User user, String text) {
        return user.openPrivateChannel().flatMap(channel -> channel.sendMessage(text)).submit().handle((ok, error) -> null);
    }

    /** The DM a punished member gets: what happened, the reason, who only if the server shows that, and where to appeal. */
    static String dmText(String guildName, ModAction action, String reason, Integer minutes, ModSettings settings, Member actor) {
        StringBuilder out = new StringBuilder("You have been " + action.past + (action == ModAction.TIMEOUT && minutes != null ? " for " + ModDurations.words(minutes) : "")
                + (action == ModAction.UNBAN ? "" : " in **" + guildName + "**") + ".");
        out.append("\n**Reason:** ").append(reason);
        if (settings.showModerator() && actor != null) out.append("\n**By:** ").append(actor.getEffectiveName());
        if (settings.appealUrl() != null && !settings.appealUrl().isBlank()) out.append("\nIf you think this was a mistake, you can appeal here: ").append(settings.appealUrl());
        return out.toString();
    }

    static String summary(ModAction action, User target, Integer minutes, long caseNo) {
        return "**" + action.label + "** · " + target.getAsMention() + (action == ModAction.TIMEOUT && minutes != null ? " for " + ModDurations.words(minutes) : "") + " · case #" + caseNo;
    }

    /** Trims a reason, gives it a default and keeps it within the limit. */
    public static String cleanReason(String reason) {
        String text = reason == null ? "" : reason.strip();
        if (text.isEmpty()) return NO_REASON;
        return text.length() > MAX_REASON ? text.substring(0, MAX_REASON) : text;
    }

    /**
     * Writes a case for something another feature already did (a warning, an autopunish rule, an automod action): no Discord call, no DM. Never
     * throws, so a failure to keep the record cannot undo or block what the caller did. Returns the case, or null if it could not be written.
     */
    public ModCase recordOnly(Guild guild, long userId, String userName, long moderatorId, String moderatorName, ModAction action, String reason,
                              Integer durationMinutes, String source) {
        try {
            return cases.record(guild.getIdLong(), userId, userName, moderatorId, moderatorName, action, cleanReason(reason), durationMinutes, source);
        } catch (RuntimeException e) {
            log.warn("Could not record the {} case for {} in {}: {}", action, userId, guild.getId(), e.getMessage());
            return null;
        }
    }

    /** The link as it is shown in a DM, or null when it isn't a usable web address. */
    public static String cleanAppealUrl(String raw) {
        return raw == null || raw.isBlank() ? null : WebsiteLink.normalize(raw).orElse(null);
    }
}
