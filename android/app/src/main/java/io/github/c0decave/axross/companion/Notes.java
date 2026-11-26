package io.github.c0decave.axross.companion;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

/**
 * The notifications: the service's ("axross is connected"), a restore's
 * question, and the reminders while the companion is the default SMS app.
 * None of them shows a message's text, a number or a name.
 */
public final class Notes {
    static final String CHANNEL_CONNECTION = "connection";
    static final String CHANNEL_ASK = "ask";
    static final int ID_SERVICE = 1;
    static final int ID_RESTORE = 2;
    static final int ID_DEFAULT_APP = 3;

    private Notes() {
    }

    static void channels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        NotificationChannel connection = new NotificationChannel(CHANNEL_CONNECTION,
                c.getString(R.string.channel_connection), NotificationManager.IMPORTANCE_LOW);
        connection.setShowBadge(false);
        nm.createNotificationChannel(connection);
        NotificationChannel ask = new NotificationChannel(CHANNEL_ASK,
                c.getString(R.string.channel_ask), NotificationManager.IMPORTANCE_HIGH);
        nm.createNotificationChannel(ask);
    }

    static PendingIntent openApp(Context c) {
        Intent i = new Intent(c, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** The service's notification: waiting, or connected (with the client's name). */
    static Notification service(Context c, int sessions, String client) {
        String title = sessions > 0 ? c.getString(R.string.note_connected) : c.getString(R.string.note_waiting);
        String text = sessions > 0
                ? c.getResources().getQuantityString(R.plurals.note_sessions, sessions, sessions, client)
                : c.getString(R.string.note_waiting_text);
        Notification.Builder b = new Notification.Builder(c, CHANNEL_CONNECTION)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(openApp(c));
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    static void restoreQuestion(Context c, long restoreId, int count) {
        Intent i = new Intent(c, RestoreActivity.class)
                .putExtra(RestoreActivity.EXTRA_RESTORE_ID, restoreId)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 1, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, CHANNEL_ASK)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(c.getString(R.string.note_restore_title))
                .setContentText(c.getResources().getQuantityString(R.plurals.note_restore_text, count, count))
                .setCategory(Notification.CATEGORY_RECOMMENDATION)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();
        notify(c, ID_RESTORE, n);
    }

    static void cancelRestoreQuestion(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.cancel(ID_RESTORE);
        }
    }

    /** "Make your messaging app the default again" (text: which situation). */
    static void giveDefaultBack(Context c, int textRes) {
        Intent i = new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(c, 2, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, CHANNEL_ASK)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(c.getString(R.string.note_default_title))
                .setContentText(c.getString(textRes))
                .setStyle(new Notification.BigTextStyle().bigText(c.getString(textRes)))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();
        notify(c, ID_DEFAULT_APP, n);
    }

    private static void notify(Context c, int id, Notification n) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        try {
            nm.notify(id, n);
        } catch (SecurityException e) {
            // (POST_NOTIFICATIONS not granted)
            L.w("a notification could not be shown", e);
        }
    }
}
