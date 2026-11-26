package io.github.c0decave.axross.companion;

import android.content.Context;
import android.database.Cursor;
import io.github.c0decave.axross.companion.protocol.ErrorCode;
import io.github.c0decave.axross.companion.protocol.Json;
import io.github.c0decave.axross.companion.protocol.MessageOrder;
import io.github.c0decave.axross.companion.protocol.Protocol;
import io.github.c0decave.axross.companion.protocol.RequestException;
import io.github.c0decave.axross.companion.protocol.Requests;
import io.github.c0decave.axross.companion.protocol.Session;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * backup.export: every SMS and MMS, oldest first, streamed as events while
 * they are read. The events wait for the client (Session.event with wait):
 * a slow client slows the export, nothing piles up on the phone.
 */
final class Exporter {
    static final int SMS_PER_EVENT = 500;
    static final int MMS_PER_EVENT = 20;
    /** Blob bytes in one event at most (then the event goes out early). */
    static final int BLOB_BYTES_PER_EVENT = 16 * 1024 * 1024;

    private final Context context;
    private final SmsStore store;
    private final AtomicLong ids = new AtomicLong();
    /** Running exports: id -> cancelled; and the session each runs for. */
    private final Map<Long, AtomicBoolean> cancelled = new ConcurrentHashMap<>();
    private final Map<Long, Long> bySession = new ConcurrentHashMap<>();

    Exporter(Context context, SmsStore store) {
        this.context = context;
        this.store = store;
    }

    void cancel(Session.Call call, long exportId) throws RequestException {
        AtomicBoolean flag = cancelled.get(exportId);
        Long owner = bySession.get(call.session().number);
        if (flag == null || owner == null || owner != exportId) {
            throw new RequestException(ErrorCode.NOT_FOUND, "no such export");
        }
        flag.set(true);
        call.ok(Json.obj());
    }

    void export(Session.Call call, Requests.Export req) throws RequestException {
        Perms.require(context, "READ_SMS");
        Session session = call.session();
        long exportId = ids.incrementAndGet();
        if (bySession.putIfAbsent(session.number, exportId) != null) {
            throw new RequestException(ErrorCode.BUSY, "an export is running in this session");
        }
        AtomicBoolean stop = new AtomicBoolean();
        cancelled.put(exportId, stop);
        try {
            MessageOrder.Selection smsSel = MessageOrder.none();
            MessageOrder.Selection mmsSel = MessageOrder.none();
            if (req.threadId > 0) {
                smsSel = smsSel.and("thread_id = ?", Long.toString(req.threadId));
                mmsSel = mmsSel.and("thread_id = ?", Long.toString(req.threadId));
            }
            Cursor sms = query(SmsStore.SMS, smsSel);
            Cursor mms = req.includeMms ? queryMms(mmsSel) : null;
            try {
                call.ok(Json.obj("export_id", exportId, "sms_count", (long) sms.getCount(),
                        "mms_count", mms == null ? 0L : (long) mms.getCount()));
                long seq = 0;
                long smsDone = 0;
                long mmsDone = 0;
                long blobs = 0;
                List<Object> batch = new ArrayList<>();
                while (!stop.get() && sms.moveToNext()) {
                    batch.add(SmsStore.sms(sms));
                    if (batch.size() >= SMS_PER_EVENT) {
                        if (!items(session, exportId, seq++, batch, null)) {
                            return;
                        }
                        smsDone += batch.size();
                        batch = new ArrayList<>();
                    }
                }
                if (!batch.isEmpty()) {
                    if (!items(session, exportId, seq++, batch, null)) {
                        return;
                    }
                    smsDone += batch.size();
                    batch = new ArrayList<>();
                }
                List<byte[]> frames = new ArrayList<>();
                long frameBytes = 0;
                while (mms != null && !stop.get() && mms.moveToNext()) {
                    Map<String, Object> m = store.mms(mms, true);
                    if (req.includeMmsData) {
                        for (Object o : (List<?>) m.get("parts")) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> part = (Map<String, Object>) o;
                            if (!Boolean.TRUE.equals(part.get("has_data"))) {
                                continue;
                            }
                            byte[] data;
                            try {
                                data = store.partBytes((Long) part.get("part_id"), Protocol.MAX_BLOB_OUT);
                            } catch (RequestException tooLarge) {
                                L.w("an MMS part is too large for the export: left out");
                                continue;
                            }
                            if (data == null) {
                                continue;
                            }
                            part.put("data", session.blob(data, (String) part.get("mime"), frames));
                            frameBytes += data.length;
                            blobs++;
                        }
                    }
                    batch.add(m);
                    if (batch.size() >= MMS_PER_EVENT || frameBytes >= BLOB_BYTES_PER_EVENT) {
                        if (!items(session, exportId, seq++, batch, frames)) {
                            return;
                        }
                        mmsDone += batch.size();
                        batch = new ArrayList<>();
                        frames = new ArrayList<>();
                        frameBytes = 0;
                    }
                }
                if (!batch.isEmpty()) {
                    if (!items(session, exportId, seq++, batch, frames)) {
                        return;
                    }
                    mmsDone += batch.size();
                }
                if (stop.get()) {
                    session.event("backup.failed", Json.obj("export_id", exportId, "code", ErrorCode.CANCELLED), null, true);
                } else {
                    session.event("backup.done", Json.obj("export_id", exportId, "sms", smsDone, "mms", mmsDone, "blobs", blobs), null, true);
                }
                L.i("session " + session.number + ": export " + exportId + " ended (" + smsDone + " SMS, " + mmsDone + " MMS)");
            } catch (RequestException | RuntimeException e) {
                L.w("export failed", e);
                session.event("backup.failed", Json.obj("export_id", exportId, "code", ErrorCode.INTERNAL), null, true);
            } finally {
                sms.close();
                if (mms != null) {
                    mms.close();
                }
            }
        } finally {
            cancelled.remove(exportId);
            bySession.remove(session.number);
        }
    }

    private static boolean items(Session session, long exportId, long seq, List<Object> batch, List<byte[]> frames) {
        return session.event("backup.items", Json.obj("export_id", exportId, "seq", seq, "items", batch), frames, true);
    }

    private Cursor query(android.net.Uri uri, MessageOrder.Selection sel) throws RequestException {
        try {
            Cursor c = context.getContentResolver().query(uri, null, sel.where, sel.argsArray(), "date ASC, _id ASC");
            if (c == null) {
                throw new RequestException(ErrorCode.INTERNAL, "the message store did not answer");
            }
            return c;
        } catch (SecurityException e) {
            throw RequestException.permission("READ_SMS");
        }
    }

    private Cursor queryMms(MessageOrder.Selection sel) {
        try {
            return query(SmsStore.MMS, sel);
        } catch (RequestException | RuntimeException e) {
            L.w("MMS left out of the export: they could not be read");
            return null;
        }
    }
}
