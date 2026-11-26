package io.github.c0decave.axross.companion.protocol;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The tokens the shell gave the service (docs/PROTOCOL.md, section 4): in
 * memory only, at most {@link #MAX}, the oldest dropped first. A token is 64
 * lowercase hex characters; its id is the first 32 hex characters of the
 * SHA-256 of those characters. Tokens, nonces and proofs are never logged.
 */
public final class Tokens {
    public static final int MAX = 8;
    public static final int TOKEN_CHARS = 64;
    public static final int ID_CHARS = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** By id, the oldest first. */
    private final Map<String, String> tokens = new LinkedHashMap<>();

    /**
     * Keeps a token (a ninth drops the oldest; one already known becomes the
     * newest). A malformed one is ignored.
     *
     * @return false when it was malformed
     */
    public synchronized boolean add(String token) {
        if (!isHex(token, TOKEN_CHARS)) {
            return false;
        }
        String id = id(token);
        tokens.remove(id);
        tokens.put(id, token);
        Iterator<String> it = tokens.keySet().iterator();
        while (tokens.size() > MAX) {
            it.next();
            it.remove();
        }
        return true;
    }

    /** The token with this id, or null. */
    public synchronized String byId(String id) {
        return id == null ? null : tokens.get(id);
    }

    public synchronized int size() {
        return tokens.size();
    }

    /** True for exactly {@code length} characters of 0-9 and a-f. */
    public static boolean isHex(String s, int length) {
        if (s == null || s.length() != length) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'f')) {
                return false;
            }
        }
        return true;
    }

    /** A token's id: the first 32 hex characters of SHA-256(its 64 characters). */
    public static String id(String token) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
            return hex(d).substring(0, ID_CHARS);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("no SHA-256", e);
        }
    }

    /** 32 random bytes as 64 lowercase hex characters. */
    public static String nonce() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return hex(b);
    }

    /** The companion's proof in hello's result. */
    public static String companionProof(String token, String clientNonce, String companionNonce) {
        return hmac(token, "axross-companion-2 companion " + clientNonce + " " + companionNonce);
    }

    /** The client's proof in auth. */
    public static String clientProof(String token, String companionNonce, String clientNonce) {
        return hmac(token, "axross-companion-2 client " + companionNonce + " " + clientNonce);
    }

    /** Lowercase hex HMAC-SHA256, the key the token's characters (UTF-8). */
    static String hmac(String token, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("no HmacSHA256", e);
        }
    }

    /** Compares two proofs in constant time. */
    public static boolean same(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }

    static String hex(byte[] b) {
        char[] digits = "0123456789abcdef".toCharArray();
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(digits[(x >> 4) & 0xf]).append(digits[x & 0xf]);
        }
        return sb.toString();
    }
}
