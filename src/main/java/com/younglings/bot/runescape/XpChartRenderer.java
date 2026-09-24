package com.younglings.bot.runescape;

import net.dv8tion.jda.api.utils.FileUpload;
import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.XYChart;
import org.knowm.xchart.XYChartBuilder;
import org.knowm.xchart.XYSeries;
import org.knowm.xchart.style.markers.SeriesMarkers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Date;
import java.util.List;

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
}
