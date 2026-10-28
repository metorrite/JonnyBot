package com.runeclans.bot.events;

import com.runeclans.bot.events.BingoBoardImage.TileView;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BingoLooksTest {
    private final BingoTemplate template = new BingoTemplate();

    private static DataObject config(String layout, Integer size, String look) {
        DataObject board = DataObject.empty().put("pool", BingoBoard.POOL_CLASSIC).put("seed", 5);
        if (layout != null) board.put("layout", layout);
        if (size != null) board.put("size", size);
        if (look != null) board.put("look", look);
        return DataObject.empty().put("board", board);
    }

    private static List<TileView> tiles(int count) {
        List<TileView> tiles = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            boolean done = i % 3 == 1;
            tiles.add(new TileView(i, i % 2 == 0 ? "Nex: Torva boots" : "Complete a Slayer task", 25 + (i % 4) * 25, done, done ? 2 : 0, 6, null, done ? "Metorrite" : null, !done && i % 4 == 0));
        }
        return tiles;
    }

    private static BufferedImage image(byte[] png) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    // ---------- the registry and the setting ----------

    @Test
    void theTwoLooksAreListedWithNamesAndTheNewDefaultIsOneOfThem() {
        assertEquals(List.of("classic", "runescape"), BingoLooks.ids());
        assertEquals("Template", BingoLooks.label("classic"));
        assertEquals("RuneScape", BingoLooks.label("runescape"));
        assertTrue(BingoLooks.valid(BingoLooks.DEFAULT_FOR_NEW));
        assertTrue(BingoLooks.all().stream().allMatch(l -> !l.blurb().isBlank()));
        assertFalse(BingoLooks.valid("neon"));
        assertFalse(BingoLooks.valid(null));
    }

    @Test
    void aBoardNamesItsLookInItsSettingsOrHasNone() {
        assertEquals("runescape", BingoLooks.of(config(null, 3, "runescape")).orElseThrow());
        assertTrue(BingoLooks.of(config(null, 3, null)).isEmpty(), "no look named: the board keeps how it was drawn");
        assertTrue(BingoLooks.of(config(null, 3, "neon")).isEmpty(), "an unknown look is treated as none");
        assertTrue(BingoLooks.of(DataObject.empty()).isEmpty());
        assertTrue(BingoLooks.of(DataObject.fromJson("{\"board\":{\"look\":5}}")).isEmpty(), "a look that isn't text is treated as none");
    }

    @Test
    void anUnknownLookIsRefusedButAMissingOneIsFine() {
        assertEquals(List.of(), template.configProblems(config(null, 3, null)));
        assertEquals(List.of(), template.configProblems(config(null, 3, "runescape")));
        assertEquals(List.of(), template.configProblems(config(BingoLayout.PATH, null, "classic")));
        assertFalse(template.configProblems(config(null, 3, "neon")).isEmpty());
    }

    @Test
    void theLookSurvivesRerollingChangingShapeAndReplacingATile() {
        DataObject prepared = template.prepare(config(BingoLayout.PYRAMID, null, "runescape"));
        assertEquals("runescape", prepared.getObject("board").getString("look"), "a new board keeps the look it was made with");
        var rerolled = template.reroll(prepared, template.starterObjectives(prepared), null, 9L, Set.of());
        assertEquals("runescape", rerolled.config().getObject("board").getString("look"));
        var tree = template.reroll(rerolled.config(), rerolled.objectives(), null, 5L, Set.of(), BingoLayout.TREE);
        assertEquals("runescape", tree.config().getObject("board").getString("look"), "a new shape keeps the look");
        var grid = template.reroll(tree.config(), tree.objectives(), 4, 5L, Set.of(), "grid");
        assertEquals("runescape", grid.config().getObject("board").getString("look"), "back to a grid keeps it too");
        var replaced = template.replaceTile(grid.config(), grid.objectives(), 2, "Kill Telos five times", 80);
        assertEquals("runescape", replaced.config().getObject("board").getString("look"), "swapping a tile keeps it");
        // a board without a look stays without one
        DataObject plain = template.prepare(config(null, 3, null));
        assertFalse(plain.getObject("board").hasKey("look"));
        assertFalse(template.reroll(plain, template.starterObjectives(plain), null, 2L, Set.of()).config().getObject("board").hasKey("look"));
    }

    // ---------- the shapes ----------

    @Test
    void everyShapeDrawsTheSameSizeInBothLooksButNotTheSamePicture() throws Exception {
        for (BingoLayout l : BingoLayout.all()) {
            byte[] classic = BingoBoardImage.render(l, BingoLooks.CLASSIC, tiles(l.tiles()));
            byte[] runescape = BingoBoardImage.render(l, BingoLooks.RUNESCAPE, tiles(l.tiles()));
            assertEquals(1600, image(runescape).getWidth(), l.id());
            assertEquals(1000, image(runescape).getHeight(), l.id());
            assertEquals(image(classic).getWidth(), image(runescape).getWidth());
            assertFalse(java.util.Arrays.equals(classic, runescape), l.id() + " looks different");
            assertArrayEquals(classic, BingoBoardImage.render(l, tiles(l.tiles())), l.id() + ": the two-argument form is the template look");
        }
    }

    // ---------- the grid ----------

    @Test
    void aGridInALookIsAsBigAsItsTilesNeedAndHasRoomForTheTitleAndFooter() throws Exception {
        for (int size = BingoBoard.MIN_SIZE; size <= BingoBoard.MAX_SIZE; size++) {
            int tile = BingoBoardImage.tileSize(size);
            int side = size * tile + (size - 1) * 8;
            for (String look : BingoLooks.ids()) {
                BufferedImage image = image(BingoBoardImage.render("Christmas Bingo", "Running", size, tiles(size * size), "footer", look));
                assertEquals(Math.max(side + 140, 760), image.getWidth(), size + " " + look);
                assertEquals(BingoBackdrops.HEADER + side + BingoBackdrops.FOOTER, image.getHeight(), size + " " + look);
            }
        }
    }

    @Test
    void aGridWithNoLookIsTheOriginalPlainBoardAndAnUnknownLookFallsBackToIt() throws Exception {
        byte[] plain = BingoBoardImage.render("Smoke bingo", "Running", 4, tiles(16), "footer");
        assertArrayEquals(plain, BingoBoardImage.render("Smoke bingo", "Running", 4, tiles(16), "footer", null));
        assertArrayEquals(plain, BingoBoardImage.render("Smoke bingo", "Running", 4, tiles(16), "footer", "neon"));
        assertFalse(java.util.Arrays.equals(plain, BingoBoardImage.render("Smoke bingo", "Running", 4, tiles(16), "footer", BingoLooks.CLASSIC)));
        assertThrows(IllegalArgumentException.class, () -> BingoBoardImage.render("x", "", 5, tiles(4), "", BingoLooks.RUNESCAPE));
    }

    @Test
    void anyTitleDrawsWhateverItsLength() throws Exception {
        String longName = "The Younglings Annual Winter Solstice Community Bingo Extravaganza Of Doom And Glory 2026";
        for (String look : BingoLooks.ids()) {
            for (String name : new String[]{"Bingo", "", "  ", null, longName, "X".repeat(80), "Ñandú Bingo ✓ 2026", "A B", "Christmas Bingo"}) {
                byte[] png = BingoBoardImage.render(name, name == null ? null : "Signups open · starts in 3 days · " + longName, 3, tiles(9), null, look);
                assertEquals(760, image(png).getWidth(), look + " " + name);
            }
        }
    }

    @Test
    void theBackdropIsPaintedOnceForASizeAndNotDrawnOn() throws Exception {
        BufferedImage first = BingoBackdrops.grid(BingoLooks.RUNESCAPE, 760, 900);
        assertTrue(first == BingoBackdrops.grid(BingoLooks.RUNESCAPE, 760, 900), "kept for the next board");
        int before = first.getRGB(300, 400);
        BingoBoardImage.render("Christmas Bingo", "Running", 3, tiles(9), "footer", BingoLooks.RUNESCAPE);
        assertEquals(before, BingoBackdrops.grid(BingoLooks.RUNESCAPE, 760, 900).getRGB(300, 400), "drawing a board leaves the kept backdrop alone");
        assertNotEquals(BingoBackdrops.grid(BingoLooks.CLASSIC, 760, 900).getRGB(300, 400), before, "each look has its own backdrop");
    }

    @Test
    void aBoardIsDrawnFromItsSettingsInItsShapeAndLook() throws Exception {
        DataObject shaped = template.prepare(config(BingoLayout.TREE, null, "runescape"));
        byte[] fromSettings = BingoBoardImage.render(shaped, "Ignored for a shape", "x", "y", tiles(16));
        assertArrayEquals(BingoBoardImage.render(BingoLayout.find(BingoLayout.TREE).orElseThrow(), BingoLooks.RUNESCAPE, tiles(16)), fromSettings);
        // a shape with no look is the template picture, as before looks existed
        DataObject old = template.prepare(config(BingoLayout.TREE, null, null));
        assertArrayEquals(BingoBoardImage.render(BingoLayout.find(BingoLayout.TREE).orElseThrow(), tiles(16)), BingoBoardImage.render(old, "n", "s", "f", tiles(16)));
        DataObject grid = template.prepare(config(null, 3, "classic"));
        assertArrayEquals(BingoBoardImage.render("Quick", "Your board", 3, tiles(9), "4 of 9", BingoLooks.CLASSIC), BingoBoardImage.render(grid, "Quick", "Your board", "4 of 9", tiles(9)));
        DataObject plain = template.prepare(config(null, 3, null));
        assertArrayEquals(BingoBoardImage.render("Quick", "Your board", 3, tiles(9), "4 of 9"), BingoBoardImage.render(plain, "Quick", "Your board", "4 of 9", tiles(9)));
    }

    @Test
    void aTileWithALongNameKeepsItsPointsLineClear() throws Exception {
        // a "Boss: item" tile with a two-line item name used to run into the points line on the smaller boards
        List<TileView> longNames = new ArrayList<>();
        for (int i = 1; i <= 25; i++) longNames.add(new TileView(i, "Kalphite King: Off-hand drygore longsword", 100, false, 0, 6, "drygore_longsword"));
        for (String look : new String[]{BingoLooks.CLASSIC, BingoLooks.RUNESCAPE}) {
            assertTrue(image(BingoBoardImage.render("Long names", "x", 5, longNames, "f", look)).getHeight() > 0);
        }
        assertTrue(image(BingoBoardImage.render("Long names", "x", 5, longNames, "f")).getHeight() > 0);
    }
}
