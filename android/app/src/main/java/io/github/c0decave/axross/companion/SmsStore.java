package io.github.c0decave.axross.companion;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import io.github.c0decave.axross.companion.protocol.ErrorCode;
import io.github.c0decave.axross.companion.protocol.Json;
import io.github.c0decave.axross.companion.protocol.MessageOrder;
import io.github.c0decave.axross.companion.protocol.Protocol;
import io.github.c0decave.axross.companion.protocol.RequestException;
import io.github.c0decave.axross.companion.protocol.Requests;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Android's SMS/MMS store (the Telephony provider), read and - as the
 * default SMS app only - written, in the protocol's objects
 * (docs/PROTOCOL.md, section 6).
 */
final class SmsStore {
    static final Uri SMS = Uri.parse("content://sms");
    static final Uri MMS = Uri.parse("content://mms");
    static final Uri MMS_PART = Uri.parse("content://mms/part");
    static final Uri THREADS_SIMPLE = Uri.parse("content://mms-sms/conversations?simple=true");
    static final Uri CONVERSATIONS = Uri.parse("content://mms-sms/conversations");
    static final Uri CANONICAL = Uri.parse("content://mms-sms/canonical-addresses");
    static final Uri SMS_CONVERSATIONS = Uri.parse("content://sms/conversations");

    /**
     * Rows are read with every column (a null projection) and the columns by
     * name: vendors' providers differ, and a projection naming a column one
     * of them lacks would fail the whole query.
     */
    static final String[] ALL_COLUMNS = null;

    private final Context context;
    private final ContentResolver cr;
    private final Contacts contacts;

    SmsStore(Context context, Contacts contacts) {
        this.context = context;
        this.cr = context.getContentResolver();
        this.contacts = contacts;
    }

    private Cursor query(Uri uri, String[] projection, MessageOrder.Selection sel, String sort) throws RequestException {
        try {
            Cursor c = cr.query(uri, projection, sel == null ? null : sel.where, sel == null ? null : sel.argsArray(), sort);
            if (c == null) {
                throw new RequestException(ErrorCode.INTERNAL, "the message store did not answer");
            }
            return c;
        } catch (SecurityException e) {
            throw RequestException.permission("READ_SMS");
        }
    }

    // -- threads ----------------------------------------------------------------------------

    Map<String, Object> threads(int offset, int limit) throws RequestException {
        Map<Long, Long> unread = unreadByThread();
        Map<Long, String> canonical = canonicalAddresses();
        Map<String, Contacts.Match> names = new HashMap<>();
        List<Object> out = new ArrayList<>();
        boolean more;
        Cursor c;
        try {
            c = query(THREADS_SIMPLE, null, null, "date DESC");
        } catch (RequestException e) {
            throw e;
        } catch (RuntimeException e) {
            // (some vendors' providers do not have the simple query)
            L.w("the simple threads query failed; using the SMS conversations", e);
            return threadsFallback(offset, limit, unread, names);
        }
        try {
            int count = c.getCount();
            more = count > offset + limit;
            if (offset < count && c.moveToPosition(offset)) {
                do {
                    long id = getLong(c, "_id", 0);
                    Map<String, Object> t = Json.obj(
                            "id", id,
                            "date", getLong(c, "date", 0),
                            "message_count", getLong(c, "message_count", 0),
                            "unread_count", unread.containsKey(id) ? unread.get(id) : 0L,
                            "snippet", getString(c, "snippet"),
                            "read", getLong(c, "read", 1) != 0,
                            "has_attachment", getLong(c, "has_attachment", 0) != 0,
                            "error", getLong(c, "error", 0) != 0,
                            "archived", getLong(c, "archived", 0) != 0);
                    List<Object> recipients = new ArrayList<>();
                    String ids = getString(c, "recipient_ids");
                    if (ids != null) {
                        for (String rid : ids.trim().split("\\s+")) {
                            try {
                                String address = canonical.get(Long.parseLong(rid));
                                if (address != null) {
                                    recipients.add(recipient(address, names));
                                }
                            } catch (NumberFormatException ignored) {
                                // (not an id)
                            }
                        }
                    }
                    t.put("recipients", recipients);
                    out.add(t);
                } while (out.size() < limit && c.moveToNext());
            }
        } finally {
            c.close();
        }
        return Json.obj("threads", out, "next_offset", more ? (Object) (long) (offset + limit) : Json.NULL);
    }

