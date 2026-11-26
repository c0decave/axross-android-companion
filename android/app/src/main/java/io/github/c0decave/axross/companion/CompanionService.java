package io.github.c0decave.axross.companion;

import android.app.ForegroundServiceStartNotAllowedException;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Build;
import android.os.IBinder;
import io.github.c0decave.axross.companion.protocol.Frame;
import io.github.c0decave.axross.companion.protocol.Json;
import io.github.c0decave.axross.companion.protocol.PeerPolicy;
import io.github.c0decave.axross.companion.protocol.Protocol;
import io.github.c0decave.axross.companion.protocol.Session;
import io.github.c0decave.axross.companion.protocol.Tokens;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The foreground service that listens on the abstract socket
 * "axross-companion" (docs/PROTOCOL.md, section 1). Started by axross over
 * ADB ({@code am start-foreground-service}; the service is exported only to
 * holders of android.permission.DUMP - the shell, not apps) or from the
 * app's own screen. Each start may carry a token ({@code --es token <64
 * hex>}): the service keeps the last eight in memory, and a client must
 * prove that it holds one (docs/PROTOCOL.md, section 4). It accepts a peer
 * only if it is uid 2000 or 0, shows "axross is connected" while a client
 * is attached, and stops by itself five minutes after the last client left.
 *
 * <p>Type dataSync: an ADB link meets none of connectedDevice's
 * prerequisites on Android 14+ (Bluetooth, USB device/accessory
 * permissions, network-state permissions); Android 15+ limits dataSync to
 * six hours a day ({@link #onTimeout(int, int)}).
 */
public final class CompanionService extends Service {
    static final long IDLE_STOP_MS = 5 * 60_000L;
    /** The start's extra that carries a token (never logged). */
    static final String EXTRA_TOKEN = "token";

    private static volatile CompanionService running;

    private final List<Session> sessions = new CopyOnWriteArrayList<>();
    /** The tokens the shell gave: in memory only, gone when the service stops. */
    private final Tokens tokens = new Tokens();
    private ExecutorService workers;
    private ScheduledExecutorService timers;
    private Backend backend;
    private LocalServerSocket server;
    private Thread acceptor;
    private volatile boolean stopping;
    private ScheduledFuture<?> idleStop;
    private boolean foreground;

    /** The running service, or null. */
    static CompanionService get() {
        return running;
    }

    List<Session> sessions() {
        return sessions;
    }

    Backend backend() {
        return backend;
    }

    ScheduledExecutorService timers() {
        return timers;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Notes.channels(this);
        workers = Executors.newCachedThreadPool(daemon("axross-companion-worker"));
        timers = Executors.newScheduledThreadPool(2, daemon("axross-companion-timer"));
        backend = new Backend(this);
        running = this;
        backend.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        takeToken(intent);
        if (!goForeground()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (server == null && !stopping) {
            listen();
        }
        scheduleIdleStop();
        // (not restarted by Android after being killed: axross starts it
        // again when it connects)
        return START_NOT_STICKY;
    }

    private void takeToken(Intent intent) {
        String token;
        try {
            token = intent == null ? null : intent.getStringExtra(EXTRA_TOKEN);
        } catch (RuntimeException e) {
            // (extras that do not unparcel)
            token = null;
        }
        if (token == null) {
            return;
        }
        if (tokens.add(token)) {
            L.i("a token was given (" + tokens.size() + " kept)");
        } else {
            L.w("a malformed token was ignored");
        }
    }

    /**
     * startForeground on every start: each startForegroundService (each
     * {@code am start-foreground-service} of axross) must be answered by one,
     * also when the service runs already.
     */
    private boolean goForeground() {
        Notification n = Notes.service(this, openSessions(), clientName());
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(Notes.ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(Notes.ID_SERVICE, n);
            }
            foreground = true;
            return true;
        } catch (RuntimeException e) {
            if (Build.VERSION.SDK_INT >= 31 && e instanceof ForegroundServiceStartNotAllowedException) {
                L.w("Android did not allow the foreground service now");
            } else {
                L.w("startForeground failed", e);
            }
            return false;
        }
    }

    private void listen() {
        try {
            server = new LocalServerSocket(Protocol.SOCKET_NAME);
        } catch (IOException e) {
            // (another copy - a debug and a release build - holds the name)
            L.w("the socket name is taken; is another build of the companion running?", e);
            stopSelf();
            return;
        }
        L.i("listening on localabstract:" + Protocol.SOCKET_NAME);
        acceptor = new Thread(this::acceptLoop, "axross-companion-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private void acceptLoop() {
        LocalServerSocket s = server;
        while (!stopping) {
            final LocalSocket socket;
            try {
                socket = s.accept();
            } catch (IOException e) {
                if (!stopping) {
                    L.w("accept failed", e);
                }
                break;
            }
            if (stopping) {
                closeQuietly(socket);
                break;
            }
            final int[] seen = {-1};
            int uid = PeerPolicy.admit(() -> {
                seen[0] = socket.getPeerCredentials().getUid();
                return seen[0];
            });
            if (uid < 0) {
                // (nothing read, nothing written)
                L.w("refused a connection from uid " + seen[0]);
                closeQuietly(socket);
                continue;
            }
            if (sessions.size() >= Protocol.MAX_SESSIONS) {
                L.w("refused a connection: " + Protocol.MAX_SESSIONS + " sessions already");
                refuse(socket);
                continue;
            }
            Session session = new Session(transport(socket), uid, tokens, backend, workers, timers, L.SESSION);
            sessions.add(session);
            L.i("session " + session.number + ": connected (uid " + uid + ")");
            cancelIdleStop();
            workers.execute(session::run);
            updateNotification();
        }
    }

    private void refuse(LocalSocket socket) {
        workers.execute(() -> {
            try {
                OutputStream out = socket.getOutputStream();
                out.write(Frame.json(Json.write(Json.obj("event", "session.closing",
                        "data", Json.obj("reason", "too_many_sessions")))));
                out.flush();
            } catch (IOException ignored) {
                // (gone)
            }
            closeQuietly(socket);
        });
    }

    private static Session.Transport transport(final LocalSocket socket) {
        return new Session.Transport() {
            @Override
            public InputStream input() throws IOException {
                return socket.getInputStream();
            }

            @Override
            public OutputStream output() throws IOException {
                return socket.getOutputStream();
            }

            @Override
            public void close() {
                closeQuietly(socket);
            }
        };
    }

    /** Called by the backend when a session has ended. */
    void sessionClosed(Session session) {
        if (sessions.remove(session)) {
            L.i("session " + session.number + ": ended");
        }
        updateNotification();
        if (sessions.isEmpty()) {
            scheduleIdleStop();
        }
    }

    /** After the handshake: the client's name goes into the notification. */
    void updateNotification() {
        if (stopping || !foreground) {
            return;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            try {
                nm.notify(Notes.ID_SERVICE, Notes.service(this, openSessions(), clientName()));
            } catch (SecurityException e) {
                // (no POST_NOTIFICATIONS: the service's notification is not shown)
            }
        }
    }

    private int openSessions() {
        int n = 0;
        for (Session s : sessions) {
            if (s.isOpen()) {
                n++;
            }
        }
        return n;
    }

    /** "axross" and the version the client named in its hello. */
    private String clientName() {
        for (Session s : sessions) {
            if (s.isOpen() && s.authenticated() && !s.clientVersion().isEmpty()) {
                // (a client's own words: kept short and plain for the notification)
                return "axross " + s.clientVersion().replaceAll("[^0-9A-Za-z.+-]", "");
            }
        }
        return "axross";
    }

    private synchronized void scheduleIdleStop() {
        cancelIdleStop();
        if (stopping || timers.isShutdown()) {
            return;
        }
        idleStop = timers.schedule(() -> {
            if (sessions.isEmpty()) {
                L.i("no client for five minutes: stopping");
                stopSelf();
            }
        }, IDLE_STOP_MS, TimeUnit.MILLISECONDS);
    }

    private synchronized void cancelIdleStop() {
        if (idleStop != null) {
            idleStop.cancel(false);
            idleStop = null;
        }
    }

    /** Android 15+: the daily time of a dataSync service is used up. */
    @Override
    public void onTimeout(int startId, int fgsType) {
        L.w("Android ended the service's time (dataSync limit)");
        closeAll("fgs_timeout");
        stopSelf();
    }

    /** Android 14 calls this one for short services only; handled the same. */
    @Override
    public void onTimeout(int startId) {
        closeAll("fgs_timeout");
        stopSelf();
    }

    private void closeAll(String reason) {
        for (Session s : sessions) {
            s.close(reason, true);
        }
    }

    @Override
    public void onDestroy() {
        stopping = true;
        running = null;
        closeAll("service_stopping");
        backend.stop();
        final LocalServerSocket s = server;
        server = null;
        if (s != null) {
            // (accept() does not return when its socket is closed: a
            // connection of our own wakes it, and is refused)
            Thread waker = new Thread(() -> {
                LocalSocket wake = new LocalSocket();
                try {
                    wake.connect(new LocalSocketAddress(Protocol.SOCKET_NAME));
                } catch (IOException ignored) {
                    // (nothing listens any more)
                }
                closeQuietly(wake);
                try {
                    s.close();
                } catch (IOException ignored) {
                    // (closed)
                }
            }, "axross-companion-stop");
            waker.setDaemon(true);
            waker.start();
        }
        // (the sessions' last frames get two seconds)
        timers.schedule(() -> {
            workers.shutdownNow();
            timers.shutdownNow();
        }, 3, TimeUnit.SECONDS);
        L.i("stopped");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    static void closeQuietly(LocalSocket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // (closed)
        }
    }

    private static ThreadFactory daemon(final String name) {
        final AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, name + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
