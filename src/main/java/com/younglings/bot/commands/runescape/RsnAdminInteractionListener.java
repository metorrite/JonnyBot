package com.younglings.bot.commands.runescape;

import com.younglings.bot.discord.Containers;
import com.younglings.bot.discord.Pagination;
import com.younglings.bot.permission.AdminRoleFilter;
import com.younglings.bot.runescape.MonthlyRecapRenderer;
import com.younglings.bot.runescape.MonthlyRecapService;
import com.younglings.bot.runescape.MonthlyRecapStats;
import com.younglings.bot.runescape.PlayerLink;
import com.younglings.bot.runescape.PlayerLinkRepository;
import com.younglings.bot.runescape.PlayerLinkService;
import com.younglings.bot.runescape.RuneScapeSkillCatalog;
import com.younglings.bot.runescape.RuneScapeStatsService;
import com.younglings.bot.runescape.RuneScapeTestDataSeeder;
import com.younglings.bot.runescape.RuneScapeXpTable;
import com.younglings.bot.runescape.SkillEmojiCatalog;
import com.younglings.bot.runescape.SkillValue;
import com.younglings.bot.runescape.SkillXpPoint;
import com.younglings.bot.runescape.XpChartRenderer;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.mediagallery.MediaGallery;
import net.dv8tion.jda.api.components.mediagallery.MediaGalleryItem;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.utils.FileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
    private static final int PAGE_SIZE = 5;

    private final PlayerLinkService linkService;
    private final RuneScapeStatsService statsService;
    private final AdminRoleFilter adminRoleFilter;
    private final SkillEmojiCatalog skillEmojiCatalog;
    private final RuneScapeTestDataSeeder testDataSeeder;
    private final MonthlyRecapService monthlyRecapService;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public RsnAdminInteractionListener(PlayerLinkService linkService, RuneScapeStatsService statsService,
                                        AdminRoleFilter adminRoleFilter, SkillEmojiCatalog skillEmojiCatalog,
                                        RuneScapeTestDataSeeder testDataSeeder, MonthlyRecapService monthlyRecapService) {
        this.linkService = linkService;
        this.statsService = statsService;
        this.adminRoleFilter = adminRoleFilter;
        this.skillEmojiCatalog = skillEmojiCatalog;
        this.testDataSeeder = testDataSeeder;
        this.monthlyRecapService = monthlyRecapService;
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

    private void handleButton(ButtonInteractionEvent event, Guild guild, String id) {
        if (id.startsWith("rsnadmin_list_page:")) {
            int page = Integer.parseInt(id.split(":")[1]);
            event.editComponents(List.of(buildPanel(linkService, statsService, skillEmojiCatalog, guild, page)))
                    .useComponentsV2(true).queue();
            return;
        }

        if (id.startsWith("rsnadmin_poll_all")) {
            // Deferred edit, not a plain edit — polling every linked player is a series of HTTP
            // calls that will very likely take longer than Discord's 3-second ack window once
            // there's more than a couple of players.
            event.deferEdit().queue();
            List<PlayerLink> links = linkService.getAllLinks(guild.getIdLong());
            for (PlayerLink link : links) {
                statsService.pollAndSnapshot(guild.getIdLong(), link.rsn());
            }
            event.getHook().editOriginalComponents(List.of(buildPanel(linkService, statsService, skillEmojiCatalog, guild, 0)))
                    .useComponentsV2(true).queue();
            return;
        }

        if (id.startsWith("rsnadmin_poll:")) {
            String rsn = id.split(":", 2)[1];
            event.deferEdit().queue();
            statsService.pollAndSnapshot(guild.getIdLong(), rsn);
            event.getHook().editOriginalComponents(List.of(buildPanel(linkService, statsService, skillEmojiCatalog, guild, 0)))
                    .useComponentsV2(true).queue();
            return;
        }

        if (id.startsWith("rsnadmin_seed:")) {
            // Deferred, not a plain reply — 30 backdated snapshots means ~60 round trips to the
            // database, comfortably longer than Discord's 3-second ack window.
            String rsn = id.split(":", 2)[1];
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
            return;
        }

        if (id.startsWith("rsnadmin_chart:")) {
            String rsn = id.split(":", 2)[1];
            if (statsService.getLatestSnapshot(guild.getIdLong(), rsn) == null) {
                Containers.replyEphemeral(event, Containers.WARNING,
                        "No synced data for **" + rsn + "** yet — use **Poll Now** first.");
                return;
            }
            event.replyComponents(List.of(buildChartContainer(guild, rsn, 0))).useComponentsV2(true).setEphemeral(true).queue();
            return;
        }

        if (id.startsWith("rsnadmin_recap:")) {
            // Deferred — building the donut chart plus a network fetch for the guild icon is
            // comfortably more than the 3-second ack window allows for.
            String rsn = id.split(":", 2)[1];
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
            return;
        }

        if (id.startsWith("rsnadmin_nearly:")) {
            String rsn = id.split(":", 2)[1];
            PlayerLinkRepository.StatsSnapshotRow latest = statsService.getLatestSnapshot(guild.getIdLong(), rsn);
            if (latest == null) {
                Containers.replyEphemeral(event, Containers.WARNING,
                        "No synced data for **" + rsn + "** yet — use **Poll Now** first.");
                return;
            }
            List<SkillValue> skills = statsService.getSkillsForSnapshot(latest.snapshotId());
            event.replyComponents(List.of(buildNearlyThereContainer(rsn, skills, latest))).useComponentsV2(true).setEphemeral(true).queue();
            return;
        }

        if (id.startsWith("rsnadmin_unlink_confirm:")) {
            String rsn = id.split(":", 2)[1];
            PlayerLink link = linkService.getLinkForRsn(guild.getIdLong(), rsn);
            boolean unlinked = link != null && linkService.unlink(guild.getIdLong(), link.discordUserId(), link.linkId());
            Containers.edit(event, unlinked ? Containers.SUCCESS : Containers.WARNING,
                    unlinked ? "Unlinked **" + rsn + "**." : "Couldn't unlink — that link may already be gone.");
            return;
        }

        if (id.startsWith("rsnadmin_unlink_cancel")) {
            Containers.edit(event, Containers.INFO, "Cancelled — nothing was unlinked.");
            return;
        }

        if (id.startsWith("rsnadmin_unlink:")) {
            String rsn = id.split(":", 2)[1];
            Container confirm = Containers.card(Containers.WARNING,
                    TextDisplay.of("### Unlink " + rsn + "?"),
                    TextDisplay.of("This removes the link between the linked Discord account and **" + rsn + "**. Historical poll data is kept."),
                    ActionRow.of(
                            Button.danger("rsnadmin_unlink_confirm:" + rsn, "Yes, Unlink"),
                            Button.secondary("rsnadmin_unlink_cancel:_", "Cancel")
                    ));
            event.replyComponents(List.of(confirm)).useComponentsV2(true).setEphemeral(true).queue();
        }
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
     * Shared by {@link RsnAdminCommand}'s initial reply and this listener's own pagination/
     * re-renders. Grouped like the signup admin controls: a "Bulk Actions" section up top, then
     * each linked player gets its own labeled block with an inline at-a-glance overview (level/
     * combat/XP/quests, trend since the previous poll, and the latest activity headline — no click
     * needed for any of that) plus a row of buttons for the full drill-downs (all 29 skills with
     * icons, full poll history, full activity log) that genuinely don't fit inline once there's
     * more than a couple of players or skills involved.
     */
    static Container buildPanel(PlayerLinkService linkService, RuneScapeStatsService statsService,
                                 SkillEmojiCatalog skillEmojiCatalog, Guild guild, int pageIndex) {
        List<PlayerLink> links = linkService.getAllLinks(guild.getIdLong());

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("# RS3 Admin Panel"));
        children.add(TextDisplay.of("-# Bulk Actions — automatic polling is disabled, everything here is manual"));
        children.add(ActionRow.of(Button.primary("rsnadmin_poll_all:_", "Poll All")));
        children.add(Separator.createDivider(Separator.Spacing.SMALL));

        if (links.isEmpty()) {
            children.add(TextDisplay.of("*No linked players yet.*"));
            return Containers.card(Containers.PRIMARY, children);
        }

        var page = Pagination.paginate(links, pageIndex, PAGE_SIZE);
        children.add(TextDisplay.of("-# Linked Players (" + links.size() + ")"));

        for (PlayerLink link : page.items()) {
            children.add(TextDisplay.of("**" + link.rsn() + "** — <@" + link.discordUserId() + ">\n" +
                    "-# Verified <t:" + link.verifiedAt().toEpochSecond() + ":R> via " + link.verificationMethod()));
            children.add(TextDisplay.of(buildOverviewLine(statsService, skillEmojiCatalog, guild, link.rsn())));
            children.add(ActionRow.of(
                    Button.primary("rsnadmin_poll:" + link.rsn(), "Poll Now"),
                    Button.secondary("rsn_skills:" + link.rsn(), "Full Skills"),
                    Button.secondary("rsn_history:" + link.rsn(), "Full History"),
                    Button.secondary("rsn_activity:" + link.rsn(), "Full Activity"),
                    Button.secondary("rsnadmin_seed:" + link.rsn(), "Seed Test Data")
            ));
            children.add(ActionRow.of(
                    Button.secondary("rsnadmin_chart:" + link.rsn(), "XP Chart"),
                    Button.secondary("rsnadmin_recap:" + link.rsn(), "Monthly Recap"),
                    Button.secondary("rsnadmin_nearly:" + link.rsn(), "Nearly There"),
                    Button.danger("rsnadmin_unlink:" + link.rsn(), "Unlink")
            ));
            children.add(Separator.createDivider(Separator.Spacing.SMALL));
        }

        if (!page.isSinglePage()) {
            children.add(Pagination.navRow(page, "rsnadmin_list_page:"));
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
