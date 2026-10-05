package com.younglings.bot.commands.poll;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Draws one poll option's progress bar as a PNG — a rounded track with a smooth gradient fill,
 * anti-aliased, so it looks the same at any share instead of stepping in character-sized blocks — with
 * that option's vote count and percentage written to the right of the bar, where they're easy to read
 * against it. Transparent background, so it sits naturally on whatever colour the container is.
 * <p>
 * The image is wide (Discord scales it to the container's width). A tiny non-zero share still gets a
 * visible rounded nub rather than vanishing.
 * <p>
 * Text needs fonts, and a stripped-down server can lack them (Java then throws as soon as it measures a
 * string). {@link #textAvailable()} checks once, and when it's false {@link PollView} leaves the text out
 * of the image and puts the same numbers in an ordinary text line instead — a poll never breaks over a font.
 */
final class PollBarRenderer {
    private PollBarRenderer() {}

    static {
        // Servers have no display; say so before anything touches AWT.
        if (System.getProperty("java.awt.headless") == null) System.setProperty("java.awt.headless", "true");
    }

    static final int WIDTH = 900;
    static final int HEIGHT = 36;
    static final int BAR_HEIGHT = 28;
    /** The bar's own length; the rest of the width is the count/percentage area. */
    static final int BAR_WIDTH = 610;

    /** How a bar is coloured: the live poll's blurple, the winning option of a closed poll, or everything else once closed. */
    enum Style {
        ACTIVE(new Color(0x5865F2), new Color(0x8C96F8)),
        WINNER(new Color(0x2FBF71), new Color(0x7BE0A4)),
        MUTED(new Color(0x6B7280), new Color(0x9CA3AF));

        final Color from;
        final Color to;

        Style(Color from, Color to) {
            this.from = from;
            this.to = to;
        }
    }

    private static volatile Boolean textAvailable;

    /** Whether this machine can draw text at all. Checked once, never throws. */
    static boolean textAvailable() {
        Boolean known = textAvailable;
        if (known != null) return known;

        boolean ok;
        try {
            BufferedImage probe = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = probe.createGraphics();
            try {
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
                ok = g.getFontMetrics().stringWidth("100%") > 0;
            } finally {
                g.dispose();
            }
        } catch (Throwable t) { // a missing font setup surfaces as an Error or NPE, not a tidy exception
            ok = false;
        }
        textAvailable = ok;
        return ok;
    }

    /**
     * @param share       0.0–1.0 share of the votes; anything outside is clamped
     * @param votesText   e.g. "3 votes", drawn lighter to the right of the bar — {@code null} for no text
     * @param percentText e.g. "50%", drawn bold at the far right — {@code null} for no text
     */
    static byte[] render(double share, Style style, String votesText, String percentText) {
        double fraction = Math.max(0.0, Math.min(1.0, share));
        double top = (HEIGHT - BAR_HEIGHT) / 2.0;

        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);

            g.setColor(new Color(255, 255, 255, 34));
            g.fill(new RoundRectangle2D.Double(0, top, BAR_WIDTH, BAR_HEIGHT, BAR_HEIGHT, BAR_HEIGHT));

            if (fraction > 0) {
                // Never narrower than the bar is tall, so a 1% share is still a clean rounded nub.
                double fillWidth = Math.max(BAR_HEIGHT, fraction * BAR_WIDTH);
                g.setPaint(new GradientPaint(0, (float) top, style.from, (float) fillWidth, (float) top, style.to));
                g.fill(new RoundRectangle2D.Double(0, top, fillWidth, BAR_HEIGHT, BAR_HEIGHT, BAR_HEIGHT));

                // A soft highlight along the top edge gives it a little depth.
                g.setPaint(new GradientPaint(0, (float) top, new Color(255, 255, 255, 60), 0, (float) (top + BAR_HEIGHT * 0.55), new Color(255, 255, 255, 0)));
                g.fill(new RoundRectangle2D.Double(1, top + 1, fillWidth - 2, BAR_HEIGHT * 0.55, BAR_HEIGHT, BAR_HEIGHT));
            }

            g.setColor(new Color(255, 255, 255, 40));
            g.setStroke(new BasicStroke(1.2f));
            g.draw(new RoundRectangle2D.Double(0.6, top + 0.6, BAR_WIDTH - 1.2, BAR_HEIGHT - 1.2, BAR_HEIGHT - 1.2, BAR_HEIGHT - 1.2));

            if (votesText != null && percentText != null) drawNumbers(g, votesText, percentText);
        } finally {
            g.dispose();
        }

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode a poll bar", e);
        }
    }

    /** The percentage is the thing to read at a glance, so it's bold and bright at the far right; the count sits just left of it, quieter. */
    private static void drawNumbers(Graphics2D g, String votesText, String percentText) {
        Font bold = new Font(Font.SANS_SERIF, Font.BOLD, 26);
        Font plain = new Font(Font.SANS_SERIF, Font.PLAIN, 22);

        g.setFont(bold);
        FontMetrics boldMetrics = g.getFontMetrics();
        int baseline = (HEIGHT + boldMetrics.getAscent() - boldMetrics.getDescent()) / 2;
        int percentX = WIDTH - 6 - boldMetrics.stringWidth(percentText);
        g.setColor(new Color(255, 255, 255, 245));
        g.drawString(percentText, percentX, baseline);

        g.setFont(plain);
        FontMetrics plainMetrics = g.getFontMetrics();
        int votesX = percentX - 16 - plainMetrics.stringWidth(votesText);
        g.setColor(new Color(255, 255, 255, 165));
        g.drawString(votesText, Math.max(BAR_WIDTH + 14, votesX), baseline);
    }
}
