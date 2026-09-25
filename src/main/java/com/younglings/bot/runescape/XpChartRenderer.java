package com.younglings.bot.runescape;

import net.dv8tion.jda.api.utils.FileUpload;
import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.CategoryChart;
import org.knowm.xchart.CategoryChartBuilder;
import org.knowm.xchart.PieChart;
import org.knowm.xchart.PieChartBuilder;
import org.knowm.xchart.PieSeries;
import org.knowm.xchart.XYChart;
import org.knowm.xchart.XYChartBuilder;
import org.knowm.xchart.XYSeries;
import org.knowm.xchart.internal.chartpart.Chart;
import org.knowm.xchart.style.PieStyler;
import org.knowm.xchart.style.markers.SeriesMarkers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Renders a skill's XP-over-time trend as a PNG, since Discord's Components V2 has no live/
 * interactive chart primitive — this is a static image attached like any other. Styled to sit
 * naturally in Discord's dark theme (most viewers) rather than XChart's white default.
 */
public final class XpChartRenderer {
    private static final Logger log = LoggerFactory.getLogger(XpChartRenderer.class);

    // Matches Discord's own dark-theme surface/text colors so the chart doesn't look like a
    // pasted-in white rectangle; the line uses the bot's existing RS3 accent (Color.ORANGE) for
    // consistency with the rest of this feature.
    private static final Color BACKGROUND = new Color(0x2b, 0x2d, 0x31);
    private static final Color GRID = new Color(0x45, 0x47, 0x4d);
    private static final Color TEXT = new Color(0xdb, 0xdd, 0xde);
    private static final Color LINE = Color.ORANGE;
    // Fixed categorical order for the guild-wide chart's per-player lines, cycled by XChart in
    // insertion order — not tied to any particular player's identity, since who's plotted changes
    // guild to guild.
    private static final Color[] SERIES_COLORS = {
            Color.ORANGE, new Color(0x3B, 0x82, 0xF6), new Color(0x22, 0xC5, 0x5E),
            new Color(0xED, 0x42, 0x45), new Color(0x8B, 0x5C, 0xF6), new Color(0xFB, 0xBF, 0x24),
            new Color(0x06, 0xB6, 0xD4), new Color(0xEC, 0x48, 0x99)
    };

    private XpChartRenderer() {}

    /** {@code null} if there are fewer than 2 points (nothing to draw a trend between) or rendering fails. */
    public static FileUpload render(String rsn, String skillName, List<SkillXpPoint> points) {
        if (points.size() < 2) return null;

        List<Date> xData = points.stream().map(p -> Date.from(p.timestamp().toInstant())).toList();
        List<Long> yData = points.stream().map(SkillXpPoint::xp).toList();

        XYChart chart = new XYChartBuilder()
                .width(700).height(400)
                .title(rsn + " — " + skillName + " XP")
                .xAxisTitle("Date")
                .yAxisTitle("XP")
                .build();

        var styler = chart.getStyler();
        styler.setChartBackgroundColor(BACKGROUND);
        styler.setPlotBackgroundColor(BACKGROUND);
        styler.setLegendBackgroundColor(BACKGROUND);
        styler.setPlotBorderVisible(false);
        styler.setLegendVisible(false);
        styler.setChartFontColor(TEXT);
        styler.setAxisTickLabelsColor(TEXT);
        styler.setPlotGridLinesColor(GRID);
        styler.setChartTitleFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        styler.setAxisTickLabelsFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        styler.setDatePattern("MMM d");
        styler.setYAxisDecimalPattern("#,###");
        styler.setPlotMargin(10);
        styler.setPlotContentSize(0.95);

        XYSeries series = chart.addSeries(skillName, xData, yData);
        series.setMarker(SeriesMarkers.NONE);
        series.setLineColor(LINE);
        series.setLineStyle(new BasicStroke(2.5f));

        try {
            var image = BitmapEncoder.getBufferedImage(chart);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", out);
            return FileUpload.fromData(out.toByteArray(), "xp_chart.png");
        } catch (IOException e) {
            log.warn("Failed to render XP chart for '{}' skill '{}'", rsn, skillName, e);
            return null;
        }
    }

