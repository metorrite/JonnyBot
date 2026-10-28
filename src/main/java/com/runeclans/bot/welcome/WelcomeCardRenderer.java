package com.runeclans.bot.welcome;

import com.runeclans.bot.config.BotConfig;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Draws a server's welcome card (700x300 PNG). The website draws it with Satori (one design, shared with the dashboard's preview); if
 * the website can't be reached in a few seconds, or {@code SITE_URL} isn't set, the bot draws a plainer version itself so the welcome
 * is never held up or sent without its picture. Pictures are cached for a short while per exact card, so the channel post and the DM
 * copy of one welcome (and a test) draw it once.
 */
@BService
public class WelcomeCardRenderer {
    private static final Logger log = LoggerFactory.getLogger(WelcomeCardRenderer.class);
    public static final int WIDTH = 700;
    public static final int HEIGHT = 300;
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int CACHE_SIZE = 64;
    private static final Duration SITE_PATIENCE = Duration.ofSeconds(5);

    private final BotConfig config;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private final Map<String, byte[]> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    public WelcomeCardRenderer(BotConfig config) {
        this.config = config;
    }

    /** The finished card for one member. Never throws and never returns {@code null}: the fallback always draws something. */
    public byte[] render(WelcomeCardConfig card, String name, String username, String avatarUrl, String serverName, int memberCount) {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH));
        String title = WelcomeCardConfig.fill(card.title(), name, username, serverName, memberCount, date);
        String description = WelcomeCardConfig.fill(card.description(), name, username, serverName, memberCount, date);
        String key = String.join("\u0000", title, description, avatarUrl, card.backgroundImageUrl(), Integer.toString(card.backgroundColor()), Integer.toString(card.textColor()));
        synchronized (cache) {
            byte[] hit = cache.get(key);
            if (hit != null) return hit;
        }
        byte[] png = fromSite(card, title, description, avatarUrl);
        if (png == null) png = fallback(card, title, description, avatarBytes(avatarUrl));
        synchronized (cache) {
            cache.put(key, png);
        }
        return png;
    }

    private byte[] fromSite(WelcomeCardConfig card, String title, String description, String avatarUrl) {
        String site = config.getSiteUrl();
        if (site == null) return null;
        String url = site + "/api/welcome-card?title=" + enc(title) + "&description=" + enc(description) + "&avatar=" + enc(avatarUrl)
                + "&bg=" + hex(card.backgroundColor()) + "&color=" + hex(card.textColor())
                + (card.backgroundImageUrl().isBlank() ? "" : "&image=" + enc(card.backgroundImageUrl()));
        try {
            HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(SITE_PATIENCE).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            String type = response.headers().firstValue("Content-Type").orElse("");
            if (response.statusCode() != 200 || !type.startsWith("image/") || response.body().length > MAX_BYTES) {
                log.warn("The website's welcome card returned {} ({}); drawing it in the bot instead", response.statusCode(), type);
                return null;
            }
            return response.body();
        } catch (IOException | RuntimeException e) {
            log.warn("The website's welcome card couldn't be fetched ({}); drawing it in the bot instead", e.toString());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private byte[] avatarBytes(String url) {
        return get(url);
    }

    private byte[] get(String url) {
        if (url == null || !url.toLowerCase(Locale.ROOT).startsWith("https://")) return null;
        try {
            HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(4)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            return response.statusCode() == 200 && response.body().length <= MAX_BYTES ? response.body() : null;
        } catch (IOException | RuntimeException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** The bot's own, plainer drawing: background colour (or picture), round avatar, title and description. */
    byte[] fallback(WelcomeCardConfig card, String title, String description, byte[] avatar) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setColor(new Color(card.backgroundColor()));
            g.fillRect(0, 0, WIDTH, HEIGHT);
            BufferedImage background = read(card.backgroundImageUrl().isBlank() ? null : get(card.backgroundImageUrl()));
            if (background != null) {
                double scale = Math.max((double) WIDTH / background.getWidth(), (double) HEIGHT / background.getHeight());
                int w = (int) Math.ceil(background.getWidth() * scale);
                int h = (int) Math.ceil(background.getHeight() * scale);
                g.drawImage(background, (WIDTH - w) / 2, (HEIGHT - h) / 2, w, h, null);
                g.setColor(new Color(0, 0, 0, 90));
                g.fillRect(0, 0, WIDTH, HEIGHT);
            }

            int size = 170;
            int x = 50;
            int y = (HEIGHT - size) / 2;
            BufferedImage face = read(avatar);
            g.setClip(new Ellipse2D.Float(x, y, size, size));
            if (face != null) g.drawImage(face, x, y, size, size, null);
            else {
                g.setColor(new Color(0x5865F2));
                g.fillRect(x, y, size, size);
            }
            g.setClip(null);
            g.setColor(new Color(255, 255, 255, 200));
            g.setStroke(new java.awt.BasicStroke(5));
            g.draw(new Ellipse2D.Float(x, y, size, size));

            g.setColor(new Color(card.textColor()));
            int textX = x + size + 40;
            int textWidth = WIDTH - textX - 40;
            Font titleFont = new Font(Font.SANS_SERIF, Font.BOLD, 40);
            Font bodyFont = new Font(Font.SANS_SERIF, Font.PLAIN, 24);
            List<String> titleLines = wrap(title, g.getFontMetrics(titleFont), textWidth, 2);
            List<String> bodyLines = wrap(description, g.getFontMetrics(bodyFont), textWidth, 3);
            int total = titleLines.size() * 48 + (bodyLines.isEmpty() ? 0 : 14 + bodyLines.size() * 32);
            int baseline = (HEIGHT - total) / 2 + 36;
            g.setFont(titleFont);
            for (String line : titleLines) {
                g.drawString(line, textX, baseline);
                baseline += 48;
            }
            g.setFont(bodyFont);
            baseline += 14 - 10;
            for (String line : bodyLines) {
                g.drawString(line, textX, baseline);
                baseline += 32;
            }
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new IllegalStateException("Couldn't encode the welcome card", e);
        }
        return out.toByteArray();
    }

    private static BufferedImage read(byte[] data) {
        if (data == null) return null;
        try {
            return ImageIO.read(new ByteArrayInputStream(data));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Breaks {@code text} into at most {@code maxLines} lines that fit {@code width}, ending the last with an ellipsis if it was cut. */
    static List<String> wrap(String text, FontMetrics metrics, int width, int maxLines) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isBlank()) return lines;
        StringBuilder line = new StringBuilder();
        for (String word : text.strip().split("\\s+")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (metrics.stringWidth(candidate) <= width || line.isEmpty()) {
                line = new StringBuilder(candidate);
            } else {
                lines.add(line.toString());
                line = new StringBuilder(word);
            }
        }
        if (!line.isEmpty()) lines.add(line.toString());
        if (lines.size() > maxLines) {
            lines = new ArrayList<>(lines.subList(0, maxLines));
            lines.set(maxLines - 1, lines.get(maxLines - 1) + "…");
        }
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            while (l.length() > 1 && metrics.stringWidth(l) > width) l = l.substring(0, l.length() - 2) + "…";
            lines.set(i, l);
        }
        return lines;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String hex(int rgb) {
        return String.format("%06x", rgb & 0xFFFFFF);
    }
}
