package io.github.c0decave.axross.companion;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.SmsMessage;
import io.github.c0decave.axross.companion.protocol.SendTracker;

/**
 * The results of the companion's own sends (its PendingIntents; not
 * exported - nothing else can reach it). Registered in the manifest, so
 * that Android delivers them also when the service does not run (it starts
 * the process for them): each goes into the send store, and becomes the
 * sending session's event when that session is still there.
 */
public final class SmsStatusReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if (!Sender.ACTION_SENT.equals(action) && !Sender.ACTION_DELIVERED.equals(action)) {
            return;
        }
        final int code = getResultCode();
        final Context app = context.getApplicationContext();
        final String store;
        final long sendId;
        final int index;
        final int part;
        final String status;
        try {
            store = intent.getStringExtra(Sender.EXTRA_STORE);
            sendId = intent.getLongExtra(Sender.EXTRA_SEND, -1);
            index = intent.getIntExtra(Sender.EXTRA_INDEX, -1);
            part = intent.getIntExtra(Sender.EXTRA_PART, -1);
            status = Sender.ACTION_DELIVERED.equals(action) ? status(intent) : null;
        } catch (RuntimeException e) {
            // (extras that do not unparcel)
            return;
        }
        if (store == null) {
            // (an intent of 0.1.1 or older: its sends were never stored)
            return;
        }
        // (the store's write - an fsync - off the main thread, in order)
        final PendingResult pending = goAsync();
        try {
            Sends.REPORTS.execute(() -> {
                try {
                    Sends sends = Sends.get(app);
                    SendTracker.Event e = status == null
                            ? sends.tracker.onSent(store, sendId, index, part, code == Activity.RESULT_OK, code)
                            : sends.tracker.onDelivered(store, sendId, index, part, status);
                    Sender.report(app, sends, e, sendId, index);
                } catch (RuntimeException e) {
                    L.w("a send report could not be recorded", e);
                } finally {
                    pending.finish();
                }
            });
        } catch (RuntimeException rejected) {
            pending.finish();
        }
    }

    private static String status(Intent intent) {
        byte[] pdu = intent.getByteArrayExtra("pdu");
        String format = intent.getStringExtra("format");
        if (pdu == null) {
            return "complete";
        }
        try {
            SmsMessage m = SmsMessage.createFromPdu(pdu, format);
            return m == null ? "complete" : SendTracker.deliveryStatus(m.getStatus(), "3gpp2".equals(format));
        } catch (RuntimeException e) {
            return "complete";
        }
    }
}
