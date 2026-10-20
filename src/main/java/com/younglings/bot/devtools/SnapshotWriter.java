package com.younglings.bot.devtools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Packs the snapshot's files into the zip. This is the single place bytes leave the snapshot, so it is also where every secret is
 * masked: each file's name and text pass through {@link SnapshotRedactor} on the way in, whatever produced them. Nothing is added to
 * the zip any other way.
 */
public final class SnapshotWriter {
    private SnapshotWriter() {}

    /** Thrown when the zip would be larger than the cap. */
    public static final class TooLargeException extends RuntimeException {
        public TooLargeException(long size, long cap) {
            super("The snapshot is " + size + " bytes, over the " + cap + " byte cap.");
        }
    }

    /**
     * @param files entry name to text, in the order they should appear
     * @param cap   the most bytes the zip may take
     */
    public static byte[] pack(Map<String, String> files, SnapshotRedactor redactor, long cap) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(redactor.scrub(file.getKey())));
                zip.write(redactor.scrub(file.getValue()).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (bytes.size() > cap) throw new TooLargeException(bytes.size(), cap);
        return bytes.toByteArray();
    }
}
