package com.younglings.bot.commands.runescape;

import com.younglings.bot.discord.Containers;
import com.younglings.bot.runescape.PlayerLinkRepository;
import com.younglings.bot.runescape.RuneScapeSkillCatalog;
import com.younglings.bot.runescape.RuneScapeStatsService;
import com.younglings.bot.runescape.SkillEmojiCatalog;
import com.younglings.bot.runescape.SkillValue;
import com.younglings.bot.runescape.SkillXpPoint;
import com.younglings.bot.runescape.XpChartRenderer;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
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

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The multi-select-skills / graph-style / Overall XP chart, shared by {@link RsInteractionListener}
 * (a member's own profile) and {@link RsAdminInteractionListener} (Player Lookup, for anyone) —
 * pulled into its own listener rather than living in either one, since RS3 stats are public,
 * unprivileged data: gating it behind {@code /rsadmin}'s admin check (as it originally was) meant a
 * regular member couldn't view their own chart from {@code /rs} without tripping an "Admin role
 * required" rejection. Callers are responsible for their own authorization <em>before</em> opening
 * the chart (e.g. "is this your own linked RSN?" or "are you an admin?") — once opened, every
 * re-selection here (skills, style, Overall) needs no further check, same as any other public-stats
 * view in this bot.
 */
@BService
public class RsChartInteractionListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(RsChartInteractionListener.class);

    private final RuneScapeStatsService statsService;
    private final SkillEmojiCatalog skillEmojiCatalog;

    public RsChartInteractionListener(RuneScapeStatsService statsService, SkillEmojiCatalog skillEmojiCatalog) {
        this.statsService = statsService;
        this.skillEmojiCatalog = skillEmojiCatalog;
    }

    public enum ChartStyle {LINE, STACKED_BAR, PIE, DONUT}

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        Guild guild = event.getGuild();
        String id = event.getComponentId();
        if (guild == null || !id.startsWith("rschart_overall:")) return;

        try {
            String[] parts = id.split(":", 2)[1].split("\\|", -1);
            event.editComponents(List.of(buildChartContainer(guild, parts[0], Set.of(), ChartStyle.valueOf(parts[1]))))
                    .useComponentsV2(true).queue();
        } catch (Exception e) {
            log.error("Unhandled exception in rschart button interaction '{}'", id, e);
            Containers.replyError(event);
        }
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        String id = event.getComponentId();
        if (guild == null || member == null || !id.startsWith("rschart_")) return;

        try {
            String action = id.split(":")[0];
            // "|" not ":" inside the payload — see the skill/style builders below.
            String[] parts = id.split(":", 2)[1].split("\\|", -1);

            switch (action) {
                case "rschart_skill_a", "rschart_skill_b" -> {
                    String rsn = parts[0];
                    ChartStyle style = ChartStyle.valueOf(parts[1]);
                    Set<Integer> otherChunkSelected = parseSkillCsv(parts[2]);

                    Set<Integer> combined = new LinkedHashSet<>(otherChunkSelected);
                    for (String value : event.getValues()) combined.add(Integer.parseInt(value));

                    event.editComponents(List.of(buildChartContainer(guild, rsn, combined, style))).useComponentsV2(true).queue();
                }
                case "rschart_style" -> {
                    String rsn = parts[0];
                    Set<Integer> selected = parseSkillCsv(parts[1]);
                    ChartStyle style = ChartStyle.valueOf(event.getValues().getFirst());
                    event.editComponents(List.of(buildChartContainer(guild, rsn, selected, style))).useComponentsV2(true).queue();
                }
            }
        } catch (Exception e) {
            log.error("Unhandled exception in rschart select interaction '{}'", id, e);
            Containers.replyError(event);
        }
    }

    private static final int CHART_HISTORY_DAYS = 30;
    // Discord caps a single select menu at 25 options — 29 skills needs two menus, each with its
    // own custom_id (Discord rejects duplicate custom_ids within the same message).
    private static final int SKILL_SELECT_LIMIT = 25;
    private static final String[] SKILL_SELECT_PREFIXES = {"rschart_skill_a:", "rschart_skill_b:"};

    /**
     * The chart's initial open, and every re-selection (skills, style, or Overall — all edits,
     * never a new message). An empty {@code selectedSkillIds} means "Overall": one combined
     * total-XP line for {@link ChartStyle#LINE}, or every skill broken out for the other three
     * styles — there's no single "Overall bar/slice" otherwise. A non-empty set restricts every
     * style to just those skills.
     */
    public Container buildChartContainer(Guild guild, String rsn, Set<Integer> selectedSkillIds, ChartStyle style) {
        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("### " + rsn + " — XP Chart"));

        FileUpload chart = renderChart(guild, rsn, selectedSkillIds, style);
        if (chart != null) {
            children.add(net.dv8tion.jda.api.components.mediagallery.MediaGallery.of(
                    net.dv8tion.jda.api.components.mediagallery.MediaGalleryItem.fromFile(chart)));
        } else {
            children.add(TextDisplay.of("Not enough poll history in the last " + CHART_HISTORY_DAYS +
                    " days yet to draw this — need at least 2 polls. Try a different selection, or seed test data first."));
        }

        children.addAll(buildSkillSelectRows(rsn, selectedSkillIds, style));
        children.add(buildStyleSelectRow(rsn, selectedSkillIds, style));
        children.add(ActionRow.of(Button.secondary("rschart_overall:" + rsn + "|" + style.name(), "Overall")));
        return Containers.card(Containers.PRIMARY, children);
    }

    private FileUpload renderChart(Guild guild, String rsn, Set<Integer> selectedSkillIds, ChartStyle style) {
        boolean overall = selectedSkillIds.isEmpty();
        return switch (style) {
            case LINE -> overall
                    ? XpChartRenderer.renderLines(rsn, "Overall XP", Map.of("Overall", overallXpHistory(guild, rsn)))
                    : XpChartRenderer.renderLines(rsn, "XP by Skill", lineSeriesFor(guild, rsn, selectedSkillIds));
            case STACKED_BAR -> XpChartRenderer.renderStackedBar(rsn, stackedSeriesFor(guild, rsn, selectedSkillIds, overall));
            case PIE -> XpChartRenderer.renderPieOrDonut(rsn, "XP Distribution", pieDataFor(guild, rsn, selectedSkillIds, overall), false);
            case DONUT -> XpChartRenderer.renderPieOrDonut(rsn, "XP Distribution", pieDataFor(guild, rsn, selectedSkillIds, overall), true);
        };
    }

    /** Total XP per poll (not one skill's) — the same {@code player_stats_snapshot} rows the guild-wide chart and history views already read. */
    private List<SkillXpPoint> overallXpHistory(Guild guild, String rsn) {
        OffsetDateTime since = OffsetDateTime.now().minusDays(CHART_HISTORY_DAYS);
        return statsService.getSnapshotsSince(guild.getIdLong(), rsn, since).stream()
                .map(row -> new SkillXpPoint(row.snapshotAt(), row.totalXp()))
                .toList();
    }

    private Map<String, List<SkillXpPoint>> lineSeriesFor(Guild guild, String rsn, Set<Integer> skillIds) {
        Map<String, List<SkillXpPoint>> series = new LinkedHashMap<>();
        for (int skillId : skillIds) {
            series.put(RuneScapeSkillCatalog.nameFor(skillId), statsService.getSkillXpHistory(guild.getIdLong(), rsn, skillId, CHART_HISTORY_DAYS));
        }
        return series;
    }

    /** One query for every skill's history, filtered down to {@code skillIds} unless {@code overall} (then every skill), kept in hiscores order. */
    private Map<String, List<SkillXpPoint>> stackedSeriesFor(Guild guild, String rsn, Set<Integer> skillIds, boolean overall) {
        OffsetDateTime since = OffsetDateTime.now().minusDays(CHART_HISTORY_DAYS);
        List<PlayerLinkRepository.SkillHistoryPoint> all = statsService.getAllSkillsXpHistorySince(guild.getIdLong(), rsn, since);

        Map<Integer, List<SkillXpPoint>> bySkill = new LinkedHashMap<>();
        for (var point : all) {
            if (!overall && !skillIds.contains(point.skillId())) continue;
            bySkill.computeIfAbsent(point.skillId(), k -> new ArrayList<>()).add(new SkillXpPoint(point.timestamp(), point.xp()));
        }

        Map<String, List<SkillXpPoint>> byName = new LinkedHashMap<>();
        for (int skillId = 0; skillId < RuneScapeSkillCatalog.skillCount(); skillId++) {
            if (bySkill.containsKey(skillId)) byName.put(RuneScapeSkillCatalog.nameFor(skillId), bySkill.get(skillId));
        }
        return byName;
    }

    // Same reasoning as MonthlyRecapRenderer's own donut: labeling all 29 skills on one pie is
    // unreadable, so Overall keeps only the top ones and folds the rest into "Other". A specific
    // selection is never capped — choosing 12 skills to compare gets all 12.
    private static final int MAX_PIE_SLICES = 8;

    /** Each skill's XP as of the latest snapshot. {@code overall} keeps only the top {@link #MAX_PIE_SLICES}, folding the rest into "Other"; a specific selection shows every one of {@code skillIds} uncapped. */
    private Map<String, Long> pieDataFor(Guild guild, String rsn, Set<Integer> skillIds, boolean overall) {
        var latest = statsService.getLatestSnapshot(guild.getIdLong(), rsn);
        if (latest == null) return Map.of();

        List<SkillValue> skills = statsService.getSkillsForSnapshot(latest.snapshotId());
        if (!overall) {
            Map<String, Long> result = new LinkedHashMap<>();
            for (SkillValue skill : skills) {
                if (skillIds.contains(skill.skillId())) result.put(RuneScapeSkillCatalog.nameFor(skill.skillId()), skill.xp());
            }
            return result;
        }

        List<SkillValue> sorted = skills.stream().sorted(java.util.Comparator.comparingLong(SkillValue::xp).reversed()).toList();
        Map<String, Long> result = new LinkedHashMap<>();
        long otherTotal = 0;
        for (int i = 0; i < sorted.size(); i++) {
            if (i < MAX_PIE_SLICES) {
                result.put(RuneScapeSkillCatalog.nameFor(sorted.get(i).skillId()), sorted.get(i).xp());
            } else {
                otherTotal += sorted.get(i).xp();
            }
        }
        if (otherTotal > 0) result.put("Other", otherTotal);
        return result;
    }

    /**
     * Two 25-option-capped multi-select menus covering all 29 skills. Each chunk's custom_id
     * carries the <em>other</em> chunk's current selection (plus the rsn and style) — a select
     * menu's own interaction only reports what's newly checked within itself, so the other chunk's
     * picks have to round-trip through the component tree rather than being recomputed.
     */
    private List<ActionRow> buildSkillSelectRows(String rsn, Set<Integer> selectedSkillIds, ChartStyle style) {
        List<ActionRow> rows = new ArrayList<>();
        int skillCount = RuneScapeSkillCatalog.skillCount();
        int chunk = 0;

        for (int start = 0; start < skillCount; start += SKILL_SELECT_LIMIT, chunk++) {
            int end = Math.min(start + SKILL_SELECT_LIMIT, skillCount);
            final int chunkStart = start;
            final int chunkEnd = end;
            String otherChunkCsv = selectedSkillIds.stream()
                    .filter(id -> id < chunkStart || id >= chunkEnd)
                    .sorted()
                    .map(String::valueOf)
                    .collect(Collectors.joining(","));

            StringSelectMenu.Builder menu = StringSelectMenu.create(SKILL_SELECT_PREFIXES[chunk] + rsn + "|" + style.name() + "|" + otherChunkCsv)
                    .setPlaceholder(chunk == 0 ? "Choose skills to overlay" : "More skills")
                    .setMinValues(1)
                    .setMaxValues(end - start);

            List<String> defaults = new ArrayList<>();
            for (int skillId = start; skillId < end; skillId++) {
                String name = RuneScapeSkillCatalog.nameFor(skillId);
                String mention = skillEmojiCatalog.mentionFor(skillId);
                if (mention != null) {
                    menu.addOption(name, String.valueOf(skillId), Emoji.fromFormatted(mention));
                } else {
                    menu.addOption(name, String.valueOf(skillId));
                }
                if (selectedSkillIds.contains(skillId)) defaults.add(String.valueOf(skillId));
            }
            if (!defaults.isEmpty()) menu.setDefaultValues(defaults);
            rows.add(ActionRow.of(menu.build()));
        }
        return rows;
    }

    private ActionRow buildStyleSelectRow(String rsn, Set<Integer> selectedSkillIds, ChartStyle currentStyle) {
        String selectionCsv = selectedSkillIds.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
        StringSelectMenu.Builder menu = StringSelectMenu.create("rschart_style:" + rsn + "|" + selectionCsv)
                .setPlaceholder("Graph style");

        menu.addOption("Line", ChartStyle.LINE.name());
        menu.addOption("Stacked Bar", ChartStyle.STACKED_BAR.name());
        menu.addOption("Pie", ChartStyle.PIE.name());
        menu.addOption("Donut", ChartStyle.DONUT.name());
        menu.setDefaultValues(currentStyle.name());

        return ActionRow.of(menu.build());
    }

    private static Set<Integer> parseSkillCsv(String csv) {
        if (csv.isEmpty()) return new LinkedHashSet<>();
        return Arrays.stream(csv.split(",")).map(Integer::parseInt).collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
