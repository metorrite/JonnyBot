package com.younglings.bot.upload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;
import java.util.Map;

/**
 * Shrinks the small pictures of a message (a thumbnail, an author or footer icon) to about twice the size Discord shows them at.
 * An upload straight from an artist is often a thousand pixels wide for a spot 80 pixels across; shrunk by whoever displays it,
 * thin edges (a ring around a logo, say) break up into a grainy dotted line. Shrinking once here, in steps with a smooth filter,
 * keeps them clean, and the stored picture is a fraction of the size.
 * <p>
 * PNG keeps its transparency and JPEG stays JPEG. GIF (it may be animated) and WebP (not readable here) are left exactly as
 * uploaded, and so is any picture that is already small enough or that can't be read: shrinking is a courtesy, never a reason to
 * refuse or damage an upload.
 */
final class ImageResizer {
    private static final Logger log = LoggerFactory.getLogger(ImageResizer.class);

    /** The longest side, by place, of pictures that are shown small. Places not listed (the big embed image) are never shrunk. */
    private static final Map<String, Integer> MAX_SIDE = Map.of("thumbnail", 256, "author_icon", 128, "footer_icon", 128);

    /** Refuses to decode anything larger than this many pixels, so a tiny file that expands to gigabytes can't exhaust memory. */
    private static final long MAX_PIXELS = 40_000_000L;

    private ImageResizer() {}

    /** {@code data} itself when nothing needs doing, else the shrunken picture in the same format. */
    static byte[] fitFor(String slot, String contentType, byte[] data) {
        Integer limit = MAX_SIDE.get(slot);
        if (limit == null) return data;
        boolean png = contentType.equals("image/png");
        if (!png && !contentType.equals("image/jpeg")) return data;

        try {
            BufferedImage source = read(data);
            if (source == null) return data;
            int width = source.getWidth();
            int height = source.getHeight();
            if (Math.max(width, height) <= limit) return data;

            double scale = (double) limit / Math.max(width, height);
            int targetWidth = Math.max(1, (int) Math.round(width * scale));
            int targetHeight = Math.max(1, (int) Math.round(height * scale));
            BufferedImage shrunk = shrink(source, targetWidth, targetHeight, png);

            byte[] result = png ? encodePng(shrunk) : encodeJpeg(shrunk);
            if (result == null) return data;
            return result.length < data.length ? result : data; // never make it bigger
        } catch (Exception | OutOfMemoryError e) {
            log.warn("Couldn't shrink an uploaded {} for {} ({}); keeping it as uploaded.", contentType, slot, e.toString());
            return data;
        }
    }

    private static byte[] encodePng(BufferedImage image) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        return ImageIO.write(image, "png", out) ? out.toByteArray() : null;
    }

    private static byte[] encodeJpeg(BufferedImage image) throws Exception {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) return null;
        ImageWriter writer = writers.next();
        ImageWriteParam params = writer.getDefaultWriteParam();
        params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        params.setCompressionQuality(0.92f);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, null), params);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /** Decodes the picture, or null if it can't be read or is too large to be worth reading. */
    private static BufferedImage read(byte[] data) throws Exception {
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                if ((long) reader.getWidth(0) * reader.getHeight(0) > MAX_PIXELS) return null;
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }

    /**
     * Halves the picture repeatedly until one more halving would overshoot, then makes the last, smaller step. Each step averages a
     * small neighbourhood instead of picking pixels, which is what keeps thin lines solid. Transparent pixels are blended with their
     * colour premultiplied by alpha, so the edge of a cut-out doesn't pick up a dark fringe from the invisible pixels beside it.
     */
    private static BufferedImage shrink(BufferedImage source, int targetWidth, int targetHeight, boolean keepAlpha) {
        int type = keepAlpha ? BufferedImage.TYPE_INT_ARGB_PRE : BufferedImage.TYPE_INT_RGB;
        BufferedImage current = copy(source, type);
        int width = current.getWidth();
        int height = current.getHeight();

        while (width > targetWidth || height > targetHeight) {
            width = Math.max(targetWidth, (width + 1) / 2);
            height = Math.max(targetHeight, (height + 1) / 2);
            BufferedImage next = new BufferedImage(width, height, type);
            Graphics2D g = next.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.drawImage(current, 0, 0, width, height, null);
            } finally {
                g.dispose();
            }
            current = next;
        }

        // back to ordinary (non-premultiplied) pixels for the encoder
        return keepAlpha ? copy(current, BufferedImage.TYPE_INT_ARGB) : current;
    }

    private static BufferedImage copy(BufferedImage source, int type) {
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), type);
        Graphics2D g = copy.createGraphics();
        try {
            g.drawImage(source, 0, 0, null);
        } finally {
            g.dispose();
        }
        return copy;
    }
}
