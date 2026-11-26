package io.github.c0decave.axross.companion.protocol;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The sends the companion accepted, on disk in the app's private files
 * (docs/PROTOCOL.md, "sms.send" and "sms.status"): so that a send asked
 * for again is not sent again, and sms.status answers, across service runs,
 * process deaths and reboots. Never the message text: the client_ref, the
 * send id, the normalized recipients with their parts, the time of
 * acceptance and each recipient's state.
 *
 * <p>Two files. {@code sends.json} is the whole store, rewritten atomically
 * (a temporary file, fsync, rename) whenever a send is accepted - before
 * any of its parts is handed to Android. {@code sends.journal} gets one
 * line (fsync'd) per report from Android after that; a line carries a
 * sequence number, and sends.json the last one it holds, so that replaying
 * the journal after a crash between the rename and the journal's removal
 * changes nothing. A torn last line is ignored.
 *
 * <p>The store has an id, 128 random bits, made with it. A store that is
 * missing or unreadable (app data cleared, the app installed again, a
 * corrupt file) is made anew, with a new id and a new random start for the
 * send ids: a client that sees the id change knows that the companion's
 * "not_found" no longer means "never accepted".
 */
public final class SendStore {
    /** A send is kept (and found) for at least this long after acceptance: hello's sends.keep_ms. */
    public static final long KEEP_MS = 86_400_000L;
    /**
     * And dropped this much later: a phone clock that is set forward a
     * little does not drop a send before a client's own keep_ms is over.
     */
    public static final long GRACE_MS = 3_600_000L;
    /**
     * A recipient still "sending" this long after acceptance is "unknown"
     * from then on: what Android reports later is ignored.
     */
    public static final long UNKNOWN_AFTER_MS = 3_600_000L;
    /** At most this many sends (the oldest dropped first); the rate limit allows 2400 a day. */
    public static final int MAX_SENDS = 5000;
    /** Send ids start at a random value in 1..2^31 and stay at or below 2^53 - 1. */
    public static final long MAX_SEND_ID = (1L << 53) - 1;
    /** sends.json is rewritten (and the journal emptied) after this many journal lines. */
    static final int COMPACT_AFTER = 1000;

    public static final String FILE = "sends.json";
    public static final String JOURNAL = "sends.journal";
    static final String TEMP = "sends.json.tmp";
    static final String FORMAT = "axross-companion-sends";
    static final long VERSION = 1;

    public static final String SENDING = "sending";
    public static final String SENT = "sent";
    public static final String FAILED = "failed";
    public static final String DELIVERED = "delivered";
    public static final String DELIVERY_FAILED = "delivery_failed";
    public static final String UNKNOWN = "unknown";

    /** How the store came to be when it was opened (for the log). */
    public enum Origin {
        /** Read from its files. */
        LOADED,
        /** There was none (first run, data cleared, installed again). */
        NEW,
        /** There was one that could not be read: made anew. */
        UNREADABLE
    }

    /** One recipient of a send. */
    public static final class Recipient {
        public final String address;
        public final int parts;
        long sentParts;
        long deliveredParts;
        boolean deliveryPending;
        String state = SENDING;
        String reason;

        Recipient(String address, int parts) {
            this.address = address;
            this.parts = parts;
        }

        long allParts() {
            return parts >= 63 ? -1L >>> 1 : (1L << parts) - 1;
        }
    }

    /** One accepted send. */
    public static final class Send {
        public final long id;
        /** Or null. */
        public final String clientRef;
        public final long accepted;
        final List<Recipient> recipients;

        Send(long id, String clientRef, long accepted, List<Recipient> recipients) {
            this.id = id;
            this.clientRef = clientRef;
            this.accepted = accepted;
            this.recipients = recipients;
        }

        public int size() {
            return recipients.size();
        }

        public Recipient recipient(int index) {
            return recipients.get(index);
        }
    }

    /** A report that completed a recipient: the event it is due (sms.sent, sms.failed, sms.delivered). */
    public static final class Event {
        public final Send send;
        public final String name;
        public final Map<String, Object> data;

        Event(Send send, String name, Map<String, Object> data) {
            this.send = send;
            this.name = name;
            this.data = data;
        }
    }

    private final File dir;
    private final RateLimiter.Clock clock;
    private final Random random;
    private String id;
    private long nextId;
    /** The journal's last line (sends.json holds every line up to {@link #snapshotSeq}). */
    private long seq;
    private long snapshotSeq;
    private int journalLines;
    private Origin origin;
    /** By id, in the order of acceptance. */
    private final LinkedHashMap<Long, Send> sends = new LinkedHashMap<>();
    private final Map<String, Long> refs = new HashMap<>();
    private volatile int writeFailures;
    /** (smaller in the tests) */
    int maxSends = MAX_SENDS;

    private SendStore(File dir, RateLimiter.Clock clock, Random random) {
        this.dir = dir;
        this.clock = clock;
        this.random = random;
    }

    /**
     * The store in {@code dir}: read, or made anew (and written) when there
     * is none or it cannot be read. A store that cannot be written is kept
     * in memory; its sends then fail with "internal" until a write works.
     */
    public static SendStore open(File dir, RateLimiter.Clock clock, Random random) {
        SendStore s = new SendStore(dir, clock, random);
        s.load();
        return s;
    }

    public synchronized String id() {
        return id;
    }

    public synchronized Origin origin() {
        return origin;
    }

    /** Writes that failed since the store was opened (the journal's; an accepted send's fails the send). */
    public int writeFailures() {
        return writeFailures;
    }

    public synchronized int size() {
        expire();
        return sends.size();
    }

    /** The send with this client_ref, or null. */
    public synchronized Send byRef(String clientRef) {
        expire();
        Long sendId = clientRef == null ? null : refs.get(clientRef);
        return sendId == null ? null : sends.get(sendId);
    }

    /** The send with this id, or null. */
    public synchronized Send byId(long sendId) {
        expire();
        return sends.get(sendId);
    }

    /** The sends accepted within the last {@code ms}: [accepted, recipients] each, oldest first. */
    public synchronized List<long[]> acceptedWithin(long ms) {
        expire();
        long since = clock.nowMs() - ms;
        List<long[]> out = new ArrayList<>();
        for (Send s : sends.values()) {
            if (s.accepted > since) {
                out.add(new long[] {s.accepted, s.recipients.size()});
            }
        }
        return out;
    }

    /**
     * Records an accepted send - durably, before it is sent. On an error
     * nothing is recorded and nothing may be sent.
     */
    public synchronized Send add(String clientRef, List<String> addresses, List<Integer> parts) throws IOException {
        expire();
        if (clientRef != null && refs.containsKey(clientRef)) {
            throw new IllegalStateException("client_ref accepted already");
        }
        List<Recipient> recipients = new ArrayList<>();
        for (int i = 0; i < addresses.size(); i++) {
            recipients.add(new Recipient(addresses.get(i), parts.get(i)));
        }
        Send send = new Send(nextId, clientRef, clock.nowMs(), Collections.unmodifiableList(recipients));
        List<Send> after = new ArrayList<>(sends.values());
        after.add(send);
        while (after.size() > maxSends) {
            after.remove(0);
        }
        long next = nextId >= MAX_SEND_ID ? 1 : nextId + 1;
        try {
            writeSnapshot(id, next, seq, after);
        } catch (IOException e) {
            writeFailures++;
            throw e;
        }
        nextId = next;
        snapshotSeq = seq;
        sends.clear();
        refs.clear();
        for (Send s : after) {
            put(s);
        }
        clearJournal();
        return send;
    }

    /**
     * A part's "sent" report ({@code ok} false: it failed with Android's
     * {@code resultCode}). Returns the event that is due, or null.
     */
    public synchronized Event sent(long sendId, int index, int part, boolean ok, int resultCode) {
        long now = clock.nowMs();
        Event e = applySent(now, sendId, index, part, ok, resultCode);
        if (e != null) {
            journal(Json.obj("t", now, "s", sendId, "i", (long) index, "p", (long) part,
                    "e", ok ? "sent" : "failed", "c", (long) resultCode));
        }
        return e == CHANGED_ONLY ? null : e;
    }

    /**
     * A part's delivery report: {@code status} "complete", "pending" or
     * "failed". Returns the event when the recipient is done, or null.
     */
    public synchronized Event delivered(long sendId, int index, int part, String status) {
        long now = clock.nowMs();
        Event e = applyDelivered(now, sendId, index, part, status);
        if (e != null) {
            journal(Json.obj("t", now, "s", sendId, "i", (long) index, "p", (long) part, "e", "delivery", "d", status));
        }
        return e == CHANGED_ONLY ? null : e;
    }

    /** A recipient's state as sms.status says it now ("unknown" by {@link #UNKNOWN_AFTER_MS}). */
    public synchronized String state(Send s, int index) {
        Recipient r = s.recipients.get(index);
        if (SENDING.equals(r.state) && clock.nowMs() - s.accepted >= UNKNOWN_AFTER_MS) {
            return UNKNOWN;
        }
        return r.state;
    }

    public synchronized String reason(Send s, int index) {
        return s.recipients.get(index).reason;
    }

    // -- the state of a recipient ------------------------------------------------------------

    /** (a report that changed something without completing the recipient) */
    private static final Event CHANGED_ONLY = new Event(null, null, null);

    private Recipient live(long sendId, int index, int part, long time) {
        Send s = sends.get(sendId);
        if (s == null || index < 0 || index >= s.recipients.size()) {
            return null;
        }
        Recipient r = s.recipients.get(index);
        if (part < 0 || part >= r.parts) {
            return null;
        }
        if (SENDING.equals(r.state) && time - s.accepted >= UNKNOWN_AFTER_MS) {
            // (unknown by now: stays so)
            return null;
        }
        return r;
    }

    private Event applySent(long time, long sendId, int index, int part, boolean ok, int resultCode) {
        Recipient r = live(sendId, index, part, time);
        if (r == null || !SENDING.equals(r.state)) {
            return null;
        }
        Send s = sends.get(sendId);
        if (!ok) {
            r.state = FAILED;
            r.reason = SendTracker.reason(resultCode);
            return event(s, index, "sms.failed", "reason", r.reason, "result_code", (long) resultCode);
        }
        long bit = 1L << part;
        if ((r.sentParts & bit) != 0) {
            return null;
        }
        r.sentParts |= bit;
        if (r.sentParts != r.allParts()) {
            return CHANGED_ONLY;
        }
        r.state = SENT;
        return event(s, index, "sms.sent");
    }

    private Event applyDelivered(long time, long sendId, int index, int part, String status) {
        Recipient r = live(sendId, index, part, time);
        if (r == null || DELIVERED.equals(r.state) || DELIVERY_FAILED.equals(r.state) || FAILED.equals(r.state)) {
            return null;
        }
        Send s = sends.get(sendId);
        if ("failed".equals(status)) {
            r.state = DELIVERY_FAILED;
            return event(s, index, "sms.delivered", "status", "failed");
        }
        if ("pending".equals(status)) {
            if (r.deliveryPending) {
                return null;
            }
            r.deliveryPending = true;
            return CHANGED_ONLY;
        }
        long bit = 1L << part;
        if ((r.deliveredParts & bit) != 0) {
            return null;
        }
        r.deliveredParts |= bit;
        if (r.deliveredParts != r.allParts()) {
            return CHANGED_ONLY;
        }
        r.state = DELIVERED;
        return event(s, index, "sms.delivered", "status", "complete");
    }

    /** An event's data: send_id, index, address, the rest, client_ref when the send had one. */
    private static Event event(Send s, int index, String name, Object... more) {
        Map<String, Object> data = Json.obj("send_id", s.id, "index", (long) index, "address", s.recipients.get(index).address);
        data.putAll(Json.obj(more));
        if (s.clientRef != null) {
            data.put("client_ref", s.clientRef);
        }
        return new Event(s, name, data);
    }

    // -- the files --------------------------------------------------------------------------

    private void put(Send s) {
        sends.put(s.id, s);
        if (s.clientRef != null) {
            refs.put(s.clientRef, s.id);
        }
    }

    /** Drops the sends past {@link #KEEP_MS} + {@link #GRACE_MS} (in memory; the next write leaves them out). */
    private void expire() {
        long limit = clock.nowMs() - KEEP_MS - GRACE_MS;
        for (Iterator<Send> it = sends.values().iterator(); it.hasNext(); ) {
            Send s = it.next();
            if (s.accepted < limit) {
                it.remove();
                if (s.clientRef != null) {
                    refs.remove(s.clientRef);
                }
            }
        }
    }

    private void load() {
        File file = new File(dir, FILE);
        byte[] bytes;
        try {
            bytes = readAll(file);
        } catch (FileNotFoundException e) {
            bytes = null;
        } catch (IOException e) {
            fresh(Origin.UNREADABLE);
            return;
        }
        if (bytes == null) {
            fresh(Origin.NEW);
            return;
        }
        try {
            parse(new String(bytes, StandardCharsets.UTF_8));
        } catch (Json.ParseException | RuntimeException e) {
            sends.clear();
            refs.clear();
            fresh(Origin.UNREADABLE);
            return;
        }
        origin = Origin.LOADED;
        replayJournal();
        expire();
    }

    /** A new store: a new id, a random first send id, written at once (if it can be). */
    private void fresh(Origin why) {
        origin = why;
        byte[] b = new byte[16];
        random.nextBytes(b);
        id = Tokens.hex(b);
        // (1 .. 2^31)
        nextId = 1 + (random.nextLong() >>> 33);
        seq = 0;
        snapshotSeq = 0;
        new File(dir, JOURNAL).delete();
        journalLines = 0;
        try {
            writeSnapshot(id, nextId, seq, new ArrayList<Send>());
        } catch (IOException e) {
            writeFailures++;
        }
    }

    @SuppressWarnings("unchecked")
    private void parse(String text) throws Json.ParseException {
        Map<String, Object> doc = Json.parseObject(text);
        if (!FORMAT.equals(doc.get("format")) || !Long.valueOf(VERSION).equals(doc.get("version"))) {
            throw new Json.ParseException("not a send store of this version");
        }
        String storeId = (String) doc.get("store");
        if (!Tokens.isHex(storeId, 32)) {
            throw new Json.ParseException("store id");
        }
        long next = (Long) doc.get("next_send_id");
        long lastSeq = (Long) doc.get("seq");
        if (next < 1 || next > MAX_SEND_ID || lastSeq < 0) {
            throw new Json.ParseException("counters");
        }
        for (Object o : (List<Object>) doc.get("sends")) {
            Map<String, Object> m = (Map<String, Object>) o;
            long sendId = (Long) m.get("send_id");
            Object ref = m.get("client_ref");
            String clientRef = ref == Json.NULL ? null : (String) ref;
            long accepted = (Long) m.get("accepted");
            List<Recipient> recipients = new ArrayList<>();
            for (Object ro : (List<Object>) m.get("recipients")) {
                Map<String, Object> rm = (Map<String, Object>) ro;
                long parts = (Long) rm.get("parts");
                if (parts < 1 || parts > 62) {
                    throw new Json.ParseException("parts");
                }
                Recipient r = new Recipient((String) rm.get("address"), (int) parts);
                r.sentParts = (Long) rm.get("sent_parts");
                r.deliveredParts = (Long) rm.get("delivered_parts");
                r.deliveryPending = (Boolean) rm.get("delivery_pending");
                r.state = state((String) rm.get("state"));
                Object reason = rm.get("reason");
                r.reason = reason == Json.NULL ? null : (String) reason;
                if (r.address == null) {
                    throw new Json.ParseException("address");
                }
                recipients.add(r);
            }
            if (sendId < 1 || recipients.isEmpty() || sends.containsKey(sendId)
                    || (clientRef != null && refs.containsKey(clientRef))) {
                throw new Json.ParseException("send");
            }
            put(new Send(sendId, clientRef, accepted, Collections.unmodifiableList(recipients)));
        }
        id = storeId;
        nextId = next;
        seq = lastSeq;
        snapshotSeq = lastSeq;
    }

    private static String state(String s) throws Json.ParseException {
        for (String known : new String[] {SENDING, SENT, FAILED, DELIVERED, DELIVERY_FAILED}) {
            if (known.equals(s)) {
                return known;
            }
        }
        throw new Json.ParseException("state");
    }

    /** The journal's lines after sends.json's, applied as they were then; a bad line ends it. */
    @SuppressWarnings("unchecked")
    private void replayJournal() {
        byte[] bytes;
        try {
            bytes = readAll(new File(dir, JOURNAL));
        } catch (IOException e) {
            return;
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        int start = 0;
        journalLines = 0;
        while (start < text.length()) {
            int end = text.indexOf('\n', start);
            if (end < 0) {
                // (a torn last line)
                break;
            }
            String line = text.substring(start, end);
            start = end + 1;
            try {
                Map<String, Object> m = Json.parseObject(line);
                long q = (Long) m.get("q");
                if (q <= seq) {
                    continue;
                }
                long t = (Long) m.get("t");
                long sendId = (Long) m.get("s");
                int index = (int) (long) (Long) m.get("i");
                int part = (int) (long) (Long) m.get("p");
                String what = (String) m.get("e");
                if ("sent".equals(what) || "failed".equals(what)) {
                    applySent(t, sendId, index, part, "sent".equals(what), (int) (long) (Long) m.get("c"));
                } else if ("delivery".equals(what)) {
                    applyDelivered(t, sendId, index, part, (String) m.get("d"));
                } else {
                    break;
                }
                seq = q;
                journalLines++;
            } catch (Json.ParseException | RuntimeException e) {
                break;
            }
        }
    }

    /** Appends a line to the journal (fsync'd); compacts it when long. A failure is counted: the state is then in memory only. */
    private void journal(Map<String, Object> entry) {
        long q = seq + 1;
        Map<String, Object> line = Json.obj("q", q);
        line.putAll(entry);
        byte[] bytes = (Json.write(line) + "\n").getBytes(StandardCharsets.UTF_8);
        seq = q;
        try (FileOutputStream out = new FileOutputStream(new File(dir, JOURNAL), true)) {
            out.write(bytes);
            out.getFD().sync();
            journalLines++;
        } catch (IOException e) {
            writeFailures++;
        }
        if (journalLines >= COMPACT_AFTER) {
            try {
                writeSnapshot(id, nextId, seq, new ArrayList<>(sends.values()));
                snapshotSeq = seq;
                clearJournal();
            } catch (IOException e) {
                writeFailures++;
            }
        }
    }

    private void clearJournal() {
        File j = new File(dir, JOURNAL);
        if (j.exists() && !j.delete()) {
            // (its lines are all in sends.json, by their numbers: replaying them changes nothing)
            writeFailures++;
        }
        journalLines = 0;
    }

    /** sends.json: written to a temporary file, synced, renamed over the old one. */
    private void writeSnapshot(String storeId, long next, long lastSeq, List<Send> list) throws IOException {
        List<Object> out = new ArrayList<>();
        for (Send s : list) {
            List<Object> rs = new ArrayList<>();
            for (Recipient r : s.recipients) {
                rs.add(Json.obj("address", r.address, "parts", (long) r.parts, "sent_parts", r.sentParts,
                        "delivered_parts", r.deliveredParts, "delivery_pending", r.deliveryPending,
                        "state", r.state, "reason", r.reason));
            }
            out.add(Json.obj("send_id", s.id, "client_ref", s.clientRef, "accepted", s.accepted, "recipients", rs));
        }
        byte[] bytes = Json.write(Json.obj("format", FORMAT, "version", VERSION, "store", storeId,
                "next_send_id", next, "seq", lastSeq, "sends", out)).getBytes(StandardCharsets.UTF_8);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("no directory");
        }
        File temp = new File(dir, TEMP);
        try (FileOutputStream f = new FileOutputStream(temp)) {
            f.write(bytes);
            f.getFD().sync();
        }
        if (!temp.renameTo(new File(dir, FILE))) {
            temp.delete();
            throw new IOException("rename failed");
        }
        syncDir();
    }

    /** (the rename made durable, where the platform allows a directory to be synced) */
    private void syncDir() {
        try (FileChannel c = FileChannel.open(dir.toPath(), StandardOpenOption.READ)) {
            c.force(true);
        } catch (IOException | RuntimeException ignored) {
            // (not possible here: the file's own sync stands)
        }
    }

    private static byte[] readAll(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                b.write(buf, 0, n);
            }
            return b.toByteArray();
        }
    }

    // -- for the tests (a phone with earlier sends) -----------------------------------------

    /** A store with this id and first send id, in an empty {@code dir}. */
    static SendStore create(File dir, String storeId, long firstSendId, RateLimiter.Clock clock) throws IOException {
        SendStore s = new SendStore(dir, clock, new Random(0));
        s.origin = Origin.NEW;
        s.id = storeId;
        s.nextId = firstSendId;
        s.writeSnapshot(storeId, firstSendId, 0, new ArrayList<Send>());
        return s;
    }

    /** Records a send of an earlier run: accepted at {@code accepted}, its recipients in these states. */
    synchronized void seed(long sendId, String clientRef, long accepted, List<String> addresses, List<Integer> parts,
            List<String> states, List<String> reasons) throws IOException {
        List<Recipient> rs = new ArrayList<>();
        for (int i = 0; i < addresses.size(); i++) {
            Recipient r = new Recipient(addresses.get(i), parts.get(i));
            r.state = states.get(i);
            r.reason = reasons.get(i);
            if (!SENDING.equals(r.state) && !FAILED.equals(r.state)) {
                r.sentParts = r.allParts();
            }
            if (DELIVERED.equals(r.state)) {
                r.deliveredParts = r.allParts();
            }
            rs.add(r);
        }
        put(new Send(sendId, clientRef, accepted, Collections.unmodifiableList(rs)));
        writeSnapshot(id, nextId, seq, new ArrayList<>(sends.values()));
    }
}
