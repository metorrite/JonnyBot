package com.younglings.bot.commands.runescape;

import com.younglings.bot.discord.Containers;
import com.younglings.bot.discord.Pagination;
import com.younglings.bot.permission.AdminRoleFilter;
import com.younglings.bot.runescape.ClanMemberRepository;
import com.younglings.bot.runescape.ClanOverviewRenderer;
import com.younglings.bot.runescape.ClanOverviewService;
import com.younglings.bot.runescape.ClanSyncService;
import com.younglings.bot.runescape.MonthlyRecapRenderer;
import com.younglings.bot.runescape.MonthlyRecapService;
import com.younglings.bot.runescape.MonthlyRecapStats;
import com.younglings.bot.runescape.PlayerLink;
import com.younglings.bot.runescape.PlayerLinkRepository;
import com.younglings.bot.runescape.PlayerLinkService;
import com.younglings.bot.runescape.ProfileResult;
import com.younglings.bot.runescape.RsnRenameService;
import com.younglings.bot.runescape.RuneScapeSkillCatalog;
import com.younglings.bot.runescape.RuneScapeStatsService;
import com.younglings.bot.runescape.RuneScapeTestDataSeeder;
import com.younglings.bot.runescape.RuneScapeXpTable;
import com.younglings.bot.runescape.SkillValue;
import com.younglings.bot.runescape.VerificationAttempt;
import com.younglings.bot.runescape.VerificationRoleSyncService;
import com.younglings.bot.runescape.XpChartRenderer;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.mediagallery.MediaGallery;
import net.dv8tion.jda.api.components.mediagallery.MediaGalleryItem;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.ComponentInteraction;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.utils.FileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Buttons/modal for {@link RsAdminCommand}'s panel: bulk actions (poll everyone, sync the clan
 * roster), admin-only per-player tools (Player Lookup, Manually Verify, Update RSN), and the full
 * pending-verification queue (moved here from the member-facing {@code /rs} — see
 * {@link RsInteractionListener}, which only ever shows a caller their own pending status now).
 * <p>
 * Deliberately does <em>not</em> show the calling admin's own linked account(s) the way it once
 * did — that's what {@code /rs} is for, same as any other member, so there's exactly one place that
 * renders a self-service profile instead of two copies of the same view.
 * <p>
 * "Poll Now"/"Poll Again" here is unrestricted (no self-poll cooldown) — that limit only applies to
 * a member's own {@code rs_poll} click under {@code /rs}; see {@code PlayerLinkService#canSelfPoll}.
 */
