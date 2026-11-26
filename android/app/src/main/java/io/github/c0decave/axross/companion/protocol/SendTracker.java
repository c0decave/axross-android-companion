package io.github.c0decave.axross.companion.protocol;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What became of each send (docs/PROTOCOL.md, "sms.send" and
 * "sms.status"): counts the parts' "sent" and "delivered" callbacks per
 * recipient and says which event is due. Kept for an hour.
 */
public final class SendTracker {
    public static final long KEEP_MS = 3_600_000L;

    public static final String SENDING = "sending";
    public static final String SENT = "sent";
    public static final String FAILED = "failed";
    public static final String DELIVERED = "delivered";
    public static final String DELIVERY_FAILED = "delivery_failed";

    /** Android's SmsManager results this protocol names (the rest is "other"). */
    public static String reason(int resultCode) {
        switch (resultCode) {
            case 1: return "generic_failure";
            case 2: return "radio_off";
            case 3: return "null_pdu";
            case 4: return "no_service";
            case 5: return "limit_exceeded";
            case 6: return "fdn_check_failure";
            case 7: return "short_code_not_allowed";
            case 8: return "short_code_never_allowed";
            default: return "other";
        }
    }

    /**
     * A delivery report's status (SmsMessage.getStatus()) as "complete",
     * "pending" or "failed". GSM/UMTS/LTE (3GPP): the TP-Status, 0x00-0x1f
     * done, 0x20-0x3f still trying, from 0x40 failed. CDMA (3GPP2): the
     * error class in bits 16+ (0 none, 2 temporary, 3 permanent).
     */
    public static String deliveryStatus(int status, boolean cdma) {
        if (cdma) {
            int errorClass = (status >> 16) & 0xff;
            return errorClass == 0 ? "complete" : errorClass == 2 ? "pending" : "failed";
        }
        if (status >= 0x40) {
            return "failed";
        }
        return status >= 0x20 ? "pending" : "complete";
    }

    /** One recipient of a send. */
    static final class Recipient {
        final String address;
        final int parts;
        int sentParts;
        int deliveredParts;
        boolean deliveryFailed;
        boolean deliveryPending;
        String state = SENDING;
        String reason;

        Recipient(String address, int parts) {
            this.address = address;
            this.parts = parts;
        }
    }

    static final class Send {
        final long sessionId;
        final long createdMs;
        final List<Recipient> recipients = new ArrayList<>();

        Send(long sessionId, long createdMs) {
            this.sessionId = sessionId;
            this.createdMs = createdMs;
        }
    }

    /** An event that is due, and for which session. */
    public static final class Event {
        public final long sessionId;
        public final String name;
        public final Map<String, Object> data;

        Event(long sessionId, String name, Map<String, Object> data) {
            this.sessionId = sessionId;
            this.name = name;
            this.data = data;
        }
    }

    private final RateLimiter.Clock clock;
    private final Map<Long, Send> sends = new LinkedHashMap<>();
    private long nextId = 1;

    public SendTracker(RateLimiter.Clock clock) {
        this.clock = clock;
    }

    /** Registers a send; returns its id. */
    public synchronized long start(long sessionId, List<String> addresses, List<Integer> parts) {
        expire();
        long id = nextId++;
        Send s = new Send(sessionId, clock.nowMs());
        for (int i = 0; i < addresses.size(); i++) {
            s.recipients.add(new Recipient(addresses.get(i), parts.get(i)));
        }
        sends.put(id, s);
        return id;
    }

    /** A part's "sent" callback. Returns the event that is due, or null. */
    public synchronized Event onSent(long sendId, int index, boolean ok, int resultCode) {
        Recipient r = recipient(sendId, index);
        if (r == null || !SENDING.equals(r.state)) {
            return null;
        }
        long session = sends.get(sendId).sessionId;
        if (!ok) {
            r.state = FAILED;
            r.reason = reason(resultCode);
            return new Event(session, "sms.failed", Json.obj("send_id", sendId, "index", (long) index,
                    "address", r.address, "reason", r.reason, "result_code", (long) resultCode));
        }
        r.sentParts++;
        if (r.sentParts < r.parts) {
            return null;
        }
        r.state = SENT;
        return new Event(session, "sms.sent", Json.obj("send_id", sendId, "index", (long) index, "address", r.address));
    }

    /**
     * A part's delivery report: {@code status} is "complete", "pending" or
     * "failed". Returns the event when all parts have reported (or one
     * failed).
     */
    public synchronized Event onDelivered(long sendId, int index, String status) {
        Recipient r = recipient(sendId, index);
        if (r == null || DELIVERED.equals(r.state) || DELIVERY_FAILED.equals(r.state) || FAILED.equals(r.state)) {
            return null;
        }
        long session = sends.get(sendId).sessionId;
        if ("failed".equals(status)) {
            r.state = DELIVERY_FAILED;
            return new Event(session, "sms.delivered", Json.obj("send_id", sendId, "index", (long) index,
                    "address", r.address, "status", "failed"));
        }
        if ("pending".equals(status)) {
            r.deliveryPending = true;
            return null;
        }
        r.deliveredParts++;
        if (r.deliveredParts < r.parts) {
            return null;
        }
        r.state = DELIVERED;
        return new Event(session, "sms.delivered", Json.obj("send_id", sendId, "index", (long) index,
                "address", r.address, "status", "complete"));
    }

    /** sms.status's result, or null when the send is unknown (or older than an hour). */
    public synchronized Map<String, Object> status(long sendId) {
        expire();
        Send s = sends.get(sendId);
        if (s == null) {
            return null;
        }
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < s.recipients.size(); i++) {
            Recipient r = s.recipients.get(i);
            list.add(Json.obj("index", (long) i, "address", r.address, "state", r.state, "reason", r.reason));
        }
        return Json.obj("send_id", sendId, "recipients", list);
    }

    private Recipient recipient(long sendId, int index) {
        Send s = sends.get(sendId);
        if (s == null || index < 0 || index >= s.recipients.size()) {
            return null;
        }
        return s.recipients.get(index);
    }

    private void expire() {
        long now = clock.nowMs();
        for (Iterator<Send> it = sends.values().iterator(); it.hasNext(); ) {
            if (it.next().createdMs < now - KEEP_MS) {
                it.remove();
            }
        }
    }
}
