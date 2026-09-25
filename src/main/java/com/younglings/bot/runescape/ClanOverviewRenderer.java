package com.younglings.bot.runescape;

import net.dv8tion.jda.api.utils.FileUpload;
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
import java.util.List;

/**
 * Draws the whole clan's stats as one PNG — a leaderboard bar chart (hand-drawn, not XChart: this
 * version of XChart's CategoryChart has no horizontal-bar option, and a leaderboard of RSNs reads
 * far better as horizontal bars than as vertical ones with rotated name labels) plus stat tiles for
 * everything that doesn't fit naturally into a per-member bar. Same static-image constraint as
 * {@link XpChartRenderer} / {@link MonthlyRecapRenderer} — Discord has no live chart component.
 */
public final class ClanOverviewRenderer {
    private static final Logger log = LoggerFactory.getLogger(ClanOverviewRenderer.class);

    private static final int WIDTH = 900;
    private static final Color BACKGROUND = new Color(0x1e, 0x1f, 0x22);
    private static final Color TILE_BACKGROUND = new Color(0x2b, 0x2d, 0x31);
    private static final Color TEXT = new Color(0xdb, 0xdd, 0xde);
    private static final Color MUTED = new Color(0x94, 0x96, 0x9c);
    private static final Color ACCENT = Color.ORANGE;

    private static final int BAR_HEIGHT = 26;
    private static final int BAR_GAP = 10;
    private static final int BAR_LABEL_WIDTH = 150;

    private ClanOverviewRenderer() {}

    public static FileUpload render(String clanName, ClanOverviewService.ClanOverviewStats stats, byte[] clanIconBytes) {
        int leaderboardRows = stats.topByTotalXp().size();
        int headerHeight = 100;
        int tileRowHeight = 100;
        int tileRowGap = 20;
        int leaderboardTitleHeight = 40;
        int leaderboardHeight = leaderboardRows * (BAR_HEIGHT + BAR_GAP);
        int height = headerHeight + 2 * tileRowHeight + tileRowGap + leaderboardTitleHeight + leaderboardHeight + 40;

        BufferedImage canvas = new BufferedImage(WIDTH, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g.setColor(BACKGROUND);
        g.fillRect(0, 0, WIDTH, height);

        drawHeader(g, clanName, stats, clanIconBytes);

        int tileRowY = headerHeight;
        drawTileRow(g, tileRowY,
                new String[]{"MEMBERS TRACKED", "VERIFIED", "COMBINED XP", "XP GAINED (MTD)"},
                new String[]{
                        String.valueOf(stats.totalMembers()),
                        stats.verifiedMembers() + " / " + stats.totalMembers(),
                        String.format("%,d", stats.totalCombinedXp()),
                        String.format("%,d", stats.totalXpGainedThisMonth())
                });

        drawTileRow(g, tileRowY + tileRowHeight + tileRowGap,
                new String[]{"QUESTS COMPLETE", "HAVE A MAXED SKILL", "HAVE A 120 SKILL", "MOST CHALLENGED"},
                new String[]{
                        String.format("%,d", stats.totalQuestsComplete()),
                        String.valueOf(stats.membersWithMaxedSkill()),
                        String.valueOf(stats.membersWith120Skill()),
                        stats.mostChallenged() != null ? stats.mostChallenged() + " (" + stats.mostChallengedCount() + ")" : "None yet"
                });

        int leaderboardY = tileRowY + 2 * tileRowHeight + tileRowGap;
        drawLeaderboard(g, leaderboardY, stats.topByTotalXp());

        g.dispose();

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(canvas, "png", out);
            return FileUpload.fromData(out.toByteArray(), "clan_overview.png");
        } catch (IOException e) {
            log.warn("Failed to encode clan overview image", e);
            return null;
        }
    }

    private static void drawHeader(Graphics2D g, String clanName, ClanOverviewService.ClanOverviewStats stats, byte[] clanIconBytes) {
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
                log.warn("Failed to decode clan icon for clan overview", e);
            }
        }

        g.setColor(TEXT);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
        g.drawString(clanName + " — Clan Overview", textX, 55);

        g.setColor(MUTED);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        g.drawString(stats.membersWithData() + " of " + stats.totalMembers() + " tracked member(s) have synced stats", textX, 80);
    }

    private static void drawTileRow(Graphics2D g, int y, String[] labels, String[] values) {
        int gap = 20;
        int x0 = 30;
        int tileWidth = (WIDTH - 2 * x0 - gap * (labels.length - 1)) / labels.length;

        for (int i = 0; i < labels.length; i++) {
            int x = x0 + i * (tileWidth + gap);
            drawTile(g, x, y, tileWidth, labels[i], values[i]);
        }
    }

    private static void drawTile(Graphics2D g, int x, int y, int w, String label, String value) {
        g.setColor(TILE_BACKGROUND);
        g.fill(new RoundRectangle2D.Float(x, y, w, 100, 16, 16));

        g.setColor(MUTED);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        g.drawString(label, x + 14, y + 26);

        g.setColor(ACCENT);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fittingFontSize(g, value, w - 28, 22)));
        g.drawString(value, x + 14, y + 62);
    }

    private static int fittingFontSize(Graphics2D g, String text, int maxWidth, int startSize) {
        int size = startSize;
        while (size > 11) {
            Font font = new Font(Font.SANS_SERIF, Font.BOLD, size);
            if (g.getFontMetrics(font).stringWidth(text) <= maxWidth) break;
            size -= 1;
        }
        return size;
    }

    private static void drawLeaderboard(Graphics2D g, int startY, List<ClanOverviewService.MemberTotal> top) {
        g.setColor(TEXT);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        g.drawString("Top Members by Total XP", 30, startY + 20);

        if (top.isEmpty()) {
            g.setColor(MUTED);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
            g.drawString("No synced stats yet — run Sync Clan first.", 30, startY + 50);
            return;
        }

        long maxValue = top.getFirst().value();
        int barAreaTop = startY + 35;
        int barMaxWidth = WIDTH - 30 - BAR_LABEL_WIDTH - 130;

        for (int i = 0; i < top.size(); i++) {
            ClanOverviewService.MemberTotal entry = top.get(i);
            int y = barAreaTop + i * (BAR_HEIGHT + BAR_GAP);

            g.setColor(TEXT);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
            g.drawString((i + 1) + ". " + entry.rsn(), 30, y + BAR_HEIGHT - 8);

            int barWidth = maxValue > 0 ? Math.max(4, (int) (barMaxWidth * (entry.value() / (double) maxValue))) : 4;
            g.setColor(ACCENT);
            g.fill(new RoundRectangle2D.Float(30 + BAR_LABEL_WIDTH, y, barWidth, BAR_HEIGHT, 8, 8));

            g.setColor(MUTED);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
            g.drawString(String.format("%,d", entry.value()), 30 + BAR_LABEL_WIDTH + barWidth + 10, y + BAR_HEIGHT - 8);
        }
    }
}
