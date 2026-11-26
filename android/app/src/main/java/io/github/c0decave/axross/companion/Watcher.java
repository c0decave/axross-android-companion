package io.github.c0decave.axross.companion;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Telephony;
import io.github.c0decave.axross.companion.protocol.Json;
import io.github.c0decave.axross.companion.protocol.MessageOrder;
import io.github.c0decave.axross.companion.protocol.RequestException;
import java.util.List;
import java.util.Map;

/**
 * New messages (docs/PROTOCOL.md, "message.received" and
 * "messages.changed"): a content observer on the SMS/MMS store, which works
 * whether or not the companion is the default SMS app, nudged by
 * SMS_RECEIVED (the default app stores a new SMS a moment after it came).
 * Each message is reported once: by its id, above the highest id seen.
 */
final class Watcher {
    private static final Uri ALL = Uri.parse("content://mms-sms/");
    private static final long DEBOUNCE_MS = 1000;

    private final Context context;
    private final Backend backend;
    private HandlerThread thread;
    private Handler handler;
    private ContentObserver observer;
    private BroadcastReceiver receiver;
    private long lastSms = -1;
    private long lastMms = -1;
    private boolean scanQueued;

    private final Runnable scan = new Runnable() {
        @Override
        public void run() {
            scanQueued = false;
            scanNow();
        }
    };

    Watcher(Context context, Backend backend) {
        this.context = context;
        this.backend = backend;
    }

    void start() {
        thread = new HandlerThread("axross-companion-watcher");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(this::baseline);
        observer = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                queueScan(DEBOUNCE_MS);
            }
        };
        try {
            context.getContentResolver().registerContentObserver(ALL, true, observer);
        } catch (RuntimeException e) {
            L.w("the message store cannot be watched", e);
        }
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                // (no data from the intent is used: the store is read)
                queueScan(1000);
                handler.postDelayed(Watcher.this::scanNow, 4000);
                handler.postDelayed(Watcher.this::scanNow, 10000);
            }
        };
        IntentFilter filter = new IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION);
        try {
            // (only the system may send it: BROADCAST_SMS)
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Manifest.permission.BROADCAST_SMS, handler, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter, Manifest.permission.BROADCAST_SMS, handler);
            }
        } catch (RuntimeException e) {
            L.w("SMS_RECEIVED could not be registered", e);
            receiver = null;
        }
    }

    void stop() {
        if (observer != null) {
            context.getContentResolver().unregisterContentObserver(observer);
        }
        if (receiver != null) {
            try {
                context.unregisterReceiver(receiver);
            } catch (RuntimeException ignored) {
                // (not registered)
            }
        }
        if (thread != null) {
            thread.quitSafely();
        }
    }

    private void queueScan(long delay) {
        if (!scanQueued) {
            scanQueued = true;
            handler.postDelayed(scan, delay);
        }
    }

    /** The highest ids now: what is there when the service starts is not new. */
    private void baseline() {
        if (!Perms.has(context, "READ_SMS")) {
            return;
        }
        lastSms = maxId(SmsStore.SMS);
        lastMms = maxId(SmsStore.MMS);
    }

    private long maxId(Uri uri) {
        try {
            Cursor c = context.getContentResolver().query(uri, new String[] {"_id"}, null, null, "_id DESC");
            if (c == null) {
                return 0;
            }
            try {
                return c.moveToFirst() ? c.getLong(0) : 0;
            } finally {
                c.close();
            }
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private void scanNow() {
        backend.broadcast("messages.changed", Json.obj());
        if (!Perms.has(context, "READ_SMS")) {
            return;
        }
        if (lastSms < 0 || lastMms < 0) {
            baseline();
            return;
        }
        if (!backend.anySubscribed("message.received")) {
            // (nobody listens: move the marks on, report nothing)
            lastSms = Math.max(lastSms, maxId(SmsStore.SMS));
            lastMms = Math.max(lastMms, maxId(SmsStore.MMS));
            return;
        }
        SmsStore store = backend.store();
        try {
            List<Map<String, Object>> sms = store.readSms(MessageOrder.none().and("_id > ?", Long.toString(lastSms)), 500);
            for (int i = sms.size() - 1; i >= 0; i--) {
                Map<String, Object> m = sms.get(i);
                lastSms = Math.max(lastSms, (Long) m.get("id"));
                if ("inbox".equals(m.get("box"))) {
                    backend.broadcast("message.received", Json.obj("message", m));
                }
            }
            // (an MMS is reported when it has been downloaded: m_type 132,
            // retrieve-conf; its notification row is not)
            List<Map<String, Object>> mms = store.readMms(MessageOrder.none().and("_id > ? AND m_type = 132", Long.toString(lastMms)), 100);
            for (int i = mms.size() - 1; i >= 0; i--) {
                Map<String, Object> m = mms.get(i);
                lastMms = Math.max(lastMms, (Long) m.get("id"));
                if ("inbox".equals(m.get("box"))) {
                    backend.broadcast("message.received", Json.obj("message", m));
                }
            }
        } catch (RequestException | RuntimeException e) {
            L.w("new messages could not be read", e);
        }
    }
}
