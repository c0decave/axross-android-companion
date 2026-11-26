package io.github.c0decave.axross.companion.protocol;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small, strict JSON reader and writer (RFC 8259), so that the protocol
 * code is plain Java: the same on the phone and in the JVM unit tests, with
 * no library behind it.
 *
 * <p>Values: {@link Map} (a {@link LinkedHashMap}, keys in order),
 * {@link List}, {@link String}, {@link Long} (integers without fraction or
 * exponent that fit), {@link Double} (other numbers), {@link Boolean} and
 * {@link #NULL}. Strict: no comments, no trailing commas, no duplicate keys,
 * no NaN, nesting at most {@link #MAX_DEPTH} deep, nothing after the value.
 */
public final class Json {
    /** JSON's null (so that a missing key and a null value differ). */
    public static final Object NULL = new Object() {
        @Override
        public String toString() {
            return "null";
        }
    };

    public static final int MAX_DEPTH = 32;

    private final String text;
    private int pos;

    private Json(String text) {
        this.text = text;
    }

    /** Thrown for text that is not one valid JSON value. */
    public static final class ParseException extends Exception {
        public ParseException(String message) {
            super(message);
        }
    }

    public static Object parse(String text) throws ParseException {
        Json json = new Json(text);
        json.skipSpace();
        Object value = json.value(0);
        json.skipSpace();
        if (json.pos != text.length()) {
            throw new ParseException("text after the value at " + json.pos);
        }
        return value;
    }

    /** Parses a JSON object (anything else is an error). */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) throws ParseException {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new ParseException("not a JSON object");
        }
        return (Map<String, Object>) value;
    }

    private Object value(int depth) throws ParseException {
        if (depth > MAX_DEPTH) {
            throw new ParseException("nested deeper than " + MAX_DEPTH);
        }
        if (pos >= text.length()) {
            throw new ParseException("unexpected end");
        }
        char c = text.charAt(pos);
        switch (c) {
            case '{':
                return object(depth);
            case '[':
                return array(depth);
            case '"':
                return string();
            case 't':
                literal("true");
                return Boolean.TRUE;
            case 'f':
                literal("false");
                return Boolean.FALSE;
            case 'n':
                literal("null");
                return NULL;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return number();
                }
                throw new ParseException("unexpected character at " + pos);
        }
    }

    private Map<String, Object> object(int depth) throws ParseException {
        pos++; // {
        Map<String, Object> map = new LinkedHashMap<>();
        skipSpace();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipSpace();
            if (peek() != '"') {
                throw new ParseException("expected a key at " + pos);
            }
            String key = string();
            skipSpace();
            expect(':');
            skipSpace();
            Object v = value(depth + 1);
            if (map.containsKey(key)) {
                throw new ParseException("duplicate key");
            }
            map.put(key, v);
            skipSpace();
            char c = peek();
            pos++;
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                throw new ParseException("expected , or } at " + (pos - 1));
            }
        }
    }

    private List<Object> array(int depth) throws ParseException {
        pos++; // [
        List<Object> list = new ArrayList<>();
        skipSpace();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipSpace();
            list.add(value(depth + 1));
            skipSpace();
            char c = peek();
            pos++;
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                throw new ParseException("expected , or ] at " + (pos - 1));
            }
        }
    }

    private String string() throws ParseException {
        pos++; // "
        StringBuilder sb = null;
        int start = pos;
        while (true) {
            if (pos >= text.length()) {
                throw new ParseException("unterminated string");
            }
            char c = text.charAt(pos);
            if (c == '"') {
                String s = sb == null ? text.substring(start, pos) : sb.append(text, start, pos).toString();
                pos++;
                return s;
            }
            if (c < 0x20) {
                throw new ParseException("control character in a string at " + pos);
            }
            if (c == '\\') {
                if (sb == null) {
                    sb = new StringBuilder();
                }
                sb.append(text, start, pos);
                pos++;
                if (pos >= text.length()) {
                    throw new ParseException("unterminated escape");
                }
                char e = text.charAt(pos++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u': {
                        if (pos + 4 > text.length()) {
                            throw new ParseException("short \\u escape");
                        }
                        int code = 0;
                        for (int i = 0; i < 4; i++) {
                            int d = Character.digit(text.charAt(pos + i), 16);
                            if (d < 0) {
                                throw new ParseException("bad \\u escape");
                            }
                            code = code * 16 + d;
                        }
                        pos += 4;
                        sb.append((char) code);
                        break;
                    }
                    default:
                        throw new ParseException("bad escape at " + (pos - 1));
                }
                start = pos;
                continue;
            }
            pos++;
        }
    }

    private Object number() throws ParseException {
        int start = pos;
        boolean integral = true;
        if (peek() == '-') {
            pos++;
        }
        if (peek() == '0') {
            pos++;
        } else if (isDigit(peek())) {
            while (isDigit(peek())) {
                pos++;
            }
        } else {
            throw new ParseException("bad number at " + start);
        }
        if (peek() == '.') {
            integral = false;
            pos++;
            if (!isDigit(peek())) {
                throw new ParseException("bad number at " + start);
            }
            while (isDigit(peek())) {
                pos++;
            }
        }
        if (peek() == 'e' || peek() == 'E') {
            integral = false;
            pos++;
            if (peek() == '+' || peek() == '-') {
                pos++;
            }
            if (!isDigit(peek())) {
                throw new ParseException("bad number at " + start);
            }
            while (isDigit(peek())) {
                pos++;
            }
        }
        String s = text.substring(start, pos);
        if (integral) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException tooBig) {
                // (falls through: an integer beyond 64 bits is a Double)
            }
        }
        double d = Double.parseDouble(s);
        if (Double.isInfinite(d)) {
            throw new ParseException("number out of range at " + start);
        }
        return d;
    }

    private void literal(String word) throws ParseException {
        if (!text.startsWith(word, pos)) {
            throw new ParseException("unexpected character at " + pos);
        }
        pos += word.length();
    }

    private void expect(char c) throws ParseException {
        if (peek() != c) {
            throw new ParseException("expected " + c + " at " + pos);
        }
        pos++;
    }

    private char peek() {
        return pos < text.length() ? text.charAt(pos) : '\0';
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private void skipSpace() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                return;
            }
        }
    }

    // -- writing ----------------------------------------------------------------------------

    /** Writes a value as compact JSON. Java null is written as null. */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, 0);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object value, int depth) {
        if (depth > MAX_DEPTH + 8) {
            throw new IllegalArgumentException("nested too deep");
        }
        if (value == null || value == NULL) {
            sb.append("null");
        } else if (value instanceof String) {
            quote(sb, (String) value);
        } else if (value instanceof Boolean) {
            sb.append(((Boolean) value) ? "true" : "false");
        } else if (value instanceof Long || value instanceof Integer || value instanceof Short
                || value instanceof Byte) {
            sb.append(((Number) value).longValue());
        } else if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException("not a JSON number");
            }
            if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                sb.append((long) d);
            } else {
                sb.append(d);
            }
        } else if (value instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Iterator<? extends Map.Entry<?, ?>> it = ((Map<?, ?>) value).entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<?, ?> e = it.next();
                if (!first) {
                    sb.append(',');
                }
                first = false;
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue(), depth + 1);
            }
            sb.append('}');
        } else if (value instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object item : (Iterable<?>) value) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(sb, item, depth + 1);
            }
            sb.append(']');
        } else if (value instanceof long[]) {
            sb.append('[');
            long[] a = (long[]) value;
            for (int i = 0; i < a.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(a[i]);
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("not a JSON value: " + value.getClass().getName());
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    // (control characters, and U+2028/U+2029 and lone
                    // surrogates escaped so that the UTF-8 is always valid)
                    if (c < 0x20 || c == 0x2028 || c == 0x2029
                            || (Character.isSurrogate(c) && !pairedSurrogate(s, i))) {
                        sb.append("\\u");
                        for (int shift = 12; shift >= 0; shift -= 4) {
                            sb.append(HEX[(c >> shift) & 0xf]);
                        }
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static boolean pairedSurrogate(String s, int i) {
        char c = s.charAt(i);
        if (Character.isHighSurrogate(c)) {
            return i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1));
        }
        return i > 0 && Character.isHighSurrogate(s.charAt(i - 1));
    }

    // -- building ---------------------------------------------------------------------------

    /** A map from alternating keys and values: {@code obj("a", 1, "b", "x")}. */
    public static Map<String, Object> obj(Object... keysAndValues) {
        if (keysAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("odd number of arguments");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1] == null ? NULL : keysAndValues[i + 1]);
        }
        return map;
    }
}
