package com.younglings.bot.events;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
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
    public record TileView(int number, String label, long points, boolean done, int count, int of, String iconKey, String by, boolean locked) {
        public TileView(int number, String label, long points, boolean done, int count, int of) {
            this(number, label, points, done, count, of, null, null, false);
        }

        public TileView(int number, String label, long points, boolean done, int count, int of, String iconKey) {
            this(number, label, points, done, count, of, iconKey, null, false);
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

    // ---------- the picture shapes ----------

    private static final Color INSET_FALLBACK = new Color(0x0a1015);
    private static final Color CENTRE = new Color(0x3b2230);
    private static final Color COMPLETE = new Color(0x35ce72);
    private static final Color BADGE = new Color(0xc7a35f);

    /**
     * Draws a board in one of the picture shapes: the shape's own picture with each tile's place filled in. The picture has printed placeholders
     * ("ITEM ICON", "PLAYER NAME") that are covered first, using the colour the picture itself has there. A finished tile is tinted green, shows
     * its item picture or its text, and under it who completed it first (and how many others did too); a tile not finished shows what it is
     * worth. On a personal board a tile that hasn't opened yet is dimmed. {@code tiles} are in the picture's numbering.
     */
    public static byte[] render(BingoLayout layout, List<TileView> tiles) throws IOException {
        if (tiles.size() != layout.tiles()) throw new IllegalArgumentException("The " + layout.name() + " board needs " + layout.tiles() + " tiles.");
        BufferedImage base = layout.image();
        BufferedImage image = new BufferedImage(base.getWidth(), base.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.drawImage(base, 0, 0, null);
            for (int i = 0; i < tiles.size(); i++) drawPlace(g, base, layout.slots().get(i), tiles.get(i));
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static Color colourAt(BufferedImage base, int x, int y, Color fallback) {
        if (x < 0 || y < 0 || x >= base.getWidth() || y >= base.getHeight()) return fallback;
        Color c = new Color(base.getRGB(x, y));
        return c.getAlpha() < 255 ? fallback : c;
    }

    private static void drawPlace(Graphics2D g, BufferedImage base, BingoLayout.Slot slot, TileView t) {
        int[] b = slot.bounds();
        int[] ib = slot.icon();
        int[] nb = slot.name();
        Shape shape = slot.round() ? new Ellipse2D.Float(b[0] + 6, b[1] + 6, b[2] - 12, b[3] - 12) : new RoundRectangle2D.Float(b[0], b[1], b[2], b[3], 18, 18);

        // cover what the picture printed there
        if (slot.round()) {
            g.setColor(CENTRE);
            g.fill(shape);
        } else {
            g.setColor(colourAt(base, ib[0] + 8, ib[1] + 8, INSET_FALLBACK));
            g.fillRoundRect(ib[0] - 3, ib[1] - 3, ib[2] + 6, ib[3] + 6, 8, 8);
            g.setColor(colourAt(base, nb[0] + 5, nb[1] + 9, INSET_FALLBACK));
            g.fillRoundRect(nb[0] - 1, nb[1] - 1, nb[2] + 2, nb[3] + 2, 6, 6);
        }
        if (t.done()) {
            g.setColor(new Color(COMPLETE.getRed(), COMPLETE.getGreen(), COMPLETE.getBlue(), 70));
            g.fill(shape);
        }

        drawLabel(g, ib, t, slot.round());
        drawUnder(g, nb, t);
        if (t.done()) drawTick(g, b[0] + b[2] - (slot.round() ? 36 : 26), b[1] + (slot.round() ? 16 : 10), 14);
        if (slot.round()) {
            // the centre has no printed number badge like the other places, so one is drawn on the circle's edge
            int cx = b[0] + b[2] / 2 - Math.round(b[2] * 0.354f);
            int cy = b[1] + b[3] / 2 - Math.round(b[3] * 0.354f);
            g.setColor(BADGE);
            g.fill(new Ellipse2D.Float(cx - 12, cy - 12, 24, 24));
            g.setColor(new Color(0x1b1b1b));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
            String number = Integer.toString(t.number());
            FontMetrics nm = g.getFontMetrics();
            g.drawString(number, cx - nm.stringWidth(number) / 2, cy + (nm.getAscent() - nm.getDescent()) / 2);
        }
        if (t.locked() && !t.done()) {
            // dimmed, but the number badge stays readable: it is what a player types to submit proof for the tile
            Area dim = new Area(shape);
            dim.subtract(new Area(new Ellipse2D.Float(b[0] - 4, b[1] - 4, 27, 27)));
            g.setColor(new Color(6, 10, 14, 135));
            g.fill(dim);
        }
    }

    /** The tile's picture and name inside {@code box}: the item picture above two lines of text, or only text when there is no picture. */
    private static void drawLabel(Graphics2D g, int[] box, TileView t, boolean round) {
        String label = t.label();
        BufferedImage icon = BingoTiles.icon(t.iconKey()).orElse(null);
        // with a picture the boss is dropped from "Boss: item" to leave room; without one the whole label is the only clue
        int colon = label.indexOf(": ");
        String main = icon != null && colon > 0 && colon < label.length() - 2 ? label.substring(colon + 2) : label;
        Font font = new Font(Font.SANS_SERIF, Font.BOLD, icon != null ? 10 : 12);
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics(font);
        int lineHeight = fm.getHeight() - 1;
        int maxLines = icon != null ? 2 : 5;
        List<String> lines = wrap(fm, main, box[2], maxLines);
        int iconHeight = icon == null ? 0 : Math.min(round ? 28 : 34, box[3] - lines.size() * lineHeight - 2);
        int blockHeight = iconHeight + (icon == null ? 0 : 2) + lines.size() * lineHeight;
        int y = box[1] + Math.max(0, (box[3] - blockHeight) / 2);
        if (icon != null && iconHeight > 8) {
            int scale = Math.max(1, Math.min(iconHeight / Math.max(1, icon.getHeight()), box[2] / Math.max(1, icon.getWidth())));
            int w = icon.getWidth() * scale;
            int h = icon.getHeight() * scale;
            Object previous = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(icon, box[0] + (box[2] - w) / 2, y + (iconHeight - h) / 2, w, h, null);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, previous == null ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : previous);
            y += iconHeight + 2;
        }
        g.setFont(font);
        g.setColor(t.done() ? TEXT : new Color(0xc9d0dc));
        for (String line : lines) {
            g.drawString(line, box[0] + (box[2] - fm.stringWidth(line)) / 2, y + fm.getAscent() - 1);
            y += lineHeight;
        }
    }

    /** The line under a tile: who completed it first (and how many others did too), or what it is worth while it isn't done. */
    private static void drawUnder(Graphics2D g, int[] box, TileView t) {
        String text;
        Color colour;
        if (t.done()) {
            String who = t.by() == null || t.by().isBlank() ? "Done" : t.by();
            text = t.count() > 1 ? who + " +" + (t.count() - 1) : who;
            colour = GOLD;
        } else {
            text = t.points() + " pts";
            colour = MUTED;
        }
        Font font = new Font(Font.SANS_SERIF, Font.BOLD, 11);
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics(font);
        String shown = ellipsize(fm, text, box[2] - 8);
        g.setColor(colour);
        g.drawString(shown, box[0] + (box[2] - fm.stringWidth(shown)) / 2, box[1] + (box[3] + fm.getAscent() - fm.getDescent()) / 2);
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