    /** {@code null} if fewer than one player has at least 2 snapshots (nothing worth plotting). */
    public static FileUpload renderMultiPlayer(Map<String, List<PlayerLinkRepository.StatsSnapshotRow>> historyByRsn) {
        Map<String, List<PlayerLinkRepository.StatsSnapshotRow>> plottable = new LinkedHashMap<>();
        for (var entry : historyByRsn.entrySet()) {
            if (entry.getValue().size() >= 2) plottable.put(entry.getKey(), entry.getValue());
        }
        if (plottable.isEmpty()) return null;

        XYChart chart = new XYChartBuilder()
                .width(800).height(450)
                .title("Guild XP Trend")
                .xAxisTitle("Date")
                .yAxisTitle("Total XP")
                .build();

        var styler = chart.getStyler();
        styler.setChartBackgroundColor(BACKGROUND);
        styler.setPlotBackgroundColor(BACKGROUND);
        styler.setLegendBackgroundColor(BACKGROUND);
        styler.setPlotBorderVisible(false);
        styler.setLegendVisible(true);
        styler.setLegendFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        styler.setChartFontColor(TEXT);
        styler.setAxisTickLabelsColor(TEXT);
        styler.setPlotGridLinesColor(GRID);
        styler.setChartTitleFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        styler.setAxisTickLabelsFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        styler.setDatePattern("MMM d");
        styler.setYAxisDecimalPattern("#,###");
        styler.setPlotMargin(10);
        styler.setSeriesColors(SERIES_COLORS);

        for (var entry : plottable.entrySet()) {
            List<Date> xData = entry.getValue().stream().map(r -> Date.from(r.snapshotAt().toInstant())).toList();
            List<Long> yData = entry.getValue().stream().map(PlayerLinkRepository.StatsSnapshotRow::totalXp).toList();
            XYSeries series = chart.addSeries(entry.getKey(), xData, yData);
            series.setMarker(SeriesMarkers.NONE);
            series.setLineStyle(new BasicStroke(2.5f));
        }

        try {
            var image = BitmapEncoder.getBufferedImage(chart);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", out);
            return FileUpload.fromData(out.toByteArray(), "guild_xp_trend.png");
        } catch (IOException e) {
            log.warn("Failed to render guild XP trend chart", e);
            return null;
        }
    }

    /**
     * One line per entry in {@code seriesByLabel} — the multi-skill-overlay/"Overall" generalization
     * of {@link #render}, sharing the same styling. {@code null} if none has at least 2 points.
     */
    public static FileUpload renderLines(String rsn, String titleSuffix, Map<String, List<SkillXpPoint>> seriesByLabel) {
        Map<String, List<SkillXpPoint>> plottable = new LinkedHashMap<>();
        for (var entry : seriesByLabel.entrySet()) {
            if (entry.getValue().size() >= 2) plottable.put(entry.getKey(), entry.getValue());
        }
        if (plottable.isEmpty()) return null;

        XYChart chart = new XYChartBuilder()
                .width(850).height(500)
                .title(rsn + " — " + titleSuffix)
                .xAxisTitle("Date")
                .yAxisTitle("XP")
                .build();

        var styler = chart.getStyler();
        styler.setChartBackgroundColor(BACKGROUND);
        styler.setPlotBackgroundColor(BACKGROUND);
        styler.setLegendBackgroundColor(BACKGROUND);
        styler.setPlotBorderVisible(false);
        styler.setLegendVisible(plottable.size() > 1);
        styler.setLegendFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        styler.setChartFontColor(TEXT);
        styler.setAxisTickLabelsColor(TEXT);
        styler.setPlotGridLinesColor(GRID);
        styler.setChartTitleFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        styler.setAxisTickLabelsFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        styler.setDatePattern("MMM d");
        styler.setYAxisDecimalPattern("#,###");
        styler.setPlotMargin(10);
        styler.setSeriesColors(SERIES_COLORS);

        for (var entry : plottable.entrySet()) {
            List<Date> xData = entry.getValue().stream().map(p -> Date.from(p.timestamp().toInstant())).toList();
            List<Long> yData = entry.getValue().stream().map(SkillXpPoint::xp).toList();
            XYSeries series = chart.addSeries(entry.getKey(), xData, yData);
            series.setMarker(SeriesMarkers.NONE);
            series.setLineStyle(new BasicStroke(2.5f));
        }

        return encodePng(chart, "xp_chart.png");
    }

