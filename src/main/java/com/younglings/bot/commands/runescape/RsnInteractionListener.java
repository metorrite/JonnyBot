package com.younglings.bot.commands.runescape;

import com.younglings.bot.permission.AdminRoleFilter;
import com.younglings.bot.runescape.MakeoverAppearance;
import com.younglings.bot.runescape.PlayerLink;
import com.younglings.bot.runescape.PlayerLinkRepository;
import com.younglings.bot.runescape.PlayerLinkService;
import com.younglings.bot.runescape.RuneScapeApiClient;
import com.younglings.bot.runescape.RuneScapeProfile;
import com.younglings.bot.runescape.RuneScapeStatsService;
import com.younglings.bot.runescape.VerificationAttempt;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.utils.FileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.util.List;

@BService
public class RsnInteractionListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(RsnInteractionListener.class);

    private final PlayerLinkService linkService;
    private final RuneScapeApiClient apiClient;
    private final RuneScapeStatsService statsService;
    private final AdminRoleFilter adminRoleFilter;

    public RsnInteractionListener(PlayerLinkService linkService, RuneScapeApiClient apiClient,
                                   RuneScapeStatsService statsService, AdminRoleFilter adminRoleFilter) {
        this.linkService = linkService;
        this.apiClient = apiClient;
        this.statsService = statsService;
        this.adminRoleFilter = adminRoleFilter;
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        if (event.getGuild() == null) return;
        String id = event.getComponentId();
        if (!id.startsWith("rsn_")) return;

        try {
            handleButton(event, id);
        } catch (Exception e) {
            log.error("Unhandled exception in rsn button interaction '{}'", id, e);
            replyError(event);
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        if (event.getGuild() == null) return;
        String id = event.getModalId();
        if (!id.startsWith("rsn_")) return;

        try {
            handleModal(event, id);
        } catch (Exception e) {
            log.error("Unhandled exception in rsn modal interaction '{}'", id, e);
            replyError(event);
        }
    }

    private void handleButton(ButtonInteractionEvent event, String id) {
        String action = id.split(":")[0];

        switch (action) {
            case "rsn_link" -> {
                TextInput rsnInput = TextInput.create("rsn_link_name", TextInputStyle.SHORT)
                        .setPlaceholder("Your exact in-game display name")
                        .setRequired(true)
                        .setRequiredRange(1, 12)
                        .build();

                Modal modal = Modal.create("rsn_link_modal:_", "Link Your RuneScape Name")
                        .addComponents(Label.of("RuneScape Name", rsnInput))
                        .build();

                event.replyModal(modal).queue();
            }

            case "rsn_stats" -> showStats(event, event.getGuild(), event.getUser().getIdLong());

            case "rsn_leaderboard" -> showLeaderboard(event, event.getGuild());

            case "rsn_review_pending" -> {
                if (!isAdmin(event)) {
                    event.reply("You need the Admin role (or higher) to use this.").setEphemeral(true).queue();
                    return;
                }

                List<VerificationAttempt> pending = linkService.getPendingAttempts(event.getGuild().getIdLong());
                if (pending.isEmpty()) {
                    event.reply("No pending verification requests.").setEphemeral(true).queue();
                    return;
                }

                StringBuilder sb = new StringBuilder("**Pending verifications:**\n");
                for (VerificationAttempt attempt : pending) {
                    sb.append("`#").append(attempt.attemptId()).append("` — ").append(attempt.rsn())
                            .append(" (<@").append(attempt.discordUserId()).append(">) — ")
                            .append(new MakeoverAppearance(attempt.assignedHairstyle(), attempt.assignedHairColor(), attempt.assignedSkinTone()).describe())
                            .append("\n");
                }

                event.reply(sb.toString()).setEphemeral(true).queue();
            }

            case "rsn_verify_ready" -> {
                long attemptId = Long.parseLong(id.split(":")[1]);
                VerificationAttempt attempt = linkService.getAttempt(attemptId);

                if (attempt == null || !"PENDING".equals(attempt.status())) {
                    event.reply("This verification request is no longer active.").setEphemeral(true).queue();
                    return;
                }
                if (attempt.discordUserId() != event.getUser().getIdLong()) {
                    event.reply("This isn't your verification request.").setEphemeral(true).queue();
                    return;
                }

                event.deferReply(true).queue();

                var imageBytes = apiClient.fetchAvatarImage(attempt.rsn());
                if (imageBytes.isEmpty()) {
                    event.getHook().editOriginal(
                            "Couldn't fetch an avatar for **" + attempt.rsn() + "** — double check the name is exact, " +
                            "and that your Adventurer's Log / avatar isn't set to private in your RuneScape account settings.").queue();
                    return;
                }

                MakeoverAppearance appearance = new MakeoverAppearance(
                        attempt.assignedHairstyle(), attempt.assignedHairColor(), attempt.assignedSkinTone());

                EmbedBuilder embed = new EmbedBuilder()
                        .setTitle("RSN Verification Request")
                        .setColor(Color.CYAN)
                        .setDescription("<@" + attempt.discordUserId() + "> claims to be **" + attempt.rsn() + "**\n\n" +
                                "Assigned appearance: " + appearance.describe() + "\n\n" +
                                "Compare the avatar below against the assigned appearance, then Approve or Reject.")
                        .setImage("attachment://avatar.png");

                event.getChannel().sendMessageEmbeds(embed.build())
                        .addFiles(FileUpload.fromData(imageBytes.get(), "avatar.png"))
                        .addComponents(ActionRow.of(
                                Button.success("rsn_verify_approve:" + attemptId, "Approve"),
                                Button.danger("rsn_verify_reject:" + attemptId, "Reject")
                        ))
                        .queue();

                event.getHook().editOriginal("Submitted for admin review — you'll be notified once it's checked.").queue();
            }

            case "rsn_verify_approve" -> {
                if (!isAdmin(event)) {
                    event.reply("You need the Admin role (or higher) to use this.").setEphemeral(true).queue();
                    return;
                }

                long attemptId = Long.parseLong(id.split(":")[1]);
                VerificationAttempt attempt = linkService.getAttempt(attemptId);
                boolean approved = linkService.approve(attemptId, event.getUser().getIdLong());

                if (!approved) {
                    event.reply("This request was already resolved.").setEphemeral(true).queue();
                    return;
                }

                event.editComponents().queue();
                event.getMessage().reply("✅ Approved by " + event.getUser().getAsMention() +
                                " — **" + attempt.rsn() + "** is now linked to <@" + attempt.discordUserId() + ">.")
                        .queue();
            }

            case "rsn_verify_reject" -> {
                if (!isAdmin(event)) {
                    event.reply("You need the Admin role (or higher) to use this.").setEphemeral(true).queue();
                    return;
                }

                long attemptId = Long.parseLong(id.split(":")[1]);
                boolean rejected = linkService.reject(attemptId, event.getUser().getIdLong());

                if (!rejected) {
                    event.reply("This request was already resolved.").setEphemeral(true).queue();
                    return;
                }

                event.editComponents().queue();
                event.getMessage().reply("❌ Rejected by " + event.getUser().getAsMention() + ".").queue();
            }
        }
    }

    private void handleModal(ModalInteractionEvent event, String modalId) {
        if (!modalId.equals("rsn_link_modal:_")) return;

        String rsn = event.getValue("rsn_link_name").getAsString().trim();
        long guildId = event.getGuild().getIdLong();
        long userId = event.getUser().getIdLong();

        PlayerLink existing = linkService.getLinkForRsn(guildId, rsn);
        if (existing != null) {
            event.reply(existing.discordUserId() == userId
                            ? "**" + rsn + "** is already linked to your account."
                            : "**" + rsn + "** is already linked to another Discord account.")
                    .setEphemeral(true).queue();
            return;
        }

        VerificationAttempt attempt = linkService.startVerification(guildId, userId, rsn);
        MakeoverAppearance appearance = new MakeoverAppearance(
                attempt.assignedHairstyle(), attempt.assignedHairColor(), attempt.assignedSkinTone());

        event.reply("**Verify you are " + rsn + "**\n\n" +
                        "1. Log in and visit the Makeover Mage.\n" +
                        "2. Set your appearance to: " + appearance.describe() + "\n" +
                        "3. Come back and click the button below.\n\n" +
                        "*Your look will need to be checked by an admin before the link is confirmed.*")
                .setEphemeral(true)
                .addComponents(ActionRow.of(Button.primary("rsn_verify_ready:" + attempt.attemptId(), "I've Applied My Look")))
                .queue();
    }

    // --- Stats display ---

    private void showStats(ButtonInteractionEvent event, Guild guild, long discordUserId) {
        List<PlayerLink> links = linkService.getLinksForUser(guild.getIdLong(), discordUserId);
        if (links.isEmpty()) {
            event.reply("You don't have a linked RSN yet — use **Link My RSN** first.").setEphemeral(true).queue();
            return;
        }

        event.deferReply(true).queue();

        PlayerLink link = links.getFirst();
        PlayerLinkRepository.StatsSnapshotRow previous = statsService.getLatestSnapshot(guild.getIdLong(), link.rsn());
        var profile = statsService.pollAndSnapshot(guild.getIdLong(), link.rsn());

        if (profile.isPresent()) {
            event.getHook().editOriginalEmbeds(buildStatsEmbed(link.rsn(), profile.get(), previous).build()).queue();
            return;
        }

        // RuneMetrics and hiscores are independently toggleable privacy settings in-game — a
        // player with RuneMetrics set private may still show up on hiscores, so it's worth trying
        // before giving up entirely.
        var overall = apiClient.fetchHiscoresOverall(link.rsn());
        if (overall.isPresent()) {
            EmbedBuilder embed = new EmbedBuilder()
                    .setTitle(link.rsn() + " — RuneScape 3 Stats (hiscores only)")
                    .setColor(Color.ORANGE)
                    .setDescription("Full profile is private — showing hiscores totals instead.")
                    .addField("Total Level", String.valueOf(overall.get().totalLevel()), true)
                    .addField("Total XP", String.format("%,d", overall.get().totalXp()), true)
                    .addField("Hiscores Rank", String.format("%,d", overall.get().rank()), true);
            event.getHook().editOriginalEmbeds(embed.build()).queue();
            return;
        }

        event.getHook().editOriginal(
                "Couldn't fetch stats for **" + link.rsn() + "** right now — their profile and hiscores may both be " +
                "private, or the RuneScape API may be temporarily unavailable.").queue();
    }

    private static final int LEADERBOARD_SIZE = 10;

    /** Ranks by each linked player's most recent snapshot — doesn't trigger a live poll itself, so this stays fast and doesn't hammer the API on every view. */
    private void showLeaderboard(ButtonInteractionEvent event, Guild guild) {
        List<PlayerLink> links = linkService.getAllLinks(guild.getIdLong());
        if (links.isEmpty()) {
            event.reply("No linked players yet.").setEphemeral(true).queue();
            return;
        }

        record Entry(PlayerLink link, PlayerLinkRepository.StatsSnapshotRow snapshot) {}

        List<Entry> entries = links.stream()
                .map(link -> new Entry(link, statsService.getLatestSnapshot(guild.getIdLong(), link.rsn())))
                .filter(entry -> entry.snapshot() != null)
                .sorted((a, b) -> Long.compare(b.snapshot().totalXp(), a.snapshot().totalXp()))
                .limit(LEADERBOARD_SIZE)
                .toList();

        if (entries.isEmpty()) {
            event.reply("No stats have been synced yet — check back after the next automatic poll, " +
                            "or have members use **My Stats** once to sync immediately.")
                    .setEphemeral(true).queue();
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            sb.append("**").append(i + 1).append(".** ").append(entry.link().rsn())
                    .append(" — ").append(String.format("%,d", entry.snapshot().totalXp())).append(" XP")
                    .append(" (Level ").append(entry.snapshot().totalLevel()).append(")\n");
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("RuneScape 3 Leaderboard — Total XP")
                .setColor(Color.ORANGE)
                .setDescription(sb.toString())
                .setFooter("Based on each player's last synced snapshot, not a live poll.");

        event.replyEmbeds(embed.build()).setEphemeral(true).queue();
    }

    private EmbedBuilder buildStatsEmbed(String rsn, RuneScapeProfile profile, PlayerLinkRepository.StatsSnapshotRow previous) {
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(rsn + " — RuneScape 3 Stats")
                .setColor(Color.ORANGE)
                .addField("Total Level", String.valueOf(profile.totalLevel()), true)
                .addField("Combat Level", String.valueOf(profile.combatLevel()), true)
                .addField("Quests Complete", String.valueOf(profile.questsComplete()), true)
                .addField("Total XP", String.format("%,d", profile.totalXp()), true);

        if (previous != null) {
            long xpGained = profile.totalXp() - previous.totalXp();
            int levelsGained = profile.totalLevel() - previous.totalLevel();
            if (xpGained > 0 || levelsGained > 0) {
                embed.addField("Since last check",
                        String.format("+%,d XP, +%d level(s)", xpGained, levelsGained), false);
            }
        }

        return embed;
    }

    // --- Helpers ---

    private boolean isAdmin(ButtonInteractionEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        return guild != null && member != null && adminRoleFilter.isAuthorized(guild, member);
    }

    private void replyError(ButtonInteractionEvent event) {
        try {
            if (!event.isAcknowledged()) {
                event.reply("An unexpected error occurred. Please try again or contact an admin.")
                        .setEphemeral(true).queue();
            }
        } catch (Exception ignored) {}
    }

    private void replyError(ModalInteractionEvent event) {
        try {
            if (!event.isAcknowledged()) {
                event.reply("An unexpected error occurred. Please try again or contact an admin.")
                        .setEphemeral(true).queue();
            }
        } catch (Exception ignored) {}
    }
}