    private Map<String, Object> threadsFallback(int offset, int limit, Map<Long, Long> unread,
            Map<String, Contacts.Match> names) throws RequestException {
        List<Object> out = new ArrayList<>();
        boolean more;
        Cursor c = query(SMS_CONVERSATIONS, null, null, "date DESC");
        try {
            int count = c.getCount();
            more = count > offset + limit;
            if (offset < count && c.moveToPosition(offset)) {
                do {
                    long id = getLong(c, "thread_id", 0);
                    long date = 0;
                    List<Object> recipients = new ArrayList<>();
                    Cursor last = query(SMS, new String[] {"address", "date"},
                            MessageOrder.none().and("thread_id = ?", Long.toString(id)), "date DESC");
                    try {
                        if (last.moveToFirst()) {
                            date = getLong(last, "date", 0);
                            String address = getString(last, "address");
                            if (address != null) {
                                recipients.add(recipient(address, names));
                            }
                        }
                    } finally {
                        last.close();
                    }
                    long u = unread.containsKey(id) ? unread.get(id) : 0L;
                    out.add(Json.obj("id", id, "date", date, "message_count", getLong(c, "msg_count", 0),
                            "unread_count", u, "snippet", getString(c, "snippet"), "read", u == 0,
                            "has_attachment", false, "error", false, "archived", false, "recipients", recipients));
                } while (out.size() < limit && c.moveToNext());
            }
        } finally {
            c.close();
        }
        return Json.obj("threads", out, "next_offset", more ? (Object) (long) (offset + limit) : Json.NULL);
    }

    private Map<String, Object> recipient(String address, Map<String, Contacts.Match> names) {
        Contacts.Match m = names.get(address);
        if (m == null && !names.containsKey(address)) {
            m = contacts.lookup(address);
            names.put(address, m);
        }
        return Json.obj("address", address, "contact_id", m == null ? null : m.id, "name", m == null ? null : m.name);
    }

    private Map<Long, Long> unreadByThread() throws RequestException {
        Map<Long, Long> out = new HashMap<>();
        countInto(out, SMS, "read = 0 AND type = 1");
        try {
            countInto(out, MMS, "read = 0 AND msg_box = 1");
        } catch (RuntimeException e) {
            L.w("unread MMS could not be counted", e);
        }
        return out;
    }

    private void countInto(Map<Long, Long> out, Uri uri, String where) throws RequestException {
        Cursor c = query(uri, new String[] {"thread_id"}, MessageOrder.none().and(where), null);
        try {
            while (c.moveToNext()) {
                long t = c.getLong(0);
                Long n = out.get(t);
                out.put(t, n == null ? 1L : n + 1);
            }
        } finally {
            c.close();
        }
    }

    private Map<Long, String> canonicalAddresses() {
        Map<Long, String> out = new HashMap<>();
        try {
            Cursor c = cr.query(CANONICAL, new String[] {"_id", "address"}, null, null, null);
            if (c != null) {
                try {
                    while (c.moveToNext()) {
                        out.put(c.getLong(0), c.getString(1));
                    }
                } finally {
                    c.close();
                }
            }
        } catch (RuntimeException e) {
            L.w("the canonical addresses could not be read", e);
        }
        return out;
    }

    // -- messages ---------------------------------------------------------------------------

    MessageOrder.Page messages(long threadId, int limit, Requests.Cursor before) throws RequestException {
        String t = Long.toString(threadId);
        List<Map<String, Object>> sms = readSms(MessageOrder.smsAfter(before).and("thread_id = ?", t), limit + 1);
        List<Map<String, Object>> mms = readMms(MessageOrder.mmsAfter(before).and("thread_id = ?", t), limit + 1);
        return MessageOrder.merge(sms, mms, limit);
    }

