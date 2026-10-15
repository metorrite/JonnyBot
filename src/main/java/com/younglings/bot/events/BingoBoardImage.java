package com.younglings.bot.events;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws a bingo board as a PNG: a square grid of numbered tiles, finished tiles filled and ticked. Pure Java2D with no Discord in it, so it can be
 * tested and reused (the live board message, a player's own board). A tile shows its number (what players type to submit proof for it), its text
 * (a "Boss: item" label is split so the boss reads small above the item), its points, and, on a shared board, how many have completed it.
 */
public final class BingoBoardImage {
    private static final Color BACKGROUND = new Color(0x15171c);
    private static final Color TILE = new Color(0x23262e);
    private static final Color TILE_BORDER = new Color(0x343845);
    private static final Color DONE = new Color(0x245c3d);
    private static final Color GOLD = new Color(0xd4af37);
    private static final Color TEXT = new Color(0xeceff4);
    private static final Color MUTED = new Color(0x9aa3b2);

    /**
     * One tile as drawn. {@code count} of {@code of} have completed it on a shared board; {@code of} 0 means a personal board with no counts.
     * {@code iconKey} names the item picture to draw above the text (see {@link BingoTiles}), or is null for a tile without one.
     */
    public record TileView(int number, String label, long points, boolean done, int count, int of, String iconKey) {
        public TileView(int number, String label, long points, boolean done, int count, int of) {
            this(number, label, points, done, count, of, null);
        }
    }

    private BingoBoardImage() {}

    /** The side of one tile in pixels for a board of this size: bigger boards get smaller tiles so the picture stays a sensible size. */
    static int tileSize(int boardSize) {
        return switch (boardSize) {
            case 3 -> 200;
            case 4 -> 172;
            case 5 -> 150;
            case 6 -> 132;
            default -> 118;
        };
    }

    public static byte[] render(String title, String subtitle, int size, List<TileView> tiles, String footer) throws IOException {
        if (tiles.size() != size * size) throw new IllegalArgumentException("A " + size + "x" + size + " board needs " + size * size + " tiles.");
        int tile = tileSize(size);
        int gap = 8;
        int pad = 20;
        int header = 64;
        int footerHeight = footer == null || footer.isBlank() ? 12 : 40;
        int width = pad * 2 + size * tile + (size - 1) * gap;
        int height = header + size * tile + (size - 1) * gap + footerHeight + pad;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(BACKGROUND);
            g.fillRect(0, 0, width, height);

            g.setColor(TEXT);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 24));
            g.drawString(fit(g, title, width - pad * 2), pad, 38);
            if (subtitle != null && !subtitle.isBlank()) {
                g.setColor(MUTED);
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
                g.drawString(fit(g, subtitle, width - pad * 2), pad, 58);
            }

            for (int i = 0; i < tiles.size(); i++) {
                int x = pad + (i % size) * (tile + gap);
                int y = header + (i / size) * (tile + gap);
                drawTile(g, tiles.get(i), x, y, tile, size);
            }

            if (footer != null && !footer.isBlank()) {
                g.setColor(MUTED);
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
                g.drawString(fit(g, footer, width - pad * 2), pad, header + size * tile + (size - 1) * gap + 26);
            }
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static void drawTile(Graphics2D g, TileView t, int x, int y, int tile, int boardSize) {
        RoundRectangle2D shape = new RoundRectangle2D.Float(x, y, tile, tile, 14, 14);
        g.setColor(t.done() ? DONE : TILE);
        g.fill(shape);
        g.setStroke(new BasicStroke(t.done() ? 2.5f : 1.5f));
        g.setColor(t.done() ? GOLD : TILE_BORDER);
        g.draw(shape);

        int fontSize = switch (boardSize) {
            case 3 -> 19;
            case 4 -> 16;
            case 5 -> 14;
            case 6 -> 13;
            default -> 12;
        };
        int inner = tile - 20;

        // tile number, top left: what a player types to submit proof for this tile
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(11, fontSize - 2)));
        g.setColor(t.done() ? GOLD : MUTED);
        g.drawString(Integer.toString(t.number()), x + 10, y + 8 + g.getFontMetrics().getAscent() - 2);

        // a "Boss: item" label reads as a small boss name above the item
        String top = null;
        String main = t.label();
        int colon = t.label().indexOf(": ");
        if (colon > 0 && colon < t.label().length() - 2) {
            top = t.label().substring(0, colon);
            main = t.label().substring(colon + 2);
        }

        Font mainFont = new Font(Font.SANS_SERIF, Font.BOLD, fontSize);
        Font topFont = new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(10, fontSize - 3));
        g.setFont(mainFont);
        BufferedImage icon = BingoTiles.icon(t.iconKey()).orElse(null);
        int iconBox = icon == null ? 0 : Math.round(tile * 0.4f);
        List<String> lines = wrap(g.getFontMetrics(), main, inner, (top == null ? 5 : 4) - (icon == null ? 0 : 2));
        FontMetrics mm = g.getFontMetrics(mainFont);
        FontMetrics tm = g.getFontMetrics(topFont);
        int lineHeight = mm.getHeight();
        int blockHeight = lines.size() * lineHeight + (top == null ? 0 : tm.getHeight() + 2) + (icon == null ? 0 : iconBox + 6);
        int cy = y + (tile - blockHeight) / 2 + 4;
        if (icon != null) {
            // the wiki's inventory icons are about 30 pixels: whole-number scaling with no smoothing keeps them crisp
            int scale = Math.max(1, Math.min(iconBox / Math.max(1, icon.getWidth()), iconBox / Math.max(1, icon.getHeight())));
            int w = icon.getWidth() * scale;
            int h = icon.getHeight() * scale;
            Object previous = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(icon, x + (tile - w) / 2, cy + (iconBox - h) / 2, w, h, null);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, previous == null ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : previous);
            cy += iconBox + 6;
        }
        if (top != null) {
            g.setFont(topFont);
            g.setColor(MUTED);
            String shown = fit(g, top, inner);
            g.drawString(shown, x + (tile - tm.stringWidth(shown)) / 2, cy + tm.getAscent());
            cy += tm.getHeight() + 2;
        }
        g.setFont(mainFont);
        g.setColor(TEXT);
        for (String line : lines) {
            g.drawString(line, x + (tile - mm.stringWidth(line)) / 2, cy + mm.getAscent());
            cy += lineHeight;
        }

        // points bottom right, completions bottom left
        Font small = new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(10, fontSize - 3));
        g.setFont(small);
        FontMetrics sm = g.getFontMetrics();
        g.setColor(t.done() ? GOLD : MUTED);
        String points = t.points() + " pts";
        g.drawString(points, x + tile - 10 - sm.stringWidth(points), y + tile - 9);
        if (t.of() > 0) {
            g.setColor(MUTED);
            g.drawString(t.count() + "/" + t.of(), x + 10, y + tile - 9);
        }

        if (t.done()) drawTick(g, x + tile - 26, y + 12, 16);
    }

    private static void drawTick(Graphics2D g, int x, int y, int s) {
        g.setColor(GOLD);
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(x, y + s / 2, x + s / 3, y + s - 2);
        g.drawLine(x + s / 3, y + s - 2, x + s, y + 1);
    }

    /** Breaks {@code text} into lines that fit {@code width}, at most {@code maxLines}, ending the last with an ellipsis if it had to be cut. */
    static List<String> wrap(FontMetrics metrics, String text, int width, int maxLines) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        String[] words = text.strip().split("\\s+");
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            // a single word wider than the tile is cut down to fit
            while (metrics.stringWidth(word) > width && word.length() > 1) word = word.substring(0, word.length() - 1);
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (metrics.stringWidth(candidate) <= width) {
                line = new StringBuilder(candidate);
                continue;
            }
            lines.add(line.toString());
            line = new StringBuilder(word);
            if (lines.size() == maxLines - 1) {
                // the rest goes on the last line, shortened
                StringBuilder rest = new StringBuilder(word);
                for (int j = i + 1; j < words.length; j++) rest.append(' ').append(words[j]);
                lines.add(ellipsize(metrics, rest.toString(), width));
                return lines;
            }
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
    }

    private static String ellipsize(FontMetrics metrics, String text, int width) {
        if (metrics.stringWidth(text) <= width) return text;
        String cut = text;
        while (cut.length() > 1 && metrics.stringWidth(cut + "...") > width) cut = cut.substring(0, cut.length() - 1);
        return cut.stripTrailing() + "...";
    }

    private static String fit(Graphics2D g, String text, int width) {
        return ellipsize(g.getFontMetrics(), text, width);
    }
}
