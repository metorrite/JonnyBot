package com.younglings.bot.events;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.QuadCurve2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Paints the backdrops a square grid board is drawn on, one painter per look, at exactly the size the board needs (a bigger grid is a bigger
 * picture). The shapes have fixed pictures of their own ({@link BingoLayout#image(String)}); the grid has no fixed size, so its backdrop is painted
 * here once per size and kept. A backdrop is the scene only: the title, the panel behind the tiles and the tiles themselves are drawn on top of it
 * by {@link BingoBoardImage}, because they depend on the event.
 */
final class BingoBackdrops {
    /** Where the title block ends and the board starts: the backdrops leave this much room at the top. */
    static final int HEADER = 190;
    /** Room under the board for the footer line and, in the RuneScape look, a row of runes. */
    static final int FOOTER = 116;

    private static final int MAX_KEPT = 12;
    private static final Map<String, BufferedImage> KEPT = new LinkedHashMap<>();

    private BingoBackdrops() {}

    /** The empty backdrop for a grid board {@code w} by {@code h} pixels in {@code look}. Painted once and kept; callers must not draw on it, copy it first. */
    static synchronized BufferedImage grid(String look, int w, int h) {
        String key = look + ":" + w + "x" + h;
        BufferedImage image = KEPT.get(key);
        if (image == null) {
            image = BingoLooks.RUNESCAPE.equals(look) ? stoneHall(w, h) : templateHall(w, h);
            if (KEPT.size() >= MAX_KEPT) {
                var eldest = KEPT.keySet().iterator();
                eldest.next();
                eldest.remove();
            }
            KEPT.put(key, image);
        }
        return image;
    }

    private static Graphics2D open(BufferedImage image) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g;
    }

    private static Color mix(Color a, Color b, float t) {
        return new Color(Math.round(a.getRed() + (b.getRed() - a.getRed()) * t), Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    private static Color rgba(int rgb, int alpha) {
        return new Color((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, alpha);
    }

    /** Adds grain: every pixel's brightness moves by up to {@code amount}, so flat colour doesn't look printed. */
    private static void grain(BufferedImage image, int amount, long seed) {
        Random r = new Random(seed);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                int d = r.nextInt(amount * 2 + 1) - amount;
                int red = Math.max(0, Math.min(255, ((rgb >> 16) & 255) + d));
                int green = Math.max(0, Math.min(255, ((rgb >> 8) & 255) + d));
                int blue = Math.max(0, Math.min(255, (rgb & 255) + d));
                image.setRGB(x, y, (red << 16) | (green << 8) | blue);
            }
        }
    }

    // ---------- the template look: a dark teal board with a gold frame, like the shape templates ----------

    private static BufferedImage templateHall(int w, int h) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = open(image);
        try {
            g.setColor(new Color(0x0a1116));
            g.fillRect(0, 0, w, h);
            g.setPaint(new RadialGradientPaint(w / 2f, h * 0.5f, (float) (Math.max(w, h) * 0.8), new float[]{0f, 1f}, new Color[]{new Color(0x1a3836), new Color(0x0a1116)}));
            g.fillRect(0, 0, w, h);
            // the faint dot grid the templates have
            g.setColor(new Color(160, 200, 200, 22));
            for (int y = 30; y < h; y += 26) for (int x = 30; x < w; x += 26) g.fillRect(x, y, 2, 2);
            // a gold double frame
            g.setPaint(new LinearGradientPaint(0, 0, w, h, new float[]{0f, 0.5f, 1f}, new Color[]{new Color(0xf0d890), new Color(0xa9833e), new Color(0xf0d890)}));
            g.setStroke(new BasicStroke(6f));
            g.draw(new RoundRectangle2D.Float(22, 22, w - 44, h - 44, 22, 22));
            g.setColor(rgba(0xd9b66b, 90));
            g.setStroke(new BasicStroke(2f));
            g.draw(new RoundRectangle2D.Float(34, 34, w - 68, h - 68, 17, 17));
        } finally {
            g.dispose();
        }
        return image;
    }

    // ---------- the RuneScape look: a stone hall with a wooden frame and a row of runes ----------

    private static BufferedImage stoneHall(int w, int h) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = open(image);
        Random r = new Random(7);
        try {
            g.setColor(new Color(0x15100d)); // the mortar shows in the gaps between the bricks
            g.fillRect(0, 0, w, h);
            int course = 30;
            int brick = 76;
            for (int row = 0, y = 0; y < h; row++, y += course) {
                int offset = (row % 2) * (brick / 2);
                for (int x = -offset; x < w; x += brick) {
                    Color tone = mix(new Color(0x56493d), new Color(0x2f2822), r.nextFloat());
                    if (r.nextInt(7) == 0) tone = mix(tone, new Color(0x6a4a30), 0.35f); // a warmer brick now and then
                    g.setColor(tone);
                    g.fillRect(x + 1, y + 1, brick - 2, course - 2);
                    g.setColor(new Color(255, 236, 210, 20));
                    g.fillRect(x + 1, y + 1, brick - 2, 2);
                    g.setColor(new Color(0, 0, 0, 46));
                    g.fillRect(x + 1, y + course - 4, brick - 2, 3);
                }
            }
            // weathering: soft dark stains
            for (int i = 0; i < Math.max(8, w * h / 40000); i++) {
                float x = r.nextInt(w);
                float y = r.nextInt(h);
                float radius = 40 + r.nextInt(90);
                g.setPaint(new RadialGradientPaint(x, y, radius, new float[]{0f, 1f}, new Color[]{new Color(0, 0, 0, 46), new Color(0, 0, 0, 0)}));
                g.fill(new Ellipse2D.Float(x - radius, y - radius, radius * 2, radius * 2));
            }
            // torchlight from above, then the room falling into shadow at its edges
            g.setPaint(new RadialGradientPaint(w / 2f, h * 0.30f, (float) (Math.max(w, h) * 0.85), new float[]{0f, 1f}, new Color[]{rgba(0xffaa50, 62), rgba(0xffaa50, 0)}));
            g.fillRect(0, 0, w, h);
            g.setPaint(new RadialGradientPaint(w / 2f, h * 0.5f, (float) (Math.hypot(w, h) * 0.55), new float[]{0f, 0.55f, 1f}, new Color[]{rgba(0x000000, 0), rgba(0x000000, 52), rgba(0x000000, 215)}));
            g.fillRect(0, 0, w, h);
        } finally {
            g.dispose();
        }
        grain(image, 3, 17);
        g = open(image);
        try {
            // a worn wooden frame with a brass line and studs
            g.setColor(new Color(0x4a3220));
            g.setStroke(new BasicStroke(14f));
            g.draw(new RoundRectangle2D.Float(16, 16, w - 32, h - 32, 18, 18));
            g.setColor(new Color(0x2c1d12));
            g.setStroke(new BasicStroke(2f));
            g.draw(new RoundRectangle2D.Float(9, 9, w - 18, h - 18, 22, 22));
            g.setColor(new Color(0xc29a4c));
            g.setStroke(new BasicStroke(3f));
            g.draw(new RoundRectangle2D.Float(26, 26, w - 52, h - 52, 12, 12));
            for (int[] p : new int[][]{{40, 40}, {w - 40, 40}, {40, h - 40}, {w - 40, h - 40}}) {
                g.setColor(new Color(0xe6c878));
                g.fill(new Ellipse2D.Float(p[0] - 9, p[1] - 9, 18, 18));
                g.setColor(new Color(0x8a6a2a));
                g.fill(new Ellipse2D.Float(p[0] - 4, p[1] - 4, 8, 8));
            }
            // a row of runes carved under the board
            g.setColor(rgba(0xd7b062, 150));
            g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int count = Math.max(3, (w - 200) / 52);
            double start = w / 2.0 - (count - 1) * 26;
            for (int i = 0; i < count; i++) rune(g, i % 6, start + i * 52, h - 52);
        } finally {
            g.dispose();
        }
        return image;
    }

    /** One simple carved glyph centred on {@code (x, y)}: six kinds, repeated along the row. */
    private static void rune(Graphics2D g, int kind, double x, double y) {
        switch (kind) {
            case 0 -> { // a ring with a dot
                g.draw(new Ellipse2D.Double(x - 9, y - 9, 18, 18));
                g.fill(new Ellipse2D.Double(x - 2, y - 2, 4, 4));
            }
            case 1 -> { // a triangle
                Path2D t = new Path2D.Double();
                t.moveTo(x, y - 10);
                t.lineTo(x + 10, y + 8);
                t.lineTo(x - 10, y + 8);
                t.closePath();
                g.draw(t);
            }
            case 2 -> { // a diamond split down the middle
                Path2D d = new Path2D.Double();
                d.moveTo(x, y - 11);
                d.lineTo(x + 8, y);
                d.lineTo(x, y + 11);
                d.lineTo(x - 8, y);
                d.closePath();
                g.draw(d);
                g.draw(new Line2D.Double(x, y - 11, x, y + 11));
            }
            case 3 -> { // a hexagon
                Path2D hex = new Path2D.Double();
                for (int k = 0; k < 6; k++) {
                    double a = Math.PI / 3 * k + Math.PI / 6;
                    double px = x + Math.cos(a) * 10;
                    double py = y + Math.sin(a) * 10;
                    if (k == 0) hex.moveTo(px, py);
                    else hex.lineTo(px, py);
                }
                hex.closePath();
                g.draw(hex);
            }
            case 4 -> { // a cross in a circle
                g.draw(new Ellipse2D.Double(x - 9, y - 9, 18, 18));
                g.draw(new Line2D.Double(x - 9, y, x + 9, y));
                g.draw(new Line2D.Double(x, y - 9, x, y + 9));
            }
            default -> { // two crescents
                g.draw(new QuadCurve2D.Double(x - 9, y - 9, x + 1, y, x - 9, y + 9));
                g.draw(new QuadCurve2D.Double(x + 9, y - 9, x - 1, y, x + 9, y + 9));
            }
        }
    }
}
