package com.runeclans.bot.branding;

import java.util.Base64;

/** Checks a picture sent from the dashboard (as base64) before it is handed to Discord as the bot's server avatar or banner. */
public final class BrandingImages {
    /** Largest picture accepted, after decoding. Discord allows more, but the dashboard shrinks pictures first and this keeps requests small. */
    public static final int MAX_BYTES = 2 * 1024 * 1024;

    private BrandingImages() {}

    /**
     * The picture's bytes.
     *
     * @throws IllegalArgumentException with a message fit to show the person, when the text isn't base64, is empty or too big, or isn't a
     *                                  PNG, JPEG, GIF or WebP picture
     */
    public static byte[] decode(String base64, String what) {
        byte[] bytes;
        try {
            String text = base64 == null ? "" : base64.strip();
            int comma = text.indexOf(',');
            if (text.startsWith("data:") && comma > 0) text = text.substring(comma + 1); // a data URL from the browser
            bytes = Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The " + what + " couldn't be read. Try another picture.");
        }
        if (bytes.length == 0) throw new IllegalArgumentException("The " + what + " was empty.");
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("The " + what + " is too big. Keep it under " + MAX_BYTES / (1024 * 1024) + " MB.");
        if (!isPicture(bytes)) throw new IllegalArgumentException("The " + what + " must be a PNG, JPEG, GIF or WebP picture.");
        return bytes;
    }

    static boolean isPicture(byte[] b) {
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return true;
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return true;
        if (b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') return true;
        return b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
    }
}
