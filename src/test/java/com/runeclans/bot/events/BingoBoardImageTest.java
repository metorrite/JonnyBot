package com.runeclans.bot.events;

import com.runeclans.bot.events.BingoBoardImage.TileView;
import com.runeclans.bot.events.EventRules.Evidence;
import com.runeclans.bot.events.EventRules.ObjectiveSpec;
import com.runeclans.bot.events.EventScoring.Standing;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BingoBoardImageTest {
    private static final BingoTiles ICONS = new BingoTiles(new com.runeclans.bot.internal.WikiCatalog());

    private static List<TileView> tiles(int size, int doneEvery) {
        List<TileView> tiles = new ArrayList<>();
        var pool = BingoBoard.dropsPool();
        for (int i = 0; i < size * size; i++) tiles.add(new TileView(i + 1, pool.get(i).label(), pool.get(i).points(), i % doneEvery == 0, i % doneEvery == 0 ? 2 : 0, 5, ICONS.iconKeyFor(pool.get(i).label())));
        return tiles;
    }

    @Test
    void aBoardIsDrawnAtTheRightSizeForEachBoardSize() throws Exception {
        for (int size = BingoBoard.MIN_SIZE; size <= BingoBoard.MAX_SIZE; size++) {
            byte[] png = BingoBoardImage.render("Smoke bingo", "Running", size, tiles(size, 3), "footer text");
            var image = ImageIO.read(new ByteArrayInputStream(png));
            int tile = BingoBoardImage.tileSize(size);
            assertEquals(40 + size * tile + (size - 1) * 8, image.getWidth(), "width for " + size);
            assertTrue(image.getHeight() > size * tile, "height for " + size);
        }
    }

    @Test
    void aBoardWithTheWrongNumberOfTilesIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> BingoBoardImage.render("x", "", 5, tiles(4, 2), ""));
    }

    @Test
    void aSampleBoardIsWrittenForALook() throws Exception {
        Path out = Path.of("target", "test-output");
        Files.createDirectories(out);
        Files.write(out.resolve("bingo-5x5.png"), BingoBoardImage.render("Friday Night Bingo", "Running · tiles show how many players have them", 5, tiles(5, 3), "Submit proof with /events submit event:12 and the tile's number"));
        Files.write(out.resolve("bingo-3x3.png"), BingoBoardImage.render("Quick Bingo", "Your board", 3, tiles(3, 2), "4 of 9 tiles done"));
        Files.write(out.resolve("bingo-7x7.png"), BingoBoardImage.render("Big Bingo", "Running", 7, tiles(7, 4), ""));
        assertTrue(Files.size(out.resolve("bingo-5x5.png")) > 5_000);
    }

    // ---------- what the tiles say ----------

    private static ObjectiveSpec obj(String key, String label, long points) {
        return new ObjectiveSpec(key, label, "", points, Evidence.EITHER, 1, 1, List.of());
    }

    @Test
    void tileNumbersFollowTheBoardAndFallBackToTheObjectiveOrder() {
        var objectives = List.of(obj("a", "A", 1), obj("b", "B", 1), obj("c", "C", 1));
        var withBoard = DataObject.fromJson("{\"board\":{\"size\":3,\"keys\":[\"c\",\"a\",\"b\"]}}");
        assertEquals("c", BingoBoardView.keyForNumber(withBoard, objectives, 1));
        assertEquals("b", BingoBoardView.keyForNumber(withBoard, objectives, 3));
        assertEquals("b", BingoBoardView.keyForNumber(DataObject.empty(), objectives, 2), "no board: objective order");
        assertNull(BingoBoardView.keyForNumber(withBoard, objectives, 0));
        assertNull(BingoBoardView.keyForNumber(withBoard, objectives, 4));
    }

    @Test
    void aSharedBoardCountsWhoHasEachTile() {
        var objectives = List.of(obj("a", "Kree'arra: Armadyl helmet", 50), obj("b", "B", 25));
        var standings = List.of(
                new Standing("u:1", 50, 1, Map.of("a", 1), Instant.EPOCH),
                new Standing("u:2", 75, 2, Map.of("a", 1, "b", 1), Instant.EPOCH));
        var tiles = BingoBoardView.shared(List.of("a", "b"), objectives, standings, 4);
        assertEquals(2, tiles.get(0).count());
        assertEquals(4, tiles.get(0).of());
        assertTrue(tiles.get(0).done());
        assertEquals(1, tiles.get(1).count());
        assertEquals("Kree'arra: Armadyl helmet", tiles.get(0).label());
    }

    @Test
    void aPersonalBoardMarksOnlyTheirOwnCompletions() {
        var objectives = List.of(obj("a", "A", 10), obj("b", "B", 10));
        var mine = new Standing("u:1", 10, 1, Map.of("a", 1), Instant.EPOCH);
        var tiles = BingoBoardView.personal(List.of("a", "b"), objectives, mine);
        assertTrue(tiles.get(0).done());
        assertEquals(false, tiles.get(1).done());
        assertEquals(0, tiles.get(0).of());
        assertEquals(false, BingoBoardView.personal(List.of("a"), objectives, null).get(0).done(), "someone with no progress has nothing done");
    }
}
