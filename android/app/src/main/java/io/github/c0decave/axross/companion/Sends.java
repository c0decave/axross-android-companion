package io.github.c0decave.axross.companion;

import android.content.Context;
import io.github.c0decave.axross.companion.protocol.Protocol;
import io.github.c0decave.axross.companion.protocol.RateLimiter;
import io.github.c0decave.axross.companion.protocol.SendStore;
import io.github.c0decave.axross.companion.protocol.SendTracker;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The sends, for the whole process - the service's runs and the
 * {@link SmsStatusReceiver}, which Android starts the process for when a
 * report comes while the service does not run. The store lives in the app's
 * no_backup files (never in a backup, never moved to another device).
 */
final class Sends {
    private static Sends instance;
    /** The receiver's work, in order, off the main thread. */
    static final ExecutorService REPORTS = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "axross-companion-reports");
        t.setDaemon(true);
        return t;
    });

    final SendStore store;
    final SendTracker tracker;
    /**
     * What the companion stores itself when it is the default SMS app: the
     * text, in memory only, until each recipient is sent or failed.
     */
    final Map<Long, Pending> pending = new ConcurrentHashMap<>();

    static final class Pending {
        final List<String> addresses;
        final String text;
        final int subId;
        int open;

        Pending(List<String> addresses, String text, int subId) {
            this.addresses = addresses;
            this.text = text;
            this.subId = subId;
            this.open = addresses.size();
        }
    }

    private Sends(SendStore store) {
        this.store = store;
        this.tracker = new SendTracker(store,
                new RateLimiter(Protocol.SENDS_PER_MINUTE, Protocol.SENDS_PER_HOUR, System::currentTimeMillis));
    }

    static synchronized Sends get(Context context) {
        if (instance == null) {
            Context app = context.getApplicationContext();
            SendStore store = SendStore.open(app.getNoBackupFilesDir(), System::currentTimeMillis, new SecureRandom());
            switch (store.origin()) {
                case NEW:
                    L.i("send store: new");
                    break;
                case UNREADABLE:
                    L.w("send store: unreadable, made anew (a new store id)");
                    break;
                default:
                    L.i("send store: " + store.size() + " sends kept");
                    break;
            }
            if (store.writeFailures() > 0) {
                L.w("send store: cannot be written (sends answer internal)");
            }
            instance = new Sends(store);
        }
        return instance;
    }
}
