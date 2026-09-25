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
import com.younglings.bot.runescape.RuneScapeSkillCatalog;
import com.younglings.bot.runescape.RuneScapeStatsService;
import com.younglings.bot.runescape.RuneScapeTestDataSeeder;
import com.younglings.bot.runescape.RuneScapeXpTable;
import com.younglings.bot.runescape.SkillEmojiCatalog;
import com.younglings.bot.runescape.SkillValue;
import com.younglings.bot.runescape.SkillXpPoint;
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
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Buttons for {@link RsnAdminCommand}'s panel: pagination, manual poll (single player or
 * everyone), and links into {@code RsnInteractionListener}'s existing per-RSN views (Skills,
 * History, Recent Activity are shared as-is — same {@code rsn_skills:}/{@code rsn_history:}/
 * {@code rsn_activity:} button IDs work from either place, no duplicated rendering logic).
 * <p>
 * "Poll Now" is deliberately the same action whether you think of it as "get fresh data" or
 * "re-sync a stale/old-format row" — both just mean fetch-and-save under whatever the current
 * schema is, so there's no separate re-sync mechanism to keep in step with this one.
 */
@BService
public class RsnAdminInteractionListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(RsnAdminInteractionListener.class);

    private final PlayerLinkService linkService;
    private final RuneScapeStatsService statsService;
    private final AdminRoleFilter adminRoleFilter;
    private final SkillEmojiCatalog skillEmojiCatalog;
    private final RuneScapeTestDataSeeder testDataSeeder;
    private final MonthlyRecapService monthlyRecapService;
    private final ClanSyncService clanSyncService;
    private final ClanOverviewService clanOverviewService;
    private final VerificationRoleSyncService roleSyncService;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public RsnAdminInteractionListener(PlayerLinkService linkService, RuneScapeStatsService statsService,
                                        AdminRoleFilter adminRoleFilter, SkillEmojiCatalog skillEmojiCatalog,
                                        RuneScapeTestDataSeeder testDataSeeder, MonthlyRecapService monthlyRecapService,
                                        ClanSyncService clanSyncService, ClanOverviewService clanOverviewService,
                                        VerificationRoleSyncService roleSyncService) {
        this.linkService = linkService;
        this.statsService = statsService;
        this.adminRoleFilter = adminRoleFilter;
        this.skillEmojiCatalog = skillEmojiCatalog;
        this.testDataSeeder = testDataSeeder;
        this.monthlyRecapService = monthlyRecapService;
        this.clanSyncService = clanSyncService;
        this.clanOverviewService = clanOverviewService;
        this.roleSyncService = roleSyncService;
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
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        String id = event.getComponentId();
        if (guild == null || member == null || !id.startsWith("rsnadmin_chart_select")) return;

        try {
            if (!adminRoleFilter.isAuthorized(guild, member)) {
                Containers.replyEphemeral(event, Containers.WARNING, "You need the Admin role (or higher) to use this.");
                return;
            }
            String rsn = id.split(":", 2)[1];
            int skillId = Integer.parseInt(event.getValues().getFirst());
            event.editComponents(List.of(buildChartContainer(guild, rsn, skillId))).useComponentsV2(true).queue();
        } catch (Exception e) {
            log.error("Unhandled exception in rsnadmin select interaction '{}'", id, e);
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
        roleSyncService.syncRoles(guild, discordUserId);
        Containers.replyEphemeral(event, Containers.SUCCESS, "Linked **" + rsn + "** to <@" + discordUserId + ">.");
    }

    private void handleButton(ButtonInteractionEvent event, Guild guild, String id) {
        String action = id.split(":")[0];

        switch (action) {
            case "rsnadmin_poll_all" -> doPollAll(event, guild);
            case "rsnadmin_guildchart" -> doGuildChart(event, guild);
            case "rsnadmin_syncclan" -> doSyncClan(event, guild);
            case "rsnadmin_lookup" -> doPlayerLookupPrompt(event);
            case "rsnadmin_manualverify" -> doManualVerifyPrompt(event);
            case "rsnadmin_clanlist" -> doClanList(event, guild);
            case "rsnadmin_clanoverview" -> doClanOverview(event, guild);

            case "rsnadmin_poll" -> doPollOne(event, guild, id.split(":", 2)[1]);
            case "rsnadmin_seed" -> doSeedTestData(event, guild, id.split(":", 2)[1]);
            case "rsnadmin_chart" -> doXpChart(event, guild, id.split(":", 2)[1]);
            case "rsnadmin_recap" -> doMonthlyRecap(event, guild, id.split(":", 2)[1]);
            case "rsnadmin_nearly" -> doNearlyThere(event, guild, id.split(":", 2)[1]);
            case "rsnadmin_unlink" -> doUnlinkPrompt(event, id.split(":", 2)[1]);

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

    private void doPollAll(ComponentInteraction event, Guild guild) {
        // Deferred edit, not a plain edit — polling every linked player is a series of HTTP calls
        // that will very likely take longer than Discord's 3-second ack window once there's more
        // than a couple of players.
        event.deferEdit().queue();
        List<PlayerLink> links = linkService.getAllLinks(guild.getIdLong());
        for (PlayerLink link : links) {
            statsService.pollAndSnapshot(guild.getIdLong(), link.rsn());
        }
        event.getHook().editOriginalComponents(List.of(buildPanel(linkService, statsService, skillEmojiCatalog, guild, event.getUser().getIdLong())))
                .useComponentsV2(true).queue();
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
                TextDisplay.of("### Guild XP Trend"),
                MediaGallery.of(MediaGalleryItem.fromFile(chart)));
        event.getHook().editOriginalComponents(List.of(container)).useComponentsV2(true).queue();
    }

    private void doSyncClan(ComponentInteraction event, Guild guild) {
        // Deferred — fetches the whole clan roster, then polls every member with a delay between
        // each (RUNESCAPE_POLL_DELAY_SECONDS), so this can genuinely take a couple of minutes for a
        // clan this size. That's expected, not a hang.
        event.deferReply(true).queue();
        var result = clanSyncService.syncAndPoll(guild.getIdLong());

        if (result.rosterSize() == 0) {
            event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.WARNING,
                    "Couldn't fetch the clan roster for **" + ClanSyncService.CLAN_NAME + "** — check the clan name and try again."))).useComponentsV2(true).queue();
            return;
        }

        event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.SUCCESS,
                "Synced **" + ClanSyncService.CLAN_NAME + "**: " + result.rosterSize() + " member(s) in the roster " +
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

        FileUpload image = ClanOverviewRenderer.render(ClanSyncService.CLAN_NAME, stats, fetchGuildIcon(guild));
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

    // --- Per-player actions (picked from buildPanel's per-row select menu) ---

    private void doPollOne(ComponentInteraction event, Guild guild, String rsn) {
        event.deferEdit().queue();
        statsService.pollAndSnapshot(guild.getIdLong(), rsn);
        event.getHook().editOriginalComponents(List.of(buildPanel(linkService, statsService, skillEmojiCatalog, guild, event.getUser().getIdLong())))
                .useComponentsV2(true).queue();
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
        event.replyComponents(List.of(buildChartContainer(guild, rsn, 0))).useComponentsV2(true).setEphemeral(true).queue();
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
    // Wide enough for "Dungeoneering" (13 chars), same reasoning as RsnInteractionListener's Skills view.
    private static final int NEARLY_THERE_NAME_WIDTH = 13;

    /** The 10 skills closest to their next level, by XP still needed — {@link RuneScapeXpTable} is the game's own level curve, not an approximation. */
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
            String mention = skillEmojiCatalog.mentionFor(gap.skill().skillId());
            String row = String.format("%-" + NEARLY_THERE_NAME_WIDTH + "s  Lv %-3d -> %-3d  %11s xp",
                    RuneScapeSkillCatalog.nameFor(gap.skill().skillId()), gap.skill().level(), gap.skill().level() + 1,
                    String.format("%,d", gap.xpNeeded()));
            if (mention != null) body.append(mention).append(" ");
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
     * Mentions use {@code setAllowedMentions} to suppress the ping — see {@link #buildVerifiedListContainer}
     * for why a real mention (not a plain username string) is used at all.
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

        boolean inClan = clanSyncService.getRoster(guild.getIdLong(), true).stream()
                .anyMatch(member -> member.rsn().equalsIgnoreCase(rsn));
        PlayerLink link = linkService.getLinkForRsn(guild.getIdLong(), rsn);

        StringBuilder meta = new StringBuilder()
                .append(inClan ? "✅ In the clan roster" : "— Not currently in the clan roster")
                .append(link != null ? "\n✅ Verified to <@" + link.discordUserId() + ">" : "\n— Not verified to any Discord account");
        children.add(TextDisplay.of(meta.toString()));

        switch (result) {
            case ProfileResult.Found(var profile) -> {
                String overallMention = skillEmojiCatalog.overallMention();
                String lead = overallMention != null ? overallMention + " " : "";
                children.add(TextDisplay.of(lead + "**Total Level:** " + profile.totalLevel() + "\n" +
                        "**Combat Level:** " + profile.combatLevel() + "\n" +
                        "**Quests Complete:** " + profile.questsComplete() + "\n" +
                        "**Total XP:** `" + String.format("%,d", profile.totalXp()) + " xp`"));
                children.add(ActionRow.of(
                        Button.primary("rsnadmin_poll:" + rsn, "Poll Again"),
                        Button.secondary("rsn_skills:" + rsn, "Full Skills"),
                        Button.secondary("rsnadmin_chart:" + rsn, "XP Chart"),
                        link != null ? Button.danger("rsnadmin_unlink:" + rsn, "Unlink")
                                : Button.secondary("rsnadmin_manualverify:_", "Verify This Player")
                ));
            }
            case ProfileResult.Private ignored -> children.add(TextDisplay.of(
                    "🔒 This player's **Adventurer's Log is set to private** — RuneScape won't return stats until they make it public in-game (Settings → Privacy)."));
            case ProfileResult.NotFound ignored -> children.add(TextDisplay.of(
                    "❓ No RuneMetrics profile found for **" + rsn + "** — check the spelling, or they may have never opened their Adventurer's Log."));
            case ProfileResult.Unavailable ignored -> children.add(TextDisplay.of(
                    "⚠️ Couldn't fetch stats right now — the RuneScape API may be temporarily unavailable. Try again shortly."));
        }

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

    private static final int CHART_HISTORY_DAYS = 30;
    private static final int GUILD_CHART_HISTORY_DAYS = 30;
    // Discord caps a single select menu at 25 options — 29 skills needs two menus, each with its
    // own custom_id (Discord rejects duplicate custom_ids within the same message).
    private static final int SKILL_SELECT_LIMIT = 25;
    private static final String[] SKILL_SELECT_PREFIXES = {"rsnadmin_chart_select_a:", "rsnadmin_chart_select_b:"};

    /** Shared by the chart's initial open and every skill-dropdown re-selection (an edit, not a new message). */
    private Container buildChartContainer(Guild guild, String rsn, int selectedSkillId) {
        List<SkillXpPoint> points = statsService.getSkillXpHistory(guild.getIdLong(), rsn, selectedSkillId, CHART_HISTORY_DAYS);
        String skillName = RuneScapeSkillCatalog.nameFor(selectedSkillId);

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("### " + rsn + " — XP Chart"));

        FileUpload chart = XpChartRenderer.render(rsn, skillName, points);
        if (chart != null) {
            children.add(MediaGallery.of(MediaGalleryItem.fromFile(chart)));
        } else {
            children.add(TextDisplay.of("Not enough poll history for **" + skillName + "** in the last " + CHART_HISTORY_DAYS +
                    " days yet — need at least 2 polls to draw a trend. Pick a different skill, or seed test data first."));
        }

        children.addAll(buildSkillSelectRows(rsn, selectedSkillId));
        return Containers.card(Containers.PRIMARY, children);
    }

    private List<ActionRow> buildSkillSelectRows(String rsn, int selectedSkillId) {
        List<ActionRow> rows = new ArrayList<>();
        int skillCount = RuneScapeSkillCatalog.skillCount();
        int chunk = 0;

        for (int start = 0; start < skillCount; start += SKILL_SELECT_LIMIT, chunk++) {
            int end = Math.min(start + SKILL_SELECT_LIMIT, skillCount);
            StringSelectMenu.Builder menu = StringSelectMenu.create(SKILL_SELECT_PREFIXES[chunk] + rsn)
                    .setPlaceholder(chunk == 0 ? "Choose a skill" : "More skills");

            for (int skillId = start; skillId < end; skillId++) {
                String name = RuneScapeSkillCatalog.nameFor(skillId);
                String mention = skillEmojiCatalog.mentionFor(skillId);
                if (mention != null) {
                    menu.addOption(name, String.valueOf(skillId), Emoji.fromFormatted(mention));
                } else {
                    menu.addOption(name, String.valueOf(skillId));
                }
            }
            if (selectedSkillId >= start && selectedSkillId < end) {
                menu.setDefaultValues(String.valueOf(selectedSkillId));
            }
            rows.add(ActionRow.of(menu.build()));
        }
        return rows;
    }

    /**
     * Shared by {@link RsnAdminCommand}'s initial reply and this listener's own re-renders (Poll
     * All / Poll Now). Two parts: bulk actions that operate on the whole clan/guild, and the
     * <em>calling admin's own</em> linked account(s) — not every linked player. Showing everyone
     * inline is what originally blew this panel past Discord's 40-component-tree budget the moment
     * more than a couple of players were linked ("Cannot build message with over 40 total
     * components"); scoping the detailed, button-heavy section to just the caller's own account(s)
     * keeps it bounded regardless of how large the clan gets, while every other player is still
     * fully manageable via **Manually Verify** (create/replace a link) and **Player Lookup**
     * (inspect, poll, chart, or unlink any one name on demand).
     */
    static Container buildPanel(PlayerLinkService linkService, RuneScapeStatsService statsService,
                                 SkillEmojiCatalog skillEmojiCatalog, Guild guild, long callerId) {
        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("# RS3 Admin Panel"));
        children.add(TextDisplay.of("-# Bulk Actions — automatic polling is disabled, everything here is manual"));
        children.add(ActionRow.of(
                Button.primary("rsnadmin_poll_all:_", "Poll All"),
                Button.secondary("rsnadmin_guildchart:_", "Guild XP Trend"),
                Button.secondary("rsnadmin_syncclan:_", "Sync Clan"),
                Button.secondary("rsnadmin_lookup:_", "Player Lookup"),
                Button.secondary("rsnadmin_manualverify:_", "Manually Verify")
        ));
        children.add(ActionRow.of(
                Button.secondary("rsnadmin_clanlist:_", "Clan Member List"),
                Button.secondary("rsnadmin_clanoverview:_", "Clan Overview")
        ));
        children.add(Separator.createDivider(Separator.Spacing.SMALL));

        List<PlayerLink> myLinks = linkService.getLinksForUser(guild.getIdLong(), callerId);
        children.add(TextDisplay.of("-# Your Linked Account" + (myLinks.size() == 1 ? "" : "s")));

        if (myLinks.isEmpty()) {
            children.add(TextDisplay.of("*You have no linked RSN yet — use **Manually Verify** above, or link one yourself via `/rsn`.*"));
            return Containers.card(Containers.PRIMARY, children);
        }

        PlayerLink primary = myLinks.getFirst();
        children.add(TextDisplay.of("**" + primary.rsn() + "** — <@" + primary.discordUserId() + ">\n" +
                "-# Verified <t:" + primary.verifiedAt().toEpochSecond() + ":R> via " + primary.verificationMethod()));
        children.add(TextDisplay.of(buildOverviewLine(statsService, skillEmojiCatalog, guild, primary.rsn())));
        children.add(ActionRow.of(
                Button.primary("rsnadmin_poll:" + primary.rsn(), "Poll Now"),
                Button.secondary("rsn_skills:" + primary.rsn(), "Full Skills"),
                Button.secondary("rsn_history:" + primary.rsn(), "Full History"),
                Button.secondary("rsn_activity:" + primary.rsn(), "Full Activity"),
                Button.secondary("rsnadmin_seed:" + primary.rsn(), "Seed Test Data")
        ));
        children.add(ActionRow.of(
                Button.secondary("rsnadmin_chart:" + primary.rsn(), "XP Chart"),
                Button.secondary("rsnadmin_recap:" + primary.rsn(), "Monthly Recap"),
                Button.secondary("rsnadmin_nearly:" + primary.rsn(), "Nearly There"),
                Button.danger("rsnadmin_unlink:" + primary.rsn(), "Unlink")
        ));

        if (myLinks.size() > 1) {
            String extras = myLinks.stream().skip(1).map(PlayerLink::rsn).reduce((a, b) -> a + ", " + b).orElse("");
            children.add(TextDisplay.of("-# Also linked: " + extras + " — use **Player Lookup** to inspect these."));
        }

        return Containers.card(Containers.PRIMARY, children);
    }

    /** The inline "everything at a glance" line — overview, trend since the last poll, and the latest activity headline. */
    private static String buildOverviewLine(RuneScapeStatsService statsService, SkillEmojiCatalog skillEmojiCatalog, Guild guild, String rsn) {
        var history = statsService.getSnapshotHistory(guild.getIdLong(), rsn, 2);
        if (history.isEmpty()) {
            return "*Never polled.*";
        }

        var latest = history.getFirst();
        String overallMention = skillEmojiCatalog.overallMention();
        StringBuilder sb = new StringBuilder()
                .append(overallMention != null ? overallMention + " " : "")
                .append("**Level:** ").append(latest.totalLevel())
                .append(" • **Combat:** ").append(latest.combatLevel())
                .append(" • **XP:** `").append(String.format("%,d", latest.totalXp())).append("`")
                .append(" • **Quests:** ").append(latest.questsComplete())
                .append("\n-# Polled <t:").append(latest.snapshotAt().toEpochSecond()).append(":R>");

        if (history.size() > 1) {
            long xpGained = latest.totalXp() - history.get(1).totalXp();
            if (xpGained != 0) {
                sb.append(" • ").append(xpGained > 0 ? "+" : "").append(String.format("%,d", xpGained)).append(" xp since previous poll");

                // Rate is only meaningful once there's a real gap between polls — two polls a few
                // seconds apart (e.g. testing) would otherwise divide by a near-zero duration and
                // print a meaningless, huge xp/hr figure.
                Duration elapsed = Duration.between(history.get(1).snapshotAt(), latest.snapshotAt());
                if (xpGained > 0 && elapsed.toMinutes() >= 15) {
                    long ratePerHour = (long) (xpGained / (elapsed.toMinutes() / 60.0));
                    sb.append(" (~").append(String.format("%,d", ratePerHour)).append(" xp/hr)");
                }
            }
        }

        var activity = statsService.getRecentActivities(guild.getIdLong(), rsn, 1);
        if (!activity.isEmpty()) {
            sb.append("\n-# Latest activity: ").append(activity.getFirst().text());
        }

        return sb.toString();
    }
}
