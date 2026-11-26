package io.github.c0decave.axross.companion.protocol;

/** The error codes of docs/PROTOCOL.md, section 5. */
public final class ErrorCode {
    public static final String BAD_REQUEST = "bad_request";
    public static final String UNKNOWN_METHOD = "unknown_method";
    public static final String HANDSHAKE_REQUIRED = "handshake_required";
    public static final String UNKNOWN_TOKEN = "unknown_token";
    public static final String AUTH_FAILED = "auth_failed";
    public static final String UNSUPPORTED_PROTOCOL = "unsupported_protocol";
    public static final String NOT_IMPLEMENTED = "not_implemented";
    public static final String PERMISSION_DENIED = "permission_denied";
    public static final String NOT_DEFAULT_SMS_APP = "not_default_sms_app";
    public static final String NOT_FOUND = "not_found";
    public static final String TOO_MANY_RECIPIENTS = "too_many_recipients";
    public static final String TOO_LARGE = "too_large";
    public static final String RATE_LIMITED = "rate_limited";
    public static final String NO_SUBSCRIPTION = "no_subscription";
    public static final String BUSY = "busy";
    public static final String USER_DENIED = "user_denied";
    public static final String CANCELLED = "cancelled";
    public static final String INTERNAL = "internal";

    private ErrorCode() {
    }
}
