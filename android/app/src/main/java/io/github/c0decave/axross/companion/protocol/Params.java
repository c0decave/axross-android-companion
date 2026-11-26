package io.github.c0decave.axross.companion.protocol;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A request's "params", read with bounds: every getter either returns a
 * value in range or throws {@code bad_request} naming the field. Unknown
 * fields are ignored (docs/PROTOCOL.md, section 3); a JSON null counts as
 * absent.
 */
public final class Params {
    /** The largest id: 2^63 - 1. */
    public static final long MAX_ID = Long.MAX_VALUE;

    private final Map<String, Object> map;

    public Params(Map<String, Object> map) {
        this.map = map == null ? Collections.<String, Object>emptyMap() : map;
    }

    public Map<String, Object> raw() {
        return map;
    }

    public boolean has(String name) {
        Object v = map.get(name);
        return v != null && v != Json.NULL;
    }

    private Object get(String name) {
        Object v = map.get(name);
        return v == Json.NULL ? null : v;
    }

    public long reqLong(String name, long min, long max) throws RequestException {
        Object v = get(name);
        if (v == null) {
            throw RequestException.bad(name, name + " is missing");
        }
        return toLong(name, v, min, max);
    }

    public long optLong(String name, long def, long min, long max) throws RequestException {
        Object v = get(name);
        return v == null ? def : toLong(name, v, min, max);
    }

    public int optInt(String name, int def, int min, int max) throws RequestException {
        return (int) optLong(name, def, min, max);
    }

    /** A positive id (1 .. 2^63-1). */
    public long reqId(String name) throws RequestException {
        return reqLong(name, 1, MAX_ID);
    }

    static long toLong(String name, Object v, long min, long max) throws RequestException {
        if (!(v instanceof Long)) {
            throw RequestException.bad(name, name + " must be an integer");
        }
        long l = (Long) v;
        if (l < min || l > max) {
            throw RequestException.bad(name, name + " must be in " + min + ".." + max);
        }
        return l;
    }

    public boolean optBool(String name, boolean def) throws RequestException {
        Object v = get(name);
        if (v == null) {
            return def;
        }
        if (!(v instanceof Boolean)) {
            throw RequestException.bad(name, name + " must be true or false");
        }
        return (Boolean) v;
    }

    public String reqString(String name, int minLength, int maxLength) throws RequestException {
        Object v = get(name);
        if (v == null) {
            throw RequestException.bad(name, name + " is missing");
        }
        return toString(name, v, minLength, maxLength);
    }

    public String optString(String name, int minLength, int maxLength) throws RequestException {
        Object v = get(name);
        return v == null ? null : toString(name, v, minLength, maxLength);
    }

    static String toString(String name, Object v, int minLength, int maxLength) throws RequestException {
        if (!(v instanceof String)) {
            throw RequestException.bad(name, name + " must be a string");
        }
        String s = (String) v;
        if (s.length() < minLength || s.length() > maxLength) {
            throw RequestException.bad(name, name + " must have " + minLength + ".." + maxLength + " characters");
        }
        return s;
    }

    public String reqEnum(String name, String... allowed) throws RequestException {
        String s = reqString(name, 1, 64);
        return checkEnum(name, s, allowed);
    }

    public String optEnum(String name, String def, String... allowed) throws RequestException {
        String s = optString(name, 1, 64);
        return s == null ? def : checkEnum(name, s, allowed);
    }

    private static String checkEnum(String name, String s, String... allowed) throws RequestException {
        for (String a : allowed) {
            if (a.equals(s)) {
                return a;
            }
        }
        throw RequestException.bad(name, name + " must be one of " + String.join(", ", allowed));
    }

    @SuppressWarnings("unchecked")
    public List<Object> reqList(String name, int minSize, int maxSize) throws RequestException {
        Object v = get(name);
        if (v == null) {
            throw RequestException.bad(name, name + " is missing");
        }
        if (!(v instanceof List)) {
            throw RequestException.bad(name, name + " must be a list");
        }
        List<Object> list = (List<Object>) v;
        if (list.size() < minSize || list.size() > maxSize) {
            throw RequestException.bad(name, name + " must have " + minSize + ".." + maxSize + " entries");
        }
        return list;
    }

    public List<Object> optList(String name, int minSize, int maxSize) throws RequestException {
        return has(name) ? reqList(name, minSize, maxSize) : null;
    }

    /** A nested object as Params, or null when absent. */
    @SuppressWarnings("unchecked")
    public Params optObject(String name) throws RequestException {
        Object v = get(name);
        if (v == null) {
            return null;
        }
        if (!(v instanceof Map)) {
            throw RequestException.bad(name, name + " must be an object");
        }
        return new Params((Map<String, Object>) v);
    }

    /** The size of a list without its checks (for limits that have their own error). */
    public int listSize(String name) {
        Object v = get(name);
        return v instanceof List ? ((List<?>) v).size() : -1;
    }
}
