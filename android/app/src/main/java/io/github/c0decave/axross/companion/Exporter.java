package io.github.c0decave.axross.companion;

import android.content.Context;
import android.database.Cursor;
import io.github.c0decave.axross.companion.protocol.ErrorCode;
import io.github.c0decave.axross.companion.protocol.Exports;
import io.github.c0decave.axross.companion.protocol.Json;
import io.github.c0decave.axross.companion.protocol.MessageOrder;
import io.github.c0decave.axross.companion.protocol.Protocol;
import io.github.c0decave.axross.companion.protocol.RequestException;
import io.github.c0decave.axross.companion.protocol.Requests;
import io.github.c0decave.axross.companion.protocol.Session;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * backup.export: every SMS and MMS, oldest first, streamed as events while
 * they are read. The events wait for the client (Session.event with wait):
 * a slow client slows the export, nothing piles up on the phone. With
 * {@code window}, at most that many backup.items are out before backup.ack
 * acknowledges them; the export waits on its own thread, the session goes
 * on answering (docs/PROTOCOL.md, "backup.export").
 */
final class Exporter {
    static final int SMS_PER_EVENT = 500;
    static final int MMS_PER_EVENT = 20;
    /** Blob bytes in one event at most (then the event goes out early). */
    static final int BLOB_BYTES_PER_EVENT = 16 * 1024 * 1024;

    private final Context context;
    private final SmsStore store;
    private final Exports exports = new Exports();

    Exporter(Context context, SmsStore store) {
        this.context = context;
        this.store = store;
    }

    void cancel(Session.Call call, long exportId) throws RequestException {
        exports.cancel(call.session(), exportId, () -> call.ok(Json.obj()));
    }

    void ack(final Session.Call call, Requests.Ack req) throws RequestException {
        exports.ack(call.session(), req.exportId, req.seq, () -> call.ok(Json.obj()));
    }

    void closed(Session session) {
        exports.closed(session);
    }

    /** Thrown inside an export: stop (cancelled, or the session is gone). */
    private static final class Stop extends Exception {
        final boolean cancelled;

        Stop(boolean cancelled) {
            super(null, null, false, false);
            this.cancelled = cancelled;
        }
    }

    void export(Session.Call call, Requests.Export req) throws RequestException {
        Perms.require(context, "READ_SMS");
        Session session = call.session();
        Exports.Export export = exports.start(session, req.window);
        long exportId = export.id;
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
                long smsDone = 0;
                long mmsDone = 0;
                long blobs = 0;
                try {
                    List<Object> batch = new ArrayList<>();
                    while (!export.cancelled() && sms.moveToNext()) {
                        batch.add(SmsStore.sms(sms));
                        if (batch.size() >= SMS_PER_EVENT) {
                            items(session, export, batch, null);
                            smsDone += batch.size();
                            batch = new ArrayList<>();
                        }
                    }
                    if (!batch.isEmpty()) {
                        items(session, export, batch, null);
                        smsDone += batch.size();
                        batch = new ArrayList<>();
                    }
                    List<byte[]> frames = new ArrayList<>();
                    long frameBytes = 0;
                    while (mms != null && !export.cancelled() && mms.moveToNext()) {
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
                            items(session, export, batch, frames);
                            mmsDone += batch.size();
                            batch = new ArrayList<>();
                            frames = new ArrayList<>();
                            frameBytes = 0;
                        }
                    }
                    if (!batch.isEmpty()) {
                        items(session, export, batch, frames);
                        mmsDone += batch.size();
                    }
                } catch (Stop stop) {
                    if (!stop.cancelled) {
                        return;
                    }
                }
                if (export.cancelled()) {
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
            exports.finish(export);
        }
    }

    /** One backup.items event, when the window has room; its blobs first. */
    private static void items(Session session, Exports.Export export, List<Object> batch, List<byte[]> frames) throws Stop {
        long seq = export.next(session);
        if (seq < 0) {
            throw new Stop(export.cancelled());
        }
        if (!session.event("backup.items", Json.obj("export_id", export.id, "seq", seq, "items", batch), frames, true)) {
            throw new Stop(false);
        }
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
