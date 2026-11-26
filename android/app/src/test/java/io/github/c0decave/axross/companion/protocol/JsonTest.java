package io.github.c0decave.axross.companion.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class JsonTest {
    @Test
    public void parsesValues() throws Exception {
        Map<String, Object> m = Json.parseObject(
                "{\"a\":1,\"b\":-2.5,\"c\":\"x\\n\\u00e4\\ud83d\\ude00\",\"d\":[true,false,null],\"e\":{}}");
        assertEquals(1L, m.get("a"));
        assertEquals(-2.5, (Double) m.get("b"), 0);
        assertEquals("x\n\u00e4\ud83d\ude00", m.get("c"));
        List<?> d = (List<?>) m.get("d");
        assertEquals(Boolean.TRUE, d.get(0));
        assertSame(Json.NULL, d.get(2));
        assertTrue(((Map<?, ?>) m.get("e")).isEmpty());
    }

    @Test
    public void integersAre64Bit() throws Exception {
        assertEquals(Long.MAX_VALUE, Json.parse("9223372036854775807"));
        assertEquals(Long.MIN_VALUE, Json.parse("-9223372036854775808"));
        // (beyond 64 bits: a Double, which no id check accepts)
        assertTrue(Json.parse("9223372036854775808") instanceof Double);
        assertTrue(Json.parse("1e3") instanceof Double);
        assertTrue(Json.parse("1.0") instanceof Double);
    }

    @Test
    public void refusesWhatIsNotStrictJson() {
        for (String bad : Arrays.asList("", " ", "{", "{\"a\":1,}", "[1,]", "{\"a\":1}x", "{'a':1}", "{\"a\":01}",
                "NaN", "{\"a\":1,\"a\":2}", "\"\u0001\"", "\"\\x\"", "\"\\u12\"", "tru", "-", "1.", "1e", "{a:1}",
                "1e999")) {
            try {
                Json.parse(bad);
                fail("parsed: " + bad);
            } catch (Json.ParseException expected) {
                // (refused)
            }
        }
    }

    @Test
    public void limitsTheDepth() throws Exception {
        StringBuilder ok = new StringBuilder();
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i <= Json.MAX_DEPTH; i++) {
            ok.append('[');
        }
        for (int i = 0; i <= Json.MAX_DEPTH; i++) {
            ok.append(']');
        }
        Json.parse(ok.toString());
        for (int i = 0; i < 10_000; i++) {
            deep.append('[');
        }
        try {
            Json.parse(deep.toString());
            fail("parsed 10000 levels");
        } catch (Json.ParseException expected) {
            // (refused, no stack overflow)
        }
    }

    @Test
    public void writesAndReadsBack() throws Exception {
        Map<String, Object> m = Json.obj("s", "a\"b\\c\u0000\u2028\ud83d\ude00", "n", 5L, "d", 0.5, "t", true,
                "z", null, "l", Arrays.asList(1L, "x"), "o", Json.obj("k", "v"));
        String text = Json.write(m);
        assertTrue(text.contains("\\u0000"));
        assertTrue(text.contains("\\u2028"));
        assertEquals(m, Json.parseObject(text));
    }

    @Test
    public void escapesLoneSurrogates() throws Exception {
        String text = Json.write("a\ud800b");
        assertEquals("\"a\\ud800b\"", text);
        assertEquals("a\ud800b", Json.parse(text));
    }
}
