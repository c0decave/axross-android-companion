package io.github.c0decave.axross.companion;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.SmsMessage;
import io.github.c0decave.axross.companion.protocol.SendTracker;

/**
 * The results of the companion's own sends (its PendingIntents; not
 * exported - nothing else can reach it).
 */
public final class SmsStatusReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        CompanionService service = CompanionService.get();
        if (service == null || intent.getAction() == null) {
            // (the service is gone: nobody to tell)
            return;
        }
        long sendId = intent.getLongExtra(Sender.EXTRA_SEND, -1);
        int index = intent.getIntExtra(Sender.EXTRA_INDEX, -1);
        Sender sender = service.backend().sender();
        if (Sender.ACTION_SENT.equals(intent.getAction())) {
            int code = getResultCode();
            sender.onSent(sendId, index, code == Activity.RESULT_OK, code);
        } else if (Sender.ACTION_DELIVERED.equals(intent.getAction())) {
            sender.onDelivered(sendId, index, status(intent));
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
