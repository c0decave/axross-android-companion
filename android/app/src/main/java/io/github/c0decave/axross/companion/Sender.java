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
import io.github.c0decave.axross.companion.protocol.Protocol;
import io.github.c0decave.axross.companion.protocol.RateLimiter;
import io.github.c0decave.axross.companion.protocol.RequestException;
import io.github.c0decave.axross.companion.protocol.Requests;
import io.github.c0decave.axross.companion.protocol.SendTracker;
import io.github.c0decave.axross.companion.protocol.Session;
import io.github.c0decave.axross.companion.protocol.TextRules;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * sms.send: SmsManager's multipart send, one recipient after the other,
 * with a "sent" and (when asked) a "delivered" PendingIntent per part; their
 * results come back through {@link SmsStatusReceiver} and become the
 * sending session's events. The only way the companion sends: from a
 * session's request, never from an intent. A send whose client_ref was
 * accepted within the hour is answered from the tracker and not sent again.
 */
final class Sender {
    static final String ACTION_SENT = "io.github.c0decave.axross.companion.SMS_SENT";
    static final String ACTION_DELIVERED = "io.github.c0decave.axross.companion.SMS_DELIVERED";
    static final String EXTRA_SEND = "send_id";
    static final String EXTRA_INDEX = "index";

    private final Context context;
    private final Backend backend;
    private final RateLimiter limiter = new RateLimiter(Protocol.SENDS_PER_MINUTE, Protocol.SENDS_PER_HOUR, System::currentTimeMillis);
    final SendTracker tracker = new SendTracker(System::currentTimeMillis);
    private final AtomicInteger requestCodes = new AtomicInteger();
    /** What the companion has to store itself when it is the default SMS app. */
    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();

    private static final class Pending {
        final List<String> addresses;
        final String text;
        final int subId;

        Pending(List<String> addresses, String text, int subId) {
            this.addresses = addresses;
            this.text = text;
            this.subId = subId;
        }
    }

    Sender(Context context, Backend backend) {
        this.context = context;
        this.backend = backend;
    }

    void send(Session.Call call, Requests.SmsSend req) throws RequestException {
        Map<String, Object> earlier = tracker.duplicate(req.clientRef);
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
        // (the client_ref looked up again, the rate limit and the
        // registration in one step: two sends with the same client_ref at
        // once make one send)
        Map<String, Object> result = tracker.accept(call.session().number, req.clientRef, req.recipients, counts, limiter);
        if (SendTracker.isDuplicate(result)) {
            answerDuplicate(call, result);
            return;
        }
        long sendId = (Long) result.get("send_id");
        if (Perms.isDefaultSmsApp(context)) {
            pending.put(sendId, new Pending(req.recipients, req.text, req.subId));
        }
        // (the answer first: the events follow it)
        call.ok(result);
        L.i("session " + call.session().number + ": sending " + req.recipients.size() + " x " + parts.size() + " parts");
        for (int i = 0; i < req.recipients.size(); i++) {
            ArrayList<PendingIntent> sent = new ArrayList<>();
            ArrayList<PendingIntent> delivered = req.deliveryReport ? new ArrayList<PendingIntent>() : null;
            for (int p = 0; p < parts.size(); p++) {
                sent.add(intent(ACTION_SENT, sendId, i, p, false));
                if (delivered != null) {
                    delivered.add(intent(ACTION_DELIVERED, sendId, i, p, true));
                }
            }
            try {
                mgr.sendMultipartTextMessage(req.recipients.get(i), null, parts, sent, delivered);
            } catch (RuntimeException e) {
                L.w("sendMultipartTextMessage failed", e);
                onSent(sendId, i, false, SmsManager.RESULT_ERROR_GENERIC_FAILURE);
            }
        }
    }

    /** The first send's result again; its events went to its own session. */
    private static void answerDuplicate(Session.Call call, Map<String, Object> result) {
        L.i("session " + call.session().number + ": a send asked for again (known client_ref): not sent again");
        call.ok(result);
    }

    private PendingIntent intent(String action, long sendId, int index, int part, boolean mutable) {
        // (a URI of its own for each part: PendingIntents that differ only in
        // their extras would be the same one)
        Intent i = new Intent(action, Uri.parse("axross-companion://sms/" + sendId + "/" + index + "/" + part),
                context, SmsStatusReceiver.class)
                .putExtra(EXTRA_SEND, sendId)
                .putExtra(EXTRA_INDEX, index);
        // (the delivery report's PDU is filled in by the system: mutable, which
        // an explicit intent to our own receiver may be)
        int flags = PendingIntent.FLAG_ONE_SHOT;
        if (Build.VERSION.SDK_INT >= 31) {
            flags |= mutable ? PendingIntent.FLAG_MUTABLE : PendingIntent.FLAG_IMMUTABLE;
        } else if (!mutable) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(context, requestCodes.incrementAndGet(), i, flags);
    }

    @SuppressWarnings("deprecation")
    private SmsManager manager(int subId) {
        if (Build.VERSION.SDK_INT >= 31) {
            SmsManager m = context.getSystemService(SmsManager.class);
            return m == null || subId < 0 ? m : m.createForSubscriptionId(subId);
        }
        return subId < 0 ? SmsManager.getDefault() : SmsManager.getSmsManagerForSubscriptionId(subId);
    }

    void onSent(long sendId, int index, boolean ok, int resultCode) {
        SendTracker.Event e = tracker.onSent(sendId, index, ok, resultCode);
        if (e == null) {
            return;
        }
        Pending p = pending.get(sendId);
        if (p != null && index < p.addresses.size()) {
            backend.store().storeSent(p.addresses.get(index), p.text, p.subId, !ok);
        }
        backend.toSession(e.sessionId, e.name, e.data);
    }

    void onDelivered(long sendId, int index, String status) {
        SendTracker.Event e = tracker.onDelivered(sendId, index, status);
        if (e != null) {
            backend.toSession(e.sessionId, e.name, e.data);
        }
    }
}
