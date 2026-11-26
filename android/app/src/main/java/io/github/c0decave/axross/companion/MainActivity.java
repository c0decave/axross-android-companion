package io.github.c0decave.axross.companion;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.net.Uri;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import io.github.c0decave.axross.companion.protocol.Protocol;

/**
 * The one screen: what the app is, the permissions and their state (with a
 * button to ask for the missing ones), whether it is the default SMS app,
 * whether the service listens. {@code --ez listen true} (from axross over
 * ADB) also starts the service, and hands it {@code --es token <64 hex>}
 * when given.
 */
public final class MainActivity extends Activity {
    static final String EXTRA_LISTEN = "listen";
    private static final int REQUEST_PERMISSIONS = 1;

    private TextView state;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);

        TextView about = new TextView(this);
        about.setText(R.string.about);
        about.setPadding(0, pad / 2, 0, pad);
        box.addView(about);

        state = new TextView(this);
        state.setTypeface(Typeface.MONOSPACE);
        state.setPadding(0, 0, 0, pad);
        box.addView(state);

        Button grant = new Button(this);
        grant.setText(R.string.grant);
        grant.setOnClickListener(v -> {
            String[] missing = Perms.missing(this);
            if (missing.length > 0) {
                requestPermissions(missing, REQUEST_PERMISSIONS);
            } else {
                refresh();
            }
        });
        box.addView(grant);

        Button settings = new Button(this);
        settings.setText(R.string.app_settings);
        settings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null))));
        box.addView(settings);

        Button listen = new Button(this);
        listen.setText(R.string.listen);
        listen.setOnClickListener(v -> {
            startListening(null);
            state.postDelayed(this::refresh, 500);
        });
        box.addView(listen);

        TextView privacy = new TextView(this);
        privacy.setText(R.string.privacy);
        privacy.setPadding(0, pad, 0, 0);
        box.addView(privacy);

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.addView(box);
        setContentView(scroll);

        onListenIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        onListenIntent(intent);
    }

    /** {@code --ez listen true [--es token <64 hex>]}: the token goes to the service. */
    private void onListenIntent(Intent intent) {
        boolean listen;
        String token;
        try {
            listen = intent.getBooleanExtra(EXTRA_LISTEN, false);
            token = intent.getStringExtra(CompanionService.EXTRA_TOKEN);
        } catch (RuntimeException e) {
            // (extras that do not unparcel)
            return;
        }
        // (not kept with the activity's intent)
        intent.removeExtra(CompanionService.EXTRA_TOKEN);
        if (listen) {
            startListening(token);
        }
    }

    private void startListening(String token) {
        Intent start = new Intent(this, CompanionService.class);
        if (token != null) {
            start.putExtra(CompanionService.EXTRA_TOKEN, token);
        }
        try {
            startForegroundService(start);
        } catch (RuntimeException e) {
            L.w("the service could not be started", e);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refresh();
    }

    private void refresh() {
        StringBuilder sb = new StringBuilder();
        for (String p : Protocol.PERMISSIONS) {
            sb.append(Perms.has(this, p) ? "[x] " : "[ ] ").append(p).append('\n');
        }
        sb.append('\n');
        sb.append(getString(Perms.isDefaultSmsApp(this) ? R.string.state_default : R.string.state_not_default)).append('\n');
        CompanionService service = CompanionService.get();
        if (service == null) {
            sb.append(getString(R.string.state_stopped));
        } else {
            int n = service.sessions().size();
            sb.append(n > 0 ? getResources().getQuantityString(R.plurals.state_sessions, n, n) : getString(R.string.state_listening));
        }
        sb.append('\n').append(getString(R.string.state_version, BuildConfig.VERSION_NAME, Protocol.VERSION));
        state.setText(sb.toString());
    }
}
