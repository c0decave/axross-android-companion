package io.github.c0decave.axross.companion;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * For phones where the shell may not start the service itself
 * ({@code am start-foreground-service} refused): {@code am start -n
 * <package>/io.github.c0decave.axross.companion.ShellStartActivity --es
 * token <64 hex>} starts it from here, with the token, and the activity
 * finishes at once. No screen (a translucent theme, finished in onCreate),
 * not in the launcher, not in recents. Exported only to holders of
 * android.permission.DUMP - the shell, not apps (as the service) - so that
 * no app can push the client's token out with tokens of its own.
 */
public final class ShellStartActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        String token;
        try {
            token = intent == null ? null : intent.getStringExtra(CompanionService.EXTRA_TOKEN);
        } catch (RuntimeException e) {
            // (extras that do not unparcel)
            token = null;
        }
        if (intent != null) {
            // (not kept with the activity's intent)
            intent.removeExtra(CompanionService.EXTRA_TOKEN);
        }
        Intent start = new Intent(this, CompanionService.class);
        if (token != null) {
            start.putExtra(CompanionService.EXTRA_TOKEN, token);
        }
        try {
            startForegroundService(start);
        } catch (RuntimeException e) {
            L.w("the service could not be started", e);
        }
        finish();
    }
}
