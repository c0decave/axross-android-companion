package io.github.c0decave.axross.companion.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The order of messages in a list (docs/PROTOCOL.md, "Cursor"): date
 * descending, then SMS before MMS at the same millisecond, then id
 * descending; and the provider queries that continue after a cursor. SMS
 * keep their dates in milliseconds, MMS in seconds.
 */
public final class MessageOrder {
    private MessageOrder() {
    }

    /** A WHERE clause with its arguments. */
    public static final class Selection {
        public final String where;
        public final List<String> args;

        Selection(String where, List<String> args) {
            this.where = where;
            this.args = args;
        }

        public String[] argsArray() {
            return args.toArray(new String[0]);
        }

        /** This selection AND another clause (with its own arguments). */
        public Selection and(String clause, String... more) {
            List<String> all = new ArrayList<>(args);
            Collections.addAll(all, more);
            String w = where == null ? clause : "(" + where + ") AND (" + clause + ")";
            return new Selection(w, all);
        }
    }

    public static Selection none() {
        return new Selection(null, new ArrayList<String>());
    }

    /** The SMS rows after the cursor (all rows when it is null). */
    public static Selection smsAfter(Requests.Cursor c) {
        List<String> args = new ArrayList<>();
        if (c == null) {
            return new Selection(null, args);
        }
        if (Requests.KIND_SMS.equals(c.kind)) {
            args.add(Long.toString(c.date));
            args.add(Long.toString(c.date));
            args.add(Long.toString(c.id));
            return new Selection("date < ? OR (date = ? AND _id < ?)", args);
        }
        // (the cursor is an MMS: the SMS of its millisecond came before it)
        args.add(Long.toString(c.date));
        return new Selection("date < ?", args);
    }

    /** The MMS rows (date in seconds) after the cursor. */
    public static Selection mmsAfter(Requests.Cursor c) {
        List<String> args = new ArrayList<>();
        if (c == null) {
            return new Selection(null, args);
        }
        // date * 1000 < D  <=>  date <= floor((D - 1) / 1000)
        long below = Math.floorDiv(c.date - 1, 1000L);
        args.add(Long.toString(below));
        if (c.date % 1000 != 0) {
            return new Selection("date <= ?", args);
        }
        long same = c.date / 1000;
        args.add(Long.toString(same));
        if (Requests.KIND_MMS.equals(c.kind)) {
            args.add(Long.toString(c.id));
            return new Selection("date <= ? OR (date = ? AND _id < ?)", args);
        }
        // (the cursor is an SMS: every MMS of its millisecond comes after it)
        return new Selection("date <= ? OR date = ?", args);
    }

    private static int rank(String kind) {
        return Requests.KIND_SMS.equals(kind) ? 1 : 0;
    }

    /** Newest first, as the protocol orders. */
    public static final Comparator<Map<String, Object>> NEWEST_FIRST = new Comparator<Map<String, Object>>() {
        @Override
        public int compare(Map<String, Object> a, Map<String, Object> b) {
            int c = Long.compare((Long) b.get("date"), (Long) a.get("date"));
            if (c != 0) {
                return c;
            }
            c = Integer.compare(rank((String) b.get("kind")), rank((String) a.get("kind")));
            if (c != 0) {
                return c;
            }
            return Long.compare((Long) b.get("id"), (Long) a.get("id"));
        }
    };

    /** A page and the cursor after it. */
    public static final class Page {
        public final List<Map<String, Object>> messages;
        public final Requests.Cursor next;

        Page(List<Map<String, Object>> messages, Requests.Cursor next) {
            this.messages = messages;
            this.next = next;
        }
    }

    /**
     * Merges SMS and MMS (each read with at most limit + 1 rows) into one
     * page of at most {@code limit}; {@code next} is set when more remain.
     */
    public static Page merge(List<Map<String, Object>> sms, List<Map<String, Object>> mms, int limit) {
        List<Map<String, Object>> all = new ArrayList<>(sms.size() + mms.size());
        all.addAll(sms);
        all.addAll(mms);
        Collections.sort(all, NEWEST_FIRST);
        if (all.size() <= limit) {
            return new Page(all, null);
        }
        List<Map<String, Object>> page = new ArrayList<>(all.subList(0, limit));
        Map<String, Object> last = page.get(limit - 1);
        return new Page(page, new Requests.Cursor((Long) last.get("date"), (String) last.get("kind"), (Long) last.get("id")));
    }
}
