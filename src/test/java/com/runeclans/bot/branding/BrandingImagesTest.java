package com.runeclans.bot.branding;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BrandingImagesTest {
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Test
    void acceptsAPictureAsPlainBase64() {
        assertArrayEquals(PNG, BrandingImages.decode(b64(PNG), "avatar"));
    }

    @Test
    void acceptsABrowserDataUrl() {
        assertArrayEquals(PNG, BrandingImages.decode("data:image/png;base64," + b64(PNG), "avatar"));
    }

    @Test
    void refusesWhatIsNotAPicture() {
        var e = assertThrows(IllegalArgumentException.class, () -> BrandingImages.decode(b64("hello world, not a picture".getBytes()), "banner"));
        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("banner"));
    }

    @Test
    void refusesNonBase64AndEmpty() {
        assertThrows(IllegalArgumentException.class, () -> BrandingImages.decode("!!!not base64!!!", "avatar"));
        assertThrows(IllegalArgumentException.class, () -> BrandingImages.decode("", "avatar"));
    }

    @Test
    void refusesAPictureOverTheLimit() {
        byte[] big = new byte[BrandingImages.MAX_BYTES + 1];
        System.arraycopy(PNG, 0, big, 0, PNG.length);
        assertThrows(IllegalArgumentException.class, () -> BrandingImages.decode(b64(big), "avatar"));
    }
}
