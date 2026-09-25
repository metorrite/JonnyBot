package com.younglings.bot.commands.runescape;

import com.younglings.bot.configure.GuildSettingsService;
import com.younglings.bot.discord.Containers;
import com.younglings.bot.discord.Pagination;
import com.younglings.bot.permission.AdminRoleFilter;
import com.younglings.bot.runescape.ClanOverviewRenderer;
import com.younglings.bot.runescape.ClanOverviewService;
import com.younglings.bot.runescape.ClanSyncService;
import com.younglings.bot.runescape.MonthlyRecapRenderer;
import com.younglings.bot.runescape.MonthlyRecapService;
import com.younglings.bot.runescape.MonthlyRecapStats;
import com.younglings.bot.runescape.PlayerActivity;
import com.younglings.bot.runescape.PlayerLink;
import com.younglings.bot.runescape.PlayerLinkRepository;
import com.younglings.bot.runescape.PlayerLinkService;
import com.younglings.bot.runescape.RuneScapeSkillCatalog;
import com.younglings.bot.runescape.RuneScapeStatsService;
import com.younglings.bot.runescape.SkillEmojiCatalog;
import com.younglings.bot.runescape.SkillValue;
import com.younglings.bot.runescape.VerificationAttempt;
import com.younglings.bot.runescape.VerificationRoleSyncService;
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
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.callbacks.IMessageEditCallback;
import net.dv8tion.jda.api.interactions.components.ComponentInteraction;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.utils.FileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Buttons/modal for {@link RsCommand}: verification (submit, admin approve/reject on the message
 * posted for review), a self-service "what's my status" view while pending, and the linked-account
 * profile panel(s) once verified — the single place that renders a member's own RS3 data now (see
 * {@link RsAdminInteractionListener}, which used to duplicate a slice of this for the calling admin
 * and no longer does).
 * <p>
 * Verification is a plain admin call for now — submit an RSN, an admin Approves or Rejects it, done.
 * The makeover-mage appearance-comparison flow ({@link PlayerLinkService#startVerification} still
 * assigns one under the hood) is temporarily disabled rather than removed: {@code RuneScapeApiClient
 * #fetchAvatarImage} is unused but intact, so restoring the photo-comparison step later is a UI
 * change here, not a rebuild.
 * <p>
 * Linking, verification status, and profile management are scoped to the caller's own account(s)
 * only, by design — every handler here checks {@code link.discordUserId() == event.getUser().getIdLong()}
 * before acting. Nothing about the rendering itself assumes that, though (every "profile" method
 * takes a resolved {@link PlayerLink}, not "the caller"), so viewing someone else's profile later is
 * only ever an authorization change at the call site, not a rewrite.
 */
@BService
public class RsInteractionListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(RsInteractionListener.class);
    private static final Color RS3_ORANGE = Color.ORANGE;

    private final PlayerLinkService linkService;
    private final RuneScapeStatsService statsService;
    private final AdminRoleFilter adminRoleFilter;
    private final SkillEmojiCatalog skillEmojiCatalog;
    private final VerificationRoleSyncService roleSyncService;
    private final RsChartInteractionListener chartListener;
    private final MonthlyRecapService monthlyRecapService;
    private final ClanSyncService clanSyncService;
    private final ClanOverviewService clanOverviewService;
    private final GuildSettingsService guildSettingsService;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    // "Share" toggle per member — in-memory only (resets on restart, and every fresh /rs invocation
    // always starts off; see buildClanHeader). Not worth a DB column for a runtime preference this
    // small. Only affects specific action outputs (Full Skills, Full Activity) — never the panel
    // itself, which stays ephemeral no matter what; see doToggleShare.
    private final Map<Long, Boolean> shareEnabled = new ConcurrentHashMap<>();

    public RsInteractionListener(PlayerLinkService linkService,
                                  RuneScapeStatsService statsService, AdminRoleFilter adminRoleFilter,
                                  SkillEmojiCatalog skillEmojiCatalog, VerificationRoleSyncService roleSyncService,
                                  RsChartInteractionListener chartListener, MonthlyRecapService monthlyRecapService,
                                  ClanSyncService clanSyncService, ClanOverviewService clanOverviewService,
                                  GuildSettingsService guildSettingsService) {
        this.linkService = linkService;
        this.statsService = statsService;
        this.adminRoleFilter = adminRoleFilter;
        this.skillEmojiCatalog = skillEmojiCatalog;
        this.roleSyncService = roleSyncService;
        this.chartListener = chartListener;
        this.monthlyRecapService = monthlyRecapService;
        this.clanSyncService = clanSyncService;
        this.clanOverviewService = clanOverviewService;
        this.guildSettingsService = guildSettingsService;
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        if (event.getGuild() == null) return;
        String id = event.getComponentId();
        if (!id.startsWith("rs_")) return;

        try {
            handleButton(event, id);
        } catch (Exception e) {
            log.error("Unhandled exception in rs button interaction '{}'", id, e);
            Containers.replyError(event);
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        if (event.getGuild() == null) return;
        String id = event.getModalId();
        if (!id.startsWith("rs_")) return;

        try {
            handleModal(event, id);
        } catch (Exception e) {
            log.error("Unhandled exception in rs modal interaction '{}'", id, e);
            Containers.replyError(event);
        }
    }

    private void handleButton(ButtonInteractionEvent event, String id) {
        String action = id.split(":")[0];
        Guild guild = event.getGuild();

        switch (action) {
            case "rs_link", "rs_link_another" -> event.replyModal(buildLinkModal()).queue();

            case "rs_refresh_pending" -> {
                VerificationAttempt pending = linkService.getPendingAttemptForUser(guild.getIdLong(), event.getUser().getIdLong());
                if (pending == null) {
                    // Resolved since this panel was shown — most likely just approved.
                    List<PlayerLink> links = linkService.getLinksForUser(guild.getIdLong(), event.getUser().getIdLong());
                    if (!links.isEmpty()) {
                        event.editComponents(List.of(buildAccountPanel(guild, event.getUser().getIdLong()))).useComponentsV2(true).queue();
                    } else {
                        Containers.edit(event, Containers.WARNING, "That request is no longer pending, and no link was created — it may have been rejected. Run `/rs` again to start over.");
                    }
                    return;
                }
                event.editComponents(List.of(buildPendingStatusPanel(pending))).useComponentsV2(true).queue();
            }

            case "rs_profile" -> {
                String rsn = id.split(":", 2)[1];
                PlayerLink link = ownLinkOrNull(guild, event.getUser().getIdLong(), rsn);
                if (link == null) {
                    Containers.replyEphemeral(event, Containers.WARNING, "That's not one of your linked accounts.");
                    return;
                }
                event.editComponents(List.of(buildProfilePanel(guild, link))).useComponentsV2(true).queue();
            }

            case "rs_back" -> event.editComponents(List.of(buildAccountPanel(guild, event.getUser().getIdLong())))
                    .useComponentsV2(true).queue();

            case "rs_poll" -> doSelfPoll(event, guild, id.split(":", 2)[1]);

            case "rs_skills" -> showSkills(event, guild, id.split(":", 2)[1]);
            case "rs_activity" -> showActivity(event, guild, id.split(":", 2)[1]);

            case "rs_activity_page" -> {
                String[] parts = id.split(":", 2)[1].split("\\|");
                String rsn = parts[0];
                if (ownLinkOrNull(guild, event.getUser().getIdLong(), rsn) == null) {
                    Containers.replyEphemeral(event, Containers.WARNING, "That's not one of your linked accounts.");
                    return;
                }
                editActivityPage(event, guild, rsn, Integer.parseInt(parts[1]));
            }

            case "rs_activity_jump" -> {
                String rsn = id.split(":", 2)[1];
                if (ownLinkOrNull(guild, event.getUser().getIdLong(), rsn) == null) {
                    Containers.replyEphemeral(event, Containers.WARNING, "That's not one of your linked accounts.");
                    return;
                }
                TextInput pageInput = TextInput.create("page_number", TextInputStyle.SHORT)
                        .setPlaceholder("Page number (1-" + ACTIVITY_MAX_PAGES + ")")
                        .setRequired(true)
                        .setRequiredRange(1, 1)
                        .build();
                Modal modal = Modal.create("rs_activity_jump_modal:" + rsn, "Go to Page")
                        .addComponents(Label.of("Page Number", pageInput))
                        .build();
                event.replyModal(modal).queue();
            }

            // XP Chart and Monthly Recap are dev-in-progress features — not linked from the live
            // profile right now (see buildProfileButtons), but kept reachable by ID in case a
            // button gets wired back up here while iterating. For now, keep testing them via
            // /rsadmin's Player Lookup instead (see RsAdminInteractionListener).
            case "rs_chart" -> {
                String rsn = id.split(":", 2)[1];
                if (ownLinkOrNull(guild, event.getUser().getIdLong(), rsn) == null) {
                    Containers.replyEphemeral(event, Containers.WARNING, "That's not one of your linked accounts.");
                    return;
                }
                event.replyComponents(List.of(chartListener.buildChartContainer(guild, rsn, Set.of(), RsChartInteractionListener.ChartStyle.LINE)))
                        .useComponentsV2(true).setEphemeral(true).queue();
            }

            case "rs_recap" -> doMonthlyRecap(event, guild, id.split(":", 2)[1]);

            case "rs_clan_join" -> Containers.replyEphemeral(event, Containers.INFO,
                    "🚧 Coming soon — how to join **" + clanNameOrFallback(guild) + "** in-game will go here.");

            case "rs_clan_info" -> Containers.replyEphemeral(event, Containers.INFO,
                    "🚧 Coming soon — clan information will go here.");

            case "rs_clan_stats" -> doClanStats(event, guild);

            case "rs_clan_leaderboard" -> doClanLeaderboard(event, guild);

            case "rs_share" -> doToggleShare(event, guild);

            case "rs_unlink" -> {
                String rsn = id.split(":", 2)[1];
                if (ownLinkOrNull(guild, event.getUser().getIdLong(), rsn) == null) {
                    Containers.replyEphemeral(event, Containers.WARNING, "That's not one of your linked accounts.");
                    return;
                }
                Container confirm = Containers.card(Containers.WARNING,
                        TextDisplay.of("### Unlink " + rsn + "?"),
                        TextDisplay.of("This removes the link between your Discord account and **" + rsn + "**. Historical stats are kept."),
                        ActionRow.of(
                                Button.danger("rs_unlink_confirm:" + rsn, "Yes, Unlink"),
                                Button.secondary("rs_unlink_cancel:_", "Cancel")));
                event.replyComponents(List.of(confirm)).useComponentsV2(true).setEphemeral(true).queue();
            }

            case "rs_unlink_confirm" -> {
                String rsn = id.split(":", 2)[1];
                PlayerLink link = ownLinkOrNull(guild, event.getUser().getIdLong(), rsn);
                boolean unlinked = link != null && linkService.unlink(guild.getIdLong(), link.discordUserId(), link.linkId());
                Containers.edit(event, unlinked ? Containers.SUCCESS : Containers.WARNING,
                        unlinked ? "Unlinked **" + rsn + "**. Run `/rs` again any time to relink." : "Couldn't unlink — that link may already be gone.");
            }

            case "rs_unlink_cancel" -> Containers.edit(event, Containers.INFO, "Cancelled — nothing was unlinked.");

            case "rs_verify_approve" -> handleVerifyApprove(event, id);
            case "rs_verify_reject" -> handleVerifyReject(event, id);

            case "rs_cancel_own" -> {
                long attemptId = Long.parseLong(id.split(":")[1]);
                boolean cancelled = linkService.cancelOwn(attemptId, event.getUser().getIdLong());

                if (!cancelled) {
                    Containers.replyEphemeral(event, Containers.WARNING, "That request is no longer active.");
                    return;
                }
                Containers.replyEphemeral(event, Containers.SUCCESS, "Cancelled — run `/rs` again to start over with a different name.");
            }
        }
    }

    /** {@code null} if {@code rsn} isn't linked, or is linked to someone other than {@code discordUserId} — the shared ownership gate every self-service action needs. */
    private PlayerLink ownLinkOrNull(Guild guild, long discordUserId, String rsn) {
        PlayerLink link = linkService.getLinkForRsn(guild.getIdLong(), rsn);
        return (link != null && link.discordUserId() == discordUserId) ? link : null;
    }

    /**
     * Posts a freshly-submitted request to this guild's configured verification channel (see
     * {@code /configure}'s Verification section) with Approve/Reject buttons — the only step in the
     * flow now; there's no more "I've applied my look" wait between submitting and this. Mentions are
     * suppressed since this is a for-admins channel, not a ping to the requester.
     */
    private void postForAdminReview(Guild guild, VerificationAttempt attempt) {
        TextChannel channel = resolveVerificationChannel(guild);
        if (channel == null) {
            log.warn("No verification review channel configured for guild {} — request {} for '{}' has nobody to notify.",
                    guild.getIdLong(), attempt.attemptId(), attempt.rsn());
            return;
        }

        Container review = Containers.card(Containers.PRIMARY,
                TextDisplay.of("### RSN Verification Request"),
                TextDisplay.of("<@" + attempt.discordUserId() + "> would like to link **" + attempt.rsn() + "**."),
                ActionRow.of(
                        Button.success("rs_verify_approve:" + attempt.attemptId(), "Approve"),
                        Button.danger("rs_verify_reject:" + attempt.attemptId(), "Reject")
                ));

        channel.sendMessageComponents(List.of(review)).useComponentsV2(true)
                .setAllowedMentions(EnumSet.noneOf(Message.MentionType.class))
                .queue(success -> {}, error -> log.warn("Failed to post verification request {} for review", attempt.attemptId(), error));
    }

    private TextChannel resolveVerificationChannel(Guild guild) {
        Long channelId = guildSettingsService.getEffective(guild.getIdLong()).verificationReviewChannelId();
        if (channelId == null) return null;
        return guild.getTextChannelById(channelId);
    }

    /** Fire-and-forget DM about a verification request's submission/outcome — mirrors {@code RsnRenameService#dmPlayer}; a closed-DM failure is only ever logged, never surfaced to whoever triggered the action. */
    private void dmVerificationUpdate(Guild guild, long discordUserId, Color color, String message) {
        guild.retrieveMemberById(discordUserId).queue(member -> {
            Container container = Containers.card(color, TextDisplay.of(message));
            member.getUser().openPrivateChannel().queue(
                    dm -> dm.sendMessageComponents(List.of(container)).useComponentsV2(true)
                            .queue(success -> {}, error -> log.info("Couldn't DM user {} about their verification request (DMs likely closed)", discordUserId)),
                    error -> log.info("Couldn't open a DM with user {} about their verification request", discordUserId));
        }, error -> log.warn("Failed to retrieve member {} for a verification DM", discordUserId, error));
    }

    /**
     * Approves one pending attempt — creates the link, syncs roles, DMs the requester — the exact
     * bundle every approval path needs, whether it's a single click on the posted review message, an
     * individual row in {@link RsAdminInteractionListener}'s Review Pending list, or that panel's
     * "Approve All". Returns the resolved attempt, or {@code null} if it was already resolved or gone.
     */
    VerificationAttempt completeApproval(Guild guild, long attemptId, long resolvedByUserId) {
        VerificationAttempt attempt = linkService.getAttempt(attemptId);
        if (!linkService.approve(attemptId, resolvedByUserId)) return null;

        roleSyncService.syncRoles(guild, attempt.discordUserId(), attempt.rsn());
        dmVerificationUpdate(guild, attempt.discordUserId(), Containers.SUCCESS,
                "✅ Your request to link **" + attempt.rsn() + "** has been approved!");
        return attempt;
    }

    /** Rejects one pending attempt and DMs the requester. Returns the resolved attempt, or {@code null} if it was already resolved or gone. */
    VerificationAttempt completeRejection(Guild guild, long attemptId, long resolvedByUserId) {
        VerificationAttempt attempt = linkService.getAttempt(attemptId);
        if (!linkService.reject(attemptId, resolvedByUserId)) return null;

        dmVerificationUpdate(guild, attempt.discordUserId(), Containers.DANGER,
                "❌ Your request to link **" + attempt.rsn() + "** was denied. Contact an admin if you think this is a mistake.");
        return attempt;
    }

    private void handleVerifyApprove(ButtonInteractionEvent event, String id) {
        if (!isAdmin(event)) {
            Containers.replyEphemeral(event, Containers.WARNING, "You need the Admin role (or higher) to use this.");
            return;
        }

        long attemptId = Long.parseLong(id.split(":")[1]);
        VerificationAttempt attempt = completeApproval(event.getGuild(), attemptId, event.getUser().getIdLong());
        if (attempt == null) {
            Containers.replyEphemeral(event, Containers.WARNING, "This request was already resolved.");
            return;
        }

        Containers.edit(event, Containers.SUCCESS, "✅ Approved by " + event.getUser().getAsMention() +
                " — **" + attempt.rsn() + "** is now linked to <@" + attempt.discordUserId() + ">.");
    }

    private void handleVerifyReject(ButtonInteractionEvent event, String id) {
        if (!isAdmin(event)) {
            Containers.replyEphemeral(event, Containers.WARNING, "You need the Admin role (or higher) to use this.");
            return;
        }

        long attemptId = Long.parseLong(id.split(":")[1]);
        VerificationAttempt attempt = completeRejection(event.getGuild(), attemptId, event.getUser().getIdLong());
        if (attempt == null) {
            Containers.replyEphemeral(event, Containers.WARNING, "This request was already resolved.");
            return;
        }

        Containers.edit(event, Containers.DANGER, "❌ Rejected by " + event.getUser().getAsMention() + ".");
    }

    private void handleModal(ModalInteractionEvent event, String modalId) {
        if (modalId.startsWith("rs_activity_jump_modal:")) {
            handleActivityJumpModal(event, modalId.split(":", 2)[1]);
            return;
        }
        if (!modalId.equals("rs_link_modal:_")) return;

        String rsn = event.getValue("rs_link_name").getAsString().trim();
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
            event.replyComponents(List.of(buildPendingStatusPanel(pending))).useComponentsV2(true).setEphemeral(true).queue();
            return;
        }

        VerificationAttempt attempt = linkService.startVerification(guildId, userId, rsn);
        postForAdminReview(event.getGuild(), attempt);
        dmVerificationUpdate(event.getGuild(), userId, Containers.INFO,
                "Your request to link **" + rsn + "** has been submitted — an admin is reviewing it. You'll get a DM once it's approved or denied.");
        event.replyComponents(List.of(buildPendingStatusPanel(attempt))).useComponentsV2(true).setEphemeral(true).queue();
    }

    static Modal buildLinkModal() {
        TextInput rsnInput = TextInput.create("rs_link_name", TextInputStyle.SHORT)
                .setPlaceholder("Your exact in-game display name")
                .setRequired(true)
                .setRequiredRange(1, 12)
                .build();

        return Modal.create("rs_link_modal:_", "Link Your RuneScape Name")
                .addComponents(Label.of("RuneScape Name", rsnInput))
                .build();
    }

    private void handleActivityJumpModal(ModalInteractionEvent event, String rsn) {
        Guild guild = event.getGuild();
        if (ownLinkOrNull(guild, event.getUser().getIdLong(), rsn) == null) {
            Containers.replyEphemeral(event, Containers.WARNING, "That's not one of your linked accounts.");
            return;
        }

        String raw = event.getValue("page_number").getAsString().trim();
        int pageNumber;
        try {
            pageNumber = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            Containers.replyEphemeral(event, Containers.WARNING, "'" + raw + "' isn't a valid page number.");
            return;
        }
        editActivityPage(event, guild, rsn, pageNumber - 1); // Pagination.paginate clamps an out-of-range index
    }

    // --- /rs's own entry-point panels (called directly by RsCommand) ---

    /** A freshly-submitted or still-pending verification, from the linked member's own point of view — not the admin queue (see {@link RsAdminInteractionListener}). */
    static Container buildPendingStatusPanel(VerificationAttempt pending) {
        return Containers.card(Containers.PRIMARY,
                TextDisplay.of("### Verifying " + pending.rsn()),
                TextDisplay.of("Your request to link **" + pending.rsn() + "** is pending admin review. " +
                        "You'll get a DM as soon as it's approved or denied."),
                ActionRow.of(
                        Button.secondary("rs_refresh_pending:_", "Refresh Status"),
                        Button.secondary("rs_cancel_own:" + pending.attemptId(), "Start Over (Wrong Name?)")
                ));
    }

    // Discord's Components V2 payload has a hard ceiling of 40 total components across the whole
    // message — every action row, button, text display, and separator counts individually, no
    // matter how deeply nested. buildAccountPanel uses these to work out how many *additional*
    // linked accounts can still render in full before the rest have to fall back to a compact
    // "View Profile" entry.
    private static final int COMPONENT_BUDGET = 40;
    private static final int CLAN_HEADER_COST = 8; // TextDisplay + invisible Separator + ActionRow(5 buttons) — see buildClanHeader
    private static final int FULL_PROFILE_CONTENT_COST = 7; // 2 TextDisplay + ActionRow(4 buttons) — see profileContent
    private static final int COMPACT_PROFILE_CONTENT_COST = 3; // 1 TextDisplay + ActionRow(1 button)
    private static final int TRAILING_ROW_COST = 2; // ActionRow(1 button) — "+ Link Another RSN" / "Back to Account List"

    /**
     * The top-level "you're verified" view: the clan header (server icon, clan name, and the
     * clan-wide/Share buttons — see {@link #buildClanHeader}, always ephemeral regardless of Share),
     * then the first/primary linked account in full, inline, exactly like {@link #buildProfilePanel}
     * but without its own "Back" footer (there's nowhere to go back to from here). Additional
     * accounts also render in full, for as long as they still fit under Discord's
     * {@value #COMPONENT_BUDGET}-component ceiling for the whole message — once the next one
     * wouldn't fit, it (and every account after it, sizes being roughly uniform) falls back to a
     * compact name-and-a-button entry that drills into its own full {@link #buildProfilePanel}
     * instead. The primary account is never subject to this — the common case (one linked account)
     * always sees full detail with no extra click. If this is ever reached with zero links
     * (shouldn't happen from {@link RsCommand} itself, only from a stale re-render after e.g. an
     * unlink), says so plainly instead of rendering nothing.
     */
    Container buildAccountPanel(Guild guild, long discordUserId) {
        List<PlayerLink> links = linkService.getLinksForUser(guild.getIdLong(), discordUserId);
        if (links.isEmpty()) {
            return Containers.card(Containers.WARNING, TextDisplay.of("You have no linked RuneScape name — run `/rs` again to link one."));
        }

        List<ContainerChildComponent> children = new ArrayList<>(buildClanHeader(guild, discordUserId));
        children.add(Separator.createDivider(Separator.Spacing.LARGE));
        children.addAll(profileContent(guild, links.getFirst()));

        int remaining = COMPONENT_BUDGET - CLAN_HEADER_COST - 1 /* divider after header */
                - FULL_PROFILE_CONTENT_COST /* primary account, always shown in full */
                - 1 /* divider before the trailing row */ - TRAILING_ROW_COST;
        boolean roomForFull = true;

        for (PlayerLink extra : links.stream().skip(1).toList()) {
            children.add(Separator.createDivider(Separator.Spacing.SMALL));
            remaining -= 1;

            if (roomForFull && remaining >= FULL_PROFILE_CONTENT_COST) {
                children.addAll(profileContent(guild, extra));
                remaining -= FULL_PROFILE_CONTENT_COST;
            } else {
                roomForFull = false;
                children.add(TextDisplay.of("**" + extra.rsn() + "** — *(display limit reached, click View Profile)*"));
                children.add(ActionRow.of(Button.secondary("rs_profile:" + extra.rsn(), "View Profile")));
                remaining -= COMPACT_PROFILE_CONTENT_COST;
            }
        }

        children.add(Separator.createDivider(Separator.Spacing.SMALL));
        children.add(ActionRow.of(Button.primary("rs_link_another:_", "+ Link Another RSN")));
        return Containers.card(RS3_ORANGE, children);
    }

    /**
     * Drilling into one specific account from {@link #buildAccountPanel}'s list — the clan header
     * stays present (same as every other /rs view now), followed by a "Back" footer instead of the
     * "other accounts" list, since that's the only way to reach this in the first place.
     */
    Container buildProfilePanel(Guild guild, PlayerLink link) {
        List<ContainerChildComponent> children = new ArrayList<>(buildClanHeader(guild, link.discordUserId()));
        children.add(Separator.createDivider(Separator.Spacing.LARGE));
        children.addAll(profileContent(guild, link));
        children.add(Separator.createDivider(Separator.Spacing.SMALL));
        children.add(ActionRow.of(Button.secondary("rs_back:_", "Back to Account List")));
        return Containers.card(RS3_ORANGE, children);
    }

    /**
     * The server's configured clan name as a top-level heading (Discord's largest, {@code #} — the
     * {@code ###} used elsewhere in this panel is actually the smallest of the three, wrong for a
     * banner title), a small invisible spacer, then clan-wide actions anyone with a linked RSN can
     * use — Join/Stats/Leaderboards/Information (always ephemeral, whatever Share is set to) and
     * Share itself. Present on every /rs view (the base panel and any drilled-into profile), not
     * just the top level. No icon: an inline emoji (see the now-unused
     * {@link com.younglings.bot.runescape.GuildIconEmojiCatalog}, kept for later) would render at
     * normal text size either way, and the bot's own avatar already sits next to every message it
     * sends, so a second icon inside the card was redundant.
     */
    private List<ContainerChildComponent> buildClanHeader(Guild guild, long discordUserId) {
        List<ContainerChildComponent> children = new ArrayList<>();

        children.add(TextDisplay.of("# " + clanNameOrFallback(guild)));
        children.add(Separator.createInvisible(Separator.Spacing.SMALL));

        boolean shareOn = isShareEnabled(discordUserId);
        children.add(ActionRow.of(
                Button.secondary("rs_clan_join:_", "Join"),
                Button.secondary("rs_clan_stats:_", "Stats"),
                Button.secondary("rs_clan_leaderboard:_", "Leaderboards"),
                Button.secondary("rs_clan_info:_", "Information"),
                shareOn ? Button.success("rs_share:_", "Share: On") : Button.danger("rs_share:_", "Share: Off")
        ));

        return children;
    }

    /**
     * One linked account's shared content — name (with a Discord mention on the same line),
     * level/combat/xp/quests, last-polled time plus whether a self-service poll is available right
     * now (independent of admin/auto polls — see {@link PlayerLinkService#canSelfPoll}), and every
     * live action button. No verification stamp here anymore — that's admin-only detail now (see
     * {@link RsAdminInteractionListener}'s Player Lookup). Used both inline (the primary account, on
     * {@link #buildAccountPanel} itself) and standalone ({@link #buildProfilePanel}'s drill-down) —
     * only the footer differs between those two.
     */
    private List<ContainerChildComponent> profileContent(Guild guild, PlayerLink link) {
        String rsn = link.rsn();
        List<ContainerChildComponent> children = new ArrayList<>();

        children.add(TextDisplay.of("### " + rsn + " — <@" + link.discordUserId() + ">"));
        children.add(TextDisplay.of(buildOverviewLine(guild, link)));

        boolean canPoll = linkService.canSelfPoll(link);
        children.add(ActionRow.of(
                canPoll ? Button.primary("rs_poll:" + rsn, "Update") : Button.primary("rs_poll:" + rsn, "Update").asDisabled(),
                Button.secondary("rs_skills:" + rsn, "Full Skills"),
                Button.secondary("rs_activity:" + rsn, "Full Activity"),
                Button.danger("rs_unlink:" + rsn, "Unlink")
        ));

        return children;
    }

    /** Level/combat/xp/quests, last-polled time, self-poll availability (🟢/🔴), and a latest-activity headline — everything at a glance. */
    private String buildOverviewLine(Guild guild, PlayerLink link) {
        var history = statsService.getSnapshotHistory(guild.getIdLong(), link.rsn(), 2);
        if (history.isEmpty()) {
            return "*Never updated yet — click **Update** below.*";
        }

        var latest = history.getFirst();
        String overallMention = skillEmojiCatalog.overallMention();
        StringBuilder sb = new StringBuilder()
                .append(overallMention != null ? overallMention + " " : "")
                .append("**Level:** ").append(latest.totalLevel())
                .append(" • **Combat:** ").append(latest.combatLevel())
                .append(" • **XP:** `").append(String.format("%,d", latest.totalXp())).append("`")
                .append(" • **Quests:** ").append(latest.questsComplete());

        if (history.size() > 1) {
            long xpGained = latest.totalXp() - history.get(1).totalXp();
            if (xpGained > 0) {
                sb.append("\n-# +").append(String.format("%,d", xpGained)).append(" xp since last update");
            }
        }

        sb.append("\n-# Updated <t:").append(latest.snapshotAt().toEpochSecond()).append(":R> • Manual update: ");
        if (linkService.canSelfPoll(link)) {
            sb.append("🟢 Available now");
        } else {
            long minutesLeft = Math.max(1, linkService.selfPollCooldownRemaining(link).toMinutes());
            sb.append("🔴 Available in ").append(minutesLeft).append("m");
        }

        var activity = statsService.getRecentActivities(guild.getIdLong(), link.rsn(), 1);
        if (!activity.isEmpty()) {
            sb.append("\n-# Latest activity: ").append(activity.getFirst().text());
        }

        return sb.toString();
    }

    /** The only 30-minute-cooldown-checked poll in the bot — admin/Player-Lookup polls and the (disabled) auto-poll scheduler all bypass this entirely. */
    private void doSelfPoll(ButtonInteractionEvent event, Guild guild, String rsn) {
        PlayerLink link = ownLinkOrNull(guild, event.getUser().getIdLong(), rsn);
        if (link == null) {
            Containers.replyEphemeral(event, Containers.WARNING, "That's not one of your linked accounts.");
            return;
        }
        if (!linkService.canSelfPoll(link)) {
            long minutesLeft = Math.max(1, linkService.selfPollCooldownRemaining(link).toMinutes());
            Containers.replyEphemeral(event, Containers.WARNING, "You can update again in about " + minutesLeft + " minute(s).");
            return;
        }

        event.deferEdit().queue();
        statsService.pollAndSnapshot(guild.getIdLong(), rsn);
        linkService.recordSelfPoll(link.linkId());
        event.getHook().editOriginalComponents(List.of(renderAfterOwnAction(guild, link))).useComponentsV2(true).queue();
    }

    /**
     * Where an action on {@code link} should land afterward: the base account panel if it's the
     * caller's primary/first linked account (already fully shown there — no reason to "drill into"
     * a profile view that duplicates what's already on screen), or the standalone profile view (with
     * its Back button) if it's a secondary account only reachable that way in the first place.
     */
    private Container renderAfterOwnAction(Guild guild, PlayerLink link) {
        List<PlayerLink> allLinks = linkService.getLinksForUser(guild.getIdLong(), link.discordUserId());
        boolean isPrimary = !allLinks.isEmpty() && allLinks.getFirst().linkId() == link.linkId();
        return isPrimary ? buildAccountPanel(guild, link.discordUserId()) : buildProfilePanel(guild, link);
    }

    private void doMonthlyRecap(ButtonInteractionEvent event, Guild guild, String rsn) {
        if (ownLinkOrNull(guild, event.getUser().getIdLong(), rsn) == null) {
            Containers.replyEphemeral(event, Containers.WARNING, "That's not one of your linked accounts.");
            return;
        }

        // Deferred — building the donut chart plus a network fetch for the guild icon is
        // comfortably more than the 3-second ack window allows for.
        event.deferReply(true).queue();

        MonthlyRecapStats stats = monthlyRecapService.getStats(guild.getIdLong(), rsn);
        if (stats == null) {
            event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.WARNING,
                    "No snapshots for **" + rsn + "** yet this month — update first."))).useComponentsV2(true).queue();
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

    /** The server's own icon, used as the recap image's "clan logo" — {@code null} if the guild has no icon set, or the fetch fails. */
    private byte[] fetchGuildIcon(Guild guild) {
        String iconUrl = guild.getIconUrl();
        if (iconUrl == null) return null;

        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(iconUrl + "?size=256")).GET().build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return response.statusCode() / 100 == 2 ? response.body() : null;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Failed to fetch guild icon for monthly recap", e);
            return null;
        }
    }

    // Longest skill name ("Dungeoneering") sets the name column's width so every row's "Level"
    // and XP columns start at the same character position — plain monospace padding, since a
    // proportional (bold) font can't be aligned this way, and Discord's inline emoji mentions
    // can't appear inside a code span at all, hence sitting outside it below.
    private static final int SKILL_NAME_COLUMN_WIDTH = 13;

    /**
     * All 29 skills on one screen, in hiscores order (skill ID order — same as
     * {@link RuneScapeStatsService#getSkillsForSnapshot} already returns them in), each line led
     * by its inline emoji ({@link SkillEmojiCatalog}). Bundling every skill into one TextDisplay
     * (not one per skill) keeps this comfortably under both the 40-node component-tree budget and
     * the 4000-character content budget, so there's no need to paginate.
     */
    void showSkills(ComponentInteraction event, Guild guild, String rsn) {
        PlayerLinkRepository.StatsSnapshotRow latest = statsService.getLatestSnapshot(guild.getIdLong(), rsn);
        if (latest == null) {
            Containers.replyEphemeral(event, Containers.WARNING,
                    "No synced data for **" + rsn + "** yet — use **Update** first.");
            return;
        }

        List<SkillValue> skills = statsService.getSkillsForSnapshot(latest.snapshotId());
        if (skills.isEmpty()) {
            Containers.replyEphemeral(event, Containers.WARNING, "No skill data in this snapshot.");
            return;
        }

        // Right-justifying each XP number within a fixed field — computed from the widest one
        // actually being shown, not a guessed constant, so it's still correct whether the biggest
        // number here is "200,000,000" or a heavy grinder's "3,828,951,176" — is what keeps "xp"
        // landing in the same column on every row; left-justifying it (the previous approach) put
        // the padding on the wrong side, so "xp" drifted left or right depending on digit count.
        // The level column needs the same treatment: every per-skill level fits in 3 digits (max
        // 120), but Overall's *total* level can run to 4 (e.g. 3232), which used to overflow a
        // fixed "%-3d" field and throw only that one row out of alignment with the rest.
        String overallXpText = String.format("%,d", latest.totalXp());
        List<String> skillXpTexts = skills.stream().map(skill -> String.format("%,d", skill.xp())).toList();
        int xpWidth = Math.max(overallXpText.length(), skillXpTexts.stream().mapToInt(String::length).max().orElse(0));
        int levelWidth = Math.max(String.valueOf(latest.totalLevel()).length(),
                skills.stream().mapToInt(skill -> String.valueOf(skill.level()).length()).max().orElse(0));

        StringBuilder body = new StringBuilder();
        appendSkillRow(body, skillEmojiCatalog.overallMention(), "Overall", latest.totalLevel(), overallXpText, levelWidth, xpWidth);

        for (int i = 0; i < skills.size(); i++) {
            SkillValue skill = skills.get(i);
            appendSkillRow(body, skillEmojiCatalog.mentionFor(skill.skillId()),
                    RuneScapeSkillCatalog.nameFor(skill.skillId()), skill.level(), skillXpTexts.get(i), levelWidth, xpWidth);
        }

        Container container = Containers.card(RS3_ORANGE,
                TextDisplay.of("### " + rsn + " — Skills"),
                TextDisplay.of(body.toString().stripTrailing()),
                TextDisplay.of("-# As of <t:" + latest.snapshotAt().toEpochSecond() + ":R>"));
        event.replyComponents(List.of(container)).useComponentsV2(true).setEphemeral(ephemeralFor(event.getUser().getIdLong())).queue();
    }

    private void appendSkillRow(StringBuilder body, String mention, String name, int level, String xpText, int levelWidth, int xpWidth) {
        String row = String.format("%-" + SKILL_NAME_COLUMN_WIDTH + "s  Level %-" + levelWidth + "d  %" + xpWidth + "s xp", name, level, xpText);
        if (mention != null) body.append(mention).append(" ");
        body.append("`").append(row).append("`\n");
    }

    // Packing pages by rendered character length (not a fixed entry count) means a page of short
    // one-liners holds far more than 15, and a page that happened to draw several long entries in a
    // row never gets one cut off mid-render — it just spills to the next page instead. 3500 leaves
    // headroom under Discord's 4000-character TextDisplay content limit for the surrounding header/
    // footer text and any markdown-escaping surprises.
    private static final int ACTIVITY_CHAR_BUDGET = 3500;
    private static final int ACTIVITY_MAX_PAGES = 5;
    private static final int ACTIVITY_HISTORY_DAYS = 90;

    void showActivity(ComponentInteraction event, Guild guild, String rsn) {
        Container container = buildActivityContainer(guild, rsn, 0);
        if (container == null) {
            Containers.replyEphemeral(event, Containers.WARNING,
                    "No recorded activity for **" + rsn + "** yet — activity is captured the next time their stats are updated.");
            return;
        }
        event.replyComponents(List.of(container)).useComponentsV2(true).setEphemeral(ephemeralFor(event.getUser().getIdLong())).queue();
    }

    /** Page-turn/jump re-render — shared by the button page-nav and the "Go to Page" modal, since both just edit the same message in place. */
    private void editActivityPage(IMessageEditCallback event, Guild guild, String rsn, int pageIndex) {
        Container container = buildActivityContainer(guild, rsn, pageIndex);
        if (container == null) return; // can't happen in practice (a page-nav button only exists once page 0 already had content), but no content beats an exception
        event.editComponents(List.of(container)).useComponentsV2(true).queue();
    }

    private String activityLine(PlayerActivity activity) {
        return "`" + activity.date() + "` — " + activity.text() + "\n";
    }

    /**
     * Greedily fills each page up to {@link #ACTIVITY_CHAR_BUDGET} characters before starting the
     * next one, capped at {@link #ACTIVITY_MAX_PAGES} — {@code activities} must already be
     * newest-first. {@code truncated} is {@code true} only if the cap was actually hit with entries
     * still left over (the older ones get dropped, never the newer ones).
     */
    private record ActivityPages(List<List<PlayerActivity>> pages, boolean truncated) {}

    private ActivityPages packActivityPages(List<PlayerActivity> activities) {
        List<List<PlayerActivity>> pages = new ArrayList<>();
        List<PlayerActivity> current = new ArrayList<>();
        int currentLength = 0;

        for (PlayerActivity activity : activities) {
            int lineLength = activityLine(activity).length();
            if (!current.isEmpty() && currentLength + lineLength > ACTIVITY_CHAR_BUDGET) {
                pages.add(current);
                if (pages.size() == ACTIVITY_MAX_PAGES) return new ActivityPages(pages, true);
                current = new ArrayList<>();
                currentLength = 0;
            }
            current.add(activity);
            currentLength += lineLength;
        }
        if (!current.isEmpty()) pages.add(current);
        return new ActivityPages(pages, false);
    }

    /**
     * Up to {@link #ACTIVITY_MAX_PAGES} dynamically-sized pages, newest first, pulled from the last
     * {@link #ACTIVITY_HISTORY_DAYS} days — {@code getActivitiesSince} already exists and does the
     * date-bounded query in one go, no new repository method needed. {@code null} if there's nothing
     * recorded at all yet.
     */
    private Container buildActivityContainer(Guild guild, String rsn, int pageIndex) {
        OffsetDateTime since = OffsetDateTime.now().minusDays(ACTIVITY_HISTORY_DAYS);
        List<PlayerActivity> activities = new ArrayList<>(statsService.getActivitiesSince(guild.getIdLong(), rsn, since));
        Collections.reverse(activities); // getActivitiesSince is oldest-first; newest-first reads better here
        if (activities.isEmpty()) return null;

        ActivityPages packed = packActivityPages(activities);
        int clampedIndex = Math.max(0, Math.min(pageIndex, packed.pages().size() - 1));
        List<PlayerActivity> pageItems = packed.pages().get(clampedIndex);
        var page = new Pagination.Page<>(pageItems, clampedIndex, packed.pages().size(), activities.size());

        StringBuilder sb = new StringBuilder();
        for (PlayerActivity activity : page.items()) {
            sb.append(activityLine(activity));
        }

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("### " + rsn + " — Recent Activity"));
        children.add(TextDisplay.of(sb.toString()));
        if (!page.isSinglePage()) {
            children.add(Pagination.navRowWithJump(page, "rs_activity_page:" + rsn + "|", "rs_activity_jump:" + rsn));
        }
        children.add(TextDisplay.of("-# Dates are RuneScape's own timestamps, timezone as reported by the game." +
                (packed.truncated() ? " Older entries beyond " + ACTIVITY_MAX_PAGES + " pages aren't shown." : "")));

        return Containers.card(RS3_ORANGE, children);
    }

    // --- Clan header actions (always ephemeral, regardless of the Share toggle) ---

    private String clanNameOrFallback(Guild guild) {
        String clanName = clanSyncService.getClanName(guild.getIdLong());
        return clanName != null ? clanName : "the clan";
    }

    private void doClanStats(ComponentInteraction event, Guild guild) {
        // Deferred — walks every active clan member's snapshots/skills/activities, plus rendering
        // an image. Comfortably more than the 3-second ack window.
        event.deferReply(true).queue();

        var stats = clanOverviewService.getOverview(guild.getIdLong());
        if (stats == null) {
            event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.WARNING,
                    "No clan data tracked yet — ask an admin to run **Sync Clan**."))).useComponentsV2(true).queue();
            return;
        }

        FileUpload image = ClanOverviewRenderer.render(clanNameOrFallback(guild), stats, fetchGuildIcon(guild));
        if (image == null) {
            event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.WARNING,
                    "Couldn't render clan stats right now."))).useComponentsV2(true).queue();
            return;
        }

        Container container = Containers.card(Containers.PRIMARY,
                TextDisplay.of("### Clan Overview"),
                MediaGallery.of(MediaGalleryItem.fromFile(image)));
        event.getHook().editOriginalComponents(List.of(container)).useComponentsV2(true).queue();
    }

    private static final int LEADERBOARD_SIZE = 10;

    /** Ranks by each linked player's most recent snapshot — doesn't trigger a live poll itself, so this stays fast and doesn't hammer the API on every view. */
    private void doClanLeaderboard(ComponentInteraction event, Guild guild) {
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
                    "No stats have been synced yet — click **Update** on your profile, or an admin can trigger " +
                    "one from the admin panel, to get started.");
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
                TextDisplay.of("### " + clanNameOrFallback(guild) + " Leaderboard — Total XP"),
                TextDisplay.of(sb.toString()),
                TextDisplay.of("-# Based on each player's last synced snapshot, not a live update."));

        event.replyComponents(List.of(container)).useComponentsV2(true).setEphemeral(true).queue();
    }

    // --- Share toggle ---

    private boolean isShareEnabled(long userId) {
        return shareEnabled.getOrDefault(userId, false);
    }

    /** Whether a personal-profile reply should be ephemeral right now — {@code false} once the member has toggled Share on. */
    private boolean ephemeralFor(long userId) {
        return !isShareEnabled(userId);
    }

    /**
     * Just flips the flag and re-renders the panel in place — the panel's own visibility never
     * changes (see the class-level note on {@code shareEnabled}), so this is a plain edit, not the
     * delete-and-repost dance an actual visibility change would need. The only thing that moves is
     * the Share button's own color/label, immediately reflecting the new state.
     */
    private void doToggleShare(ButtonInteractionEvent event, Guild guild) {
        long userId = event.getUser().getIdLong();
        shareEnabled.put(userId, !isShareEnabled(userId));
        event.editComponents(List.of(buildAccountPanel(guild, userId))).useComponentsV2(true).queue();
    }

    // --- Helpers ---

    private boolean isAdmin(ButtonInteractionEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        return guild != null && member != null && adminRoleFilter.isAuthorized(guild, member);
    }
}