@BService
public class RsAdminInteractionListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(RsAdminInteractionListener.class);

    private final PlayerLinkService linkService;
    private final RuneScapeStatsService statsService;
    private final AdminRoleFilter adminRoleFilter;
    private final RuneScapeTestDataSeeder testDataSeeder;
    private final MonthlyRecapService monthlyRecapService;
    private final ClanSyncService clanSyncService;
    private final ClanOverviewService clanOverviewService;
    private final VerificationRoleSyncService roleSyncService;
    private final RsnRenameService renameService;
    private final RsChartInteractionListener chartListener;
    private final RsInteractionListener rsInteractionListener;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public RsAdminInteractionListener(PlayerLinkService linkService, RuneScapeStatsService statsService,
                                       AdminRoleFilter adminRoleFilter, RuneScapeTestDataSeeder testDataSeeder,
                                       MonthlyRecapService monthlyRecapService, ClanSyncService clanSyncService,
                                       ClanOverviewService clanOverviewService, VerificationRoleSyncService roleSyncService,
                                       RsnRenameService renameService, RsChartInteractionListener chartListener,
                                       RsInteractionListener rsInteractionListener) {
        this.linkService = linkService;
        this.statsService = statsService;
        this.adminRoleFilter = adminRoleFilter;
        this.testDataSeeder = testDataSeeder;
        this.monthlyRecapService = monthlyRecapService;
        this.clanSyncService = clanSyncService;
        this.clanOverviewService = clanOverviewService;
        this.roleSyncService = roleSyncService;
        this.renameService = renameService;
        this.chartListener = chartListener;
        this.rsInteractionListener = rsInteractionListener;
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        String id = event.getComponentId();
        if (guild == null || member == null || !id.startsWith("rsnadmin_")) return;

        try {
            if (!adminRoleFilter.isAuthorized(guild, member)) {
                Containers.replyEphemeral(event, Containers.WARNING, "You need the Admin role (or higher) to use this.");
                return;
            }
            handleButton(event, guild, id);
        } catch (Exception e) {
            log.error("Unhandled exception in rsnadmin button interaction '{}'", id, e);
            Containers.replyError(event);
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        String id = event.getModalId();
        if (guild == null || member == null || !id.startsWith("rsnadmin_")) return;

        try {
            if (!adminRoleFilter.isAuthorized(guild, member)) {
                Containers.replyEphemeral(event, Containers.WARNING, "You need the Admin role (or higher) to use this.");
                return;
            }
            if (id.equals("rsnadmin_manualverify_modal:_")) {
                handleManualVerifyModal(event, guild, member);
                return;
            }
            if (id.equals("rsnadmin_lookup_modal:_")) {
                handleLookupModal(event, guild);
                return;
            }
            if (id.startsWith("rsnadmin_renamemodal:")) {
                handleRenameModal(event, guild, id.split(":", 2)[1]);
                return;
            }
            if (id.startsWith("rsnadmin_joindatemodal:")) {
                handleJoinDateModal(event, guild, id.split(":", 2)[1]);
            }
        } catch (Exception e) {
            log.error("Unhandled exception in rsnadmin modal interaction '{}'", id, e);
            Containers.replyError(event);
        }
    }

    /**
     * Always fetches live rather than reading storage first — this is the "look up anyone, in the
     * clan or not, tracked before or not" tool, so a stale/missing local row isn't a reason to stop.
     * Also saves the fetch as a real snapshot on success, the same as any other poll, so looking
     * someone up starts tracking them going forward.
     */
    private void handleLookupModal(ModalInteractionEvent event, Guild guild) {
        String rsn = event.getValue("lookup_rsn").getAsString().trim();
        event.deferReply(true).queue();

        ProfileResult result = statsService.pollAndSnapshotResult(guild.getIdLong(), rsn);
        event.getHook().editOriginalComponents(List.of(buildLookupCard(guild, rsn, result))).useComponentsV2(true)
                .setAllowedMentions(EnumSet.noneOf(Message.MentionType.class)).queue();
    }

    /**
     * Repoints an existing link at a new RSN in place — the manual fallback for a name change,
     * always available regardless of whether {@link RsnRenameService}'s automated detection ever
     * catches it (it only runs during **Sync Clan**, and only for clan-roster members in the first
     * place). Historical snapshots/activity stay under the old name; only the link moves.
     */
    private void handleRenameModal(ModalInteractionEvent event, Guild guild, String oldRsn) {
        String newRsn = event.getValue("new_rsn").getAsString().trim();
        PlayerLink link = linkService.getLinkForRsn(guild.getIdLong(), oldRsn);
        if (link == null) {
            Containers.replyEphemeral(event, Containers.WARNING,
                    "**" + oldRsn + "** isn't linked to anyone — nothing to rename. Use **Manually Verify** to link **" + newRsn + "** directly instead.");
            return;
        }

        try {
            linkService.renameLink(guild.getIdLong(), link.linkId(), newRsn);
            renameService.recordManualRename(guild.getIdLong(), oldRsn, newRsn);
            Containers.replyEphemeral(event, Containers.SUCCESS,
                    "Updated the link: **" + oldRsn + "** → **" + newRsn + "**. Historical data stays under the old name.");
        } catch (RuntimeException e) {
            Containers.replyEphemeral(event, Containers.WARNING,
                    "Couldn't update — **" + newRsn + "** may already be linked to someone else.");
        }
    }

    /**
     * Manual dev backfill for {@code clan_member.clan_joined_at} — see the column comment in
     * {@link com.younglings.bot.runescape.RuneScapeDatabaseInitializer} for why this isn't populated
     * automatically yet. Independent of verification: a member can be in the clan roster (and have a
     * join date worth recording) whether or not their Discord account is linked at all.
     */
    private void handleJoinDateModal(ModalInteractionEvent event, Guild guild, String rsn) {
        String raw = event.getValue("clan_joined_at").getAsString().trim();
        LocalDate date;
        try {
            date = LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            Containers.replyEphemeral(event, Containers.WARNING,
                    "'" + raw + "' isn't a valid date — use YYYY-MM-DD (e.g. 2026-03-14).");
            return;
        }

        boolean updated = clanSyncService.setClanJoinedAt(guild.getIdLong(), rsn, date);
        Containers.replyEphemeral(event, updated ? Containers.SUCCESS : Containers.WARNING,
                updated ? "Set **" + rsn + "**'s clan join date to " + date + "."
                        : "**" + rsn + "** isn't in the tracked clan roster — run **Sync Clan** first, or check the spelling.");
    }

    /** Links an RSN straight to a Discord user and applies the same role sync a real verification approval would — no makeover-mage dance needed. */
    private void handleManualVerifyModal(ModalInteractionEvent event, Guild guild, Member admin) {
        String rawUserId = event.getValue("manual_verify_discord_id").getAsString().trim().replaceAll("[<@!>]", "");
        String rsn = event.getValue("manual_verify_rsn").getAsString().trim();

        long discordUserId;
        try {
            discordUserId = Long.parseLong(rawUserId);
        } catch (NumberFormatException e) {
            Containers.replyEphemeral(event, Containers.WARNING,
                    "'" + rawUserId + "' doesn't look like a Discord user ID — right-click the member (Developer Mode must be on) and Copy User ID.");
            return;
        }

        linkService.manualLink(guild.getIdLong(), discordUserId, rsn, admin.getIdLong());
        roleSyncService.syncRoles(guild, discordUserId, rsn);
        Containers.replyEphemeral(event, Containers.SUCCESS, "Linked **" + rsn + "** to <@" + discordUserId + ">.");
    }

    private void handleButton(ButtonInteractionEvent event, Guild guild, String id) {
        String action = id.split(":")[0];

        switch (action) {
            case "rsnadmin_poll_all" -> doPollAllPrompt(event, guild);
            case "rsnadmin_guildchart" -> doGuildChartPrompt(event, guild);
            case "rsnadmin_syncclan" -> doSyncClanPrompt(event);
            case "rsnadmin_lookup" -> doPlayerLookupPrompt(event);
            case "rsnadmin_manualverify" -> doManualVerifyPrompt(event);
            case "rsnadmin_clanlist" -> doClanList(event, guild);
            case "rsnadmin_clanoverview" -> doClanOverviewPrompt(event, guild);
            case "rsnadmin_review_pending" -> showPendingVerifications(event, guild, 0, false);
            case "rsnadmin_citadel" -> Containers.replyEphemeral(event, Containers.INFO,
                    "🚧 Coming soon — a Citadel overview will go here.");

            case "rsnadmin_poll_all_confirm" -> doPollAll(event, guild);
            case "rsnadmin_guildchart_confirm" -> doGuildChart(event, guild);
            case "rsnadmin_syncclan_confirm" -> doSyncClan(event, guild);
            case "rsnadmin_clanoverview_confirm" -> doClanOverview(event, guild);
            case "rsnadmin_bulk_cancel" -> Containers.edit(event, Containers.INFO, "Cancelled — nothing was run.");

            case "rsnadmin_poll" -> doPollOne(event, guild, id.split(":", 2)[1]);
            case "rsnadmin_seed" -> doSeedTestData(event, guild, id.split(":", 2)[1]);
            case "rsnadmin_chart" -> doXpChart(event, guild, id.split(":", 2)[1]);
            case "rsnadmin_recap" -> doMonthlyRecap(event, guild, id.split(":", 2)[1]);
            case "rsnadmin_nearly" -> doNearlyThere(event, guild, id.split(":", 2)[1]);
            case "rsnadmin_unlink" -> doUnlinkPrompt(event, id.split(":", 2)[1]);
            case "rsnadmin_renamerequest" -> doRenamePrompt(event, id.split(":", 2)[1]);
            case "rsnadmin_joindate" -> doSetJoinDatePrompt(event, id.split(":", 2)[1]);

            case "rsnadmin_pending_page" -> showPendingVerifications(event, guild, Integer.parseInt(id.split(":")[1]), true);
            case "rsnadmin_review_approve" -> doReviewRowAction(event, guild, id.split(":", 2)[1], true);
            case "rsnadmin_review_reject" -> doReviewRowAction(event, guild, id.split(":", 2)[1], false);
            case "rsnadmin_review_approve_all" -> doApproveAllPrompt(event, guild);
            case "rsnadmin_review_approve_all_confirm" -> doApproveAllConfirmed(event, guild);

            case "rsnadmin_clanlist_page" -> {
                int page = Integer.parseInt(id.split(":")[1]);
                event.editComponents(List.of(buildClanListContainer(guild, page))).useComponentsV2(true)
                        .setAllowedMentions(EnumSet.noneOf(Message.MentionType.class)).queue();
            }

            case "rsnadmin_unlink_confirm" -> {
                String rsn = id.split(":", 2)[1];
                PlayerLink link = linkService.getLinkForRsn(guild.getIdLong(), rsn);
                boolean unlinked = link != null && linkService.unlink(guild.getIdLong(), link.discordUserId(), link.linkId());
                Containers.edit(event, unlinked ? Containers.SUCCESS : Containers.WARNING,
                        unlinked ? "Unlinked **" + rsn + "**." : "Couldn't unlink — that link may already be gone.");
            }

            case "rsnadmin_unlink_cancel" -> Containers.edit(event, Containers.INFO, "Cancelled — nothing was unlinked.");
        }
    }

    // --- Bulk actions ---

    /**
     * Every bulk/heavy action (anything that loops over more than one player) confirms first —
     * colored red since these are the actions actually worth being careful about on a limited
     * database plan, distinct from a single-target action's own confirm (e.g. Unlink, which is red
     * for irreversibility, not database load). {@code confirmId} is whatever this specific action's
     * own "go" button should be; Cancel is always the same shared button.
     */
    private void doBulkConfirmPrompt(ComponentInteraction event, String title, String message, String confirmId) {
        Container confirm = Containers.card(Containers.DANGER,
                TextDisplay.of("### " + title),
                TextDisplay.of(message + "\n\n-# This can take a while and will access the database multiple times — make sure you mean to run this now."),
                ActionRow.of(
                        Button.danger(confirmId, "Yes, Continue"),
                        Button.secondary("rsnadmin_bulk_cancel:_", "Cancel")));
        event.replyComponents(List.of(confirm)).useComponentsV2(true).setEphemeral(true).queue();
    }

    private void doPollAllPrompt(ComponentInteraction event, Guild guild) {
        int count = linkService.getAllLinks(guild.getIdLong()).size();
        doBulkConfirmPrompt(event, "Poll All Linked Players?",
                "This polls **every linked player's** RuneScape profile individually (" + count + " total) and writes " +
                "a database update for each one that's changed — up to " + count + " external requests and database writes.",
                "rsnadmin_poll_all_confirm:_");
    }

    private void doSyncClanPrompt(ComponentInteraction event) {
        doBulkConfirmPrompt(event, "Sync Clan Roster?",
                "This fetches the entire clan roster, then polls **every listed member** individually (with a short delay " +
                "between each) and writes updates to the database for each one. For a clan this size, this can take a " +
                "couple of minutes and will touch the database dozens of times.",
                "rsnadmin_syncclan_confirm:_");
    }

    private void doGuildChartPrompt(ComponentInteraction event, Guild guild) {
        int count = linkService.getAllLinks(guild.getIdLong()).size();
        doBulkConfirmPrompt(event, "Build the Clan XP Graph?",
                "This reads recent poll history from the database for **every linked player** (" + count + " total) to build the chart.",
                "rsnadmin_guildchart_confirm:_");
    }

    private void doClanOverviewPrompt(ComponentInteraction event, Guild guild) {
        int count = clanSyncService.getRoster(guild.getIdLong(), true).size();
        doBulkConfirmPrompt(event, "Build the Clan Overview?",
                "This reads snapshots, skills, and activity history from the database for **every active clan member** " +
                "(" + count + " total) to build the overview — potentially hundreds of database reads.",
                "rsnadmin_clanoverview_confirm:_");
    }

    private void doPollAll(ComponentInteraction event, Guild guild) {
        // Deferred edit, not a plain edit — polling every linked player is a series of HTTP calls
        // that will very likely take longer than Discord's 3-second ack window once there's more
        // than a couple of players.
        event.deferEdit().queue();
        List<PlayerLink> links = linkService.getAllLinks(guild.getIdLong());
        for (PlayerLink link : links) {
            statsService.pollAndSnapshot(guild.getIdLong(), link.rsn());
        }
        event.getHook().editOriginalComponents(List.of(buildPanel(guild))).useComponentsV2(true).queue();
    }

    private void doGuildChart(ComponentInteraction event, Guild guild) {
        // Deferred — one history query per linked player, plus chart rendering, adds up.
        event.deferReply(true).queue();

        List<PlayerLink> links = linkService.getAllLinks(guild.getIdLong());
        Map<String, List<PlayerLinkRepository.StatsSnapshotRow>> historyByRsn = new LinkedHashMap<>();
        OffsetDateTime since = OffsetDateTime.now().minusDays(GUILD_CHART_HISTORY_DAYS);
        for (PlayerLink link : links) {
            historyByRsn.put(link.rsn(), statsService.getSnapshotsSince(guild.getIdLong(), link.rsn(), since));
        }

        FileUpload chart = XpChartRenderer.renderMultiPlayer(historyByRsn);
        if (chart == null) {
            event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.WARNING,
                    "Not enough poll history yet — need at least 2 polls for at least one linked player " +
                    "in the last " + GUILD_CHART_HISTORY_DAYS + " days."))).useComponentsV2(true).queue();
            return;
        }

        Container container = Containers.card(Containers.PRIMARY,
                TextDisplay.of("### Clan XP Graph"),
                MediaGallery.of(MediaGalleryItem.fromFile(chart)));
        event.getHook().editOriginalComponents(List.of(container)).useComponentsV2(true).queue();
    }

    private void doSyncClan(ComponentInteraction event, Guild guild) {
        String clanName = clanSyncService.getClanName(guild.getIdLong());
        if (clanName == null) {
            Containers.replyEphemeral(event, Containers.WARNING,
                    "No clan name configured for this server yet — set one via **/configure** first.");
            return;
        }

        // Deferred — fetches the whole clan roster, then polls every member with a delay between
        // each (RUNESCAPE_POLL_DELAY_SECONDS), so this can genuinely take a couple of minutes for a
        // clan this size. That's expected, not a hang.
        event.deferReply(true).queue();
        var result = clanSyncService.syncAndPoll(guild);

        if (result.rosterSize() == 0) {
            event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.WARNING,
                    "Couldn't fetch the clan roster for **" + clanName + "** — check the clan name and try again."))).useComponentsV2(true).queue();
            return;
        }

        event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.SUCCESS,
                "Synced **" + clanName + "**: " + result.rosterSize() + " member(s) in the roster " +
                "(" + result.newMembers() + " new, " + result.departedMembers() + " no longer listed), " +
                result.polled() + "/" + result.rosterSize() + " polled successfully."))).useComponentsV2(true).queue();
    }

    private void doManualVerifyPrompt(ComponentInteraction event) {
        TextInput discordIdInput = TextInput.create("manual_verify_discord_id", TextInputStyle.SHORT)
                .setPlaceholder("Right-click the member > Copy User ID")
                .setRequired(true)
                .build();
        TextInput rsnInput = TextInput.create("manual_verify_rsn", TextInputStyle.SHORT)
                .setPlaceholder("Exact in-game display name")
                .setRequired(true)
                .setRequiredRange(1, 12)
                .build();

        Modal modal = Modal.create("rsnadmin_manualverify_modal:_", "Manually Verify a Player")
                .addComponents(Label.of("Discord User ID", discordIdInput), Label.of("RuneScape Name", rsnInput))
                .build();
        event.replyModal(modal).queue();
    }

    private void doClanList(ComponentInteraction event, Guild guild) {
        event.replyComponents(List.of(buildClanListContainer(guild, 0))).useComponentsV2(true).setEphemeral(true)
                .setAllowedMentions(EnumSet.noneOf(Message.MentionType.class)).queue();
    }

    private void doPlayerLookupPrompt(ComponentInteraction event) {
        TextInput rsnInput = TextInput.create("lookup_rsn", TextInputStyle.SHORT)
                .setPlaceholder("Exact in-game display name")
                .setRequired(true)
                .setRequiredRange(1, 12)
                .build();

        Modal modal = Modal.create("rsnadmin_lookup_modal:_", "Look Up a Player")
                .addComponents(Label.of("RuneScape Name", rsnInput))
                .build();
        event.replyModal(modal).queue();
    }

    private void doClanOverview(ComponentInteraction event, Guild guild) {
        // Deferred — this walks every active clan member's snapshots, skills, and activities (all
        // local DB reads, but a few hundred of them for a clan this size), plus rendering a
        // leaderboard image. Comfortably more than 3 seconds worth of work.
        event.deferReply(true).queue();

        var stats = clanOverviewService.getOverview(guild.getIdLong());
        if (stats == null) {
            event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.WARNING,
                    "Nothing tracked yet — run **Sync Clan** first."))).useComponentsV2(true).queue();
            return;
        }

        String clanName = clanSyncService.getClanName(guild.getIdLong());
        FileUpload image = ClanOverviewRenderer.render(clanName != null ? clanName : "Clan", stats, fetchGuildIcon(guild));
        if (image == null) {
            event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.WARNING,
                    "Couldn't render the clan overview image right now."))).useComponentsV2(true).queue();
            return;
        }

        Container container = Containers.card(Containers.PRIMARY,
                TextDisplay.of("### Clan Overview"),
                MediaGallery.of(MediaGalleryItem.fromFile(image)));
        event.getHook().editOriginalComponents(List.of(container)).useComponentsV2(true).queue();
    }

    // --- Pending verifications (moved here from /rs — this is the full, server-wide queue) ---

    private static final int PENDING_REVIEW_PAGE_SIZE = 5;

    /**
     * The full queue, with Approve/Reject right on each row — no need to go find that player's
     * original post in the review channel just to act on it. Re-rendered in place (same message)
     * after any row action or page nav, so working through a backlog doesn't lose your spot; a fresh
     * "Review Pending" click posts a new one instead (see {@code isPageNav}).
     */
    private void showPendingVerifications(ComponentInteraction event, Guild guild, int pageIndex, boolean isPageNav) {
        List<VerificationAttempt> pending = linkService.getPendingAttempts(guild.getIdLong());
        if (pending.isEmpty()) {
            if (isPageNav) {
                Containers.edit(event, Containers.INFO, "No pending verification requests remaining.");
            } else {
                Containers.replyEphemeral(event, Containers.INFO, "No pending verification requests.");
            }
            return;
        }

        var page = Pagination.paginate(pending, pageIndex, PENDING_REVIEW_PAGE_SIZE);

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("# Pending Verifications (" + pending.size() + ")"));
        children.add(ActionRow.of(Button.success("rsnadmin_review_approve_all:_", "Approve All")));
        children.add(Separator.createDivider(Separator.Spacing.SMALL));

        for (VerificationAttempt attempt : page.items()) {
            children.add(TextDisplay.of("`#" + attempt.attemptId() + "` — **" + attempt.rsn() + "** — <@" + attempt.discordUserId() + ">"));
            children.add(ActionRow.of(
                    Button.success("rsnadmin_review_approve:" + attempt.attemptId(), "Approve"),
                    Button.danger("rsnadmin_review_reject:" + attempt.attemptId(), "Reject")));
        }

        if (!page.isSinglePage()) {
            children.add(Separator.createDivider(Separator.Spacing.SMALL));
            children.add(Pagination.navRow(page, "rsnadmin_pending_page:"));
        }

        Container container = Containers.card(Containers.INFO, children);

        if (isPageNav) {
            event.editComponents(List.of(container)).useComponentsV2(true)
                    .setAllowedMentions(EnumSet.noneOf(Message.MentionType.class)).queue();
        } else {
            event.replyComponents(List.of(container)).useComponentsV2(true).setEphemeral(true)
                    .setAllowedMentions(EnumSet.noneOf(Message.MentionType.class)).queue();
        }
    }

    /** One row's Approve/Reject from the Review Pending list — shares the exact side effects (link, role sync, DM) that a standalone review-message click gets, then refreshes the list in place. */
    private void doReviewRowAction(ComponentInteraction event, Guild guild, String attemptIdRaw, boolean approve) {
        long attemptId = Long.parseLong(attemptIdRaw);
        VerificationAttempt result = approve
                ? rsInteractionListener.completeApproval(guild, attemptId, event.getUser().getIdLong())
                : rsInteractionListener.completeRejection(guild, attemptId, event.getUser().getIdLong());

        if (result == null) {
            Containers.replyEphemeral(event, Containers.WARNING, "That request was already resolved.");
            return;
        }
        showPendingVerifications(event, guild, 0, true);
    }

    private void doApproveAllPrompt(ComponentInteraction event, Guild guild) {
        int count = linkService.getPendingAttempts(guild.getIdLong()).size();
        if (count == 0) {
            Containers.replyEphemeral(event, Containers.INFO, "No pending verification requests.");
            return;
        }

        doBulkConfirmPrompt(event, "Approve All " + count + " Pending Request(s)?",
                "This links every pending RSN to its requester and DMs each of them. " +
                "It can't be undone in bulk — you'd need to unlink each one individually afterward.",
                "rsnadmin_review_approve_all_confirm:_");
    }

    private void doApproveAllConfirmed(ComponentInteraction event, Guild guild) {
        List<VerificationAttempt> pending = linkService.getPendingAttempts(guild.getIdLong());
        if (pending.isEmpty()) {
            Containers.edit(event, Containers.INFO, "No pending verification requests remaining.");
            return;
        }

        // Deferred — approving N people means N role syncs and N DM sends, comfortably longer than
        // Discord's 3-second ack window once the queue has more than a couple of entries.
        event.deferEdit().queue();
        int approved = 0;
        for (VerificationAttempt attempt : pending) {
            if (rsInteractionListener.completeApproval(guild, attempt.attemptId(), event.getUser().getIdLong()) != null) approved++;
        }
        event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.SUCCESS,
                "Approved " + approved + " of " + pending.size() + " pending request(s)."))).useComponentsV2(true).queue();
    }

    // --- Per-player actions ---

    /** Reached from a Player Lookup card's "Poll Again" — re-renders that same card, not the admin panel (which no longer shows any one player inline anyway). */
    private void doPollOne(ComponentInteraction event, Guild guild, String rsn) {
        event.deferEdit().queue();
        ProfileResult result = statsService.pollAndSnapshotResult(guild.getIdLong(), rsn);
        event.getHook().editOriginalComponents(List.of(buildLookupCard(guild, rsn, result)))
                .useComponentsV2(true).setAllowedMentions(EnumSet.noneOf(Message.MentionType.class)).queue();
    }

    private void doSeedTestData(ComponentInteraction event, Guild guild, String rsn) {
        // Deferred, not a plain reply — 30 backdated snapshots means ~60 round trips to the
        // database, comfortably longer than Discord's 3-second ack window.
        event.deferReply(true).queue();
        int created = testDataSeeder.seed(guild.getIdLong(), rsn);

        if (created == 0) {
            event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.WARNING,
                    "Can't seed test data for **" + rsn + "** — it needs at least one real poll first (Poll Now)."))).useComponentsV2(true).queue();
            return;
        }
        event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.SUCCESS,
                "Seeded " + created + " days of fake history for **" + rsn + "** — try the XP Chart now. " +
                "Clicking this again will stack more fake history on top, so only use it once."))).useComponentsV2(true).queue();
    }

    private void doXpChart(ComponentInteraction event, Guild guild, String rsn) {
        if (statsService.getLatestSnapshot(guild.getIdLong(), rsn) == null) {
            Containers.replyEphemeral(event, Containers.WARNING,
                    "No synced data for **" + rsn + "** yet — use **Poll Now** first.");
            return;
        }
        event.replyComponents(List.of(chartListener.buildChartContainer(guild, rsn, Set.of(), RsChartInteractionListener.ChartStyle.LINE)))
                .useComponentsV2(true).setEphemeral(true).queue();
    }

    private void doMonthlyRecap(ComponentInteraction event, Guild guild, String rsn) {
        // Deferred — building the donut chart plus a network fetch for the guild icon is
        // comfortably more than the 3-second ack window allows for.
        event.deferReply(true).queue();

        MonthlyRecapStats stats = monthlyRecapService.getStats(guild.getIdLong(), rsn);
        if (stats == null) {
            event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.WARNING,
                    "No snapshots for **" + rsn + "** yet this month — poll (or seed test data) first."))).useComponentsV2(true).queue();
            return;
        }

        FileUpload image = MonthlyRecapRenderer.render(stats, fetchGuildIcon(guild));
        if (image == null) {
            event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.WARNING,
                    "Couldn't render the recap image right now."))).useComponentsV2(true).queue();
            return;
        }

        Container container = Containers.card(Containers.PRIMARY,
                TextDisplay.of("### " + rsn + " — Monthly Recap"),
                MediaGallery.of(MediaGalleryItem.fromFile(image)));
        event.getHook().editOriginalComponents(List.of(container)).useComponentsV2(true).queue();
    }

    private void doNearlyThere(ComponentInteraction event, Guild guild, String rsn) {
        PlayerLinkRepository.StatsSnapshotRow latest = statsService.getLatestSnapshot(guild.getIdLong(), rsn);
        if (latest == null) {
            Containers.replyEphemeral(event, Containers.WARNING,
                    "No synced data for **" + rsn + "** yet — use **Poll Now** first.");
            return;
        }
        List<SkillValue> skills = statsService.getSkillsForSnapshot(latest.snapshotId());
        event.replyComponents(List.of(buildNearlyThereContainer(rsn, skills, latest))).useComponentsV2(true).setEphemeral(true).queue();
    }

    private void doRenamePrompt(ComponentInteraction event, String rsn) {
        TextInput newRsnInput = TextInput.create("new_rsn", TextInputStyle.SHORT)
                .setPlaceholder("Their new in-game display name")
                .setRequired(true)
                .setRequiredRange(1, 12)
                .build();

        Modal modal = Modal.create("rsnadmin_renamemodal:" + rsn, "Update RSN for " + rsn)
                .addComponents(Label.of("New RuneScape Name", newRsnInput))
                .build();
        event.replyModal(modal).queue();
    }

    private void doSetJoinDatePrompt(ComponentInteraction event, String rsn) {
        TextInput dateInput = TextInput.create("clan_joined_at", TextInputStyle.SHORT)
                .setPlaceholder("YYYY-MM-DD")
                .setRequired(true)
                .setRequiredRange(10, 10)
                .build();

        Modal modal = Modal.create("rsnadmin_joindatemodal:" + rsn, "Set Clan Join Date for " + rsn)
                .addComponents(Label.of("Join Date", dateInput))
                .build();
        event.replyModal(modal).queue();
    }

    private void doUnlinkPrompt(ComponentInteraction event, String rsn) {
        Container confirm = Containers.card(Containers.WARNING,
                TextDisplay.of("### Unlink " + rsn + "?"),
                TextDisplay.of("This removes the link between the linked Discord account and **" + rsn + "**. Historical poll data is kept."),
                ActionRow.of(
                        Button.danger("rsnadmin_unlink_confirm:" + rsn, "Yes, Unlink"),
                        Button.secondary("rsnadmin_unlink_cancel:_", "Cancel")
                ));
        event.replyComponents(List.of(confirm)).useComponentsV2(true).setEphemeral(true).queue();
    }

    private static final int NEARLY_THERE_COUNT = 10;
    // Wide enough for "Dungeoneering" (13 chars), same reasoning as RsInteractionListener's Skills view.
    private static final int NEARLY_THERE_NAME_WIDTH = 13;

    /**
     * The 10 skills closest to their next level, by XP still needed — {@link RuneScapeXpTable} is
     * the game's own level curve, not an approximation. Kept as admin-only tooling; no longer wired
     * to a member-facing button (see {@code RsInteractionListener}'s profile panel), per request —
     * the logic stays intact here in case it comes back.
     */
    private Container buildNearlyThereContainer(String rsn, List<SkillValue> skills, PlayerLinkRepository.StatsSnapshotRow latest) {
        record Gap(SkillValue skill, long xpNeeded) {}

        List<Gap> gaps = skills.stream()
                .filter(skill -> skill.level() < 120)
                .map(skill -> new Gap(skill, RuneScapeXpTable.xpToNextLevel(skill.skillId(), skill.level(), skill.xp())))
                .sorted(Comparator.comparingLong(Gap::xpNeeded))
                .limit(NEARLY_THERE_COUNT)
                .toList();

        if (gaps.isEmpty()) {
            return Containers.card(Containers.SUCCESS,
                    TextDisplay.of("### " + rsn + " — Nearly There"),
                    TextDisplay.of("Every skill is already level 120!"));
        }

        StringBuilder body = new StringBuilder();
        for (Gap gap : gaps) {
            String row = String.format("%-" + NEARLY_THERE_NAME_WIDTH + "s  Lv %-3d -> %-3d  %11s xp",
                    RuneScapeSkillCatalog.nameFor(gap.skill().skillId()), gap.skill().level(), gap.skill().level() + 1,
                    String.format("%,d", gap.xpNeeded()));
            body.append("`").append(row).append("`\n");
        }

        return Containers.card(Containers.PRIMARY,
                TextDisplay.of("### " + rsn + " — Nearly There"),
                TextDisplay.of(body.toString().stripTrailing()),
                TextDisplay.of("-# As of <t:" + latest.snapshotAt().toEpochSecond() + ":R> — closest " + gaps.size() + " skill(s) to leveling up"));
    }

    private static final int CLAN_LIST_PAGE_SIZE = 15;

    /**
     * Every currently-active clan_member row (i.e. still seen in the last Sync Clan), cross-
     * referenced against player_link so it's obvious at a glance who still needs verifying.
     * Mentions use {@code setAllowedMentions} to suppress the ping.
     */
    private Container buildClanListContainer(Guild guild, int pageIndex) {
        List<ClanMemberRepository.ClanMemberRow> members = clanSyncService.getRoster(guild.getIdLong(), true);
        if (members.isEmpty()) {
            return Containers.card(Containers.PRIMARY,
                    TextDisplay.of("### Clan Member List"),
                    TextDisplay.of("*Nothing tracked yet — run **Sync Clan** first.*"));
        }

        var page = Pagination.paginate(members, pageIndex, CLAN_LIST_PAGE_SIZE);

        StringBuilder sb = new StringBuilder();
        for (ClanMemberRepository.ClanMemberRow row : page.items()) {
            PlayerLink link = linkService.getLinkForRsn(guild.getIdLong(), row.rsn());
            sb.append("**").append(row.rsn()).append("** — ").append(row.clanRank());
            sb.append(link != null ? " — ✅ <@" + link.discordUserId() + ">" : " — *unverified*");
            sb.append("\n");
        }

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("### Clan Member List (" + members.size() + " active)"));
        children.add(TextDisplay.of(sb.toString().stripTrailing()));
        if (!page.isSinglePage()) children.add(Pagination.navRow(page, "rsnadmin_clanlist_page:"));

        return Containers.card(Containers.PRIMARY, children);
    }

    /**
     * The "look up anyone" info card: clan-roster membership and verification status always shown,
     * then the live fetch outcome — full stats if it succeeded, or an explicit reason if it didn't
     * (see {@link ProfileResult}). Mentions use {@code setAllowedMentions} for the same reason as
     * {@link #buildClanListContainer}.
     */
    private Container buildLookupCard(Guild guild, String rsn, ProfileResult result) {
        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("### " + rsn + " — Player Lookup"));

        ClanMemberRepository.ClanMemberRow clanRow = clanSyncService.getRoster(guild.getIdLong(), true).stream()
                .filter(member -> member.rsn().equalsIgnoreCase(rsn))
                .findFirst().orElse(null);
        boolean inClan = clanRow != null;
        PlayerLink link = linkService.getLinkForRsn(guild.getIdLong(), rsn);

        StringBuilder meta = new StringBuilder()
                .append(inClan ? "✅ In the clan roster" : "— Not currently in the clan roster")
                .append(link != null ? "\n✅ Verified to <@" + link.discordUserId() + ">" : "\n— Not verified to any Discord account");
        if (inClan) {
            meta.append("\n📅 Clan joined: ").append(clanRow.clanJoinedAt() != null ? clanRow.clanJoinedAt().toString() : "*not recorded yet*");
        }
        children.add(TextDisplay.of(meta.toString()));

        switch (result) {
            case ProfileResult.Found(var profile) -> {
                children.add(TextDisplay.of("**Total Level:** " + profile.totalLevel() + "\n" +
                        "**Combat Level:** " + profile.combatLevel() + "\n" +
                        "**Quests Complete:** " + profile.questsComplete() + "\n" +
                        "**Total XP:** `" + String.format("%,d", profile.totalXp()) + " xp`"));
                children.add(link != null
                        ? ActionRow.of(
                                Button.primary("rsnadmin_poll:" + rsn, "Poll Again"),
                                Button.secondary("rs_skills:" + rsn, "Full Skills"),
                                Button.secondary("rsnadmin_chart:" + rsn, "XP Chart"),
                                Button.secondary("rsnadmin_renamerequest:" + rsn, "Update RSN"),
                                Button.danger("rsnadmin_unlink:" + rsn, "Unlink"))
                        : ActionRow.of(
                                Button.primary("rsnadmin_poll:" + rsn, "Poll Again"),
                                Button.secondary("rs_skills:" + rsn, "Full Skills"),
                                Button.secondary("rsnadmin_chart:" + rsn, "XP Chart"),
                                Button.secondary("rsnadmin_manualverify:_", "Verify This Player")));
                // XP Chart and Monthly Recap are still dev-in-progress (see RsInteractionListener) —
                // Player Lookup is where they stay testable while they're not on the live /rs profile.
                children.add(ActionRow.of(Button.secondary("rsnadmin_recap:" + rsn, "Monthly Recap")));
            }
            case ProfileResult.Private ignored -> children.add(TextDisplay.of(
                    "🔒 This player's **Adventurer's Log is set to private** — RuneScape won't return stats until they make it public in-game (Settings → Privacy)."));
            case ProfileResult.NotFound ignored -> children.add(TextDisplay.of(
                    "❓ No RuneMetrics profile found for **" + rsn + "** — check the spelling, or they may have never opened their Adventurer's Log."));
            case ProfileResult.Unavailable ignored -> children.add(TextDisplay.of(
                    "⚠️ Couldn't fetch stats right now — the RuneScape API may be temporarily unavailable. Try again shortly."));
        }

        // Always shown, independent of inClan/the live-poll outcome above — the modal handler
        // itself already rejects cleanly if rsn turns out not to be a tracked clan member (see
        // handleJoinDateModal), which is friendlier than hiding the button on a possibly-stale
        // roster read and leaving an admin wondering where it went.
        children.add(ActionRow.of(Button.secondary("rsnadmin_joindate:" + rsn, "Set Join Date")));

        return Containers.card(Containers.PRIMARY, children);
    }

    /** The server's own icon, used as the recap image's "clan logo" — {@code null} if the guild has no icon set, or the fetch fails. */
    private byte[] fetchGuildIcon(Guild guild) {
        String iconUrl = guild.getIconUrl();
        if (iconUrl == null) return null;

        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(iconUrl + "?size=256")).GET().build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return response.statusCode() / 100 == 2 ? response.body() : null;
        } catch (Exception e) {
            log.warn("Failed to fetch guild icon for monthly recap", e);
            return null;
        }
    }

    private static final int GUILD_CHART_HISTORY_DAYS = 30;

    /**
     * {@link RsAdminCommand}'s initial reply and this listener's own re-renders (Poll All) — bulk
     * actions only now, no self section (see {@code /rs}). Grouped into labeled sections rather than
     * one undifferentiated button wall, since this panel is meant to grow well beyond RS3 tracking
     * over time (Coffer, Events, whatever comes next each get their own section here rather than
     * their own top-level admin command).
     */
    Container buildPanel(Guild guild) {
        String clanName = clanSyncService.getClanName(guild.getIdLong());

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("# RS3 Admin Panel" + (clanName != null ? " - " + clanName : "")));
        children.add(TextDisplay.of("-# Administrator only — automatic polling is disabled, everything here is manual"));
        children.add(Separator.createDivider(Separator.Spacing.SMALL));

        children.add(TextDisplay.of("### Clan Management"));
        children.add(ActionRow.of(
                Button.secondary("rsnadmin_syncclan:_", "Sync Clan"),
                Button.secondary("rsnadmin_guildchart:_", "Clan XP Graph"),
                Button.secondary("rsnadmin_clanlist:_", "Clan Member List"),
                Button.secondary("rsnadmin_clanoverview:_", "Clan Overview"),
                Button.secondary("rsnadmin_citadel:_", "Citadel Viewer")
        ));
        children.add(Separator.createDivider(Separator.Spacing.SMALL));

        children.add(TextDisplay.of("### Player Management"));
        children.add(ActionRow.of(
                Button.primary("rsnadmin_poll_all:_", "Poll All"),
                Button.secondary("rsnadmin_lookup:_", "Player Lookup"),
                Button.secondary("rsnadmin_manualverify:_", "Manually Verify"),
                Button.secondary("rsnadmin_review_pending:_", "Review Pending")
        ));
        children.add(Separator.createDivider(Separator.Spacing.SMALL));
        children.add(TextDisplay.of("-# Manage your own linked account(s) via `/rs`."));

        return Containers.card(Containers.PRIMARY, children);
    }
}