    MessageOrder.Page search(Requests.Search s) throws RequestException {
        String like = "%" + escapeLike(s.query) + "%";
        MessageOrder.Selection smsSel = MessageOrder.smsAfter(s.before).and("body LIKE ? ESCAPE '\\'", like);
        if (s.threadId > 0) {
            smsSel = smsSel.and("thread_id = ?", Long.toString(s.threadId));
        }
        List<Map<String, Object>> sms = readSms(smsSel, s.limit + 1);
        List<Map<String, Object>> mms = new ArrayList<>();
        Set<Long> ids = new LinkedHashSet<>();
        try {
            Cursor c = query(MMS_PART, new String[] {"mid"},
                    MessageOrder.none().and("ct = 'text/plain' AND text LIKE ? ESCAPE '\\'", like), null);
            try {
                while (c.moveToNext() && ids.size() < 1000) {
                    ids.add(c.getLong(0));
                }
            } finally {
                c.close();
            }
        } catch (RuntimeException e) {
            L.w("MMS text could not be searched", e);
        }
        if (!ids.isEmpty()) {
            StringBuilder in = new StringBuilder("_id IN (");
            boolean first = true;
            for (Long id : ids) {
                if (!first) {
                    in.append(',');
                }
                first = false;
                in.append(id.longValue());
            }
            in.append(')');
            MessageOrder.Selection mmsSel = MessageOrder.mmsAfter(s.before).and(in.toString());
            if (s.threadId > 0) {
                mmsSel = mmsSel.and("thread_id = ?", Long.toString(s.threadId));
            }
            mms = readMms(mmsSel, s.limit + 1);
        }
        return MessageOrder.merge(sms, mms, s.limit);
    }

