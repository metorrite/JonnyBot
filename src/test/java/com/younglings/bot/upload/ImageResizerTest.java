package com.younglings.bot.upload;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageResizerTest {
    /** A red disc with a thin white ring, on a fully transparent background: the kind of logo that goes grainy when shrunk carelessly. */
    private static byte[] ringLogoPng(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillOval(0, 0, width - 1, height - 1);
        g.setColor(new Color(150, 0, 0));
        int ring = Math.max(2, width / 100);
        g.fillOval(ring, ring, width - 1 - 2 * ring, height - 1 - 2 * ring);
        g.dispose();
        return write(image, "png");
    }

    private static byte[] write(BufferedImage image, String format) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    private static BufferedImage read(byte[] data) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(data));
    }

    @Test
    void aLargeThumbnailIsShrunkToTheLimitKeepingItsProportions() throws Exception {
        byte[] shrunk = ImageResizer.fitFor("thumbnail", "image/png", ringLogoPng(1166, 1186));

        BufferedImage image = read(shrunk);
        assertEquals(256, Math.max(image.getWidth(), image.getHeight()));
        assertEquals(1166.0 / 1186.0, (double) image.getWidth() / image.getHeight(), 0.01);
    }

    @Test
    void transparencyIsKeptAndTheEdgeHasNoDarkFringe() throws Exception {
        BufferedImage image = read(ImageResizer.fitFor("thumbnail", "image/png", ringLogoPng(1000, 1000)));

        assertEquals(0, image.getRGB(0, 0) >>> 24, "the corner is still transparent");
        assertEquals(255, image.getRGB(image.getWidth() / 2, image.getHeight() / 2) >>> 24, "the middle is still opaque");

        // along the left edge of the ring, every partly transparent pixel must be as light as the white ring, never darkened by the empty pixels beside it
        int y = image.getHeight() / 2;
        for (int x = 0; x < image.getWidth() / 4; x++) {
            int argb = image.getRGB(x, y);
            int alpha = argb >>> 24;
            if (alpha > 0 && alpha < 255) assertTrue(((argb >> 8) & 0xFF) > 150, "edge pixel at x=" + x + " looks dark: " + Integer.toHexString(argb));
        }
    }

    @Test
    void iconsAreShrunkToTheirOwnSmallerLimit() throws Exception {
        assertEquals(128, read(ImageResizer.fitFor("author_icon", "image/png", ringLogoPng(600, 600))).getWidth());
        assertEquals(128, read(ImageResizer.fitFor("footer_icon", "image/png", ringLogoPng(600, 600))).getWidth());
    }

    @Test
    void theBigEmbedImageIsNeverShrunk() throws Exception {
        byte[] original = ringLogoPng(1200, 1200);

        assertSame(original, ImageResizer.fitFor("image", "image/png", original));
    }

    @Test
    void aPictureAlreadySmallEnoughIsLeftAlone() throws Exception {
        byte[] original = ringLogoPng(200, 200);

        assertSame(original, ImageResizer.fitFor("thumbnail", "image/png", original));
    }

    @Test
    void aJpegStaysAJpeg() throws Exception {
        BufferedImage rgb = new BufferedImage(900, 900, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, 900, 900);
        g.dispose();
        byte[] original = write(rgb, "jpg");

        byte[] shrunk = ImageResizer.fitFor("thumbnail", "image/jpeg", original);

        assertEquals(256, read(shrunk).getWidth());
        assertEquals((byte) 0xFF, shrunk[0]);
        assertEquals((byte) 0xD8, shrunk[1]);
    }

    @Test
    void gifsAndWebpAreLeftExactlyAsUploaded() {
        byte[] gif = {'G', 'I', 'F', '8', '9', 'a', 1, 0, 1, 0};
        byte[] webp = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};

        assertSame(gif, ImageResizer.fitFor("thumbnail", "image/gif", gif));
        assertSame(webp, ImageResizer.fitFor("thumbnail", "image/webp", webp));
    }

    @Test
    void somethingThatLooksLikeAPngButCannotBeReadIsKeptAsUploaded() {
        byte[] broken = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};

        assertArrayEquals(broken, ImageResizer.fitFor("thumbnail", "image/png", broken));
    }
}
