package com.younglings.bot.wheel;

import org.w3c.dom.Node;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A spin of the wheel: picks the winner, then draws the wheel turning and slowing to a stop on them, as an animated GIF Discord plays in the chat.
 * The winner is chosen first, fairly (each entry is equally likely), and the animation is built to land on them, so the picture never decides anything.
 */
public final class WheelSpin {
    /** The animation and who won. */
    public record Result(String winner, int winnerIndex, byte[] gif, List<String> labels) {}

    private static final int FRAMES = 60;
    private static final int FRAME_MS = 70;
    private static final int HOLD_MS = 8000;
    private static final double TURNS = 5;

    private WheelSpin() {}

    /** Spins with a secure random winner. {@code entries} are the names in drawing order (one per entry). */
    public static Result spin(List<String> entries, int size) throws IOException {
        return spin(entries, size, new SecureRandom());
    }

    public static Result spin(List<String> entries, int size, Random random) throws IOException {
        if (entries.isEmpty()) throw new IllegalArgumentException("There's nobody on the wheel.");
        int n = entries.size();
        int winner = random.nextInt(n);
        double slice = 2 * Math.PI / n;
        // where inside the winning slice the pointer comes to rest: not the very edge
        double inside = 0.15 + random.nextDouble() * 0.7;
        // slice w spans [angle + w*slice, angle + (w+1)*slice]; the pointer is at 0, so the end angle makes w*slice + angle + inside*slice = 0 (mod 2pi)
        double endAngle = -(winner + inside) * slice;
        double startAngle = endAngle - TURNS * 2 * Math.PI;

        List<BufferedImage> frames = new ArrayList<>();
        List<Integer> delays = new ArrayList<>();
        for (int f = 0; f < FRAMES; f++) {
            double t = (double) f / (FRAMES - 1);
            double eased = 1 - Math.pow(1 - t, 3.2);
            frames.add(WheelImage.frame(entries, startAngle + (endAngle - startAngle) * eased, size, -1, "Spinning..."));
            delays.add(FRAME_MS);
        }
        // the last frame holds so it can be read, with the winner lit and named
        frames.add(WheelImage.frame(entries, endAngle, size, winner, entries.get(winner)));
        delays.add(HOLD_MS);
        return new Result(entries.get(winner), winner, gif(frames, delays), entries);
    }

    /** Which slice the pointer is on when the wheel has turned to {@code angle}. Used to check a spin lands where it says. */
    public static int sliceAtPointer(int n, double angle) {
        double slice = 2 * Math.PI / n;
        double a = (-angle) % (2 * Math.PI);
        if (a < 0) a += 2 * Math.PI;
        return (int) Math.floor(a / slice) % n;
    }

    static byte[] gif(List<BufferedImage> frames, List<Integer> delaysMs) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.prepareWriteSequence(null);
            for (int i = 0; i < frames.size(); i++) {
                IIOMetadata metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(frames.get(i)), param);
                String format = metadata.getNativeMetadataFormatName();
                IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(format);
                IIOMetadataNode control = node(root, "GraphicControlExtension");
                control.setAttribute("disposalMethod", "none");
                control.setAttribute("userInputFlag", "FALSE");
                control.setAttribute("transparentColorFlag", "FALSE");
                control.setAttribute("delayTime", Integer.toString(Math.max(2, delaysMs.get(i) / 10)));
                control.setAttribute("transparentColorIndex", "0");
                metadata.setFromTree(format, root);
                writer.writeToSequence(new IIOImage(frames.get(i), null, metadata), param);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static IIOMetadataNode node(IIOMetadataNode root, String name) {
        for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeName().equalsIgnoreCase(name)) return (IIOMetadataNode) child;
        }
        IIOMetadataNode created = new IIOMetadataNode(name);
        root.appendChild(created);
        return created;
    }
}
