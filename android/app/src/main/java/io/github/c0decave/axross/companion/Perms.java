package io.github.c0decave.axross.companion;

import android.app.NotificationManager;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Telephony;
import io.github.c0decave.axross.companion.protocol.ErrorCode;
import io.github.c0decave.axross.companion.protocol.Json;
import io.github.c0decave.axross.companion.protocol.Protocol;
import io.github.c0decave.axross.companion.protocol.RequestException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The runtime permissions and the default-SMS-app role. */
public final class Perms {
    private Perms() {
    }

    /** The full name of one of Protocol.PERMISSIONS. */
    public static String full(String shortName) {
        return "android.permission." + shortName;
    }

    public static boolean has(Context c, String shortName) {
        if ("POST_NOTIFICATIONS".equals(shortName) && Build.VERSION.SDK_INT < 33) {
            // (no such runtime permission below Android 13)
            return true;
        }
        return c.checkSelfPermission(full(shortName)) == PackageManager.PERMISSION_GRANTED;
    }

    public static void require(Context c, String shortName) throws RequestException {
        if (!has(c, shortName)) {
            throw RequestException.permission(shortName);
        }
    }

    public static Map<String, Object> state(Context c) {
        Map<String, Object> m = Json.obj();
        for (String p : Protocol.PERMISSIONS) {
            m.put(p, has(c, p));
        }
        return m;
    }

    /** The permissions to ask for in the activity (those not granted). */
    public static String[] missing(Context c) {
        List<String> out = new ArrayList<>();
        for (String p : Protocol.PERMISSIONS) {
            if (!has(c, p)) {
                out.add(full(p));
            }
        }
        return out.toArray(new String[0]);
    }

    public static boolean isDefaultSmsApp(Context c) {
        if (Build.VERSION.SDK_INT >= 29) {
            RoleManager rm = c.getSystemService(RoleManager.class);
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_SMS)) {
                return rm.isRoleHeld(RoleManager.ROLE_SMS);
            }
        }
        return c.getPackageName().equals(defaultSmsPackage(c));
    }

    /** The default SMS app's package, or null. */
    public static String defaultSmsPackage(Context c) {
        try {
            return Telephony.Sms.getDefaultSmsPackage(c);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** not_default_sms_app unless the companion is the default SMS app. */
    public static void requireDefaultSmsApp(Context c, String what) throws RequestException {
        if (!isDefaultSmsApp(c)) {
            String pkg = defaultSmsPackage(c);
            throw new RequestException(ErrorCode.NOT_DEFAULT_SMS_APP,
                    "only the default SMS app may " + what, Json.obj("default_sms_package", pkg));
        }
    }

    public static boolean notificationsShown(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        return has(c, "POST_NOTIFICATIONS") && nm != null && nm.areNotificationsEnabled();
    }
}
