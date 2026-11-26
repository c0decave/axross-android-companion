package io.github.c0decave.axross.companion.protocol;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** The constants of docs/PROTOCOL.md. */
public final class Protocol {
    public static final int VERSION = 1;
    public static final List<Long> SUPPORTED = Collections.singletonList(1L);

    /** The abstract socket's name (localabstract:axross-companion). */
    public static final String SOCKET_NAME = "axross-companion";
    public static final String APP = "axross-android-companion";

    /** The uids that may connect: root and shell (adbd). */
    public static final int UID_ROOT = 0;
    public static final int UID_SHELL = 2000;

    public static final int MAX_SESSIONS = 4;
    public static final int MAX_IN_FLIGHT = 8;
    public static final int MAX_REQUEST_JSON = 4 * 1024 * 1024;
    public static final int MAX_BLOB_IN = 8 * 1024 * 1024;
    public static final int MAX_BLOBS_PENDING = 32 * 1024 * 1024;
    public static final int MAX_BLOB_OUT = 64 * 1024 * 1024;
    public static final int MAX_PAGE = 500;
    public static final int SENDS_PER_MINUTE = 20;
    public static final int SENDS_PER_HOUR = 100;
    public static final long HANDSHAKE_TIMEOUT_MS = 10_000;
    public static final long SLOW_READER_MS = 60_000;

    /** What version 1 implements (mms.send is not in it). */
    public static final List<String> CAPABILITIES = Collections.unmodifiableList(Arrays.asList(
            "contacts", "contacts.photo", "threads", "messages", "messages.search",
            "message.mark_read", "message.delete", "sms.send", "sms.status", "mms.parts",
            "mms.part_data", "subscribe", "subscriptions", "backup.export", "backup.restore"));

    /** The events a session can subscribe to. */
    public static final List<String> SUBSCRIBABLE = Collections.unmodifiableList(Arrays.asList(
            "message.received", "messages.changed"));

    /** The permissions hello and status report, by their short names. */
    public static final List<String> PERMISSIONS = Collections.unmodifiableList(Arrays.asList(
            "READ_SMS", "SEND_SMS", "RECEIVE_SMS", "READ_CONTACTS", "READ_PHONE_STATE",
            "POST_NOTIFICATIONS"));

    private Protocol() {
    }

    public static Map<String, Object> limits() {
        return Json.obj(
                "max_frame", (long) Frame.MAX_LENGTH,
                "max_request_json", (long) MAX_REQUEST_JSON,
                "max_blob_in", (long) MAX_BLOB_IN,
                "max_blobs_pending", (long) MAX_BLOBS_PENDING,
                "max_blob_out", (long) MAX_BLOB_OUT,
                "max_in_flight", (long) MAX_IN_FLIGHT,
                "max_recipients", (long) Recipients.MAX_RECIPIENTS,
                "max_text_chars", (long) TextRules.MAX_TEXT_CHARS,
                "max_parts", (long) TextRules.MAX_PARTS,
                "sends_per_minute", (long) SENDS_PER_MINUTE,
                "sends_per_hour", (long) SENDS_PER_HOUR,
                "max_page", (long) MAX_PAGE);
    }
}
