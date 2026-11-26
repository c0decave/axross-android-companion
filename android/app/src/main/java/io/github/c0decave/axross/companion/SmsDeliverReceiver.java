package io.github.c0decave.axross.companion;

import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;

/**
 * SMS_DELIVER: Android sends a new SMS here only while the companion is
 * the default SMS app (during a restore, say). The companion stores it in
 * the inbox - so that nothing is lost - and reminds the user to make their
 * messaging app the default again. Exported because Android must reach it;
 * protected by BROADCAST_SMS (only the system holds it).
 */
public final class SmsDeliverReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.SMS_DELIVER_ACTION.equals(intent.getAction())) {
            return;
        }
        SmsMessage[] parts;
        try {
            parts = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        } catch (RuntimeException e) {
            L.w("an incoming SMS could not be read", e);
            return;
        }
        if (parts == null || parts.length == 0 || parts[0] == null) {
            return;
        }
        StringBuilder body = new StringBuilder();
        for (SmsMessage p : parts) {
            if (p != null && p.getMessageBody() != null) {
                body.append(p.getMessageBody());
            }
        }
        ContentValues v = new ContentValues();
        v.put("address", parts[0].getOriginatingAddress());
        v.put("body", body.toString());
        v.put("date", System.currentTimeMillis());
        v.put("date_sent", parts[0].getTimestampMillis());
        v.put("read", 0);
        v.put("seen", 0);
        v.put("type", 1);
        int sub = intent.getIntExtra("subscription", intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", -1));
        if (sub >= 0) {
            v.put("sub_id", sub);
        }
        try {
            context.getContentResolver().insert(Telephony.Sms.Inbox.CONTENT_URI, v);
            L.i("stored an incoming SMS (the companion is the default SMS app)");
        } catch (RuntimeException e) {
            L.w("an incoming SMS could not be stored", e);
        }
        Notes.channels(context);
        Notes.giveDefaultBack(context, R.string.note_default_sms_arrived);
    }
}
