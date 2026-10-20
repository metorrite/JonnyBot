package com.younglings.bot.rsnlink;

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.NamedParameterSpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

/**
 * Signed alias assertions (LINK-SYSTEM.md section 13.2): a compact JWS (EdDSA over Ed25519) that a dependent's own server can check
 * against the public key at {@code /v1/keys}, so it can trust that an alias came from this service. The claims are the issuer, the
 * plugin, the alias, the local player's own RSN (which the dependent already knows), the verification level and a 15-minute expiry.
 * No Discord data is ever in one.
 * <p>
 * The key pair is made from the 32-byte seed in {@code LINK_SIGNING_KEY}: the JDK has no "public key from private key" call for
 * Ed25519, but its generator draws exactly the 32 seed bytes from the random source it is given, so a source that returns the seed
 * rebuilds the same pair every time. A self-check at start-up signs and verifies once, so a JDK that ever changed that would fail loudly.
 */
public final class AliasSigner {
    public static final long LIFETIME_SECONDS = 15 * 60;

    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final String keyId;
    private final byte[] rawPublic;
    private final String issuer;

    public AliasSigner(byte[] seed, String issuer) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            generator.initialize(NamedParameterSpec.ED25519, new SeedRandom(seed));
            KeyPair pair = generator.generateKeyPair();
            this.privateKey = pair.getPrivate();
            this.publicKey = pair.getPublic();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 is always available on Java 21", e);
        }
        byte[] encoded = publicKey.getEncoded();
        // X.509 SubjectPublicKeyInfo for Ed25519 is a fixed 12-byte header and then the 32 raw key bytes.
        this.rawPublic = Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
        this.keyId = "rsl-" + LinkCrypto.base32(LinkCrypto.sha256(b64(rawPublic))).substring(0, 10);
        this.issuer = issuer;
        if (!verify(sign(DataObject.empty().put("check", true)))) throw new IllegalStateException("The signing key failed its self-check");
    }

    /** The assertion for one alias. {@code disputed} is set while another character's claim on the name is waiting to be settled. */
    public String assertion(String plugin, String alias, String rsnLookup, String verification, boolean disputed, Instant now) {
        DataObject claims = DataObject.empty().put("iss", issuer).put("plugin", plugin).put("alias", alias).put("rsn", rsnLookup)
                .put("ver", verification).put("iat", now.getEpochSecond()).put("exp", now.getEpochSecond() + LIFETIME_SECONDS);
        if (disputed) claims.put("disputed", true);
        return sign(claims);
    }

    /** The public key set, JWKS shape (OKP / Ed25519), for {@code GET /v1/keys}. */
    public DataObject keySet() {
        return DataObject.empty().put("keys", DataArray.empty().add(DataObject.empty().put("kty", "OKP").put("crv", "Ed25519").put("alg", "EdDSA")
                .put("use", "sig").put("kid", keyId).put("x", b64(rawPublic))));
    }

    String sign(DataObject claims) {
        String header = b64(DataObject.empty().put("alg", "EdDSA").put("typ", "JWT").put("kid", keyId).toJson());
        String payload = b64(claims.toJson());
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(privateKey);
            signer.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
            return header + "." + payload + "." + b64(signer.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Signing failed", e);
        }
    }

    /** Checks a compact JWS made by this signer (used by the self-check and by tests; dependents check with any JOSE library). */
    public boolean verify(String jws) {
        String[] parts = jws.split("\\.");
        if (parts.length != 3) return false;
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            return verifier.verify(Base64.getUrlDecoder().decode(parts[2]));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    public String keyId() {
        return keyId;
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String b64(String text) {
        return b64(text.getBytes(StandardCharsets.UTF_8));
    }

    /** A "random" source that hands out the seed and nothing else, so the generator rebuilds the configured key pair. */
    private static final class SeedRandom extends SecureRandom {
        private final byte[] seed;
        private int offset;

        SeedRandom(byte[] seed) {
            this.seed = seed.clone();
        }

        @Override
        public void nextBytes(byte[] bytes) {
            if (offset + bytes.length > seed.length) throw new IllegalStateException("The Ed25519 generator asked for more than the 32-byte seed");
            System.arraycopy(seed, offset, bytes, 0, bytes.length);
            offset += bytes.length;
        }
    }
}
