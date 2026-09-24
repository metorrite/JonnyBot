package com.younglings.bot.commands.runescape;

import com.younglings.bot.discord.Containers;
import com.younglings.bot.discord.Pagination;
import com.younglings.bot.permission.AdminRoleFilter;
import com.younglings.bot.runescape.AvatarResult;
import com.younglings.bot.runescape.MakeoverAppearance;
import com.younglings.bot.runescape.PlayerActivity;
import com.younglings.bot.runescape.PlayerLink;
import com.younglings.bot.runescape.PlayerLinkRepository;
import com.younglings.bot.runescape.PlayerLinkService;
import com.younglings.bot.runescape.RuneScapeApiClient;
import com.younglings.bot.runescape.RuneScapeSkillCatalog;
import com.younglings.bot.runescape.RuneScapeStatsService;
import com.younglings.bot.runescape.SkillEmojiCatalog;
import com.younglings.bot.runescape.SkillValue;
import com.younglings.bot.runescape.VerificationAttempt;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.mediagallery.MediaGallery;
import net.dv8tion.jda.api.components.mediagallery.MediaGalleryItem;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.utils.FileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

@BService
public class RsnInteractionListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(RsnInteractionListener.class);
    private static final Color RS3_ORANGE = Color.ORANGE;

    private final PlayerLinkService linkService;
    private final RuneScapeApiClient apiClient;
    private final RuneScapeStatsService statsService;
    private final AdminRoleFilter adminRoleFilter;
    private final SkillEmojiCatalog skillEmojiCatalog;

    public RsnInteractionListener(PlayerLinkService linkService, RuneScapeApiClient apiClient,
                                   RuneScapeStatsService statsService, AdminRoleFilter adminRoleFilter,
                                   SkillEmojiCatalog skillEmojiCatalog) {
        this.linkService = linkService;
        this.apiClient = apiClient;
        this.statsService = statsService;
        this.adminRoleFilter = adminRoleFilter;
        this.skillEmojiCatalog = skillEmojiCatalog;
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
            Containers.replyError(event);
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
            Containers.replyError(event);
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

            case "rsn_stats_pick" -> {
                event.deferReply(true).queue();
                showStatsForRsn(event, event.getGuild(), id.split(":", 2)[1]);
            }

            case "rsn_lookup" -> {
                TextInput rsnInput = TextInput.create("rsn_lookup_name", TextInputStyle.SHORT)
                        .setPlaceholder("Exact in-game display name")
                        .setRequired(true)
                        .setRequiredRange(1, 12)
                        .build();

                Modal modal = Modal.create("rsn_lookup_modal:_", "Look Up a Player")
                        .addComponents(Label.of("RuneScape Name", rsnInput))
                        .build();

                event.replyModal(modal).queue();
            }

            case "rsn_poll" -> pollRsnAndShow(event, event.getGuild(), id.split(":", 2)[1]);

            case "rsn_skills" -> showSkills(event, event.getGuild(), id.split(":", 2)[1]);

            case "rsn_history" -> showHistory(event, event.getGuild(), id.split(":", 2)[1]);

            case "rsn_activity" -> showActivity(event, event.getGuild(), id.split(":", 2)[1]);

            case "rsn_leaderboard" -> showLeaderboard(event, event.getGuild());

            case "rsn_review_pending" -> {
                if (!isAdmin(event)) {
                    Containers.replyEphemeral(event, Containers.WARNING, "You need the Admin role (or higher) to use this.");
                    return;
                }
                showPendingVerifications(event, 0, false);
            }

            case "rsn_pending_page" -> {
                if (!isAdmin(event)) {
                    Containers.replyEphemeral(event, Containers.WARNING, "You need the Admin role (or higher) to use this.");
                    return;
                }
                showPendingVerifications(event, Integer.parseInt(id.split(":")[1]), true);
            }

            case "rsn_verify_ready" -> {
                long attemptId = Long.parseLong(id.split(":")[1]);
                VerificationAttempt attempt = linkService.getAttempt(attemptId);

                if (attempt == null || !"PENDING".equals(attempt.status())) {
                    Containers.replyEphemeral(event, Containers.WARNING, "This verification request is no longer active.");
                    return;
                }
                if (attempt.discordUserId() != event.getUser().getIdLong()) {
                    Containers.replyEphemeral(event, Containers.WARNING, "This isn't your verification request.");
                    return;
                }

                event.deferReply(true).queue();

                AvatarResult avatarResult = apiClient.fetchAvatarImage(attempt.rsn());

                switch (avatarResult) {
                    case AvatarResult.NotCustomized ignored -> {
                        // As of RuneScape's 8 June 2026 avatar-system migration, the "photobooth" that
                        // renders this image site-wide is temporarily disabled — every RSN currently
                        // resolves to the default placeholder regardless of whether it's customized or
                        // even real, not just accounts with privacy settings. Rather than dead-end the
                        // whole verification flow until Jagex restores it, this still routes to an admin
                        // for a manual override decision — no photo comparison is possible, so it's a
                        // trust call instead of a visual one until the photobooth comes back.
                        MakeoverAppearance appearance = new MakeoverAppearance(
                                attempt.assignedHairstyle(), attempt.assignedHairColor(), attempt.assignedSkinTone());

                        Container review = Containers.card(Containers.WARNING,
                                TextDisplay.of("### RSN Verification Request — No Avatar Available"),
                                TextDisplay.of("<@" + attempt.discordUserId() + "> claims to be **" + attempt.rsn() + "**\n\n" +
                                        "Assigned appearance: " + appearance.describe() + "\n\n" +
                                        "RuneScape returned its generic default avatar instead of a real one. This can mean " +
                                        "the name is misspelled, doesn't exist, the account has never customized its look — " +
                                        "**or that RuneScape's avatar photobooth is currently disabled game-wide** " +
                                        "(taken down for an avatar system migration as of June 2026, no announced return date). " +
                                        "No photo comparison is possible right now, so this needs a manual override call instead."),
                                TextDisplay.of("-# Admin override — no avatar image to compare against."),
                                ActionRow.of(
                                        Button.success("rsn_verify_approve:" + attemptId, "Approve (Override)"),
                                        Button.danger("rsn_verify_reject:" + attemptId, "Reject")
                                ));

                        event.getChannel().sendMessageComponents(List.of(review)).useComponentsV2(true).queue();

                        event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.INFO,
                                "No avatar could be fetched right now (RuneScape's photobooth may be disabled) — submitted " +
                                "for manual admin review anyway. You'll be notified once it's checked."))).useComponentsV2(true).queue();
                    }

                    case AvatarResult.Unavailable ignored -> event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.WARNING,
                            "Couldn't fetch an avatar for **" + attempt.rsn() + "** right now — the RuneScape API " +
                            "may be temporarily unavailable. Try again in a bit."))).useComponentsV2(true).queue();

                    case AvatarResult.Found(byte[] imageBytes) -> {
                        MakeoverAppearance appearance = new MakeoverAppearance(
                                attempt.assignedHairstyle(), attempt.assignedHairColor(), attempt.assignedSkinTone());

                        FileUpload avatarFile = FileUpload.fromData(imageBytes, "avatar.png");

                        Container review = Containers.card(Containers.PRIMARY,
                                TextDisplay.of("### RSN Verification Request"),
                                TextDisplay.of("<@" + attempt.discordUserId() + "> claims to be **" + attempt.rsn() + "**\n\n" +
                                        "Assigned appearance: " + appearance.describe() + "\n\n" +
                                        "Compare the avatar below against the assigned appearance, then Approve or Reject."),
                                MediaGallery.of(MediaGalleryItem.fromFile(avatarFile)),
                                ActionRow.of(
                                        Button.success("rsn_verify_approve:" + attemptId, "Approve"),
                                        Button.danger("rsn_verify_reject:" + attemptId, "Reject")
                                ));

                        event.getChannel().sendMessageComponents(List.of(review)).useComponentsV2(true).queue();

                        event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.SUCCESS,
                                "Submitted for admin review — you'll be notified once it's checked."))).useComponentsV2(true).queue();
                    }
                }
            }

            case "rsn_verify_approve" -> {
                if (!isAdmin(event)) {
                    Containers.replyEphemeral(event, Containers.WARNING, "You need the Admin role (or higher) to use this.");
                    return;
                }

                long attemptId = Long.parseLong(id.split(":")[1]);
                VerificationAttempt attempt = linkService.getAttempt(attemptId);
                boolean approved = linkService.approve(attemptId, event.getUser().getIdLong());

                if (!approved) {
                    Containers.replyEphemeral(event, Containers.WARNING, "This request was already resolved.");
                    return;
                }

                event.editComponents().queue();
                event.getMessage().replyComponents(List.of(Containers.toast(Containers.SUCCESS,
                        "✅ Approved by " + event.getUser().getAsMention() +
                                " — **" + attempt.rsn() + "** is now linked to <@" + attempt.discordUserId() + ">.")))
                        .useComponentsV2(true).queue();
            }

            case "rsn_cancel_own" -> {
                long attemptId = Long.parseLong(id.split(":")[1]);
                boolean cancelled = linkService.cancelOwn(attemptId, event.getUser().getIdLong());

                if (!cancelled) {
                    Containers.replyEphemeral(event, Containers.WARNING, "That request is no longer active.");
                    return;
                }

                Containers.replyEphemeral(event, Containers.SUCCESS,
                        "Cancelled — click **Link My RSN** again to start over with a different name.");
            }

            case "rsn_verify_reject" -> {
                if (!isAdmin(event)) {
                    Containers.replyEphemeral(event, Containers.WARNING, "You need the Admin role (or higher) to use this.");
                    return;
                }

                long attemptId = Long.parseLong(id.split(":")[1]);
                boolean rejected = linkService.reject(attemptId, event.getUser().getIdLong());

                if (!rejected) {
                    Containers.replyEphemeral(event, Containers.WARNING, "This request was already resolved.");
                    return;
                }

                event.editComponents().queue();
                event.getMessage().replyComponents(List.of(Containers.toast(Containers.DANGER,
                                "❌ Rejected by " + event.getUser().getAsMention() + ".")))
                        .useComponentsV2(true).queue();
            }
        }
    }

    private void handleModal(ModalInteractionEvent event, String modalId) {
        if (modalId.equals("rsn_lookup_modal:_")) {
            String rsn = event.getValue("rsn_lookup_name").getAsString().trim();
            event.deferReply(true).queue();
            showStatsForRsn(event, event.getGuild(), rsn);
            return;
        }

        if (!modalId.equals("rsn_link_modal:_")) return;

        String rsn = event.getValue("rsn_link_name").getAsString().trim();
        long guildId = event.getGuild().getIdLong();
        long userId = event.getUser().getIdLong();

        PlayerLink existing = linkService.getLinkForRsn(guildId, rsn);
        if (existing != null) {
            Containers.replyEphemeral(event, Containers.WARNING, existing.discordUserId() == userId
                    ? "**" + rsn + "** is already linked to your account."
                    : "**" + rsn + "** is already linked to another Discord account.");
            return;
        }

        VerificationAttempt pending = linkService.getPendingAttemptForUser(guildId, userId);
        if (pending != null) {
            MakeoverAppearance pendingAppearance = new MakeoverAppearance(
                    pending.assignedHairstyle(), pending.assignedHairColor(), pending.assignedSkinTone());

            Container container = Containers.card(Containers.WARNING,
                    TextDisplay.of("You already have a verification in progress for **" + pending.rsn() + "**.\n\n" +
                            "Assigned appearance: " + pendingAppearance.describe() + "\n\n" +
                            "Apply that look in-game, then click below — or start over if you meant a different name."),
                    ActionRow.of(
                            Button.primary("rsn_verify_ready:" + pending.attemptId(), "I've Applied My Look"),
                            Button.secondary("rsn_cancel_own:" + pending.attemptId(), "Start Over (Wrong Name?)")
                    ));

            event.replyComponents(List.of(container)).useComponentsV2(true).setEphemeral(true).queue();
            return;
        }

        VerificationAttempt attempt = linkService.startVerification(guildId, userId, rsn);
        MakeoverAppearance appearance = new MakeoverAppearance(
                attempt.assignedHairstyle(), attempt.assignedHairColor(), attempt.assignedSkinTone());

        Container container = Containers.card(Containers.PRIMARY,
                TextDisplay.of("**Verify you are " + rsn + "**\n\n" +
                        "1. Log in and visit the Makeover Mage.\n" +
                        "2. Set your appearance to: " + appearance.describe() + "\n" +
                        "3. Come back and click the button below.\n\n" +
                        "*Your look will need to be checked by an admin before the link is confirmed.*"),
                ActionRow.of(Button.primary("rsn_verify_ready:" + attempt.attemptId(), "I've Applied My Look")));

        event.replyComponents(List.of(container)).useComponentsV2(true).setEphemeral(true).queue();
    }

    // --- Pending verifications (admin) ---

    private void showPendingVerifications(ButtonInteractionEvent event, int pageIndex, boolean isPageNav) {
        List<VerificationAttempt> pending = linkService.getPendingAttempts(event.getGuild().getIdLong());
        if (pending.isEmpty()) {
            Containers.replyEphemeral(event, Containers.INFO, "No pending verification requests.");
            return;
        }

        var page = Pagination.paginate(pending, pageIndex);

        StringBuilder sb = new StringBuilder();
        for (VerificationAttempt attempt : page.items()) {
            sb.append("`#").append(attempt.attemptId()).append("` — ").append(attempt.rsn())
                    .append(" (<@").append(attempt.discordUserId()).append(">) — ")
                    .append(new MakeoverAppearance(attempt.assignedHairstyle(), attempt.assignedHairColor(), attempt.assignedSkinTone()).describe())
                    .append("\n");
        }

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("# Pending Verifications (" + pending.size() + ")"));
        children.add(TextDisplay.of(sb.toString()));
        if (!page.isSinglePage()) {
            children.add(Pagination.navRow(page, "rsn_pending_page:"));
        }

        Container container = Containers.card(Containers.INFO, children);

        if (isPageNav) {
            event.editComponents(List.of(container)).useComponentsV2(true).queue();
        } else {
            event.replyComponents(List.of(container)).useComponentsV2(true).setEphemeral(true).queue();
        }
    }

    // --- Stats display ---

    /** "My Stats" entry point: shows the one linked RSN directly, or a picker if there's more than one — see {@link PlayerLink}'s doc on multi-RSN support. */
    private void showStats(ButtonInteractionEvent event, Guild guild, long discordUserId) {
        List<PlayerLink> links = linkService.getLinksForUser(guild.getIdLong(), discordUserId);
        if (links.isEmpty()) {
            Containers.replyEphemeral(event, Containers.WARNING, "You don't have a linked RSN yet — use **Link My RSN** first.");
            return;
        }

        if (links.size() == 1) {
            event.deferReply(true).queue();
            showStatsForRsn(event, guild, links.getFirst().rsn());
            return;
        }

        List<Button> buttons = links.stream()
                .limit(5) // ActionRow.of's own cap — a person with more than 5 linked RSNs is not a case worth building a second row for yet
                .map(link -> Button.secondary("rsn_stats_pick:" + link.rsn(), link.rsn()))
                .toList();

        Container container = Containers.card(Containers.PRIMARY,
                TextDisplay.of("You have **" + links.size() + "** RuneScape names linked. Which one?"),
                ActionRow.of(buttons));

        event.replyComponents(List.of(container)).useComponentsV2(true).setEphemeral(true).queue();
    }

    /**
     * The shared "show me what we already know" path for "My Stats" (single link), picking one of
     * several linked RSNs, and "Look Up Player" alike. Reads the latest stored snapshot only — no
     * live API call — so viewing stats never triggers a poll on its own; the stats card's own
     * "Poll Now" button ({@link #pollRsnAndShow}) is the only thing that does. Caller must have
     * already deferred the reply.
     */
    private void showStatsForRsn(IReplyCallback event, Guild guild, String rsn) {
        List<PlayerLinkRepository.StatsSnapshotRow> recent = statsService.getSnapshotHistory(guild.getIdLong(), rsn, 2);

        if (recent.isEmpty()) {
            Container container = Containers.card(Containers.WARNING,
                    TextDisplay.of("### " + rsn),
                    TextDisplay.of("No synced data yet for this name — click **Poll Now** to fetch it."),
                    ActionRow.of(Button.primary("rsn_poll:" + rsn, "Poll Now")));
            event.getHook().editOriginalComponents(List.of(container)).useComponentsV2(true).queue();
            return;
        }

        PlayerLinkRepository.StatsSnapshotRow latest = recent.getFirst();
        PlayerLinkRepository.StatsSnapshotRow previous = recent.size() > 1 ? recent.get(1) : null;

        event.getHook().editOriginalComponents(List.of(buildStatsContainer(rsn, latest, previous)))
                .useComponentsV2(true).queue();
    }

    /** The only member-facing action that actually calls the RuneScape API — triggered by the stats card's own "Poll Now" button. */
    private void pollRsnAndShow(ButtonInteractionEvent event, Guild guild, String rsn) {
        event.deferReply(true).queue();

        var profile = statsService.pollAndSnapshot(guild.getIdLong(), rsn);
        if (profile.isPresent()) {
            showStatsForRsn(event, guild, rsn);
            return;
        }

        // RuneMetrics and hiscores are independently toggleable privacy settings in-game — a
        // player with RuneMetrics set private may still show up on hiscores, so it's worth trying
        // before giving up entirely.
        var overall = apiClient.fetchHiscoresOverall(rsn);
        if (overall.isPresent()) {
            Container container = Containers.card(RS3_ORANGE,
                    TextDisplay.of("### " + rsn + " — RuneScape 3 Stats (hiscores only)"),
                    TextDisplay.of("Full profile is private — showing hiscores totals instead.\n\n" +
                            "**Total Level:** " + overall.get().totalLevel() + "\n" +
                            "**Total XP:** " + String.format("%,d", overall.get().totalXp()) + "\n" +
                            "**Hiscores Rank:** " + String.format("%,d", overall.get().rank())));
            event.getHook().editOriginalComponents(List.of(container)).useComponentsV2(true).queue();
            return;
        }

        event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.WARNING,
                "Couldn't fetch stats for **" + rsn + "** right now — their profile and hiscores may both be " +
                "private, the name may not exist, or the RuneScape API may be temporarily unavailable."))).useComponentsV2(true).queue();
    }

    // Longest skill name ("Dungeoneering") sets the name column's width so every row's "Level"
    // and XP columns start at the same character position — plain monospace padding, since a
    // proportional (bold) font can't be aligned this way, and Discord's inline emoji mentions
    // can't appear inside a code span at all, hence sitting outside it below.
    private static final int SKILL_NAME_COLUMN_WIDTH = 13;

    /**
     * All 29 skills on one screen, in hiscores order (skill ID order — same as
     * {@link RuneScapeStatsService#getSkillsForSnapshot} already returns them in), each line led
     * by its inline emoji ({@link SkillEmojiCatalog}) — an emoji mention renders to the LEFT of
     * the text that follows it, unlike a {@code Section}'s thumbnail accessory which Discord
     * always renders on the right with no way to flip it. Bundling every skill into one
     * TextDisplay (not one per skill) also keeps this comfortably under both the 40-node
     * component-tree budget and the 4000-character content budget, so there's no need to
     * paginate — one read from the database, one render, done.
     */
    private void showSkills(ButtonInteractionEvent event, Guild guild, String rsn) {
        PlayerLinkRepository.StatsSnapshotRow latest = statsService.getLatestSnapshot(guild.getIdLong(), rsn);
        if (latest == null) {
            Containers.replyEphemeral(event, Containers.WARNING,
                    "No synced data for **" + rsn + "** yet — use **Poll Now** on their stats card first.");
            return;
        }

        List<SkillValue> skills = statsService.getSkillsForSnapshot(latest.snapshotId());
        if (skills.isEmpty()) {
            Containers.replyEphemeral(event, Containers.WARNING, "No skill data in this snapshot.");
            return;
        }

        StringBuilder body = new StringBuilder();
        for (SkillValue skill : skills) {
            String mention = skillEmojiCatalog.mentionFor(skill.skillId());
            String row = String.format("%-" + SKILL_NAME_COLUMN_WIDTH + "s  Level %-3d  %-11s xp",
                    RuneScapeSkillCatalog.nameFor(skill.skillId()), skill.level(), String.format("%,d", skill.xp()));
            if (mention != null) body.append(mention).append(" ");
            body.append("`").append(row).append("`\n");
        }

        Container container = Containers.card(RS3_ORANGE,
                TextDisplay.of("### " + rsn + " — Skills"),
                TextDisplay.of(body.toString().stripTrailing()),
                TextDisplay.of("-# As of <t:" + latest.snapshotAt().toEpochSecond() + ":R>"));
        event.replyComponents(List.of(container)).useComponentsV2(true).setEphemeral(true).queue();
    }

    private static final int HISTORY_SIZE = 10;

    private void showHistory(ButtonInteractionEvent event, Guild guild, String rsn) {
        List<PlayerLinkRepository.StatsSnapshotRow> history = statsService.getSnapshotHistory(guild.getIdLong(), rsn, HISTORY_SIZE);
        if (history.isEmpty()) {
            Containers.replyEphemeral(event, Containers.WARNING,
                    "No synced data for **" + rsn + "** yet — use **My Stats** or **Look Up Player** first.");
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < history.size(); i++) {
            PlayerLinkRepository.StatsSnapshotRow row = history.get(i);
            sb.append("<t:").append(row.snapshotAt().toEpochSecond()).append(":R> — Level ")
                    .append(row.totalLevel()).append(", ").append(String.format("%,d", row.totalXp())).append(" xp");

            if (i + 1 < history.size()) {
                long xpGained = row.totalXp() - history.get(i + 1).totalXp();
                if (xpGained != 0) sb.append(" (").append(xpGained > 0 ? "+" : "").append(String.format("%,d", xpGained)).append(")");
            }
            sb.append("\n");
        }

        Container container = Containers.card(RS3_ORANGE,
                TextDisplay.of("### " + rsn + " — Recent History"),
                TextDisplay.of(sb.toString()),
                TextDisplay.of("-# Most recent " + history.size() + " poll(s), newest first."));

        event.replyComponents(List.of(container)).useComponentsV2(true).setEphemeral(true).queue();
    }

    private static final int ACTIVITY_SIZE = 15;

    private void showActivity(ButtonInteractionEvent event, Guild guild, String rsn) {
        List<PlayerActivity> activities = statsService.getRecentActivities(guild.getIdLong(), rsn, ACTIVITY_SIZE);
        if (activities.isEmpty()) {
            Containers.replyEphemeral(event, Containers.WARNING,
                    "No recorded activity for **" + rsn + "** yet — activity is captured the next time their stats are polled.");
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (PlayerActivity activity : activities) {
            sb.append("`").append(activity.date()).append("` — ").append(activity.text()).append("\n");
        }

        Container container = Containers.card(RS3_ORANGE,
                TextDisplay.of("### " + rsn + " — Recent Activity"),
                TextDisplay.of(sb.toString()),
                TextDisplay.of("-# Dates are RuneScape's own timestamps, timezone as reported by the game."));

        event.replyComponents(List.of(container)).useComponentsV2(true).setEphemeral(true).queue();
    }

    private static final int LEADERBOARD_SIZE = 10;

    /** Ranks by each linked player's most recent snapshot — doesn't trigger a live poll itself, so this stays fast and doesn't hammer the API on every view. */
    private void showLeaderboard(ButtonInteractionEvent event, Guild guild) {
        List<PlayerLink> links = linkService.getAllLinks(guild.getIdLong());
        if (links.isEmpty()) {
            Containers.replyEphemeral(event, Containers.INFO, "No linked players yet.");
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
            Containers.replyEphemeral(event, Containers.INFO,
                    "No stats have been synced yet — polling is manual right now, "
                            + "so someone needs to click **Poll Now** on their stats (or an admin needs to poll from the admin panel) first.");
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            sb.append("**").append(i + 1).append(".** ").append(entry.link().rsn())
                    .append(" — ").append(String.format("%,d", entry.snapshot().totalXp())).append(" XP")
                    .append(" (Level ").append(entry.snapshot().totalLevel()).append(")\n");
        }

        Container container = Containers.card(RS3_ORANGE,
                TextDisplay.of("### RuneScape 3 Leaderboard — Total XP"),
                TextDisplay.of(sb.toString()),
                TextDisplay.of("-# Based on each player's last synced snapshot, not a live poll."));

        event.replyComponents(List.of(container)).useComponentsV2(true).setEphemeral(true).queue();
    }

    private Container buildStatsContainer(String rsn, PlayerLinkRepository.StatsSnapshotRow latest, PlayerLinkRepository.StatsSnapshotRow previous) {
        String overallMention = skillEmojiCatalog.overallMention();
        String lead = overallMention != null ? overallMention + " " : "";

        StringBuilder sb = new StringBuilder()
                .append(lead).append("**Total Level:** ").append(latest.totalLevel()).append("\n")
                .append("**Combat Level:** ").append(latest.combatLevel()).append("\n")
                .append("**Quests Complete:** ").append(latest.questsComplete()).append("\n")
                .append("**Total XP:** `").append(String.format("%,d", latest.totalXp())).append(" xp`");

        if (previous != null) {
            long xpGained = latest.totalXp() - previous.totalXp();
            int levelsGained = latest.totalLevel() - previous.totalLevel();
            if (xpGained > 0 || levelsGained > 0) {
                sb.append("\n\n**Since last poll:** +").append(levelsGained).append(" level(s), `+")
                        .append(String.format("%,d", xpGained)).append(" xp`");
            }
        }

        return Containers.card(RS3_ORANGE,
                TextDisplay.of("### " + rsn + " — RuneScape 3 Stats"),
                TextDisplay.of(sb.toString()),
                TextDisplay.of("-# As of <t:" + latest.snapshotAt().toEpochSecond() + ":R>"),
                ActionRow.of(
                        Button.primary("rsn_poll:" + rsn, "Poll Now"),
                        Button.secondary("rsn_skills:" + rsn, "View Skills"),
                        Button.secondary("rsn_history:" + rsn, "History"),
                        Button.secondary("rsn_activity:" + rsn, "Recent Activity")
                ));
    }

    // --- Helpers ---

    private boolean isAdmin(ButtonInteractionEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        return guild != null && member != null && adminRoleFilter.isAuthorized(guild, member);
    }
}
