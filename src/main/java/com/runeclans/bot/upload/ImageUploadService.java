package com.runeclans.bot.upload;

import io.github.freya022.botcommands.api.core.service.annotations.BService;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Takes the pictures a server uploads for its messages: checks they really are small web pictures, keeps one per place, and hands
 * them back by an unguessable token. Only the places listed in {@link #SLOTS} accept one, so an upload can't be used as free storage.
 */
@BService
public class ImageUploadService {
    /** Large enough for any picture an embed shows, small enough that a few hundred servers never matter to the database. */
    public static final int MAX_BYTES = 2 * 1024 * 1024;

    /** The places a picture can go, by scope: today only the welcome message's embed. */
    static final Map<String, Set<String>> SLOTS = Map.of("welcome", Set.of("author_icon", "thumbnail", "image", "footer_icon", "card_bg"),
            // the website builder's logo and banner (T-301)
            "site", Set.of("logo", "banner"));

    public static class InvalidImageException extends RuntimeException {
        public InvalidImageException(String message) {
            super(message);
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UploadedImageRepository repository;

    public ImageUploadService(UploadedImageRepository repository) {
        this.repository = repository;
    }

    public static boolean isKnownSlot(String scope, String slot) {
        return SLOTS.getOrDefault(scope, Set.of()).contains(slot);
    }

    /** Stores {@code data} for this place, replacing the previous picture there. */
    public UploadedImageRepository.Saved save(long guildId, String scope, String slot, byte[] data) {
        if (!isKnownSlot(scope, slot)) throw new InvalidImageException("That isn't a place a picture can go.");
        if (data == null || data.length == 0) throw new InvalidImageException("That file is empty.");
        if (data.length > MAX_BYTES) throw new InvalidImageException("That picture is too big. Keep it under " + (MAX_BYTES / (1024 * 1024)) + " MB.");
        String type = sniff(data).orElseThrow(() -> new InvalidImageException("Upload a PNG, JPEG, GIF or WebP picture."));
        return repository.put(guildId, scope, slot, newToken(), type, ImageResizer.fitFor(slot, type, data));
    }

    public boolean remove(long guildId, String scope, String slot) {
        return isKnownSlot(scope, slot) && repository.delete(guildId, scope, slot);
    }

    // ---------- a clan website's picture library (T-408, docs/BUILDER-V3.md 5.4) ----------

    /**
     * The scope of a clan website's picture library: gallery pictures and every single-picture field and section background of the site
     * editor. Unlike the places above, a library holds many pictures, each under its own token (its slot is the token), up to the site's
     * plan limit. A plan that ends keeps every picture (docs/BUILDER-V3.md 10.4); only new uploads count against the lower limit.
     */
    public static final String LIBRARY = "site-gallery";

    /** Adds a picture to the server's site library unless it already holds {@code limit} pictures. Empty when it is full. */
    public Optional<UploadedImageRepository.Saved> addToLibrary(long guildId, byte[] data, long limit) {
        if (data == null || data.length == 0) throw new InvalidImageException("That file is empty.");
        if (data.length > MAX_BYTES) throw new InvalidImageException("That picture is too big. Keep it under " + (MAX_BYTES / (1024 * 1024)) + " MB.");
        String type = sniff(data).orElseThrow(() -> new InvalidImageException("Upload a PNG, JPEG, GIF or WebP picture."));
        return repository.addIfRoom(guildId, LIBRARY, newToken(), type, ImageResizer.fitFor(LIBRARY, type, data), limit);
    }

    /** The server's site library, oldest first. */
    public List<UploadedImageRepository.Listed> library(long guildId) {
        return repository.list(guildId, LIBRARY);
    }

    /** Removes one picture from the server's site library. False if that token isn't one of this server's library pictures. */
    public boolean removeFromLibrary(long guildId, String token) {
        return token != null && token.matches("[0-9a-f]{32}") && repository.delete(guildId, LIBRARY, token);
    }

    public Optional<UploadedImageRepository.Stored> find(String token) {
        return token == null || !token.matches("[0-9a-f]{32}") ? Optional.empty() : repository.findByToken(token);
    }

    /** The picture's real type from its first bytes, never from what the uploader claimed. */
    static Optional<String> sniff(byte[] d) {
        if (d.length >= 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') return Optional.of("image/png");
        if (d.length >= 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) return Optional.of("image/jpeg");
        if (d.length >= 6 && d[0] == 'G' && d[1] == 'I' && d[2] == 'F' && d[3] == '8') return Optional.of("image/gif");
        if (d.length >= 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F' && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') return Optional.of("image/webp");
        return Optional.empty();
    }

    private static String newToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
