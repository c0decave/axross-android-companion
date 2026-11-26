package io.github.c0decave.axross.companion;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import io.github.c0decave.axross.companion.protocol.ErrorCode;
import io.github.c0decave.axross.companion.protocol.RequestException;
import io.github.c0decave.axross.companion.protocol.Requests;
import io.github.c0decave.axross.companion.protocol.SendTracker;
import io.github.c0decave.axross.companion.protocol.Session;
import io.github.c0decave.axross.companion.protocol.TextRules;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * sms.send: SmsManager's multipart send, one recipient after the other,
 * with a "sent" and (when asked) a "delivered" PendingIntent per part; their
 * results come back through {@link SmsStatusReceiver}, go into the send
 * store and become the sending session's events. The only way the
 * companion sends: from a session's request, never from an intent. A send
 * is written to the store before any part is handed to Android; a send
 * whose client_ref the store has is answered from it and not sent again
 * (docs/PROTOCOL.md, "sms.send").
 */
final class Sender {
    static final String ACTION_SENT = "io.github.c0decave.axross.companion.SMS_SENT";
    static final String ACTION_DELIVERED = "io.github.c0decave.axross.companion.SMS_DELIVERED";
    static final String EXTRA_STORE = "store";
    static final String EXTRA_SEND = "send_id";
    static final String EXTRA_INDEX = "index";
    static final String EXTRA_PART = "part";

    private final Context context;
    private final Sends sends;

    Sender(Context context) {
        this.context = context;
        this.sends = Sends.get(context);
    }

    /** sms.send has been parsed (the session's reader thread): its client_ref is "checking". */
    void arrived(Session.Call call, Requests.SmsSend req) {
        sends.tracker.arrive(call, req.clientRef);
    }

    void send(Session.Call call, Requests.SmsSend req) throws RequestException {
        SendTracker tracker = sends.tracker;
        Map<String, Object> earlier = tracker.resolve(call, req.clientRef);
        if (earlier != null) {
            answerDuplicate(call, earlier);
            return;
        }
        Perms.require(context, "SEND_SMS");
        if (req.subId >= 0) {
            Perms.require(context, "READ_PHONE_STATE");
            SubscriptionManager sm = context.getSystemService(SubscriptionManager.class);
            SubscriptionInfo info;
            try {
                info = sm == null ? null : sm.getActiveSubscriptionInfo(req.subId);
            } catch (SecurityException e) {
                throw RequestException.permission("READ_PHONE_STATE");
            }
            if (info == null) {
                throw new RequestException(ErrorCode.NO_SUBSCRIPTION, "no active SIM with that sub_id");
            }
        }
        SmsManager mgr = manager(req.subId);
        if (mgr == null) {
            throw new RequestException(ErrorCode.NO_SUBSCRIPTION, "no SMS service on this phone");
        }
        ArrayList<String> parts = mgr.divideMessage(req.text);
        if (parts == null || parts.isEmpty()) {
            throw new RequestException(ErrorCode.INTERNAL, "the text could not be divided");
        }
        TextRules.checkParts(parts.size());
        List<Integer> counts = new ArrayList<>();
        for (int i = 0; i < req.recipients.size(); i++) {
            counts.add(parts.size());
        }
        // (the rate limit, and the send written to the store: from here on it
        // counts as accepted, also when the process dies before it went)
        Map<String, Object> result = tracker.accept(call, req.clientRef, call.session().number, req.recipients, counts);
        long sendId = (Long) result.get("send_id");
        String store = sends.store.id();
        if (Perms.isDefaultSmsApp(context)) {
            sends.pending.put(sendId, new Sends.Pending(req.recipients, req.text, req.subId));
        }
        // (the answer first: the events follow it)
        call.ok(result);
        L.i("session " + call.session().number + ": sending " + req.recipients.size() + " x " + parts.size() + " parts");
        for (int i = 0; i < req.recipients.size(); i++) {
            ArrayList<PendingIntent> sent = new ArrayList<>();
            ArrayList<PendingIntent> delivered = req.deliveryReport ? new ArrayList<PendingIntent>() : null;
            for (int p = 0; p < parts.size(); p++) {
                sent.add(intent(ACTION_SENT, store, sendId, i, p));
                if (delivered != null) {
                    delivered.add(intent(ACTION_DELIVERED, store, sendId, i, p));
                }
            }
            try {
                mgr.sendMultipartTextMessage(req.recipients.get(i), null, parts, sent, delivered);
            } catch (RuntimeException e) {
                L.w("sendMultipartTextMessage failed", e);
                // (every part fails: the first report makes the recipient failed)
                report(context, sends, sends.tracker.onSent(store, sendId, i, 0, false, SmsManager.RESULT_ERROR_GENERIC_FAILURE),
                        sendId, i);
            }
        }
    }

