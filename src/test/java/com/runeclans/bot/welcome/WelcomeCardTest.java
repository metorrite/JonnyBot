package com.runeclans.bot.welcome;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The welcome card's variables and the bot's own drawing (the fallback when the website can't draw it). Nothing here touches the network or Discord. */
class WelcomeCardTest {
    @Test
    void variablesAreFilled() {
        assertEquals("Welcome, Ryan! You are #128 of The Younglings on 19 Oct 2026 (pvmryan)",
                WelcomeCardConfig.fill("Welcome, {name}! You are #{count} of {server} on {date} ({username})", "Ryan", "pvmryan", "The Younglings", 128, "19 Oct 2026"));
    }

    @Test
    void theDefaultIsOff() {
        assertFalse(WelcomeCardConfig.defaults(1L).enabled());
    }

    @Test
    void theFallbackDrawsA700By300PngInTheChosenBackground() throws Exception {
        WelcomeCardRenderer renderer = new WelcomeCardRenderer(null);
        WelcomeCardConfig card = new WelcomeCardConfig(1L, true, 0x204060, "", 0xFFFFFF, "Welcome, {name}!", "x");
        byte[] png = renderer.fallback(card, "Welcome, Ryan!", "You are member #128", null);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertEquals(WelcomeCardRenderer.WIDTH, image.getWidth());
        assertEquals(WelcomeCardRenderer.HEIGHT, image.getHeight());
        assertEquals(0x204060, image.getRGB(690, 5) & 0xFFFFFF, "the corner shows the background colour");
    }

    @Test
    void longTextIsWrappedAndCutRatherThanOverflowing() {
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        var metrics = probe.createGraphics().getFontMetrics(new Font(Font.SANS_SERIF, Font.PLAIN, 24));
        List<String> lines = WelcomeCardRenderer.wrap("word ".repeat(80), metrics, 300, 3);
        assertEquals(3, lines.size());
        assertTrue(lines.getLast().endsWith("…"));
        for (String line : lines) assertTrue(metrics.stringWidth(line) <= 300);
        assertTrue(WelcomeCardRenderer.wrap("  ", metrics, 300, 3).isEmpty());
    }
}
