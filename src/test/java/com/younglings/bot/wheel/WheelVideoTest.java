package com.younglings.bot.wheel;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The optional video form of the wheel's spin. */
class WheelVideoTest {
    @Test
    void aRealSpinBecomesAnMp4() throws Exception {
        var spin = WheelSpin.spinTo(List.of("Ann", "Bob", "Cy", "Di"), 2, 240, new Random(1));

        byte[] mp4 = WheelVideo.fromGif(spin.gif());

        assertNotNull(mp4);
        assertTrue(mp4.length > 1000);
        // an MP4 starts with a box whose type is "ftyp"
        assertEquals("ftyp", new String(mp4, 4, 4, java.nio.charset.StandardCharsets.US_ASCII));
        assertTrue(mp4.length <= WheelVideo.LIMIT_BYTES);
    }

    @Test
    void anUnreadableGifGivesNoVideoSoTheGifIsUsed() {
        assertNull(WheelVideo.fromGif(new byte[]{1, 2, 3}));
    }

    @Test
    void theVideoKeepsTheSpinsTiming() {
        List<Integer> delays = List.of(100, 200, 4000);
        assertEquals(129, WheelVideo.videoFrameCount(delays)); // 4300 ms at 30 fps
        assertEquals(0, WheelVideo.pictureAt(delays, 0));
        assertEquals(1, WheelVideo.pictureAt(delays, 4));
        assertEquals(2, WheelVideo.pictureAt(delays, 128));
    }

    @Test
    void everyPictureGetsAtLeastOneVideoFrame() {
        assertEquals(3, WheelVideo.videoFrameCount(List.of(1, 1, 1)));
    }
}
