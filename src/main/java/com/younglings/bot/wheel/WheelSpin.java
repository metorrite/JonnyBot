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

    /** A GIF over this size won't upload to Discord (about 10 MB for most servers), so a bigger spin is redrawn smaller. */
    static final int GIF_LIMIT_BYTES = 9 * 1024 * 1024;
    private static final int HOLD_MS = 4000;
    private static final double TURNS = 3;
    private static final double SPIN_SECONDS = 4.5;
    /** The slow-down curve: the wheel starts fast and the speed falls away to nothing as it settles on the winner. */
    private static final double EASE_POWER = 2.4;
    /** The most the wheel may turn between two frames, so fast turning still reads as turning. */
    private static final double MAX_STEP_RADIANS = 0.2;
    private static final int MIN_FRAME_MS = 20;
    private static final int MAX_FRAME_MS = 110;

    private WheelSpin() {}

    /** Spins with a secure random winner. {@code entries} are the names in drawing order (one per entry). */
    public static Result spin(List<String> entries, int size) throws IOException {
        return spin(entries, size, new SecureRandom());
    }

    public static Result spin(List<String> entries, int size, Random random) throws IOException {
        if (entries.isEmpty()) throw new IllegalArgumentException("There's nobody on the wheel.");
        return spinTo(entries, random.nextInt(entries.size()), size, random);
    }

    /** Spins and stops on a winner that has already been chosen (by the dashboard, say), so the picture matches. */
    public static Result spinTo(List<String> entries, int winner, int size, Random random) throws IOException {
        if (entries.isEmpty()) throw new IllegalArgumentException("There's nobody on the wheel.");
        if (winner < 0 || winner >= entries.size()) throw new IllegalArgumentException("That winner isn't on the wheel.");
        int n = entries.size();
        double slice = 2 * Math.PI / n;
        // where inside the winning slice the pointer comes to rest: not the very edge
        double inside = 0.15 + random.nextDouble() * 0.7;
        // slice w spans [angle + w*slice, angle + (w+1)*slice]; the pointer is at 0, so the end angle makes w*slice + angle + inside*slice = 0 (mod 2pi)
        double endAngle = -(winner + inside) * slice;
        double startAngle = endAngle - TURNS * 2 * Math.PI;

        // the smoothest GIF that still fits: if it is too big for Discord, draw the same spin smaller
        byte[] gif = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            int drawSize = attempt == 0 ? size : attempt == 1 ? size * 5 / 6 : size * 2 / 3;
            gif = gif(frames(entries, winner, startAngle, endAngle, drawSize), delays(startAngle, endAngle));
            if (gif.length <= GIF_LIMIT_BYTES) break;
        }
        return new Result(entries.get(winner), winner, gif, entries);
    }

    /** When each frame of the spin is shown: close together while the wheel is fast, further apart as it slows, so it is smooth where it matters. */
    static List<Double> times(double startAngle, double endAngle) {
        double distance = endAngle - startAngle;
        List<Double> times = new ArrayList<>();
        double t = 0;
        while (t < SPIN_SECONDS) {
            times.add(t);
            double speed = distance * EASE_POWER / SPIN_SECONDS * Math.pow(1 - t / SPIN_SECONDS, EASE_POWER - 1);
            double dt = Math.abs(speed) < 1e-9 ? MAX_FRAME_MS / 1000.0 : MAX_STEP_RADIANS / Math.abs(speed);
            t += Math.min(MAX_FRAME_MS / 1000.0, Math.max(MIN_FRAME_MS / 1000.0, dt));
        }
        times.add(SPIN_SECONDS);
        return times;
    }

    static double angleAt(double startAngle, double endAngle, double seconds) {
        double progress = Math.min(1, seconds / SPIN_SECONDS);
        return startAngle + (endAngle - startAngle) * (1 - Math.pow(1 - progress, EASE_POWER));
    }

    private static List<BufferedImage> frames(List<String> entries, int winner, double startAngle, double endAngle, int size) {
        List<BufferedImage> frames = new ArrayList<>();
        for (double t : times(startAngle, endAngle)) frames.add(WheelImage.frame(entries, angleAt(startAngle, endAngle, t), size, -1, "Spinning..."));
        // the last frame holds so it can be read, with the winner lit and named
        frames.add(WheelImage.frame(entries, endAngle, size, winner, entries.get(winner)));
        return frames;
    }

    private static List<Integer> delays(double startAngle, double endAngle) {
        List<Double> times = times(startAngle, endAngle);
        List<Integer> delays = new ArrayList<>();
        for (int i = 0; i + 1 < times.size(); i++) delays.add((int) Math.round((times.get(i + 1) - times.get(i)) * 1000));
        delays.add(MIN_FRAME_MS); // the last turning frame, then the one that holds
        delays.add(HOLD_MS);
        return delays;
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
