package com.younglings.bot.wheel;

import org.jcodec.api.awt.AWTSequenceEncoder;
import org.jcodec.common.io.ByteBufferSeekableByteChannel;
import org.jcodec.common.model.Rational;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * An optional way to post the wheel's spin: the same picture as the GIF, encoded as an MP4 (H.264) video. Discord plays a video inline, but it does not
 * start by itself or loop like a GIF, so the GIF stays the default; the video is smoother and usually smaller. It is built from the GIF's frames and
 * their timings, so the video always shows exactly the spin the GIF would, whichever way the spin was started.
 */
public final class WheelVideo {
    private static final Logger log = LoggerFactory.getLogger(WheelVideo.class);

    /** Frames per second of the video. The spin's own frames are re-timed onto this steady rate. */
    static final int FPS = 30;
    /** A video over this size won't upload to Discord, so the GIF is posted instead. */
    static final int LIMIT_BYTES = 9 * 1024 * 1024;

    private WheelVideo() {}

    /** One picture of the spin and how long it is shown. */
    record Frame(BufferedImage image, int delayMs) {}

    /**
     * The spin as MP4 bytes, or {@code null} when it can't be made (an unreadable GIF, an encoder problem, or a result too big for Discord), in which case
     * the caller posts the GIF as it always did.
     */
    public static byte[] fromGif(byte[] gif) {
        try {
            byte[] mp4 = toMp4(gif);
            return mp4 != null && mp4.length <= LIMIT_BYTES ? mp4 : null;
        } catch (IOException | RuntimeException e) {
            log.warn("Couldn't turn the wheel's spin into a video; the GIF is posted instead", e);
            return null;
        }
    }

    static byte[] toMp4(byte[] gif) throws IOException {
        List<Frame> frames = readGif(gif);
        return frames.isEmpty() ? null : encode(frames);
    }

    /** How many video frames a spin of these delays needs at {@link #FPS}: at least one frame per picture so a short one is never dropped. */
    static int videoFrameCount(List<Integer> delaysMs) {
        long total = 0;
        for (int d : delaysMs) total += d;
        return (int) Math.max(delaysMs.size(), Math.round(total * FPS / 1000.0));
    }

    /** Which picture is on screen at {@code videoFrame} when each is shown for its delay. */
    static int pictureAt(List<Integer> delaysMs, int videoFrame) {
        double timeMs = (videoFrame + 0.5) * 1000.0 / FPS;
        double end = 0;
        for (int i = 0; i < delaysMs.size(); i++) {
            end += delaysMs.get(i);
            if (timeMs < end) return i;
        }
        return delaysMs.size() - 1;
    }

    private static byte[] encode(List<Frame> frames) throws IOException {
        List<Integer> delays = frames.stream().map(Frame::delayMs).toList();
        int count = videoFrameCount(delays);
        BufferedImage first = frames.get(0).image();
        // H.264 wants even sides
        int w = first.getWidth() & ~1;
        int h = first.getHeight() & ~1;

        ByteBuffer buffer = ByteBuffer.allocate(LIMIT_BYTES + 1024 * 1024);
        ByteBufferSeekableByteChannel channel = new ByteBufferSeekableByteChannel(buffer, 0);
        AWTSequenceEncoder encoder = new AWTSequenceEncoder(channel, Rational.R(FPS, 1));
        int previous = -1;
        BufferedImage cropped = null;
        for (int i = 0; i < count; i++) {
            int index = pictureAt(delays, i);
            if (index != previous) {
                BufferedImage image = frames.get(index).image();
                cropped = image.getWidth() == w && image.getHeight() == h ? image : image.getSubimage(0, 0, w, h);
                previous = index;
            }
            encoder.encodeImage(cropped);
        }
        encoder.finish();
        ByteBuffer contents = channel.getContents();
        byte[] out = new byte[contents.remaining()];
        contents.get(out);
        return out;
    }

    /** Reads every picture of a GIF with its delay. Our GIFs write each frame in full, so no compositing is needed. */
    static List<Frame> readGif(byte[] gif) throws IOException {
        List<Frame> frames = new ArrayList<>();
        ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(gif))) {
            reader.setInput(in, false);
            int n = reader.getNumImages(true);
            for (int i = 0; i < n; i++) {
                BufferedImage image = reader.read(i);
                // the frames come back as palette pictures; redraw as plain RGB for the encoder
                BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
                rgb.getGraphics().drawImage(image, 0, 0, null);
                frames.add(new Frame(rgb, delayOf(reader, i)));
            }
        } finally {
            reader.dispose();
        }
        return frames;
    }

    private static int delayOf(ImageReader reader, int index) throws IOException {
        IIOMetadataNode root = (IIOMetadataNode) reader.getImageMetadata(index).getAsTree("javax_imageio_gif_image_1.0");
        for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeName().equalsIgnoreCase("GraphicControlExtension")) {
                String delay = ((IIOMetadataNode) child).getAttribute("delayTime");
                try {
                    return Math.max(20, Integer.parseInt(delay) * 10);
                } catch (NumberFormatException e) {
                    return 100;
                }
            }
        }
        return 100;
    }
}
