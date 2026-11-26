package io.github.c0decave.axross.companion.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every method's parameters, checked and turned into a value
 * (docs/PROTOCOL.md, section 7). {@link #parse} is the one gate: nothing
 * reaches the phone's data that did not pass it.
 */
public final class Requests {
    private Requests() {
    }

    public static final String KIND_SMS = "sms";
    public static final String KIND_MMS = "mms";

    /** For methods without parameters. */
    public static final Object NONE = new Object();

    public static final class Hello {
        public final List<Long> protocols;
        public final String client;
        public final String clientVersion;
        /** 64 lowercase hex characters. */
        public final String clientNonce;
        /** 32 lowercase hex characters: names the token the client holds. */
        public final String tokenId;

        Hello(List<Long> protocols, String client, String clientVersion, String clientNonce, String tokenId) {
            this.protocols = protocols;
            this.client = client;
            this.clientVersion = clientVersion;
            this.clientNonce = clientNonce;
            this.tokenId = tokenId;
        }

        /** The highest version both speak, or -1. */
        public int choose() {
            return choose(protocols);
        }

        static int choose(List<Long> protocols) {
            long best = -1;
            for (Long p : protocols) {
                if (Protocol.SUPPORTED.contains(p) && p > best) {
                    best = p;
                }
            }
            return (int) best;
        }
    }

    /** auth: the client's proof (64 lowercase hex characters). */
    public static final class Auth {
        public final String proof;

        Auth(String proof) {
            this.proof = proof;
        }
    }

    public static final class Page {
        public final int offset;
        public final int limit;

        Page(int offset, int limit) {
            this.offset = offset;
            this.limit = limit;
        }
    }

    public static final class ContactPhoto {
        public final long contactId;
        public final boolean full;

        ContactPhoto(long contactId, boolean full) {
            this.contactId = contactId;
            this.full = full;
        }
    }

    /** A position in a list of messages (newest first). */
    public static final class Cursor {
        public final long date;
        public final String kind;
        public final long id;

        public Cursor(long date, String kind, long id) {
            this.date = date;
            this.kind = kind;
            this.id = id;
        }

        public Map<String, Object> toJson() {
            return Json.obj("date", date, "kind", kind, "id", id);
        }
    }

    public static final class MessagesList {
        public final long threadId;
        public final int limit;
        public final Cursor before;

        MessagesList(long threadId, int limit, Cursor before) {
            this.threadId = threadId;
            this.limit = limit;
            this.before = before;
        }
    }

    public static final class Search {
        public final String query;
        /** 0: all threads. */
        public final long threadId;
        public final int limit;
        public final Cursor before;

        Search(String query, long threadId, int limit, Cursor before) {
            this.query = query;
            this.threadId = threadId;
            this.limit = limit;
            this.before = before;
        }
    }

    /** One message (kind and id), or - with kind null - a whole thread. */
    public static final class Target {
        public final String kind;
        public final long id;
        public final long threadId;
        public final boolean read;

        Target(String kind, long id, long threadId, boolean read) {
            this.kind = kind;
            this.id = id;
            this.threadId = threadId;
            this.read = read;
        }

        public boolean isThread() {
            return kind == null;
        }
    }

    public static final class SmsSend {
        public final List<String> recipients;
        public final String text;
        /** -1: the default subscription for SMS. */
        public final int subId;
        public final boolean deliveryReport;
        /** The client's name for this send (1..64 of A-Z a-z 0-9 _ -), or null. */
        public final String clientRef;

        SmsSend(List<String> recipients, String text, int subId, boolean deliveryReport, String clientRef) {
            this.recipients = recipients;
            this.text = text;
            this.subId = subId;
            this.deliveryReport = deliveryReport;
            this.clientRef = clientRef;
        }
    }

    /** sms.status: by send_id or (null) by client_ref. */
    public static final class SendQuery {
        public final long sendId;
        public final String clientRef;

        SendQuery(long sendId, String clientRef) {
            this.sendId = sendId;
            this.clientRef = clientRef;
        }
    }

    public static final class Events {
        public final Set<String> events;

        Events(Set<String> events) {
            this.events = events;
        }
    }

    public static final class Export {
        public final long threadId;
        public final boolean includeMms;
        public final boolean includeMmsData;

        Export(long threadId, boolean includeMms, boolean includeMmsData) {
            this.threadId = threadId;
            this.includeMms = includeMms;
            this.includeMmsData = includeMmsData;
        }
    }

    public static final class RestoreBegin {
        public final int smsCount;
        public final String source;

        RestoreBegin(int smsCount, String source) {
            this.smsCount = smsCount;
            this.source = source;
        }
    }

    public static final class RestoreChunk {
        public final long restoreId;
        public final List<Object> items;

        RestoreChunk(long restoreId, List<Object> items) {
            this.restoreId = restoreId;
            this.items = items;
        }
    }

    /** One SMS of a restore, checked (see {@link #restoreSms}). */
    public static final class RestoreSms {
        public final String address;
        public final String body;
        public final long date;
        public final long dateSent;
        /** Android's type: 1 inbox, 2 sent, 3 draft, 4 outbox, 5 failed, 6 queued. */
        public final int type;
        public final boolean read;
        public final boolean seen;
        public final int subId;

        RestoreSms(String address, String body, long date, long dateSent, int type, boolean read, boolean seen, int subId) {
            this.address = address;
            this.body = body;
            this.date = date;
            this.dateSent = dateSent;
            this.type = type;
            this.read = read;
            this.seen = seen;
            this.subId = subId;
        }
    }

    public static final List<String> BOXES = Collections.unmodifiableList(java.util.Arrays.asList(
            "inbox", "sent", "draft", "outbox", "failed", "queued"));

    /** Android's SMS type for a box name (1..6), or 0. */
    public static int smsType(String box) {
        int i = BOXES.indexOf(box);
        return i < 0 ? 0 : i + 1;
    }

    /** The box name for Android's SMS type. */
    public static String boxName(int type) {
        return type >= 1 && type <= BOXES.size() ? BOXES.get(type - 1) : "other";
    }

    /**
     * Checks a request's parameters.
     *
     * @return the method's value (one of the classes above, a Long for a
     *     single id, or {@link #NONE})
     */
    public static Object parse(String method, Params p) throws RequestException {
        switch (method) {
            case "hello":
                return hello(p);
            case "auth":
                return new Auth(hex(p, "proof", Tokens.TOKEN_CHARS));
            case "ping":
            case "status":
            case "subscriptions.list":
                return NONE;
            case "contacts.list":
                return new Page(p.optInt("offset", 0, 0, 10_000_000), p.optInt("limit", 500, 1, 1000));
            case "contacts.photo":
                return new ContactPhoto(p.reqId("contact_id"), "full".equals(p.optEnum("size", "thumb", "thumb", "full")));
            case "threads.list":
                return new Page(p.optInt("offset", 0, 0, 10_000_000), p.optInt("limit", 200, 1, 1000));
            case "messages.list":
                return new MessagesList(p.reqId("thread_id"), p.optInt("limit", 100, 1, Protocol.MAX_PAGE), cursor(p));
            case "messages.search":
                return new Search(searchQuery(p), p.has("thread_id") ? p.reqId("thread_id") : 0,
                        p.optInt("limit", 100, 1, 200), cursor(p));
            case "message.get":
                return new Target(p.reqEnum("kind", KIND_SMS, KIND_MMS), p.reqId("id"), 0, true);
            case "message.mark_read":
                return target(p, true);
            case "message.delete":
                return target(p, false);
            case "sms.send":
                return smsSend(p);
            case "sms.status":
                return sendQuery(p);
            case "mms.parts":
                return p.reqId("id");
            case "mms.part_data":
                return p.reqId("part_id");
            case "mms.send":
                throw new RequestException(ErrorCode.NOT_IMPLEMENTED, "mms.send is not in this protocol version");
            case "subscribe":
                return events(p, true);
            case "unsubscribe":
                return events(p, false);
            case "backup.export":
                return new Export(p.has("thread_id") ? p.reqId("thread_id") : 0,
                        p.optBool("include_mms", true), p.optBool("include_mms_data", true));
            case "backup.cancel":
                return p.reqId("export_id");
            case "backup.restore.begin":
                return new RestoreBegin((int) p.reqLong("sms_count", 1, 1_000_000), p.optString("source", 0, 100));
            case "backup.restore.chunk":
                return new RestoreChunk(p.reqId("restore_id"), p.reqList("items", 1, 500));
            case "backup.restore.end":
                return p.reqId("restore_id");
            default:
                throw new RequestException(ErrorCode.UNKNOWN_METHOD, "no such method");
        }
    }

    /**
     * hello: the versions first - a client without a common one (an older
     * axross) is told unsupported_protocol, whatever else it sent - then
     * client_nonce and token_id, which version 2 requires.
     */
    private static Hello hello(Params p) throws RequestException {
        List<Object> raw = p.reqList("protocols", 1, 16);
        List<Long> protocols = new ArrayList<>();
        for (Object o : raw) {
            protocols.add(Params.toLong("protocols", o, 1, 1_000_000));
        }
        String client = p.optString("client", 0, 64);
        String clientVersion = p.optString("client_version", 0, 64);
        if (Hello.choose(protocols) < 0) {
            throw new RequestException(ErrorCode.UNSUPPORTED_PROTOCOL, "no common protocol version",
                    Json.obj("supported", new ArrayList<Object>(Protocol.SUPPORTED)));
        }
        return new Hello(protocols, client, clientVersion,
                hex(p, "client_nonce", 64), hex(p, "token_id", Tokens.ID_CHARS));
    }

    /** A required string of exactly {@code length} lowercase hex characters. */
    private static String hex(Params p, String name, int length) throws RequestException {
        String s = p.reqString(name, length, length);
        if (!Tokens.isHex(s, length)) {
            throw RequestException.bad(name, name + " must be " + length + " lowercase hex characters");
        }
        return s;
    }

    /** client_ref: 1..64 characters of A-Z, a-z, 0-9, _ and -; null when absent. */
    private static String clientRef(Params p) throws RequestException {
        String s = p.optString("client_ref", 1, 64);
        if (s == null) {
            return null;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(c >= 'A' && c <= 'Z') && !(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') && c != '_' && c != '-') {
                throw RequestException.bad("client_ref", "client_ref may hold only A-Z, a-z, 0-9, _ and -");
            }
        }
        return s;
    }

    private static SendQuery sendQuery(Params p) throws RequestException {
        boolean byId = p.has("send_id");
        boolean byRef = p.has("client_ref");
        if (byId && byRef) {
            throw RequestException.bad("client_ref", "give either send_id or client_ref");
        }
        if (byRef) {
            return new SendQuery(0, clientRef(p));
        }
        return new SendQuery(p.reqId("send_id"), null);
    }

    private static String searchQuery(Params p) throws RequestException {
        String q = p.reqString("query", 1, 200);
        if (q.indexOf('\0') >= 0) {
            throw RequestException.bad("query", "query contains NUL");
        }
        return q;
    }

    private static Cursor cursor(Params p) throws RequestException {
        Params c = p.optObject("before");
        if (c == null) {
            return null;
        }
        try {
            return new Cursor(c.reqLong("date", 0, Long.MAX_VALUE), c.reqEnum("kind", KIND_SMS, KIND_MMS), c.reqId("id"));
        } catch (RequestException e) {
            throw RequestException.bad("before", "before is not a cursor");
        }
    }

    private static Target target(Params p, boolean markRead) throws RequestException {
        boolean read = markRead ? p.optBool("read", true) : false;
        boolean hasThread = p.has("thread_id");
        boolean hasMessage = p.has("kind") || p.has("id");
        if (hasThread == hasMessage) {
            throw RequestException.bad(hasThread ? "thread_id" : "id", "give either kind and id or thread_id");
        }
        if (hasThread) {
            return new Target(null, 0, p.reqId("thread_id"), read);
        }
        return new Target(p.reqEnum("kind", KIND_SMS, KIND_MMS), p.reqId("id"), 0, read);
    }

    private static SmsSend smsSend(Params p) throws RequestException {
        List<String> recipients = Recipients.parse(p);
        String text = TextRules.check(p);
        int subId = (int) p.optLong("sub_id", -1, -1, Integer.MAX_VALUE);
        return new SmsSend(recipients, text, subId, p.optBool("delivery_report", true), clientRef(p));
    }

    private static Events events(Params p, boolean required) throws RequestException {
        List<Object> raw = required ? p.reqList("events", 1, 16) : p.optList("events", 0, 16);
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) {
            out.addAll(Protocol.SUBSCRIBABLE);
            return new Events(out);
        }
        for (Object o : raw) {
            if (!(o instanceof String) || !Protocol.SUBSCRIBABLE.contains(o)) {
                throw RequestException.bad("events", "unknown event name");
            }
            out.add((String) o);
        }
        return new Events(out);
    }

    /**
     * One item of a restore chunk. Returns null for an item that is not an
     * SMS this version restores (counted as "rejected"); throws for one
     * that is malformed.
     */
    @SuppressWarnings("unchecked")
    public static RestoreSms restoreSms(Object item) throws RequestException {
        if (!(item instanceof Map)) {
            throw RequestException.bad("items", "an item must be an object");
        }
        Params p = new Params((Map<String, Object>) item);
        if (!KIND_SMS.equals(p.optString("kind", 1, 8))) {
            return null;
        }
        String address = p.optString("address", 0, 256);
        String body = p.optString("body", 0, 100_000);
        String box = p.reqString("box", 1, 16);
        int type = smsType(box);
        if (type == 0) {
            return null;
        }
        return new RestoreSms(address == null ? "" : address, body == null ? "" : body,
                p.reqLong("date", 0, Long.MAX_VALUE), p.optLong("date_sent", 0, 0, Long.MAX_VALUE), type,
                p.optBool("read", true), p.optBool("seen", true),
                (int) p.optLong("sub_id", -1, -1, Integer.MAX_VALUE));
    }
}
