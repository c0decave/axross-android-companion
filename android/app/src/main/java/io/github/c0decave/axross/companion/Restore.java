package io.github.c0decave.axross.companion;

import android.content.Context;
import io.github.c0decave.axross.companion.protocol.ErrorCode;
import io.github.c0decave.axross.companion.protocol.Json;
import io.github.c0decave.axross.companion.protocol.RequestException;
import io.github.c0decave.axross.companion.protocol.Requests;
import io.github.c0decave.axross.companion.protocol.Session;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * backup.restore.*: one restore at a time on the phone. It starts only
 * after the phone's user said yes on the phone ({@link RestoreActivity})
 * and while the companion is the default SMS app (Android lets only that
 * app write messages). Version 1 restores SMS; MMS items are counted as
 * rejected.
 */
final class Restore {
    static final long ANSWER_MS = 5 * 60_000L;

    static final String WAITING = "waiting_for_user";
    static final String APPROVED = "approved";
    static final String DENIED = "denied";
    static final String NOT_DEFAULT = "not_default_sms_app";
    static final String EXPIRED = "expired";
    static final String ENDED = "ended";

    /** The one restore there may be. */
    static final class State {
        final long id;
        final long sessionNumber;
        final int count;
        final String source;
        String state = WAITING;
        String previousDefault;
        long inserted;
        long skipped;
        long rejected;

        State(long id, long sessionNumber, int count, String source) {
            this.id = id;
            this.sessionNumber = sessionNumber;
            this.count = count;
            this.source = source;
        }
    }

    private final Context context;
    private final Backend backend;
    private long nextId = 1;
    private State current;

    Restore(Context context, Backend backend) {
        this.context = context;
        this.backend = backend;
    }

    /** What RestoreActivity shows, or null when no restore waits. */
    synchronized State waiting(long id) {
        return current != null && current.id == id && WAITING.equals(current.state) ? current : null;
    }

    synchronized void begin(Session.Call call, Requests.RestoreBegin req) throws RequestException {
        Perms.require(context, "READ_SMS");
        if (!Perms.notificationsShown(context)) {
            // (the question is asked through a notification)
            throw RequestException.permission("POST_NOTIFICATIONS");
        }
        if (current != null && !ENDED.equals(current.state) && !DENIED.equals(current.state)
                && !EXPIRED.equals(current.state) && !NOT_DEFAULT.equals(current.state)) {
            throw new RequestException(ErrorCode.BUSY, "a restore is in progress");
        }
        final State s = new State(nextId++, call.session().number, req.smsCount, req.source);
        s.previousDefault = Perms.defaultSmsPackage(context);
        current = s;
        call.ok(Json.obj("restore_id", s.id, "state", WAITING));
        L.i("restore " + s.id + ": asking the user");
        Notes.restoreQuestion(context, s.id, s.count);
        backend.timers().schedule(() -> decide(s.id, EXPIRED), ANSWER_MS, TimeUnit.MILLISECONDS);
    }

    /** The user's answer (from RestoreActivity), or the timeout. */
    void decide(long id, String state) {
        State s;
        synchronized (this) {
            s = current;
            if (s == null || s.id != id || !WAITING.equals(s.state)) {
                return;
            }
            s.state = state;
        }
        Notes.cancelRestoreQuestion(context);
        L.i("restore " + id + ": " + state);
        backend.toSession(s.sessionNumber, "backup.restore.state", Json.obj("restore_id", id, "state", state));
    }

    private State approved(Session.Call call, long id) throws RequestException {
        State s = current;
        if (s == null || s.id != id || s.sessionNumber != call.session().number || ENDED.equals(s.state)) {
            throw new RequestException(ErrorCode.NOT_FOUND, "no such restore");
        }
        if (DENIED.equals(s.state)) {
            throw new RequestException(ErrorCode.USER_DENIED, "the phone's user said no");
        }
        if (!APPROVED.equals(s.state)) {
            throw new RequestException(ErrorCode.BAD_REQUEST, "the restore is not approved", Json.obj("field", "restore_id"));
        }
        Perms.requireDefaultSmsApp(context, "restore messages");
        return s;
    }

    synchronized void chunk(Session.Call call, Requests.RestoreChunk req) throws RequestException {
        State s = approved(call, req.restoreId);
        // (every item checked before any is written)
        List<Requests.RestoreSms> items = new ArrayList<>();
        int rejected = 0;
        for (Object item : req.items) {
            Requests.RestoreSms sms = Requests.restoreSms(item);
            if (sms == null) {
                rejected++;
            } else {
                items.add(sms);
            }
        }
        int inserted = 0;
        int skipped = 0;
        for (Requests.RestoreSms sms : items) {
            if (backend.store().insertIfNew(sms)) {
                inserted++;
            } else {
                skipped++;
            }
        }
        s.inserted += inserted;
        s.skipped += skipped;
        s.rejected += rejected;
        call.ok(Json.obj("inserted", (long) inserted, "skipped", (long) skipped, "rejected", (long) rejected));
    }

    synchronized void end(Session.Call call, long id) throws RequestException {
        State s = current;
        if (s == null || s.id != id || s.sessionNumber != call.session().number || ENDED.equals(s.state)) {
            throw new RequestException(ErrorCode.NOT_FOUND, "no such restore");
        }
        boolean wasApproved = APPROVED.equals(s.state);
        s.state = ENDED;
        L.i("restore " + id + ": ended (" + s.inserted + " inserted, " + s.skipped + " skipped, " + s.rejected + " rejected)");
        if (wasApproved && Perms.isDefaultSmsApp(context)) {
            Notes.giveDefaultBack(context, R.string.note_default_after_restore);
        }
        call.ok(Json.obj("inserted", s.inserted, "skipped", s.skipped, "rejected", s.rejected,
                "previous_default_sms_package", s.previousDefault));
    }
}
