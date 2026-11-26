package io.github.c0decave.axross.companion.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One client's connection, after the peer check: the handshake (hello and
 * auth), the requests and their answers, the events, the blobs - everything
 * of docs/PROTOCOL.md that does not touch the phone's data. Plain Java: the
 * service gives it a socket's streams, the tests a pair of pipes.
 *
 * <p>Threads: {@link #run} reads (the caller's thread); requests other than
 * hello, auth, ping, subscribe and unsubscribe run on the executor; one writer
 * thread writes, from a queue - so that a client that does not read stalls
 * nobody but itself, and is dropped after {@link Protocol#SLOW_READER_MS}.
 */
public final class Session {
    /** The connection under the session. */
    public interface Transport {
        InputStream input() throws IOException;

        OutputStream output() throws IOException;

        /** Closes the connection (and so unblocks a blocked read or write). */
        void close();
    }

    /** What the session cannot do by itself. */
    public interface Handler {
        /**
         * hello's result (without "protocol" and "auth", which the session
         * adds). Called once the client's token is known.
         */
        Map<String, Object> hello(Session session, Requests.Hello hello);

        /** The client has proved that it holds the token: requests follow. */
        default void authenticated(Session session) {
        }

        /**
         * A request has been parsed and is about to be queued for
         * {@link #handle} (on the reader thread, in the order the requests
         * came: what is noted here is there for every later request). Must
         * be quick.
         */
        default void arrived(Session session, Call call) {
        }

        /**
         * Answers one request (on an executor thread). It must answer
         * through {@code call} before it returns; it may go on afterwards
         * with events (an export does). A {@link RequestException} becomes
         * the error answer; anything else "internal".
         */
        void handle(Session session, Call call) throws Exception;

        /** The session has ended. */
        void closed(Session session);
    }

    /** Log lines: fixed texts, method names and codes only - never data. */
    public interface Log {
        void info(String line);

        void warn(String line);
    }

    /** One request being answered. */
    public final class Call {
        public final long id;
        public final String method;
        /** What {@link Requests#parse} made of the parameters. */
        public final Object value;
        private boolean answered;
        private volatile Object attachment;
        private final List<Runnable> whenAnswered = new java.util.concurrent.CopyOnWriteArrayList<>();
        private volatile RequestException failure;

        Call(long id, String method, Object value) {
            this.id = id;
            this.method = method;
            this.value = value;
        }

        public Session session() {
            return Session.this;
        }

        public void ok(Map<String, Object> result) {
            answer(null, Frame.json(Json.write(Json.obj("id", id, "ok", true, "result", result))));
        }

        /**
         * Answers with a blob: its chunks first, then the result with
         * {@code result[key] = {"blob", "size", "mime"}}.
         */
        public void okWithBlob(byte[] data, String mime, String key, Map<String, Object> result) {
            List<byte[]> frames = new ArrayList<>();
            result.put(key, blob(data, mime, frames));
            answer(frames, Frame.json(Json.write(Json.obj("id", id, "ok", true, "result", result))));
        }

        public void fail(RequestException e) {
            failure = e;
            if (!ErrorCode.NOT_DEFAULT_SMS_APP.equals(e.code) && !ErrorCode.PERMISSION_DENIED.equals(e.code)) {
                log.info("session " + number + ": " + method + " -> " + e.code);
            }
            answer(null, errorFrame(id, e));
        }

        public synchronized boolean answered() {
            return answered;
        }

        /** The handler's own state for this request (see {@link Handler#arrived}). */
        public void attach(Object o) {
            attachment = o;
        }

        public Object attachment() {
            return attachment;
        }

        /** {@code r} runs once the answer is queued (whether ok or an error). */
        public void whenAnswered(Runnable r) {
            whenAnswered.add(r);
        }

        /** The error this request was answered with, or null. */
        public RequestException failure() {
            return failure;
        }

        private void answer(List<byte[]> before, byte[] frame) {
            synchronized (this) {
                if (answered) {
                    throw new IllegalStateException("answered twice");
                }
                answered = true;
            }
            List<byte[]> group = before == null ? new ArrayList<byte[]>() : before;
            group.add(frame);
            synchronized (lock) {
                inFlight--;
            }
            outbox.put(group, true);
            for (Runnable after : whenAnswered) {
                try {
                    after.run();
                } catch (RuntimeException e) {
                    log.warn("session " + number + ": " + method + ": after the answer: " + e.getClass().getSimpleName());
                }
            }
        }
    }

    // -- state ------------------------------------------------------------------------------

    private static final AtomicLong NUMBERS = new AtomicLong();

    /** A session's number: for the log and to route a send's events back. */
    public final long number = NUMBERS.incrementAndGet();
    /** The peer's uid (as the peer check found it). */
    public final int peerUid;

    private final Transport transport;
    private final Tokens tokens;
    private final Handler handler;
    private final Executor executor;
    private final ScheduledExecutorService timers;
    private final Log log;
    private final Outbox outbox = new Outbox();
    private final IncomingBlobs blobs = new IncomingBlobs();
    private final Object lock = new Object();
    private final Set<String> subscribed = Collections.synchronizedSet(new LinkedHashSet<String>());
    private final AtomicLong nextBlobId = new AtomicLong(1);

    private int inFlight;
    private volatile boolean helloDone;
    private volatile boolean authenticated;
    /** After hello: the proof auth must bring (never logged). */
    private String expectedProof;
    private volatile boolean closed;
    private volatile String clientName = "";
    private volatile String clientVersion = "";
    private ScheduledFuture<?> handshakeTimer;

    /** {@code tokens}: the tokens the shell gave the service (hello looks the client's up). */
    public Session(Transport transport, int peerUid, Tokens tokens, Handler handler, Executor executor,
            ScheduledExecutorService timers, Log log) {
        this.transport = transport;
        this.peerUid = peerUid;
        this.tokens = tokens;
        this.handler = handler;
        this.executor = executor;
        this.timers = timers;
        this.log = log;
    }

    public boolean isOpen() {
        return !closed;
    }

    public boolean helloDone() {
        return helloDone;
    }

    /** hello and auth are done: the client holds the token. */
    public boolean authenticated() {
        return authenticated;
    }

    public String clientName() {
        return clientName;
    }

    public String clientVersion() {
        return clientVersion;
    }

    public boolean subscribedTo(String event) {
        return subscribed.contains(event);
    }

    public List<String> subscriptions() {
        synchronized (subscribed) {
            return new ArrayList<>(subscribed);
        }
    }

    /** The client's complete blob {@code id} of exactly {@code size} bytes, taken. */
    public byte[] takeBlob(long id, long size) throws RequestException {
        return blobs.take(id, size);
    }

    // -- reading ----------------------------------------------------------------------------

    /** Reads and answers until the connection ends. */
    public void run() {
        String reason = null;
        boolean notify = false;
        try {
            final OutputStream out = transport.output();
            Thread writer = new Thread(new Runnable() {
                @Override
                public void run() {
                    outbox.drain(out);
                }
            }, "axross-companion-writer-" + number);
            writer.setDaemon(true);
            writer.start();
            handshakeTimer = timers.schedule(new Runnable() {
                @Override
                public void run() {
                    if (!authenticated) {
                        close("handshake_timeout", true);
                    }
                }
            }, Protocol.HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            InputStream in = transport.input();
            Frame frame;
            while (!closed && (frame = Frame.read(in, Protocol.MAX_REQUEST_JSON)) != null) {
                if (frame.kind == Frame.KIND_BLOB) {
                    blobs.add(frame);
                } else if (frame.skipped()) {
                    outbox.put(single(errorFrame(-1, new RequestException(ErrorCode.TOO_LARGE,
                            "JSON frame too large", Json.obj("max", (long) Protocol.MAX_REQUEST_JSON)))), true);
                } else {
                    onJson(frame.text());
                }
            }
        } catch (ProtocolException e) {
            log.warn("session " + number + ": protocol error");
            reason = "protocol_error";
            notify = true;
        } catch (IOException e) {
            // (the connection ended)
        } catch (RuntimeException e) {
            log.warn("session " + number + ": internal error " + e.getClass().getSimpleName());
            reason = "protocol_error";
            notify = true;
        } finally {
            close(reason, notify);
        }
    }

    @SuppressWarnings("unchecked")
    private void onJson(String text) {
        Map<String, Object> m;
        try {
            m = Json.parseObject(text);
        } catch (Json.ParseException e) {
            reply(-1, RequestException.bad("", "not a JSON object"));
            return;
        }
        Object rawId = m.get("id");
        if (!(rawId instanceof Long) || (Long) rawId < 1 || (Long) rawId > Integer.MAX_VALUE) {
            reply(-1, RequestException.bad("id", "id must be an integer 1..2147483647"));
            return;
        }
        long id = (Long) rawId;
        Object rawMethod = m.get("method");
        if (!(rawMethod instanceof String) || ((String) rawMethod).isEmpty() || ((String) rawMethod).length() > 64) {
            reply(id, RequestException.bad("method", "method must be a string"));
            return;
        }
        String method = (String) rawMethod;
        Object rawParams = m.get("params");
        if (rawParams != null && rawParams != Json.NULL && !(rawParams instanceof Map)) {
            reply(id, RequestException.bad("params", "params must be an object"));
            return;
        }
        Params params = new Params(rawParams instanceof Map ? (Map<String, Object>) rawParams : null);

        if ("hello".equals(method)) {
            if (helloDone) {
                reply(id, RequestException.bad("method", "hello was already done"));
            } else {
                onHello(id, params);
            }
            return;
        }
        if ("auth".equals(method)) {
            if (!helloDone) {
                reply(id, RequestException.bad("method", "auth comes after hello"));
            } else if (authenticated) {
                reply(id, RequestException.bad("method", "auth was already done"));
            } else {
                onAuth(id, params);
            }
            return;
        }
        if (!authenticated) {
            reply(id, new RequestException(ErrorCode.HANDSHAKE_REQUIRED, "hello and auth come first"));
            close(null, false);
            return;
        }
        Object value;
        try {
            value = Requests.parse(method, params);
        } catch (RequestException e) {
            if (!ErrorCode.UNKNOWN_METHOD.equals(e.code)) {
                log.info("session " + number + ": " + method + " -> " + e.code);
            }
            reply(id, e);
            return;
        }
        switch (method) {
            case "ping":
                reply(id, Json.obj("time", System.currentTimeMillis()));
                return;
            case "subscribe":
                subscribed.addAll(((Requests.Events) value).events);
                reply(id, Json.obj("events", subscriptions()));
                return;
            case "unsubscribe":
                subscribed.removeAll(((Requests.Events) value).events);
                reply(id, Json.obj("events", subscriptions()));
                return;
            default:
                break;
        }
        synchronized (lock) {
            if (inFlight >= Protocol.MAX_IN_FLIGHT) {
                reply(id, new RequestException(ErrorCode.BUSY, "too many requests in flight"));
                return;
            }
            inFlight++;
        }
        final Call call = new Call(id, method, value);
        try {
            handler.arrived(this, call);
        } catch (RuntimeException e) {
            log.warn("session " + number + ": " + method + ": arrived failed: " + e.getClass().getSimpleName());
        }
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    execute(call);
                }
            });
        } catch (RuntimeException rejected) {
            call.fail(new RequestException(ErrorCode.BUSY, "the companion is stopping"));
        }
    }

    private void execute(Call call) {
        try {
            handler.handle(this, call);
            if (!call.answered()) {
                log.warn("session " + number + ": " + call.method + " gave no answer");
                call.fail(new RequestException(ErrorCode.INTERNAL, "no answer"));
            }
        } catch (RequestException e) {
            if (!call.answered()) {
                call.fail(e);
            }
        } catch (Throwable t) {
            log.warn("session " + number + ": " + call.method + " failed: " + t.getClass().getSimpleName());
            if (!call.answered()) {
                call.fail(new RequestException(ErrorCode.INTERNAL, "internal error"));
            }
        }
    }

    /**
     * hello: the version, then the token the client names. The result
     * proves that the companion holds it; auth must prove the same of the
     * client (docs/PROTOCOL.md, section 4).
     */
    private void onHello(long id, Params params) {
        Requests.Hello hello;
        try {
            hello = (Requests.Hello) Requests.parse("hello", params);
        } catch (RequestException e) {
            reply(id, e);
            if (ErrorCode.UNSUPPORTED_PROTOCOL.equals(e.code)) {
                log.info("session " + number + ": hello -> " + e.code);
                close(null, false);
            }
            return;
        }
        int version = hello.choose();
        String token = tokens.byId(hello.tokenId);
        if (token == null) {
            log.info("session " + number + ": hello -> " + ErrorCode.UNKNOWN_TOKEN);
            reply(id, new RequestException(ErrorCode.UNKNOWN_TOKEN, "the companion was not given that token"));
            close(null, false);
            return;
        }
        clientName = hello.client == null ? "" : hello.client;
        clientVersion = hello.clientVersion == null ? "" : hello.clientVersion;
        String companionNonce = Tokens.nonce();
        Map<String, Object> result = Json.obj("protocol", (long) version);
        result.putAll(handler.hello(this, hello));
        result.put("auth", Json.obj("companion_nonce", companionNonce,
                "proof", Tokens.companionProof(token, hello.clientNonce, companionNonce)));
        synchronized (lock) {
            expectedProof = Tokens.clientProof(token, companionNonce, hello.clientNonce);
        }
        helloDone = true;
        reply(id, result);
        log.info("session " + number + ": hello done (protocol " + version + ")");
    }

    /** auth: the client's proof; a wrong one closes the session. */
    private void onAuth(long id, Params params) {
        Requests.Auth auth;
        try {
            auth = (Requests.Auth) Requests.parse("auth", params);
        } catch (RequestException e) {
            reply(id, e);
            return;
        }
        String expected;
        synchronized (lock) {
            expected = expectedProof;
            expectedProof = null;
        }
        if (!Tokens.same(expected, auth.proof)) {
            log.warn("session " + number + ": auth -> " + ErrorCode.AUTH_FAILED);
            reply(id, new RequestException(ErrorCode.AUTH_FAILED, "the proof is wrong"));
            close(null, false);
            return;
        }
        authenticated = true;
        if (handshakeTimer != null) {
            handshakeTimer.cancel(false);
        }
        reply(id, Json.obj());
        log.info("session " + number + ": authenticated");
        try {
            handler.authenticated(this);
        } catch (RuntimeException e) {
            log.warn("session " + number + ": authenticated handler failed");
        }
    }

    private void reply(long id, Map<String, Object> result) {
        outbox.put(single(Frame.json(Json.write(Json.obj("id", id, "ok", true, "result", result)))), true);
    }

    private void reply(long id, RequestException e) {
        outbox.put(single(errorFrame(id, e)), true);
    }

    private static byte[] errorFrame(long id, RequestException e) {
        Map<String, Object> error = Json.obj("code", e.code, "message", e.getMessage() == null ? "" : e.getMessage());
        if (e.data != null) {
            error.put("data", e.data);
        }
        return Frame.json(Json.write(Json.obj("id", id < 0 ? Json.NULL : (Object) id, "ok", false, "error", error)));
    }

    private static List<byte[]> single(byte[] frame) {
        List<byte[]> l = new ArrayList<>(1);
        l.add(frame);
        return l;
    }

    // -- writing ----------------------------------------------------------------------------

    /**
     * A blob's chunks, appended to {@code frames}; returns the reference to
     * it ({"blob", "size", "mime"}) for the JSON that follows.
     */
    public Map<String, Object> blob(byte[] data, String mime, List<byte[]> frames) {
        long blobId = nextBlobId.getAndIncrement();
        int offset = 0;
        do {
            int count = Math.min(Frame.CHUNK_DATA, data.length - offset);
            frames.add(Frame.blobChunk(blobId, offset + count >= data.length, data, offset, count));
            offset += count;
        } while (offset < data.length);
        return Json.obj("blob", blobId, "size", (long) data.length, "mime", mime);
    }

    /**
     * Sends an event. {@code before}: frames (blobs) that go first, or null.
     * {@code wait}: block while the client is behind (an export's events;
     * slows the export down), else queue at once (a live event).
     *
     * @return false when the session is closed
     */
    public boolean event(String name, Map<String, Object> data, List<byte[]> before, boolean wait) {
        List<byte[]> group = before == null ? new ArrayList<byte[]>() : before;
        group.add(Frame.json(Json.write(Json.obj("event", name, "data", data))));
        return outbox.put(group, wait);
    }

    public boolean event(String name, Map<String, Object> data) {
        return event(name, data, null, false);
    }

    /**
     * Ends the session. {@code reason}: sent as session.closing first when
     * {@code notify} (null: no event).
     */
    public void close(String reason, boolean notify) {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
        }
        if (reason != null) {
            log.info("session " + number + ": closing (" + reason + ")");
        }
        if (handshakeTimer != null) {
            handshakeTimer.cancel(false);
        }
        byte[] last = reason != null && notify
                ? Frame.json(Json.write(Json.obj("event", "session.closing", "data", Json.obj("reason", reason))))
                : null;
        outbox.finish(last);
        // (if the writer does not get the rest out within two seconds, the
        // connection is closed under it)
        try {
            timers.schedule(new Runnable() {
                @Override
                public void run() {
                    transport.close();
                }
            }, 2, TimeUnit.SECONDS);
        } catch (RuntimeException stopping) {
            transport.close();
        }
        try {
            handler.closed(this);
        } catch (RuntimeException e) {
            log.warn("session " + number + ": close handler failed");
        }
    }

    /** The frames waiting for the writer. */
    private final class Outbox {
        /** Above this a waiting producer waits (unless the queue is empty). */
        private static final long SOFT_LIMIT = 16L * 1024 * 1024;
        /** Above this even a live event closes the session. */
        private static final long HARD_LIMIT = 96L * 1024 * 1024;

        private final ArrayDeque<List<byte[]>> queue = new ArrayDeque<>();
        private long queued;
        private boolean finished;
        private long lastProgress = System.currentTimeMillis();

        /** Queues a group of frames, written back to back. False when closed. */
        boolean put(List<byte[]> group, boolean wait) {
            long size = 0;
            for (byte[] f : group) {
                size += f.length;
            }
            boolean slow = false;
            synchronized (this) {
                if (finished) {
                    return false;
                }
                if (wait) {
                    long deadline = System.currentTimeMillis() + Protocol.SLOW_READER_MS;
                    while (!finished && queued > 0 && queued + size > SOFT_LIMIT) {
                        long left = deadline - System.currentTimeMillis();
                        if (left <= 0) {
                            slow = true;
                            break;
                        }
                        try {
                            wait(left);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return false;
                        }
                    }
                    if (finished) {
                        return false;
                    }
                } else if (queued + size > HARD_LIMIT
                        || (queued > 0 && System.currentTimeMillis() - lastProgress > Protocol.SLOW_READER_MS)) {
                    slow = true;
                }
                if (!slow) {
                    queue.addLast(group);
                    queued += size;
                    notifyAll();
                    return true;
                }
            }
            close("slow_reader", false);
            transport.close();
            return false;
        }

        /** No more frames after {@code last} (may be null); the writer closes the connection. */
        synchronized void finish(byte[] last) {
            if (last != null && !finished) {
                List<byte[]> group = new ArrayList<>(1);
                group.add(last);
                queue.addLast(group);
                queued += last.length;
            }
            finished = true;
            notifyAll();
        }

        void drain(OutputStream out) {
            try {
                while (true) {
                    List<byte[]> group;
                    synchronized (this) {
                        while (queue.isEmpty() && !finished) {
                            lastProgress = System.currentTimeMillis();
                            wait();
                        }
                        if (queue.isEmpty()) {
                            break;
                        }
                        group = queue.peekFirst();
                    }
                    long size = 0;
                    for (byte[] f : group) {
                        out.write(f);
                        size += f.length;
                    }
                    out.flush();
                    synchronized (this) {
                        queue.pollFirst();
                        queued -= size;
                        lastProgress = System.currentTimeMillis();
                        notifyAll();
                    }
                }
            } catch (IOException | InterruptedException e) {
                // (the connection is gone)
            } finally {
                synchronized (this) {
                    finished = true;
                    queue.clear();
                    queued = 0;
                    notifyAll();
                }
                transport.close();
                if (!closed) {
                    close(null, false);
                }
            }
        }
    }

    /** The client's blobs, until a request takes them (section 2). */
    private static final class IncomingBlobs {
        private final Map<Long, java.io.ByteArrayOutputStream> partial = new HashMap<>();
        private final Map<Long, byte[]> complete = new HashMap<>();
        private long pending;

        synchronized void add(Frame chunk) throws ProtocolException {
            long id = chunk.blobId();
            if (complete.containsKey(id)) {
                throw new ProtocolException("blob id reused");
            }
            int dataLength = chunk.length - Frame.BLOB_HEADER;
            java.io.ByteArrayOutputStream buf = partial.get(id);
            long size = (buf == null ? 0 : buf.size()) + (long) dataLength;
            if (size > Protocol.MAX_BLOB_IN) {
                throw new ProtocolException("blob larger than " + Protocol.MAX_BLOB_IN);
            }
            if (pending + dataLength > Protocol.MAX_BLOBS_PENDING) {
                throw new ProtocolException("too many blob bytes pending");
            }
            if (buf == null) {
                buf = new java.io.ByteArrayOutputStream();
                partial.put(id, buf);
            }
            buf.write(chunk.payload, Frame.BLOB_HEADER, dataLength);
            pending += dataLength;
            if ((chunk.blobFlags() & Frame.FLAG_LAST) != 0) {
                partial.remove(id);
                complete.put(id, buf.toByteArray());
            }
        }

        synchronized byte[] take(long id, long size) throws RequestException {
            byte[] data = complete.get(id);
            if (data == null) {
                throw RequestException.bad("blob", "no complete blob with that id");
            }
            if (data.length != size) {
                throw RequestException.bad("blob", "the blob's size differs");
            }
            complete.remove(id);
            pending -= data.length;
            return data;
        }
    }
}
