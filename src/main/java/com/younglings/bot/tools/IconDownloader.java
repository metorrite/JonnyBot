package com.younglings.bot.tools;

import com.younglings.bot.tracking.BossCatalog;
import com.younglings.bot.tracking.DropItemCatalog;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Standalone tool — NOT wired into the bot, never run by it, never run by Claude either. Run it
 * yourself, whenever you want, to (re)download every icon the tracking system references from the RS3
 * wiki and lay them out under {@code src/main/resources/images/}. Adding a new item to
 * {@link DropItemCatalog} or a new boss to {@link BossCatalog} just needs a re-run of this afterward to
 * pick up its icon — nobody has to go hand-fetch it.
 * <p>
 * Run it from the project root with either:
 * <pre>
 *   mvn -q compile exec:java -Dexec.mainClass=com.younglings.bot.tools.IconDownloader
 * </pre>
 * or, after a normal {@code mvn compile}, directly:
 * <pre>
 *   java -cp target/classes com.younglings.bot.tools.IconDownloader
 * </pre>
 * It needs no bot config, no database, no Discord token — it only ever talks to
 * {@code runescape.wiki} and writes files under {@code src/main/resources/images/}.
 * <p>
 * Always overwrites what it finds — re-running is always safe, and is how you pick up a wiki image
 * that's changed since the last run. Something the wiki doesn't have (a 404, or the request fails
 * outright) leaves an existing file alone if there is one, or is just listed as missing at the end if
 * there isn't — {@code TrackingIconCatalog} falls back to the generated default icon (also refreshed by
 * this tool every run) for anything with no file at all, so a missing icon never means no icon.
 * <p>
 * The four category icons (quest/clue/pet/archaeology) don't have a clean automatic
 * name-to-wiki-filename mapping the way a specific item or boss does — {@link #CATEGORY_SOURCES} is a
 * best guess for each; spot-check those four after a run rather than trusting them blindly.
 */
public final class IconDownloader {
    private static final Path IMAGES_ROOT = Path.of("src/main/resources/images");
    private static final Duration REQUEST_DELAY = Duration.ofMillis(400);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    // The wiki serves a ready-to-use small icon at this URL for most item pages, but for others
    // (bosses especially) the exact same URL pattern returns that page's full splash-art/infobox
    // image instead — confirmed live: item icons come back ~1-2KB, boss "icons" came back
    // 400KB-1.5MB. Discord's application-emoji upload has a hard size cap well under that, so
    // fetchAndSave downscales anything bigger than this on its longest side before ever saving it,
    // regardless of which kind of page it came from.
    private static final int MAX_ICON_DIMENSION = 128;

    // Best-guess RS3 wiki file names for the 4 category icons — unlike an item/boss name (which IS
    // the wiki filename, underscored), these are abstract concepts with no single obvious source
    // image, so these were picked by hand and haven't all been re-verified since. Adjust freely if
    // one turns out wrong; a bad guess just 404s and falls back to the default icon, it won't silently
    // save the wrong image under the right name unless the wiki genuinely has a page by that title.
    private static final Map<String, String> CATEGORY_SOURCES = new LinkedHashMap<>();
    static {
        CATEGORY_SOURCES.put("quest", "Quest point icon");
        CATEGORY_SOURCES.put("clue", "Clue scroll (master)");
        CATEGORY_SOURCES.put("pet", "Pet interface icon");
        CATEGORY_SOURCES.put("archaeology", "Archaeology icon");
    }

    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        int downloaded = 0;
        List<String> missing = new ArrayList<>();

        System.out.println("== Drop items (" + DropItemCatalog.all().size() + ") ==");
        for (DropItemCatalog.DropItem item : DropItemCatalog.all()) {
            boolean ok = fetchAndSave(client, item.name(), IMAGES_ROOT.resolve("drops").resolve(item.key() + ".png"));
            if (ok) downloaded++; else missing.add("drop: " + item.name());
        }

        System.out.println("== Bosses (" + BossCatalog.all().size() + ") ==");
        for (BossCatalog.Boss boss : BossCatalog.all()) {
            boolean ok = fetchAndSave(client, boss.name(), IMAGES_ROOT.resolve("bosses").resolve(boss.key() + ".png"));
            if (ok) downloaded++; else missing.add("boss: " + boss.name());
        }

        System.out.println("== Category icons (best-guess sources — verify these) ==");
        for (var e : CATEGORY_SOURCES.entrySet()) {
            boolean ok = fetchAndSave(client, e.getValue(), IMAGES_ROOT.resolve("tracking").resolve(e.getKey() + ".png"));
            if (ok) downloaded++; else missing.add("category '" + e.getKey() + "': " + e.getValue());
        }

        System.out.println("== Default fallback icon ==");
        Path defaultPath = IMAGES_ROOT.resolve("tracking").resolve("default.png");
        generateDefaultIcon(defaultPath);
        System.out.println("  generated " + defaultPath);

        System.out.println();
        System.out.println("Downloaded/refreshed " + downloaded + " icon(s). " + missing.size() + " not found on the wiki:");
        for (String name : missing) {
            System.out.println("  - " + name);
        }
        if (!missing.isEmpty()) {
            System.out.println();
            System.out.println("Any of the above that already had a file from a previous run were left alone. Anything");
            System.out.println("that's never had one falls back to the generated default icon at runtime.");
        }
    }

    /**
     * Downloads {@code https://runescape.wiki/images/<name with spaces as underscores>.png} and saves
     * it to {@code target}, creating parent directories as needed. Leaves an existing file at
     * {@code target} untouched if the fetch fails, rather than deleting it — a transient wiki hiccup
     * shouldn't regress an icon that already worked on a previous run.
     */
    private static boolean fetchAndSave(HttpClient client, String wikiPageName, Path target) {
        String fileName = wikiPageName.replace(' ', '_') + ".png";
        try {
            URI uri = new URI("https", "runescape.wiki", "/images/" + fileName, null);
            HttpRequest request = HttpRequest.newBuilder(uri).GET().timeout(REQUEST_TIMEOUT).build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                System.out.println("  ! " + wikiPageName + " -> HTTP " + response.statusCode());
                return false;
            }

            byte[] bytes = fitToIconSize(response.body());
            if (bytes == null) {
                System.out.println("  ! " + wikiPageName + " -> response wasn't a decodable image");
                return false;
            }

            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
            System.out.println("  ok " + wikiPageName + " -> " + target + " (" + bytes.length + " bytes)");
            return true;

        } catch (Exception e) {
            System.out.println("  ! " + wikiPageName + " -> " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        } finally {
            try {
                Thread.sleep(REQUEST_DELAY.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** {@code null} if {@code original} isn't a decodable image at all (rare — a 200 status with a non-image body); returned as-is if already small, downscaled to fit {@link #MAX_ICON_DIMENSION} otherwise. */
    private static byte[] fitToIconSize(byte[] original) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(original));
        if (image == null) return null;
        if (image.getWidth() <= MAX_ICON_DIMENSION && image.getHeight() <= MAX_ICON_DIMENSION) return original;

        double scale = (double) MAX_ICON_DIMENSION / Math.max(image.getWidth(), image.getHeight());
        int newWidth = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int newHeight = Math.max(1, (int) Math.round(image.getHeight() * scale));

        BufferedImage scaled = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(image, 0, 0, newWidth, newHeight, null);
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(scaled, "png", out);
        return out.toByteArray();
    }

    /** A small generated placeholder — deliberately not wiki-sourced, so this step never depends on guessing a real page's exact filename. */
    private static void generateDefaultIcon(Path target) throws IOException {
        int size = 32;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0x4A, 0x4A, 0x4A));
            g.fillRoundRect(1, 1, size - 2, size - 2, 8, 8);
            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
            FontMetrics fm = g.getFontMetrics();
            String text = "?";
            g.drawString(text, (size - fm.stringWidth(text)) / 2f, (size - fm.getHeight()) / 2f + fm.getAscent());
        } finally {
            g.dispose();
        }
        Files.createDirectories(target.getParent());
        ImageIO.write(image, "png", target.toFile());
    }

    private IconDownloader() {}
}
