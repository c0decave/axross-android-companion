package io.github.c0decave.axross.companion.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class TokensTest {
    private static final String CLIENT_NONCE = Wire.CLIENT_NONCE;
    private static final String COMPANION_NONCE = "0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f";

    /** The values of docs/PROTOCOL.md's rules, computed with Python's hashlib and hmac. */
    @Test
    public void knownValues() {
        assertEquals("2a8abfa8cb9906290437854193ca6bca", Tokens.id(Wire.TOKEN));
        assertEquals("bad1374a38088e6c1714b14f9bf2ba4b9b364dd27126ec8b352e5f697e549637",
                Tokens.companionProof(Wire.TOKEN, CLIENT_NONCE, COMPANION_NONCE));
        assertEquals("0885d2f485e2aea07717b650e58bdb970bb3ebc02b0a02db95f46e92f7f0be87",
                Tokens.clientProof(Wire.TOKEN, COMPANION_NONCE, CLIENT_NONCE));
    }

    @Test
    public void nonces() {
        String a = Tokens.nonce();
        assertTrue(Tokens.isHex(a, 64));
        assertNotEquals(a, Tokens.nonce());
    }

    @Test
    public void malformedTokensAreIgnored() {
        Tokens t = new Tokens();
        for (String bad : new String[] {null, "", "abc", Wire.TOKEN.toUpperCase(), Wire.TOKEN + "0",
                Wire.TOKEN.substring(1), Wire.TOKEN.substring(1) + "g", Wire.TOKEN.substring(1) + " "}) {
            assertFalse(String.valueOf(bad), t.add(bad));
        }
        assertEquals(0, t.size());
        assertTrue(t.add(Wire.TOKEN));
        assertEquals(Wire.TOKEN, t.byId(Tokens.id(Wire.TOKEN)));
        assertNull(t.byId(null));
        assertNull(t.byId(Wire.TOKEN));
    }

    @Test
    public void atMostEightTheOldestGoesFirst() {
        Tokens t = new Tokens();
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String token = String.format("%064x", i + 1);
            tokens.add(token);
        }
        for (int i = 0; i < 8; i++) {
            assertTrue(t.add(tokens.get(i)));
        }
        // (the first given again: the newest now)
        assertTrue(t.add(tokens.get(0)));
        assertEquals(8, t.size());
        assertTrue(t.add(tokens.get(8)));
        assertEquals(Tokens.MAX, t.size());
        assertNull("the oldest is dropped", t.byId(Tokens.id(tokens.get(1))));
        assertEquals(tokens.get(0), t.byId(Tokens.id(tokens.get(0))));
        assertTrue(t.add(tokens.get(9)));
        assertNull(t.byId(Tokens.id(tokens.get(2))));
        assertEquals(tokens.get(9), t.byId(Tokens.id(tokens.get(9))));
    }

    @Test
    public void same() {
        assertTrue(Tokens.same("ab", "ab"));
        assertFalse(Tokens.same("ab", "ac"));
        assertFalse(Tokens.same("ab", "abc"));
        assertFalse(Tokens.same(null, "ab"));
        assertFalse(Tokens.same("ab", null));
    }
}