    /**
     * One stacked segment per entry in {@code seriesByLabel}, one bar per distinct poll timestamp
     * across all of them — "how much of the total each skill contributes, day by day." {@code null}
     * if there's nothing to plot.
     */
    public static FileUpload renderStackedBar(String rsn, Map<String, List<SkillXpPoint>> seriesByLabel) {
        TreeSet<java.time.OffsetDateTime> allTimestamps = new TreeSet<>();
        for (var points : seriesByLabel.values()) {
            for (var point : points) allTimestamps.add(point.timestamp());
        }
        if (allTimestamps.isEmpty() || seriesByLabel.isEmpty()) return null;

        DateTimeFormatter dayFormat = DateTimeFormatter.ofPattern("MMM d");
        List<String> dayLabels = allTimestamps.stream().map(dayFormat::format).toList();

        CategoryChart chart = new CategoryChartBuilder()
                .width(900).height(550)
                .title(rsn + " — XP by Skill")
                .xAxisTitle("Date")
                .yAxisTitle("XP")
                .build();

        var styler = chart.getStyler();
        styler.setChartBackgroundColor(BACKGROUND);
        styler.setPlotBackgroundColor(BACKGROUND);
        styler.setLegendBackgroundColor(BACKGROUND);
        styler.setPlotBorderVisible(false);
        styler.setLegendVisible(true);
        styler.setLegendFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        styler.setChartFontColor(TEXT);
        styler.setAxisTickLabelsColor(TEXT);
        styler.setPlotGridLinesColor(GRID);
        styler.setChartTitleFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        styler.setAxisTickLabelsFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        styler.setYAxisDecimalPattern("#,###");
        styler.setXAxisLabelRotation(45);
        styler.setStacked(true);
        styler.setSeriesColors(SERIES_COLORS);

        for (var entry : seriesByLabel.entrySet()) {
            Map<java.time.OffsetDateTime, Long> xpByTimestamp = new LinkedHashMap<>();
            for (var point : entry.getValue()) xpByTimestamp.put(point.timestamp(), point.xp());

            List<Long> yData = new ArrayList<>();
            for (var timestamp : allTimestamps) yData.add(xpByTimestamp.getOrDefault(timestamp, 0L));
            chart.addSeries(entry.getKey(), dayLabels, yData);
        }

        return encodePng(chart, "xp_stacked_bar.png");
    }

    /**
     * Each entry's share of the total as a pie (or donut, per {@code donut}) — reuses the same
     * donut-chart approach as {@link MonthlyRecapRenderer}. {@code null} if nothing to plot (every
     * value is zero, or the map is empty).
     */
    public static FileUpload renderPieOrDonut(String rsn, String title, Map<String, Long> xpByLabel, boolean donut) {
        Map<String, Long> filtered = new LinkedHashMap<>();
        for (var entry : xpByLabel.entrySet()) {
            if (entry.getValue() > 0) filtered.put(entry.getKey(), entry.getValue());
        }
        if (filtered.isEmpty()) return null;

        PieChart chart = new PieChartBuilder()
                .width(900).height(900)
                .title(rsn + " — " + title)
                .build();

        PieStyler styler = chart.getStyler();
        styler.setChartBackgroundColor(BACKGROUND);
        styler.setPlotBackgroundColor(BACKGROUND);
        styler.setChartFontColor(TEXT);
        styler.setChartTitleFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        // The actual pie/donut toggle — donutThickness alone does nothing (verified live against
        // this exact version: a 0.7 thickness rendered as a plain, hole-less pie) unless the series
        // render style is explicitly switched too.
        styler.setDefaultSeriesRenderStyle(donut ? PieSeries.PieSeriesRenderStyle.Donut : PieSeries.PieSeriesRenderStyle.Pie);
        styler.setDonutThickness(0.35);
        // Labels carry the name themselves, drawn on the ring/wedge — no separate legend needed,
        // which was eating the right-hand third of the image for nothing at the old, smaller size.
        styler.setLegendVisible(false);
        styler.setLabelType(PieStyler.LabelType.NameAndPercentage);
        styler.setLabelsFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        styler.setForceAllLabelsVisible(true);
        // Shrinks the pie/donut itself so labels extending past its outer edge land inside the
        // canvas instead of clipping — verified live: at the default content size, the leftmost and
        // rightmost labels ran off the image edge.
        styler.setPlotContentSize(0.62);
        styler.setLabelsDistance(0.85);
        styler.setPlotBorderVisible(false);
        styler.setSumVisible(false);
        styler.setSeriesColors(SERIES_COLORS);

        for (var entry : filtered.entrySet()) {
            chart.addSeries(entry.getKey(), entry.getValue());
        }

        return encodePng(chart, donut ? "xp_donut.png" : "xp_pie.png");
    }

    private static FileUpload encodePng(Chart<?, ?> chart, String filename) {
        try {
            var image = BitmapEncoder.getBufferedImage(chart);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", out);
            return FileUpload.fromData(out.toByteArray(), filename);
        } catch (IOException e) {
            log.warn("Failed to encode chart '{}'", filename, e);
            return null;
        }
    }
}
