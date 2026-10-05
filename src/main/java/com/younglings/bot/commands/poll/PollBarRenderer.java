package com.younglings.bot.commands.poll;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Draws one poll option's progress bar as a PNG — a rounded track with a smooth gradient fill, anti-aliased,
 * so it looks the same at any share instead of stepping in character-sized blocks. Transparent background,
 * so it sits naturally on whatever colour the container is.
 * <p>
 * The image is wide (Discord scales it to the container's width) and short, so it reads as a thin bar. A
 * tiny non-zero share still gets a visible rounded nub rather than vanishing.
 */
final class PollBarRenderer {
    private PollBarRenderer() {}

    static final int WIDTH = 900;
    static final int HEIGHT = 30;

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

    /** @param fraction 0.0–1.0 share of the votes; anything outside is clamped */
    static byte[] render(double fraction, Style style) {
        double share = Math.max(0.0, Math.min(1.0, fraction));

        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

            RoundRectangle2D track = new RoundRectangle2D.Double(0, 0, WIDTH, HEIGHT, HEIGHT, HEIGHT);
            g.setColor(new Color(255, 255, 255, 34));
            g.fill(track);

            if (share > 0) {
                // Never narrower than the bar is tall, so a 1% share is still a clean rounded nub.
                double fillWidth = Math.max(HEIGHT, share * WIDTH);
                RoundRectangle2D fill = new RoundRectangle2D.Double(0, 0, fillWidth, HEIGHT, HEIGHT, HEIGHT);
                g.setPaint(new GradientPaint(0, 0, style.from, (float) fillWidth, 0, style.to));
                g.fill(fill);

                // A soft highlight along the top edge gives it a little depth.
                g.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, 60), 0, (float) (HEIGHT * 0.55), new Color(255, 255, 255, 0)));
                g.fill(new RoundRectangle2D.Double(1, 1, Math.max(HEIGHT, share * WIDTH) - 2, HEIGHT * 0.55, HEIGHT, HEIGHT));
            }

            g.setColor(new Color(255, 255, 255, 40));
            g.setStroke(new BasicStroke(1.2f));
            g.draw(new RoundRectangle2D.Double(0.6, 0.6, WIDTH - 1.2, HEIGHT - 1.2, HEIGHT - 1.2, HEIGHT - 1.2));
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
}
