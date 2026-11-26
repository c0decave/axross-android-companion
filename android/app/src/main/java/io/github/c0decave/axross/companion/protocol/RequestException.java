package io.github.c0decave.axross.companion.protocol;

import java.util.Map;

/**
 * A request that is answered with an error (docs/PROTOCOL.md, section 5).
 * The message is for people and must not carry message text, numbers or
 * names.
 */
public final class RequestException extends Exception {
    public final String code;
    /** The error's "data" object, or null. */
    public final Map<String, Object> data;

    public RequestException(String code, String message) {
        this(code, message, null);
    }

    public RequestException(String code, String message, Map<String, Object> data) {
        super(message);
        this.code = code;
        this.data = data;
    }

    public static RequestException bad(String field, String message) {
        return new RequestException(ErrorCode.BAD_REQUEST, message, Json.obj("field", field));
    }

    public static RequestException permission(String permission) {
        return new RequestException(ErrorCode.PERMISSION_DENIED, permission + " is not granted",
                Json.obj("permission", permission));
    }
}