    /** The first send's result again; its events went to its own session. */
    private static void answerDuplicate(Session.Call call, Map<String, Object> result) {
        L.i("session " + call.session().number + ": a send asked for again (known client_ref): not sent again");
        call.ok(result);
    }

    /**
     * A PendingIntent of its own for each part: its data URI names the store,
     * the send, the recipient and the part, and its request code is unique
     * for some million sends in a row - so that a later run never gets an
     * earlier run's FLAG_ONE_SHOT intent back (PendingIntents that differ
     * only in their extras would be the same one).
     */
    private PendingIntent intent(String action, String store, long sendId, int index, int part) {
        boolean delivery = ACTION_DELIVERED.equals(action);
        Intent i = new Intent(action, Uri.parse("axross-companion://sms/" + store + "/" + sendId + "/" + index + "/" + part),
                context, SmsStatusReceiver.class)
                .putExtra(EXTRA_STORE, store)
                .putExtra(EXTRA_SEND, sendId)
                .putExtra(EXTRA_INDEX, index)
                .putExtra(EXTRA_PART, part);
        // (the delivery report's PDU is filled in by the system: mutable, which
        // an explicit intent to our own receiver may be)
        int flags = PendingIntent.FLAG_ONE_SHOT;
        if (Build.VERSION.SDK_INT >= 31) {
            flags |= delivery ? PendingIntent.FLAG_MUTABLE : PendingIntent.FLAG_IMMUTABLE;
        } else if (!delivery) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(context, SendTracker.requestCode(sendId, index, part, delivery), i, flags);
    }

    @SuppressWarnings("deprecation")
    private SmsManager manager(int subId) {
        if (Build.VERSION.SDK_INT >= 31) {
            SmsManager m = context.getSystemService(SmsManager.class);
            return m == null || subId < 0 ? m : m.createForSubscriptionId(subId);
        }
        return subId < 0 ? SmsManager.getDefault() : SmsManager.getSmsManagerForSubscriptionId(subId);
    }

    /**
     * What a report brought (from {@link SmsStatusReceiver}, or a send that
     * failed at once): when the companion is the default SMS app, the sent
     * or failed message goes into the store; the event goes to the session
     * that sent, if it is still there.
     */
    static void report(Context context, Sends sends, SendTracker.Event e, long sendId, int index) {
        if (e == null) {
            return;
        }
        CompanionService service = CompanionService.get();
        if (!"sms.delivered".equals(e.name)) {
            Sends.Pending p = sends.pending.get(sendId);
            if (p != null && index < p.addresses.size()) {
                SmsStore smsStore = service != null ? service.backend().store() : new SmsStore(context, new Contacts(context));
                smsStore.storeSent(p.addresses.get(index), p.text, p.subId, "sms.failed".equals(e.name));
                synchronized (p) {
                    if (--p.open <= 0) {
                        sends.pending.remove(sendId);
                    }
                }
            }
        }
        if (service != null && e.sessionId >= 0) {
            service.backend().toSession(e.sessionId, e.name, e.data);
        }
    }
}
