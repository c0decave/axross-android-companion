package io.github.c0decave.axross.companion;

import android.util.Log;
import io.github.c0decave.axross.companion.protocol.Session;

/**
 * The app's log (tag "axross-companion"). Only fixed texts, method names,
 * counts and codes go in - never a message's text, a number, a name or
 * anything else read from the phone's data or from a request.
 */
public final class L {
    public static final String TAG = "axross-companion";

    /** The session's log, the same rules. */
    public static final Session.Log SESSION = new Session.Log() {
        @Override
        public void info(String line) {
            L.i(line);
        }

        @Override
        public void warn(String line) {
            L.w(line);
        }
    };

    private L() {
    }

    public static void i(String line) {
        Log.i(TAG, line);
    }

    public static void w(String line) {
        Log.w(TAG, line);
    }

    /** A warning with the exception's class only (its message may carry data). */
    public static void w(String line, Throwable t) {
        Log.w(TAG, line + ": " + t.getClass().getSimpleName());
    }
}
