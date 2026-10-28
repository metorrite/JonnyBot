package com.runeclans.bot.commands.points;

import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.points.PointsRepository;
import com.runeclans.bot.points.PointsRepository.Balance;
import com.runeclans.bot.points.PointsRepository.Settings;
import com.runeclans.bot.points.PointsRules;
import com.runeclans.bot.points.PointsRules.Category;
import com.runeclans.bot.points.PointsService;
import com.runeclans.bot.points.RewardsRepository;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.SlashOption;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.TopLevelSlashCommandData;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.utils.FileUpload;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /points}: a server's points. {@code balance} and {@code top} are open to everyone; {@code award} (which also takes points
 * back with a negative amount) is for members who can manage the server, and always needs a reason so the history reads clearly.
 */
@Command
public class PointsCommand {
    private final PointsRepository repository;
    private final PointsService service;
    private final RewardsRepository rewards;

    public PointsCommand(PointsRepository repository, PointsService service, RewardsRepository rewards) {
        this.repository = repository;
        this.service = service;
        this.rewards = rewards;
    }

    @TopLevelSlashCommandData(description = "Your points in this server")
    @JDASlashCommand(name = "points", subcommand = "balance", description = "See your points, or another member's")
    public void onBalance(GuildSlashEvent event, @SlashOption(description = "Whose points (you, by default)") @Nullable Member member) {
        Settings settings = repository.settings(event.getGuild().getIdLong());
        if (!enabled(event, settings)) return;
        Member who = member != null ? member : event.getMember();
        long balance = repository.balance(event.getGuild().getIdLong(), who.getIdLong());
        List<String> lines = new ArrayList<>();
        lines.add("### " + who.getEffectiveName() + ": " + PointsRules.format(balance, settings.unit()));
        for (var e : repository.history(event.getGuild().getIdLong(), who.getIdLong(), 5)) {
            String sign = e.points() > 0 ? "+" : "";
            lines.add("`" + sign + e.points() + "` <t:" + e.createdAt().getEpochSecond() + ":d> " + trim(e.reason()));
        }
        Containers.replyEphemeral(event, Containers.INFO, lines.toArray(String[]::new));
    }

    @JDASlashCommand(name = "points", subcommand = "top", description = "The members with the most points")
    public void onTop(GuildSlashEvent event) {
        Settings settings = repository.settings(event.getGuild().getIdLong());
        if (!enabled(event, settings)) return;
        List<Balance> top = repository.top(event.getGuild().getIdLong(), 10);
        if (top.isEmpty()) {
            Containers.replyEphemeral(event, Containers.INFO, Text.of(event, "points.nobody_has_any_yet", settings.unit()));
            return;
        }
        List<String> lines = new ArrayList<>();
        lines.add("### Top " + settings.unit());
        int rank = 1;
        for (Balance b : top) lines.add(rank++ + ". <@" + b.userId() + "> " + PointsRules.format(b.points(), settings.unit()));
        Containers.replyEphemeral(event, Containers.INFO, lines.toArray(String[]::new));
    }

