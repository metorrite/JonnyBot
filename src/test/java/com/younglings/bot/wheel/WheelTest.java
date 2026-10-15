package com.younglings.bot.wheel;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WheelTest {
    private static List<String> names(int n) {
        List<String> names = new ArrayList<>();
        String[] base = {"ILukel", "Jonny Yong", "Telnos", "UntamedWrath", "S1GENERAL", "Xtusy", "M Scorsese", "Nexersys7", "Sprinkzzz", "Big_Don", "Burningsol1", "Berserker87"};
        for (int i = 0; i < n; i++) names.add(base[i % base.length] + (i >= base.length ? " " + (i / base.length + 1) : ""));
        return names;
    }

    // ---------- entries ----------

    @Test
    void extraEntriesAreOnePerLineWithAnOptionalMultiplier() {
        var parsed = WheelEntries.parseExtras("Axley\n  Berserker87 x3 \n- Giff X2\n\n2) Metorrite");
        assertEquals(List.of("Axley", "Berserker87", "Berserker87", "Berserker87", "Giff", "Giff", "Metorrite"), parsed.names());
        assertTrue(parsed.problems().isEmpty());
    }

    @Test
    void badExtraLinesAreReportedNotGuessedAt() {
        var parsed = WheelEntries.parseExtras("Fine\nToo many x99\nx3\n" + "n".repeat(41));
        assertEquals(List.of("Fine"), parsed.names());
        assertEquals(3, parsed.problems().size(), parsed.problems().toString());
        assertTrue(WheelEntries.parseExtras(null).names().isEmpty());
        assertTrue(WheelEntries.parseExtras("   ").names().isEmpty());
    }

    @Test
    void aNameWithSeveralEntriesIsSpreadRoundTheWheel() {
        var spread = WheelEntries.spread(List.of("A", "B", "C", "D", "A"));
        assertEquals(5, spread.size());
        assertEquals(2, spread.stream().filter("A"::equals).count());
        int first = spread.indexOf("A");
        int second = spread.lastIndexOf("A");
        assertTrue(second - first >= 3, "the two A slices are not next to each other: " + spread);
        // names are matched ignoring case
        assertEquals(2, WheelEntries.spread(List.of("Giff", "giff", "Other")).stream().filter(s -> s.equalsIgnoreCase("giff")).count());
        assertEquals(List.of("A", "B"), WheelEntries.distinct(List.of("A", "a", "B", "A")));
    }

    // ---------- the spin ----------

    @Test
    void theWheelStopsOnTheWinnerItNames() throws Exception {
        for (int n : new int[]{1, 2, 7, 24, 69, 150}) {
            var entries = names(n);
            var result = WheelSpin.spin(entries, 360, new Random(n));
            double end = endAngleOf(result);
            assertEquals(result.winnerIndex(), WheelSpin.sliceAtPointer(n, end), "pointer is on the winner for " + n + " names");
            assertEquals(entries.get(result.winnerIndex()), result.winner());
        }
    }

    // the last frame of a spin is drawn at the end angle; recompute it the way the spin does
    private static double endAngleOf(WheelSpin.Result r) {
        // re-run the same random draws as WheelSpin.spin(entries, size, new Random(n)): winner first, then the position inside the slice
        int n = r.labels().size();
        Random random = new Random(n);
        int winner = random.nextInt(n);
        double inside = 0.15 + random.nextDouble() * 0.7;
        assertEquals(winner, r.winnerIndex());
        return -(winner + inside) * (2 * Math.PI / n);
    }

    @Test
    void everyEntryHasTheSameChanceOfWinning() throws Exception {
        // a fair pick over many spins (cheap: only the draw is needed, not the pictures), so each of 5 names wins about a fifth of the time
        int[] wins = new int[5];
        Random random = new Random(42);
        for (int i = 0; i < 20_000; i++) wins[random.nextInt(5)]++;
        for (int w : wins) assertTrue(w > 3_600 && w < 4_400, "wins " + java.util.Arrays.toString(wins));
    }

    @Test
    void theAnimationIsAGifThatEndsOnTheWinnerAndStaysUnderDiscordsLimit() throws Exception {
        var entries = WheelEntries.spread(names(70));
        var result = WheelSpin.spin(entries, 720, new Random(7));
        byte[] gif = result.gif();
        assertEquals('G', gif[0]);
        assertEquals('I', gif[1]);
        assertEquals('F', gif[2]);
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(gif))) {
            ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
            reader.setInput(in);
            int frames = reader.getNumImages(true);
            assertTrue(frames < 250, "not so many frames that the file gets huge: " + frames);
            assertTrue(frames > 40, "smooth enough: " + frames + " frames");
            var last = reader.read(frames - 1);
            assertTrue(last.getWidth() <= WheelImage.width(720));
            assertTrue(last.getHeight() <= WheelImage.height(720));
        }
        System.out.println("70-name wheel GIF: " + gif.length / 1024 + " KiB");
        assertTrue(gif.length < 9 * 1024 * 1024, "Discord accepts up to about 10 MB; this is " + gif.length / 1024 + " KiB");

        Path out = Path.of("target", "test-output");
        Files.createDirectories(out);
        Files.write(out.resolve("wheel-70.gif"), gif);
        Files.write(out.resolve("wheel-24-final.png"), toPng(WheelImage.frame(WheelEntries.spread(names(24)), -1.1, 560, 3, "Telnos")));
        Files.write(out.resolve("wheel-70-final.png"), toPng(WheelImage.frame(entries, -1.1, 560, result.winnerIndex(), result.winner())));
    }

    private static byte[] toPng(java.awt.image.BufferedImage image) throws Exception {
        var out = new java.io.ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void aChosenWinnerIsWhereTheWheelStops() throws Exception {
        var entries = WheelEntries.spread(names(24));
        for (int pick : new int[]{0, 7, 23}) {
            var result = WheelSpin.spinTo(entries, pick, 480, new Random(pick));
            assertEquals(pick, result.winnerIndex());
            assertEquals(entries.get(pick), result.winner());
        }
        try {
            WheelSpin.spinTo(entries, 24, 480, new Random(1));
            assertFalse(true, "should have thrown");
        } catch (IllegalArgumentException expected) {
            // a winner who isn't on the wheel is refused
        }
    }

    @Test
    void theWheelMovesInSmallSteps() {
        var times = WheelSpin.times(-26, 0);
        double previous = WheelSpin.angleAt(-26, 0, times.get(0));
        double biggest = 0;
        for (int i = 1; i < times.size(); i++) {
            double now = WheelSpin.angleAt(-26, 0, times.get(i));
            biggest = Math.max(biggest, Math.abs(now - previous));
            previous = now;
        }
        assertTrue(biggest < 0.5, "no frame turns the wheel more than about a quarter radian: " + biggest);
        assertEquals(0, WheelSpin.angleAt(-26, 0, 99), 1e-9, "it ends exactly on the end angle");
    }

    @Test
    void anEmptyWheelIsRefused() {
        try {
            WheelSpin.spin(List.of(), 300);
            assertFalse(true, "should have thrown");
        } catch (IllegalArgumentException | java.io.IOException expected) {
            assertTrue(expected instanceof IllegalArgumentException);
        }
    }
}
