package io.github.c0decave.axross.companion;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Telephony;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The phone's question before a restore (not exported: opened from the
 * companion's own notification). "Restore" first asks Android to make the
 * companion the default SMS app when it is not - only that app may write
 * messages.
 */
public final class RestoreActivity extends Activity {
    static final String EXTRA_RESTORE_ID = "restore_id";
    private static final int REQUEST_ROLE = 1;

    private long restoreId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        restoreId = getIntent().getLongExtra(EXTRA_RESTORE_ID, -1);
        Restore restore = restore();
        Restore.State s = restore == null ? null : restore.waiting(restoreId);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);
        box.setFitsSystemWindows(true);
        TextView text = new TextView(this);
        text.setTextSize(18);
        box.addView(text);
        if (s == null) {
            text.setText(R.string.restore_none);
            Button close = new Button(this);
            close.setText(R.string.close);
            close.setOnClickListener(v -> finish());
            box.addView(close);
        } else {
            String source = s.source == null || s.source.isEmpty() ? getString(R.string.restore_unknown_source) : s.source;
            text.setText(getResources().getQuantityString(R.plurals.restore_question, s.count, s.count, source));
            TextView note = new TextView(this);
            note.setText(Perms.isDefaultSmsApp(this) ? R.string.restore_note_default : R.string.restore_note_not_default);
            note.setPadding(0, pad, 0, pad);
            box.addView(note);
            LinearLayout buttons = new LinearLayout(this);
            buttons.setGravity(Gravity.END);
            Button cancel = new Button(this);
            cancel.setText(R.string.restore_cancel);
            cancel.setOnClickListener(v -> answer(Restore.DENIED));
            Button ok = new Button(this);
            ok.setText(R.string.restore_ok);
            ok.setOnClickListener(v -> approve());
            buttons.addView(cancel);
            buttons.addView(ok);
            box.addView(buttons);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        setContentView(scroll);
    }

    private static Restore restore() {
        CompanionService service = CompanionService.get();
        return service == null ? null : service.backend().restore();
    }

    private void approve() {
        if (Perms.isDefaultSmsApp(this)) {
            answer(Restore.APPROVED);
            return;
        }
        Intent ask;
        if (Build.VERSION.SDK_INT >= 29) {
            RoleManager rm = getSystemService(RoleManager.class);
            if (rm == null || !rm.isRoleAvailable(RoleManager.ROLE_SMS)) {
                answer(Restore.NOT_DEFAULT);
                return;
            }
            ask = rm.createRequestRoleIntent(RoleManager.ROLE_SMS);
        } else {
            ask = new Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                    .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, getPackageName());
        }
        try {
            startActivityForResult(ask, REQUEST_ROLE);
        } catch (RuntimeException e) {
            L.w("the default SMS app could not be asked for", e);
            answer(Restore.NOT_DEFAULT);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ROLE) {
            answer(Perms.isDefaultSmsApp(this) ? Restore.APPROVED : Restore.NOT_DEFAULT);
        }
    }

    private void answer(String state) {
        Restore restore = restore();
        if (restore != null) {
            restore.decide(restoreId, state);
        }
        finish();
    }
}
