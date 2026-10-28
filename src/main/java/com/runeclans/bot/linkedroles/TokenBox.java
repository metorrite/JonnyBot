package com.runeclans.bot.linkedroles;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Seals a person's Discord OAuth tokens at rest: AES-256-GCM under a key derived from {@code LINKED_ROLES_TOKEN_KEY}, a fresh 12-byte IV per
 * value, and the person's Discord id as associated data, so a sealed token copied onto another row doesn't open. Layout: version byte,
 * IV, ciphertext with its tag. Never logs a value.
 */
public final class TokenBox {
    private static final byte VERSION = 1;
    private static final int IV = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public TokenBox(byte[] secret) {
        if (secret == null || secret.length < 32) throw new IllegalArgumentException("the token key needs 32 bytes or more");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            this.key = new SecretKeySpec(mac.doFinal("linkedroles|token|v1".getBytes(StandardCharsets.UTF_8)), "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    public byte[] seal(String value, long discordUserId) {
        try {
            byte[] iv = new byte[IV];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            cipher.updateAAD(Long.toUnsignedString(discordUserId).getBytes(StandardCharsets.UTF_8));
            byte[] body = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[1 + IV + body.length];
            out[0] = VERSION;
            System.arraycopy(iv, 0, out, 1, IV);
            System.arraycopy(body, 0, out, 1 + IV, body.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("sealing failed", e);
        }
    }

    /** The value, or null when it was sealed under another key, for another person, or is damaged. */
    public String open(byte[] sealed, long discordUserId) {
        if (sealed == null || sealed.length < 1 + IV + 16 || sealed[0] != VERSION) return null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Arrays.copyOfRange(sealed, 1, 1 + IV)));
            cipher.updateAAD(Long.toUnsignedString(discordUserId).getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(sealed, 1 + IV, sealed.length - 1 - IV), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            return null;
        }
    }
}
