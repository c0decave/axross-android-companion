package io.github.c0decave.axross.companion;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;

/**
 * WAP_PUSH_DELIVER (an MMS notification): reaches the companion only while
 * it is the default SMS app. The companion cannot download MMS (the MMS is
 * not received; docs/ARCHITECTURE.md says why it is not stored for later);
 * it says so and asks the user to make their messaging app the default
 * again. Exported because Android must reach it; protected by
 * BROADCAST_WAP_PUSH (only the system holds it).
 */
public final class MmsDeliverReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION.equals(intent.getAction())) {
            return;
        }
        L.w("an MMS notification arrived while the companion is the default SMS app; it cannot download MMS");
        Notes.channels(context);
        Notes.giveDefaultBack(context, R.string.note_default_mms_arrived);
    }
}
