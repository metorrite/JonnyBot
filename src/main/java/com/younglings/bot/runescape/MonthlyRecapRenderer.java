package com.younglings.bot.runescape;

import net.dv8tion.jda.api.utils.FileUpload;
import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.PieChart;
import org.knowm.xchart.PieChartBuilder;
import org.knowm.xchart.style.PieStyler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws one player's month-to-date recap as a single PNG — a plain {@link Graphics2D} canvas for
 * the header/stat tiles, with an {@link org.knowm.xchart.PieChart} (donut style) composited in for
 * the skill XP distribution. Static image, not an interactive dashboard: Discord's Components V2
 * has no live chart primitive, same constraint as {@link XpChartRenderer}.
 */
public final class MonthlyRecapRenderer {
    private static final Logger log = LoggerFactory.getLogger(MonthlyRecapRenderer.class);

    private static final int WIDTH = 800;
    private static final int HEIGHT = 760;
    private static final Color BACKGROUND = new Color(0x1e, 0x1f, 0x22);
    private static final Color TILE_BACKGROUND = new Color(0x2b, 0x2d, 0x31);
    private static final Color TEXT = new Color(0xdb, 0xdd, 0xde);
    private static final Color MUTED = new Color(0x94, 0x96, 0x9c);
    private static final Color ACCENT = Color.ORANGE;
    // 8 distinct hues + a neutral gray for "Other" — assigned by gain-rank, not skill identity,
    // since the set of top skills (and slice count) differs every time this renders.
    private static final Color[] SLICE_COLORS = {
            new Color(0xED, 0x42, 0x45), new Color(0xF5, 0x9E, 0x0B), new Color(0xFB, 0xBF, 0x24),
            new Color(0x22, 0xC5, 0x5E), new Color(0x06, 0xB6, 0xD4), new Color(0x3B, 0x82, 0xF6),
            new Color(0x8B, 0x5C, 0xF6), new Color(0xEC, 0x48, 0x99), new Color(0x6B, 0x72, 0x80)
    };
    private static final int MAX_DONUT_SLICES = 8;

    private MonthlyRecapRenderer() {}

    /** {@code clanIconBytes} may be {@code null} — the header just skips the icon and shifts the title left. */
    public static FileUpload render(MonthlyRecapStats stats, byte[] clanIconBytes) {
        BufferedImage canvas = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g.setColor(BACKGROUND);
        g.fillRect(0, 0, WIDTH, HEIGHT);

        drawHeader(g, stats, clanIconBytes);
        drawStatTiles(g, stats);
        drawDonut(g, stats);
        g.dispose();

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(canvas, "png", out);
            return FileUpload.fromData(out.toByteArray(), "monthly_recap.png");
        } catch (IOException e) {
            log.warn("Failed to encode monthly recap image for '{}'", stats.rsn(), e);
            return null;
        }
    }

    private static void drawHeader(Graphics2D g, MonthlyRecapStats stats, byte[] clanIconBytes) {
        int iconSize = 70;
        int textX = 30;

        if (clanIconBytes != null) {
            try {
                BufferedImage icon = ImageIO.read(new ByteArrayInputStream(clanIconBytes));
                if (icon != null) {
                    Graphics2D clipped = (Graphics2D) g.create();
                    clipped.setClip(new Ellipse2D.Float(30, 20, iconSize, iconSize));
                    clipped.drawImage(icon, 30, 20, iconSize, iconSize, null);
                    clipped.dispose();
                    textX = 30 + iconSize + 20;
                }
            } catch (IOException e) {
                log.warn("Failed to decode clan icon for monthly recap", e);
            }
        }

        g.setColor(TEXT);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
        g.drawString(stats.rsn() + " — " + stats.monthLabel() + " Recap", textX, 55);

        g.setColor(MUTED);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MMM d");
        g.drawString(fmt.format(stats.rangeStart()) + " – " + fmt.format(stats.rangeEnd()) + " (month-to-date)", textX, 80);
    }

    private static void drawStatTiles(Graphics2D g, MonthlyRecapStats stats) {
        int y = 120;
        int tileHeight = 100;
        int tileWidth = 240;
        int gap = 20;
        int x0 = 30;

        drawTile(g, x0, y, tileWidth, tileHeight, "XP GAINED", String.format("%,d", stats.totalXpGained()));
        drawTile(g, x0 + tileWidth + gap, y, tileWidth, tileHeight, "TIMES CAPPED", String.valueOf(stats.timesCapped()));

        String mostChallengedValue = stats.mostChallenged() != null
                ? stats.mostChallenged() + " (" + stats.mostChallengedCount() + ")"
                : "None yet";
        drawTile(g, x0 + (tileWidth + gap) * 2, y, tileWidth, tileHeight, "MOST CHALLENGED", mostChallengedValue);
    }

    private static void drawTile(Graphics2D g, int x, int y, int w, int h, String label, String value) {
        g.setColor(TILE_BACKGROUND);
        g.fill(new RoundRectangle2D.Float(x, y, w, h, 16, 16));

        g.setColor(MUTED);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        g.drawString(label, x + 16, y + 28);

        g.setColor(ACCENT);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fittingFontSize(g, value, w - 32, 26)));
        g.drawString(value, x + 16, y + 66);
    }

    /** Shrinks the font until {@code text} fits {@code maxWidth} — a long boss name shouldn't spill out of its tile. */
    private static int fittingFontSize(Graphics2D g, String text, int maxWidth, int startSize) {
        int size = startSize;
        while (size > 12) {
            Font font = new Font(Font.SANS_SERIF, Font.BOLD, size);
            if (g.getFontMetrics(font).stringWidth(text) <= maxWidth) break;
            size -= 2;
        }
        return size;
    }

    private static void drawDonut(Graphics2D g, MonthlyRecapStats stats) {
        List<Map.Entry<Integer, Long>> sorted = stats.skillXpGained().entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<Integer, Long>comparingByValue().reversed())
                .toList();

        if (sorted.isEmpty()) {
            g.setColor(MUTED);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
            g.drawString("No XP gained yet this month.", 30, 280);
            return;
        }

        Map<String, Long> slices = new LinkedHashMap<>();
        long otherTotal = 0;
        for (int i = 0; i < sorted.size(); i++) {
            var entry = sorted.get(i);
            if (i < MAX_DONUT_SLICES) {
                slices.put(RuneScapeSkillCatalog.nameFor(entry.getKey()), entry.getValue());
            } else {
                otherTotal += entry.getValue();
            }
        }
        if (otherTotal > 0) slices.put("Other", otherTotal);

        PieChart chart = new PieChartBuilder()
                .width(740).height(460)
                .title("XP Distribution This Month")
                .build();

        PieStyler styler = chart.getStyler();
        styler.setChartBackgroundColor(BACKGROUND);
        styler.setPlotBackgroundColor(BACKGROUND);
        styler.setLegendBackgroundColor(BACKGROUND);
        styler.setChartFontColor(TEXT);
        styler.setLegendFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        styler.setChartTitleFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        styler.setDonutThickness(0.35);
        styler.setLabelType(PieStyler.LabelType.Percentage);
        styler.setLabelsFontColor(Color.WHITE);
        styler.setPlotBorderVisible(false);
        styler.setSumVisible(false);
        styler.setSeriesColors(SLICE_COLORS);

        for (var entry : slices.entrySet()) {
            chart.addSeries(entry.getKey(), entry.getValue());
        }

        try {
            g.drawImage(BitmapEncoder.getBufferedImage(chart), 30, 250, null);
        } catch (Exception e) {
            log.warn("Failed to render donut chart for monthly recap", e);
        }
    }
}
