package com.runeclans.bot.rsnlink;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * The service's cryptography, all from the JDK (LINK-SYSTEM.md sections 6 and 13.1):
 * <ul>
 *   <li><b>Lookups</b>: HMAC-SHA-256 with {@code LINK_LOOKUP_KEY} over {@code kind|value}. Discord ids, tracking ids, IPs and codes are
 *       guessable, so an unkeyed hash would not protect them; the kind keeps an IP and a Discord id with the same digits apart.</li>
 *   <li><b>Discord ids at rest</b>: AES-256-GCM, a fresh 12-byte nonce per value, stored as {@code nonce || ciphertext+tag} next to a
 *       key version, so the key can be rotated by re-encrypting rows in the background ({@code LINK_ENC_KEY_OLD} decrypts the old ones).</li>
 *   <li><b>Aliases</b>: {@code "a1_" + base32(HMAC(LINK_ALIAS_KEY, "alias|v1|" + linkId + "|" + plugin))[0..26]}.</li>
 *   <li><b>Tokens and device secrets</b>: plain SHA-256. They are 32 random bytes, so a key adds nothing.</li>
 * </ul>
 * The AES key is derived from the configured key with HMAC, so any key of 32 bytes or more works and the raw key is never used twice.
 */
public final class LinkCrypto {
    /** Version written with every encrypted value. Bumped when {@code LINK_ENC_KEY} is rotated. */
    public static final short CURRENT_KEY_VERSION = 1;

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final char[] BASE32 = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();

    private final SecretKeySpec encKey;
    private final SecretKeySpec encKeyOld;
    private final byte[] lookupKey;
    private final byte[] aliasKey;
    private final SecureRandom random;

    public LinkCrypto(LinkSettings settings, SecureRandom random) {
        this.encKey = new SecretKeySpec(hmac(settings.encKey(), "rsnlink|enc|v1".getBytes(StandardCharsets.UTF_8)), "AES");
        this.encKeyOld = settings.encKeyOld() == null ? null
                : new SecretKeySpec(hmac(settings.encKeyOld(), "rsnlink|enc|v1".getBytes(StandardCharsets.UTF_8)), "AES");
        this.lookupKey = settings.lookupKey().clone();
        this.aliasKey = settings.aliasKey().clone();
        this.random = random;
    }

    /** The keyed fingerprint stored in a lookup column. {@code kind} is one of the {@code Subject} names, or "code"/"session". */
    public byte[] lookup(String kind, String value) {
        return hmac(lookupKey, (kind + "|" + value).getBytes(StandardCharsets.UTF_8));
    }

    public byte[] encrypt(String plain) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encKey, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(nonce.length + sealed.length).put(nonce).put(sealed).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM is always available", e);
        }
    }

    /** Opens a value sealed by {@link #encrypt}, with the old key when the row was written before a rotation. */
    public String decrypt(byte[] sealed, short keyVersion) {
        SecretKeySpec key = keyVersion == CURRENT_KEY_VERSION ? encKey : encKeyOld;
        if (key == null) throw new IllegalStateException("No key for version " + keyVersion + " (set LINK_ENC_KEY_OLD while rotating)");
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_BYTES));
            return new String(cipher.doFinal(sealed, NONCE_BYTES, sealed.length - NONCE_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("A stored Discord id couldn't be opened (wrong key?)", e);
        }
    }

    /** The alias a plugin sees for a link: stable for the link's life, different for every plugin name, meaningless without the key. */
    public String alias(String linkId, String pluginName) {
        byte[] mac = hmac(aliasKey, ("alias|v1|" + linkId + "|" + pluginName.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
        return "a1_" + base32(mac).substring(0, 26);
    }

    public static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    /** A short, non-reversible prefix of a lookup for log lines ("which subject was it" without the value). */
    public static String logPrefix(byte[] lookup) {
        return lookup == null ? "-" : java.util.HexFormat.of().formatHex(lookup, 0, 4);
    }

    static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is always available", e);
        }
    }

    /** RFC 4648 base32, lower case, no padding. */
    static String base32(byte[] data) {
        StringBuilder out = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32[(buffer >> (bits - 5)) & 31]);
                bits -= 5;
            }
        }
        if (bits > 0) out.append(BASE32[(buffer << (5 - bits)) & 31]);
        return out.toString();
    }
}
