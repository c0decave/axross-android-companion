package io.github.c0decave.axross.companion;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * "Respond via message" (quick replies to a call), which Android requires
 * of a default SMS app. The companion sends nothing for other apps: the
 * request is dropped. Exported because Android must reach it; protected by
 * SEND_RESPOND_VIA_MESSAGE (only the system holds it).
 */
public final class RespondService extends Service {
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        L.w("a respond-via-message request was dropped (the companion sends only for axross)");
        stopSelf(startId);
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
