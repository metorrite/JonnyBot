package com.runeclans.bot.moderation;

import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.i18n.Text;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.SlashOption;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.TopLevelSlashCommandData;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * {@code /ban /unban /kick /timeout /untimeout /softban} and the case lookups {@code /case} and {@code /modlogs}. Each one is a Hub
 * command (see {@code HubCommand}): the action commands are OFF in every server until it switches them on from the dashboard's Commands page,
 * and admins only until it widens that to a moderator group there. All rules (who can be touched, hierarchy, protected roles) live in
 * {@link ModPolicy}; the doing and the numbered case in {@link ModService}. Replies are private to the moderator.
 */
@Command
public class ModerationCommands {
    private final ModService service;
    private final ModCaseRepository cases;

    public ModerationCommands(ModService service, ModCaseRepository cases) {
        this.service = service;
        this.cases = cases;
    }

    @TopLevelSlashCommandData(description = "Ban a member")
    @JDASlashCommand(name = "ban", description = "Ban a member, or someone who has left, and keep a numbered case")
    public void onBan(GuildSlashEvent event,
                      @SlashOption(description = "Who to ban") User user,
                      @SlashOption(description = "Why (they are told by DM when the server has that on)") @Nullable String reason,
                      @SlashOption(name = "delete_days", description = "Days of their messages to delete, 0 to 7 (default 0)") @Nullable Integer deleteDays) {
        run(event, user, ModAction.BAN, reason, null, deleteDays == null ? 0 : deleteDays);
    }

    @TopLevelSlashCommandData(description = "Lift a ban")
    @JDASlashCommand(name = "unban", description = "Lift a ban")
    public void onUnban(GuildSlashEvent event,
                        @SlashOption(description = "Who to unban (pick them or paste their id)") User user,
                        @SlashOption(description = "Why") @Nullable String reason) {
        run(event, user, ModAction.UNBAN, reason, null, 0);
    }

    @TopLevelSlashCommandData(description = "Kick a member")
    @JDASlashCommand(name = "kick", description = "Kick a member and keep a numbered case")
    public void onKick(GuildSlashEvent event,
                       @SlashOption(description = "Who to kick") User user,
                       @SlashOption(description = "Why") @Nullable String reason) {
        run(event, user, ModAction.KICK, reason, null, 0);
    }

