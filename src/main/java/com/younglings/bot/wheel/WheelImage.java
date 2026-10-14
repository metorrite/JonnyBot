package com.younglings.bot.wheel;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Draws the wheel of names with Java2D: coloured slices with the names running along each slice towards the rim, a hub, and a pointer at the top.
 * The wheel's angle is the turn clockwise (in radians) from "slice 0 starts at the pointer". Pure drawing, so it can be tested and reused for every
 * frame of the spin.
 */
public final class WheelImage {
    private static final Color[] COLOURS = {
            new Color(0x3369e8), new Color(0xd50f25), new Color(0xeeb211), new Color(0x009925),
    };
    private static final Color BACKGROUND = new Color(0x15171c);
    private static final Color TEXT = Color.WHITE;

    private WheelImage() {}

    /** The picture's width and height for a wheel {@code size} pixels across: a little room round it, and a banner below for the winner. */
    public static int width(int size) {
        return size + 40;
    }

    public static int height(int size) {
        return size + 40 + 72;
    }

    /**
     * One frame. {@code winnerIndex} is the slice to highlight (or -1 while spinning), and {@code banner} is the line shown under the wheel.
     */
    public static BufferedImage frame(List<String> labels, double angle, int size, int winnerIndex, String banner) {
        int n = labels.size();
        if (n < 1) throw new IllegalArgumentException("A wheel needs at least one name.");
        int w = width(size);
        int h = height(size);
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setColor(BACKGROUND);
            g.fillRect(0, 0, w, h);

            double cx = w / 2.0;
            double cy = 20 + size / 2.0 + 8;
            double radius = size / 2.0 - 8;
            double slice = 2 * Math.PI / n;

            // Slice i spans [angle + i*slice, angle + (i+1)*slice] clockwise from the top. Arc2D measures degrees anticlockwise from 3 o'clock.
            for (int i = 0; i < n; i++) {
                double startClockwiseFromTop = angle + i * slice;
                double startDeg = 90 - Math.toDegrees(startClockwiseFromTop);
                double extentDeg = -Math.toDegrees(slice);
                Color colour = colourFor(i, n);
                if (winnerIndex >= 0 && i != winnerIndex) colour = dim(colour);
                g.setColor(colour);
                g.fill(new Arc2D.Double(cx - radius, cy - radius, radius * 2, radius * 2, startDeg, extentDeg - 0.0, Arc2D.PIE));
            }

            drawLabels(g, labels, angle, cx, cy, radius, slice, winnerIndex);

            // rim and hub
            g.setStroke(new BasicStroke(4f));
            g.setColor(new Color(0x2b2f38));
            g.draw(new Ellipse2D.Double(cx - radius, cy - radius, radius * 2, radius * 2));
            double hub = Math.max(18, radius * 0.1);
            g.setColor(Color.WHITE);
            g.fill(new Ellipse2D.Double(cx - hub, cy - hub, hub * 2, hub * 2));
            g.setColor(new Color(0x2b2f38));
            g.setStroke(new BasicStroke(3f));
            g.draw(new Ellipse2D.Double(cx - hub, cy - hub, hub * 2, hub * 2));

            // pointer at the top, tip pointing down into the wheel
            int tipY = (int) (cy - radius + 14);
            Polygon pointer = new Polygon(new int[]{(int) cx - 15, (int) cx + 15, (int) cx}, new int[]{tipY - 34, tipY - 34, tipY}, 3);
            g.setColor(new Color(0xffffff));
            g.fill(pointer);
            g.setColor(new Color(0x15171c));
            g.setStroke(new BasicStroke(2.5f));
            g.draw(pointer);

            if (banner != null && !banner.isBlank()) {
                g.setColor(TEXT);
                int fontSize = 34;
                Font font = new Font(Font.SANS_SERIF, Font.BOLD, fontSize);
                g.setFont(font);
                FontMetrics m = g.getFontMetrics();
                while (m.stringWidth(banner) > w - 40 && fontSize > 14) {
                    font = new Font(Font.SANS_SERIF, Font.BOLD, --fontSize);
                    g.setFont(font);
                    m = g.getFontMetrics();
                }
                g.drawString(banner, (w - m.stringWidth(banner)) / 2, h - 28);
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    private static Color colourFor(int i, int n) {
        // never give the last slice the same colour as the first, which sit side by side round the wheel
        int index = i % COLOURS.length;
        if (n % COLOURS.length == 1 && i == n - 1) index = (index + 1) % COLOURS.length;
        return COLOURS[index];
    }

    private static Color dim(Color c) {
        return new Color((int) (c.getRed() * 0.45 + 20), (int) (c.getGreen() * 0.45 + 20), (int) (c.getBlue() * 0.45 + 20));
    }

    private static void drawLabels(Graphics2D g, List<String> labels, double angle, double cx, double cy, double radius, double slice, int winnerIndex) {
        int n = labels.size();
        // the text height has to fit the slice where the text sits: about 62% of the way out
        double arcAtText = radius * 0.62 * slice;
        int fontSize = (int) Math.max(8, Math.min(24, arcAtText * 0.62));
        Font font = new Font(Font.SANS_SERIF, Font.BOLD, fontSize);
        g.setFont(font);
        FontMetrics m = g.getFontMetrics();
        double textStart = radius * 0.2;
        double textEnd = radius - 12;
        double room = textEnd - textStart;
        AffineTransform saved = g.getTransform();
        for (int i = 0; i < n; i++) {
            double centre = angle + (i + 0.5) * slice;
            String label = labels.get(i);
            while (m.stringWidth(label) > room && label.length() > 3) label = label.substring(0, label.length() - 1);
            if (!label.equals(labels.get(i))) label = label.stripTrailing() + "…";
            g.setTransform(saved);
            g.translate(cx, cy);
            // a text line pointing along the slice's middle, from the hub out to the rim: rotate so +x points that way (0 = straight up)
            g.rotate(centre - Math.PI / 2);
            g.setColor(winnerIndex >= 0 && i != winnerIndex ? new Color(255, 255, 255, 130) : TEXT);
            int width = m.stringWidth(label);
            g.drawString(label, (float) (textEnd - width), (float) (m.getAscent() / 2.0 - 1));
        }
        g.setTransform(saved);
    }
}