    @JDASlashCommand(name = "points", subcommand = "award", description = "Give points to a member (a negative amount takes them back)")
    public void onAward(GuildSlashEvent event,
                        @SlashOption(description = "Who to give points to") Member member,
                        @SlashOption(description = "How many; negative to take back") long amount,
                        @SlashOption(description = "Why, for the history") String reason) {
        Member staff = event.getMember();
        if (staff == null || !staff.hasPermission(Permission.MANAGE_SERVER)) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "points.you_need_the_manage_server_permission"));
            return;
        }
        Settings settings = repository.settings(event.getGuild().getIdLong());
        if (!enabled(event, settings)) return;
        if (member.getUser().isBot()) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "points.bots_can_t_hold_points"));
            return;
        }
        var result = service.apply(event.getGuild().getIdLong(), member.getIdLong(), amount, reason, Category.MANUAL, null, event.getUser().getIdLong());
        if (result.outcome() != PointsService.Outcome.DONE) {
            Containers.replyEphemeral(event, Containers.WARNING, result.message());
            return;
        }
        Containers.replyEphemeral(event, Containers.SUCCESS,
                Text.of(event, amount > 0 ? "points.gave_to" : "points.took_from", PointsRules.format(Math.abs(amount), settings.unit()), member.getAsMention()),
                Text.of(event, "points.they_now_have", PointsRules.format(result.balance(), settings.unit())));
    }

    @JDASlashCommand(name = "points", subcommand = "rewards", description = "See what you can spend your points on")
    public void onRewards(GuildSlashEvent event) {
        long guildId = event.getGuild().getIdLong();
        Settings settings = repository.settings(guildId);
        if (!enabled(event, settings)) return;
        var list = rewards.rewards(guildId, true);
        if (list.isEmpty()) {
            Containers.replyEphemeral(event, Containers.INFO, Text.of(event, "points.there_are_no_rewards_to_spend", settings.unit()));
            return;
        }
        List<String> lines = new ArrayList<>();
        lines.add("### Rewards (you have " + PointsRules.format(repository.balance(guildId, event.getUser().getIdLong()), settings.unit()) + ")");
        for (var r : list) {
            String left = r.stock() == null ? "" : r.stock() == 0 ? " · sold out" : " · " + r.stock() + " left";
            lines.add("`#" + r.id() + "` **" + r.name() + "** · " + PointsRules.format(r.cost(), settings.unit()) + left + (r.description().isBlank() ? "" : "\n-# " + r.description()));
        }
        lines.add("-# Spend with /points redeem and the reward's number.");
        Containers.replyEphemeral(event, Containers.INFO, lines.toArray(String[]::new));
    }

    @JDASlashCommand(name = "points", subcommand = "redeem", description = "Spend points on a reward")
    public void onRedeem(GuildSlashEvent event, @SlashOption(description = "The reward's number, from /points rewards") long reward) {
        long guildId = event.getGuild().getIdLong();
        Settings settings = repository.settings(guildId);
        if (!enabled(event, settings)) return;
        var result = service.redeem(guildId, event.getUser().getIdLong(), reward);
        if (result.outcome() != PointsService.RedeemOutcome.DONE) {
            Containers.replyEphemeral(event, Containers.WARNING, result.message());
            return;
        }
        Containers.replyEphemeral(event, Containers.SUCCESS,
                Text.of(event, "points.you_got_for", result.redemption().rewardName(), PointsRules.format(result.redemption().cost(), settings.unit())),
                Text.of(event, "points.staff_will_hand_it_over_you", PointsRules.format(result.balance(), settings.unit())));
    }

    @JDASlashCommand(name = "points", subcommand = "submit", description = "Send a screenshot to staff to earn points")
    public void onSubmit(GuildSlashEvent event,
                         @SlashOption(description = "A screenshot showing what you did") Message.Attachment proof,
                         @SlashOption(description = "What it shows, e.g. the drop or the kill count") String description) {
        long guildId = event.getGuild().getIdLong();
        Settings settings = repository.settings(guildId);
        if (!enabled(event, settings)) return;
        TextChannel review = settings.reviewChannelId() == null ? null : event.getGuild().getTextChannelById(settings.reviewChannelId());
        if (review == null) {
            Containers.replyEphemeral(event, Containers.INFO, Text.of(event, "points.submissions_aren_t_set_up_in"));
            return;
        }
        String problem = PointsRules.submissionProblem(description, proof.getContentType(), proof.getFileName(), proof.getSize(), repository.pendingFor(guildId, event.getUser().getIdLong()));
        if (problem != null) {
            Containers.replyEphemeral(event, Containers.WARNING, problem);
            return;
        }
        event.deferReply(true).queue();
        long id = repository.addSubmission(guildId, event.getUser().getIdLong(), description.strip(), proof.getFileName(), proof.getUrl());
        proof.getProxy().download().thenCompose(stream -> review.sendMessage("**Points submission #" + id + "** from " + event.getUser().getAsMention() + "\n" + trimDescription(description))
                        .setAllowedMentions(java.util.List.of())
                        .addFiles(FileUpload.fromData(stream, proof.getFileName())).submit())
                .whenComplete((message, error) -> {
                    if (error != null) {
                        repository.deleteSubmission(id);
                        event.getHook().editOriginal("I couldn't pass your screenshot to staff. Try again in a moment.").queue();
                        return;
                    }
                    repository.setReviewMessage(id, review.getIdLong(), message.getIdLong(), message.getAttachments().isEmpty() ? proof.getUrl() : message.getAttachments().get(0).getUrl());
                    event.getHook().editOriginal("Sent to staff as submission #" + id + ". You'll get a DM when they decide.").queue();
                });
    }

    private static String trimDescription(String description) {
        return description.strip().replaceAll("[\\r\\n]+", " ");
    }

    private static boolean enabled(GuildSlashEvent event, Settings settings) {
        if (settings.enabled()) return true;
        Containers.replyEphemeral(event, Containers.INFO, Text.of(event, "points.points_aren_t_turned_on_in"));
        return false;
    }

    private static String trim(String s) {
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }
}