    @TopLevelSlashCommandData(description = "Time a member out")
    @JDASlashCommand(name = "timeout", description = "Time a member out (mute) for a while")
    public void onTimeout(GuildSlashEvent event,
                          @SlashOption(description = "Who to time out") User user,
                          @SlashOption(description = "How long, like 30m, 2h, 7d or 1d12h (at most 28 days)") String duration,
                          @SlashOption(description = "Why") @Nullable String reason) {
        OptionalInt minutes = ModDurations.parseMinutes(duration);
        if (minutes.isEmpty() || minutes.getAsInt() > ModDurations.MAX_TIMEOUT_MINUTES) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "moderation.i_couldn_t_read_that_duration"), Text.of(event, "moderation.try_30m_2h_7d_or_1d12h"));
            return;
        }
        run(event, user, ModAction.TIMEOUT, reason, minutes.getAsInt(), 0);
    }

    @TopLevelSlashCommandData(description = "Remove a member's timeout")
    @JDASlashCommand(name = "untimeout", description = "Remove a member's timeout")
    public void onUntimeout(GuildSlashEvent event,
                            @SlashOption(description = "Who to release") User user,
                            @SlashOption(description = "Why") @Nullable String reason) {
        run(event, user, ModAction.UNTIMEOUT, reason, null, 0);
    }

    @TopLevelSlashCommandData(description = "Softban a member")
    @JDASlashCommand(name = "softban", description = "Ban and immediately unban, to clear their last day of messages")
    public void onSoftban(GuildSlashEvent event,
                          @SlashOption(description = "Who to softban") User user,
                          @SlashOption(description = "Why") @Nullable String reason) {
        run(event, user, ModAction.SOFTBAN, reason, null, 1);
    }

    private void run(GuildSlashEvent event, User user, ModAction action, String reason, Integer minutes, int deleteDays) {
        Guild guild = event.getGuild();
        Member actor = event.getMember();
        event.deferReply(true).queue(hook -> guild.retrieveMember(user).submit().handle((member, error) -> member)
                .thenCompose(member -> service.execute(guild, actor, user, member, action, reason, minutes, deleteDays))
                .whenComplete((outcome, error) -> {
                    if (error != null) hook.editOriginal(Text.of(event, "moderation.something_went_wrong_nothing_was_recorded")).queue();
                    else hook.editOriginal((outcome.done() ? "✅ " : "⚠️ ") + outcome.message()).queue();
                }));
    }

    @TopLevelSlashCommandData(description = "Look up or edit a moderation case")
    @JDASlashCommand(name = "case", subcommand = "view", description = "Show one case")
    public void onCaseView(GuildSlashEvent event, @SlashOption(description = "The case number") long number) {
        var found = cases.find(event.getGuild().getIdLong(), number);
        if (found.isEmpty()) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "moderation.this_server_has_no_case", number));
            return;
        }
        Containers.replyEphemeral(event, Containers.INFO, describe(found.get()).toArray(String[]::new));
    }

    @JDASlashCommand(name = "case", subcommand = "edit", description = "Change a case's reason")
    public void onCaseEdit(GuildSlashEvent event, @SlashOption(description = "The case number") long number, @SlashOption(description = "The new reason") String reason) {
        String text = ModService.cleanReason(reason);
        if (cases.editReason(event.getGuild().getIdLong(), number, text, event.getUser().getIdLong())) {
            Containers.replyEphemeral(event, Containers.SUCCESS, Text.of(event, "moderation.updated_the_reason_of_case", number), Text.of(event, "moderation.the_first_reason_is_kept_in"));
        } else {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "moderation.this_server_has_no_case", number));
        }
    }

    @TopLevelSlashCommandData(description = "A member's moderation history")
    @JDASlashCommand(name = "modlogs", description = "Every moderation case for a member, newest first")
    public void onModlogs(GuildSlashEvent event, @SlashOption(description = "Whose history") User user) {
        List<ModCase> list = cases.forUser(event.getGuild().getIdLong(), user.getIdLong(), 10);
        if (list.isEmpty()) {
            Containers.replyEphemeral(event, Containers.INFO, Text.of(event, "moderation.has_no_moderation_cases", user.getAsMention()));
            return;
        }
        List<String> lines = new ArrayList<>();
        lines.add("### Cases for " + user.getName());
        for (ModCase c : list) {
            String reason = c.reason().length() > 120 ? c.reason().substring(0, 120) + "…" : c.reason();
            lines.add("`#" + c.caseNo() + "` **" + c.action().label + "** <t:" + c.createdAt().getEpochSecond() + ":d> by <@" + c.moderatorId() + ">: " + reason);
        }
        lines.add("-# Showing the latest 10. The dashboard's Moderation page has the full history.");
        Containers.replyEphemeral(event, Containers.INFO, lines.toArray(String[]::new));
    }

    static List<String> describe(ModCase c) {
        List<String> lines = new ArrayList<>();
        lines.add("### Case #" + c.caseNo() + ": " + c.action().label);
        lines.add("**Member:** <@" + c.userId() + "> (" + c.userName() + ")");
        lines.add("**By:** <@" + c.moderatorId() + "> (" + c.moderatorName() + ")");
        if (c.durationMinutes() != null) lines.add("**For:** " + ModDurations.words(c.durationMinutes()));
        lines.add("**Reason:** " + c.reason());
        lines.add("-# <t:" + c.createdAt().getEpochSecond() + ":f> · from " + c.source().toLowerCase().replace('_', ' ')
                + (c.editedAt() != null ? " · reason edited <t:" + c.editedAt().getEpochSecond() + ":R>" : ""));
        return lines;
    }
}
