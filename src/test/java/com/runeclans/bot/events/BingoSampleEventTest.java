package com.runeclans.bot.events;

import com.runeclans.bot.events.BingoBoardImage.TileView;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BingoSampleEventTest {
    private final BingoTemplate template = new BingoTemplate();

    private BingoSampleEvent.Sample sample(BingoLayout layout, long seed) {
        return BingoSampleEvent.build(template, layout, label -> null, seed);
    }

    @Test
    void everyShapeGetsAFullBoardPlayedPartWay() {
        for (BingoLayout layout : BingoLayout.all()) {
            var s = sample(layout, 20261016L);
            assertEquals(layout.tiles(), s.tiles().size(), layout.id());
            assertTrue(s.completed() > 0, layout.id() + " has some tiles done");
            assertTrue(s.completed() < layout.tiles(), layout.id() + " is not finished");
        }
    }

    @Test
    void aTileIsOnlyDoneWhenWhatItNeedsIsDone() {
        for (BingoLayout layout : BingoLayout.all()) {
            List<TileView> tiles = sample(layout, 20261016L).tiles();
            for (TileView t : tiles) {
                if (!t.done()) continue;
                for (int need : layout.requires().getOrDefault(t.number(), List.of())) {
                    assertTrue(tiles.get(need - 1).done(), layout.id() + " tile " + t.number() + " is done but needs " + need);
                }
            }
        }
    }

    @Test
    void aGridIsPlayedPartWayToo() {
        for (int size = BingoBoard.MIN_SIZE; size <= BingoBoard.MAX_SIZE; size++) {
            var s = BingoSampleEvent.buildGrid(template, size, label -> null, 20261016L);
            assertEquals(size * size, s.tiles().size(), "size " + size);
            assertTrue(s.completed() > 0 && s.completed() < size * size, "size " + size + " has some tiles done but not all");
        }
    }

    @Test
    void theSameSeedGivesTheSameEvent() {
        BingoLayout layout = BingoLayout.find(BingoLayout.PYRAMID).orElseThrow();
        assertEquals(sample(layout, 5).tiles(), sample(layout, 5).tiles());
    }

    @Test
    void everyShapeDrawsInBothLooks() throws Exception {
        for (BingoLayout layout : BingoLayout.all()) {
            var s = sample(layout, 20261016L);
            for (String look : BingoLayout.looks()) {
                var image = ImageIO.read(new ByteArrayInputStream(BingoBoardImage.render(layout, look, s.tiles())));
                assertEquals(1600, image.getWidth(), layout.id() + " " + look);
                assertEquals(1000, image.getHeight(), layout.id() + " " + look);
            }
        }
    }
}