    static String escapeLike(String q) {
        return q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    Map<String, Object> get(String kind, long id) throws RequestException {
        List<Map<String, Object>> l = Requests.KIND_SMS.equals(kind)
                ? readSms(MessageOrder.none().and("_id = ?", Long.toString(id)), 1)
                : readMms(MessageOrder.none().and("_id = ?", Long.toString(id)), 1);
        if (l.isEmpty()) {
            throw new RequestException(ErrorCode.NOT_FOUND, "no such message");
        }
        return l.get(0);
    }

    /** At most {@code max} SMS, newest first. */
    List<Map<String, Object>> readSms(MessageOrder.Selection sel, int max) throws RequestException {
        List<Map<String, Object>> out = new ArrayList<>();
        Cursor c = query(SMS, ALL_COLUMNS, sel, "date DESC, _id DESC");
        try {
            while (out.size() < max && c.moveToNext()) {
                out.add(sms(c));
            }
        } finally {
            c.close();
        }
        return out;
    }

    static Map<String, Object> sms(Cursor c) {
        String address = getString(c, "address");
        String body = getString(c, "body");
        return Json.obj(
                "kind", "sms",
                "id", getLong(c, "_id", 0),
                "thread_id", getLong(c, "thread_id", 0),
                "address", address == null ? "" : address,
                "date", getLong(c, "date", 0),
                "date_sent", getLong(c, "date_sent", 0),
                "box", Requests.boxName((int) getLong(c, "type", 0)),
                "read", getLong(c, "read", 0) != 0,
                "seen", getLong(c, "seen", 0) != 0,
                "status", status(getLong(c, "status", -1)),
                "body", body == null ? "" : body,
                "sub_id", getLong(c, "sub_id", -1),
                "locked", getLong(c, "locked", 0) != 0,
                "error_code", getLong(c, "error_code", 0));
    }

    /** The provider's TP-Status (or -1) as the protocol names it. */
    static String status(long s) {
        if (s < 0) {
            return "none";
        }
        if ((s & 0x40) != 0) {
            return "failed";
        }
        if ((s & 0x20) != 0) {
            return "pending";
        }
        return "complete";
    }

    /** At most {@code max} MMS, newest first, with their addresses, text and attachments. */
    List<Map<String, Object>> readMms(MessageOrder.Selection sel, int max) throws RequestException {
        List<Map<String, Object>> out = new ArrayList<>();
        Cursor c;
        try {
            c = query(MMS, ALL_COLUMNS, sel, "date DESC, _id DESC");
        } catch (RequestException e) {
            throw e;
        } catch (RuntimeException e) {
            // (a phone without MMS in its provider)
            L.w("MMS could not be read", e);
            return out;
        }
        try {
            while (out.size() < max && c.moveToNext()) {
                out.add(mms(c, false));
            }
        } finally {
            c.close();
        }
        return out;
    }

    Map<String, Object> mms(Cursor c, boolean withParts) throws RequestException {
        long id = getLong(c, "_id", 0);
        Map<String, Object> m = Json.obj(
                "kind", "mms",
                "id", id,
                "thread_id", getLong(c, "thread_id", 0),
                "date", getLong(c, "date", 0) * 1000,
                "date_sent", getLong(c, "date_sent", 0) * 1000,
                "box", Requests.boxName((int) getLong(c, "msg_box", 0)),
                "read", getLong(c, "read", 0) != 0,
                "seen", getLong(c, "seen", 0) != 0,
                "subject", getString(c, "sub"),
                "addresses", mmsAddresses(id));
        List<Map<String, Object>> parts = parts(id);
        StringBuilder text = new StringBuilder();
        List<Object> attachments = new ArrayList<>();
        for (Map<String, Object> p : parts) {
            String mime = (String) p.get("mime");
            if ("text/plain".equals(mime)) {
                Object t = p.get("text");
                if (t instanceof String) {
                    if (text.length() > 0) {
                        text.append('\n');
                    }
                    text.append((String) t);
                }
            } else if (!"application/smil".equals(mime)) {
                attachments.add(Json.obj("part_id", p.get("part_id"), "mime", mime, "name", p.get("name")));
            }
        }
        m.put("text", text.toString());
        m.put("attachments", attachments);
        if (withParts) {
            m.put("parts", new ArrayList<Object>(parts));
        }
        m.put("sub_id", getLong(c, "sub_id", -1));
        m.put("locked", getLong(c, "locked", 0) != 0);
        return m;
    }

    private List<Object> mmsAddresses(long id) {
        List<Object> out = new ArrayList<>();
        try {
            Cursor c = cr.query(Uri.parse("content://mms/" + id + "/addr"), new String[] {"address", "type"}, null, null, null);
            if (c == null) {
                return out;
            }
            try {
                while (c.moveToNext()) {
                    String a = c.getString(0);
                    if (a == null || "insert-address-token".equals(a)) {
                        continue;
                    }
                    out.add(Json.obj("address", a, "role", role(c.getInt(1))));
                }
            } finally {
                c.close();
            }
        } catch (RuntimeException e) {
            L.w("MMS addresses could not be read", e);
        }
        return out;
    }

    static String role(int type) {
        switch (type) {
            case 137: return "from";
            case 151: return "to";
            case 130: return "cc";
            case 129: return "bcc";
            default: return "other";
        }
    }

    /** mms.parts: every part, the text of text/plain and application/smil inline. */
    List<Map<String, Object>> parts(long mmsId) throws RequestException {
        List<Map<String, Object>> out = new ArrayList<>();
        Cursor c;
        try {
            c = query(Uri.parse("content://mms/" + mmsId + "/part"),
                    new String[] {"_id", "ct", "name", "fn", "cid", "chset", "text", "_data"}, null, "seq ASC, _id ASC");
        } catch (RequestException e) {
            throw e;
        } catch (RuntimeException e) {
            L.w("MMS parts could not be read", e);
            return out;
        }
        try {
            while (c.moveToNext()) {
                long id = getLong(c, "_id", 0);
                String mime = getString(c, "ct");
                mime = mime == null ? "application/octet-stream" : mime.toLowerCase(java.util.Locale.ROOT);
                boolean hasData = getString(c, "_data") != null;
                Object text = Json.NULL;
                if ("text/plain".equals(mime) || "application/smil".equals(mime)) {
                    String t = getString(c, "text");
                    if (t == null && hasData) {
                        byte[] b = partBytes(id, 1024 * 1024);
                        t = b == null ? null : new String(b, StandardCharsets.UTF_8);
                    }
                    text = t == null ? Json.NULL : t;
                }
                long chset = getLong(c, "chset", -1);
                out.add(Json.obj("part_id", id, "mime", mime, "name", getString(c, "name"), "filename", getString(c, "fn"),
                        "content_id", getString(c, "cid"), "charset", chset < 0 ? null : (Object) chset, "text", text,
                        "has_data", hasData));
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** One part's data and type. */
    static final class PartData {
        final byte[] data;
        final String mime;

        PartData(byte[] data, String mime) {
            this.data = data;
            this.mime = mime;
        }
    }

    PartData partData(long partId) throws RequestException {
        String mime = null;
        String text = null;
        boolean found = false;
        Cursor c = query(MMS_PART, new String[] {"ct", "text"}, MessageOrder.none().and("_id = ?", Long.toString(partId)), null);
        try {
            if (c.moveToFirst()) {
                found = true;
                mime = c.getString(0);
                text = c.getString(1);
            }
        } finally {
            c.close();
        }
        if (!found) {
            throw new RequestException(ErrorCode.NOT_FOUND, "no such part");
        }
        mime = mime == null ? "application/octet-stream" : mime.toLowerCase(java.util.Locale.ROOT);
        byte[] data = partBytes(partId, Protocol.MAX_BLOB_OUT);
        if (data == null && text != null) {
            data = text.getBytes(StandardCharsets.UTF_8);
        }
        if (data == null) {
            throw new RequestException(ErrorCode.NOT_FOUND, "the part has no data");
        }
        return new PartData(data, mime);
    }

    /** A part's bytes (at most max; too_large beyond), or null when it has none. */
    byte[] partBytes(long partId, int max) throws RequestException {
        try (InputStream in = cr.openInputStream(Uri.parse("content://mms/part/" + partId))) {
            if (in == null) {
                return null;
            }
            return readAtMost(in, max);
        } catch (java.io.FileNotFoundException e) {
            return null;
        } catch (IOException e) {
            throw new RequestException(ErrorCode.INTERNAL, "the part could not be read");
        } catch (SecurityException e) {
            throw RequestException.permission("READ_SMS");
        }
    }

    static byte[] readAtMost(InputStream in, int max) throws IOException, RequestException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (out.size() + n > max) {
                throw new RequestException(ErrorCode.TOO_LARGE, "larger than " + max + " bytes", Json.obj("max", (long) max));
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    // -- writing (the default SMS app only) ------------------------------------------------

    int markRead(Requests.Target t) throws RequestException {
        Perms.requireDefaultSmsApp(context, "mark messages read");
        ContentValues v = new ContentValues();
        v.put("read", t.read ? 1 : 0);
        if (t.read) {
            v.put("seen", 1);
        }
        int n = 0;
        try {
            if (t.isThread()) {
                String[] args = {Long.toString(t.threadId)};
                n += cr.update(SMS, v, "thread_id = ?", args);
                n += cr.update(MMS, v, "thread_id = ?", args);
            } else {
                n += cr.update(Requests.KIND_SMS.equals(t.kind) ? SMS : MMS, v, "_id = ?", new String[] {Long.toString(t.id)});
            }
        } catch (SecurityException e) {
            throw RequestException.permission("READ_SMS");
        }
        if (n == 0) {
            throw new RequestException(ErrorCode.NOT_FOUND, "no such message");
        }
        return n;
    }

    int delete(Requests.Target t) throws RequestException {
        Perms.requireDefaultSmsApp(context, "delete messages");
        int n;
        try {
            if (t.isThread()) {
                n = cr.delete(Uri.parse("content://mms-sms/conversations/" + t.threadId), null, null);
            } else {
                n = cr.delete(Uri.parse((Requests.KIND_SMS.equals(t.kind) ? "content://sms/" : "content://mms/") + t.id), null, null);
            }
        } catch (SecurityException e) {
            throw RequestException.permission("READ_SMS");
        }
        if (n == 0) {
            throw new RequestException(ErrorCode.NOT_FOUND, "no such message");
        }
        return n;
    }

    /** A restore's SMS: false when the same one is there already. */
    boolean insertIfNew(Requests.RestoreSms s) throws RequestException {
        Cursor c = query(SMS, new String[] {"_id"}, MessageOrder.none().and(
                "address = ? AND date = ? AND type = ? AND body = ?",
                s.address, Long.toString(s.date), Integer.toString(s.type), s.body), null);
        try {
            if (c.moveToFirst()) {
                return false;
            }
        } finally {
            c.close();
        }
        ContentValues v = new ContentValues();
        v.put("address", s.address);
        v.put("body", s.body);
        v.put("date", s.date);
        v.put("date_sent", s.dateSent);
        v.put("type", s.type);
        v.put("read", s.read ? 1 : 0);
        v.put("seen", s.seen ? 1 : 0);
        if (s.subId >= 0) {
            v.put("sub_id", s.subId);
        }
        Uri u = cr.insert(SMS, v);
        if (u == null) {
            throw new RequestException(ErrorCode.INTERNAL, "the message store refused an insert");
        }
        return true;
    }

    /** A sent message, stored by the companion itself when it is the default SMS app. */
    void storeSent(String address, String body, int subId, boolean failed) {
        try {
            ContentValues v = new ContentValues();
            v.put("address", address);
            v.put("body", body);
            v.put("date", System.currentTimeMillis());
            v.put("type", failed ? 5 : 2);
            v.put("read", 1);
            v.put("seen", 1);
            if (subId >= 0) {
                v.put("sub_id", subId);
            }
            cr.insert(SMS, v);
        } catch (RuntimeException e) {
            L.w("a sent message could not be stored", e);
        }
    }

    // -- cursors ----------------------------------------------------------------------------

    static long getLong(Cursor c, String column, long def) {
        int i = c.getColumnIndex(column);
        return i < 0 || c.isNull(i) ? def : c.getLong(i);
    }

    static String getString(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        return i < 0 || c.isNull(i) ? null : c.getString(i);
    }
}
